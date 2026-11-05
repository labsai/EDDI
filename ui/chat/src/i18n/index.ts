/* ──────────────────────────────────────────────
   EDDI Chat — i18n on i18next, through @eddi/ui (frontend unification plan,
   phase 3a). The widget ships the same eleven locales as the Manager.

   English (`locales/en.json`) is bundled and is the fallback for any key; each
   other locale is its own chunk, and only the one in use is loaded — the
   widget's first load stays within its budget (ui/bundle-budget.json).

   The locale is process-wide and chosen once at startup (`?lang=` first, then
   the browser's languages), which is why plain functions outside React — the
   SSE copy helpers, the HITL headlines — can call t() as well. There is no
   react-i18next: nothing re-renders on a language change, because the widget
   never changes language after its first paint.

   Only text the WIDGET owns goes through here. What an agent says is never
   translated.
   ────────────────────────────────────────────── */

import { createInstance } from "i18next";
import {
  matchLanguage,
  setupI18n,
  SUPPORTED_LANGUAGES,
  type LocaleLoader,
  type SupportedLanguage,
} from "@eddi/ui";
import { withConversationLocales, type ConversationTextKey } from "@eddi/conversation";
import { getBrand } from "@/api/brand";
import en from "./locales/en.json";

/** A widget string, or one of the shared `conversation.*` strings it renders. */
export type MessageKey = keyof typeof en | ConversationTextKey;

/** One chunk per locale; the record's type makes a missing locale a compile error. */
export const LOCALE_LOADERS: Record<Exclude<SupportedLanguage, "en">, LocaleLoader> = {
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

const i18n = createInstance();

// The shared conversation.* strings ride along in each locale's bundle.
const bundles = withConversationLocales(en, LOCALE_LOADERS);

// Synchronous for English (`initAsync: false`), so the first t() — and every
// test that renders in English — never waits for a chunk.
void setupI18n({
  i18n,
  languages: SUPPORTED_LANGUAGES,
  english: bundles.english,
  loaders: bundles.loaders,
  lng: "en",
  defaultVariables: (() => {
    const brand = getBrand();
    return { productName: brand.productName, shortName: brand.shortName };
  })(),
  init: {
    initAsync: false,
    // Flat keys ("paused.headline" and "paused.headline.tool" both exist), so
    // a dot is part of the key, not a path.
    keySeparator: false,
    nsSeparator: false,
    // A placeholder no value was passed for stays visible, as it always has.
    missingInterpolationHandler: (_text: string, match: RegExpExecArray) => match[0],
  },
});

/** Reduce "de-AT" / "DE_at" to a shipped locale, or null when unsupported. */
export function resolveLocale(raw: string | null | undefined): SupportedLanguage | null {
  return matchLanguage([raw], SUPPORTED_LANGUAGES);
}

/**
 * Pick the locale: an explicit `?lang=` wins, then the browser's languages in
 * preference order, then English. Sets `<html lang>` and `dir` (@eddi/ui keeps
 * them on the RESOLVED language). Resolves once that locale's strings are in;
 * `main.tsx` awaits it before the first render, so nobody sees English flash.
 */
export async function initLocale(langParam?: string | null): Promise<string> {
  const browser =
    typeof navigator === "undefined"
      ? []
      : navigator.languages?.length
        ? navigator.languages
        : [navigator.language];
  const chosen = matchLanguage([langParam, ...browser], SUPPORTED_LANGUAGES) ?? "en";
  await setLocale(chosen);
  return getLocale();
}

/** Switch to `locale` (an unsupported one renders English). Resolves once its strings are in. */
export async function setLocale(locale: string): Promise<void> {
  await i18n.changeLanguage(resolveLocale(locale) ?? "en");
}

export function getLocale(): string {
  return i18n.resolvedLanguage ?? "en";
}

/** Look a string up, falling back to English, and fill `{{name}}` placeholders. */
export function t(key: MessageKey, params?: Record<string, string | number>): string {
  return i18n.t(key, params ?? {});
}
