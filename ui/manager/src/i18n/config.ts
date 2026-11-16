import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import LanguageDetector from "i18next-browser-languagedetector";
import { setDefaultVariables, setupI18n, SUPPORTED_LANGUAGES, type LocaleLoader, type SupportedLanguage } from "@eddi/ui";
import en from "./locales/en.json";
import { brandVariables, getBrand, subscribeBrand } from "@/lib/brand";

// The locale list, the RTL subset and the lazy backend are @eddi/ui's, shared
// with the Chat UI (ui/packages/ui/src/i18n). Re-exported for the Manager's own
// code and tests.
export { isRtlLanguage, RTL_LANGUAGES, SUPPORTED_LANGUAGES, type SupportedLanguage } from "@eddi/ui";

/**
 * One dynamic `import()` per non-English locale.
 *
 * ## Why English is the only static import
 *
 * The eleven locale files total 2.7 MB (~710 KB gzipped). Importing them all
 * statically put every one in the entry chunk, so a German user downloaded
 * Arabic, Thai, Hindi and seven others to read a German UI — and the locale data
 * ended up LARGER than the application code itself. Loading one on demand takes
 * roughly 646 KB gzipped off the critical path.
 *
 * English stays bundled because it is `fallbackLng`: it has to be present
 * synchronously for the very first render, and for any key a translation is
 * still missing. That also keeps the test suite synchronous — nothing has to
 * await a chunk to assert on English text.
 *
 * The record's type makes a locale added to `SUPPORTED_LANGUAGES` without a
 * loader here a compile error.
 */
const LOCALE_LOADERS: Record<Exclude<SupportedLanguage, "en">, LocaleLoader> = {
  de: () => import("./locales/de.json"),
  fr: () => import("./locales/fr.json"),
  es: () => import("./locales/es.json"),
  ar: () => import("./locales/ar.json"),
  zh: () => import("./locales/zh.json"),
  th: () => import("./locales/th.json"),
  ja: () => import("./locales/ja.json"),
  ko: () => import("./locales/ko.json"),
  pt: () => import("./locales/pt.json"),
  hi: () => import("./locales/hi.json"),
};

/**
 * Resolves once the detected language's bundle is in memory.
 *
 * `main.tsx` awaits this before the first render. Without it the app paints in
 * English and then swaps to the real language a moment later — a visible flash
 * of the wrong language on every cold load in a non-English locale. Awaiting
 * costs one chunk fetch that would have happened anyway.
 *
 * It never rejects: a failed locale chunk resolves through i18next's own
 * fallback to English, which is a worse UI than intended but a working one.
 * `setupI18n` (@eddi/ui) also keeps `dir` and `lang` on `<html>` in step with
 * the RESOLVED language.
 */
export const i18nReady: Promise<unknown> = setupI18n({
  i18n,
  languages: SUPPORTED_LANGUAGES,
  english: en,
  loaders: LOCALE_LOADERS,
  plugins: [LanguageDetector, initReactI18next],
  // `{{productName}}` / `{{shortName}}` in any string, from the deployment's
  // brand (src/lib/brand.ts). Set before `i18nReady` resolves, so the first
  // render already carries the brand (white-label plan, R11).
  defaultVariables: brandVariables(getBrand()),
  init: {
    detection: {
      order: ["localStorage", "navigator"],
      caches: ["localStorage"],
    },
  },
});

// A brand applied at runtime (the settings page, after a save).
subscribeBrand(() => setDefaultVariables(i18n, brandVariables(getBrand())));

export default i18n;
