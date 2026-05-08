import { describe, it, expect } from "vitest";
import { isRtlLanguage } from "@/i18n/config";
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

  it("identifies Hebrew as RTL", () => {
    expect(isRtlLanguage("he")).toBe(true);
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

  // Keys under these namespaces intentionally rely on fallbackLng="en"
  // rather than duplicating English strings in every locale file.
  const fallbackNamespaces = ["variables."];

  Object.entries(locales).forEach(([code, locale]) => {
    it(`${code}.json has all keys from en.json`, () => {
      const localeKeys = new Set(getKeys(locale));
      const missing = enKeys.filter(
        (k) =>
          !localeKeys.has(k) &&
          !fallbackNamespaces.some((ns) => k.startsWith(ns)),
      );
      expect(missing).toEqual([]);
    });
  });
});
