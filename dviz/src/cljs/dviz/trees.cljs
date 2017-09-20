(ns dviz.trees)

(defn leaf [v]
  (list v))

(defn root [tree]
  (first tree))

(defn children [tree]
  (rest tree))

(defn update-nth [l n f & args]
  (concat (take n l) (list (apply f (nth l n) args)) (drop (inc n) l)))

(defn set-nth [l n x]
  (concat (take n l) (list x) (drop (inc n) l)))

(defn append-path
  "Takes a tree, a path and a value. Returns a new tree with that
  value appended at the position identified by the path and a new
  path that identifies the appended value's position"
  [tree path v]
  (if (empty? path)
    [(cons (root tree) (concat (children tree) (list (leaf v))))
     [(count (children tree))]]
    (let [n (first path)
          path' (rest path)
          subtree (nth (children tree) n)
          [subtree' path'] (append-path subtree path' v)]
      [(cons (root tree) (set-nth (children tree) n subtree'))
       (cons n path')])))

(defn get-path [tree path]
  (if (empty? path)
    tree
    (recur (nth (children tree) (first path)) (rest path))))

(defn layout
  "Takes a tree and layes it out, returning a list of maps:
  {:position [<x> <y>] :value <v> :path <path>}"
  ([tree dx dy] (layout tree dx dy 0 0 [] nil))
  ([tree dx dy x y path parent]
   (let [root-layout {:position [x y] :value (root tree) :path path :parent parent}]
     (cons root-layout
           (apply concat
                  (for [index (range (count (children tree)))
                        :let
                        [child (nth (children tree) index)
                         new-x (+ x dx)
                         new-y (+ y (* index dy))
                         new-path (conj path index)]]
                    (layout child dx dy new-x new-y new-path [x y])))))))
