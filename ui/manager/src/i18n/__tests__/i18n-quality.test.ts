import { describe, it, expect } from "vitest";
import en from "@/i18n/locales/en.json";
import de from "@/i18n/locales/de.json";
import fr from "@/i18n/locales/fr.json";
import es from "@/i18n/locales/es.json";
import ar from "@/i18n/locales/ar.json";
import zh from "@/i18n/locales/zh.json";
import th from "@/i18n/locales/th.json";
import ja from "@/i18n/locales/ja.json";
import ko from "@/i18n/locales/ko.json";
import pt from "@/i18n/locales/pt.json";
import hi from "@/i18n/locales/hi.json";

/**
 * `config.test.ts` asserts that every locale has the same *keys* as English.
 * That is necessary but not sufficient, and two whole classes of breakage slid
 * underneath it while it stayed green:
 *
 *  1. A locale can define `key_one`/`key_other` and still render English,
 *     because its language needs plural categories those two do not cover.
 *     Arabic needs six; it shipped two, so counts of 0, 2, 3-10 and 11-99 —
 *     nearly every real count — fell back to English.
 *  2. A key can be present with the English string copied verbatim as its
 *     "translation". 56% of the Group Wizard was English in every locale.
 */

type Json = Record<string, unknown>;

const LOCALES: Record<string, Json> = { de, fr, es, ar, zh, th, ja, ko, pt, hi };

function flatten(obj: Json, prefix = "", out: Record<string, unknown> = {}) {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === "object" && !Array.isArray(v)) flatten(v as Json, key, out);
    else out[key] = v;
  }
  return out;
}

const PLURAL_SUFFIX = /_(zero|one|two|few|many|other)$/;

describe("i18n plural completeness", () => {
  for (const [code, locale] of Object.entries(LOCALES)) {
    it(`${code}.json covers every plural category its language requires`, () => {
      const flat = flatten(locale);
      const required = new Intl.PluralRules(code).resolvedOptions().pluralCategories;

      const bases = new Set(
        Object.keys(flat)
          .filter((k) => PLURAL_SUFFIX.test(k))
          .map((k) => k.replace(PLURAL_SUFFIX, "")),
      );

      const missing: string[] = [];
      for (const base of bases) {
        for (const category of required) {
          if (flat[`${base}_${category}`] === undefined) {
            missing.push(`${base}_${category}`);
          }
        }
      }

      expect(
        missing,
        `${code} requires [${required.join(", ")}]; i18next falls back to English for any missing form`,
      ).toEqual([]);
    });
  }
});

describe("i18n translation debt", () => {
  const enFlat = flatten(en as Json);

  /**
   * Number of multi-word strings still copied verbatim from English, per
   * locale, at the time this test was written. These are real gaps (the Group
   * Wizard, group templates and parts of the Audit page ship in English), not
   * false positives.
   *
   * The assertion is `<=`, so the debt can only shrink. Lower a number when you
   * translate; never raise one.
   */
  const BASELINE: Record<string, number> = {
    de: 16, es: 19, fr: 28,
    ar: 90, hi: 93, ja: 94, ko: 94, pt: 95, th: 95, zh: 95,
  };

  it("records a baseline for every locale", () => {
    // Without this, adding a 12th locale to LOCALES but not to BASELINE compares
    // its count against `undefined`, and the guard silently stops applying to
    // the one locale most likely to be freshly machine-translated.
    expect(Object.keys(BASELINE).sort()).toEqual(Object.keys(LOCALES).sort());
  });

  for (const [code, locale] of Object.entries(LOCALES)) {
    it(`${code}.json has no more untranslated strings than its baseline`, () => {
      const flat = flatten(locale);
      const untranslated = Object.keys(flat).filter((k) => {
        const source = enFlat[k];
        const target = flat[k];
        if (typeof source !== "string" || typeof target !== "string") return false;
        if (source !== target) return false;
        // Single tokens are usually technical identifiers that must not be
        // translated (e.g. "capabilityMatch", "deploymentContext").
        return source.trim().split(/\s+/).length >= 2;
      });

      expect(
        untranslated.length,
        `${code}: ${untranslated.length} English strings (baseline ${BASELINE[code]}).\n` +
          untranslated.slice(0, 10).map((k) => `  ${k}`).join("\n"),
      ).toBeLessThanOrEqual(BASELINE[code]!);
    });
  }
});

describe("i18n interpolation integrity", () => {
  const enFlat = flatten(en as Json);
  const placeholders = (s: string) =>
    [...s.matchAll(/\{\{\s*([a-zA-Z0-9_]+)[^}]*\}\}/g)].map((m) => m[1]).sort();

  for (const [code, locale] of Object.entries(LOCALES)) {
    it(`${code}.json keeps the interpolation variables English uses`, () => {
      const flat = flatten(locale);
      const broken: string[] = [];
      for (const [key, source] of Object.entries(enFlat)) {
        const target = flat[key];
        if (typeof source !== "string" || typeof target !== "string") continue;
        // Singular and zero forms idiomatically drop the numeral in many
        // languages — Arabic "وكيل واحد" ("one agent") is correct, not a bug.
        if (key.endsWith("_zero") || key.endsWith("_one")) continue;
        const a = placeholders(source).join(",");
        const b = placeholders(target).join(",");
        if (a !== b) broken.push(`${key}: en[${a}] vs ${code}[${b}]`);
      }
      expect(broken, "a dropped {{var}} renders a sentence with a hole in it").toEqual([]);
    });
  }
});
