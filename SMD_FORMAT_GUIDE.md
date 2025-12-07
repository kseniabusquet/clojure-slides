# Guia do Formato Slide Markdown (.smd)

> **English version available:** [SMD_FORMAT_GUIDE_en.md](SMD_FORMAT_GUIDE_en.md)

O arquivo `.smd` é um formato de texto híbrido projetado para separar **definição de layout** (EDN) da **criação de conteúdo** (Markdown). Consiste em duas seções distintas separadas pela palavra-chave `END`.

## 1. Estrutura do Arquivo

```text
{ ... Cabeçalho EDN (Configuração & Templates) ... }
END
-*-*- [template-id] Título do Slide
Conteúdo Markdown...
```

---

## 2. O Cabeçalho (EDN)

A seção superior é um mapa EDN do Clojure definindo metadados globais e layouts de slides reutilizáveis.

### Chaves de Nível Superior

| Chave | Tipo | Descrição |
| :--- | :--- | :--- |
| `:title` | String | O título da página HTML (aba do navegador). |
| `:templates` | Vector | Uma lista de mapas de template definindo layouts. |

### Estrutura do Template

Cada map dentro de `:templates` define um layout.

| Chave | Tipo | Descrição |
| :--- | :--- | :--- |
| `:id` | String | **Obrigatório.** Identificador único referenciado nos slides (ex: `"split-view"`). |
| `:style` | String | String CSS aplicada ao contêiner do slide. Usado principalmente para backgrounds. |
| `:elements` | Vector | Uma lista de slots de conteúdo (mapas) definindo onde o conteúdo aparece. |

### Estrutura do Elemento

Cada mapa dentro de `:elements` define um slot para conteúdo.

| Chave | Tipo | Descrição |
| :--- | :--- | :--- |
| `:type` | String | `"text"`, `"image"`, ou `"video"`. |
| `:style` | String | String CSS para posicionamento e estilização (ex: `left: 10%; top: 5%; color: #333;`). **Nota:** `position: absolute` é aplicado automaticamente pelo engine. |
| `:controls`| Boolean| *(Apenas vídeo)* Mostrar/Ocultar controles do player. Padrão: `true`. |
| `:autoplay`| Boolean| *(Apenas vídeo)* Autoplay silenciado. Padrão: `false`. |

#### Exemplo de Cabeçalho

```clojure
{:title "Minha Apresentação"
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

## 3. O Separador

O cabeçalho e o corpo **devem** ser separados pela palavra-chave `END` em sua própria linha.

```text
END
```

---

## 4. O Corpo (Slides)

O corpo consiste em slides separados pelo delimitador `-*-*-`.

### Sintaxe do Cabeçalho do Slide
Cada slide começa com a linha delimitadora:

```text
-*-*- [template-id] Título Opcional do Slide
```

* **`[template-id]`**: Corresponde a um `:id` definido no cabeçalho. Se omitido, o **primeiro** template definido no cabeçalho é usado.
* **`Título Opcional do Slide`**: Usado para o menu dropdown de navegação.

### Mapeamento de Conteúdo (Blocos)

O conteúdo é escrito em Markdown padrão (GitHub Flavoured Markdown). O parser divide seu conteúdo Markdown em **blocos** baseado em **linhas em branco**. Esses blocos são então mapeados para os `:elements` do template em ordem.

1.  **Primeiro Bloco** -> Mapeado para **Elemento 1**.
2.  **Segundo Bloco** -> Mapeado para **Elemento 2**.
3.  ...

#### A Regra do Último Elemento "Ganancioso"
Se você fornecer mais blocos Markdown do que há elementos no template, o **último elemento** se torna "ganancioso". Ele consome seu bloco atribuído **mais** todos os blocos restantes, juntando-os com quebras de linha.

### Exemplo de Criação de Slide

```markdown
-*-*- [split] Texto à Esquerda, Imagem à Direita
# Olá Mundo
Este texto vai para o primeiro elemento (esquerda).

<-- Linha em Branco Separa os Blocos -->

![Texto Alternativo](caminho/para/imagem.png)
Este bloco de imagem vai para o segundo elemento (direita).
```

### Conteúdo Suportado

* **Texto:** Cabeçalhos (`#`), Listas (`*`, `-`), Negrito/Itálico, Links.
* **Código:** Três crases (```` ```clojure ... ``` ````). O destaque de sintaxe do PrismJS é aplicado automaticamente.
* **Imagens:** Sintaxe padrão do Markdown `![alt](url)`.
* **Vídeos:** Caminho de arquivo bruto (ex: `assets/demo.mp4`) passado para um elemento `:type "video"`.
