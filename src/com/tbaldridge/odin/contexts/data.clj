(ns com.tbaldridge.odin.contexts.data
  (:require [com.tbaldridge.odin :as o]
            [com.tbaldridge.odin.unification :as u]
            [com.tbaldridge.odin.util :as util]))

(defprotocol IPath
  (add-to-path [this val]))

(deftype Path [val nxt]
  IPath
  (add-to-path [this val]
    (Path. val nxt)))

(extend-protocol IPath
  nil
  (add-to-path [this val]
    (->Path val this)))


(def empty-path (->Path nil nil))

(defn prefix [itm rc]
  (reify
    clojure.lang.IReduceInit
    (reduce [this f init]
      (let [acc (f init itm)]
        (if (reduced? acc)
          @acc
          (reduce f acc rc))))))



(defn map-value [p k v]
  (cond
    (map? v)
    (let [next-path (add-to-path p k)]
      (prefix [p k next-path]
              (eduction
                (mapcat (fn [[k v]]
                          (map-value next-path k v)))
                v)))

    (and (sequential? v)
         (not (string? v)))

    (let [next-path (add-to-path p k)]
      (prefix [p k next-path]
              (eduction
                (map-indexed
                  (partial map-value next-path))
                cat
                v)))

    :else
    [[p k v]]))


(defn map-path [v]
  (let [p empty-path]
    (cond

      (map? v)
      (eduction
        (mapcat
          (fn [[k v]]
            (map-value p k v)))
        v)

      (and (sequential? v)
           (not (string? v)))

      (eduction
        (map-indexed
          (partial map-value p))
        cat
        v))))

(deftype IndexedData [coll index])

(defn index-data ^IndexedData [coll]
  (->>
    (reduce
      (fn [acc [p a v]]
        (-> acc
            (util/assoc-in! [:eav p a] v)
            (util/update-in! [:ave a v] conj p)
            (util/update-in! [:vea v p] conj a)))
      nil
      (map-path coll))
    (->IndexedData coll)))


(defn coll-index [coll]
  (if (instance? IndexedData coll)
    (.-index coll)
    (if-let [v (get (u/*query-ctx* ::indicies) coll)]
      v
      (let [indexed (.-index (index-data coll))]
        (set! u/*query-ctx* (assoc-in u/*query-ctx* [::indicies coll] indexed))
        indexed))))


(defn query [coll p a v]
  (let [index (coll-index coll)]
    (mapcat
      (fn [env]
        (let [index (if (u/lvar? coll)
                      (coll-index (u/walk env coll))
                      index)
              p' (u/walk env p)
              a' (u/walk env a)
              v' (u/walk env v)]
          (util/truth-table [(u/lvar? p') (u/lvar? a') (u/lvar? v')]

            [true false true] (util/efor [[v es] (get-in index [:ave a'])
                                          e es]
                                         (-> env
                                             (u/unify p' e)
                                             (u/unify v' v)))
            [false false true] (when-some [v (get-in index [:eav p' a'])]
                                 (u/just (assoc env v' v)))

            [false true true] (util/efor [[a v] (get-in index [:eav p'])]
                                         (assoc env a' a v' v))

            [true false false] (util/efor [e (get-in index [:ave a' v'])]
                                 (assoc env p' e))

            [false true false] (util/efor [a (get-in index [:vea v' p'])]
                                          (u/unify env a' a))

            [false false false] (when (= v' (get-in index [:eav p' a']))
                                  (u/just env))

            [true true false] (util/efor [[e as] (get-in index [:vea v'])
                                          a as]
                                (-> env
                                    (u/unify p' e)
                                    (u/unify a' a)))

            [true true true] (util/efor [[e avs] (get index :eav)
                                         [a v] avs]
                               (-> env
                                      (u/unify p' e)
                                      (u/unify a' a)
                                      (u/unify v' v)))))))))


(defn query-in [coll p [h & t] v]
  (if (seq t)
    (let [cvar (u/lvar)]
      (u/conjunction
        (query coll p h cvar)
        (query-in coll cvar t v)))
    (query coll p h v)))



(o/defrule parent-of [data ?p ?c]
  (o/or
    (o/= ?p ?c)
    (o/and
      (query data ?p _ ?ic)
      (u/lazy-rule (parent-of data ?ic ?c)))))







