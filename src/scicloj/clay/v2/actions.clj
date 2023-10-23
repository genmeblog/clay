(ns scicloj.clay.v2.actions
  (:require
   [scicloj.clay.v2.notebook :as notebook]
   [scicloj.clay.v2.path :as path]
   [scicloj.clay.v2.server :as server]
   [scicloj.clay.v2.show :as show]
   [scicloj.clay.v2.quarto :as quarto]
   [scicloj.clay.v2.item :as item]))

(defn show-doc!
  ([path]
   (show-doc! path nil))
  ([path {:keys [title toc? custom-message]
          :as options}]
   (show/show-items!
    [(item/hiccup
      (or custom-message
          [:div
           [:p "showing document for "
            [:code (path/path->filename path)]]
           [:div.loader]]))])
   (let [doc (notebook/gen-doc path options)]
     (-> doc
         (show/show-items!
          {:title title
           :toc? toc?})))
   :ok))

(defn show-doc-and-write-html!
  [path options]
  (-> options
      (assoc :custom-message [:div
                              [:p "showing document for "
                               [:code (path/path->filename path)]]
                              [:p "and then writing as html file"]
                              [:div.loader]]
             :path path)
      (->> (show-doc! path)))
  (Thread/sleep 1000)
  (show/write-html!))

(defn render-quarto!
  [path {:keys [title]
         :as options}]
  (show/show-items!
   [(item/hiccup
     [:div
      [:p "generating Quarto document for "
       [:code (path/path->filename path)]]
      [:div.loader]])])
  (-> options
      (assoc
       :title (or title path)
       :path path)
      (->> (notebook/gen-doc path))
      quarto/render-quarto!))

(defn write-quarto!
  [path {:keys [title]
         :as options}]
  (-> options
      (assoc
       :title (or title path)
       :path path)
      (->> (notebook/gen-doc path))
      quarto/write-quarto!))
