import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import {
  BRAND_SCALE,
  BRAND_SCALE_STEPS,
  brandPreviewStyle,
  brandScaleDeclarations,
  renderBrandStylesheet,
  resolveBrandColors,
  EMPTY_COLORS,
  UNBRANDED_COLORS,
} from "@/lib/brand-css";

/**
 * `BrandDeliveryTest` keeps {@link BRAND_SCALE} equal to the Java constant; this
 * file keeps the rest of the stylesheet equal to what the Java test expects, so
 * the settings page's preview and the mock's brand.css render what EDDI does.
 */
describe("renderBrandStylesheet", () => {
  it("matches BrandDelivery.stylesheet for a full brand (BrandDeliveryTest.stylesheetSetsTheTokensOfBothUis)", () => {
    const colors = resolveBrandColors({
      primary: "#0f766e",
      primaryForeground: "#ffffff",
      darkPrimary: "#2dd4bf",
      darkPrimaryForeground: "#042f2e",
      chatAccent: null,
    });
    expect(renderBrandStylesheet(colors, 3)).toBe(
      "/* brand revision 3 */\n" +
        ":root{--color-primary:#0f766e;--color-ring:#0f766e;--color-sidebar-accent:#0f766e;" +
        "--color-primary-foreground:#ffffff;--color-sidebar-accent-foreground:#ffffff;" +
        BRAND_SCALE +
        "--brand-loader-accent:#2dd4bf;--chat-accent:#0f766e;}\n" +
        ".dark{--color-primary:#2dd4bf;--color-ring:#2dd4bf;--color-sidebar-accent:#2dd4bf;" +
        "--color-primary-foreground:#042f2e;--color-sidebar-accent-foreground:#042f2e;" +
        BRAND_SCALE +
        "}\n",
    );
  });

  it("is empty with no colour, and touches only the chat for an accent alone", () => {
    expect(renderBrandStylesheet(EMPTY_COLORS, 3)).toBe("");
    expect(renderBrandStylesheet(resolveBrandColors({ ...EMPTY_COLORS, chatAccent: "#123" }), 3)).toBe(
      "/* brand revision 3 */\n:root{--chat-accent:#123;}\n",
    );
  });
});

describe("resolveBrandColors", () => {
  it("lets dark follow light and the accent follow the primary", () => {
    expect(resolveBrandColors({ ...EMPTY_COLORS, primary: "#111", primaryForeground: "#fff" })).toEqual({
      primary: "#111",
      primaryForeground: "#fff",
      darkPrimary: "#111",
      darkPrimaryForeground: "#fff",
      chatAccent: "#111",
    });
  });
});

describe("brand scale", () => {
  it("declares every step the Manager's theme has, each over --color-primary", () => {
    const declarations = brandScaleDeclarations();
    expect(declarations.map(([k]) => k)).toEqual(BRAND_SCALE_STEPS.map((s) => `--color-brand-${s}`));
    for (const [, value] of declarations) expect(value).toContain("var(--color-primary)");
  });

  it("is what index.css's preview panels and BrandDelivery both rely on", () => {
    const css = readFileSync(join(__dirname, "../../index.css"), "utf8");
    for (const step of BRAND_SCALE_STEPS) {
      expect(css).toContain(`--color-brand-${step}: var(--color-amber-${step});`);
    }
    expect(css).toContain(".brand-preview-light {");
    expect(css).toContain(".brand-preview-dark {");
  });
});

describe("brandPreviewStyle", () => {
  it("sets the brand tokens and the scale for a container, nothing for an unset colour", () => {
    const style = brandPreviewStyle("#0f766e", "#ffffff");
    expect(style["--color-primary"]).toBe("#0f766e");
    expect(style["--color-sidebar-accent-foreground"]).toBe("#ffffff");
    expect(style["--color-brand-500"]).toBe("var(--color-primary)");
    expect(brandPreviewStyle(null, null)).toEqual({});
    expect(Object.keys(brandPreviewStyle(null, "#000"))).toEqual([
      "--color-primary-foreground",
      "--color-sidebar-accent-foreground",
    ]);
  });
});

describe("UNBRANDED_COLORS", () => {
  it("is what the stylesheets paint when nothing is branded", () => {
    // The Branding page shows these in an empty field's colour picker; they must
    // be the bundles' real defaults, not a guess.
    const root = join(__dirname, "../../..");
    const manager = readFileSync(join(root, "src/index.css"), "utf8");
    const chat = readFileSync(join(root, "../chat/src/styles/variables.css"), "utf8");
    const declared = (css: string, name: string) =>
      new RegExp(`${name}:\\s*(#[0-9a-fA-F]{6})`).exec(css)?.[1]?.toLowerCase();
    expect(declared(manager, "--color-primary")).toBe(UNBRANDED_COLORS.primary);
    expect(declared(manager, "--color-primary-foreground")).toBe(UNBRANDED_COLORS.primaryForeground);
    expect(declared(chat, "--chat-accent")).toBe(UNBRANDED_COLORS.chatAccent);
  });
});
