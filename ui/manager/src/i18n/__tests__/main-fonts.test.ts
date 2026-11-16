import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * The script fonts load per locale (`installLocaleFonts` in @eddi/ui); what the
 * Manager must do is not undo that in its entry point.
 */
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
