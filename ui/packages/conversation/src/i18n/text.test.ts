import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  CONVERSATION_ENGLISH,
  CONVERSATION_LOCALE_LOADERS,
  englishText,
  interpolate,
  withConversationLocales,
} from "./text";

const LOCALES = join(process.cwd(), "src", "i18n", "locales");
const read = (file: string) => JSON.parse(readFileSync(join(LOCALES, file), "utf8")) as Record<string, string>;

describe("the conversation.* strings", () => {
  it("ships the eleven locales both apps ship, each with a loader", () => {
    const files = readdirSync(LOCALES).map((f) => f.replace(/\.json$/, "")).sort();
    expect(files).toEqual(["ar", "de", "en", "es", "fr", "hi", "ja", "ko", "pt", "th", "zh"]);
    expect(["en", ...Object.keys(CONVERSATION_LOCALE_LOADERS)].sort()).toEqual(files);
  });

  it("has every English key in every locale, and nothing else", () => {
    const en = Object.keys(CONVERSATION_ENGLISH).sort();
    for (const file of readdirSync(LOCALES)) {
      expect(Object.keys(read(file)).sort(), file).toEqual(en);
    }
  });

  it("keeps every key in the conversation namespace and every placeholder of English", () => {
    const placeholders = (s: string) => [...s.matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map((m) => m[1]).sort();
    for (const file of readdirSync(LOCALES)) {
      const strings = read(file);
      for (const [key, english] of Object.entries(CONVERSATION_ENGLISH)) {
        expect(key.startsWith("conversation."), key).toBe(true);
        expect(placeholders(strings[key] ?? ""), `${file} ${key}`).toEqual(placeholders(english));
      }
    }
  });

  it("interpolates, and leaves a placeholder no value was given for visible", () => {
    expect(interpolate("Using {{tool}}…", { tool: "calc" })).toBe("Using calc…");
    expect(interpolate("{{a}} and {{b}}", { a: 1 })).toBe("1 and {{b}}");
    expect(englishText("conversation.indicator.tool", { tool: "search" })).toBe("Using search…");
  });

  it("merges into an app's bundle and loaders, the app winning a clash", async () => {
    const { english, loaders } = withConversationLocales(
      { "app.key": "App", "conversation.copy.done": "App's" },
      { de: async () => ({ default: { "app.key": "Anwendung" } }) },
    );
    expect(english["app.key"]).toBe("App");
    expect(english["conversation.copy.done"]).toBe("App's");
    expect(english["conversation.copy.code"]).toBe("Copy code");
    const de = (await loaders.de()).default as Record<string, unknown>;
    expect(de["app.key"]).toBe("Anwendung");
    expect(de["conversation.copy.code"]).toBe(read("de.json")["conversation.copy.code"]);
  });
});
