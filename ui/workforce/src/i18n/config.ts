import { i18n, setupAppI18n, type AppLocaleLoaders } from "@eddi/app-kit/i18n/app-i18n";
import en from "./locales/en.json";

/**
 * Workforce's own strings, one chunk per non-English locale; English is bundled
 * because it is `fallbackLng` (the Manager's i18n/config.ts has the measured
 * reasons). What the components shared with the Manager render lives in
 * @eddi/app-kit, and `setupAppI18n` merges that bundle (and
 * @eddi/conversation's) into each of these.
 *
 * The detector reads the stored choice first (`i18nextLng` in localStorage), so
 * a language picked in the Manager is the one Workforce opens in: the two apps
 * share an origin, and moving between them is a full page navigation.
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

/** Resolves once the detected language's bundle is in memory; never rejects. */
export const i18nReady: Promise<unknown> = setupAppI18n(en, LOCALE_LOADERS);

export default i18n;
