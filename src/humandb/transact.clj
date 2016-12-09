(ns humandb.transact
  (:require
    [datascript.core :as d]
    [humandb.out :as out]))

(defn tee [x]
  (println x) x)

(defn relationship? [db doc-type key]
  (first (filter (fn [x]
                   (and (= (first x) doc-type)
                        (= (keyword (second x)) key)))
                 (db :relationships))))

(declare get-doc)

(defn get-doc-by-id [db id]
  (let [eid (first (d/q '[:find [?eid]
                          :in $ ?id
                          :where
                          [?eid :id ?id]]
                        @(db :conn)
                        id))]
    (get-doc db eid)))

(defn embedded?
  "Returns true if child-doc is embedded in parent-doc"
  [child-doc parent-doc]
  (boolean
    (and (:db/embedded? child-doc)
         (= (:db/src parent-doc)
            (take (count (:db/src parent-doc)) (:db/src child-doc)))
         (<= 1 (- (count (:db/src child-doc))
                  (count (:db/src parent-doc))) 2))))

(defn doc-or-id-if-child
  "If doc with child-id is embedded in parent-id, return child-doc, otherwise, just return the child-id"
  [db child-id parent-doc]
  (let [child-doc (get-doc-by-id db child-id)]
    (if (embedded? child-doc parent-doc)
      child-doc
      child-id)))

(defn get-doc
  "Given eid, returns doc containing all attributes (and embedded docs)"
  [db eid]
  (let [raw-doc (d/pull @(db :conn) '[*] eid)]
    (->> raw-doc
         (reduce (fn [doc [k v]]
                   (if (relationship? db (raw-doc :type) k)
                     (if (vector? v)
                       ; vector of ids
                       (assoc doc k (map (fn [id]
                                           (doc-or-id-if-child db id raw-doc)) v))
                       ; id
                       (assoc doc k (doc-or-id-if-child db v raw-doc)))
                     ; not an id
                     (assoc doc k v))) {}))))

(defn remove-metadata
  "Recursively removes metadata (keywords in :db/* namespace)"
  [doc]
  (->> doc
       (reduce (fn [memo [k v]]
                 (cond
                   (= "db" (namespace k))
                   memo

                   (map? v)
                   (assoc memo k (remove-metadata v))

                   (and (coll? v) (map? (first v)))
                   (assoc memo k (map remove-metadata v))

                   :else
                   (assoc memo k v)))
               {})))

(defn get-parent
  "Returns eid of parent, if there is one, otherwise nil"
  [db eid]
  (->> (d/q '[:find ?pid ?rel
              :in $ ?eid
              :where
              [?eid :id ?id]
              [?eid :db/embedded? true]
              [?pid ?rel ?id]]
         @(db :conn)
         eid)
       (remove (fn [r] (= :id (second r))))
       ffirst))

(defn save-doc!
  "Saves top-level document to file"
  [db eid]

  (if-let [parent-eid (get-parent db eid)]
    (save-doc! db parent-eid)

    (let [doc (get-doc db eid)
          location (:db/src doc)]
      (out/replace! location (remove-metadata doc)))))

(defn affected-docs [txs]
  (set (map second txs)))

(defn transact! [db txs]
  (d/transact (db :conn) txs)
  ; TODO could identify parents of each affected-doc
  ; to avoid saving a top-level doc multiple times
  (doseq [doc (affected-docs txs)]
    (save-doc! db doc)))

