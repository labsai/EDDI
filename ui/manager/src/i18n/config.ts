import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import LanguageDetector from "i18next-browser-languagedetector";
import en from "./locales/en.json";
import de from "./locales/de.json";
import fr from "./locales/fr.json";
import es from "./locales/es.json";
import ar from "./locales/ar.json";
import zh from "./locales/zh.json";
import th from "./locales/th.json";
import ja from "./locales/ja.json";
import ko from "./locales/ko.json";
import pt from "./locales/pt.json";
import hi from "./locales/hi.json";

export const RTL_LANGUAGES = ["ar", "he", "fa", "ur"];

export function isRtlLanguage(lang: string): boolean {
  return RTL_LANGUAGES.includes(lang.split("-")[0]!);
}

/** Update the document direction based on the current language */
function updateDirection(lang: string) {
  const dir = isRtlLanguage(lang) ? "rtl" : "ltr";
  document.documentElement.setAttribute("dir", dir);
  document.documentElement.setAttribute("lang", lang);
}

i18n
  .use(LanguageDetector)
  .use(initReactI18next)
  .init({
    resources: {
      en: { translation: en },
      de: { translation: de },
      fr: { translation: fr },
      es: { translation: es },
      ar: { translation: ar },
      zh: { translation: zh },
      th: { translation: th },
      ja: { translation: ja },
      ko: { translation: ko },
      pt: { translation: pt },
      hi: { translation: hi },
    },
    fallbackLng: "en",
    interpolation: {
      escapeValue: false,
    },
    detection: {
      order: ["localStorage", "navigator"],
      caches: ["localStorage"],
    },
  });

// Set initial direction
updateDirection(i18n.language);

// Update direction on language change
i18n.on("languageChanged", updateDirection);

export default i18n;
