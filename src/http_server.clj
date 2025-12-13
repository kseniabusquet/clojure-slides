(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[taoensso.timbre :as log])

(import '[java.net ServerSocket InetSocketAddress]
        '[java.io BufferedReader InputStreamReader PrintWriter OutputStreamWriter])

;; Track file modification times for live reload
(def reload-timestamp (atom (System/currentTimeMillis)))
(def watched-file (atom nil))
(def watched-html-files (atom #{}))

(defn get-content-type [path]
  (cond
    (str/ends-with? path ".html") "text/html"
    (str/ends-with? path ".css")  "text/css"
    (str/ends-with? path ".js")   "text/javascript"
    (str/ends-with? path ".png")  "image/png"
    (str/ends-with? path ".jpg")  "image/jpeg"
    (str/ends-with? path ".gif")  "image/gif"
    :else "text/plain"))

(defn get-html-files
  "Get all HTML files in current directory"
  []
  (->> (io/file ".")
       .listFiles
       (filter #(str/ends-with? (.getName %) ".html"))
       (map #(.getName %))))



(defn start-file-watcher []
  ;; Watch .smd file if provided
  (when @watched-file
    (let [smd-file @watched-file]
      (future
        (loop [last-modified (.lastModified (io/file smd-file))]
          (Thread/sleep 500)
          (let [current-modified (.lastModified (io/file smd-file))]
            (if (> current-modified last-modified)
              (do
                (log/info "File changed, regenerating HTML...")
                (try
                  (let [result (.exec (Runtime/getRuntime)
                                      (into-array ["bb" "-f" "src/slide_markdown.clj" smd-file]))]
                    (.waitFor result)
                    (if (= 0 (.exitValue result))
                      (do
                        (reset! reload-timestamp (System/currentTimeMillis))
                        (log/info "HTML updated and browser will reload!"))
                      (log/error "Error regenerating HTML")))
                  (catch Exception e
                    (log/error (str "Error: " (.getMessage e)))))
                (recur current-modified))
              (recur last-modified)))))))

  ;; Always watch HTML files for changes
  (future
    (loop []
      (Thread/sleep 500)
      (let [html-files (get-html-files)
            current-timestamps (->> html-files
                                    (map (fn [f] [f (.lastModified (io/file f))]))
                                    (into {}))
            previous-timestamps @watched-html-files]
        (doseq [[file-path last-mod] current-timestamps]
          (when-let [prev-mod (get previous-timestamps file-path)]
            (when (> last-mod prev-mod)
              (log/infof "HTML file changed: " file-path)
              (reset! reload-timestamp (System/currentTimeMillis))
              (log/info "Browser will reload!"))))
        (reset! watched-html-files current-timestamps)
        (recur)))))

(defn inject-live-reload-script
  "Inject live reload JavaScript into HTML content"
  [html-content]
  (let [live-reload-script "<script>
(function() {
  let lastReload = null;
  setInterval(() => {
    fetch('/__livereload__', {cache: 'no-cache'})
      .then(response => response.text())
      .then(timestamp => {
        const ts = parseInt(timestamp, 10);
        if (isNaN(ts)) {
          console.error('Invalid timestamp received:', timestamp);
          return;
        }
        if (lastReload === null) {
          lastReload = ts;
          console.log('🔄 Live reload enabled - watching for changes...');
        } else if (ts !== lastReload) {
          console.log('🔄 Live reload: Files changed, refreshing...');
          location.reload();
        }
      })
      .catch((err) => {
        console.error('Live reload check failed:', err);
      });
  }, 1000);
})();
</script>"]
    (if (str/includes? html-content "</body>")
      (str/replace html-content "</body>" (str live-reload-script "</body>"))
      (str html-content live-reload-script))))

(defn start-server [port smd-file-arg]
  ;; Initialize HTML file timestamps
  (let [html-files (get-html-files)
        initial-timestamps (->> html-files
                                (map (fn [f] [f (.lastModified (io/file f))]))
                                (into {}))]
    (reset! watched-html-files initial-timestamps))

  (when smd-file-arg
    (reset! watched-file smd-file-arg))

  (start-file-watcher)

  (log/infof "Starting Clojure HTTP server on http://localhost:%s..." port)
  (if @watched-file
    (log/infof "Live reload enabled for %s and HTML files" @watched-file)
    (log/info "Live reload enabled for HTML files"))

  (try
    (with-open [server-socket (ServerSocket.)]
      (.bind server-socket (InetSocketAddress. port))
      (log/info "✅ Server is up and running!")
      (log/info "Press Ctrl+C to stop")

      (while true
        (with-open [client-socket (.accept server-socket)
                    in (BufferedReader. (InputStreamReader. (.getInputStream client-socket)))
                    out (PrintWriter. (OutputStreamWriter. (.getOutputStream client-socket)) true)]
          (when-let [request-line (.readLine in)]
            (let [parts (str/split request-line (re-pattern " "))
                  path (second parts)
                  path (if (= path "/")
                         (let [html-files (->> (io/file ".")
                                               (.listFiles)
                                               (filter #(.isFile %))
                                               (filter #(str/ends-with? (.getName %) ".html"))
                                               (map #(.getName %)))]
                           (if (seq html-files)
                             (str "/" (first html-files))
                             "/index.html"))
                         path)
                  file-path (str "." path)
                  file (io/file file-path)]

              (cond
                (= path "/__livereload__")
                (do
                  (.println out "HTTP/1.1 200 OK")
                  (.println out "Content-Type: text/plain")
                  (.println out "Cache-Control: no-cache")
                  (.println out "Access-Control-Allow-Origin: *")
                  (.println out "")
                  (.println out (str @reload-timestamp)))

                (.exists file)
                (let [content-type (get-content-type path)
                      content (slurp file)
                      enhanced-content (if (= content-type "text/html")
                                         (inject-live-reload-script content)
                                         content)]
                  (.println out "HTTP/1.1 200 OK")
                  (.println out (str "Content-Type: " content-type))
                  (.println out "Cache-Control: no-cache")
                  (.println out "")
                  (.print out enhanced-content))

                :else
                (do
                  (.println out "HTTP/1.1 404 Not Found")
                  (.println out "Content-Type: text/html")
                  (.println out "")
                  (.println out "<h1>404 Not Found</h1><p>The requested file was not found.</p>"))))))))
    (catch java.net.BindException _e
      (log/errorf "Error: Port %s is already in use" port)
      (log/infof "Please choose a different port or stop the process using port " port)
      (log/info "Example: bb serve 8080 ws-clojure.smd")
      (System/exit 1))))

(defn -main [& args]
  (let [port-str (or (first args) "8080")
        smd-file (second args)]
    (try
      (let [port (Integer/parseInt port-str)]
        (when (or (< port 1) (> port 65535))
          (log/error "Error: Port must be between 1 and 65535")
          (System/exit 1))
        (start-server port smd-file))
      (catch NumberFormatException _
        (log/error "Error: Invalid port number. Please provide a valid port.")
        (log/info "Usage: bb serve [port]")
        (log/info "Example: bb serve 8080")
        (log/infof "You provided: '%s' which is not a valid port number" port-str)
        (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
