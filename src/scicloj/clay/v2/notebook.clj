(ns scicloj.clay.v2.notebook
  (:require
   [clojure.string :as string]
   [scicloj.clay.v2.item :as item]
   [scicloj.clay.v2.util.path :as path]
   [scicloj.clay.v2.item :as item]
   [scicloj.clay.v2.prepare :as prepare]
   [scicloj.clay.v2.read :as read]
   [scicloj.clay.v2.config :as config]
   [scicloj.clay.v2.util.merge :as merge]
   [scicloj.kindly.v4.kind :as kind]
   [scicloj.kindly-advice.v1.api :as kindly-advice]))

(defn deref-if-needed [v]
  (if (delay? v)
    @v
    v))

(def hidden-form-starters
  #{'ns 'comment
    'def 'defonce 'defn 'defmacro
    'defrecord 'defprotocol 'deftype
    'extend-protocol 'extend
    'require})

(defn info-line [absolute-file-path]
  (let [relative-file-path (path/path-relative-to-repo
                            absolute-file-path)]
    (item/info-line {:path relative-file-path
                     :url (some-> (config/config)
                                  :remote-repo
                                  (path/file-git-url relative-file-path))})))

(defn complete [{:as note
                 :keys [comment? code form value]}]
  (if (or value comment?)
    note
    (assoc
     note
     :value (cond form (-> form
                           eval
                           deref-if-needed)
                  code (-> code
                           read-string
                           eval
                           deref-if-needed)))))

(defn comment->item [comment]
  (-> comment
      (string/split #"\n")
      (->> (map #(-> %
                     (string/replace
                      #"^;+\s*" "")
                     (string/replace
                      #"^#" "\n#")))
           (string/join "\n"))
      item/md))

(defn note-to-items [{:as note
                      :keys [comment? code form value]}
                     {:keys [hide-code hide-nils hide-vars]}]
  (if (and comment? code)
    [(comment->item code)]
    (concat
     ;; code
     [(when-not (or hide-code
                    (-> form meta :kindly/hide-code)
                    (-> form meta :kindly/hide-code?) ; legacy convention
                    (-> value meta :kindly/hide-code)
                    (-> value meta :kindly/hide-code?) ; legacy convention
                    (some-> note
                            :kindly/options
                            :kinds-that-hide-code
                            (as-> kthc
                                (-> value
                                    meta
                                    :kindly/kind
                                    kthc)))
                    (nil? code))
        (item/source-clojure code))]
     ;; value
     (when-not (or
                (and (sequential? form)
                     (-> form first hidden-form-starters))
                (-> note :form meta :kind/hidden)
                (and hide-nils (nil? value))
                (and hide-vars (var? value)))
       (-> note
           (select-keys [:value :code :form
                         :base-target-path
                         :full-target-path
                         :kindly/options
                         :format])
           (update :value deref-if-needed)
           prepare/prepare-or-pprint)))))

(defn add-info-line [items {:keys [full-source-path hide-info-line]}]
  (if hide-info-line
    items
    (let [il (info-line full-source-path)]
      (concat #_[il
                 item/separator]
              items
              [item/separator
               il]))))

(defn ->var-name [i]
  (symbol (str "var" i)))

(defn ->test-name [i]
  (symbol (str "test" i)))

(defn test-last? [complete-note]
  (and (-> complete-note
           :comment?
           not)
       (-> complete-note
           kindly-advice/advise
           :kind
           (= :kind/test-last))))

(defn def-form [var-name form]
  (list 'def
        var-name
        form))

(defn deftest-form [test-name var-name form]
  (if (-> form first (= 'kind/test-last))
    (deftest-form test-name var-name (second form))
    (let [[f-symbol & args] form]
      (list 'deftest
            test-name
            (concat (list 'is
                          (concat (list f-symbol
                                        var-name)
                                  args)))))))

(defn ns-form? [form]
  (and (sequential? form)
       (-> form first (= 'ns))))

(defn test-ns-form [[_ ns-symbol & rest-ns-form]]
  (concat (list 'ns
                (-> ns-symbol
                    (str "-generated-test")
                    symbol))
          (->> rest-ns-form
               (map (fn [part]
                      (if (and (list? part)
                               (-> part first (= :require)))
                        (concat part
                                '[[clojure.test :refer [deftest is]]])
                        part))))))


(defn notebook-items-and-test-forms
  ([{:as options
     :keys [full-source-path
            hide-info-line
            hide-code hide-nils hide-vars
            title toc?
            base-target-path
            full-target-path
            single-form
            single-value
            format]}]
   (let [code (some-> full-source-path
                      slurp)
         notes  (cond
                  single-value (conj (when code
                                       [{:form (read/read-ns-form code)}])
                                     {:value single-value})
                  single-form (conj (when code
                                      [{:form (read/read-ns-form code)}])
                                    {:form single-form})
                  :else (read/->safe-notes code))]
     (-> (->> notes
              (reduce (fn [{:as aggregation :keys [i
                                                   items
                                                   test-forms
                                                   last-nontest-i]}
                           note]
                        (let [{:as complete-note :keys [form]} (complete note)
                              test-note (test-last? complete-note)
                              new-items (when-not test-note
                                          (-> complete-note
                                              (merge/deep-merge
                                               (-> options
                                                   (select-keys [:base-target-path
                                                                 :full-target-path
                                                                 :kindly/options
                                                                 :format])))
                                              (note-to-items options)))
                              test-form (if test-note
                                          ;; a deftest form
                                          (deftest-form
                                            (->test-name i)
                                            (->var-name last-nontest-i)
                                            form)
                                          (if (ns-form? form)
                                            ;; the test ns form
                                            (test-ns-form form)
                                            ;; the regular case, just a def
                                            (def-form
                                              (->var-name i)
                                              form)))]
                          {:i (inc i)
                           :items (concat items new-items)
                           :test-forms (conj test-forms test-form)
                           :last-nontest-i (if (or (:comment? complete-note)
                                                   test-note)
                                             last-nontest-i
                                             i)}))
                      ;; initial value
                      {:i 0
                       :items []
                       :test-forms []
                       :last-nontest-i nil}))
         (update :items
                 ;; final processing of items
                 (fn [items]
                   (-> items
                       (->> (remove nil?))
                       (add-info-line options)
                       doall)))
         (update :test-forms
                 ;; Leave the test-form only when
                 ;; at least one of them is a `deftest`.
                 (fn [test-forms]
                   (when (->> test-forms
                              (some #(-> % first (= 'deftest))))
                     test-forms)))))))


(comment
  (-> "notebooks/scratch.clj"
      (notebook-items {:full-target-path "docs/scratch.html"}))

  (-> "notebooks/scratch.clj"
      (notebook-items {:full-target-path "docs/scratch.html"
                       :single-form '(+ 1 2)})))


;; aa
