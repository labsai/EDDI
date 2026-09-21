import { describe, it, expect, beforeAll } from "vitest";
import i18next from "i18next";
import en from "@/i18n/locales/en.json";
import de from "@/i18n/locales/de.json";
import ar from "@/i18n/locales/ar.json";
import zh from "@/i18n/locales/zh.json";

/**
 * The key-parity and plural-completeness tests check that the KEYS exist. They
 * cannot tell whether a count of 1 actually renders a singular sentence — the
 * whole point of the change that introduced these keys.
 *
 * These strings used to read "1 orphan(s) found" and "Ended 1 conversation(s)".
 */
describe("plural rendering", () => {
  beforeAll(async () => {
    await i18next.init({
      lng: "en",
      fallbackLng: false,
      resources: { en: { translation: en }, de: { translation: de }, ar: { translation: ar }, zh: { translation: zh } },
      interpolation: { escapeValue: false },
    });
  });

  it("renders English singular and plural from the same key", async () => {
    await i18next.changeLanguage("en");
    expect(i18next.t("orphans.found", { count: 1 })).toBe("1 orphan found");
    expect(i18next.t("orphans.found", { count: 5 })).toBe("5 orphans found");
    expect(i18next.t("conversations.endSuccess", { count: 1 })).toBe("Ended 1 conversation");
    expect(i18next.t("conversations.endSuccess", { count: 3 })).toBe("Ended 3 conversations");
    expect(i18next.t("groupTemplates.roleCount", { count: 1 })).toBe("1 role to assign");
    expect(i18next.t("groupTemplates.roleCount", { count: 4 })).toBe("4 roles to assign");
  });

  it("agrees the verb, not just the noun", async () => {
    await i18next.changeLanguage("en");
    expect(i18next.t("conversations.pausedNote", { count: 1 })).toContain("is Awaiting Human");
    expect(i18next.t("conversations.pausedNote", { count: 2 })).toContain("are Awaiting Human");
  });

  it("renders German singular and plural", async () => {
    await i18next.changeLanguage("de");
    expect(i18next.t("conversations.endSuccess", { count: 1 })).toBe("1 Gespräch beendet");
    expect(i18next.t("conversations.endSuccess", { count: 7 })).toBe("7 Gespräche beendet");
  });

  it("picks a real Arabic form for the counts that are not one", async () => {
    await i18next.changeLanguage("ar");
    // Arabic has six categories; 3 resolves to "few", which previously fell
    // through to the English string entirely.
    const few = i18next.t("groupTemplates.roleCount", { count: 3 });
    const one = i18next.t("groupTemplates.roleCount", { count: 1 });
    expect(few).not.toBe(one);
    expect(few).not.toMatch(/role/i); // not the English fallback
    expect(one).not.toMatch(/role/i);
  });

  it("uses the single Chinese form for every count", async () => {
    await i18next.changeLanguage("zh");
    const one = i18next.t("orphans.found", { count: 1 });
    expect(i18next.t("orphans.found", { count: 9 })).toBe(one.replace("1", "9"));
    expect(one).not.toMatch(/orphan/i);
  });

  it("keeps a parenthetical that is prose rather than a plural marker", async () => {
    await i18next.changeLanguage("en");
    // "Paused (Awaiting Human)" is a status name. An earlier version of the
    // conversion treated every "(...)" as a plural suffix and mangled these.
    expect(i18next.t("conversations.confirmEndDesc", { count: 2 })).toContain("Paused (Awaiting Human)");
    await i18next.changeLanguage("ar");
    expect(i18next.t("conversations.confirmPurgeDesc", { count: 2, days: 2 })).toContain("(ENDED)");
  });

  it("interpolates the count in convergenceSkipped", async () => {
    await i18next.changeLanguage("en");
    // The string interpolated {{skipped}} while its only caller passed `count`,
    // so the number rendered blank.
    expect(i18next.t("groups.convergenceSkipped", { count: 4 })).toContain("4");
  });
});
