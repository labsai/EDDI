import { describe, it, expect } from "vitest";
import {
  buildDictionaryUri,
  dictionaryIdFromUri,
  parseDictionaryUri,
} from "@/lib/dictionary-uri";

describe("dictionary-uri", () => {
  it("builds the URI shape the parser config stores", () => {
    expect(buildDictionaryUri("dict1", 3)).toBe(
      "eddi://ai.labs.dictionary/dictionarystore/dictionaries/dict1?version=3",
    );
  });

  it("round-trips what it builds", () => {
    expect(parseDictionaryUri(buildDictionaryUri("dict1", 3))).toEqual({ id: "dict1", version: 3 });
  });

  it("reads the relative Location header a POST returns", () => {
    expect(parseDictionaryUri("/dictionarystore/dictionaries/new-res?version=1")).toEqual({
      id: "new-res",
      version: 1,
    });
  });

  it("reports no version when the URI pins none", () => {
    expect(
      parseDictionaryUri("eddi://ai.labs.dictionary/dictionarystore/dictionaries/dict1"),
    ).toEqual({ id: "dict1", version: null });
  });

  // The manual URI field accepts anything, and `new URL` resolves anything.
  // Taking the last segment regardless would mint an id-shaped string out of
  // another store's URI or free text — and the callers turn an id into a
  // resource link, a descriptor request and a duplicate-detection key.
  it.each([
    ["another store", "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1"],
    ["a collection URI with no id", "eddi://ai.labs.dictionary/dictionarystore/dictionaries/"],
    ["a truncated URI", "eddi://ai.labs.dictionary/dictionarystore"],
    ["free text", "the second one"],
    ["nothing", ""],
  ])("finds no id in %s", (_label, uri) => {
    expect(parseDictionaryUri(uri)).toEqual({ id: "", version: null });
    expect(dictionaryIdFromUri(uri)).toBe("");
  });
});
