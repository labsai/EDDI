import { describe, it, expect } from "vitest";
import { jsonKeyOrder, reindentJsonText } from "@/lib/reindent-json";

describe("reindentJsonText", () => {
  it("prints valid JSON exactly as JSON.stringify does", () => {
    const value = {
      a: 1,
      b: [true, null, { c: "text" }],
      empty: {},
      none: [],
      nested: { deeper: { deepest: -1.5 } },
    };
    expect(reindentJsonText(JSON.stringify(value))).toBe(JSON.stringify(value, null, 2));
    // Already pretty-printed input comes out the same, too.
    expect(reindentJsonText(JSON.stringify(value, null, 4))).toBe(JSON.stringify(value, null, 2));
  });

  it("leaves string contents alone — structural characters, whitespace and escapes included", () => {
    const value = { note: 'a, {b}: [c]  "quoted" \\ back', spaced: "  two  spaces  " };
    expect(reindentJsonText(JSON.stringify(value))).toBe(JSON.stringify(value, null, 2));
  });

  it("lays out a body with a closing brace too many — the reported case — without repairing it", () => {
    // The operator's updateLlm body closed an object twice before the array.
    // The stray brace must stay visible as its own line, not be dropped.
    expect(reindentJsonText('{"tasks":[{"id":"a","n":1}}]}')).toBe(
      ['{', '  "tasks": [', "    {", '      "id": "a",', '      "n": 1', "    }", "  }", "]", "}"].join("\n"),
    );
  });

  it("keeps every non-whitespace character of a broken body, in order", () => {
    const broken = '{"a": 1,, "b": [2, 3}, "c": "unterminated';
    const strip = (text: string) => text.replace(/\s/g, "");
    expect(strip(reindentJsonText(broken))).toBe(strip(broken));
  });

  it("keeps two adjacent values apart instead of running them into one token", () => {
    expect(reindentJsonText('{"a": true  false, "b": "x"   "y"}')).toBe('{\n  "a": true false,\n  "b": "x" "y"\n}');
  });

  it("keeps text after a closed container apart from its bracket", () => {
    // Trailing text after a complete document is exactly what the approval
    // warns about — it must not be printed glued onto the closing brace.
    expect(reindentJsonText('{"a":1} true')).toBe('{\n  "a": 1\n} true');
    expect(reindentJsonText('[1]  "x"')).toBe('[\n  1\n] "x"');
    // No separator was written, so none is invented.
    expect(reindentJsonText('{"a":1}true')).toBe('{\n  "a": 1\n}true');
  });

  it("indents what follows a stray closer from the margin, not one level short of it", () => {
    expect(reindentJsonText('{"a":1}}{"b":2}')).toBe('{\n  "a": 1\n}\n}{\n  "b": 2\n}');
    expect(reindentJsonText("}")).toBe("}");
  });

  it("closes a trailing comma's line instead of leaving a blank row", () => {
    expect(reindentJsonText('{"a":1,}')).toBe('{\n  "a": 1,\n}');
    expect(reindentJsonText("[1,\n ]")).toBe("[\n  1,\n]");
  });

  it("carries the redaction filter's mangled field through as written", () => {
    expect(reindentJsonText('{"modelName":"x","apiKey=<REDACTED>"}')).toBe(
      '{\n  "modelName": "x",\n  "apiKey=<REDACTED>"\n}',
    );
  });
});

describe("jsonKeyOrder", () => {
  it("numbers keys in the order they are first written, nested ones included", () => {
    // `"x:y"` is a value, and `b` is only counted where it first appears.
    expect([...jsonKeyOrder('{"b":1,"a":{"c":"x:y","b":2}}').entries()]).toEqual([
      ["b", 0],
      ["a", 1],
      ["c", 2],
    ]);
  });

  it("reads a body that does not parse, the redaction filter's mangled field included", () => {
    expect([...jsonKeyOrder('{"modelName":"x","apiKey=<REDACTED>"}},"n" : 1').keys()]).toEqual([
      "modelName",
      "apiKey",
      "n",
    ]);
  });
});
