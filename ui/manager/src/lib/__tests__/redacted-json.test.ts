import { describe, it, expect } from "vitest";
import { parseRedactedJson } from "@/lib/redacted-json";

/**
 * The inputs below are what EDDI's `SecretRedactionFilter` actually emits, not
 * a guess at it: its four rules were run over JSON bodies to produce them. The
 * first draft of this repair targeted `"apiKey":<REDACTED>"` — a shape the
 * filter never produces — so the shapes are pinned here rather than described.
 */
describe("parseRedactedJson", () => {
  describe("the shapes the filter produces", () => {
    it.each([
      ['{"modelName":"claude-sonnet-5","apiKey=<REDACTED>","threshold":9}', "a string value"],
      ['{"apiKey=<REDACTED>"}', "the only field"],
      ['{"token=<REDACTED>,"n":1}', "a numeric value, which loses its closing quote"],
      ['{"clientSecret=<REDACTED>","n":1}', "a key that merely ends in a filter name"],
      ['{"x-api-key=<REDACTED>","n":1}', "a hyphenated key"],
      ['{"api_key=<REDACTED>","n":1}', "an underscored key"],
      ['{\n  "apiKey=<REDACTED>",\n  "n": 1\n}', "a pretty-printed body"],
      ['{"password=<REDACTED> more","n":1}', "a secret with a space in it"],
      ['{"llm":{"apiKey=<REDACTED>"},"n":1}', "a nested object"],
      ['{"items":[{"token=<REDACTED>","n":1}]}', "a field inside an array element"],
      ['{"apiKey=<REDACTED>","password=<REDACTED>","n":1}', "two mangled fields"],
      ['{"AUTHORIZATION=<REDACTED>","n":1}', "an upper-case key"],
    ])("parses %#: %s", (body) => {
      const result = parseRedactedJson(body);
      expect(result.ok).toBe(true);
      // The repaired field is a plain marker string, so the rest of the
      // document is readable and the credential is still not shown.
      expect(JSON.stringify(result.ok && result.value)).toContain("<REDACTED>");
    });

    it("keeps the surrounding document intact, not just parseable", () => {
      const result = parseRedactedJson(
        '{"modelName":"claude-sonnet-5","apiKey=<REDACTED>","threshold":9}',
      );
      expect(result.ok && result.value).toEqual({
        modelName: "claude-sonnet-5",
        apiKey: "<REDACTED>",
        threshold: 9,
      });
    });
  });

  /**
   * The filter's value class excludes `,`, whitespace, `;`, `{`, `}` and `]`, so
   * a secret containing one of those is cut at it and the rest is left inside
   * the string. Each of these leaves a different amount of debris after the
   * marker, and the field's own closing quote is what ends it — not the first
   * delimiter, which is where an earlier version of this repair stopped.
   */
  describe("a secret the filter cut short", () => {
    it.each([
      ['{"password=<REDACTED>,rest","n":1}', "a comma"],
      ['{"password=<REDACTED>}rest","n":1}', "a closing brace"],
      ['{"apiKey=<REDACTED>]rest","n":1}', "a closing bracket"],
      ['{"password=<REDACTED> rest","n":1}', "a space"],
      ['{"password=<REDACTED>;rest","n":1}', "a semicolon"],
      ['{"password=<REDACTED>,a,b,c","n":1}', "several commas"],
      ['{"llm":{"apiKey=<REDACTED>,rest"},"n":1}', "a comma, nested"],
    ])("discards the debris after %#: %s", (body) => {
      const result = parseRedactedJson(body);
      expect(result.ok).toBe(true);
      // Everything after the marker is secret material: it must not survive
      // into the repaired value, and `n` must still be readable beside it.
      const rendered = JSON.stringify(result.ok ? result.value : null);
      expect(rendered).not.toMatch(/rest|a,b,c/);
      expect(rendered).toContain('"<REDACTED>"');
      expect(rendered).toContain('"n":1');
    });

    it("keeps the sibling fields — the reported case", () => {
      // Reported on the PR: this parsed as a failure, so `detectEscalationFlags`
      // returned only `inlineCredential` and the allowCreation grant beside it
      // went unwarned.
      const result = parseRedactedJson(
        '{"password=<REDACTED>,rest","dynamicAgents":{"enabled":true,"allowCreation":true}}',
      );
      expect(result.ok && result.value).toEqual({
        password: "<REDACTED>",
        dynamicAgents: { enabled: true, allowCreation: true },
      });
    });

    it("decides each field separately when a body mixes both shapes", () => {
      // `token` lost its closing quote (numeric value) while `password` kept one
      // (its secret held a comma). No single uniform reading repairs both.
      const result = parseRedactedJson(
        '{"token=<REDACTED>,"password=<REDACTED>,rest","apiKey=<REDACTED>","n":1}',
      );
      expect(result.ok && result.value).toEqual({
        token: "<REDACTED>",
        password: "<REDACTED>",
        apiKey: "<REDACTED>",
        n: 1,
      });
    });
  });

  describe("what it must not touch", () => {
    it("returns a body that already parses untouched", () => {
      // `"note":"apiKey=abcdefghij"` is what the filter correctly produces for a
      // credential embedded in a string — valid JSON, and not ours to rewrite.
      const body = '{"note":"apiKey=<REDACTED>","n":1}';
      const result = parseRedactedJson(body);
      expect(result.ok && result.value).toEqual({ note: "apiKey=<REDACTED>", n: 1 });
    });

    it("leaves an escaped JSON document nested in a string alone", () => {
      const body = JSON.stringify({ requestBody: '{"apiKey=<REDACTED>"}' });
      const result = parseRedactedJson(body);
      expect(result.ok && result.value).toEqual({ requestBody: '{"apiKey=<REDACTED>"}' });
    });

    it("does not rewrite a vault reference", () => {
      const body = '{"apiKey":"${vault:anthropic}"}';
      const result = parseRedactedJson(body);
      expect(result.ok && result.value).toEqual({ apiKey: "${vault:anthropic}" });
    });

    it("only rewrites in key position", () => {
      // The marker sits in a VALUE here; repairing it would corrupt the body.
      const body = '{"a":"x=<REDACTED>","apiKey=<REDACTED>"}';
      const result = parseRedactedJson(body);
      expect(result.ok && result.value).toEqual({ a: "x=<REDACTED>", apiKey: "<REDACTED>" });
    });

    it("stays failed for JSON broken by anything else", () => {
      // No pretending: a body this cannot explain is still a parse failure, so
      // the caller keeps whatever fallback it had.
      expect(parseRedactedJson("not { json at all").ok).toBe(false);
      expect(parseRedactedJson('{"a":1,,}').ok).toBe(false);
    });

    it("leaves a body with no marker at all alone", () => {
      const result = parseRedactedJson('{"a":1}');
      expect(result.ok && result.value).toEqual({ a: 1 });
    });
  });

  it("stays bounded on a body stuffed with unrepairable credential fields", () => {
    // The per-field search is 2^n parses, so it is capped. This body defeats
    // every candidate; the point is that it gives up quickly rather than
    // grinding through combinations of a document it cannot explain.
    const body = `{${Array.from({ length: 30 }, (_, i) => `"apiKey${i}=<REDACTED>,x`).join("")}}`;
    const started = performance.now();
    expect(parseRedactedJson(body).ok).toBe(false);
    expect(performance.now() - started).toBeLessThan(1_000);
  });

  it("runs in linear time on a large body — the key scan cannot cross a quote", () => {
    // The backend went possessive on its own quantifiers over ReDoS; this
    // regex is reachable from the same untrusted request bodies.
    const big = `{${Array.from({ length: 20_000 }, (_, i) => `"k${i}":"v${i}"`).join(",")},"apiKey=<REDACTED>"}`;
    const started = performance.now();
    expect(parseRedactedJson(big).ok).toBe(true);
    expect(performance.now() - started).toBeLessThan(2_000);
  });
});
