import i18n, { type BackendModule, type ReadCallback } from "i18next";
import { initReactI18next } from "react-i18next";
import LanguageDetector from "i18next-browser-languagedetector";
import en from "./locales/en.json";

/**
 * Every locale this app ships a translation for. Single source of truth: it
 * feeds {@link LOCALE_LOADERS}, i18next's `supportedLngs`, and
 * {@link RTL_LANGUAGES}.
 */
export const SUPPORTED_LANGUAGES = [
  "en", "de", "fr", "es", "ar", "zh", "th", "ja", "ko", "pt", "hi",
] as const;

export type SupportedLanguage = (typeof SUPPORTED_LANGUAGES)[number];

/**
 * Right-to-left locales — the RTL subset of what we actually ship, which today
 * is Arabic alone.
 *
 * This deliberately does NOT list `he`, `fa` or `ur`. It used to, and the result
 * was a bug rather than forward-thinking: a browser set to Hebrew detected as
 * `he-IL`, matched here, and got `dir="rtl"` — while i18next, having no `he`
 * bundle, fell back to English. Users saw English text laid out right-to-left,
 * under `lang="he-IL"`, which also told screen readers to pronounce English with
 * Hebrew phonetics.
 *
 * Add a language here when, and only when, its locale file lands.
 */
export const RTL_LANGUAGES: readonly string[] = ["ar"];

export function isRtlLanguage(lang: string): boolean {
  return RTL_LANGUAGES.includes(lang.split("-")[0]!);
}

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
 * ## Why an explicit map rather than `import(\`./locales/${lng}.json\`)`
 *
 * Vite can resolve a template-literal dynamic import into a glob, but it will
 * then emit a chunk for *every* file matching the pattern, including anything
 * added to the directory later that is not a real locale. Naming the eleven
 * makes the chunk set exactly what {@link SUPPORTED_LANGUAGES} says it is, and a
 * typo becomes a type error instead of a 404 at runtime.
 */
const LOCALE_LOADERS: Record<
  Exclude<SupportedLanguage, "en">,
  () => Promise<{ default: Record<string, unknown> }>
> = {
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
 * A minimal i18next backend that resolves a language from {@link LOCALE_LOADERS}.
 *
 * Using the backend interface rather than a `loadLanguage()` helper is what makes
 * this safe: i18next calls `read` itself whenever it needs a bundle it does not
 * have — on init, on `changeLanguage`, and on a fallback lookup. There is no call
 * site that can forget to load first, which is exactly the failure a hand-rolled
 * loader invites.
 *
 * `i18next-resources-to-backend` does the same job; this is ~15 lines and avoids
 * a dependency for it.
 */
const lazyLocaleBackend: BackendModule = {
  type: "backend",
  // Required by the interface; there is nothing to configure, because the loader
  // map above is the whole backend.
  init: () => {},
  read: (language: string, _namespace: string, callback: ReadCallback) => {
    const loader = LOCALE_LOADERS[language as Exclude<SupportedLanguage, "en">];
    if (!loader) {
      // Not an error: English is bundled, and anything else is genuinely absent.
      // Reporting `null` data (rather than an Error) lets i18next fall back
      // quietly instead of logging a failure for a language we never shipped.
      callback(null, null as never);
      return;
    }
    loader()
      .then((module) => callback(null, module.default as never))
      .catch((error: unknown) => {
        // A chunk that 404s — typically a tab open across a deploy — must not
        // leave the UI blank. Report the failure so i18next falls back to
        // English rather than rendering raw keys.
        callback(error as Error, null as never);
      });
  },
};

/**
 * Point the document at the language actually being rendered.
 *
 * Takes i18next's RESOLVED language, never the detected one. They differ
 * whenever the browser asks for something we do not ship: detection reports
 * `nl-NL`, resolution falls back to `en`, and the rendered text is English. The
 * old code wrote the detected value into both `dir` and `lang`, so an
 * unsupported locale mislabelled the page for assistive tech — and an
 * unsupported RTL locale also flipped the layout for text that was not RTL.
 */
function updateDirection(lang: string) {
  const dir = isRtlLanguage(lang) ? "rtl" : "ltr";
  document.documentElement.setAttribute("dir", dir);
  document.documentElement.setAttribute("lang", lang);
}

/** The language whose bundle is actually in use, after fallback. */
function renderedLanguage(): string {
  return i18n.resolvedLanguage || i18n.language || "en";
}

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
 */
export const i18nReady: Promise<unknown> = i18n
  .use(lazyLocaleBackend)
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    // English is pre-bundled; every other language arrives via the backend.
    resources: { en: { translation: en } },
    // Required whenever `resources` and a backend are combined: without it
    // i18next treats the bundled English as the complete resource set and never
    // asks the backend for anything.
    partialBundledLanguages: true,
    fallbackLng: "en",
    // Without this, `resolvedLanguage` tracks whatever the detector reported —
    // including locales we ship no bundle for — and every consumer that asks
    // "which language is on screen?" gets a wrong answer.
    supportedLngs: [...SUPPORTED_LANGUAGES],
    // Detection returns region-tagged tags (`de-DE`, `pt-BR`). Without this,
    // i18next treats `de-DE` as its own language, finds no bundle and falls all
    // the way back to English for a locale we do ship.
    nonExplicitSupportedLngs: true,
    interpolation: {
      escapeValue: false,
    },
    detection: {
      order: ["localStorage", "navigator"],
      caches: ["localStorage"],
    },
  });

// Set initial direction. Runs before the bundle has necessarily arrived, which
// is correct: the RESOLVED language is already known at init, and `dir`/`lang`
// must be right for the first paint rather than one tick later.
updateDirection(renderedLanguage());

// Update direction on language change. The event carries the REQUESTED language,
// which is not necessarily the one that ends up rendered — read the resolved one.
i18n.on("languageChanged", () => updateDirection(renderedLanguage()));

export default i18n;
