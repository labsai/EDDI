import { describe, it, expect } from "vitest";
import { parseJsonPatch, parseJsonPatchBody, JSON_PATCH_MEDIA_TYPE } from "../json-patch";

describe("parseJsonPatch", () => {
  it("reads add, replace, remove, test, move and copy, keeping value and from only when present", () => {
    const parsed = parseJsonPatch([
      { op: "add", path: "/a", value: { x: 1 } },
      { op: "remove", path: "/b" },
      { op: "move", from: "/c", path: "/d" },
      { op: "test", path: "/e", value: null },
    ]);
    expect(parsed).toEqual({
      ok: true,
      ops: [
        { op: "add", path: "/a", value: { x: 1 } },
        { op: "remove", path: "/b" },
        { op: "move", from: "/c", path: "/d" },
        // `null` is a value; it is not the same as no value.
        { op: "test", path: "/e", value: null },
      ],
    });
    expect("value" in (parsed.ok ? parsed.ops[1]! : {})).toBe(false);
  });

  it("reads an empty patch", () => {
    expect(parseJsonPatch([])).toEqual({ ok: true, ops: [] });
  });

  it.each([
    ["an object", { op: "add", path: "/a" }],
    ["a string", "add /a"],
    ["null", null],
    ["an entry that is not an object", ["add"]],
    ["an entry with no op", [{ path: "/a" }]],
    ["an entry with no path", [{ op: "add" }]],
    ["a non-string path", [{ op: "add", path: 1 }]],
    ["a non-string op", [{ op: 1, path: "/a" }]],
    ["a non-string from", [{ op: "move", from: 1, path: "/a" }]],
    ["a nested array entry", [[{ op: "add", path: "/a" }]]],
  ])("is not a patch: %s", (_label, value) => {
    expect(parseJsonPatch(value)).toEqual({ ok: false });
  });
});

describe("parseJsonPatchBody", () => {
  it("parses JSON text", () => {
    expect(parseJsonPatchBody('[{"op":"remove","path":"/a"}]')).toEqual({ ok: true, ops: [{ op: "remove", path: "/a" }] });
  });

  it("is not a patch for blank, absent or invalid text", () => {
    expect(parseJsonPatchBody(null)).toEqual({ ok: false });
    expect(parseJsonPatchBody(undefined)).toEqual({ ok: false });
    expect(parseJsonPatchBody("  ")).toEqual({ ok: false });
    expect(parseJsonPatchBody('[{"op":"remove"')).toEqual({ ok: false });
  });
});

it("names the media type EDDI's PATCH endpoints accept", () => {
  expect(JSON_PATCH_MEDIA_TYPE).toBe("application/json-patch+json");
});
