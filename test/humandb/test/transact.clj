(ns humandb.test.transact
  (:require
    [clojure.test :refer :all]
    [clojure.string :as string]
    [humandb.transact :as tx]
    [humandb.db :as db]
    [humandb.import :as import]
    [yaml.core :as yaml]
    [datascript.core :as d]))

(deftest affected-docs
  (testing "single"
    (let [txs [[:db/add 1234 :foo "bar"]]]
      (is (= #{1234}
             (tx/affected-docs txs)))))

  (testing "multiple"
    (let [txs [[:db/add 1234 :foo "bar"]
               [:db/add 5678 :foo "bar"]]]
      (is (= #{1234 5678}
             (tx/affected-docs txs)))))

  (testing "removes dupes"
    (let [txs [[:db/add 1234 :foo "bar"]
               [:db/add 1234 :bar "baz"]]]
      (is (= #{1234}
             (tx/affected-docs txs))))))


(deftest test-embedded
  (testing "returns true when direct embed"
    (let [docs [{:id 1
                 :foo "bar"
                 :type "post"
                 :db/src [:a 0]
                 :comments {:id 2
                            :abc 123
                            :type "comment"
                            :db/src [:a 0 :comments]}}]
          db (db/init! [["post", "comments", "comment"]])]
      (import/import-docs db docs)

      (is (= true
             (tx/embedded? (tx/get-doc-by-id db 2)
                           (tx/get-doc-by-id db 1))))))

  (testing "returns true when array embed"
    (let [docs [{:id 1
                 :foo "bar"
                 :type "post"
                 :db/src [:a 0]
                 :comments [{:id 2
                             :abc 123
                             :type "comment"
                             :db/src [:a 0 :comments 0]}]}]
          db (db/init! [["post", "comments", "comment"]])]
      (import/import-docs db docs)

      (is (= true
             (tx/embedded? (tx/get-doc-by-id db 2)
                           (tx/get-doc-by-id db 1))))))

  (testing "returns false when not embedded"
    (let [docs [{:id 1
                 :foo "bar"
                 :type "post"
                 :db/src [:a 0]
                 :comments [2]}
                {:id 2
                 :abc 123
                 :type "comment"
                 :db/src [:a 1]}]
          db (db/init! [["post", "comments", "comment"]])]
        (import/import-docs db docs)

        (is (= false
               (tx/embedded? (tx/get-doc-by-id db 2)
                             (tx/get-doc-by-id db 1))))))

  (testing "returns false when deeply embedded"
    (let [docs [{:id 1
                 :foo "bar"
                 :type "post"
                 :db/src [:a 0]
                 :comments [{:id 2
                             :abc 123
                             :type "comment"
                             :db/src [:a 0 :comments 0]
                             :author {:name "Bob"
                                      :id 3
                                      :type "author"
                                      :db/src [:a 0 :comments 0 :author]}}]}]

          db (db/init! [["post", "comments", "comment"]
                        ["comment", "author", "author"]])]
        (import/import-docs db docs)

        (is (= false
               (tx/embedded? (tx/get-doc-by-id db 3)
                             (tx/get-doc-by-id db 1))))))

  (testing "returns false when embedded in other doc, not this one"
    (let [docs [{:id 1
                 :foo "bar"
                 :type "post"
                 :db/src [:a 0]}
                {:id 2
                 :foo "baz"
                 :type "post"
                 :db/src [:a 1]
                 :comment {:id 3
                           :abc 123
                           :type "comment"
                           :db/src [:a 1 :comment]}}]
          db (db/init! [["post", "comment", "comment"]])]
      (import/import-docs db docs)

      (is (= false
             (tx/embedded? (tx/get-doc-by-id db 3)
                           (tx/get-doc-by-id db 1)))))))

(deftest get-doc
  (testing "returns doc attributes"
    (let [conn (d/create-conn {})]
      (d/transact conn [{:db/id 1234
                         :foo "bar"}])
      (is (= {:db/id 1234
              :foo "bar"}
             (tx/get-doc {:conn conn} 1234)))))

  (testing "returns nested docs"
    (let [docs [{:id 1000
                 :type "post"
                 :db/src [:a 0]
                 :comments [{:id 2000
                             :type "comment"
                             :db/src [:a 0 :comments 0]
                             :content "ayy"}]}]
          db (db/init! [["post", "comments", "comment"]])]

      (import/import-docs db docs)
      (let [pid (first (d/q '[:find [?eid]
                              :in $ ?id
                              :where
                              [?eid :id ?id]]
                            @(db :conn)
                            1000))
            eid (first (d/q '[:find [?eid]
                              :in $ ?id
                              :where
                              [?eid :id ?id]]
                            @(db :conn)
                            2000))]

        (is (= {:id 1000
                :type "post"
                :db/src [:a 0]
                :db/id pid
                :comments [{:id 2000
                            :type "comment"
                            :content "ayy"
                            :db/src [:a 0 :comments 0]
                            :db/embedded? true
                            :db/id eid}]}
               (tx/get-doc db pid))))))

  (testing "does not return related docs as embedded"
    (let [docs [{:id 1000
                 :type "post"
                 :comments [2000]}
                {:id 2000
                 :type "comment"
                 :content "ayy"}]
          db (db/init! [["post", "comments", "comment"]])]

      (import/import-docs db docs)

      (let [pid (first (d/q '[:find [?eid]
                              :in $ ?id
                              :where
                              [?eid :id ?id]]
                            @(db :conn)
                            1000))]
        (is (= {:id 1000
                :type "post"
                :db/id pid
                :comments [2000]}
               (tx/get-doc db pid)))))))

(deftest remove-metadata
  (testing "removes db/* attributes"
    (testing "direct attributes"
      (let [raw-attrs {:normal-attr "keep this"
                       :db/anything "remove this"}]
        (is (= {:normal-attr "keep this"}
               (tx/remove-metadata raw-attrs)))))

    (testing "embedded doc"
      (let [raw-attrs {:attr "keep"
                       :db/attr "remove"
                       :embed {:attr "keep"
                               :db/attr "remove"}}]
        (is (= {:attr "keep"
                :embed {:attr "keep"}}
               (tx/remove-metadata raw-attrs)))))

    (testing "list of embedded docs"
      (let [raw-attrs {:attr "keep"
                       :db/attr "remove"
                       :embed [{:attr "keep"
                                :db/attr "remove"}
                               {:attr "keep"
                                :db/attr "remove"}]}]
        (is (= {:attr "keep"
                :embed [{:attr "keep"}
                        {:attr "keep"}]}
               (tx/remove-metadata raw-attrs)))))))

(deftest get-parent
  (testing "returns parent"
    (testing "when nested"
      (let [db (db/init! [["post", "comments", "comment"]])
            docs [{:type "post"
                   :content "abcde"
                   :id 1000
                   :comments {:id 2000
                              :content "foobar"
                              :type "comment"}}
                  {:type "post"
                   :id 4000
                   :content "zzzzz"}]]
        (import/import-docs db docs)
        (let [pid (first (d/q '[:find [?eid]
                                :in $ ?id
                                :where
                                [?eid :id ?id]]
                           @(db :conn)
                           1000))
              eid (first (d/q '[:find [?eid]
                                :in $ ?id
                                :where
                                [?eid :id ?id]]
                           @(db :conn)
                           2000))]
          (is (= pid
                 (tx/get-parent db eid))))))

    (testing "when nested in array"
      (let [db (db/init! [["post", "comments", "comment"]])
            docs [{:type "post"
                   :content "abcde"
                   :id 1000
                   :comments [{:id 2000
                               :content "foobar"
                               :type "comment"}
                              {:id 3000
                               :content "foobar"
                               :type "comment"}]}
                  {:type "post"
                   :id 4000
                   :content "zzzzz"}]]
        (import/import-docs db docs)
        (let [pid (first (d/q '[:find [?eid]
                                :in $ ?id
                                :where
                                [?eid :id ?id]]
                           @(db :conn)
                           1000))
              eid (first (d/q '[:find [?eid]
                                :in $ ?id
                                :where
                                [?eid :id ?id]]
                           @(db :conn)
                           2000))]
          (is (= pid
                 (tx/get-parent db eid))))))

    (testing "when nested multiple levels deep"
      (let [db (db/init! [["post", "comments", "comment"]
                          ["comment", "author", "author"]])
            docs [{:type "post"
                   :content "abcde"
                   :id 1000
                   :comments [{:id 2000
                               :content "foobar"
                               :type "comment"
                               :author {:id 4000
                                        :type "author"
                                        :email "foo@bar.com"}}]}]]
        (import/import-docs db docs)
        (let [pid (first (d/q '[:find [?eid]
                                :in $ ?id
                                :where
                                [?eid :id ?id]]
                           @(db :conn)
                           2000))
              eid (first (d/q '[:find [?eid]
                                :in $ ?id
                                :where
                                [?eid :id ?id]]
                           @(db :conn)
                           4000))]
          (is (= pid
                 (tx/get-parent db eid)))))))

  (testing "does not return parent for docs that aren't embedded"
    (let [db (db/init! [["post", "comments", "comment"]])
          docs [{:type "post"
                 :content "abcde"
                 :id 1000
                 :comments [2000 3000]}
                {:id 2000
                 :content "foobar"
                 :type "comment"}
                {:id 3000
                 :content "foobar"
                 :type "comment"}
                {:type "post"
                 :id 4000
                 :content "zzzzz"}]]
      (import/import-docs db docs)
      (let [eid (first (d/q '[:find [?eid]
                              :in $ ?id
                              :where
                              [?eid :id ?id]]
                         @(db :conn)
                         2000))]
        (is (= nil
               (tx/get-parent db eid)))))))

(deftest save-doc!
  (testing "top-level doc"
    (let [path "/tmp/humandb_savedoc_test.yaml"
          relationships [["post", "comments", "comment"]]
          db (db/init! relationships)
          docs [{:type "post"
                 :content "abcde"
                 :id 1000
                 :db/src [path 0]}
                {:type "post"
                 :id 4000
                 :content "zzzzz"
                 :db/src [path 1]}]]
      (import/import-docs db docs)
      (let [eid (first (d/q '[:find [?eid]
                              :in $ ?id
                              :where
                              [?eid :id ?id]]
                            @(db :conn)
                            1000))]
        ; save-doc! currently expects the docs to exist at their indexes
        ; create a fake pre-version of file
        (spit path "---\n ---\n ---\n")
        (tx/save-doc! db eid)
        (is (= "---\ncontent: abcde\nid: 1000\ntype: post\n---\n"
               (slurp path))))))

  (testing "nested doc"
    (let [path "/tmp/humandb_savedoc_test.yaml"
          db (db/init! [["post", "comments", "comment"]])
          docs [{:type "post"
                 :content "zzz"
                 :id 1000
                 :comments [{:id 5000
                             :type "comment"
                             :content "blargh"
                             :db/src [path 0 :comments 0]}]
                 :db/src [path 0]}
                {:type "post"
                 :id 4000
                 :content "zzzzz"
                 :db/src [path 1]}]]
      (import/import-docs db docs)
      (let [eid (first (d/q '[:find [?eid]
                              :in $ ?id
                              :where
                              [?eid :id ?id]]
                            @(db :conn)
                            5000))]
        ; save-doc! currently expects the docs to exist at their indexes
        ; create a fake pre-version of file
        (spit path "---\n ---\n ---\n")
        (tx/save-doc! db eid)
        (is (= "---\ncomments:\n- content: blargh\n  id: 5000\n  type: comment\ncontent: zzz\nid: 1000\ntype: post\n---\n"
               (slurp path)))))))

