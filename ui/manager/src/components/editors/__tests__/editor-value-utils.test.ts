import { describe, it, expect } from "vitest";
import {
  formatJsonArgument,
  nextFreeKey,
  parseHttpCodeList,
  parseJsonArgument,
  renameKey,
  toolArgumentKind,
} from "@/components/editors/editor-value-utils";

describe("parseHttpCodeList", () => {
  it("parses a comma-separated list", () => {
    expect(parseHttpCodeList("200, 201,404")).toEqual([200, 201, 404]);
  });

  it("returns undefined, never [], for an empty field — an empty runOnHttpCode matches nothing", () => {
    // PrePostUtils substitutes the defaults only for a null list; [] is kept
    // and `[].contains(code)` is false for every response.
    expect(parseHttpCodeList("")).toBeUndefined();
    expect(parseHttpCodeList(" , ,abc")).toBeUndefined();
  });
});

describe("renameKey", () => {
  it("renames in place, keeping the order of the other keys", () => {
    const renamed = renameKey({ a: 1, b: 2, c: 3 }, "b", "temperature");
    expect(Object.keys(renamed)).toEqual(["a", "temperature", "c"]);
    expect(renamed.temperature).toBe(2);
  });
});

describe("nextFreeKey", () => {
  it("skips names that are already taken", () => {
    // Counting keys handed out "param1" while param1 existed and overwrote it.
    expect(nextFreeKey(["systemMessage", "param1"], "param")).toBe("param2");
    expect(nextFreeKey([], "arg")).toBe("arg1");
  });
});

describe("tool argument helpers", () => {
  it("edits strings as text and everything else as JSON", () => {
    expect(toolArgumentKind("{memory.current.input}")).toBe("text");
    expect(toolArgumentKind(5)).toBe("json");
    expect(toolArgumentKind({ limit: 10 })).toBe("json");
    expect(toolArgumentKind(null)).toBe("json");
  });

  it("round-trips non-string values without turning them into strings", () => {
    for (const value of [5, true, { limit: 10 }, [1, 2], null]) {
      const parsed = parseJsonArgument(formatJsonArgument(value));
      expect(parsed).toEqual({ ok: true, value });
    }
  });

  it("reports text that is not JSON instead of storing it", () => {
    expect(parseJsonArgument("{limit: 10")).toEqual({ ok: false });
  });
});
