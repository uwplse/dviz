(ns dviz.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [reagent.core :as reagent]
            [secretary.core :as secretary :include-macros true]
            [accountant.core :as accountant]
            [vomnibus.color-brewer :as cb]
            [dviz.circles :as c]
            [dviz.event-source :as event-source]
            [dviz.util :refer [remove-one paths fields-match]]
            [dviz.sim]
            [dviz.trees :as trees]
            [dviz.paxos :refer [paxos-sim]]
            [dviz.debug-client :refer [make-debugger]]
            [goog.string :as gs]
            [goog.string.format]
            [cljsjs.react-transition-group]
            [datafrisk.core :as df]
            [ajax.core :refer [GET POST]]
            [cognitect.transit :as t]
            [cljsjs.filesaverjs]
            [cljs.core.async :refer [put! take! chan <! >! close!]]
            [clojure.browser.dom :refer [get-element]]
            [alandipert.storage-atom :refer [local-storage]]
            [clojure.data :refer [diff]]
            [haslett.client :as ws]
            [haslett.format :as ws-fmt]))

;; Views
;; -------------------------

(defn translate [x y]
  (gs/format "translate(%d %d)" x y))

(defonce events (reagent/atom (event-source/event-source-static-example)))
(defonce state (reagent/atom nil))
(defonce server-positions (reagent/atom nil))
(defonce event-history (reagent/atom (trees/leaf {:state @state :events @events})))
(defonce selected-event-path (reagent/atom []))
(defonce inspect (reagent/atom nil))

(defn non-propagating-event-handler [f]
  (fn [e] (f e) (.preventDefault e) (.stopPropagation e)))

(defn add-message [m]
  (let [id (:message-id-counter (swap! state update-in [:message-id-counter] inc))
        m (merge m {:id id})]
    (swap! state update-in [:messages (:to m)] #(vec (conj % m)))))

(defn set-timeout [t]
  (let [id (:timeout-id-counter (swap! state update-in [:timeout-id-counter] inc))
        t (merge t {:id id})]
    (.log js/console (gs/format "Adding %s to %s" t (get-in @state [:timeouts (:to t)])))
    (swap! state update-in [:timeouts (:to t)] #(set (conj % t)))))

(defn clear-timeout [id t]
  (.log js/console (gs/format "Removing %s from %s" t (get-in @state [:timeouts id])))
  (swap! state update-in [:timeouts id]
         #(set (remove (partial fields-match [:to :type :body] t) %))))

(defn update-server-state [id path val]
  (swap! state (fn [s]
                 (assoc-in s (concat [:server-state id] path) val))))

(defn update-server-log [id updates]
  (swap! state update-in [:server-log id] #(vec (conj % updates))))


(defn drop-message [message]
  (swap! state update-in [:messages (:to message)] #(vec (remove-one (partial fields-match [:from :to :type :body] message) %))))

(defn index-of [l v]
  (let [i (.indexOf l v)]
    (if (>= i 0) i nil)))

(defn diff-states [old new]
  (let [[_ diffs _] (diff old new)]
    (paths diffs)))

(defonce next-event-channel (chan))

(defn handle-state-updates [id updates]
  (doseq [[path val] updates] (update-server-state id path val))
  (update-server-log id updates))

(defn next-event-loop []
  (.log js/console "starting event loop (you'd better only see this once!)")
  (go-loop []
    (let [[ev evs] (<! next-event-channel)]
      ;; process debug
      (when-let [debug (:debug ev)]
        (.log js/console (gs/format "Processing event: %s %s" debug ev)))
      ;; process reset
      (when-let [reset (:reset ev)]
        (doall (for [[k v] reset]
                 (do 
                   (swap! state assoc k v)))))
      ;; process delivered messages
      (when-let [m (:deliver-message ev)]
        (drop-message m))
      ;; process state updates
      (when-let [[id & updates] (:update-state ev)]
        (handle-state-updates id updates))
      ;; TODO unify these
      (when-let [state-updates (:update-states ev)]
        (doseq [[id updates] state-updates]
          (handle-state-updates id updates)))
      ;; process state dumps
      (when-let [state-dumps (:states ev)]
        (doseq [[id new-state] state-dumps]
          (.log js/console (gs/format "Updating server %s to state %s" id new-state))
          (let [updates (diff-states (get-in @state [:server-state id]) new-state)]
            (doseq [[path val] updates] (update-server-state id path val))
            (update-server-log id updates))))
      ;; process send messages
      (when-let [ms (:send-messages ev)]
        (doseq [m ms] (add-message m)))
      ;; process cleared timeouts
      (when-let [ts (:clear-timeouts ev)]
        (prn "clearing timeout")
        (doseq [[id t] ts] (clear-timeout id t)))
      ;; process new timeouts
      (when-let [ts (:set-timeouts ev)]
        (prn "setting timeout")
        (doseq [t ts] (set-timeout t)))
      ;; add event to history
      (let [new-event-for-history {:state @state :events evs}]
        (let [next-events (map trees/root (trees/children (trees/get-path @event-history @selected-event-path)))]
          (if-let [next-event-index (index-of next-events new-event-for-history)]
            (swap! selected-event-path conj next-event-index)
            (do
              (let [[new-event-history new-selected-event-path]
                    (trees/append-path @event-history @selected-event-path new-event-for-history)]
                (reset! event-history new-event-history)
                (reset! selected-event-path (vec new-selected-event-path)))))))
      (reset! events evs)
      (recur))))

(defn do-next-event [action]
  (event-source/next-event @events next-event-channel action))

(def server-circle (c/circle 400 300 150))

(defn server-angle [state index]
  (+ 270 (* index (/ 360 (count (:servers state))))))

(def server-colors [cb/Dark2-3 ; 1
                    cb/Dark2-3 ; 2
                    cb/Dark2-3 ; 3
                    cb/Dark2-4 ; 4
                    cb/Dark2-5 ; 5
                    cb/Dark2-6 ; 6
                    cb/Dark2-7 ; 7
                    cb/Dark2-8 ; 8
                    ])

(defn server-color [state id]
  (let [nservers (count (:servers state))
        index (.indexOf (:servers state) id)
        colors (if (< 8 nservers)
                 (nth server-colors nservers)
                 cb/Dark2-8)]
    (nth colors index)))

(def transition-group (reagent/adapt-react-class js/ReactTransitionGroup.TransitionGroup))

(defn server-position [state id]
  (if-let [pos (get @server-positions id)]
    pos
    (let [index (.indexOf (:servers state) id)
          angle (server-angle state index)]
      (c/angle server-circle angle))))

(defn message [state index message inbox-loc status static]
  (let [mouse-over (reagent/atom false)]
    (fn [state index message inbox-loc status static]
      (let [[inbox-loc-x inbox-loc-y] inbox-loc]
        [:g {:transform
             (case status
               :new (let [from-pos (server-position state (:from message))
                          to-pos (server-position state (:to message))]
                      (translate (- (:x from-pos) (- (:x to-pos) 80))
                                 (- (:y from-pos) (:y to-pos))))
               :stable (translate 5 (* index -40))
               :deleted (translate 50 0))
             :fill (server-color state (:from message))
             :stroke (server-color state (:from message))
             :style {:transition (when (not static) "transform 0.5s ease-out")}
             }
         [:rect {:width 40 :height 30
                 :on-context-menu
                 (when (not static)
                   (non-propagating-event-handler (fn [])))
                 :on-mouse-down
                 (when (not static)
                   (non-propagating-event-handler 
                    (fn [e]
                      (case (.-button e)
                        2 (reset! inspect {:x (+ inbox-loc-x 5) :y (+ inbox-loc-y (* index -40))
                                           :value (:body message)
                                           :actions (:actions message)})
                        0 (when-let [[name action] (first (:actions message))] (do-next-event action))))))}]
         [:text {:style {:pointer-events "none" :user-select "none"} :text-anchor "end"
                 :transform (translate -10 20)}
          (:type message)]]))))


(def message-wrapper
  (reagent/create-class
   {:get-initial-state (fn [] {:status  :new})
    :component-will-appear (fn [cb]
                             (this-as this
                               (reagent/replace-state this {:status :stable})
                               (cb)))
    :component-will-enter (fn [cb]
                            (this-as this
                              (reagent/replace-state this {:status :stable})
                              (cb)))
    :component-will-leave (fn [cb]
                            (this-as this
                              (reagent/replace-state this {:status :deleted})
                              (js/setTimeout cb 500)))
    :display-name "message-wrapper"
    :reagent-render
    (fn [state index m inbox-loc static]
      [message state index m inbox-loc (:status (reagent/state (reagent/current-component))) static])}))

(defn timeout [state index timeout inbox-loc status static]
  (let [mouse-over (reagent/atom false)]
    (fn [state index timeout inbox-loc status static]
      (let [[inbox-loc-x inbox-loc-y] inbox-loc]
        [:g {:transform
             (case status
               (:new :deleted) (translate 50 0)
               :stable (translate 5 (* index -40)))
             :fill (server-color state (:to timeout))
             :stroke (server-color state (:to timeout))
             :style {:transition (when (not static) "transform 0.5s ease-out")}
             }
         [:g {:on-context-menu
              (when (not static)
                (non-propagating-event-handler (fn [])))
              :on-mouse-down
              (when (not static)
                (non-propagating-event-handler 
                 (fn [e]
                   (case (.-button e)
                     2 (reset! inspect {:x (+ inbox-loc-x 5) :y (+ inbox-loc-y (* index -40))
                                        :value (:body timeout)
                                        :actions (:actions timeout)})
                     0 (when-let [[name action] (first (:actions timeout))] (do-next-event action))))))}
          [:rect {:width 40 :height 30}]
          [:text {:style {:pointer-events "none" :user-select "none"} :x 20 :y 15 :text-anchor "middle" :alignment-baseline "central"} "⌛"]]
         [:text {:style {:pointer-events "none" :user-select "none"}
                 :text-anchor "end"
                 :transform (translate -10 20)}
          (:type timeout)]]))))


(def timeout-wrapper
  (reagent/create-class
   {:get-initial-state (fn [] {:status  :new})
    :component-will-appear (fn [cb]
                             (this-as this
                               (reagent/replace-state this {:status :stable})
                               (cb)))
    :component-will-enter (fn [cb]
                            (this-as this
                              (reagent/replace-state this {:status :stable})
                              (cb)))
    :component-will-leave (fn [cb]
                            (this-as this
                              (reagent/replace-state this {:status :deleted})
                              (js/setTimeout cb 500)))
    :display-name "timeout-wrapper"
    :reagent-render
    (fn [state index t inbox-loc static]
      [timeout state index t inbox-loc (:status (reagent/state (reagent/current-component))) static])}))

(defn path-component [c]
  (if (keyword? c) (str (name c)) (str c)))

(defn server-log-entry-line [index [path val]]
  [:tspan {:x "0" :dy "-1.2em"} (gs/format "%s = %s" (clojure.string/join "." (map path-component path)) val)])

(defn component-map-indexed [el f l & args]
  (into el (map-indexed (fn [index item] (with-meta (vec (concat [f] args [index item]))
                                           {:key [index item]})) l)))

(defn server-log-entry [updates status]
  (fn [updates status]
    [:g {:transform
         (case status
           :new (translate 0 0)
           :stable (translate 0 -80))
         :style {:opacity (case status :new "1.0" :stable "0.0")
                 :transition "all 3s ease-out"
                 :transition-property "transform, opacity"
                 :pointer-events "none" :user-select "none"}}
     (component-map-indexed [:text] server-log-entry-line updates)]))

(def server-log-entry-wrapper
  (reagent/create-class
   {:get-initial-state (fn [] {:status  :new})
    :component-will-appear (fn [cb]
                             (this-as this
                               (reagent/replace-state this {:status :stable})
                               (cb)))
    :component-will-enter (fn [cb]
                            (this-as this
                              (reagent/replace-state this {:status :stable})
                              (cb)))
    :display-name "server-log-wrapper"
    :reagent-render
    (fn [upd]
      [server-log-entry upd (:status (reagent/state (reagent/current-component)))])}))

(defn timeouts-and-messages [state server-id inbox-pos static]
  (let [timeouts (get-in state [:timeouts server-id])
        messages (get-in state [:messages server-id])
        ntimeouts (count timeouts)]
    [:g
     (if static
       (doall (map-indexed (fn [index t] ^{:key t} [timeout state index t inbox-pos :stable static])
                           timeouts))
       [transition-group {:component "g"}
        (doall (map-indexed (fn [index t] ^{:key t} [timeout-wrapper state index t inbox-pos static])
                            timeouts))]
       )
     (if static
       (doall (map-indexed (fn [index m] ^{:key m}
                             [message state (+ index ntimeouts) m inbox-pos :stable static])
                           messages))
       [transition-group {:component "g"}
        (doall (map-indexed (fn [index m] ^{:key m}
                              [message-wrapper state (+ index ntimeouts) m
                               inbox-pos static])
                            messages))]
       )
     ]))

(defn server [state static index id]
  (let 
      [is-mouse-down (reagent/atom false)
       starting-mouse-x (reagent/atom nil)
       starting-mouse-y (reagent/atom nil)
       xstart-on-down (reagent/atom nil)
       ystart-on-down (reagent/atom nil)
       mouse-move-handler
       (fn [e]
         (let [x (.-clientX e)
               y (.-clientY e)]
           (swap! server-positions assoc id
                  {:x (- @xstart-on-down (- @starting-mouse-x x))
                   :y (- @ystart-on-down (- @starting-mouse-y y))})))]
    (fn [state static index id] 
      (let [pos (server-position state id)
            server-state (get-in state [:server-state id])]
        [:g {:transform (translate (:x pos) (:y pos))
             :fill (server-color state id)
             :stroke (server-color state id)}
         [:text {:style {:pointer-events "none" :user-select "none"} :x 25 :y -20 :text-anchor "middle"} id]
         [:line {:x1 -35 :x2 -35 :y1 -40 :y2 40 :stroke-dasharray "5,5"}]
         [:image {:xlinkHref "images/server.png" :x 0 :y -10 :width 50
                  :on-click (when (not static)
                              (non-propagating-event-handler #(reset! inspect {:x (:x pos) :y (:y pos)
                                                                               :value server-state})))}]
         [:line {:x1 -100 :x2 -50 :y1 0 :y2 0 :stroke-width 10}]
         [:g {:transform (translate -100 -40)}   ; inbox
          [timeouts-and-messages state id [(- (:x pos) 100) (- (:y pos) 40)] static]]
         (when (not static)
           [:g {:transform (translate 0 -40)} ; log
            [transition-group {:component "g"}
             (doall (map-indexed (fn [index upd] ^{:key index} [server-log-entry-wrapper upd])
                                 (get-in state [:server-log id])))]])
         [:circle {:fill "black" :stroke "black" :cx 25 :cy 30 :r 5
                   :on-mouse-down (fn [e]
                                    (reset! starting-mouse-x (.-clientX e))
                                    (reset! starting-mouse-y (.-clientY e))
                                    (reset! xstart-on-down (:x pos))
                                    (reset! ystart-on-down (:y pos))
                                    (.addEventListener js/document "mousemove" mouse-move-handler)
                                    (.addEventListener js/document "mouseup"
                                                       (fn [] (.removeEventListener js/document "mousemove" mouse-move-handler))))}]]))))

(defn nw-state [state static]
  (component-map-indexed [:g {:style {:background "white"}}] server (:servers state) state static))

(defn history-move [path]
  (let [{new-state :state new-events :events} (trees/root (trees/get-path @event-history path))
        ch (chan)]
    (event-source/reset new-events ch)
    (go
      (let [new-events (<! ch)]
        (reset! state new-state)
        (reset! events new-events)
        (reset! selected-event-path path)))))

(defn history-view-event-line [path [x y] event parent-position]
  [:g {:fill "black" :stroke "black"}
   (when-let [[parent-x parent-y] parent-position]
     [:line {:x1 parent-x :x2 x :y1 parent-y :y2 y :stroke-width 5 :stroke-dasharray "5,1" :style {:z-index -5}}])])

(defn history-view-event [path [x y] event parent-position inspect selected]
  [:g {:fill "black" :stroke "black"}
   [:g {:transform (translate x y)}
    [:circle {:cx 0 :cy 0 :r 20
              :on-click #(history-move path)
              :on-mouse-over #(reset! inspect [x y event])
              :on-mouse-out #(reset! inspect nil)
              :stroke (if selected "red" "black")
              :stroke-width 5
              :style {:z-index 5}}]]])

(defn history-event-inspector [inspect-event zoom xstart ystart]
  (when-let [[x y event] @inspect-event]
    [:div {:style {:position "absolute"
                   :left (/ (-  x @xstart) @zoom)
                   :bottom (+ (/ (-  y @ystart) @zoom) 100)
                   :z-index 100}}
     [:svg {:xmlnsXlink "http://www.w3.org/1999/xlink"
            :width 200 :height 150 :style {:border "1px solid black" :background-color "white"}
            :viewBox "0 0 800 600"}
      (nw-state (:state event) true)]
     ]))

(defn history-view []
  (let [xstart (reagent/atom 0)
        ystart (reagent/atom 0)
        is-mouse-down (reagent/atom false)
        starting-mouse-x (reagent/atom nil)
        starting-mouse-y (reagent/atom nil)
        xstart-on-down (reagent/atom nil)
        ystart-on-down (reagent/atom nil)
        zoom (reagent/atom 1)
        inspect-event (reagent/atom nil)]
    (fn []
      [:div {:style {:position "relative"}}
       [:svg {:xmlnsXlink "http://www.w3.org/1999/xlink"
              :width 750 :height 100
              :viewBox (gs/format "%d %d %d %d" @xstart @ystart (* 750 @zoom) (* 100 @zoom))
              :style {:border "1px solid black"}
              :on-mouse-down (fn [e]
                               (reset! is-mouse-down true)
                               (reset! starting-mouse-x (.-clientX e))
                               (reset! starting-mouse-y (.-clientY e))
                               (reset! xstart-on-down @xstart)
                               (reset! ystart-on-down @ystart)
                               true)
              :on-mouse-up (fn [] (reset! is-mouse-down false))
              :on-mouse-move (fn [e]
                               (when @is-mouse-down
                                 (let [x (.-clientX e)
                                       y (.-clientY e)]
                                   (reset! xstart (+ @xstart-on-down (- @starting-mouse-x x)))
                                   (reset! ystart (+ @ystart-on-down (- @starting-mouse-y y))))))}
        [:g {:transform (translate 50 50)}
         (let [layout (trees/layout @event-history 75 50)]
           (doall
            (concat 
             (for [{:keys [position value path parent]} layout]
               ^{:key [path value :line]} [history-view-event-line path position value parent])
             (for [{:keys [position value path parent]} layout]
               ^{:key [path value]} [history-view-event path position value parent inspect-event (= path @selected-event-path)]))))]]
       [history-event-inspector inspect-event zoom xstart ystart]
       [:button {:on-click #(swap! zoom - .1)} "+"]
       [:button {:on-click #(swap! zoom + .1)} "-"]])))

(defn next-event-button []
  (let []
    (fn [n] 
      [:button {:on-click
                (fn []
                  (do-next-event nil))}
       "Random next event"])))


(defn reset-events-button []
  (let []
    (fn [n] 
      [:button {:on-click
                (fn []
                  (reset! events (event-source/event-source-static-example)))}
       "Static events"])))

(defn paxos-events-button []
  (let []
    (fn [n] 
      [:button {:on-click
                (fn []
                  (reset! events (paxos-sim 3)))}
       "Paxos events"])))

(defn reset-server-positions-button []
  (let []
    (fn [n] 
      [:button {:on-click
                (fn []
                  (reset! server-positions nil))}
       "Reset Server Positions"])))


(defn inspector []
  (when-let [{:keys [x y value actions]} @inspect]
    [:div {:style {:position "absolute" :top y :left x
                   :border "1px solid black" :background "white"
                   :padding "10px"}}
     [:span (pr-str value)]
     [:br]
     (doall (for [[name action] actions]
              ^{:key name} [:button {:on-click (fn [] (do-next-event action))} name]))]))

(defn add-trace [trace-db name trace]
  (let [{:keys [trace servers]} trace]
    (conj trace-db {:name name :trace trace :servers servers :id (count trace-db)})))

(defn trace-upload [traces]
  (let [file (reagent/atom nil)]
    (fn [after-post]
      [:div
       (if @file
         [:div
          [:input#trace-name {:type "text"}]
          [:button {:on-click
                    (fn [] (swap! traces add-trace (.-value (get-element "trace-name"))
                                  (js->clj (.parse js/JSON @file) :keywordize-keys true)))}
           "Upload"]
          [:button {:on-click #(reset! file nil)} "Cancel"]]
         [:input {:type "file"
                  :on-change (fn [t]
                               (let [f (-> t (.-target) (.-files) (.item 0))
                                     reader (js/FileReader.)
                                     on-load (fn [e] (reset! file (-> e (.-target) (.-result))))]
                                 (set! (.-onload reader) on-load)
                                 (.readAsText reader f)))}])])))

(defn make-trace [trace servers]
  (into [{:reset {:servers servers :messages {} :server-state {}}}] trace))

(def default-traces
  [{:name "Mutex"
   :id 0
   :servers ["0" "1"]
   :trace [{:update-state [0 [["clock"] 2]] :send-messages [{:from 0 :to 1 :type "png" :body {:clock 2}}]}
           {:update-state [0 [["req" "1"] 2]] :send-messages [{:from 0 :to 1 :type "req" :body {:clock 2}}]}
           {:update-state [1 [["clock"] 2]] :send-messages [{:from 1 :to 0 :type "png" :body {:clock 2}}]}
           {:update-state [1 [["req" "2"] 2]] :send-messages [{:from 1 :to 0 :type "req" :body {:clock 2}}]}
           {:update-state [0 [["png" "2"] 2]] :deliver-message {:from 1 :to 0 :type "png" :body {:clock 2}}}
           {:update-state [0 [["crit"] true]]}
           {:update-state [1 [["png" "1"] 2]] :deliver-message {:from 0 :to 1 :type "png" :body {:clock 2}}}
           {:update-state [1 [["crit"] true]]}]}])

(defonce traces-local (local-storage (atom default-traces) :traces))

(defn download [filename content & [mime-type]]
  (let [mime-type (or mime-type (str "text/plain;charset=" (.-characterSet js/document)))
        blob (new js/Blob
                  (clj->js [content])
                  (clj->js {:type mime-type}))]
    (js/saveAs blob filename)))

(defn download-trace [trace servers]
  (let [json (.stringify js/JSON (clj->js {:trace trace :servers servers}))]
    (download "trace.json" json "application/json")))

(defn trace-display []
  (let [traces (reagent/atom @traces-local)
        fetch-traces (reset! traces @traces-local)
        expanded (reagent/atom false)
        file-channel (chan)]
    (add-watch traces-local :copy-to-traces (fn [_ _ _ v] (reset! traces v)))
    (fn []
      [:div {:style {:position "absolute" :top 5 :left 5}}
       [:a {:href "#" :on-click #(swap! expanded not)} (concat "Traces " (if @expanded "▼" "▶"))]
       (when @expanded
         [:div
          [:ul
           (doall
            (for [{:keys [name trace id servers]} @traces]
              ^{:key name} [:li
                            [:a {:href "#"
                                 :on-click #(reset! events (event-source/StaticEventSource. (make-trace trace servers)))}
                             name]
                            [:span " "]
                            [:a {:href "#" :on-click #(download-trace trace servers)} "(download)"]]))]
          [trace-upload traces-local]])])))

(defn debug-display []
  (let [st (reagent/atom nil)
        debugger (reagent/atom nil)]
    (fn []
      [:div {:style {:position "absolute" :top 5 :right 100 :text-align "right"}}
       (if (nil? @debugger)
         [:a {:href "#" :on-click #(reset! debugger (make-debugger st))}
          "Start debug server"]
         [:div {:style {:border "1px solid black"}}
          [:span "Servers: " (clojure.string/join "," (:servers @st))]
          [:br]
          (if (= (:status @st) :processing) "Processing..." "Ready")
          [:br]
          (when-not (:started @st)
            [:div
             [:a {:href "#"
                  :on-click (fn []
                              (reset! events @debugger)
                              (do-next-event {:type :start}))}
              "Debug!"]
             [:br]
             (when-let [trace (:trace @st)]
               [:a {:href "#"
                    :on-click (fn []
                                (reset! events @debugger)
                                (do-next-event {:type :trace :trace trace}))}
                "Debug trace"])])])])))

(defn home-page []
  
  (fn [] 
    [:div {:style {:position "relative"}}
     [inspector]
     [:svg {:xmlnsXlink "http://www.w3.org/1999/xlink"
            :width 800 :height 600 :style {:border "1px solid black"}
            :viewBox "0 0 800 600"
            :on-click #(reset! inspect nil)}
      (nw-state @state false)]
     [:br]
     [next-event-button]
     [reset-server-positions-button]
     ;[reset-events-button]
     ;[paxos-events-button]
     [history-view]
     [trace-display]
     [debug-display]]))

;; -------------------------
;; Routes

(def page (reagent/atom #'home-page))

(defn current-page []
  [:div [@page]])

(secretary/defroute "/" []
  (reset! page #'home-page))

;; -------------------------
;; Initialize app

(defn mount-root []
  (reagent/render [current-page] (.getElementById js/document "app")))

(defn init! []
  (next-event-loop)
  (accountant/configure-navigation!
   {:nav-handler
    (fn [path]
      (secretary/dispatch! path))
    :path-exists?
    (fn [path]
      (secretary/locate-route path))})
  (accountant/dispatch-current!)
  (mount-root))
