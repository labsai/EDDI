import { describe, it, expect } from "vitest";
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
import { appBundles, describeI18nQuality, type Json } from "@eddi/app-kit/test/i18n-quality";

/**
 * The translation-quality suite over the bundles Workforce loads: its own files
 * merged with @eddi/app-kit's. The suite and its history are in @eddi/app-kit
 * (src/test/i18n-quality.ts); the Manager runs it over its own.
 */
const bundles = appBundles({ en, de, fr, es, ar, zh, th, ja, ko, pt, hi });

describeI18nQuality({
  bundles,
  // The debt as it stood when these strings moved out of the Manager's files
  // (each at or below the Manager's count before the split). Lower a number
  // when you translate; never raise one.
  baseline: {
    de: 7, es: 4, fr: 3,
    ar: 12, hi: 13, ja: 13, ko: 13, pt: 15, th: 13, zh: 13,
  },
  deliberatelyIdentical: {
    // "Task Force" is standard German business vocabulary; "Arbeitsgruppe"
    // means something softer, and the rest of the German UI uses the loanword.
    de: ["Workforce.board.title", "Workforce.boardsLabel", "knowledgeHealth.taskForces"],
    // "expert(s)" is the same word in French, and the surrounding French strings
    // already use it ("Aucun expert").
    fr: ["Workforce.card.advisorCount", "workforce.count"],
  },
});

describe("the wizard's Template step and its Workforce name stay distinguishable", () => {
  /**
   * Spanish translated both concepts as "plantilla": step 1 of the Workforce
   * wizard was called "Plantilla" while step 2 asked for the "Nombre de la
   * plantilla". The rest of the Workforce namespace already said "grupo de
   * trabajo", so the outliers were realigned to it. Guarded because the two
   * words are near-synonyms in several languages.
   */
  const locales = Object.entries(bundles).filter(([code]) => code !== "en");
  for (const [code, locale] of locales) {
    it(`${code} does not name the Workforce after the Template step`, () => {
      const wizard = ((locale as Json).Workforce as Json).wizard as Json;
      const step = (wizard.stepTemplate as string).toLowerCase();
      const clashing = ["boardName", "boardNameRequired", "createWorkforce", "useDefault"]
        .map((key) => [key, wizard[key] as string] as const)
        .filter(([, value]) => typeof value === "string" && value.toLowerCase().includes(step));
      expect(
        clashing.map(([key, value]) => `${key}: "${value}" contains stepTemplate "${step}"`),
        "the Workforce and the Template step must not share a word",
      ).toEqual([]);
    });
  }
});
