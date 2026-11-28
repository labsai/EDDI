import { createInstance } from "i18next";
import { afterEach, describe, expect, it, vi } from "vitest";
import { isRtlLanguage, matchLanguage, SUPPORTED_LANGUAGES } from "./languages";
import { createLazyLocaleBackend, setDefaultVariables, setupI18n, type LocaleLoader } from "./setup";

const english = { greet: "Hello from {{productName}}", only: { en: "English only" } };

function loaders(calls: string[], fail = new Set<string>()): Record<string, LocaleLoader> {
  const bundle = (lang: string) => () => {
    calls.push(lang);
    return fail.has(lang)
      ? Promise.reject(new Error("404"))
      : Promise.resolve({ default: { greet: `[${lang}] {{productName}}` } });
  };
  return { de: bundle("de"), ar: bundle("ar"), ja: bundle("ja") };
}

function root(): HTMLElement {
  return document.createElement("html");
}

afterEach(() => vi.restoreAllMocks());

describe("setupI18n", () => {
  it("bundles English and loads only the starting locale", async () => {
    const calls: string[] = [];
    const i18n = createInstance();
    await setupI18n({ i18n, languages: ["en", "de", "ar", "ja"], english, loaders: loaders(calls), lng: "de", root: root() });
    expect(calls).toEqual(["de"]);
    expect(i18n.t("greet", { productName: "X" })).toBe("[de] X");
    // A key the locale lacks falls back to the bundled English.
    expect(i18n.t("only.en")).toBe("English only");
  });

  it("loads nothing for English", async () => {
    const calls: string[] = [];
    const i18n = createInstance();
    await setupI18n({ i18n, languages: ["en", "de"], english, loaders: loaders(calls), lng: "en", root: root() });
    expect(calls).toEqual([]);
  });

  it("matches a region tag to the locale it ships", async () => {
    const calls: string[] = [];
    const i18n = createInstance();
    await setupI18n({ i18n, languages: ["en", "de"], english, loaders: loaders(calls), lng: "de-AT", root: root() });
    expect(i18n.resolvedLanguage).toBe("de");
    expect(calls).toEqual(["de"]);
  });

  it("fills productName from the default variables, and replaces them at runtime", async () => {
    const i18n = createInstance();
    await setupI18n({
      i18n,
      languages: ["en"],
      english,
      loaders: {},
      lng: "en",
      defaultVariables: { productName: "Acme" },
      root: root(),
    });
    expect(i18n.t("greet")).toBe("Hello from Acme");
    const changed = vi.fn();
    i18n.on("languageChanged", changed);
    setDefaultVariables(i18n, { productName: "Globex" });
    expect(i18n.t("greet")).toBe("Hello from Globex");
    // Re-announced, so react-i18next consumers re-render.
    expect(changed).toHaveBeenCalledWith("en");
  });

  it("sets dir and lang from the RESOLVED language, and follows a change", async () => {
    const html = root();
    const i18n = createInstance();
    await setupI18n({ i18n, languages: ["en", "de", "ar"], english, loaders: loaders([]), lng: "ar", root: html });
    expect(html.getAttribute("dir")).toBe("rtl");
    expect(html.getAttribute("lang")).toBe("ar");
    await i18n.changeLanguage("de");
    expect(html.getAttribute("dir")).toBe("ltr");
    expect(html.getAttribute("lang")).toBe("de");
    // Unsupported: rendered in English, so labelled English — never RTL for Hebrew.
    await i18n.changeLanguage("he");
    expect(html.getAttribute("dir")).toBe("ltr");
    expect(html.getAttribute("lang")).toBe("en");
  });

  it("never rejects when a locale chunk fails, and renders English", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.spyOn(console, "error").mockImplementation(() => {});
    const i18n = createInstance();
    await expect(
      setupI18n({
        i18n,
        languages: ["en", "ja"],
        english,
        loaders: loaders([], new Set(["ja"])),
        lng: "ja",
        defaultVariables: { productName: "EDDI" },
        root: root(),
      }),
    ).resolves.not.toThrow();
    expect(i18n.t("greet")).toBe("Hello from EDDI");
  });
});

describe("createLazyLocaleBackend", () => {
  it("answers null data, not an error, for a language without a loader", () => {
    const callback = vi.fn();
    createLazyLocaleBackend({}).read("xx", "translation", callback);
    expect(callback).toHaveBeenCalledWith(null, null);
  });
});

describe("languages", () => {
  it("ships the eleven locales both apps use, with Arabic the only RTL one", () => {
    expect([...SUPPORTED_LANGUAGES]).toEqual(["en", "de", "fr", "es", "ar", "zh", "th", "ja", "ko", "pt", "hi"]);
    expect(SUPPORTED_LANGUAGES.filter((lang) => isRtlLanguage(lang))).toEqual(["ar"]);
    expect(isRtlLanguage("ar-EG")).toBe(true);
    expect(isRtlLanguage("he")).toBe(false);
  });

  it("matches the first supported candidate by primary subtag", () => {
    expect(matchLanguage(["ja-JP", "es-MX", "de"], ["en", "de", "es"])).toBe("es");
    expect(matchLanguage(["FR_ca"], ["en", "fr"])).toBe("fr");
    expect(matchLanguage([null, undefined, "", "xx"], ["en"])).toBeNull();
  });
});
