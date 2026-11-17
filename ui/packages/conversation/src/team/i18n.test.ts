import { describe, expect, it } from "vitest";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { TEAM_LOCALE_LOADERS, mergeLocaleBundles, withTeamLocales } from "./i18n";

/*
 * The team components' strings in the eleven locales. The apps' `i18n:check`
 * checks them against the code with the apps' own files; this pins the files
 * against each other where the package can see them alone.
 */

const dir = join(__dirname, "i18n", "locales");
const read = (file: string) => JSON.parse(readFileSync(join(dir, file), "utf8")) as Record<string, unknown>;

function flatten(obj: Record<string, unknown>, prefix = "", out: Record<string, string> = {}) {
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === "object" && !Array.isArray(v)) flatten(v as Record<string, unknown>, key, out);
    else out[key] = String(v);
  }
  return out;
}

const PLURAL = /_(zero|one|two|few|many|other)$/;
const placeholders = (s: string) => [...s.matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map((m) => m[1]).sort();

describe("team strings", () => {
  const en = flatten(read("en.json"));
  const others = readdirSync(dir).filter((f) => f.endsWith(".json") && f !== "en.json");

  it("ship in the eleven locales, with a loader for each", () => {
    expect(others.sort()).toEqual(Object.keys(TEAM_LOCALE_LOADERS).map((l) => `${l}.json`).sort());
    expect(Object.keys(en).length).toBeGreaterThan(100);
  });

  it.each(others)("%s has every English key and the same placeholders", (file) => {
    const table = flatten(read(file));
    // Plural categories differ by language (Arabic has six), so a plural key is
    // compared by its base name.
    const bases = new Set(Object.keys(table).map((k) => k.replace(PLURAL, "")));
    const missing = Object.keys(en).filter((k) => !(k in table) && !bases.has(k.replace(PLURAL, "")));
    expect(missing).toEqual([]);
    for (const [key, value] of Object.entries(table)) {
      const source = en[key] ?? en[`${key.replace(PLURAL, "")}_other`];
      if (source === undefined) continue;
      // A plural form may spell its count out ("one bid", Arabic's dual).
      const names = (s: string) => placeholders(s).filter((n) => !(PLURAL.test(key) && n === "count"));
      expect([key, names(value)]).toEqual([key, names(source)]);
    }
  });

  it("merge under an app's bundle, the app winning a clash", async () => {
    const app = { groups: { title: "App title" }, other: "x" };
    const { english, loaders } = withTeamLocales(app, { de: async () => ({ default: { groups: { title: "App-Titel" } } }) });
    expect((english.groups as Record<string, unknown>).title).toBe("App title");
    expect(Object.keys(english.groups as object).length).toBeGreaterThan(1);
    const de = (await loaders.de()).default;
    expect((de.groups as Record<string, unknown>).title).toBe("App-Titel");
    expect(mergeLocaleBundles({ a: { b: 1, c: 2 } }, { a: { c: 3 } })).toEqual({ a: { b: 1, c: 3 } });
  });
});
