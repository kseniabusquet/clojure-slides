(ns test-slide-markdown
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]))

(load-file "slide_markdown.clj")


;; TODO, try to read real files

(deftest test-parsing-logic
  (testing "Parse Slide Header"
    (is (= {:template-id "my-tpl" :title "My Title"}
           (slide-markdown/parse-slide-header "[my-tpl] My Title")))
    (is (= {:template-id "default" :title "Just Title"}
           (slide-markdown/parse-slide-header "[default] Just Title")))
    (is (= {:template-id nil :title "No ID Here"}
           (slide-markdown/parse-slide-header "No ID Here")))
    (is (= {:template-id "only-id" :title nil}
           (slide-markdown/parse-slide-header "[only-id]")))))

(deftest test-css-generation
  (testing "Build Gradient CSS"
    (let [bg {:orientation "vertical"
              :layers [{:color "#000" :proportion "50%"}
                       {:color "#fff" :proportion "50%"}]}
          css (slide-markdown/build-css-gradient bg)]
      (is (str/includes? css "linear-gradient(to bottom"))
      (is (str/includes? css "#000 0.0% 50.0%"))
      (is (str/includes? css "#fff 50.0% 100.0%")))))

(deftest test-greedy-pairing
  (testing "Pairing: 1 to 1 (Exact match)"
    (let [elements [:e1 :e2]
          blocks   ["b1" "b2"]]
      (is (= [[:e1 "b1"] [:e2 "b2"]]
             (slide-markdown/pair-elements-with-blocks elements blocks)))))

  (testing "Pairing: Greedy (More blocks than elements)"
    (let [elements [:e1 :e2]
          blocks   ["b1" "b2" "b3" "b4"]
          result (slide-markdown/pair-elements-with-blocks elements blocks)]
      (is (= 2 (count result)))
      (is (= [:e1 "b1"] (first result)))
      (is (= [:e2 "b2\n\nb3\n\nb4"] (second result)))))

  (testing "Pairing: Single element greedy"
    (let [elements [:e1]
          blocks   ["b1" "b2"]]
      (is (= [[:e1 "b1\n\nb2"]]
             (slide-markdown/pair-elements-with-blocks elements blocks))))))

(deftest test-render-multimethod
  (testing "Render Text with Syntax Highlighting Fix"
    (let [el {:type "text"}
          block "```clojure\n(def x 1)\n```"
          html (slide-markdown/render-slide-element el block nil)]
      (is (str/includes? html "<pre>"))
      (is (str/includes? html "class=\"language-clojure\""))))

  (testing "Render Default/Unsupported"
    (let [element {:type "unknown-thing"}
          block "content"]
      (is (str/includes? (slide-markdown/render-slide-element element block nil)
                         "Unsupported: unknown-thing")))))

(deftest test-utils
  (testing "Mime Types"
    (is (= "image/png" (slide-markdown/guess-mime-type "file.png")))
    (is (= "image/jpeg" (slide-markdown/guess-mime-type "file.JPG")))
    (is (= "application/octet-stream" (slide-markdown/guess-mime-type "file.unknown"))))

  (testing "Extract Image Path"
    (is (= "img.png" (slide-markdown/extract-image-path "img.png")))
    (is (= "img.png" (slide-markdown/extract-image-path "![alt](img.png)")))))

;; --- RUN TESTS ---
(let [{:keys [fail error]} (run-tests 'test-slide-markdown)]
  (if (+ fail error)
    (System/exit 1)
    (System/exit 0)))