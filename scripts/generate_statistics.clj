(ns generate-statistics
  (:require [cheshire.core :as json]
            [babashka.http-client :as http]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(defn load-env-file [file]
  (when (.exists (io/file file))
    (doseq [line (str/split-lines (slurp file))]
      (when (and (not (str/blank? line))
                 (not (str/starts-with? line "#")))
        (let [[k v] (str/split line #"=" 2)]
          (System/setProperty k v))))))

(load-env-file ".env")

(def JOTFORM_API_KEY (or (System/getenv "JOTFORM_API_KEY")
                         (System/getProperty "JOTFORM_API_KEY")
                         (throw (ex-info "JOTFORM_API_KEY environment variable not set" {}))))

(def FORM_ID (or (System/getenv "FORM_ID")
                 (System/getProperty "FORM_ID")
                 (throw (ex-info "FORM_ID environment variable not set" {}))))

(defn authenticate []
  (try
    (let [url (format "https://api.jotform.com/user?apiKey=%s" JOTFORM_API_KEY)
          response (http/get url)]
      (-> response :body (json/parse-string true)))
    (catch Exception e
      (prn "Authentication failed:" (.getMessage e)))))

(defn get-submissions []
  (try
    (let [url (format "https://api.jotform.com/form/%s/submissions?apiKey=%s" FORM_ID JOTFORM_API_KEY)
          response (http/get url)
          content (-> response
                      :body
                      (json/parse-string true)
                      :content)
          answers (map :answers content)
          total-answers (count answers)
          answers-grouped (->> answers
                               (map vals)
                               flatten
                               (remove #(#{1 11 12} (:order %)))
                               (map #(select-keys % [:text :prettyFormat]))
                               (group-by :text))
          frequencies #(hash-map :question (:text %)
                                 :frequencies (map :prettyFormat))
          fn-generate-frequencies (fn [[key value]]
                                    {:question key
                                     :frequencies (frequencies (map :prettyFormat value))})
          frequencies (map fn-generate-frequencies answers-grouped)])
    (catch Exception e
      (prn "Submissions fetching failed:" (.getMessage e)))))
