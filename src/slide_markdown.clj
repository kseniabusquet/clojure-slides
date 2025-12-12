(ns slide-markdown
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [markdown.core :as md])
  (:import [java.util Base64]
           [java.io FileInputStream ByteArrayOutputStream]))

;; --- Helper: CDN Asset Caching ---

(defn- fetch-cdn-asset
  "Fetches a text asset from a URL. Caches it locally in .slide-cache"
  [url file-name]
  (let [cache-dir (io/file ".slide-cache")
        cache-file (io/file cache-dir file-name)]
    (when-not (.exists cache-dir)
      (.mkdirs cache-dir))
    (if (.exists cache-file)
      (slurp cache-file)
      (let [content (slurp url)]
        (spit cache-file content)
        content))))

;; --- Core Parsing Logic ---

(defn- parse-slide-header
  "Parses the slide header (e.g., '[template-id] Title')."
  [header-line]
  (if-let [[_ id title] (re-find #"^\[([\w\d-]+)\]\s*(.*)$" header-line)]
    {:template-id id
     :title (when-not (str/blank? title) title)}
    {:template-id nil
     :title (when-not (str/blank? header-line) header-line)}))

(defn parse-slide-content
  "Parses a raw slide string into a structured map."
  [raw-slide]
  (let [[header-line & rest-lines] (str/split-lines raw-slide)
        content-str (str/join "\n" rest-lines)
        blocks (->> (str/split content-str #"\n\s*\n")
                    (map str/trim)
                    (remove str/blank?)
                    vec)]
    (assoc (parse-slide-header header-line) :markdown-blocks blocks)))

(defn parse-smd-file [file-content]
  (let [[edn-header slides-content] (str/split file-content #"\nEND\n" 2)]
    (when-not slides-content
      (throw (ex-info "Invalid .smd file: 'END' separator not found." {})))
    {:meta (edn/read-string edn-header)
     :slides (->> (str/split slides-content #"-\*-\*-\s*")
                  (remove str/blank?)
                  (map parse-slide-content)
                  vec)}))

;; --- Validation Logic ---

(defn- get-template-map [templates]
  (into {} (map (juxt :id identity) templates)))

(defn validate-presentation-data
  [{:keys [meta slides] :as presentation-data}]
  (let [templates (:templates meta)]
    (when (empty? templates)
      (throw (ex-info "Validation Failed: No templates found." {})))
    (let [template-map (get-template-map templates)
          default-id (-> templates first :id)]
      (doseq [[i slide] (map-indexed vector slides)]
        (let [t-id (or (:template-id slide) default-id)
              template (get template-map t-id)]
          (when-not template
            (throw (ex-info (str "Slide " (inc i) " uses unknown template '" t-id "'.") {})))

          (let [expected (count (:elements template))
                provided (count (:markdown-blocks slide))]
            (when (< provided expected)
              (throw (ex-info (str "Slide " (inc i) " expects at least " expected
                                   " blocks, but provided " provided ".") {}))))))))
  presentation-data)

;; --- Media Generation ---

(def mime-types
  {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg"
   "gif" "image/gif" "svg" "image/svg+xml" "webp" "image/webp"
   "mp4" "video/mp4" "webm" "video/webm" "ogg" "video/ogg"})

(defn- get-mime-type [path]
  (let [ext (second (re-find #"\.([a-zA-Z0-9]+)$" path))]
    (get mime-types (str/lower-case (or ext "")) "application/octet-stream")))

(defn- encode-file-to-base64 [base-dir file-path]
  (let [file (io/file base-dir file-path)]
    (when-not (.exists file)
      (throw (ex-info (str "Media file not found: " file-path) {:path (str file)})))
    (with-open [in (FileInputStream. file) out (ByteArrayOutputStream.)]
      (io/copy in out)
      (.encodeToString (Base64/getEncoder) (.toByteArray out)))))

(defn- extract-image-path [block]
  (or (second (re-find #"!\[.*\]\((.*?)\)" block)) block))

;; --- HTML Generation ---

(defn- pair-greedy [elements blocks]
  (let [idx (dec (count elements))
        [head-els [last-el]] (split-at idx elements)
        [head-bls tail-bls]  (split-at idx blocks)]
    (conj (vec (map vector head-els head-bls))
          [last-el (str/join "\n\n" tail-bls)])))

(defn pair-elements-with-blocks [elements blocks]
  (if (>= (count blocks) (count elements))
    (pair-greedy elements blocks)
    (map vector elements blocks)))

(defn wrap-element
  "Wraps the inner HTML in the standard content-element div."
  [specific-class style inner-html]
  (str "<div class=\"content-element " specific-class "\" "
       "style=\"position: absolute; " (or style "") "\">"
       inner-html
       "</div>"))

(defmulti render-slide-element (fn [element _ _] (:type element)))

(defmethod render-slide-element "text" [element block _]
  (let [html (md/md-to-html-string block :spec :gfm)
        fixed-html (str/replace html #"<code class=\"([^\"]+)\">" "<code class=\"language-$1\">")]
    (wrap-element "text-content" (:style element) fixed-html)))

(defmethod render-slide-element "image" [element block base-dir]
  (let [path (extract-image-path block)
        mime (get-mime-type path)
        b64 (encode-file-to-base64 base-dir path)
        img-tag (str "<img src=\"data:" mime ";base64," b64 "\" alt=\"Embedded Image\">")]
    (wrap-element "image-content" (:style element) img-tag)))

(defmethod render-slide-element "video" [element block base-dir]
  (let [mime (get-mime-type block)
        b64 (encode-file-to-base64 base-dir block)
        opts (str (when (:controls element true) "controls ")
                  (when (:autoplay element) "autoplay muted"))
        video-tag (str "<video " opts " src=\"data:" mime ";base64," b64 "\"></video>")]
    (wrap-element "video-content" (:style element) video-tag)))

(defmethod render-slide-element "html" [element block _]
  "Renders raw HTML content directly (useful for charts, widgets, etc.)"
  (wrap-element "html-content" (:style element) block))

(defmethod render-slide-element :default [element _ _]
  (str "<div style=\"" (:style element) "\">Unsupported: " (:type element) "</div>"))

(defn- generate-slide-content [template-elements markdown-blocks base-dir]
  (str/join "\n" (for [[el bl] (pair-elements-with-blocks template-elements markdown-blocks)
                       :when bl]
                   (render-slide-element el bl base-dir))))

(defn- generate-slide-html [slide index template-map default-id base-dir]
  (let [template (get template-map (or (:template-id slide) default-id))
        bg-style (or (:style template) "background: #000")]
    (str "<div class=\"slide\" id=\"slide-" index "\" style=\"" bg-style "\">"
         (generate-slide-content (:elements template) (:markdown-blocks slide) base-dir)
         "</div>")))

(defn- generate-slide-options [slides]
  (str/join "\n" (map-indexed (fn [i s]
                                (let [label (str (inc i) (when (:title s) (str " - " (:title s))))]
                                  (str "<option value=\"" i "\">" label "</option>")))
                              slides)))

(defn- generate-css []
  (let [prism-css (fetch-cdn-asset "https://cdnjs.cloudflare.com/ajax/libs/prism/1.30.0/themes/prism-okaidia.min.css" "prism-okaidia.min.css")]
    (str "<style>" prism-css "
      body, html { margin: 0; padding: 0; height: 100%; width: 100%; overflow: hidden; background-color: #111; }
      #presentation-container { display: flex; justify-content: center; align-items: center; width: 100%; height: 100%; }
      #viewport { width: 100vw; height: 56.25vw; max-height: 100vh; max-width: 177.78vh; position: relative; overflow: hidden; background-color: #000; box-shadow: 0 0 20px rgba(0,0,0,0.5); }
      .slide { width: 100%; height: 100%; font-size: 4.2vh; position: absolute; top: 0; left: 0; opacity: 0; visibility: hidden; transition: opacity 0.5s, visibility 0.5s; }
      .slide.active { opacity: 1; visibility: visible; z-index: 1; }
      .content-element { box-sizing: border-box; padding: 1em 1em 1em 1em; padding-top: 0; }
      .text-content h1, .text-content h2, .text-content p, .text-content ul { margin: 0.5em 0; margin-top: 0; }
      .content-element img, .content-element video { max-width: 100%; max-height: 100%; display: block; margin: auto; }
      .html-content { padding: 0; }
      .html-content .chart-container { background: #f9f9f9; border-radius: 8px; padding: 15px; margin: 15px 0; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
      .html-content .stats-header { text-align: center; background: #7EBA46; color: white; padding: 15px; border-radius: 8px; margin-bottom: 20px; }
      .html-content canvas { max-width: 100%; height: auto; }
      #nav-menu { position: fixed; top: 15px; left: 15px; z-index: 100; background-color: rgba(0,0,0,0.7); border-radius: 5px; padding: 8px; display: flex; gap: 5px; transition: opacity 0.3s; }
      #nav-menu.hidden { opacity: 0; pointer-events: none; }
      #nav-menu button, #nav-menu select { background-color: #444; color: white; border: none; border-radius: 3px; padding: 5px 8px; cursor: pointer; font-size: 16px; }
      pre[class*=\"language-\"] { background: transparent; margin: 0; }
    </style>")))

(defn- generate-js [count]
  (let [core (fetch-cdn-asset "https://cdnjs.cloudflare.com/ajax/libs/prism/1.30.0/prism.min.js" "prism-core.min.js")
        clj  (fetch-cdn-asset "https://cdnjs.cloudflare.com/ajax/libs/prism/1.30.0/components/prism-clojure.min.js" "prism-clojure.min.js")]
    (str "<script>" core "\n" clj "</script>"
         "<script>
          let currentSlide = 0; const total = " count ";
          const slides = document.querySelectorAll('.slide');
          const select = document.getElementById('slide-select');
          const menu = document.getElementById('nav-menu');
          let timer;
          const STORAGE_KEY = 'slide-md-current-slide';

          // Save slide position to localStorage and URL hash
          function saveSlidePosition(idx) {
            try {
              localStorage.setItem(STORAGE_KEY, idx.toString());
              // Update hash without triggering navigation
              if (window.history && window.history.replaceState) {
                window.history.replaceState(null, '', '#slide-' + idx);
              } else {
                window.location.hash = 'slide-' + idx;
              }
            } catch (e) {
              // localStorage might not be available
            }
          }

          // Save position before page unload
          window.addEventListener('beforeunload', () => {
            saveSlidePosition(currentSlide);
          });

          // Restore slide position from URL hash or localStorage
          function getSavedSlidePosition() {
            // First check URL hash
            const hashMatch = window.location.hash.match(/slide-(\\d+)/);
            if (hashMatch) {
              const idx = parseInt(hashMatch[1], 10);
              if (idx >= 0 && idx < total) return idx;
            }
            // Fall back to localStorage
            try {
              const saved = localStorage.getItem(STORAGE_KEY);
              if (saved !== null) {
                const idx = parseInt(saved, 10);
                if (idx >= 0 && idx < total) return idx;
              }
            } catch (e) {
              // localStorage might not be available
            }
            return 0;
          }

          // Re-initialize charts in a slide when it becomes visible
          function initializeChartsInSlide(slideElement) {
            if (!window.Chart) {
              // Chart.js not loaded yet, try again later
              setTimeout(() => initializeChartsInSlide(slideElement), 100);
              return;
            }

            // Find all canvas elements in this slide
            const canvases = slideElement.querySelectorAll('canvas[id^=\"chart\"]');
            if (canvases.length === 0) return;

            canvases.forEach(canvas => {
              // Check if chart already exists using Chart.js API
              let existingChart = null;
              try {
                if (window.Chart && window.Chart.getChart) {
                  existingChart = window.Chart.getChart(canvas);
                }
              } catch (e) {
                // Chart.getChart might not be available in older versions
              }

              if (existingChart) {
                // Chart exists, try to resize it
                try {
                  if (typeof existingChart.resize === 'function') {
                    existingChart.resize();
                  }
                } catch (e) {
                  // Ignore resize errors
                }
                return;
              }

              // Find the script that should initialize this chart
              const chartId = canvas.id;
              const scripts = Array.from(slideElement.querySelectorAll('script'));

              for (const script of scripts) {
                // Look for script that contains this chart ID
                if (script.textContent && script.textContent.includes(chartId) &&
                    script.textContent.includes('getElementById') &&
                    script.textContent.includes('new Chart')) {

                  // Check if this script has already been executed for this canvas
                  const executionKey = 'chart-executed-' + chartId;
                  if (script.dataset[executionKey] === 'true') {
                    // Already executed, but chart doesn't exist - try again
                    script.dataset[executionKey] = 'false';
                  }

                  if (script.dataset[executionKey] === 'true') continue;

                  try {
                    // Mark as executed
                    script.dataset[executionKey] = 'true';

                    // Execute the script content
                    const scriptContent = script.textContent.trim();
                    if (scriptContent) {
                      // Create a new script element to execute in proper context
                      const newScript = document.createElement('script');
                      newScript.textContent = scriptContent;
                      // Append to the slide element so it has access to the canvas
                      slideElement.appendChild(newScript);
                      // Remove after a short delay
                      setTimeout(() => {
                        if (newScript.parentNode) {
                          newScript.parentNode.removeChild(newScript);
                        }
                      }, 100);
                    }
                  } catch (e) {
                    console.warn('Failed to initialize chart for ' + chartId + ':', e);
                    // Reset execution flag on error so we can try again
                    script.dataset[executionKey] = 'false';
                  }
                  break; // Found the script for this chart
                }
              }
            });
          }

          function show(idx) {
            if (idx < 0 || idx >= total) return;
            slides[currentSlide].classList.remove('active');
            currentSlide = idx;
            slides[currentSlide].classList.add('active');
            if (select) select.value = currentSlide;
            saveSlidePosition(currentSlide);

            // Re-initialize charts in the newly visible slide
            // Use a longer delay to ensure Chart.js is loaded and slide transition is complete
            setTimeout(() => {
              initializeChartsInSlide(slides[currentSlide]);
            }, 300);
          }
          function next() { show(currentSlide + 1); }
          function prev() { show(currentSlide - 1); }
          function menuVis() {
            menu.classList.remove('hidden');
            clearTimeout(timer);
            timer = setTimeout(() => menu.classList.add('hidden'), 3000);
          }
          document.addEventListener('keydown', e => {
            if (e.key === 'ArrowRight') next();
            if (e.key === 'ArrowLeft') prev();
          });
          document.addEventListener('mousemove', menuVis);
          document.getElementById('btn-prev').addEventListener('click', prev);
          document.getElementById('btn-next').addEventListener('click', next);
          document.getElementById('btn-fullscreen').addEventListener('click', () => {
            (!document.fullscreenElement) ? document.documentElement.requestFullscreen() : document.exitFullscreen();
          });
          select.addEventListener('change', e => show(parseInt(e.target.value)));

          // Handle hash changes (e.g., browser back/forward)
          window.addEventListener('hashchange', () => {
            const saved = getSavedSlidePosition();
            if (saved !== currentSlide) {
              show(saved);
            }
          });

          // Wait for Chart.js to load - check all slides for Chart.js scripts
          function waitForChartJS(callback) {
            if (window.Chart) {
              callback();
              return;
            }

            // Check if Chart.js script exists anywhere in the document
            const chartScripts = document.querySelectorAll('script[src*=\"chart.js\"], script[src*=\"Chart.js\"]');
            if (chartScripts.length > 0) {
              // Wait for script to load
              let attempts = 0;
              const checkInterval = setInterval(() => {
                if (window.Chart) {
                  clearInterval(checkInterval);
                  callback();
                } else if (attempts++ > 100) {
                  // Give up after 10 seconds
                  clearInterval(checkInterval);
                  console.warn('Chart.js did not load in time');
                  callback();
                }
              }, 100);
            } else {
              // No Chart.js scripts found, proceed anyway
              callback();
            }
          }

          // Restore saved position on load (after ensuring Chart.js is ready)
          waitForChartJS(() => {
            setTimeout(() => {
              const savedIdx = getSavedSlidePosition();
              show(savedIdx);
              // Also initialize charts in the initial slide
              setTimeout(() => {
                initializeChartsInSlide(slides[savedIdx]);
              }, 500);
            }, 200);
          });
          menuVis();
          Prism.highlightAll();
         </script>")))

(defn generate-html [{:keys [meta slides]} base-dir]
  (let [template-map (get-template-map (:templates meta))
        default-id (-> meta :templates first :id)]
    (str "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
         "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
         "<title>" (or (:title meta) "Presentation") "</title>"
         (generate-css) "</head><body>"
         "<div id=\"nav-menu\">"
         "<button id=\"btn-prev\">&#9664;</button><button id=\"btn-next\">&#9654;</button>"
         "<select id=\"slide-select\">" (generate-slide-options slides) "</select>"
         "<button id=\"btn-fullscreen\">&#x26F6;</button></div>"
         "<div id=\"presentation-container\"><div id=\"viewport\">"
         (str/join "\n" (map-indexed #(generate-slide-html %2 %1 template-map default-id base-dir) slides))
         "</div></div>" (generate-js (count slides)) "</body></html>")))

(defn smd->html [file input-file]
  (let [path (.getCanonicalPath file)
        output (str/replace input-file #"\.smd$" ".html")
        data (-> (slurp path) parse-smd-file validate-presentation-data)]
    (println "Generating HTML...")
    (spit output (generate-html data (.getParent (io/file path))))
    (println "Success:" output)))

(defn -main [& args]
  (if-let [input-file (first args)]
    (try
      (let [file (io/file input-file)]
        (if (.exists file)
          (smd->html file input-file)
          (println "Error: File not found.")))
      (catch Exception e (println "Error:" (.getMessage e)) (.printStackTrace e)))
    (println "Usage: ./slide-markdown.clj <input.smd>")))

(when (= *file* (System/getProperty "babashka.file")) (apply -main *command-line-args*))
