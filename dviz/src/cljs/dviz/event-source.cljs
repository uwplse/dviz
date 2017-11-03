(ns dviz.event-source
  (:require [cljs.core.async :refer [put! take! chan <! >! timeout close!]]))

(defprotocol IEventSource
  (next-event [this ch]
    "put the next event and a new EventSource onto the channel")
  (reset [this ch]
    "put a reset version of the event source onto the channel"))

(defrecord StaticEventSource [evs]
  IEventSource
  (next-event [this ch]
    (when-let [e (first evs)]
      (put! ch [e (StaticEventSource. (rest evs))])))
  (reset [this ch] (put! ch this)))

(defn event-source-static-example []
  (StaticEventSource. 
   [{:debug "init" :reset {:servers ["1" "2"] :server-state {0 {:clock 1} 1 {:clock 1}}}}
    {:update-state [0 [[:clock] 2]] :send-messages [{:from 0 :to 1 :type :png :body {:clock 2}}]}
    {:update-state [0 [[:req "1"] 2]] :send-messages [{:from 0 :to 1 :type :req :body {:clock 2}}]}
    {:update-state [1 [[:clock] 2]] :send-messages [{:from 1 :to 0 :type :png :body {:clock 2}}]}
    {:update-state [1 [[:req "2"] 2]] :send-messages [{:from 1 :to 0 :type :req :body {:clock 2}}]}
    {:update-state [0 [[:png "2"] 2]] :deliver-message {:from 1 :to 0 :type :png :body {:clock 2}}}
    {:update-state [0 [[:crit] true]]}
    {:update-state [1 [[:png "1"] 2]] :deliver-message {:from 0 :to 1 :type :png :body {:clock 2}}}
    {:update-state [1 [[:crit] true]]}
    ;; {:update-state [1 [[:acceptor :status] "ACCEPT"] [[:acceptor :bal] 1]]}
    ;; {:debug "1" :send-messages [{:from 0 :to 1 :type "p1a" :body {:bal 1 :id 0}}]}
    ;; {:debug "2" :send-messages [{:from 0 :to 1 :type "p1a" :body {:bal 1 :id 0}}]}
    ;; {:debug "3" :deliver-message {:from 0 :to 1 :type "p1a" :body {:bal 1 :id 0}}}
    ;; {:debug "4" :send-messages [{:from 0 :to 1 :type "p1a" :body {:bal 1 :id 0}}]}
    ;; {:debug "5" :send-messages [{:from 0 :to 2 :type "p1a" :body {:bal 1 :id 0}}]}
    ;; {:debug "6" :deliver-message {:from 0 :to 1 :type "p1a" :body {:bal 1 :id 0}} :update-state [1 [[:acceptor :bal] 1]]}
    ;; {:debug "3" :deliver-message {:from 0 :to 2 :type "p1a" :body {:bal 1 :id 0}} :update-state [1 [[:acceptor :bal] 1]]}
    ]))
