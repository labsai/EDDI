import { describe, it, expect } from "vitest";
import {
  check,
  collectUsedKeys,
  collectDefaults,
  isSatisfied,
  isLegitimatePluralVariant,
  KNOWN_COLLISIONS,
} from "../../.github/scripts/check-i18n.mjs";

/**
 * Runs the CI i18n gate as a unit test.
 *
 * The script is the authority (CI calls it directly via `npm run i18n:check`),
 * but running it here too means a developer sees the drift on `npm test` rather
 * than after pushing — and it keeps the script's own helpers honest, since a
 * checker with a bug in `isSatisfied` would quietly pass everything.
 */
describe("i18n drift gate", () => {
  const result = check();

  it("every key passed to t() exists in en.json", () => {
    // A missing key is not a crash — it renders the inline English fallback, in
    // every language. That is why this has to be checked rather than noticed.
    expect(result.missing.map((m) => m.key)).toEqual([]);
  });

  it("every en.json key exists in all 10 other locales", () => {
    expect(result.parity.map((p) => `${p.code}:${p.key}`)).toEqual([]);
  });

  it("no locale carries a key en.json has dropped", () => {
    expect(result.orphans.map((o) => `${o.code}:${o.key}`)).toEqual([]);
  });

  it("no NEW key is used with two different English defaults", () => {
    // Only one default can reach en.json, so the other call site renders text
    // meant for somewhere else. `Workforce.thread.placeholder` did exactly that:
    // one site wanted "Type a message..." and another "Message {{name}}...".
    expect(result.collisions.map((c) => c.key)).toEqual([]);
  });

  it("covers all 11 shipped locales", () => {
    expect(result.localeCount).toBe(11);
  });
});

describe("collision detection", () => {
  it("flags a key called with two different defaults", () => {
    const found = collectDefaults(["a.tsx"], () =>
      ['t("x.y", "First")', 't("x.y", "Second")'].join("\n"),
    );
    expect([...found.get("x.y")!].sort()).toEqual(["First", "Second"]);
  });

  it("does not flag the same default repeated", () => {
    const found = collectDefaults(["a.tsx"], () =>
      ['t("x.y", "Same")', 't("x.y", "Same")'].join("\n"),
    );
    expect(found.get("x.y")!.size).toBe(1);
  });

  it("ignores an options object, which is not a default", () => {
    const found = collectDefaults(["a.tsx"], () => 't("x.y", { count: 3 })');
    expect(found.has("x.y")).toBe(false);
  });

  it("keeps the known-collision list from silently growing", () => {
    // A ratchet: entries may be removed as they are fixed, never added. If this
    // number rises, a real collision was waved through instead of split.
    expect(KNOWN_COLLISIONS.size).toBeLessThanOrEqual(24);
  });
});

describe("checker internals", () => {
  it("treats a plural variant as satisfying the base key", () => {
    const defined = new Set(["thing_one", "thing_other"]);
    expect(isSatisfied("thing", defined)).toBe(true);
    expect(isSatisfied("other", defined)).toBe(false);
  });

  it("accepts a plural form English does not have, if the base exists", () => {
    // Arabic has six plural categories and es/fr/pt have `many`; those extra
    // keys are correct CLDR behaviour, not orphans.
    const enKeys = new Set(["count_one", "count_other"]);
    expect(isLegitimatePluralVariant("count_many", enKeys)).toBe(true);
    expect(isLegitimatePluralVariant("count_zero", enKeys)).toBe(true);
  });

  it("does NOT excuse an unrelated key that merely ends in a plural word", () => {
    const enKeys = new Set(["count_one"]);
    expect(isLegitimatePluralVariant("somethingElse_many", enKeys)).toBe(false);
  });

  it("collects keys from t() and i18nKey, ignoring non-namespaced ones", () => {
    const used = collectUsedKeys(["fake.tsx"], () =>
      [
        't("a.b", "A")',
        "t('c.d')",
        '<Trans i18nKey="e.f" />',
        't("noDot")',
      ].join("\n"),
    );
    expect([...used.keys()].sort()).toEqual(["a.b", "c.d", "e.f"]);
  });

  it("records where a key was first seen, for an actionable failure message", () => {
    const used = collectUsedKeys(["src/page.tsx"], () => 'x\nt("a.b", "A")');
    expect(used.get("a.b")).toBe("src/page.tsx:2");
  });
});
