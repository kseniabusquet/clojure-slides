# SlideMD (Slide Markdown)

> **English version available:** [README_en.md](README_en.md)

## Sobre

**SlideMD** é uma ferramenta leve de linha de comando escrita em Clojure (rodando no Babashka) que converte um formato de texto customizado (`.smd`) em uma **apresentação HTML autocontida em arquivo único**.

É projetada para desenvolvedores que querem escrever apresentações em Markdown, definir layouts usando CSS padrão dentro de dados EDN, e compartilhar um único arquivo HTML que funciona offline sem dependências.

## Funcionalidades

* **Saída Autocontida:** Imagens (Base64), CSS e JavaScript são incorporados diretamente no HTML. Não são necessárias pastas externas para apresentar.
* **Suporte ao Markdown:** Suporte completo ao GFM (GitHub Flavored Markdown), incluindo tabelas, listas e imagens.
* **Templates Baseados em CSS:** Defina layouts de slides usando CSS padrão. Sem engines complexos de layout customizado—apenas use estilos que você já conhece.
* **Destaque de Sintaxe:** Destaque automático de sintaxe para blocos de código usando Prism.js (armazenado localmente).
* **Responsivo:** Slides escalam automaticamente usando unidades de viewport (`vh`), ficando bem em qualquer tamanho de tela.
* **Amigável ao Desenvolvedor:** Escrito em Clojure, alimentado pelo Babashka.

## Pré-requisitos

Você precisa do **Babashka** instalado para executar esta ferramenta.

* [Instalar Babashka](https://github.com/babashka/babashka#installation)
    * macOS:
      ```bash
      brew install borkdude/brew/babashka
      ```
    * Linux:
      ```bash
      curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
      chmod +x install
      sudo ./install
      ```
    * Windows:
      ```bash
      scoop install babashka
      ```

## Como Usar

1.  Crie seu arquivo `.smd` (ex: `apresentacao.smd`).
2.  Execute o script:

```bash
# Se você configurou um bb.edn (Recomendado)
bb slide_markdown.clj apresentacao.smd

# Ou executando diretamente
./slide_markdown.clj apresentacao.smd
```

3.  Abra o `apresentacao.html` gerado no seu navegador.

**Nota:** Na primeira execução, a ferramenta requer uma conexão com a internet para baixar os temas e scripts do Prism.js para uma pasta local `.slide-cache`. Execuções subsequentes funcionam offline.

## Formato do Arquivo .smd

> **Para informações detalhadas sobre o formato:** Consulte o [Guia do Formato SMD](SMD_FORMAT_GUIDE.md)

O arquivo `.smd` é um formato híbrido dividido em duas partes separadas pela palavra-chave `END`.

1.  **Cabeçalho (EDN):** Configuração e definições de Template.
2.  **Corpo (Markdown):** O conteúdo real dos slides.

### Exemplo de Estrutura

```clojure
{:title "Minha Apresentação Incrível"
 :templates [
   {:id "default"
    :style "background: #333; color: white"
    :elements [{:type "text"
                :style "left: 5%; top: 10%; width: 90%"}]}

   {:id "split"
    :style "background: linear-gradient(to right, #222 50%, #eee 50%)"
    :elements [{:type "text" :style "left: 5%; top: 20%; width: 40%"}
               {:type "image" :style "left: 55%; top: 20%; width: 40%"}]}]}
END
-*-*- [default] Título do Primeiro Slide
# Olá Mundo

Este é o conteúdo do corpo.
```

---

## Criando Templates (Cabeçalho)

Templates são definidos no vetor `:templates` no cabeçalho.

### Estrutura do Template
Cada map em `:templates` define um layout:

* `:id`: **Obrigatório.** Identificador único referenciado nos slides (ex: `"split-view"`).
* `:style`: **Opcional.** String CSS aplicada ao contêiner do slide. Usado principalmente para backgrounds (cores, gradientes ou imagens).
* `:elements`: **Obrigatório.** Um vetor de slots de conteúdo.

### Estrutura do Elemento
Cada map em `:elements` define um slot onde o conteúdo Markdown será injetado:

* `:type`: `"text"`, `"image"`, ou `"video"`.
* `:style`: String CSS para posicionamento e estilização.
    * **Nota:** O engine aplica automaticamente `position: absolute`. Você deve definir `top`, `left`, `width`, `color`, etc.
* `:controls`: *(Apenas vídeo)* Boolean para mostrar/ocultar controles do player. Padrão: `true`.
* `:autoplay`: *(Apenas vídeo)* Boolean para autoplay silenciado. Padrão: `false`.

---

## Escrevendo Slides (Corpo)

Slides são separados pelo marcador `-*-*-`.

### Sintaxe do Cabeçalho do Slide
Cada slide começa com a linha delimitadora:

```text
-*-*- [template-id] Título Opcional do Slide
```

* `[template-id]`: Deve corresponder a um `:id` definido no cabeçalho. Se omitido, o **primeiro** template definido no cabeçalho é usado.
* `Título Opcional do Slide`: Aparece no dropdown de navegação do navegador.

### Mapeamento de Conteúdo (O Sistema de Blocos)
O parser divide seu conteúdo Markdown em **blocos** baseado em **linhas em branco**. Esses blocos são então mapeados para os `:elements` do template em ordem sequencial.

1.  **Primeiro Bloco** -> Mapeado para **Elemento 1**.
2.  **Segundo Bloco** -> Mapeado para **Elemento 2**.
3.  E assim por diante...

**Exemplo:**

```markdown
-*-*- [split] Slide de Comparação
# Conteúdo da Coluna Esquerda
Este texto vai para o primeiro elemento definido no template 'split'.

<-- Linha em Branco é CRÍTICA para separar blocos -->

![Imagem](caminho/para/imagem.png)
Este bloco de imagem vai para o segundo elemento.
```

### A Regra do Último Elemento "Ganancioso"
Se você fornecer mais blocos Markdown do que há elementos no template, o **último elemento** se torna "ganancioso". Ele consome seu bloco atribuído **mais** todos os blocos restantes, juntando-os com quebras de linha.

### Conteúdo Suportado
* **Texto:** Cabeçalhos, Listas, Negrito, Itálico, Citações.
* **Código:** Três crases (```` ```clojure ... ``` ````). O destaque de sintaxe do PrismJS é aplicado automaticamente.
* **Imagens:** Sintaxe padrão do Markdown `![alt](caminho)`.
* **Vídeos:** Caminho de arquivo bruto (ex: `assets/demo.mp4`) passado para um elemento `:type "video"`.

---

## Atalhos do Teclado

* `Seta Direita` / `Espaço`: Próximo Slide
* `Seta Esquerda`: Slide Anterior
* `F` / Botão: Alternar Tela Cheia

## Solução de Problemas

* **Cores/Tema não aparecendo?**
    Tente limpar o cache se suspeitar de um download corrompido: `rm -r .slide-cache`.
* **Blocos de código mesclados com títulos?**
    Certifique-se de ter uma **linha em branco** entre seu texto de cabeçalho e seu bloco de código ` ``` `. Sem uma linha em branco, o parser os trata como um único bloco.
* **Erros de Validação?**
    O script valida que você forneceu blocos de conteúdo suficientes para o template escolhido. Certifique-se de usar linhas em branco para separar seu conteúdo Markdown corretamente.
