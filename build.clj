(ns build
  (:require
   [shadow.css.build :as cb]
   [clojure.java.io :as io]
   [nextjournal.beholder :as beholder]
   [clojure.pprint :as pp]
   ))

(defn css [_]
  (println "[" (str (java.time.Instant/now)) "]" "Compiling CSS")
  (let [build-state
        (-> (cb/start)
             (assoc :preflight-src "")
            (cb/index-path (io/file "src") {})
            (cb/generate '{:main {:entries [app.main]}})
            (cb/minify)
            (cb/write-outputs-to (io/file "resources" "public" "css" "gen")))]
    (doseq [mod (vals (:chunks build-state))
            {:keys [warning-type] :as warning} (:warnings mod)]
      (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))
    (println "[" (str (java.time.Instant/now)) "]" "Done Compiling CSS")))

(defn watch-css [_]
  (css nil)
  (let [paths ["src"]]
    (println "[" (str (java.time.Instant/now)) "]" "Watching files for changes." {:paths paths})
    (apply beholder/watch-blocking (fn [_] (css nil)) paths)))
