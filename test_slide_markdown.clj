(ns test-slide-markdown
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]))

(load-file "slide_markdown.clj")

;; Acessores para funções privadas
(def parse-slide-content #'slide-markdown/parse-slide-content)
(def pair-elements-with-blocks #'slide-markdown/pair-elements-with-blocks)
(def wrap-element #'slide-markdown/wrap-element)

(deftest test-wrapper-helper
  (testing "Wrap Element Structure"
    (let [html (wrap-element "my-class" "color: red;" "<p>Content</p>")]
      (is (str/includes? html "class=\"content-element my-class\""))
      (is (str/includes? html "style=\"position: absolute; color: red;\""))
      (is (str/includes? html "<p>Content</p>")))))

(deftest test-block-splitting
  (testing "Split content by blank lines"
    (let [raw "Slide Header\n\nBloco 1\n\nBloco 2"]
      (is (= 2 (count (:markdown-blocks (parse-slide-content raw))))
          "Deve separar em 2 blocos de conteúdo (além do header)"))
    (let [raw "Slide Header\nBloco1\nBloco2"]
      (is (= 1 (count (:markdown-blocks (parse-slide-content raw))))
          "Sem linha em branco dupla, o conteúdo vira um bloco único"))))

(deftest test-markdown-features
  (testing "Render List (<ul>)"
    (let [el {:type "text"}
          block "* Item 1\n* Item 2"
          html (slide-markdown/render-slide-element el block nil)]
      (is (str/includes? html "<ul>") "Deve gerar tag <ul>")
      (is (str/includes? html "<li>Item 1</li>") "Deve gerar itens de lista")))

  (testing "Render Bold/Italic"
    (let [el {:type "text"}
          block "Texto **negrito** e *italico*"
          html (slide-markdown/render-slide-element el block nil)]
      (is (str/includes? html "<strong>negrito</strong>") "Deve gerar tag <strong>")
      (is (str/includes? html "<em>italico</em>") "Deve gerar tag <em>")))

  (testing "Render Code Block (PrismJS Class)"
    (let [el {:type "text"}
          block "```clojure\n(def x 1)\n```"
          html (slide-markdown/render-slide-element el block nil)]
      (is (str/includes? html "<pre>") "Deve gerar tag <pre>")
      ;; Este é o teste crucial para o seu problema de cores
      (is (str/includes? html "class=\"language-clojure\"")
          "Deve injetar a classe 'language-clojure' para o Prism colorir"))))

(deftest test-greedy-pairing
  (testing "Pairing Logic"
    (let [elements [:title :code]
          blocks ["# O Título" "```\n(codigo)\n```"]
          pairs (pair-elements-with-blocks elements blocks)]
      (is (= [:title "# O Título"] (first pairs)))
      (is (= [:code "```\n(codigo)\n```"] (second pairs))
          "O segundo bloco deve ir para o segundo elemento"))))

;; --- EXECUÇÃO ---
(let [{:keys [fail error]} (run-tests 'test-slide-markdown)]
  (if (+ fail error) (System/exit 1) (System/exit 0)))