import { describe, expect, it } from "vitest";
import {
  checkBrandText,
  contrastRatio,
  isSafeBrandColor,
  parseSrgb,
  toColorInputValue,
} from "@/lib/brand-validation";

/** BrandValidator's grammar and rules, so the page flags what the server would refuse. */
describe("isSafeBrandColor", () => {
  it.each(["#fff", "#0f766e", "rgb(15 118 110)", "rgba(15, 118, 110, 0.5)", "hsl(170 80% 26%)", "oklch(0.55 0.1 180)"])(
    "accepts %s",
    (color) => expect(isSafeBrandColor(color)).toBe(true),
  );

  it.each([
    "red",
    "#ffff",
    "red;}body{display:none",
    "url(javascript:alert(1))",
    "expression(alert(1))",
    "rgb(var(--x) 0 0)",
    "calc(1px)",
    "#fff}</style>",
    "",
    `rgb(${"1 ".repeat(40)})`,
  ])("refuses %s", (color) => expect(isSafeBrandColor(color)).toBe(false));
});

describe("checkBrandText", () => {
  it("accepts unset and ordinary names", () => {
    expect(checkBrandText(null, 40)).toBeNull();
    expect(checkBrandText("Acme Assist", 40)).toBeNull();
  });

  it("refuses blanks, overlong names, angle brackets and control or bidi characters", () => {
    expect(checkBrandText("   ", 40)).toBe("blank");
    expect(checkBrandText("x".repeat(13), 12)).toBe("tooLong");
    // Code points, not UTF-16 units: twelve emoji fit a 12-character cap.
    expect(checkBrandText("😀".repeat(12), 12)).toBeNull();
    expect(checkBrandText("<b>Acme</b>", 40)).toBe("angleBrackets");
    expect(checkBrandText(`Acme${String.fromCharCode(7)}`, 40)).toBe("control");
    expect(checkBrandText(`Acme${String.fromCharCode(0x202e)}`, 40)).toBe("control");
  });
});

describe("contrastRatio", () => {
  it("computes WCAG ratios for hex and rgb()", () => {
    expect(contrastRatio("#fff", "#000")).toBeCloseTo(21, 5);
    expect(contrastRatio("#ffffff", "#0f766e")!).toBeCloseTo(5.47, 1);
    expect(contrastRatio("rgb(255 255 255)", "#eab308")!).toBeLessThan(4.5);
  });

  it("does not guess hsl() or oklch()", () => {
    expect(contrastRatio("#fff", "hsl(0 0% 0%)")).toBeNull();
    expect(parseSrgb("oklch(0.5 0.1 100)")).toBeNull();
  });
});

describe("toColorInputValue", () => {
  it("expands short hex for <input type=color> and gives up on anything else", () => {
    expect(toColorInputValue("#ABC")).toBe("#aabbcc");
    expect(toColorInputValue("#0f766e")).toBe("#0f766e");
    expect(toColorInputValue("rgb(1 2 3)")).toBeNull();
  });
});
