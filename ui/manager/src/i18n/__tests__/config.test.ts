import { describe, it, expect } from "vitest";
import { isRtlLanguage } from "@/i18n/config";

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
