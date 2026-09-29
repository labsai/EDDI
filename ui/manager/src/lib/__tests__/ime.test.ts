import { describe, it, expect } from "vitest";
import { isImeComposing } from "@/lib/ime";

describe("isImeComposing", () => {
  it("is true while a composition is open", () => {
    expect(isImeComposing({ nativeEvent: { isComposing: true }, keyCode: 13 })).toBe(true);
  });

  it("is true for Safari's confirming keydown, which arrives after compositionend", () => {
    expect(isImeComposing({ nativeEvent: { isComposing: false }, keyCode: 229 })).toBe(true);
  });

  it("is false for an ordinary Enter", () => {
    expect(isImeComposing({ nativeEvent: { isComposing: false }, keyCode: 13 })).toBe(false);
    expect(isImeComposing({})).toBe(false);
  });
});
