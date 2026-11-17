import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { CHAT_TOKEN_ALIASES, SEMANTIC_TOKENS } from "./chat-aliases";

// Comments explain the rules (and name "@theme inline"); test the declarations.
const theme = readFileSync(resolve(__dirname, "styles/theme.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");

describe("design tokens", () => {
  it("declares every semantic token in the @theme block", () => {
    const themeBlock = /@theme\s*\{([\s\S]*?)\n\}/.exec(theme)?.[1] ?? "";
    for (const token of SEMANTIC_TOKENS) {
      expect(themeBlock, token).toMatch(new RegExp(`^\\s*${token}:`, "m"));
    }
  });

  it("keeps the brand scale re-brandable: plain @theme, never inline", () => {
    // BrandDelivery's /branding/brand.css only reaches the utilities when they
    // read the variables at runtime.
    expect(theme).toMatch(/@theme\s*\{/);
    expect(theme).not.toMatch(/@theme\s+inline/);
    for (const step of [50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950]) {
      expect(theme).toContain(`--color-brand-${step}: var(--color-amber-${step});`);
    }
  });

  it("overrides the surfaces for dark mode under .dark", () => {
    const dark = /\n\.dark\s*\{([\s\S]*?)\n\}/.exec(theme)?.[1] ?? "";
    for (const token of ["--color-background", "--color-foreground", "--color-card", "--color-border"]) {
      expect(dark, token).toMatch(new RegExp(`^\\s*${token}:`, "m"));
    }
  });
});

describe("CHAT_TOKEN_ALIASES", () => {
  it("points every --chat-* alias at a shared token or another aliased --chat-* name", () => {
    const tokens = new Set<string>(SEMANTIC_TOKENS);
    for (const [alias, target] of Object.entries(CHAT_TOKEN_ALIASES)) {
      const ok = tokens.has(target) || target in CHAT_TOKEN_ALIASES;
      expect(ok, `${alias} → ${target}`).toBe(true);
      expect(target, alias).not.toBe(alias);
    }
  });
});
