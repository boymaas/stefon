(ns stefon.jsengine
  (:require [stefon.settings :as settings]
            [clj-time.core :as time]
            [clj-time.coerce :as time-coerce]
            [clojure.java.io :as io]
            [stefon.pools :as pools]
            [stefon.v8 :as v8]))

(def memoized (atom {}))
(defn- memoize-file [filename f]
  "Ability to cache precomputed files using timestamps (avoiding the term \"cache\" since it'ss already overloaded here)"
  (let [val (get @memoized filename)
        current-timestamp (-> filename io/file .lastModified time-coerce/from-long)
        saved-timestamp (:timestamp val)
        saved-content (:content val)]
    (if (and saved-content
             (time/before? current-timestamp saved-timestamp))

      ;; return already memory
      saved-content

      ;; compute new value and save it
      (let [new-content (f)]
        (dosync
         (swap! memoized assoc filename {:content new-content
                                         :timestamp (time/now)}))
        new-content))))

;; TODO: take an asset to avoid slurping here
(defn- run-compiler [pool preloads fn-name content filename]
  (try
    (let [file (io/file filename)
          content (String. content "UTF-8")
          absolute (.getAbsolutePath file)]
      (v8/with-scope pool preloads
        (v8/call fn-name [content absolute filename])))
    (catch Exception e
      (let [ste (StackTraceElement. "jsengine"
                                    fn-name filename -1)
            st (.getStackTrace e)
            new-st (into [ste ] st)
            new-st-array (into-array StackTraceElement new-st)]
        (.setStackTrace e new-st-array)
        (throw e)))))

(defn compiler [fn-name preloads]
  (let [pool (pools/make-pool)]
    (fn [filename content]
      (memoize-file filename
                    #(run-compiler pool preloads fn-name content filename)))))
