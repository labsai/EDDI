import { describe, it, expect } from "vitest";
import { GROUP_CONVERSATION_STATES } from "../lib/api/groups";
import { mergeBundles } from "../i18n/app-i18n";
import kitEn from "../i18n/locales/en.json";
import kitDe from "../i18n/locales/de.json";
import kitFr from "../i18n/locales/fr.json";
import kitEs from "../i18n/locales/es.json";
import kitAr from "../i18n/locales/ar.json";
import kitZh from "../i18n/locales/zh.json";
import kitTh from "../i18n/locales/th.json";
import kitJa from "../i18n/locales/ja.json";
import kitKo from "../i18n/locales/ko.json";
import kitPt from "../i18n/locales/pt.json";
import kitHi from "../i18n/locales/hi.json";
import teamEn from "@eddi/conversation/team/locales/en.json";
import teamDe from "@eddi/conversation/team/locales/de.json";
import teamFr from "@eddi/conversation/team/locales/fr.json";
import teamEs from "@eddi/conversation/team/locales/es.json";
import teamAr from "@eddi/conversation/team/locales/ar.json";
import teamZh from "@eddi/conversation/team/locales/zh.json";
import teamTh from "@eddi/conversation/team/locales/th.json";
import teamJa from "@eddi/conversation/team/locales/ja.json";
import teamKo from "@eddi/conversation/team/locales/ko.json";
import teamPt from "@eddi/conversation/team/locales/pt.json";
import teamHi from "@eddi/conversation/team/locales/hi.json";

/**
 * The translation-quality suite both signed-in apps run over the bundle they
 * actually load per locale: their own file deep-merged with this package's
 * (`setupAppI18n`). Key parity is `npm run i18n:check`'s; this asks what it
 * cannot. Two whole classes of breakage slid underneath key parity while it
 * stayed green:
 *
 *  1. A locale can define `key_one`/`key_other` and still render English,
 *     because its language needs plural categories those two do not cover.
 *     Arabic needs six; it shipped two, so counts of 0, 2, 3-10 and 11-99 —
 *     nearly every real count — fell back to English.
 *  2. A key can be present with the English string copied verbatim as its
 *     "translation". 56% of the Group Wizard was English in every locale.
 */

export type Json = Record<string, unknown>;
export type LocaleCode = "en" | "de" | "fr" | "es" | "ar" | "zh" | "th" | "ja" | "ko" | "pt" | "hi";

/** This package's strings over the team components' (@eddi/conversation/team), as `setupAppI18n` merges them. */
const KIT: Record<LocaleCode, Json> = {
  en: mergeBundles(teamEn, kitEn),
  de: mergeBundles(teamDe, kitDe),
  fr: mergeBundles(teamFr, kitFr),
  es: mergeBundles(teamEs, kitEs),
  ar: mergeBundles(teamAr, kitAr),
  zh: mergeBundles(teamZh, kitZh),
  th: mergeBundles(teamTh, kitTh),
  ja: mergeBundles(teamJa, kitJa),
  ko: mergeBundles(teamKo, kitKo),
  pt: mergeBundles(teamPt, kitPt),
  hi: mergeBundles(teamHi, kitHi),
};

/** An app's files merged over this package's, as the app loads them. */
export function appBundles(own: Record<LocaleCode, Json>): Record<LocaleCode, Json> {
  return Object.fromEntries(
    (Object.keys(KIT) as LocaleCode[]).map((code) => [code, mergeBundles(KIT[code], own[code])]),
  ) as Record<LocaleCode, Json>;
}

export function flatten(obj: Json, prefix = "", out: Record<string, unknown> = {}) {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === "object" && !Array.isArray(v)) flatten(v as Json, key, out);
    else out[key] = v;
  }
  return out;
}

const PLURAL_SUFFIX = /_(zero|one|two|few|many|other)$/;

/** Multi-word strings in `locale` copied verbatim from English, minus the reviewed exceptions. */
export function untranslatedKeys(en: Json, locale: Json, exempt: Iterable<string> = []): string[] {
  const enFlat = flatten(en);
  const flat = flatten(locale);
  const skip = new Set(exempt);
  return Object.keys(flat).filter((k) => {
    const source = enFlat[k];
    const target = flat[k];
    if (typeof source !== "string" || typeof target !== "string") return false;
    if (source !== target) return false;
    if (skip.has(k)) return false;
    // Single tokens are usually technical identifiers that must not be
    // translated (e.g. "capabilityMatch", "deploymentContext").
    return source.trim().split(/\s+/).length >= 2;
  });
}

export interface I18nQualityOptions {
  /** The bundles the app loads, per locale (`appBundles(own)`). */
  bundles: Record<LocaleCode, Json>;
  /**
   * Multi-word strings still copied verbatim from English, per locale. The
   * assertion is `<=`, so the debt can only shrink: lower a number when you
   * translate; never raise one.
   */
  baseline: Record<Exclude<LocaleCode, "en">, number>;
  /**
   * Strings identical to English *because that is the correct translation* —
   * loanwords and cognates. Exact keys, never a namespace prefix: a prefix
   * exemption silently covers every key added under it later, which is how a
   * parity test once waived all of `variables.*` and hid 34 real gaps. Each
   * entry needs a reason in the caller.
   */
  deliberatelyIdentical?: Partial<Record<LocaleCode, string[]>>;
}

export function describeI18nQuality({ bundles, baseline, deliberatelyIdentical = {} }: I18nQualityOptions) {
  const { en, ...locales } = bundles;

  describe("i18n plural completeness", () => {
    for (const [code, locale] of Object.entries(locales)) {
      it(`${code} covers every plural category its language requires`, () => {
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
            if (flat[`${base}_${category}`] === undefined) missing.push(`${base}_${category}`);
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
    const enFlat = flatten(en);

    it("records a baseline for every locale", () => {
      // Without this, a 12th locale compares its count against `undefined`, and
      // the guard silently stops applying to the one locale most likely to be
      // freshly machine-translated.
      expect(Object.keys(baseline).sort()).toEqual(Object.keys(locales).sort());
    });

    for (const [code, locale] of Object.entries(locales)) {
      it(`${code} has no more untranslated strings than its baseline`, () => {
        const untranslated = untranslatedKeys(en, locale, deliberatelyIdentical[code as LocaleCode] ?? []);
        const limit = baseline[code as Exclude<LocaleCode, "en">];
        expect(
          untranslated.length,
          `${code}: ${untranslated.length} English strings (baseline ${limit}).\n` +
            untranslated.slice(0, 10).map((k) => `  ${k}`).join("\n"),
        ).toBeLessThanOrEqual(limit);
      });
    }

    it("every deliberately-identical entry still describes a real match", () => {
      // A stale exemption is worse than none: it reads as "reviewed" while
      // covering nothing, and hides the key if the English later diverges.
      for (const [code, keys] of Object.entries(deliberatelyIdentical)) {
        const flat = flatten(bundles[code as LocaleCode]);
        for (const key of keys ?? []) {
          // Both sides must EXIST: `undefined === undefined` would keep an
          // exemption for a deleted key forever.
          expect(typeof enFlat[key], `${key} is exempt but absent from English`).toBe("string");
          expect(typeof flat[key], `${code}.${key} is exempt but absent from ${code}`).toBe("string");
          expect(flat[key], `${code}.${key} is exempt but no longer matches English`).toBe(enFlat[key]);
        }
      }
    });
  });

  describe("i18n interpolation integrity", () => {
    const enFlat = flatten(en);
    const placeholders = (s: string) =>
      [...s.matchAll(/\{\{\s*([a-zA-Z0-9_]+)[^}]*\}\}/g)].map((m) => m[1]).sort();
    // Forms where dropping the numeral is idiomatic: Arabic "وكيل واحد" (one
    // agent) and the dual "وكيلان" (two agents) are correct without a digit.
    const NUMERAL_OPTIONAL = /_(zero|one|two)$/;

    for (const [code, locale] of Object.entries(locales)) {
      it(`${code} keeps the interpolation variables English uses`, () => {
        const broken: string[] = [];
        // Iterate the LOCALE's keys, not English's: plural categories English
        // lacks (_two/_few/_many) have no English counterpart, and keying off
        // English once skipped every one of them.
        for (const [key, target] of Object.entries(flatten(locale))) {
          if (typeof target !== "string") continue;
          if (NUMERAL_OPTIONAL.test(key)) continue;
          const source = PLURAL_SUFFIX.test(key) ? enFlat[key.replace(PLURAL_SUFFIX, "_other")] : enFlat[key];
          if (typeof source !== "string") continue;
          const a = placeholders(source).join(",");
          const b = placeholders(target).join(",");
          if (a !== b) broken.push(`${key}: en[${a}] vs ${code}[${b}]`);
        }
        expect(broken, "a dropped {{var}} renders a sentence with a hole in it").toEqual([]);
      });
    }
  });

  /**
   * Keys built from a template literal are invisible to `npm run i18n:check`,
   * which scans literal `t("...")` calls: ``t(`groups.state.${state}`)`` renders
   * in the group sidebar, the Workforce analytics, the session history and the
   * transcript export. When `REJECTED` was added, `groups.state` gained no entry
   * in any locale and every gate stayed green. Driven off
   * `GROUP_CONVERSATION_STATES`, so a state added later fails here.
   */
  describe("i18n dynamic key completeness", () => {
    for (const [code, locale] of Object.entries(bundles)) {
      it(`${code} translates every group conversation state`, () => {
        const groups = locale.groups as Json | undefined;
        const map = (groups?.state ?? {}) as Record<string, string>;
        expect(GROUP_CONVERSATION_STATES.filter((s) => typeof map[s] !== "string" || !map[s].trim())).toEqual([]);
      });
    }
  });
}
