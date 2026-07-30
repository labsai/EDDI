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
