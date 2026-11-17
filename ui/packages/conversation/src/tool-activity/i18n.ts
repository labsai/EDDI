/* ──────────────────────────────────────────────
   The tool cards' strings

   Like the team components': nested `toolActivity.*` keys read through the
   signed-in apps' react-i18next, in `i18n/locales/<lng>.json` beside this file,
   in the same eleven locales. @eddi/app-kit's `setupAppI18n` deep-merges them
   under each app's bundle (`withToolActivityLocales`). English is bundled with
   the entry; every other locale is its own chunk, loaded with the app's.
   ────────────────────────────────────────────── */

import en from "./i18n/locales/en.json";

type Bundle = Record<string, unknown>;

const isObject = (v: unknown): v is Bundle => typeof v === "object" && v !== null && !Array.isArray(v);

/** Deep-merge two nested bundles, `over` winning (the team entry's rule, without importing its strings). */
function merge(base: Bundle, over: Bundle): Bundle {
  const out: Bundle = { ...base };
  for (const [key, value] of Object.entries(over)) {
    const prev = out[key];
    out[key] = isObject(prev) && isObject(value) ? merge(prev, value) : value;
  }
  return out;
}

/** A locale bundle loaded on demand (the shape `@eddi/ui`'s `setupI18n` takes). */
export type ToolActivityLocaleLoader = () => Promise<{ default: Bundle }>;

/** The English strings of the tool cards and the tool notice. */
export const TOOL_ACTIVITY_ENGLISH: Readonly<Bundle> = en;

/** One chunk per non-English locale, the eleven both apps ship. */
export const TOOL_ACTIVITY_LOCALE_LOADERS: Readonly<Record<string, ToolActivityLocaleLoader>> = {
  de: () => import("./i18n/locales/de.json"),
  fr: () => import("./i18n/locales/fr.json"),
  es: () => import("./i18n/locales/es.json"),
  ar: () => import("./i18n/locales/ar.json"),
  zh: () => import("./i18n/locales/zh.json"),
  th: () => import("./i18n/locales/th.json"),
  ja: () => import("./i18n/locales/ja.json"),
  ko: () => import("./i18n/locales/ko.json"),
  pt: () => import("./i18n/locales/pt.json"),
  hi: () => import("./i18n/locales/hi.json"),
};

/**
 * An app's English bundle and loader map with the tool cards' strings merged
 * under them, for `setupI18n`. The app's own strings win a clash.
 */
export function withToolActivityLocales<L extends Record<string, ToolActivityLocaleLoader>>(
  english: Bundle,
  loaders: L,
): { english: Bundle; loaders: L } {
  const merged = {} as Record<string, ToolActivityLocaleLoader>;
  for (const lng of Object.keys(loaders)) {
    const shared = TOOL_ACTIVITY_LOCALE_LOADERS[lng];
    const load = () => (loaders[lng] as ToolActivityLocaleLoader)();
    merged[lng] = shared
      ? () => Promise.all([shared(), load()]).then(([a, b]) => ({ default: merge(a.default, b.default) }))
      : load;
  }
  return { english: merge(TOOL_ACTIVITY_ENGLISH as Bundle, english), loaders: merged as L };
}
