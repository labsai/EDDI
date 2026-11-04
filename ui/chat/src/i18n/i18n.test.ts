import { readdirSync, readFileSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { describe, it, expect, afterEach, vi } from "vitest";
import { SUPPORTED_LANGUAGES } from "@eddi/ui";
import { initLocale, resolveLocale, setLocale, t, getLocale, LOCALE_LOADERS, type MessageKey } from "@/i18n";
import en from "./locales/en.json";

const LOCALE_DIR = resolve(process.cwd(), "src/i18n/locales");

function table(locale: string): Record<string, string> {
  return JSON.parse(readFileSync(join(LOCALE_DIR, `${locale}.json`), "utf8")) as Record<string, string>;
}

afterEach(async () => {
  vi.restoreAllMocks();
  await setLocale("en");
});

describe("i18n", () => {
  it("resolves regional and oddly cased tags to a shipped locale", () => {
    expect(resolveLocale("de-AT")).toBe("de");
    expect(resolveLocale("FR_ca")).toBe("fr");
    expect(resolveLocale("ja-JP")).toBe("ja");
    expect(resolveLocale("xx")).toBeNull();
    expect(resolveLocale(null)).toBeNull();
  });

  it("?lang= wins over the browser language and sets <html lang>", async () => {
    vi.spyOn(navigator, "languages", "get").mockReturnValue(["fr-FR"]);
    expect(await initLocale("de")).toBe("de");
    expect(document.documentElement.lang).toBe("de");
    expect(document.documentElement.dir).toBe("ltr");
  });

  it("falls back to the browser's languages in order, then English", async () => {
    vi.spyOn(navigator, "languages", "get").mockReturnValue(["nl-NL", "ko-KR", "de"]);
    expect(await initLocale(null)).toBe("ko");
    vi.spyOn(navigator, "languages", "get").mockReturnValue(["nl-NL"]);
    expect(await initLocale("zz")).toBe("en");
  });

  it("lays Arabic out right to left, and back", async () => {
    await initLocale("ar");
    expect(document.documentElement.dir).toBe("rtl");
    expect(document.documentElement.lang).toBe("ar");
    expect(t("input.label")).toBe(table("ar")["input.label"]);
    await initLocale("en");
    expect(document.documentElement.dir).toBe("ltr");
  });

  it("fills placeholders and falls back to English for an unknown locale", async () => {
    await setLocale("de");
    expect(t("attach.removeNth", { file: "a.pdf", n: 1, total: 2 })).toBe("a.pdf entfernen (1 von 2)");
    await setLocale("nope");
    expect(getLocale()).toBe("en");
    expect(t("start.retry")).toBe("Try again");
  });

  it("leaves a placeholder literal when no value is supplied for it", () => {
    expect(t("attach.done", {})).toBe("{{file}} attached.");
    expect(t("attach.done", { file: "a.pdf" })).toBe("a.pdf attached.");
    expect(t("attach.maxFiles", { max: 3 })).toContain("3");
  });

  it("never interpolates a value a second time", () => {
    // A file name is user input: a `{{…}}` or `$t(…)` in it stays literal.
    expect(t("attach.done", { file: "{{max}} $t(start.retry)" })).toBe("{{max}} $t(start.retry) attached.");
  });

  it("loads only the locale in use", async () => {
    const loaders = Object.keys(LOCALE_LOADERS);
    expect(loaders.sort()).toEqual(SUPPORTED_LANGUAGES.filter((l) => l !== "en").sort());
    const spy = vi.spyOn(LOCALE_LOADERS, "ja");
    await setLocale("ja");
    await setLocale("ja-JP");
    // i18next keeps the bundle; the chunk is requested once.
    expect(spy).toHaveBeenCalledTimes(1);
  });
});

describe("locale files", () => {
  const placeholders = (text: string) => (text.match(/\{\{\w+\}\}/g) ?? []).sort().join(",");
  const english = en as Record<string, string>;

  it("ships one file per supported locale, and nothing else", () => {
    const files = readdirSync(LOCALE_DIR).map((f) => f.replace(/\.json$/, "")).sort();
    expect(files).toEqual([...SUPPORTED_LANGUAGES].sort());
  });

  it.each(SUPPORTED_LANGUAGES.filter((l) => l !== "en"))("%s is complete and keeps the English placeholders", (locale) => {
    const strings = table(locale);
    expect(Object.keys(strings).sort()).toEqual(Object.keys(english).sort());
    for (const [key, text] of Object.entries(strings)) {
      expect(placeholders(text), `${locale}: ${key}`).toBe(placeholders(english[key]!));
      expect(text.trim(), `${locale}: ${key} is empty`).not.toBe("");
    }
  });

  it.each(["ar", "zh", "th", "ja", "ko", "hi"])("%s is actually translated", (locale) => {
    // A copied English table would pass the parity check above.
    const strings = table(locale);
    const same = Object.keys(english).filter((key) => key !== "lang.name" && strings[key] === english[key]);
    expect(same).toEqual([]);
  });

  it("every key the code passes to t() exists in English", () => {
    const used = new Map<string, string>();
    const walk = (dir: string): string[] =>
      readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
        const path = join(dir, entry.name);
        if (entry.isDirectory()) return entry.name === "locales" ? [] : walk(path);
        return /\.(ts|tsx)$/.test(entry.name) && !/\.test\./.test(entry.name) ? [path] : [];
      });
    for (const file of walk(resolve(process.cwd(), "src"))) {
      for (const match of readFileSync(file, "utf8").matchAll(/\bt\(\s*["']([A-Za-z0-9_.-]+)["']/g)) {
        used.set(match[1]!, relative(process.cwd(), file));
      }
    }
    expect(used.size).toBeGreaterThan(50);
    const missing = [...used].filter(([key]) => !(key in english)).map(([key, file]) => `${file}: ${key}`);
    expect(missing).toEqual([]);
  });

  it("types every key", () => {
    const key: MessageKey = "input.label";
    expect(english[key]).toBe("Message");
  });
});
