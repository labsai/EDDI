import { describe, expect, it } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

/**
 * A left/right arrow or chevron points the wrong way in a right-to-left locale
 * unless it is mirrored. The Manager ships Arabic, so every such icon must
 * carry an `rtl:` variant (`rtl:rotate-180`) — a disclosure chevron that
 * rotates itself (`rotate-90`) must say what it does in RTL instead.
 */
const DIRECTIONAL = [
  "ArrowLeft",
  "ArrowRight",
  "ChevronLeft",
  "ChevronRight",
  "ChevronsLeft",
  "ChevronsRight",
  "CornerDownLeft",
  "CornerDownRight",
  "MoveLeft",
  "MoveRight",
];

function sourceFiles(dir: string, found: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) {
      if (name !== "__tests__" && name !== "test") sourceFiles(path, found);
    } else if (path.endsWith(".tsx")) {
      found.push(path);
    }
  }
  return found;
}

describe("directional icons", () => {
  it("every left/right arrow and chevron is mirrored in RTL", () => {
    const tag = new RegExp(`<(${DIRECTIONAL.join("|")})(\\s[^<>]*?)?\\s*/>`, "g");
    const offenders: string[] = [];
    for (const file of sourceFiles("src")) {
      const text = readFileSync(file, "utf8");
      for (const match of text.matchAll(tag)) {
        if (!match[0].includes("rtl:")) offenders.push(`${file}: <${match[1]}>`);
      }
    }
    expect(offenders).toEqual([]);
  });
});
