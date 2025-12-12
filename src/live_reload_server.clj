(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(import '[java.net ServerSocket InetSocketAddress]
        '[java.io BufferedReader InputStreamReader PrintWriter OutputStreamWriter])

(def reload-timestamp (atom (System/currentTimeMillis)))
(def watched-file (atom nil))

(defn get-content-type [path]
  (cond
    (str/ends-with? path ".html") "text/html"
    (str/ends-with? path ".css")  "text/css"
    (str/ends-with? path ".js")   "text/javascript"
    (str/ends-with? path ".png")  "image/png"
    (str/ends-with? path ".jpg")  "image/jpeg"
    (str/ends-with? path ".gif")  "image/gif"
    :else "text/plain"))

(defn find-first-html-file []
  (let [html-files (->> (file-seq (io/file "."))
                       (filter #(.isFile %))
                       (map #(.getName %))
                       (filter #(str/ends-with? % ".html")))]
    (first html-files)))

(defn inject-live-reload-script [html-content]
  (let [live-reload-script "<script>
setInterval(() => {
  fetch('/reload-check')
    .then(r => r.text())
    .then(timestamp => {
      if (window.lastReload && timestamp !== window.lastReload) {
        console.log('🔄 Live reload: File changed, reloading page...');
        window.location.reload();
      }
      window.lastReload = timestamp;
    })
    .catch(() => {});
}, 1000);
console.log('🔄 Live reload enabled - watching for changes...');
</script>"]
    (if (str/includes? html-content "</body>")
      (str/replace html-content "</body>" (str live-reload-script "</body>"))
      (str html-content live-reload-script))))

(defn start-file-watcher []
  (when @watched-file
    (let [smd-file @watched-file]
      (prn (str "Watching " smd-file " for changes..."))
      (future
        (loop [last-modified (.lastModified (io/file smd-file))]
          (Thread/sleep 500)
          (let [current-modified (.lastModified (io/file smd-file))]
            (if (> current-modified last-modified)
              (do
                (prn "File changed, regenerating HTML...")
                (try
                  (let [result (.exec (Runtime/getRuntime)
                                     (into-array ["bb" "-f" "src/slide_markdown.clj" smd-file]))]
                    (.waitFor result)
                    (if (= 0 (.exitValue result))
                      (do
                        (reset! reload-timestamp (System/currentTimeMillis))
                        (prn "HTML updated and browser will reload!"))
                      (prn "Error regenerating HTML")))
                  (catch Exception e
                    (prn (str "Error: " (.getMessage e)))))
                (recur current-modified))
              (recur last-modified))))))))

(defn start-server [port smd-file-arg]
  (when smd-file-arg
    (reset! watched-file smd-file-arg)
    (start-file-watcher))

  (prn "Starting Clojure HTTP server with live reload...")
  (prn (str "Open http://localhost:" port))
  (if @watched-file
    (prn (str "Live reload enabled for " @watched-file))
    (prn "Add .smd filename as argument to enable live reload: bb serve <port> <file.smd>"))
  (prn "Press Ctrl+C to stop")

  (with-open [server-socket (ServerSocket.)]
    (.bind server-socket (InetSocketAddress. port))
    (prn (str "✅ Server running on http://localhost:" port))

    (while true
      (with-open [client-socket (.accept server-socket)
                 in (BufferedReader. (InputStreamReader. (.getInputStream client-socket)))
                 out (PrintWriter. (OutputStreamWriter. (.getOutputStream client-socket)) true)]
        (when-let [request-line (.readLine in)]
          (let [parts (str/split request-line (re-pattern " "))
                path (second parts)
                path (if (= path "/")
                       (str "/" (or (find-first-html-file) "index.html"))
                       path)
                file-path (str "." path)
                file (io/file file-path)]

            (cond
              (= path "/reload-check")
              (do
                (.println out "HTTP/1.1 200 OK")
                (.println out "Content-Type: text/plain")
                (.println out "Cache-Control: no-cache")
                (.println out "")
                (.println out (str @reload-timestamp)))

              (.exists file)
              (let [content-type (get-content-type path)
                    content (slurp file)
                    enhanced-content (if (and @watched-file (= content-type "text/html"))
                                      (inject-live-reload-script content)
                                      content)]
                (.println out "HTTP/1.1 200 OK")
                (.println out (str "Content-Type: " content-type))
                (.println out "")
                (.print out enhanced-content))

              :else
              (do
                (.println out "HTTP/1.1 404 Not Found")
                (.println out "Content-Type: text/html")
                (.println out "")
                (.println out "<h1>404 Not Found</h1><p>The requested file was not found.</p>")))))))))

(defn -main [& args]
  (let [port-str (or (first args) "8080")
        smd-file (second args)]
    (try
      (let [port (Integer/parseInt port-str)]
        (when (or (< port 1) (> port 65535))
          (prn "Error: Port must be between 1 and 65535")
          (System/exit 1))
        (start-server port smd-file))
      (catch NumberFormatException _
        (prn "Error: Invalid port number. Please provide a valid port.")
        (prn "Usage: bb serve [port] [file.smd]")
        (prn "Example: bb serve 8080 presentation.smd")
        (prn (str "You provided: '" port-str "' which is not a valid port number"))
        (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
