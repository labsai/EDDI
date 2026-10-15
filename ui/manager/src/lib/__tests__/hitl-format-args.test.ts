import { describe, expect, it } from "vitest";
import { formatToolArguments, minifyJson } from "@/lib/hitl-format-args";

describe("formatToolArguments", () => {
  it("pretty-prints plain JSON with two-space indent", () => {
    expect(formatToolArguments('{"a":1,"b":[true,null]}')).toBe(
      '{\n  "a": 1,\n  "b": [\n    true,\n    null\n  ]\n}',
    );
  });

  it("unwraps JSON-in-a-JSON-string (the setupAgent requestBody shape)", () => {
    const inner = JSON.stringify({ agentName: "Support", systemPrompt: "Be kind.\nBe brief." });
    const out = formatToolArguments(JSON.stringify({ requestBody: inner }));
    expect(JSON.parse(out)).toEqual({
      requestBody: { agentName: "Support", systemPrompt: "Be kind.\nBe brief." },
    });
    expect(out).toContain('"agentName": "Support"');
    expect(out).not.toContain('\\"');
  });

  it("unwraps strings holding arrays, and nesting inside arrays", () => {
    const out = formatToolArguments(
      JSON.stringify({ list: [JSON.stringify([1, 2]), JSON.stringify({ deep: JSON.stringify({ x: 1 }) })] }),
    );
    expect(JSON.parse(out)).toEqual({ list: [[1, 2], { deep: { x: 1 } }] });
  });

  it("leaves strings that are not JSON objects or arrays alone", () => {
    const args = JSON.stringify({ a: "42", b: "true", c: '"quoted"', d: "{not json", e: "[1,", f: "hello" });
    expect(JSON.parse(formatToolArguments(args))).toEqual(JSON.parse(args));
  });

  it("keeps <REDACTED> markers exactly as they are", () => {
    const body = JSON.stringify({ apiKey: "<REDACTED>", nested: "{\"token\":\"<REDACTED>\"}" });
    const out = formatToolArguments(body);
    expect(JSON.parse(out)).toEqual({ apiKey: "<REDACTED>", nested: { token: "<REDACTED>" } });
    expect(out).toContain('"<REDACTED>"');
  });

  it("returns the original text when the arguments are truncated", () => {
    const args = '{"requestBody":"{\\"agentName\\":\\"Sup';
    expect(formatToolArguments(args, true)).toBe(args);
    const valid = '{"a":"{\\"b\\":1}"}';
    expect(formatToolArguments(valid, true)).toBe(valid);
  });

  it("returns the original text when the top level does not parse or is not an object", () => {
    for (const text of ["not json", "", '{"a":', "42", '"str"', "null", "true"]) {
      expect(formatToolArguments(text)).toBe(text);
    }
  });

  it("never throws, and bounds how deep it unwraps", () => {
    let nested: unknown = { leaf: 1 };
    for (let i = 0; i < 12; i++) nested = { w: JSON.stringify(nested) };
    let out = "";
    expect(() => {
      out = formatToolArguments(JSON.stringify(nested));
    }).not.toThrow();
    // Past the bound a string stays a string: some level of the output is still escaped JSON.
    expect(out).toContain('\\"');
  });

  describe("never misrepresents the call (lossless-only)", () => {
    const wrap = (inner: string) => JSON.stringify({ requestBody: inner });

    it.each([
      ["a big integer", '{"id":12345678901234567891}'],
      ["a trailing-zero decimal", '{"limit":1.50}'],
      ["exponent notation", '{"n":1e3}'],
      ["negative zero", '{"n":-0}'],
      ["a duplicate key", '{"role":"viewer","role":"admin"}'],
      ["a nested duplicate key", '{"a":{"role":"viewer","role":"admin"}}'],
      ["a unicode escape", '{"name":"caf\\u00e9"}'],
      ["an escaped slash", '{"url":"http:\\/\\/x"}'],
    ])("returns a top level with %s unchanged", (_label, text) => {
      expect(formatToolArguments(text)).toBe(text);
    });

    it("keeps a lossy NESTED string as the original string, not expanded", () => {
      for (const inner of [
        '{"id":12345678901234567891,"limit":1.50}',
        '{"role":"viewer","role":"admin"}',
        '{"n":1e3}',
        '{"name":"caf\\u00e9"}',
        '{"u":"a\\/b"}',
      ]) {
        const out = formatToolArguments(wrap(inner));
        // The top level is lossless so it is reformatted, but the inner string is intact.
        expect(JSON.parse(out).requestBody).toBe(inner);
      }
    });

    it("still expands a normal pretty-printed nested payload", () => {
      const inner = JSON.stringify({ agentName: "A", n: 2, list: [1, 2] }, null, 2);
      expect(JSON.parse(formatToolArguments(wrap(inner))).requestBody).toEqual({
        agentName: "A",
        n: 2,
        list: [1, 2],
      });
    });

    it("minifies without touching string contents", () => {
      expect(minifyJson('{ "a b" : "x \\" y ", "c":[ 1 , 2 ] }')).toBe('{"a b":"x \\" y ","c":[1,2]}');
    });
  });
});
