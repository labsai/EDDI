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

/**
 * The masking stands placeholder characters (Unicode Private Use Area) in for
 * protected regions and math spans. Agent output can contain any such
 * character, so the placeholders are chosen per input from those it does NOT
 * contain: a literal one is ordinary text, never read as ours and never
 * removed. A display repair must not delete content.
 */
describe("formatMarkdownText with literal placeholder characters in the input", () => {
  const STASH_MARK = String.fromCharCode(0xe000);
  const MATH_MARK = String.fromCharCode(0xe001);

  it("keeps a literal math mark and the real math span, and repairs the bold (the review's trigger)", () => {
    // The literal mark is plain text here; the line's `**` pair is repaired as
    // on any other line, and `$x$` is masked by a mark the input does not hold.
    expect(formatMarkdownText(`a${MATH_MARK} and $x$ **b **(y`)).toBe(`a${MATH_MARK} and $x$ **b** (y`);
  });

  it("keeps a literal math mark when there is no math span at all", () => {
    expect(formatMarkdownText(`a${MATH_MARK} **b **(y`)).toBe(`a${MATH_MARK} **b** (y`);
  });

  it("keeps literal math marks around several math spans, every span in its place", () => {
    expect(formatMarkdownText(`${MATH_MARK}$p$ ${MATH_MARK} **q **(r $s$${MATH_MARK}`)).toBe(
      `${MATH_MARK}$p$ ${MATH_MARK} **q** (r $s$${MATH_MARK}`,
    );
  });

  it("keeps a forged stash placeholder as text and the real code span once", () => {
    expect(formatMarkdownText(`${STASH_MARK}0${STASH_MARK} and \`code\` **b **(y`)).toBe(
      `${STASH_MARK}0${STASH_MARK} and \`code\` **b** (y`,
    );
  });

  it("keeps a forged out-of-range stash placeholder in full", () => {
    expect(formatMarkdownText(`keep ${STASH_MARK}7${STASH_MARK} **b **(y`)).toBe(
      `keep ${STASH_MARK}7${STASH_MARK} **b** (y`,
    );
  });

  it("returns a fenced block holding the placeholder characters byte for byte", () => {
    const pua = [0xe000, 0xe001, 0xe002, 0xe003].map((c) => String.fromCharCode(c)).join("");
    const input = `Example:\n\n\`\`\`json\n{"mark": "${pua}", "bold": "** x **"}\n\`\`\`\n\nThen **done **(ok`;
    expect(formatMarkdownText(input)).toBe(input.replace("**done **(ok", "**done** (ok"));
    const fenceOnly = `\`\`\`json\n{"mark": "${pua}", "n": "a ** b"}\n\`\`\``;
    expect(formatMarkdownText(fenceOnly)).toBe(fenceOnly);
  });

  it("returns text without bold delimiters as it is, placeholder characters included", () => {
    const input = `plain ${MATH_MARK}${STASH_MARK} text $x$ \`c\``;
    expect(formatMarkdownText(input)).toBe(input);
  });

  it("returns the input unrepaired when it holds every placeholder character there is", () => {
    let all = "";
    for (let c = 0xe000; c <= 0xf8ff; c++) all += String.fromCharCode(c);
    const input = `${all} **b **(y`;
    expect(formatMarkdownText(input)).toBe(input);
  });

  it("loses no character on generated inputs mixing marks, math, code, fences and bold", () => {
    // A small deterministic PRNG (mulberry32), so a failure reproduces.
    let seed = 0x5eed;
    const random = () => {
      seed = (seed + 0x6d2b79f5) | 0;
      let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
    const pieces = [
      STASH_MARK,
      MATH_MARK,
      `${STASH_MARK}0${STASH_MARK}`,
      `${STASH_MARK}3${STASH_MARK}`,
      "$x$",
      "$a **b ** c$",
      "`",
      "`code`",
      "```\n",
      "**",
      "** ",
      " **",
      "b",
      "word",
      " ",
      "\n",
      "7",
      "(",
      "|",
      "https://x.test/a",
    ];
    // Only whitespace next to a repaired delimiter may move or change.
    const nonSpace = (s: string) => s.replace(/\s/g, "");
    let repaired = 0;
    for (let n = 0; n < 400; n++) {
      let input = "";
      const length = 1 + Math.floor(random() * 24);
      for (let i = 0; i < length; i++) input += pieces[Math.floor(random() * pieces.length)];
      const out = formatMarkdownText(input);
      expect(nonSpace(out), JSON.stringify(input)).toBe(nonSpace(input));
      if (!input.includes("**")) expect(out, JSON.stringify(input)).toBe(input);
      if (out !== input) repaired++;
    }
    // The generator does reach the repair, not only the inputs it leaves alone.
    expect(repaired).toBeGreaterThan(0);
  });
});
