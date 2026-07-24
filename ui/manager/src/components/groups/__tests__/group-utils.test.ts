import { describe, it, expect } from "vitest";
import { parseTranscriptContent, parseEmojiVerification, truncateContent, safeFormatDate } from "@/components/groups/group-utils";

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
      expect(parseTranscriptContent(json)).toBe("First\nSecond");
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

    it("formats epoch seconds", () => {
      const result = safeFormatDate(1717235400, "time");
      expect(result).toBeTruthy();
    });

    it("formats epoch milliseconds", () => {
      const result = safeFormatDate(1717235400000, "date");
      expect(result).toBeTruthy();
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
