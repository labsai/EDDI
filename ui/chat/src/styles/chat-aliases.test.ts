/* ──────────────────────────────────────────────
   The --chat-* names are documented aliases of the shared tokens
   (frontend unification plan §3.3, D3). Embedders have them in their links
   (`?bgColor=` …) and /branding/brand.css writes one, so this pins three
   things: variables.css declares exactly the alias table @eddi/ui documents,
   every name still resolves to the colour it had before the aliases existed,
   and every name a URL parameter writes is still declared.
   ────────────────────────────────────────────── */

import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { CHAT_TOKEN_ALIASES, SEMANTIC_TOKENS } from "@eddi/ui";

const css = readFileSync(resolve(process.cwd(), "src/styles/variables.css"), "utf8").replace(/\/\*[\s\S]*?\*\//g, "");

/** The declarations of the first rule whose selector list is exactly `selector`. */
function block(selector: string): Map<string, string> {
  const rules = [...css.matchAll(/([^{}]+)\{([^}]*)\}/g)];
  const rule = rules.find((r) => r[1]!.replace(/\s+/g, " ").trim() === selector);
  expect(rule, selector).toBeDefined();
  const decls = new Map<string, string>();
  for (const m of rule![2]!.matchAll(/(--[\w-]+)\s*:\s*([^;]+);/g)) decls.set(m[1]!, m[2]!.trim());
  return decls;
}

const base = block(':root, [data-theme="dark"]');
const light = block('[data-theme="light"]');
const aliases = block(":root");

/** What `<html data-theme=…>` computes for `name`, following var(). */
function computed(theme: "dark" | "light", name: string, depth = 0): string {
  expect(depth, `var() loop at ${name}`).toBeLessThan(10);
  const value = (theme === "light" ? light.get(name) : undefined) ?? aliases.get(name) ?? base.get(name);
  expect(value, `${theme}: ${name} is not declared`).toBeDefined();
  const ref = /^var\((--[\w-]+)\)$/.exec(value!);
  return ref ? computed(theme, ref[1]!, depth + 1) : value!;
}

/** variables.css before phase 3a, value for value. */
const BEFORE: Record<string, { dark: string; light: string }> = {
  "--chat-bg": { dark: "#0a0a0a", light: "#f5f5f5" },
  "--chat-surface": { dark: "#141414", light: "#ffffff" },
  "--chat-surface-raised": { dark: "#1e1e1e", light: "#fafafa" },
  "--chat-text": { dark: "#f1f1f1", light: "#1a1a1a" },
  "--chat-text-muted": { dark: "#999", light: "#666" },
  "--chat-text-accent": { dark: "#cba135", light: "#8b6914" },
  "--chat-accent": { dark: "#113b92", light: "#113b92" },
  "--chat-accent-hover": { dark: "#1a50b5", light: "#0d2d6b" },
  "--chat-accent-soft": { dark: "rgba(17, 59, 146, 0.15)", light: "rgba(17, 59, 146, 0.08)" },
  "--chat-agent-bg": { dark: "#1e1e1e", light: "#ffffff" },
  "--chat-agent-border": { dark: "#2a2a2a", light: "#e0e0e0" },
  "--chat-agent-text": { dark: "#f1f1f1", light: "#1a1a1a" },
  "--chat-user-bg": { dark: "#113b92", light: "#113b92" },
  "--chat-user-text": { dark: "#ffffff", light: "#ffffff" },
  "--chat-input-bg": { dark: "#1a1a1a", light: "#ffffff" },
  "--chat-input-border": { dark: "#333", light: "#d0d0d0" },
  "--chat-input-focus": { dark: "#113b92", light: "#113b92" },
  "--chat-input-text": { dark: "#f1f1f1", light: "#1a1a1a" },
  "--chat-input-placeholder": { dark: "#666", light: "#999" },
  "--chat-qr-bg": { dark: "rgba(17, 59, 146, 0.18)", light: "rgba(17, 59, 146, 0.06)" },
  "--chat-qr-border": { dark: "rgba(17, 59, 146, 0.4)", light: "rgba(17, 59, 146, 0.25)" },
  "--chat-qr-text": { dark: "#7eaaff", light: "#113b92" },
  "--chat-qr-hover": { dark: "rgba(17, 59, 146, 0.35)", light: "rgba(17, 59, 146, 0.14)" },
  "--chat-border": { dark: "#222", light: "#e0e0e0" },
  "--chat-radius": { dark: "16px", light: "16px" },
  "--chat-radius-sm": { dark: "8px", light: "8px" },
  "--chat-font": {
    dark: '"Noto Sans", system-ui, -apple-system, sans-serif',
    light: '"Noto Sans", system-ui, -apple-system, sans-serif',
  },
  "--chat-shadow": { dark: "0 2px 12px rgba(0, 0, 0, 0.4)", light: "0 2px 12px rgba(0, 0, 0, 0.08)" },
  "--chat-scrollbar-thumb": { dark: "#444", light: "#ccc" },
  "--chat-scrollbar-track": { dark: "transparent", light: "transparent" },
  "--chat-warning": { dark: "#d9822b", light: "#a85a12" },
};

describe("--chat-* aliases", () => {
  it("declares exactly the documented alias table, once, on :root", () => {
    const declared = Object.fromEntries([...aliases].map(([name, value]) => [name, value]));
    const expected = Object.fromEntries(
      Object.entries(CHAT_TOKEN_ALIASES).map(([alias, target]) => [alias, `var(${target})`]),
    );
    expect(declared).toEqual(expected);
  });

  it("never gives an aliased name a literal in a theme block, where it would beat the alias", () => {
    for (const name of Object.keys(CHAT_TOKEN_ALIASES)) {
      expect(base.has(name), `dark: ${name}`).toBe(false);
      expect(light.has(name), `light: ${name}`).toBe(false);
    }
  });

  it("only gives values to shared tokens @eddi/ui documents, and --chat-* names", () => {
    const tokens = new Set<string>(SEMANTIC_TOKENS);
    for (const name of [...base.keys(), ...light.keys()]) {
      expect(name.startsWith("--chat-") || tokens.has(name), name).toBe(true);
    }
  });

  it.each(Object.keys(BEFORE))("%s renders as it did before the aliases", (name) => {
    expect(computed("dark", name)).toBe(BEFORE[name]!.dark);
    expect(computed("light", name)).toBe(BEFORE[name]!.light);
  });

  it("declares nothing --chat-* that was not there before", () => {
    const now = new Set([...base.keys(), ...light.keys(), ...aliases.keys()].filter((n) => n.startsWith("--chat-")));
    expect([...now].sort()).toEqual(Object.keys(BEFORE).sort());
  });

  it("declares every name a URL colour parameter writes", () => {
    const widget = readFileSync(resolve(process.cwd(), "src/components/ChatWidget.tsx"), "utf8");
    const map = /const COLOR_PARAM_MAP[^{]*\{([\s\S]*?)\n\};/.exec(widget)?.[1] ?? "";
    const written = [...map.matchAll(/"(--chat-[\w-]+)"/g)].map((m) => m[1]!);
    expect(written.length).toBeGreaterThan(10);
    for (const name of new Set(written)) expect(Object.keys(BEFORE), name).toContain(name);
  });

  it("keeps --chat-accent overridable by /branding/brand.css", () => {
    // BrandDelivery writes `:root{--chat-accent:…}` after this stylesheet; a
    // higher-specificity declaration here would silently win over the brand.
    const rules = [...css.matchAll(/([^{}]+)\{([^}]*)\}/g)];
    const declaring = rules.filter((r) => /--chat-accent\s*:/.test(r[2]!)).map((r) => r[1]!.trim());
    expect(declaring).toEqual([":root"]);
  });
});
