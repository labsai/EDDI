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
    ar: 78, hi: 81, ja: 82, ko: 82, pt: 83, th: 83, zh: 83,
  };

  /**
   * Strings that are identical to English *because that is the correct
   * translation* — loanwords and cognates, not untranslated copy-paste.
   *
   * Deliberately a list of EXACT keys rather than a namespace prefix. A prefix
   * exemption is prospective: it silently covers every key added under it later,
   * which is precisely how `config.test.ts` once waived all of `variables.*` and
   * hid 34 real gaps. An exact key has to be added by a human who looked at that
   * one string, and it stops applying the moment the English changes.
   *
   * Each entry needs a reason. If you cannot write one, it is debt, not an
   * exception — leave it in the count.
   */
  const DELIBERATELY_IDENTICAL: Record<string, string[]> = {
    // "Task Force" is standard German business vocabulary; "Arbeitsgruppe"
    // means something softer, and the rest of the German UI uses the loanword.
    de: ["Workforce.board.title", "Workforce.boardsLabel", "knowledgeHealth.taskForces"],
    // "expert(s)" is the same word in French, and the surrounding French strings
    // already use it ("Aucun expert").
    fr: ["Workforce.card.advisorCount", "workforce.count"],
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
      const exempt = new Set(DELIBERATELY_IDENTICAL[code] ?? []);
      const untranslated = Object.keys(flat).filter((k) => {
        const source = enFlat[k];
        const target = flat[k];
        if (typeof source !== "string" || typeof target !== "string") return false;
        if (source !== target) return false;
        if (exempt.has(k)) return false;
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

  it("every deliberately-identical entry still describes a real match", () => {
    // A stale exemption is worse than none: it reads as "reviewed" while
    // covering nothing, and hides the key if the English later diverges.
    for (const [code, keys] of Object.entries(DELIBERATELY_IDENTICAL)) {
      const flat = flatten(LOCALES[code]!);
      for (const key of keys) {
        expect(flat[key], `${code}.${key} is exempt but no longer matches English`).toBe(
          enFlat[key],
        );
      }
    }
  });
});

describe("i18n interpolation integrity", () => {
  const enFlat = flatten(en as Json);
  const placeholders = (s: string) =>
    [...s.matchAll(/\{\{\s*([a-zA-Z0-9_]+)[^}]*\}\}/g)].map((m) => m[1]).sort();

  /**
   * Forms where dropping the numeral is idiomatic rather than a defect: the count
   * lives in the noun itself. Arabic "وكيل واحد" (one agent) and the dual
   * "وكيلان" (two agents) are correct without a digit.
   */
  const NUMERAL_OPTIONAL = /_(zero|one|two)$/;
  const PLURAL_FORM = /_(zero|one|two|few|many|other)$/;

  for (const [code, locale] of Object.entries(LOCALES)) {
    it(`${code}.json keeps the interpolation variables English uses`, () => {
      const flat = flatten(locale);
      const broken: string[] = [];

      // Iterate the LOCALE's keys, not English's. Plural categories English does
      // not have (_two/_few/_many, required by ar and by fr/es/pt) have no
      // English counterpart, so keying off enFlat skipped every form this repo
      // added — a dropped {{count}} in ar.agents.count_many went unseen.
      for (const [key, target] of Object.entries(flat)) {
        if (typeof target !== "string") continue;
        if (NUMERAL_OPTIONAL.test(key)) continue;

        // For a plural form, compare against English's canonical `_other`.
        const source = PLURAL_FORM.test(key)
          ? enFlat[key.replace(PLURAL_FORM, "_other")]
          : enFlat[key];
        if (typeof source !== "string") continue;

        const a = placeholders(source).join(",");
        const b = placeholders(target).join(",");
        if (a !== b) broken.push(`${key}: en[${a}] vs ${code}[${b}]`);
      }
      expect(broken, "a dropped {{var}} renders a sentence with a hole in it").toEqual([]);
    });
  }
});
