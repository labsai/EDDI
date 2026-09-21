import { describe, it, expect } from "vitest";
import { formatMarkdownText } from "../group-utils";

/**
 * `formatMarkdownText` runs on every agent message the app renders (1:1 chat,
 * group transcript, workforce board and thread). Historically it applied its
 * prose repairs to code and URLs too, which silently corrupted the product's
 * primary output. These tests pin the boundary.
 */
describe("formatMarkdownText", () => {
  describe("leaves code, URLs and identifiers alone", () => {
    const untouched = [
      "Set the apiKey and conversationId fields.",
      "Call getUserProfile() then setUserName().",
      "Use PostgreSQL or MongoDB with JavaScript.",
      "The model is gpt-4o from OpenAI.",
      "See https://docs.labs.ai/Release.Notes for details.",
      "`const userId = agentId;`",
      "[the API docs](https://example.com/api/GetStarted)",
      '{"conversationId":"abc","agentName":"Bot"}',
      "Deploy to `eddi://ai.labs.agent?id=abc`",
      "Query with https://api.test/v1?filter=a,Bar&sort=Name",
    ];
    for (const input of untouched) {
      it(`preserves ${JSON.stringify(input)}`, () => {
        expect(formatMarkdownText(input)).toBe(input);
      });
    }

    it("preserves a fenced code block verbatim", () => {
      const input = [
        "Here is the config:",
        "```json",
        '{ "apiKey": "x", "maxTokens": 10 }',
        "```",
      ].join("\n");
      expect(formatMarkdownText(input)).toContain('{ "apiKey": "x", "maxTokens": 10 }');
    });

    it("does not treat ordinary numbers as placeholders", () => {
      const input = "Retry after 5 minutes, then 10 minutes.";
      expect(formatMarkdownText(input)).toBe(input);
    });

    /**
     * Known, accepted boundary: masking keys off real constructs (URLs, code,
     * link destinations). A bare query fragment in prose is not one, so the
     * punctuation rule still applies to it. Pinned so a future change to the
     * masking patterns is a deliberate decision rather than a surprise.
     */
    it("does NOT protect a bare query fragment that is not part of a URL", () => {
      expect(formatMarkdownText("Query with ?filter=a,Bar")).toBe(
        "Query with ?filter=a, Bar"
      );
    });
  });

  describe("still repairs prose inside indented list content", () => {
    // The indented-code mask must not treat a nested bullet or a list
    // continuation paragraph as a code block. Verified against the repo's own
    // remark-parse: "- A\n    - ** X ** y" has NO strong node until repaired.
    it("repairs bold inside a 4-space nested bullet", () => {
      expect(formatMarkdownText("- Punkt eins\n    - ** Wichtig ** hier")).toBe(
        "- Punkt eins\n    - **Wichtig** hier",
      );
    });

    it("repairs bold inside a tab-indented nested bullet", () => {
      expect(formatMarkdownText("- A\n\t- ** Wichtig ** hier")).toBe(
        "- A\n\t- **Wichtig** hier",
      );
    });

    it("repairs a 4-space list continuation paragraph", () => {
      expect(
        formatMarkdownText("1. Erster\n\n    Fortsetzung mit ** Fett ** hier"),
      ).toBe("1. Erster\n\n    Fortsetzung mit **Fett** hier");
    });

    it("still protects a genuine indented code block", () => {
      const input = "Beispiel:\n\n    .btn { color:#fff }\n    foo(a,B);\n";
      expect(formatMarkdownText(input)).toBe(input);
    });

    it("protects an indented code block that contains a blank line", () => {
      // The run regex stops at a blank line, so such a block arrives as several
      // runs. For every run after the first the nearest preceding non-blank line
      // is the previous run's indented code, which was misread as list
      // continuation — and the punctuation rule then rewrote real code.
      const input = "Beispiel:\n\n    foo(a,B);\n\n    bar(c,D);\n";
      expect(formatMarkdownText(input)).toBe(input);
    });

    it("protects a tab-indented block across a blank line", () => {
      const input = "X:\n\n\ta(1,B);\n\n\tb(2,C);\n";
      expect(formatMarkdownText(input)).toBe(input);
    });

    it("protects every run of a three-part indented block", () => {
      const input = "X:\n\n    a(1,B);\n\n    b(2,C);\n\n    c(3,D);\n";
      expect(formatMarkdownText(input)).toBe(input);
    });
  });

  describe("a bare fence marker in prose does not disable the rest", () => {
    // Unanchored, /```[\s\S]*$/ matched an inline mention and suppressed every
    // rule for the remainder of the message.
    it("still splits a heading after an inline ``` mention", () => {
      expect(formatMarkdownText("Nutze ``` um Code.## Titel")).toBe(
        "Nutze ``` um Code.\n\n## Titel",
      );
    });

    it("still repairs punctuation after adjacent strikethroughs", () => {
      expect(formatMarkdownText("Alt ~~entfernt~~~~neu~~ und dann,Dann")).toBe(
        "Alt ~~entfernt~~~~neu~~ und dann, Dann",
      );
    });

    it("a stray backtick on an earlier line no longer suppresses that line", () => {
      // The unterminated-span guard is anchored to end of input, not end of each
      // line, so only the final line can be treated as a streaming frontier.
      expect(formatMarkdownText("Kosten 5` pro Stueck,Dann\nZweite Zeile.")).toBe(
        "Kosten 5` pro Stueck, Dann\nZweite Zeile.",
      );
    });

    it("but still protects an unpaired backtick at the streaming frontier", () => {
      const input = "Setze `color:#fff";
      expect(formatMarkdownText(input)).toBe(input);
    });

    it("still protects a real fence at the start of a line", () => {
      const input = "Hier:\n```json\n{ \"apiKey\": 1 }\n```";
      expect(formatMarkdownText(input)).toBe(input);
    });

    it("still protects an unterminated fence while streaming", () => {
      const input = "Hier:\n```json\n{ \"apiKey\": 1,";
      expect(formatMarkdownText(input)).toBe(input);
    });
  });

  describe("still repairs prose", () => {
    it("adds the missing space after a comma before a capital", () => {
      expect(formatMarkdownText("Klick auf Speichern,Dann weiter.")).toBe(
        "Klick auf Speichern, Dann weiter."
      );
    });

    it("adds the space after an ATX heading marker", () => {
      expect(formatMarkdownText("#Header")).toBe("# Header");
    });

    it("strips illegal whitespace inside bold delimiters", () => {
      expect(formatMarkdownText("** von der Strategie **")).toBe("**von der Strategie**");
    });

    // The operator's group-overview reply rendered literal asterisks all over
    // a table — every cell was `**Name **` (trailing space inside the closing
    // delimiter, which CommonMark refuses to parse as emphasis).
    it("repairs the trailing-space bold inside a table cell without eating the cell separator", () => {
      expect(formatMarkdownText("| **SMC Recruitment Panel ** | PEER_REVIEW |")).toBe(
        "| **SMC Recruitment Panel** | PEER_REVIEW |",
      );
    });

    it("re-inserts a separator when dropping the inner space would glue the bold to the next word", () => {
      expect(formatMarkdownText("two flavors: **real business use cases **(recruitment, grants)")).toBe(
        "two flavors: **real business use cases** (recruitment, grants)",
      );
    });

    // The repair itself was the vandal here: rule 6 (space before a glued
    // OPENING **) also fired on closing delimiters glued to punctuation,
    // turning every valid "**label**:" into the un-parseable "**label **:".
    // The operator's capability list rendered as literal asterisks with the
    // bold landing on the descriptions instead of the labels.
    it("leaves a valid bold label glued to a colon alone", () => {
      const line = "- **Agents & workflows**: list and inspect agent configs.";
      expect(formatMarkdownText(line)).toBe(line);
    });

    it("leaves a valid bold run glued to sentence punctuation alone", () => {
      expect(formatMarkdownText("**Disable schedules**.")).toBe("**Disable schedules**.");
      expect(formatMarkdownText("See **Documentation**: it lists the endpoints.")).toBe(
        "See **Documentation**: it lists the endpoints.",
      );
    });

    it("still spaces a truly glued opener before a word", () => {
      expect(formatMarkdownText("Das**Logo** ist neu")).toBe("Das **Logo** ist neu");
    });

    it("repairs a bullet marker glued to its bold opener", () => {
      expect(formatMarkdownText("-** Create a new agent, or a group.** - Edit an existing agent.")).toBe(
        "- **Create a new agent, or a group.** - Edit an existing agent.",
      );
    });

    it("separates a heading glued to preceding text", () => {
      expect(formatMarkdownText("Schluss## Titel")).toBe("Schluss\n\n## Titel");
    });
  });

  it("returns empty string for empty input", () => {
    expect(formatMarkdownText("")).toBe("");
  });

  describe("cannot be tricked by a forged placeholder", () => {
    const SENTINEL = String.fromCharCode(0xe000);

    it("does not substitute stashed content for a sentinel in the input", () => {
      // Agent output is attacker-influenceable via prompt injection. Without
      // stripping the sentinel first this returned "`code` and `code`" — the code
      // span duplicated into where the agent's own text had been. Only the
      // meaningless PUA character is dropped; the surrounding text survives.
      const out = formatMarkdownText("`code` and " + SENTINEL + "0" + SENTINEL);
      expect(out).toBe("`code` and 0");
      expect(out).not.toContain(SENTINEL);
    });

    it("does not let an out-of-range forged index delete surrounding text", () => {
      // Previously the forged placeholder resolved to `stash[99] ?? ""`, deleting
      // the run outright. The digits are ordinary content and must survive.
      const out = formatMarkdownText("before " + SENTINEL + "99" + SENTINEL + " after");
      expect(out).toBe("before 99 after");
      expect(out).not.toContain(SENTINEL);
    });

    it("keeps ordinary text containing digits intact", () => {
      const input = "Retry after 5 minutes, then 10.";
      expect(formatMarkdownText(input)).toBe(input);
    });
  });

  describe("partial output mid-stream", () => {
    // These render on every token while a reply streams in, so the closing fence
    // or backtick has not arrived yet. Before unterminated constructs were
    // masked, the prose rules rewrote the code the user was watching and it
    // visibly snapped back once the fence landed.
    const partials = [
      "Hier der Fix:\n```css\n.btn { color:#fff }\n", // heading rule split color:#fff
      "```ts\nconst x = a,B;\nfoo(y,Z);\n", // punctuation rule added spaces
      "```js\nlet a = b**2;\n", // bold rule inserted a space before **
      "Setze `color:#fff", // unterminated inline code
      'Here you go:\n```json\n{"apiKey":"x","maxTokens":1}',
      "Call getUserProfile(",
    ];
    for (const input of partials) {
      it(`leaves ${JSON.stringify(input.slice(0, 30))}… unchanged`, () => {
        expect(formatMarkdownText(input)).toBe(input);
      });
    }

    it("emits the same text once the fence closes as it did while open", () => {
      const open = "Hier der Fix:\n```css\n.btn { color:#fff }\n";
      const closed = open + "```";
      expect(formatMarkdownText(open)).toBe(open);
      expect(formatMarkdownText(closed)).toBe(closed);
    });

    it("never leaks a sentinel at any prefix length of a streaming reply", () => {
      const full = "Fix:\n```css\n.btn { color:#fff }\n```\nDone,Then go.";
      for (let i = 1; i <= full.length; i++) {
        expect(formatMarkdownText(full.slice(0, i))).not.toContain(
          String.fromCharCode(0xe000),
        );
      }
    });
  });

  describe("indented code blocks", () => {
    // A standard CommonMark form that reached the prose rules even when complete.
    it("leaves a 4-space indented block alone", () => {
      const input = "Beispiel:\n\n    .btn { color:#fff }\n    foo(a,B);\n";
      expect(formatMarkdownText(input)).toBe(input);
    });

    it("leaves a tab-indented block alone", () => {
      const input = "Beispiel:\n\n\tfoo(a,B);\n";
      expect(formatMarkdownText(input)).toBe(input);
    });
  });

  it("restores every masked region (no sentinel leaks into output)", () => {
    const input = "See `code` and https://x.test/A and [l](https://y.test/B) plus `more`.";
    const out = formatMarkdownText(input);
    expect(out).toBe(input);
    expect(out).not.toContain(String.fromCharCode(0xe000));
  });
});

/**
 * The repair must never corrupt emphasis that was already correct. The three
 * regex passes it replaced could not tell an opener from a closer, so on a line
 * with two bold spans they matched from the CLOSER of the first to the OPENER of
 * the second and "fixed" whitespace that was never inside emphasis.
 */
describe("pairing awareness", () => {
  it("leaves a second, already-correct bold span alone", () => {
    // Observed: "…dead-lettered. **Quotas**: quota is currently** disabled**…"
    const input = "0 dead-lettered.**Quotas**: quota is currently **disabled** for";
    expect(formatMarkdownText(input)).toBe(
      "0 dead-lettered. **Quotas**: quota is currently **disabled** for",
    );
  });

  it("does not treat a closer as an opener across two spans", () => {
    expect(formatMarkdownText("a.**B**: c **d** e")).toBe("a. **B**: c **d** e");
  });

  it("still repairs the span that genuinely needs it when another follows", () => {
    expect(formatMarkdownText("**first ** and **second** end")).toBe("**first** and **second** end");
  });

  it("leaves an unpaired trailing delimiter's text untouched", () => {
    expect(formatMarkdownText("**bold** then a stray ** and more text")).toBe(
      "**bold** then a stray ** and more text",
    );
  });

  it("ignores an empty span rather than inventing a pair around nothing", () => {
    expect(formatMarkdownText("a **** b")).toBe("a **** b");
  });
});
