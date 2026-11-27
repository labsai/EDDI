import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import LanguageDetector from "i18next-browser-languagedetector";
import { setDefaultVariables, setupI18n, SUPPORTED_LANGUAGES, type LocaleLoader, type SupportedLanguage } from "@eddi/ui";
import { withConversationLocales } from "@eddi/conversation";
import { mergeLocaleBundles, withTeamLocales } from "@eddi/conversation/team";
import { withToolActivityLocales } from "@eddi/conversation/tool-activity";
import en from "./locales/en.json";
import { brandVariables, getBrand, subscribeBrand } from "../lib/brand";

/** One loader per non-English locale; the record type makes a missing one a compile error. */
export type AppLocaleLoaders = Record<Exclude<SupportedLanguage, "en">, LocaleLoader>;

/**
 * The strings this package's own components render, in the same eleven locales
 * as both apps. Each app's bundle for a locale is this one deep-merged with the
 * app's own (`withAppKitLocales`), so a component from here reads its keys
 * through the app's i18next like any other string. English is bundled (it is
 * `fallbackLng`); every other locale is its own chunk, loaded with the app's.
 */
const APP_KIT_LOCALE_LOADERS: AppLocaleLoaders = {
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

type Bundle = Record<string, unknown>;

/**
 * Deep-merge two nested locale bundles; `over` wins a clash. The files are
 * nested (`{"groups": {"title": …}}`) and one namespace can be split between the
 * packages and an app (`groups.*` is partly here, partly the team components'
 * in @eddi/conversation, partly the Manager's), so a shallow spread would drop
 * part of it. @eddi/conversation/team's merge, which it uses for its own strings.
 */
export const mergeBundles: (base: Bundle, over: Bundle) => Bundle = mergeLocaleBundles;

/** An app's English bundle and loader map, with this package's strings merged in. */
export function withAppKitLocales(
  english: Bundle,
  loaders: AppLocaleLoaders,
): { english: Bundle; loaders: AppLocaleLoaders } {
  const merged = {} as AppLocaleLoaders;
  for (const lng of Object.keys(loaders) as Array<keyof AppLocaleLoaders>) {
    const shared = APP_KIT_LOCALE_LOADERS[lng];
    merged[lng] = () =>
      Promise.all([shared(), loaders[lng]()]).then(([a, b]) => ({ default: mergeBundles(a.default, b.default) }));
  }
  return { english: mergeBundles(en, english), loaders: merged };
}

/**
 * Initialise the app's i18next — the default instance — for one of the signed-in
 * apps: its own strings, this package's and `@eddi/conversation`'s (the agent
 * kind's `conversation.*`, the team components' `groups.*` and the tool
 * cards' `toolActivity.*`), the language
 * detector (stored choice first, so a language picked in the Manager is the one
 * Workforce opens in), and the brand's `{{productName}}` / `{{shortName}}`.
 *
 * Resolves once the detected language's bundle is in memory; `startApp` awaits it
 * before the first paint. Never rejects.
 */
export function setupAppI18n(english: Bundle, loaders: AppLocaleLoaders): Promise<unknown> {
  const withKit = withAppKitLocales(english, loaders);
  const withTeam = withTeamLocales(withKit.english, withKit.loaders);
  const withTools = withToolActivityLocales(withTeam.english, withTeam.loaders);
  const bundles = withConversationLocales(withTools.english, withTools.loaders);
  const ready = setupI18n({
    i18n,
    languages: SUPPORTED_LANGUAGES,
    english: bundles.english,
    loaders: bundles.loaders,
    plugins: [LanguageDetector, initReactI18next],
    // `{{productName}}` / `{{shortName}}` in any string, from the deployment's
    // brand (lib/brand.ts). Set before the promise resolves, so the first render
    // already carries the brand (white-label plan, R11).
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
  return ready;
}

export { i18n };
