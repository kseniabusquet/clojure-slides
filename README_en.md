# SlideMD (Slide Markdown)

> **Versão em Português disponível:** [README.md](README.md)

## About

**SlideMD** is a lightweight, command-line tool written in Clojure (running on Babashka) that converts a custom text format (`.smd`) into a **self-contained, single-file HTML presentation**.

It is designed for developers who want to write presentations in Markdown, define layouts using standard CSS within EDN data, and share a single HTML file that works offline without dependencies.

## Features

* **Self-Contained Output:** Images (Base64), CSS, and JavaScript are embedded directly into the HTML. No external folders required to present.
* **Markdown Support:** Full GFM (GitHub Flavored Markdown) support, including tables, lists, and images.
* **CSS-Based Templates:** Define slide layouts using standard CSS. No complex custom layout engines—just use styles you already know.
* **Syntax Highlighting:** Automatic syntax highlighting for code blocks using Prism.js (cached locally).
* **Responsive:** Slides scale automatically using viewport units (`vh`), looking good on any screen size.
* **Developer Friendly:** Written in Clojure, powered by Babashka.

## Prerequisites

You need **Babashka** installed to run this tool.

* [Install Babashka](https://github.com/babashka/babashka#installation)
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

## Usage

1.  Create your `.smd` file (e.g., `presentation.smd`).
2.  Run the script:

```bash
# If you set up a bb.edn (Recommended)
bb slide_markdown.clj presentation.smd

# Or running directly
./slide_markdown.clj presentation.smd
```

3.  Open the generated `presentation.html` in your browser.

**Note:** On the first run, the tool requires an internet connection to download the Prism.js themes and scripts to a local `.slide-cache` folder. Subsequent runs work offline.

## Live Development with Babashka

For a smoother development workflow, use the following Babashka commands:

### Available Commands

```bash
# Run tests
bb test

# Watch .smd file and auto-regenerate HTML
bb watch presentation.smd

# Start Clojure HTTP server with live reload
bb serve [port] [file.smd]  # default port: 8080

# Start REPL
bb --nrepl-server [port]
```

### Live Development Workflow

**Option 1: Server with Automatic Live Reload (Recommended)**
```bash
bb serve 8080 presentation.smd
```
- Starts native Clojure HTTP server
- Serves files at `http://localhost:8080`
- **Automatic live reload:** Browser automatically refreshes when:
  - The `.smd` file is modified (regenerates HTML and reloads)
  - HTML files are modified directly
- Request logging with detailed information

**Option 2: Server for HTML Only (without .smd)**
```bash
bb serve 8080
```
- Serves HTML files at `http://localhost:8080`
- Automatic live reload for HTML files

**Option 3: Two Terminals (Alternative Workflow)**

**Terminal 1** (File Watching):
```bash
bb watch presentation.smd
```
- Monitors changes to the `.smd` file
- Automatically regenerates HTML on save
- Shows success/error messages with colorful emojis

**Terminal 2** (HTTP Server):
```bash
bb serve 8080
```
- Starts native Clojure HTTP server
- Serves files at `http://localhost:8080`
- Automatic live reload for HTML files

**Browser:** Open `http://localhost:8080/presentation.html`

## The .smd File Format

> **📋 For detailed format information:** See the [SMD Format Guide](SMD_FORMAT_GUIDE_en.md)

The `.smd` file is a hybrid format split into two parts separated by the keyword `END`.

1.  **Header (EDN):** Configuration and Template definitions.
2.  **Body (Markdown):** The actual slide content.

### Structure Example

```clojure
{:title "My Awesome Presentation"
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
-*-*- [default] First Slide Title
# Hello World

This is the body content.
```

---

## Creating Templates (Header)

Templates are defined in the `:templates` vector in the header.

### Template Structure
Each map in `:templates` defines a layout:

* `:id`: **Required.** Unique identifier referenced in slides (e.g., `"split-view"`).
* `:style`: **Optional.** CSS string applied to the slide container. Primarily used for backgrounds (colors, gradients, or images).
* `:elements`: **Required.** A vector of content slots.

### Element Structure
Each map in `:elements` defines a slot where Markdown content will be injected:

* `:type`: `"text"`, `"image"`, or `"video"`.
* `:style`: CSS string for positioning and styling.
    * **Note:** The engine automatically applies `position: absolute`. You should define `top`, `left`, `width`, `color`, etc.
* `:controls`: *(Video only)* Boolean to show/hide player controls. Default: `true`.
* `:autoplay`: *(Video only)* Boolean to autoplay muted. Default: `false`.

---

## Writing Slides (Body)

Slides are separated by the marker `-*-*-`.

### Slide Header Syntax
Each slide starts with the delimiter line:

```text
-*-*- [template-id] Optional Slide Title
```

* `[template-id]`: Must match an `:id` defined in the header. If omitted, the **first** template defined in the header is used.
* `Optional Slide Title`: Appears in the browser navigation dropdown.

### Content Mapping (The Block System)
The parser splits your Markdown content into **blocks** based on **blank lines**. These blocks are then mapped to the template's `:elements` in sequential order.

1.  **First Block** -> Mapped to **Element 1**.
2.  **Second Block** -> Mapped to **Element 2**.
3.  And so on...

**Example:**

```markdown
-*-*- [split] Comparison Slide
# Left Column Content
This text goes to the first element defined in the 'split' template.

<-- Blank Line is CRITICAL to separate blocks -->

![Image](path/to/image.png)
This image block goes to the second element.
```

### The "Greedy" Last Element Rule
If you provide more Markdown blocks than there are elements in the template, the **last element** becomes "greedy". It consumes its assigned block **plus** all remaining blocks, joining them with line breaks.

### Supported Content
* **Text:** Headers, Lists, Bold, Italic, Blockquotes.
* **Code:** Triple backticks (```` ```clojure ... ``` ````). PrismJS syntax highlighting is applied automatically.
* **Images:** Standard Markdown syntax `![alt](path)`.
* **Videos:** Raw file path (e.g., `assets/demo.mp4`) passed to a `:type "video"` element.

---

## Keyboard Shortcuts

* `Arrow Right` / `Space`: Next Slide
* `Arrow Left`: Previous Slide
* `F` / Button: Toggle Fullscreen

## Troubleshooting

* **Colors/Theme not showing?**
    Try clearing the cache if you suspect a corrupted download: `rm -r .slide-cache`.
* **Code blocks merged with titles?**
    Ensure you have a **blank line** between your header text and your ` ``` ` code block. Without a blank line, the parser treats them as a single block.
* **Validation Errors?**
    The script validates that you provided enough content blocks for the chosen template. Ensure you use blank lines to separate your Markdown content correctly.
* **Live reload not working?**
    - Make sure the server is running with `bb serve [port] [file.smd]`
    - Check the browser console for live reload messages
    - Live reload works automatically for HTML files served by the server
    - If using a `.smd` file, pass it as the second argument: `bb serve 8080 presentation.smd`
* **Port already in use?**
    The server will show a clear error message. Choose a different port: `bb serve 3000 presentation.smd`
