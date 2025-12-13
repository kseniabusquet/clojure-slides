(require '[clojure.java.io :as io]
         '[clojure.string :as str])
(import '[java.net ServerSocket]
        '[java.io BufferedReader InputStreamReader PrintWriter FileInputStream])

(defn generate-html-with-livereload [smd-file]
  (load-file "src/slide_markdown.clj")
  (let [parsed-data ((resolve 'slide-markdown/parse-smd-file) (slurp smd-file))
        base-dir (.getParent (io/file smd-file))
        original-html ((resolve 'slide-markdown/generate-html) parsed-data base-dir)
        live-reload-script "<script>
          setInterval(() => {
            fetch('/reload-check')
              .then(r => r.text())
              .then(timestamp => {
                if (window.lastReload && timestamp !== window.lastReload) {
                  window.location.reload();
                }
                window.lastReload = timestamp;
              })
              .catch(() => {});
          }, 1000);
        </script>"
        enhanced-html (str/replace original-html #"</body>" (str live-reload-script "</body>"))]
    enhanced-html))

(defn start-file-watcher [smd-file html-file reload-timestamp-atom]
  (future
    (loop [last-modified (.lastModified (io/file smd-file))]
      (Thread/sleep 500)
      (let [current-modified (.lastModified (io/file smd-file))]
        (if (> current-modified last-modified)
          (do
            (println "File changed, regenerating...")
            (try
              (let [enhanced-html (generate-html-with-livereload smd-file)]
                (spit html-file enhanced-html)
                (reset! reload-timestamp-atom (System/currentTimeMillis))
                (println "HTML updated with live reload!"))
              (catch Exception e
                (println (str "Error: " (.getMessage e)))))
            (recur current-modified))
          (recur last-modified))))))

(defn handle-request [in out reload-timestamp-atom]
  (let [request-line (.readLine in)]
    (when request-line
      (let [[_method path] (str/split request-line #" " 2)
            path (or path "/")]
        (cond
          (= path "/reload-check")
          (do
            (.println out "HTTP/1.1 200 OK")
            (.println out "Content-Type: text/plain")
            (.println out "Access-Control-Allow-Origin: *")
            (.println out "")
            (.println out (str @reload-timestamp-atom)))

          :else
          (let [file-path (if (= path "/") "/index.html" path)
                file (io/file (str "." file-path))
                is-image? (re-find #"\.(jpeg|jpg|png|gif|svg)$" file-path)]
            (if (.exists file)
              (do
                (.println out "HTTP/1.1 200 OK")
                (.println out (str "Content-Type: " (cond
                                                    (str/ends-with? file-path ".html") "text/html"
                                                    (str/ends-with? file-path ".css") "text/css"
                                                    (str/ends-with? file-path ".js") "application/javascript"
                                                    (str/ends-with? file-path ".jpeg") "image/jpeg"
                                                    (str/ends-with? file-path ".jpg") "image/jpeg"
                                                    (str/ends-with? file-path ".png") "image/png"
                                                    (str/ends-with? file-path ".gif") "image/gif"
                                                    (str/ends-with? file-path ".svg") "image/svg+xml"
                                                    :else "text/plain")))
                (.println out "")
                (.flush out)
                (if is-image?
                  ;; Para imagens, usar InputStream e copiar bytes
                  (with-open [fis (java.io.FileInputStream. file)
                              os (.getOutputStream (.getSocket out))]
                    (io/copy fis os))
                  ;; Para texto, usar slurp normal
                  (.print out (slurp file))))
              (do
                (.println out "HTTP/1.1 404 Not Found")
                (.println out "Content-Type: text/html")
                (.println out "")
                (.println out "<h1>404 - File not found</h1>")))))))))

(defn -main [& args]
  (let [smd-file (first args)
        port (Integer/parseInt (or (second args) "8080"))]
    (when-not smd-file
      (println "Usage: bb serve.clj <file.smd> [port]")
      (System/exit 1))

    (let [html-file (str/replace smd-file #"\.smd$" ".html")
          reload-timestamp-atom (atom (System/currentTimeMillis))]

      ;; Generate initial HTML
      (println "Generating initial HTML...")
      (spit html-file (generate-html-with-livereload smd-file))

      ;; Start file watcher
      (start-file-watcher smd-file html-file reload-timestamp-atom)

      ;; Start HTTP server
      (println (str "Starting server on http://localhost:" port))
      (println (str "Open http://localhost:" port "/" html-file))
      (println "Live reload enabled - browser will refresh automatically")
      (println "Press Ctrl+C to stop")

      (try
        (with-open [server-socket (ServerSocket. port)]
          (while true
            (try
              (with-open [client-socket (.accept server-socket)
                         in (BufferedReader. (InputStreamReader. (.getInputStream client-socket)))
                         out (PrintWriter. (.getOutputStream client-socket) true)]
                (handle-request in out reload-timestamp-atom))
              (catch Exception e
                (println (str "Request error: " (.getMessage e)))))))
        (catch Exception e
          (println (str "Server error: " (.getMessage e))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
