import en from "@/i18n/locales/en.json";
import de from "@/i18n/locales/de.json";
import fr from "@/i18n/locales/fr.json";
import es from "@/i18n/locales/es.json";
import ar from "@/i18n/locales/ar.json";
import zh from "@/i18n/locales/zh.json";
import th from "@/i18n/locales/th.json";
import ja from "@/i18n/locales/ja.json";
import ko from "@/i18n/locales/ko.json";
import pt from "@/i18n/locales/pt.json";
import hi from "@/i18n/locales/hi.json";
import { appBundles, describeI18nQuality } from "@eddi/app-kit/test/i18n-quality";

/**
 * The translation-quality suite (plural categories, translation debt,
 * interpolation, dynamic keys) over the bundles the Manager loads: its own
 * files merged with @eddi/app-kit's. The suite and its history are in
 * @eddi/app-kit (src/test/i18n-quality.ts); Workforce runs it over its own.
 */
describeI18nQuality({
  bundles: appBundles({ en, de, fr, es, ar, zh, th, ja, ko, pt, hi }),
  // The debt as it stood when Workforce's strings moved out of these files
  // (each at or below the count before the split). Lower a number when you
  // translate; never raise one.
  baseline: {
    de: 16, es: 19, fr: 26,
    ar: 73, hi: 76, ja: 77, ko: 77, pt: 81, th: 79, zh: 78,
  },
});
