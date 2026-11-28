import { i18n, setupAppI18n, type AppLocaleLoaders } from "@eddi/app-kit/i18n/app-i18n";
import en from "./locales/en.json";

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
 * These files hold the Manager's own strings. What the components shared with
 * Workforce render lives in @eddi/app-kit (`src/i18n/locales`), and Workforce's
 * own strings in `ui/workforce/src/i18n/locales`; `setupAppI18n` merges the
 * package's bundle (and @eddi/conversation's) into each of these.
 *
 * The record's type makes a locale added to `SUPPORTED_LANGUAGES` without a
 * loader here a compile error.
 */
const LOCALE_LOADERS: AppLocaleLoaders = {
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
 * `main.tsx` awaits this (through `startApp`) before the first render. Without
 * it the app paints in English and then swaps to the real language a moment
 * later — a visible flash of the wrong language on every cold load in a
 * non-English locale. It never rejects: a failed locale chunk resolves through
 * i18next's own fallback to English. `setupI18n` (@eddi/ui) also keeps `dir` and
 * `lang` on `<html>` in step with the RESOLVED language.
 */
export const i18nReady: Promise<unknown> = setupAppI18n(en, LOCALE_LOADERS);

export default i18n;
