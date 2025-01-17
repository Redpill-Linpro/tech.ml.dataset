(ns tech.v3.dataset.zip
  "Load zip data.  Zip files with a single file entry can be loaded with ->dataset.  When
  a zip file has multiple entries you have to call zipfile->dataset-seq."
  (:require [tech.v3.dataset.io :as ds-io]
            [tech.v3.io :as io]
            [clojure.tools.logging :as log])
  (:import [java.util.zip ZipInputStream ZipOutputStream ZipEntry]
           [java.util Set HashSet]
           [tech.v3.dataset NoCloseInputStream NoCloseOutputStream]))


(set! *warn-on-reflection* true)


(defmethod ds-io/data->dataset :zip
  [data options]
  (with-open [is (-> (apply io/input-stream data (apply concat (seq options)))
                     (ZipInputStream.))]
    (let [zentry (.getNextEntry is)
          ftype (-> (ds-io/str->file-info (.getName zentry))
                    (get :file-type))
          retval (ds-io/data->dataset (NoCloseInputStream. is)
                                      (assoc options :file-type ftype))]
      (when (.getNextEntry is)
        (log/warnf "Multiple entries found in zipfile"))
      retval)))


(defmethod ds-io/dataset->data! :zip
  [data output options]
  (let [inner-name (.substring (str output) 0 (- (count output) 4))
        ftype (-> (ds-io/str->file-info inner-name)
                  (get :file-type))]
    (with-open [os (-> (apply io/output-stream! output (apply concat (seq options)))
                       (ZipOutputStream.))]
      (.putNextEntry os (ZipEntry. inner-name))
      (ds-io/dataset->data! data os (assoc options :file-type ftype)))))


(defn- load-zip-entry
  [^ZipInputStream is options]
  (try
    (if-let [entry (.getNextEntry is)]
      (let [ftype (-> (ds-io/str->file-info (.getName entry))
                      (get :file-type))]
        (cons (ds-io/data->dataset (NoCloseInputStream. is)
                                   (assoc options
                                          :file-type ftype
                                          :dataset-name (.getName entry)))
              (lazy-seq (load-zip-entry is options))))
      (do
        (.close is)
        nil))
    (catch Exception e
      (.close is)
      (throw e))))


(defn zipfile->dataset-seq
  "Load a zipfile attempting to load each zip entry."
  ([input options]
   (let [is (-> (apply io/input-stream input (apply concat (seq options)))
                (ZipInputStream.))]
     (load-zip-entry is options)))
  ([input]
   (zipfile->dataset-seq input nil)))


(defn- ds-name->string
  ^String [ds]
  (let [nm (:name (meta ds))]
    (if (or (symbol? nm) (keyword? nm))
      (name nm)
      (str nm))))


(defn- unique-name!
  ^String [ds ^Set used]
  (let [nm (ds-name->string ds)
        nm (if (.contains used nm )
             (loop [idx 0]
               (let [idx-nm (str nm "-" idx)]
                 (if (.contains used idx-nm)
                   (recur (inc idx))
                   idx-nm)))
             nm)]
    (.add used nm)
    nm))


(defn dataset-seq->zipfile!
  "Write a sequence of datasets to zipfiles.  You can control the inner type with the
  :file-type option which defaults to .tsv"
  ([output options ds-seq]
   (let [fnames (HashSet.)
         ftype (get options :file-type :tsv)
         options (assoc options :file-type ftype)]
     (with-open [os (-> (apply io/output-stream! output (apply concat (set options)))
                        (ZipOutputStream.))]
       (doseq [ds ds-seq]
         (.putNextEntry os (ZipEntry. (str (unique-name! ds fnames) "." (name ftype))))
         (ds-io/dataset->data! ds (NoCloseOutputStream. os) options)))))
  ([output ds-seq]
   (dataset-seq->zipfile! output nil ds-seq)))
