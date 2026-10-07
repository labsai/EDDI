import { describe, it, expect, afterEach, vi } from "vitest";
import { initLocale, resolveLocale, setLocale, t, getLocale, TRANSLATIONS } from "@/i18n";

afterEach(() => {
  vi.restoreAllMocks();
  setLocale("en");
  document.documentElement.lang = "en";
  document.documentElement.removeAttribute("dir");
});

describe("i18n", () => {
  it("resolves regional and oddly cased tags to the table", () => {
    expect(resolveLocale("de-AT")).toBe("de");
    expect(resolveLocale("FR_ca")).toBe("fr");
    expect(resolveLocale("xx")).toBeNull();
    expect(resolveLocale(null)).toBeNull();
  });

  it("?lang= wins over the browser language and sets <html lang>", () => {
    vi.spyOn(navigator, "languages", "get").mockReturnValue(["fr-FR"]);
    expect(initLocale("de")).toBe("de");
    expect(document.documentElement.lang).toBe("de");
    expect(document.documentElement.dir).toBe("ltr");
  });

  it("falls back to the browser's languages in order, then English", () => {
    vi.spyOn(navigator, "languages", "get").mockReturnValue(["ja-JP", "es-MX", "de"]);
    expect(initLocale(null)).toBe("es");
    vi.spyOn(navigator, "languages", "get").mockReturnValue(["ja-JP"]);
    expect(initLocale("zz")).toBe("en");
  });

  it("fills placeholders and falls back to English for an unknown locale", () => {
    setLocale("de");
    expect(t("attach.removeNth", { file: "a.pdf", n: 1, total: 2 })).toBe("a.pdf entfernen (1 von 2)");
    setLocale("nope");
    expect(getLocale()).toBe("en");
    expect(t("start.retry")).toBe("Try again");
  });

  it("leaves a placeholder literal when no value is supplied for it", () => {
    // (Object.hasOwn guards against inherited names like {constructor}; no shipped string uses one, so only the literal-kept path is observable.)
    expect(t("attach.done", {} as Record<string, string>)).toBe("{file} attached.");
    expect(t("attach.done", { file: "a.pdf" })).toBe("a.pdf attached.");
    const text = t("attach.maxFiles", { max: 3 });
    expect(text).toContain("3");
  });

  it("every translation defines only real keys and keeps the English placeholders", () => {
    const placeholders = (text: string) => (text.match(/\{\w+\}/g) ?? []).sort().join(",");
    const english = TRANSLATIONS.en as Record<string, string>;
    for (const [locale, table] of Object.entries(TRANSLATIONS)) {
      for (const [key, text] of Object.entries(table as Record<string, string>)) {
        expect(english, `${locale}: unknown key ${key}`).toHaveProperty(key);
        expect(placeholders(text), `${locale}: ${key}`).toBe(placeholders(english[key]));
      }
    }
  });

  it("ships complete tables for the languages it offers", () => {
    const keys = Object.keys(TRANSLATIONS.en);
    for (const locale of ["de", "fr", "es"]) {
      expect(Object.keys(TRANSLATIONS[locale]).sort(), locale).toEqual([...keys].sort());
    }
  });
});
