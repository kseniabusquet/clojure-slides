#!/usr/bin/env bb
(ns slide-markdown
  "Converts a .smd (Slide Markdown) file into a self-contained HTML presentation."
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
      (println (str "Creating cache dir: " cache-dir))
      (.mkdirs cache-dir))
    (if (.exists cache-file)
      (slurp cache-file)
      (do
        (println (str "Downloading " file-name " from CDN..."))
        (let [content (slurp url)]
          (spit cache-file content)
          content)))))

;; --- Core Parsing Logic ---

(defn parse-slide-header
  "Parses the slide header (e.g., '[template-id] Title')."
  [header-line]
  (if-let [[_ id title] (re-find #"^\[([\w\d-]+)\]\s*(.*)$" header-line)]
    {:template-id id
     :title (when-not (str/blank? title) title)}
    {:template-id nil
     :title (when-not (str/blank? header-line) header-line)}))

(defn- parse-slide-content
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
  (into {} (map (juxt :slide-template identity) templates)))

(defn validate-presentation-data
  [{:keys [meta slides] :as presentation-data}]
  (let [templates (:templates meta)]
    (when (empty? templates)
      (throw (ex-info "Validation Failed: No templates found." {})))

    (let [template-map (get-template-map templates)
          default-id (-> templates first :slide-template)]

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

;; --- Media & Style Generation ---

(def mime-types
  {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg"
   "gif" "image/gif" "svg" "image/svg+xml" "webp" "image/webp"
   "mp4" "video/mp4" "webm" "video/webm" "ogg" "video/ogg"})

(defn guess-mime-type [path]
  (let [ext (second (re-find #"\.([a-zA-Z0-9]+)$" path))]
    (get mime-types (str/lower-case (or ext "")) "application/octet-stream")))

(defn- encode-file-to-base64 [base-dir file-path]
  (let [file (io/file base-dir file-path)]
    (when-not (.exists file)
      (throw (ex-info (str "Media file not found: " file-path) {:path (str file)})))
    (with-open [in (FileInputStream. file) out (ByteArrayOutputStream.)]
      (io/copy in out)
      (.encodeToString (Base64/getEncoder) (.toByteArray out)))))

(defn extract-image-path [block]
  (or (second (re-find #"!\[.*\]\((.*?)\)" block)) block))

(defn- build-css-gradient [{:keys [orientation layers]}]
  (let [dir (if (= "vertical" orientation) "to right" "to bottom")
        [stops] (reduce (fn [[acc cur] {:keys [color proportion]}]
                          (let [p (Double/parseDouble (str/replace proportion "%" ""))
                                next (+ cur p)]
                            [(conj acc (str color " " cur "% " next "%")) next]))
                        [[] 0.0]
                        layers)]
    (str "linear-gradient(" dir ", " (str/join ", " stops) ")")))

(defn- build-position-style [{:keys [position style]}]
  (str "position: absolute; "
       (when (:x position) (str "left: " (:x position) "; "))
       (when (:y position) (str "top: " (:y position) "; "))
       (when (:color style) (str "color: " (:color style) "; "))
       (when (:alignment style) (str "text-align: " (:alignment style) "; "))))

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

(defmulti render-slide-element (fn [element _ _] (:type element)))

(defmethod render-slide-element "text" [element block _]
  (let [style (build-position-style element)
        html (md/md-to-html-string block :spec :gfm)]
    (str "<div class=\"content-element text-content\" style=\"" style "\">"
         (str/replace html #"<code class=\"(\w+)\">" "<code class=\"language-$1\">")
         "</div>")))

(defmethod render-slide-element "image" [element block base-dir]
  (let [style (build-position-style element)
        path (extract-image-path block)
        mime (guess-mime-type path)
        b64 (encode-file-to-base64 base-dir path)]
    (str "<div class=\"content-element image-content\" style=\"" style "\">"
         "<img src=\"data:" mime ";base64," b64 "\" alt=\"Embedded Image\"></div>")))

(defmethod render-slide-element "video" [element block base-dir]
  (let [style (build-position-style element)
        mime (guess-mime-type block)
        b64 (encode-file-to-base64 base-dir block)
        opts (str (when-not (false? (:controls element)) "controls ")
                  (when (:autoplay element) "autoplay muted"))]
    (str "<div class=\"content-element video-content\" style=\"" style "\">"
         "<video " opts " src=\"data:" mime ";base64," b64 "\"></video></div>")))

(defmethod render-slide-element :default [element _ _]
  (str "<div style=\"" (build-position-style element) "\">Unsupported: " (:type element) "</div>"))

(defn- generate-slide-content [template-elements markdown-blocks base-dir]
  (str/join "\n" (for [[element block] (pair-elements-with-blocks template-elements markdown-blocks)
                       :when block]
                   (render-slide-element element block base-dir))))

(defn- generate-slide-html [slide index template-map default-id base-dir]
  (let [template (get template-map (or (:template-id slide) default-id))
        bg (build-css-gradient (:background template))]
    (str "<div class=\"slide\" id=\"slide-" index "\" style=\"background: " bg ";\">"
         (generate-slide-content (:elements template) (:markdown-blocks slide) base-dir)
         "</div>")))

(defn- generate-slide-options [slides]
  (str/join "\n" (map-indexed (fn [i slide]
                                (let [label (if (:title slide)
                                              (str (inc i) " - " (:title slide))
                                              (str (inc i)))]
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
          function show(idx) {
            if (idx < 0 || idx >= total) return;
            slides[currentSlide].classList.remove('active');
            currentSlide = idx;
            slides[currentSlide].classList.add('active');
            if (select) select.value = currentSlide;
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
          show(0); menuVis(); Prism.highlightAll();
         </script>")))

(defn generate-html [{:keys [meta slides]} base-dir]
  (let [template-map (get-template-map (:templates meta))
        default-id (-> meta :templates first :slide-template)]
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

(defn load-smd [file input-file]
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
          (load-smd file input-file)
          (println "Error: File not found.")))
      (catch Exception e (println "Error:" (.getMessage e)) (.printStackTrace e)))
    (println "Usage: ./slide-markdown.clj <input.smd>")))

(when (= *file* (System/getProperty "babashka.file")) (apply -main *command-line-args*))