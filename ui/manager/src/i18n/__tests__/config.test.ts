import { describe, it, expect, afterAll } from "vitest";
import i18n, { isRtlLanguage, RTL_LANGUAGES, SUPPORTED_LANGUAGES } from "@/i18n/config";
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

/** Recursively extract all dot-separated key paths from a nested object */
function getKeys(
  obj: Record<string, unknown>,
  prefix = "",
): string[] {
  return Object.entries(obj).flatMap(([k, v]) => {
    const key = prefix ? `${prefix}.${k}` : k;
    return typeof v === "object" && v !== null
      ? getKeys(v as Record<string, unknown>, key)
      : [key];
  });
}

describe("i18n config", () => {
  it("identifies Arabic as RTL", () => {
    expect(isRtlLanguage("ar")).toBe(true);
  });

  it("identifies English as LTR", () => {
    expect(isRtlLanguage("en")).toBe(false);
  });

  it("identifies German as LTR", () => {
    expect(isRtlLanguage("de")).toBe(false);
  });

  it("handles language with region code", () => {
    expect(isRtlLanguage("ar-SA")).toBe(true);
    expect(isRtlLanguage("en-US")).toBe(false);
  });

  it("only claims RTL for locales we actually ship", () => {
    // Regression. `he`, `fa` and `ur` were listed here with no bundle behind
    // them, so a Hebrew browser got dir="rtl" over English text.
    for (const lang of RTL_LANGUAGES) {
      expect(SUPPORTED_LANGUAGES, `${lang} is RTL but has no bundle`).toContain(lang);
    }
  });
});

describe("document language and direction", () => {
  const original = i18n.language;
  afterAll(async () => {
    await i18n.changeLanguage(original);
  });

  it("labels the document with the language actually rendered", async () => {
    await i18n.changeLanguage("de");
    expect(document.documentElement.getAttribute("lang")).toBe("de");
    expect(document.documentElement.getAttribute("dir")).toBe("ltr");
  });

  it("flips direction for Arabic, which we do ship", async () => {
    await i18n.changeLanguage("ar");
    expect(document.documentElement.getAttribute("dir")).toBe("rtl");
    expect(document.documentElement.getAttribute("lang")).toBe("ar");
  });

  it("keeps a region-tagged tag on its base bundle rather than falling back", async () => {
    // `nonExplicitSupportedLngs` — without it `pt-BR` resolves to English even
    // though pt.json exists.
    await i18n.changeLanguage("pt-BR");
    expect(i18n.resolvedLanguage).toBe("pt");
    expect(document.documentElement.getAttribute("lang")).toBe("pt");
  });

  it("falls back to en — LTR, labelled en — for a locale we do not ship", async () => {
    // The Hebrew bug, pinned from the other side: an unsupported RTL locale
    // must render as English AND be labelled English, not as RTL Hebrew.
    await i18n.changeLanguage("he-IL");
    expect(i18n.resolvedLanguage).toBe("en");
    expect(document.documentElement.getAttribute("dir")).toBe("ltr");
    expect(document.documentElement.getAttribute("lang")).toBe("en");
  });

  it("does the same for an unsupported LTR locale", async () => {
    await i18n.changeLanguage("nl-NL");
    expect(i18n.resolvedLanguage).toBe("en");
    expect(document.documentElement.getAttribute("lang")).toBe("en");
  });
});

describe("lazy locale loading", () => {
  const original = i18n.language;
  afterAll(async () => {
    await i18n.changeLanguage(original);
  });

  it("does not hold a locale until something asks for it", async () => {
    // The whole point of the split: all eleven locale files came to 2.7 MB
    // (~710 KB gzipped) in the entry chunk, larger than the app code, and ten of
    // them were dead weight for any given user.
    //
    // Uses Korean deliberately — no other test in this file touches it, so the
    // assertion does not depend on execution order. Asserting `["en"]` outright
    // would pass or fail purely on which describe block ran first.
    expect(Object.keys(i18n.services.resourceStore.data)).not.toContain("ko");
    await i18n.changeLanguage("ko");
    expect(Object.keys(i18n.services.resourceStore.data)).toContain("ko");
    expect(i18n.t("common.save")).toBe("저장");
  });

  it("has English available synchronously, because it is the fallback", () => {
    // English must be in the entry chunk: it is `fallbackLng`, so it has to
    // resolve on the very first render and for any key a translation misses.
    expect(i18n.getResourceBundle("en", "translation")).toBeTruthy();
  });

  it("loads a locale's real strings on changeLanguage, not just its metadata", async () => {
    // Asserting on `resolvedLanguage` alone would pass even if the backend
    // silently failed and i18next fell back to English — the language code would
    // still look right while every string stayed English. Assert on the text.
    await i18n.changeLanguage("de");
    expect(Object.keys(i18n.services.resourceStore.data)).toContain("de");
    expect(i18n.t("common.save")).toBe("Speichern");
  });

  it("loads a second locale without discarding the first", async () => {
    await i18n.changeLanguage("de");
    await i18n.changeLanguage("ar");
    const loaded = Object.keys(i18n.services.resourceStore.data);
    expect(loaded).toEqual(expect.arrayContaining(["en", "de", "ar"]));
    expect(i18n.t("common.save")).toBe("حفظ");
  });

  it("falls back to English for a language we ship no bundle for", async () => {
    await i18n.changeLanguage("he-IL");
    expect(i18n.resolvedLanguage).toBe("en");
    expect(i18n.t("common.save")).toBe("Save");
  });
});

describe("i18n key parity", () => {
  const enKeys = getKeys(en as Record<string, unknown>);
  const locales: Record<string, Record<string, unknown>> = {
    de: de as Record<string, unknown>,
    fr: fr as Record<string, unknown>,
    es: es as Record<string, unknown>,
    ar: ar as Record<string, unknown>,
    zh: zh as Record<string, unknown>,
    th: th as Record<string, unknown>,
    ja: ja as Record<string, unknown>,
    ko: ko as Record<string, unknown>,
    pt: pt as Record<string, unknown>,
    hi: hi as Record<string, unknown>,
  };

  // No exemptions. There used to be one — every `variables.*` key was waived on
  // the grounds that fallbackLng="en" would cover them — and it hid 34 genuinely
  // untranslated strings on the Global Variables page for as long as it existed.
  // A fallback is indistinguishable from a translation when you are reading the
  // code, which is exactly why this has to be checked rather than reasoned about.
  // If a key really must stay English everywhere (a brand name), give it the same
  // English value in every file so the parity is explicit.
  Object.entries(locales).forEach(([code, locale]) => {
    it(`${code}.json has all keys from en.json`, () => {
      const localeKeys = new Set(getKeys(locale));
      const missing = enKeys.filter((k) => !localeKeys.has(k));
      expect(missing).toEqual([]);
    });
  });
});
