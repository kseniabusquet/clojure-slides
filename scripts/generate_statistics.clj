(ns generate-statistics
  (:require [cheshire.core :as json]
            [babashka.http-client :as http]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]))

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

(def irrelevant-question-ids #{1 11 12})

(defn authenticate []
  (try
    (let [url (format "https://api.jotform.com/user?apiKey=%s" JOTFORM_API_KEY)
          response (http/get url)]
      (-> response :body (json/parse-string true)))
    (catch Exception e
      (log/errorf "Authentication failed: %s" (.getMessage e)))))

(defn get-submissions []
  (try
    (let [url (format "https://api.jotform.com/form/%s/submissions?apiKey=%s" FORM_ID JOTFORM_API_KEY)
          response (http/get url)]
      (-> response :body (json/parse-string true)))
    (catch Exception e
      (log/errorf "Submissions fetching failed:" (.getMessage e)))))

(defn analyze-frequencies []
  (let [data (get-submissions)
        submissions (:content data)]
    (when submissions
      (let [all-answers (mapcat (comp vals :answers) submissions)
            relevant-answers (filter #(and (:prettyFormat %)
                                           (not (contains? irrelevant-question-ids (:order %))))
                                     all-answers)
            questions-grouped (group-by :text relevant-answers)
            treat-answers-fn (fn [[question-text answers]]
                               (let [answer-frequencies (frequencies (map :prettyFormat answers))
                                     sorted-answers (sort-by second > answer-frequencies)]
                                 {:question question-text
                                  :total-responses (count answers)
                                  :answers (into {} sorted-answers)}))]

        {:total-submissions (count submissions)
         :questions (map treat-answers-fn questions-grouped)}))))

(defn generate-pie-chart-html
  "Generate HTML for a Chart.js pie chart"
  [{:keys [answers question total-responses]}]
  (let [labels (vec (keys answers))
        data (vec (vals answers))
        chart-id (str "chart-" (hash question))
        clean-id (str/replace chart-id #"-" "")]
    (format "
<div class=\"pie-chart-container\">
  <h4>%s</h4>
  <div class=\"pie-chart-wrapper\">
    <canvas id=\"%s\"></canvas>
  </div>
  <p class=\"response-count\">%d respostas</p>
</div>

<script>
const ctx%s = document.getElementById('%s').getContext('2d');
new Chart(ctx%s, {
    type: 'pie',
    data: {
        labels: %s,
        datasets: [{
            data: %s,
            backgroundColor: [
                '#FF6384', '#36A2EB', '#FFCE56', '#4BC0C0',
                '#9966FF', '#FF9F40', '#FF6384', '#C9CBCF'
            ],
            borderColor: '#ffffff',
            borderWidth: 2
        }]
    },
    options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: {
            legend: {
                position: 'bottom',
                labels: {
                    font: {
                        size: 10
                    },
                    boxWidth: 12,
                    padding: 4,
                    usePointStyle: true,
                    generateLabels: function(chart) {
                        const data = chart.data;
                        if (data.labels.length && data.datasets.length) {
                            return data.labels.map((label, i) => {
                                const meta = chart.getDatasetMeta(0);
                                const style = meta.controller.getStyle(i);
                                return {
                                    text: label.length > 25 ? label.substring(0, 25) + '...' : label,
                                    fillStyle: style.backgroundColor,
                                    strokeStyle: style.borderColor,
                                    lineWidth: style.borderWidth,
                                    pointStyle: 'circle',
                                    hidden: isNaN(data.datasets[0].data[i]) || meta.data[i].hidden,
                                    index: i
                                };
                            });
                        }
                        return [];
                    }
                }
            }
        }
    }
});
</script>"
            question
            chart-id
            total-responses
            clean-id
            chart-id
            clean-id
            (json/generate-string labels)
            (json/generate-string data))))

(defn generate-survey-stats-smd
  "Generate SMD content with survey statistics and charts"
  []
  (let [{:keys [total-submissions questions]} (analyze-frequencies)
        first-four (take 4 questions)
        last-four (drop 4 questions)
        charts-html (str
"<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>

<style>
.charts-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 6px;
    padding: 3px;
    margin-bottom: 8px;
}
.charts-grid.bottom {
    margin-top: 5px;
}
.pie-chart-container {
    background: #f9f9f9;
    border-radius: 3px;
    padding: 6px;
    text-align: center;
    box-shadow: 0 1px 3px rgba(0,0,0,0.1);
}
.pie-chart-container h4 {
    margin: 0 0 6px 0;
    font-size: 0.55em;
    color: #333;
    line-height: 1.1;
    height: 3.2em;
    overflow: hidden;
    font-weight: bold;
}
.pie-chart-wrapper {
    position: relative;
    height: 180px;
    margin: 6px 0;
}
.response-count {
    margin: 2px 0 0 0;
    font-size: 0.5em;
    color: #666;
    font-weight: bold;
}
.stats-header {
    text-align: center;
    background: #7EBA46;
    color: white;
    padding: 6px;
    border-radius: 3px;
    margin-bottom: 6px;
}
.stats-header h2 {
    margin: 0 0 1px 0;
    font-size: 0.95em;
}
.stats-header p {
    margin: 0;
    font-size: 0.65em;
}
/* Chart.js legend text wrapping */
.pie-chart-container canvas {
    max-width: 100%;
}
.pie-chart-container .chartjs-legend {
    text-align: center;
}
.pie-chart-container .chartjs-legend ul {
    margin: 0 !important;
    padding: 0 !important;
    list-style: none;
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 4px;
}
.pie-chart-container .chartjs-legend li {
    display: flex;
    align-items: center;
    margin: 2px !important;
    font-size: 10px;
    white-space: normal;
    word-wrap: break-word;
    max-width: 120px;
}
</style>

<div class=\"stats-header\">
<h2>Total Participantes: " total-submissions "</h2>
</div>

<div class=\"charts-grid\">
"
                     (str/join "\n" (map generate-pie-chart-html first-four))
"
</div>

<div class=\"charts-grid bottom\">
"
                     (str/join "\n" (map generate-pie-chart-html last-four))
"
</div>

<script>
document.addEventListener('DOMContentLoaded', function() {

});
</script>")]
    (str
     "\n-*-*- [survey-stats] Estatísticas do Workshop

## Resultados do questionário em tempo real" charts-html)))

(defn save-survey-stats-slide
  "Generate and save survey statistics as SMD file (replaces existing survey content if present)"
  [smd-filename]
  (if (.exists (java.io.File. smd-filename))
    (let [file-content (slurp smd-filename)
          survey-start-pattern #"\n-\*-\*- \[survey-stats\].*?(?=\n-\*-\*-|\n#|\nEnd|\nend|\Z)"
          cleaned-content (clojure.string/replace file-content survey-start-pattern "")]
      (spit smd-filename cleaned-content)
      (spit smd-filename (generate-survey-stats-smd) :append true))
    (spit smd-filename (generate-survey-stats-smd)))
  (log/infof "Survey statistics slide saved to: %s" smd-filename)
  (log/infof "Total submissions: %d" (:total-submissions (analyze-frequencies))))
