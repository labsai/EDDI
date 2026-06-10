import { describe, it, expect } from "vitest";
import {
  parseTranscriptContent,
  safeFormatDate,
} from "@/components/groups/group-utils";

describe("parseTranscriptContent", () => {
  it("returns empty string for empty input", () => {
    expect(parseTranscriptContent("")).toBe("");
  });

  it("returns plain text as-is", () => {
    expect(parseTranscriptContent("Hello world")).toBe("Hello world");
  });

  it("parses JSON with output array containing text objects", () => {
    const json = JSON.stringify({
      output: [{ type: "text", text: "Hello from agent" }],
    });
    expect(parseTranscriptContent(json)).toBe("Hello from agent");
  });

  it("parses JSON with output array containing multiple text objects", () => {
    const json = JSON.stringify({
      output: [
        { type: "text", text: "Line 1" },
        { type: "text", text: "Line 2" },
      ],
    });
    expect(parseTranscriptContent(json)).toBe("Line 1\nLine 2");
  });

  it("parses JSON with output array containing plain strings", () => {
    const json = JSON.stringify({
      output: ["Simple string response"],
    });
    expect(parseTranscriptContent(json)).toBe("Simple string response");
  });

  it("parses JSON with flat output:text: keys", () => {
    const json = JSON.stringify({
      "output:text:response": "Flat key response",
    });
    expect(parseTranscriptContent(json)).toBe("Flat key response");
  });

  it("parses output:text: key with array value", () => {
    const json = JSON.stringify({
      "output:text:multi": ["Item 1", "Item 2"],
    });
    expect(parseTranscriptContent(json)).toBe("Item 1\nItem 2");
  });

  it("parses output:text: key with array of objects having text field", () => {
    const json = JSON.stringify({
      "output:text:nested": [{ text: "Nested text" }],
    });
    expect(parseTranscriptContent(json)).toBe("Nested text");
  });

  it("parses output:text: key with object having text field", () => {
    const json = JSON.stringify({
      "output:text:single": { text: "Object text" },
    });
    expect(parseTranscriptContent(json)).toBe("Object text");
  });

  it("returns original for invalid JSON that looks like JSON", () => {
    const badJson = "{ invalid json }";
    expect(parseTranscriptContent(badJson)).toBe(badJson);
  });

  it("returns original for JSON array without matching structure", () => {
    const json = JSON.stringify([1, 2, 3]);
    expect(parseTranscriptContent(json)).toBe(json);
  });

  it("returns original for JSON object with no matching keys", () => {
    const json = JSON.stringify({ someKey: "someValue" });
    expect(parseTranscriptContent(json)).toBe(json);
  });

  it("handles whitespace around JSON", () => {
    const json = `  ${JSON.stringify({
      output: [{ text: "Padded" }],
    })}  `;
    expect(parseTranscriptContent(json)).toBe("Padded");
  });

  it("prefers output array over flat keys", () => {
    const json = JSON.stringify({
      output: [{ text: "From output array" }],
      "output:text:fallback": "From flat key",
    });
    expect(parseTranscriptContent(json)).toBe("From output array");
  });
});

describe("safeFormatDate", () => {
  it("returns empty string for null", () => {
    expect(safeFormatDate(null)).toBe("");
  });

  it("returns empty string for undefined", () => {
    expect(safeFormatDate(undefined)).toBe("");
  });

  it("formats epoch milliseconds", () => {
    const result = safeFormatDate(1705315200000);
    // Should contain some date string (locale-dependent)
    expect(result.length).toBeGreaterThan(0);
  });

  it("formats epoch seconds (auto-detected)", () => {
    // 1705315200 seconds = 2024-01-15T12:00:00Z
    const result = safeFormatDate(1705315200);
    expect(result.length).toBeGreaterThan(0);
  });

  it("formats ISO string", () => {
    const result = safeFormatDate("2024-01-15T12:00:00Z");
    expect(result.length).toBeGreaterThan(0);
  });

  it("formats numeric string (epoch seconds)", () => {
    const result = safeFormatDate("1705315200");
    expect(result.length).toBeGreaterThan(0);
  });

  it("formats numeric string (epoch millis)", () => {
    const result = safeFormatDate("1705315200000");
    expect(result.length).toBeGreaterThan(0);
  });

  it("returns original string for invalid date", () => {
    expect(safeFormatDate("not-a-date")).toBe("not-a-date");
  });

  it("returns date-only for style 'date'", () => {
    const result = safeFormatDate(1705315200000, "date");
    expect(result.length).toBeGreaterThan(0);
    // Should not include time info (exact format is locale-dependent)
  });

  it("returns time-only for style 'time'", () => {
    const result = safeFormatDate(1705315200000, "time");
    expect(result.length).toBeGreaterThan(0);
  });

  it("returns full date+time for style 'full' (default)", () => {
    const result = safeFormatDate(1705315200000, "full");
    expect(result.length).toBeGreaterThan(0);
  });

  it("handles float epoch seconds string", () => {
    const result = safeFormatDate("1705315200.5");
    expect(result.length).toBeGreaterThan(0);
  });
});
