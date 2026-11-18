import { describe, expect, it } from "vitest";
import { formatMarkdownText } from "./group-utils";

/**
 * Correct Markdown and ordinary prose must come out of `formatMarkdownText`
 * byte for byte. The first block is the review's literal before -> after table
 * (each used to be rewritten), the rest are the constructs the repair must
 * never reach.
 */
describe("formatMarkdownText leaves correct text unchanged", () => {
  const unchanged: Array<[string, string]> = [
    ["C# and F#", "I like C# and F# a lot. See Issue#12"],
    ["issue number", "Issue#12"],
    ["URL fragment without scheme", "see www.x.com/p#sec"],
    ["line starting with #1", "#1 priority"],
    ["hex colour at line start", "#fff is white"],
    ["hashtag", "Loving the new release #eddi #ai"],
    ["colon then emoticon", "thanks:-)"],
    ["hyphen before bold", "pre-**trained**"],
    ["exponent notation", "x ** 2 and y ** 2"],
    ["bold glued to a word", "**bold**word"],
    ["word glued to bold", "word**bold**"],
    ["table row with C#", "| C# | F# |"],
    ["link text with #", "[C# guide](https://x)"],
    ["link with fragment", "[section](https://example.com/page#sec) and [local](#top)"],
    ["bare URL with fragment", "see https://example.com/a#b,C now"],
    ["comma before capital", "Hello,World and a,B"],
    ["bullet glued to bold", "-**not a list**"],
    ["heading without space is not ours to fix", "#Header"],
    ["glued heading", "Schluss## Titel"],
    ["inline code", "use `a ** b` and `#fff` and `C#`"],
    ["inline code with a spaced delimiter", "run `**x **y` now"],
    ["fenced block", "Text\n\n```js\nlet a = b ** 2; // C#\n#fff\n** x **\n```\n\nAfter"],
    ["tilde fence", "~~~\n** x ** #1\n~~~"],
    ["indented code", "Example:\n\n    ** x ** #1\n    a,B\n"],
    ["table with bold", "| a | b |\n|---|---|\n| **x** | **y** |"],
    ["nested bold and italic", "**bold *italic* more** and *it **bold** it* and ***both***"],
    ["bold with punctuation", "**Label**: text, **other**. (**x**)"],
    ["list of bold labels", "- **Agents**: list\n- **Groups**: show"],
    ["valid bold in a list", "1. **First** item\n2. **Second** item"],
    ["ordinary punctuation", "Retry after 5 minutes, then 10; why?Because."],
    ["heading", "# Title\n\nText\n## Sub\nmore"],
  ];
  it.each(unchanged)("%s", (_name, input) => {
    expect(formatMarkdownText(input)).toBe(input);
  });

  it("returns an empty string for empty input", () => {
    expect(formatMarkdownText("")).toBe("");
  });
});

describe("formatMarkdownText mid-stream", () => {
  const partial: Array<[string, string]> = [
    ["unclosed bold", "this is **bold and still"],
    ["unclosed bold after a closed one", "**a** and **b still"],
    ["unclosed code fence", "Here:\n```ts\nconst a = b ** 2; // #1\n** x **"],
    ["unclosed fence marker only", "Here:\n```"],
    ["unclosed inline code", "see `a ** b"],
    ["lone delimiter", "**"],
    ["trailing delimiter", "text **bold** more **"],
    ["half link", "see [docs](https://example.com/a#"],
  ];
  it.each(partial)("%s", (_name, input) => {
    expect(formatMarkdownText(input)).toBe(input);
  });

  it("never changes a prefix of correct text into something that is not a prefix", () => {
    const full = "Fix: **done** for C# and [x](https://e.com/a#b) with `code` ok.";
    for (let i = 1; i <= full.length; i++) {
      const prefix = full.slice(0, i);
      expect(formatMarkdownText(prefix)).toBe(prefix);
    }
  });
});

describe("formatMarkdownText the repairs that remain", () => {
  const repaired: Array<[string, string, string]> = [
    ["closing space", "real **business **(x", "real **business** (x"],
    ["closing space before punctuation", "a **b **, c", "a **b**, c"],
    ["opening space", "a ** b**, c", "a **b**, c"],
    ["both edges, opening the line", "** von der Strategie **", "**von der Strategie**"],
    ["both edges, opening a list item", "- ** Wichtig ** hier", "- **Wichtig** hier"],
    ["nested list item", "- A\n    - ** Wichtig ** hier", "- A\n    - **Wichtig** hier"],
    ["next to a valid span", "**first ** and **second** end", "**first** and **second** end"],
    ["next to an inline code span", "`x` and **bold **(y", "`x` and **bold** (y"],
  ];
  it.each(repaired)("%s", (_name, input, expected) => {
    expect(formatMarkdownText(input)).toBe(expected);
  });

  it("repairs only the line that needs it", () => {
    expect(formatMarkdownText("x ** 2 and y ** 2\n**bold **(z")).toBe("x ** 2 and y ** 2\n**bold** (z");
  });
});

describe("formatMarkdownText leaves flags and operators alone", () => {
  const unchanged: Array<[string, string]> = [
    ["python kwargs call", "f(**kwargs, **opts)"],
    ["python kwargs prose", "pass **kwargs and **opts"],
    ["kwargs in a sentence", "Use f(**a, **b) to forward both."],
    ["math exponent span", "the cost is $2 **n ** m$ here"],
    ["math exponent span after text", "**bold** and $a **b ** c$"],
    ["identifier before the delimiter", "x**2 and y**3"],
    ["table cell with exponent", "| x ** 2 and y ** 2 | ok |"],
  ];
  it.each(unchanged)("%s", (_name, input) => {
    expect(formatMarkdownText(input)).toBe(input);
  });
});

describe("formatMarkdownText repairs inside structure", () => {
  const repaired: Array<[string, string, string]> = [
    ["table cell, the Operator group overview", "| **SMC Recruitment Panel ** | PEER_REVIEW |", "| **SMC Recruitment Panel** | PEER_REVIEW |"],
    ["every cell of a row", "| **A ** | **B ** | plain |", "| **A** | **B** | plain |"],
    ["leading space in a cell", "| ** Name** | x |", "| **Name** | x |"],
    ["both edges, opening a cell", "| ** Name ** | x |", "| **Name** | x |"],
    ["heading marker before the span", "## ** Title **", "## **Title**"],
    ["heading with a one-sided span", "### **Title ** (draft)", "### **Title** (draft)"],
  ];
  it.each(repaired)("%s", (_name, input, expected) => {
    expect(formatMarkdownText(input)).toBe(expected);
  });

  it("never crosses a pipe: a lone delimiter in one cell does not pair with the next cell", () => {
    const row = "| a ** b | c ** d |";
    expect(formatMarkdownText(row)).toBe(row);
  });
});
