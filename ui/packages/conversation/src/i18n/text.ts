/* ──────────────────────────────────────────────
   The shared `conversation.*` strings

   Every string this package shows — a notice the controller writes, a label a
   component renders — is a `conversation.*` key in `locales/<lng>.json`, in the
   same eleven locales as both apps. The keys are flat ("conversation.send.failed"
   is one key, not a path), which i18next reads under the widget's
   `keySeparator: false` and, through `ignoreJSONStructure`, under the Manager's
   nested files too.

   The package does not own an i18next instance: each app merges these bundles
   into its own (`withConversationLocales`) and passes its `t` in — to the
   controller as an option, to the components through `ConversationTextProvider`.
   Without one, English is used, so a component or a controller test renders
   readable text with no setup.
   ────────────────────────────────────────────── */

import en from "./locales/en.json";

/** A key of the shared namespace. */
export type ConversationTextKey = keyof typeof en;

/** Parameters a string interpolates as `{{name}}`. */
export type TextParams = Record<string, string | number>;

/** An app's translator, narrowed to what this package calls. */
export type Translate = (key: ConversationTextKey, params?: TextParams) => string;

/** The English bundle — the fallback for every key. Bundled, never loaded. */
export const CONVERSATION_ENGLISH: Readonly<Record<ConversationTextKey, string>> = en;

/** A locale bundle loaded on demand (the shape `@eddi/ui`'s `setupI18n` takes). */
export type ConversationLocaleLoader = () => Promise<{ default: Record<string, unknown> }>;

/** One chunk per non-English locale, the eleven both apps ship. */
export const CONVERSATION_LOCALE_LOADERS: Readonly<Record<string, ConversationLocaleLoader>> = {
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

/** Fill `{{name}}` placeholders; one no value was passed for stays visible. */
export function interpolate(text: string, params?: TextParams): string {
  if (!params) return text;
  return text.replace(/\{\{\s*(\w+)\s*\}\}/g, (match, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? String(params[name]) : match,
  );
}

/** The English translator: what the package uses when the app passed none. */
export const englishText: Translate = (key, params) => interpolate(CONVERSATION_ENGLISH[key] ?? key, params);

/**
 * An app's English bundle and loader map, with this package's merged in, for
 * `setupI18n`. The app's own strings win a clash (there should be none: every
 * key here starts with `conversation.`).
 */
export function withConversationLocales<L extends Record<string, ConversationLocaleLoader>>(
  english: Record<string, unknown>,
  loaders: L,
): { english: Record<string, unknown>; loaders: L } {
  const merged = {} as Record<string, ConversationLocaleLoader>;
  for (const [lng, load] of Object.entries(loaders)) {
    const shared = CONVERSATION_LOCALE_LOADERS[lng];
    merged[lng] = shared
      ? () => Promise.all([shared(), load()]).then(([a, b]) => ({ default: { ...a.default, ...b.default } }))
      : load;
  }
  return { english: { ...CONVERSATION_ENGLISH, ...english }, loaders: merged as L };
}
