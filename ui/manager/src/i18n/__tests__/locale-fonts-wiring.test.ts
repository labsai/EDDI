import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

/* The Manager's side of the per-locale fonts. The loader itself is
   @eddi/ui/fonts (ui/packages/ui/src/i18n/locale-fonts.test.ts); what the
   app must do with it, and its own font stacks, are pinned here. */

describe("main.tsx", () => {
  const main = readFileSync(resolve(__dirname, "../../main.tsx"), "utf8");

  it("imports only the default Noto family eagerly", () => {
    // The seven script families are ~388 KB of @font-face rules; an eager
    // import puts them all back into the main stylesheet for every user.
    const eager = [...main.matchAll(/^import\s+["'](@fontsource[^"']+)["']/gm)].map((m) => m[1]);
    expect(eager).toEqual(["@fontsource-variable/noto-sans"]);
  });

  it("installs the per-locale loader", () => {
    expect(main).toMatch(/installLocaleFonts\(i18n\)/);
  });
});

// The stacks live in the base stylesheet both apps import (index.css imports it).
describe("@eddi/app-kit/styles/app.css", () => {
  const css = readFileSync(resolve(__dirname, "../../../../packages/app-kit/src/styles/app.css"), "utf8");

  it.each([
    [":root:lang(ja)", "JP"],
    [":root:lang(ko)", "KR"],
    [":root:lang(zh)", "SC"],
    [":root:lang(zh-TW)", "TC"],
  ])("%s puts its own CJK family first, so an earlier locale's glyphs lose", (selector, family) => {
    const rule = css.slice(css.indexOf(selector));
    const stack = /--font-sans:\s*([^;]+);/.exec(rule)![1]!;
    expect(stack.startsWith(`'Noto Sans ${family} Variable'`)).toBe(true);
  });
});
