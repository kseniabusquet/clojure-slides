# Slide Markdown (.smd) Format Guide

The `.smd` file is a hybrid text format designed to separate **layout definition** (EDN) from **content authoring** (Markdown). It consists of two distinct sections separated by the keyword `END`.

## 1. File Structure

```text
{ ... EDN Header (Configuration & Templates) ... }
END
-*-*- [template-id] Slide Title
Markdown Content...
```

---

## 2. The Header (EDN)

The top section is a Clojure EDN map defining global metadata and reusable slide layouts.

### Top-Level Keys

| Key | Type | Description |
| :--- | :--- | :--- |
| `:title` | String | The HTML page title (browser tab). |
| `:templates` | Vector | A list of template maps defining layouts. |

### Template Structure

Each map inside `:templates` defines a layout.

| Key | Type | Description |
| :--- | :--- | :--- |
| `:id` | String | **Required.** Unique identifier referenced in slides (e.g., `"split-view"`). |
| `:style` | String | CSS string applied to the slide container. Primarily used for backgrounds. |
| `:elements` | Vector | A list of content slots (maps) defining where content appears. |

### Element Structure

Each map inside `:elements` defines a slot for content.

| Key | Type | Description |
| :--- | :--- | :--- |
| `:type` | String | `"text"`, `"image"`, or `"video"`. |
| `:style` | String | CSS string for positioning and styling (e.g., `left: 10%; top: 5%; color: #333;`). **Note:** `position: absolute` is applied automatically by the engine. |
| `:controls`| Boolean| *(Video only)* Show/Hide player controls. Default: `true`. |
| `:autoplay`| Boolean| *(Video only)* Autoplay muted. Default: `false`. |

#### Example Header

```clojure
{:title "My Presentation"
 :templates [
   {:id "default"
    :style "background: #222"
    :elements [{:type "text" 
                :style "left: 5%; top: 10%; color: white; width: 90%"}]}
                
   {:id "split"
    :style "background: linear-gradient(to right, #111 50%, #eee 50%)"
    :elements [{:type "text" 
                :style "left: 5%; top: 20%; width: 40%; color: white"}
               {:type "image" 
                :style "left: 55%; top: 20%; width: 40%"}]}]}
```

---

## 3. The Separator

The header and the body **must** be separated by the keyword `END` on its own line.

```text
END
```

---

## 4. The Body (Slides)

The body consists of slides separated by the delimiter `-*-*-`.

### Slide Header Syntax
Each slide begins with the delimiter line:

```text
-*-*- [template-id] Optional Slide Title
```

* **`[template-id]`**: Matches an `:id` defined in the header. If omitted, the **first** template defined in the header is used.
* **`Optional Slide Title`**: Used for the navigation dropdown menu.

### Content Mapping (Blocks)

Content is written in standard Markdown (GFM). The parser splits your Markdown content into **blocks** based on **blank lines**. These blocks are then mapped to the template's `:elements` in order.

1.  **First Block** -> Mapped to **Element 1**.
2.  **Second Block** -> Mapped to **Element 2**.
3.  ...

#### The "Greedy" Last Element Rule
If you provide more Markdown blocks than there are elements in the template, the **last element** becomes "greedy". It consumes its assigned block **plus** all remaining blocks, joining them with line breaks.

### Example Slide Authoring

```markdown
-*-*- [split] Text on Left, Image on Right
# Hello World
This text goes into the first element (left).

<-- Blank Line Splits the Blocks -->

![Alt Text](path/to/image.png)
This image block goes into the second element (right).
```

### Supported Content

* **Text:** Headers (`#`), Lists (`*`, `-`), Bold/Italic, Links.
* **Code:** Triple backticks (```` ```clojure ... ``` ````). PrismJS syntax highlighting is applied automatically.
* **Images:** Standard Markdown syntax `![alt](url)`.
* **Videos:** Raw file path (e.g., `assets/demo.mp4`) passed to a `:type "video"` element.