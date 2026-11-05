/* ──────────────────────────────────────────────
   The team components' strings

   Unlike the agent kind's flat `conversation.*` keys (which the Chat UI reads
   with its own translator), the team components are rendered only by the two
   signed-in apps, which run react-i18next: they call `useTranslation()` and
   their keys are the nested `groups.*` paths they had in @eddi/app-kit, in
   `i18n/locales/<lng>.json` beside this file, in the same eleven locales.
   Each app's bundle for a locale is this one deep-merged under its own
   (`withTeamLocales`, called by @eddi/app-kit's `setupAppI18n`). English is
   bundled — it is the fallback language — and every other locale is its own
   chunk, loaded with the app's.
   ────────────────────────────────────────────── */

import en from "./i18n/locales/en.json";

type Bundle = Record<string, unknown>;

/** A locale bundle loaded on demand (the shape `@eddi/ui`'s `setupI18n` takes). */
export type TeamLocaleLoader = () => Promise<{ default: Bundle }>;

/** The English strings of the team components. */
export const TEAM_ENGLISH: Readonly<Bundle> = en;

/** One chunk per non-English locale, the eleven both apps ship. */
export const TEAM_LOCALE_LOADERS: Readonly<Record<string, TeamLocaleLoader>> = {
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

const isObject = (v: unknown): v is Bundle => typeof v === "object" && v !== null && !Array.isArray(v);

/**
 * Deep-merge two nested locale bundles; `over` wins a clash. One namespace can
 * be split between this package and an app (`groups.*` is partly here, partly
 * @eddi/app-kit's, partly the Manager's), so a shallow spread would drop half
 * of it.
 */
export function mergeLocaleBundles(base: Bundle, over: Bundle): Bundle {
  const out: Bundle = { ...base };
  for (const [key, value] of Object.entries(over)) {
    const prev = out[key];
    out[key] = isObject(prev) && isObject(value) ? mergeLocaleBundles(prev, value) : value;
  }
  return out;
}

/**
 * An app's English bundle and loader map with the team strings merged under
 * them, for `setupI18n`. The app's own strings win a clash.
 */
export function withTeamLocales<L extends Record<string, TeamLocaleLoader>>(
  english: Bundle,
  loaders: L,
): { english: Bundle; loaders: L } {
  const merged = {} as Record<string, TeamLocaleLoader>;
  for (const lng of Object.keys(loaders)) {
    const shared = TEAM_LOCALE_LOADERS[lng];
    const load = () => (loaders[lng] as TeamLocaleLoader)();
    merged[lng] = shared
      ? () => Promise.all([shared(), load()]).then(([a, b]) => ({ default: mergeLocaleBundles(a.default, b.default) }))
      : load;
  }
  return { english: mergeLocaleBundles(TEAM_ENGLISH as Bundle, english), loaders: merged as L };
}
