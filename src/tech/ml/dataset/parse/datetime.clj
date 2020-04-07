(ns tech.ml.dataset.parse.datetime
  (:require [clojure.string :as s]
            [tech.v2.datatype.datetime :as dtype-dt]
            [primitive-math :as pmath])
  (:import [java.time LocalDate LocalDateTime LocalTime
            ZonedDateTime OffsetDateTime Instant]
           [tech.v2.datatype.typed_buffer TypedBuffer]
           [java.time.format DateTimeFormatter DateTimeFormatterBuilder]
           [java.util List]
           [it.unimi.dsi.fastutil.ints IntList]
           [it.unimi.dsi.fastutil.longs LongList]))


(set! *warn-on-reflection* true)


;;Assumping is that the string will have /,-. replaced with /space
(def date-parser-patterns
  ["yyyyMMdd"
   "MM dd yyyy"
   "yyyy MM dd"
   "dd MMM yyyy"
   "M d yyyy"
   "M d yy"
   "MMM dd yyyy"
   "MMM dd yy"
   "MMM d yyyy"])


(defn date-preparse
  ^String [^String data]
  (.replaceAll data "[/,-. ]+" " "))


(def base-local-date-formatter
  (let [builder (DateTimeFormatterBuilder.)]
    (.parseCaseInsensitive builder)
    (doseq [pattern date-parser-patterns]
      (.appendOptional builder (DateTimeFormatter/ofPattern pattern)))
    (.appendOptional builder DateTimeFormatter/ISO_LOCAL_DATE)
    (.toFormatter builder)))


(defn parse-local-date
  ^LocalDate [^String str-data]
  (LocalDate/parse (date-preparse str-data)
                   base-local-date-formatter))


(def time-parser-patterns
  ["HH:mm:ss:SSS"
   "hh:mm:ss a"
   "HH:mm:ss"
   "h:mm:ss a"
   "H:mm:ss"
   "hh:mm a"
   "HH:mm"
   "HHmm"
   "h:mm a"
   "H:mm"])


(def base-local-time-formatter
  (let [builder (DateTimeFormatterBuilder.)]
    (.parseCaseInsensitive builder)
    (doseq [pattern time-parser-patterns]
      (.appendOptional builder (DateTimeFormatter/ofPattern pattern)))
    (.appendOptional builder DateTimeFormatter/ISO_LOCAL_TIME)
    (.toFormatter builder)))


(defn local-time-preparse
  ^String [^String data]
  (.replaceAll data "[.]" ":"))


(defn parse-local-time
  ^LocalTime [^String str-data]
  (LocalTime/parse (local-time-preparse str-data)
                   base-local-time-formatter))


(defn parse-local-date-time
  ^LocalDateTime [^String str-data]
  (let [split-data (s/split str-data #"[ T]+")]
    (cond
      (== 2 (count split-data))
      (let [local-date (parse-local-date (first split-data))
            local-time (parse-local-time (second split-data))]
        (LocalDateTime/of local-date local-time))
      (== 3 (count split-data))
      (let [local-date (parse-local-date (first split-data))
            local-time (parse-local-time (str (split-data 1) " " (split-data 2)))]
        (LocalDateTime/of local-date local-time))
      :else
      (throw (Exception. (format "Failed to parse \"%s\" as a LocalDateTime"
                                 str-data))))))


(defn try-parse-datetimes
  "Given unknown string value, attempt to parse out a datetime value.
  Returns tuple of
  [dtype value]"
  [str-value]
  (if-let [date-val (try (parse-local-date str-value)
                         (catch Exception e nil))]
    [:local-date date-val]
    (if-let [time-val (try (parse-local-date-time str-value)
                           (catch Exception e nil))]
      [:local-time time-val]
      (if-let [date-time-val (try (parse-local-date-time str-value)
                                  (catch Exception e nil))]
        [:local-date-time date-time-val]
        [:string str-value]))))



(defmacro compile-time-datetime-parse-str
  [datatype str-val]
  (case datatype
    :local-date
    `(parse-local-date ~str-val)
    :local-date-time
    `(parse-local-date-time ~str-val)
    :local-time
    `(parse-local-time ~str-val)
    :packed-local-date
    `(dtype-dt/pack-local-date (parse-local-date ~str-val))
    :packed-local-date-time
    `(dtype-dt/pack-local-date-time (parse-local-date-time ~str-val))
    :packed-local-time
    `(dtype-dt/pack-local-time (parse-local-time ~str-val))
    :instant
    `(Instant/parse ~str-val)
    :zoned-date-time
    `(ZonedDateTime/parse ~str-val)
    :offset-date-time
    `(OffsetDateTime/parse ~str-val)))


(defn datetime-parse-str-fn
  [datatype formatter]
  (case datatype
    :local-date
    #(LocalDate/parse % formatter)
    :local-date-time
    #(LocalDateTime/parse % formatter)
    :local-time
    #(LocalTime/parse % formatter)
    :packed-local-date
    #(dtype-dt/pack-local-date (LocalDate/parse % formatter))
    :packed-local-date-time
    #(dtype-dt/pack-local-date-time (LocalDateTime/parse % formatter))
    :packed-local-time
    #(dtype-dt/pack-local-time (LocalTime/parse % formatter))
    :instant
    (throw (Exception. "Instant parsers do not take format strings"))
    :zoned-date-time
    #(ZonedDateTime/parse % formatter)
    :offset-date-time
    #(OffsetDateTime/parse % formatter)))


(defn as-typed-buffer
  ^TypedBuffer [item] item)

(defn as-list
  ^List [item] item)

(defn as-int-list
  ^IntList [item] item)

(defn as-long-list
  ^LongList [item] item)


(defmacro compile-time-add-to-container!
  [datatype container parsed-val]
  (case datatype
    :local-date
    `(.add (as-list ~container) ~parsed-val)
    :local-date-time
    `(.add (as-list ~container) ~parsed-val)
    :local-time
    `(.add (as-list ~container) ~parsed-val)
    :zoned-date-time
    `(.add (as-list ~container) ~parsed-val)
    :offset-date-time
    `(.add (as-list ~container) ~parsed-val)
    :instant
    `(.add (as-list ~container) ~parsed-val)
    :packed-local-date
    `(.add (as-int-list (.backing-store (as-typed-buffer ~container)))
           (pmath/int ~parsed-val))
    :packed-local-time
    `(.add (as-int-list (.backing-store (as-typed-buffer ~container)))
           (pmath/int ~parsed-val))
    :packed-local-date-time
    `(.add (as-long-list (.backing-store (as-typed-buffer ~container)))
           (pmath/long ~parsed-val))))


(defn add-to-container!
  [datatype container parsed-val]
  (case datatype
    :instant
    (compile-time-add-to-container! :instant container parsed-val)
    :zoned-date-time
    (compile-time-add-to-container! :zoned-date-time container parsed-val)
    :offset-date-time
    (compile-time-add-to-container! :offset-date-time container parsed-val)
    :local-date
    (compile-time-add-to-container! :local-date container parsed-val)
    :local-date-time
    (compile-time-add-to-container! :local-date-time container parsed-val)
    :local-time
    (compile-time-add-to-container! :local-time container parsed-val)
    :packed-local-date
    (compile-time-add-to-container! :packed-local-date container parsed-val)
    :packed-local-time
    (compile-time-add-to-container! :packed-local-time container parsed-val)
    :packed-local-date-time
    (compile-time-add-to-container! :packed-local-date-time container parsed-val)))


(defmacro datetime-can-parse?
  [datatype str-val]
  `(try
     (compile-time-datetime-parse-str ~datatype ~str-val)
     true
     (catch Throwable e#
       false)))

(def all-datetime-datatypes
  #{:instant :zoned-date-time :offset-date-time :local-date
    :local-date-time :local-time :packed-local-date
    :packed-local-time :packed-local-date-time})

(defn datetime-datatype?
  [dtype]
  (boolean (all-datetime-datatypes dtype)))