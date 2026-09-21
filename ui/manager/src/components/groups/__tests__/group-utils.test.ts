import { describe, it, expect } from "vitest";
import { parseTranscriptContent, parseEmojiVerification, parseVerdictJson, truncateContent, safeFormatDate, humanizeJsonKey } from "@/components/groups/group-utils";

describe("group-utils", () => {
  describe("parseTranscriptContent", () => {
    it("returns empty string for null/undefined/empty input", () => {
      expect(parseTranscriptContent("")).toBe("");
      expect(parseTranscriptContent(null as unknown as string)).toBe("");
      expect(parseTranscriptContent(undefined as unknown as string)).toBe("");
    });

    it("extracts text from JSON output array format", () => {
      const json = JSON.stringify({
        output: [{ type: "text", text: "hello" }],
      });
      expect(parseTranscriptContent(json)).toBe("hello");
    });

    it("returns empty string (NOT raw JSON) when JSON has empty output array", () => {
      const json = JSON.stringify({ output: [] });
      expect(parseTranscriptContent(json)).toBe("");
    });

    it("returns plain text unchanged", () => {
      const text = "Hello world, this is a plain text message.";
      expect(parseTranscriptContent(text)).toBe(text);
    });

    it("returns plain text if JSON parsing fails", () => {
      const invalidJson = "{ output: [ invalid json }";
      expect(parseTranscriptContent(invalidJson)).toBe(invalidJson);
    });
  });

  describe("parseEmojiVerification", () => {
    it("returns null for null/empty input", () => {
      expect(parseEmojiVerification("")).toBeNull();
      expect(parseEmojiVerification(null as unknown as string)).toBeNull();
    });

    it("returns null for content without ✅/❌ emoji", () => {
      expect(parseEmojiVerification("Task Verification Results\nAll tests passed")).toBeNull();
    });

    it("parses single ✅ item correctly", () => {
      const content = "✅ Task 1: Passed";
      const result = parseEmojiVerification(content);
      expect(result).toEqual([
        {
          subject: "Task 1",
          passed: true,
        },
      ]);
    });

    it("parses single ❌ item correctly", () => {
      const content = "❌ Task 2: Failed";
      const result = parseEmojiVerification(content);
      expect(result).toEqual([
        {
          subject: "Task 2",
          passed: false,
        },
      ]);
    });

    it("handles bold subject markers correctly", () => {
      const content = "✅ **Financial Analysis**: Passed";
      const result = parseEmojiVerification(content);
      expect(result).toEqual([
        {
          subject: "Financial Analysis",
          passed: true,
        },
      ]);
    });

    it("handles content with header lines before first emoji", () => {
      const content = "## Task Verification Results\n\n✅ **Financial Analysis**: Passed";
      const result = parseEmojiVerification(content);
      expect(result).toEqual([
        {
          subject: "Financial Analysis",
          passed: true,
        },
      ]);
    });

    it("returns items with feedback text from lines below the emoji line", () => {
      const content = "✅ **Financial Analysis**: Passed\nAll requirements met and verified.";
      const result = parseEmojiVerification(content);
      expect(result).toEqual([
        {
          subject: "Financial Analysis",
          passed: true,
          feedback: "All requirements met and verified.",
        },
      ]);
    });

    it("parses mixed ✅/❌ items with feedback text", () => {
      const content =
        "## Task Verification Results\n\n✅ **Financial Analysis**: Passed\nAll requirements met.\n\n❌ **Risk Assessment**: Failed\nNo risk data provided.";
      const result = parseEmojiVerification(content);
      expect(result).toEqual([
        {
          subject: "Financial Analysis",
          passed: true,
          feedback: "All requirements met.",
        },
        {
          subject: "Risk Assessment",
          passed: false,
          feedback: "No risk data provided.",
        },
      ]);
    });
  });

  describe("truncateContent", () => {
    it("returns original string if under max length", () => {
      const shortStr = "Short text";
      expect(truncateContent(shortStr)).toBe(shortStr);
    });

    it("truncates and appends label if over max length", () => {
      const longStr = "A".repeat(50005);
      const result = truncateContent(longStr);
      expect(result).toHaveLength(50000 + "\n\n[Content truncated]".length);
      expect(result.endsWith("\n\n[Content truncated]")).toBe(true);
    });

    it("uses custom label when provided", () => {
      const longStr = "A".repeat(50005);
      const result = truncateContent(longStr, "[Custom Cut]");
      expect(result.endsWith("\n\n[Custom Cut]")).toBe(true);
    });

    it("uses custom max length when provided", () => {
      const text = "1234567890";
      const result = truncateContent(text, "[Truncated]", 5);
      expect(result).toBe("12345\n\n[Truncated]");
    });
  });

  describe("parseTranscriptContent — flat key format", () => {
    it("extracts text from flat output:text:* keys", () => {
      const json = JSON.stringify({
        "output:text:agent-001": "Hello from flat key",
      });
      expect(parseTranscriptContent(json)).toBe("Hello from flat key");
    });

    it("extracts text from flat key with array value", () => {
      const json = JSON.stringify({
        "output:text:agent-001": [{ text: "First" }, { text: "Second" }],
      });
      expect(parseTranscriptContent(json)).toBe("First\n\nSecond");
    });

    it("extracts text from flat key with object value", () => {
      const json = JSON.stringify({
        "output:text:agent-001": { text: "From object" },
      });
      expect(parseTranscriptContent(json)).toBe("From object");
    });
  });

  describe("safeFormatDate", () => {
    it("formats ISO string dates", () => {
      const result = safeFormatDate("2024-06-01T10:30:00Z", "full");
      expect(result).toBeTruthy();
      expect(result).not.toBe("");
    });

    it("formats epoch seconds and epoch millis to the same date", () => {
      const fromSeconds = safeFormatDate(1717235400, "full");
      const fromMillis = safeFormatDate(1717235400000, "full");
      expect(fromSeconds).toBeTruthy();
      expect(fromSeconds).not.toBe("");
      // Both should resolve to the same underlying date (Jun 1, 2024)
      expect(fromSeconds).toBe(fromMillis);
    });

    it("formats epoch value with 'date' and 'time' styles", () => {
      const dateOnly = safeFormatDate(1717235400, "date");
      const timeOnly = safeFormatDate(1717235400, "time");
      // date style should not include time, time style should not include date
      expect(dateOnly).toBeTruthy();
      expect(timeOnly).toBeTruthy();
      expect(dateOnly).not.toBe(timeOnly);
    });

    it("returns empty string for null/undefined", () => {
      expect(safeFormatDate(null)).toBe("");
      expect(safeFormatDate(undefined)).toBe("");
    });

    it("returns raw value for invalid dates as fallback", () => {
      expect(safeFormatDate("not-a-date")).toBe("not-a-date");
    });
  });
});

/**
 * A DEBATE judge answers in JSON, so the SYNTHESIS transcript entry's body is a
 * ```json block. The engine parses the same object into the conversation's
 * `DecisionRecord` — which is what the verdict card renders — but the raw text
 * stays on the entry, and every surface that showed that entry printed the blob
 * verbatim. In a demo the conclusion of the whole discussion read as JSON.
 */
describe("parseVerdictJson", () => {
  const VERDICT = {
    winner: "TIE",
    scores: { PRO: 7, CON: 7 },
    reasoning: "Both sides argued substantively.",
  };

  it("reads a verdict out of a ```json fence", () => {
    const parsed = parseVerdictJson("```json\n" + JSON.stringify(VERDICT, null, 2) + "\n```");
    expect(parsed).toEqual(VERDICT);
  });

  it("reads a bare verdict, and one fenced without a language tag", () => {
    expect(parseVerdictJson(JSON.stringify(VERDICT))).toEqual(VERDICT);
    expect(parseVerdictJson("```\n" + JSON.stringify(VERDICT) + "\n```")).toEqual(VERDICT);
  });

  it("accepts a verdict with a winner but no tally, and vice versa", () => {
    expect(parseVerdictJson(JSON.stringify({ winner: "PRO", reasoning: "Clear." }))).toEqual({
      winner: "PRO",
      scores: null,
      reasoning: "Clear.",
    });
    expect(parseVerdictJson(JSON.stringify({ scores: { PRO: 9 }, reasoning: "Clear." }))).toEqual({
      winner: null,
      scores: { PRO: 9 },
      reasoning: "Clear.",
    });
  });

  /**
   * `reasoning` alone used to be the discriminator, which was wrong in both
   * directions: it swallowed every other structured answer carrying that field,
   * and it let a response envelope with a sibling `reasoning` outrank the answer
   * in its own `output` array.
   */
  it("does not claim any object that merely has a reasoning field", () => {
    expect(parseVerdictJson(JSON.stringify({ position: "PRO", reasoning: "because" }))).toBeNull();
    expect(parseVerdictJson(JSON.stringify({ reasoning: "a chain of thought" }))).toBeNull();
  });

  it("reads a tally-only verdict, with empty prose", () => {
    // Not null: the object IS a verdict, it just has nothing left to say once
    // the verdict card has rendered the outcome. Returning null here sent the
    // caller back to printing the raw JSON — the exact demo defect.
    expect(parseVerdictJson(JSON.stringify({ winner: "PRO", scores: { PRO: 9, CON: 3 } }))).toEqual({
      winner: "PRO",
      scores: { PRO: 9, CON: 3 },
      reasoning: "",
    });
  });

  it("drops non-numeric scores rather than rendering them", () => {
    const parsed = parseVerdictJson(
      JSON.stringify({ reasoning: "x", scores: { PRO: 7, CON: "seven" } }),
    );
    expect(parsed!.scores).toEqual({ PRO: 7 });
  });

  /**
   * `winner` alone is not proof. A structured answer that happens to name a
   * winner and says everything else in other fields is not a verdict, and
   * collapsing it to `reasoning` would have rendered it as nothing at all.
   */
  it("does not claim an object carrying anything beyond the verdict fields", () => {
    expect(
      parseVerdictJson(JSON.stringify({ winner: "player1", analysis: "long form" })),
    ).toBeNull();
    expect(
      parseVerdictJson(JSON.stringify({ winner: "PRO", scores: { PRO: 9 }, notes: "x" })),
    ).toBeNull();
  });

  it("is not a verdict without an outcome", () => {
    expect(parseVerdictJson(JSON.stringify({ scores: {} }))).toBeNull();
    expect(parseVerdictJson(JSON.stringify({ scores: { PRO: "nine" } }))).toBeNull();
    expect(parseVerdictJson(JSON.stringify({ winner: 3 }))).toBeNull();
  });

  it("leaves ordinary prose, arrays and malformed JSON alone", () => {
    expect(parseVerdictJson("The panel could not agree.")).toBeNull();
    expect(parseVerdictJson('[{"winner":"PRO"}]')).toBeNull();
    expect(parseVerdictJson("```json\n{ not json }\n```")).toBeNull();
    expect(parseVerdictJson("")).toBeNull();
    expect(parseVerdictJson(null)).toBeNull();
  });

  it("does not unwrap a body that is more than one fence", () => {
    // Prose around a snippet is prose, not a verdict — unwrapping it would
    // throw the surrounding words away.
    expect(parseVerdictJson('Here it is:\n```json\n{"winner":"PRO"}\n```')).toBeNull();
  });
});

describe("parseTranscriptContent — JSON that is somebody's answer", () => {
  it("renders a verdict as its reasoning", () => {
    const content =
      "```json\n" + JSON.stringify({ winner: "TIE", reasoning: "It was close." }) + "\n```";
    expect(parseTranscriptContent(content)).toBe("It was close.");
  });

  it("renders a tally-only verdict as nothing, not as the blob", () => {
    const content =
      "```json\n" + JSON.stringify({ winner: "PRO", scores: { PRO: 9, CON: 3 } }) + "\n```";
    expect(parseTranscriptContent(content)).toBe("");
  });

  /**
   * The envelope is resolved first. Reading the verdict first meant an envelope
   * carrying a sibling `reasoning` rendered the model's chain of thought and
   * silently discarded the answer inside `output`.
   */
  it("prefers the envelope's answer over a sibling reasoning key", () => {
    const content = JSON.stringify({
      output: [{ type: "text", text: "THE REAL ANSWER" }],
      reasoning: "an internal chain of thought",
    });
    expect(parseTranscriptContent(content)).toBe("THE REAL ANSWER");
  });

  it("renders a winner-plus-extras object in full rather than as nothing", () => {
    const out = parseTranscriptContent(JSON.stringify({ winner: "player1", analysis: "detail" }));
    // Keys become sentence-cased labels — the raw JSON name is not what a
    // reader needs, but every field still has to survive.
    expect(out).toContain("**Winner**: player1");
    expect(out).toContain("**Analysis**: detail");
  });

  it("keeps every field of a structured answer that is not a verdict", () => {
    const out = parseTranscriptContent(JSON.stringify({ position: "PRO", reasoning: "because" }));
    expect(out).toContain("**Position**: PRO");
    expect(out).toContain("**Reasoning**: because");
  });

  it("reads a single-string output envelope", () => {
    expect(parseTranscriptContent(JSON.stringify({ output: "hello" }))).toBe("hello");
  });

  it("renders a list of unrecognised objects rather than a blank card", () => {
    expect(parseTranscriptContent(JSON.stringify([{ foo: "bar" }]))).toContain("**Foo**: bar");
    // An empty list is still an empty answer.
    expect(parseTranscriptContent("[]")).toBe("");
  });

  it("still collapses an EMPTY response envelope to nothing", () => {
    // The narrowing must not cost the envelope behaviour: an empty answer is
    // empty, not a blob.
    expect(parseTranscriptContent(JSON.stringify({ output: [] }))).toBe("");
  });

  it("no longer swallows a non-envelope object whole", () => {
    // This used to return "" for anything that was an object without `output`,
    // so an unrecognised answer rendered as a blank card.
    const out = parseTranscriptContent(JSON.stringify({ verdict: "PRO", margin: 2 }));
    expect(out).toContain("**Verdict**: PRO");
    expect(out).toContain("**Margin**: 2");
  });

  /**
   * The regression these guard: EDDI hands a member a JSON contract for four
   * phase types and stores the reply verbatim, so a BID turn's body really is
   * `{"bids": [{…}]}`. Rendering nested values with `JSON.stringify` put that
   * array on screen as a raw blob in the middle of a discussion transcript.
   */
  describe("nested JSON never renders as a blob", () => {
    it("expands an array of objects into a sub-list", () => {
      const out = parseTranscriptContent(
        JSON.stringify({ bids: [{ subject: "Draft spec", confidence: 0.9 }] }),
      );
      expect(out).not.toContain('{"');
      expect(out).not.toContain("[{");
      expect(out).toContain("- **Bids**:");
      expect(out).toContain("**Subject**: Draft spec");
      expect(out).toContain("**Confidence**: 0.9");
    });

    it("expands a nested object into a sub-list", () => {
      const out = parseTranscriptContent(JSON.stringify({ meta: { round: 2, judge: "m1" } }));
      expect(out).not.toContain("{");
      expect(out).toContain("**Round**: 2");
      expect(out).toContain("**Judge**: m1");
    });

    it("joins an array of plain values into a sentence rather than a list", () => {
      expect(parseTranscriptContent(JSON.stringify({ tags: ["a", "b"] }))).toContain(
        "**Tags**: a, b",
      );
    });

    it("drops a null field rather than printing the word null", () => {
      const out = parseTranscriptContent(JSON.stringify({ kept: "yes", dropped: null }));
      expect(out).toContain("**Kept**: yes");
      expect(out).not.toContain("Dropped");
    });

    it("keeps each item of an array visually separate", () => {
      const out = parseTranscriptContent(
        JSON.stringify({ bids: [{ subject: "A", note: "x" }, { subject: "B", note: "y" }] }),
      );
      // The first field of each item rides on its own bullet; a flat run of
      // sibling bullets would merge the two bids into one undifferentiated list.
      expect(out).toContain("  - **Subject**: A");
      expect(out).toContain("  - **Subject**: B");
    });

    it("stops recursing on a pathologically deep object instead of hanging", () => {
      let deep: Record<string, unknown> = { end: "bottom" };
      for (let i = 0; i < 12; i++) deep = { level: deep };
      const out = parseTranscriptContent(JSON.stringify(deep));
      expect(out).toContain("**Level**");
      // The escape hatch is bounded stringification, not an unbounded descent.
      expect(out.length).toBeLessThan(2000);
    });
  });

  describe("humanizeJsonKey", () => {
    it("sentence-cases a camelCase key", () => {
      expect(humanizeJsonKey("estimatedComplexity")).toBe("Estimated complexity");
    });

    it("treats underscores and hyphens as word breaks", () => {
      expect(humanizeJsonKey("task_id")).toBe("Task id");
      expect(humanizeJsonKey("in-return-for")).toBe("In return for");
    });

    it("leaves an acronym in caps, where lowercasing would read as a typo", () => {
      expect(humanizeJsonKey("URL")).toBe("URL");
      expect(humanizeJsonKey("requestURL")).toBe("Request URL");
    });

    it("returns an unusable key unchanged rather than an empty label", () => {
      expect(humanizeJsonKey("")).toBe("");
    });
  });
});
