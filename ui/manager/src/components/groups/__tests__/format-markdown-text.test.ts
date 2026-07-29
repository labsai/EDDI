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

  it("restores every masked region (no sentinel leaks into output)", () => {
    const input = "See `code` and https://x.test/A and [l](https://y.test/B) plus `more`.";
    const out = formatMarkdownText(input);
    expect(out).toBe(input);
    expect(out).not.toContain(String.fromCharCode(0xe000));
  });
});
