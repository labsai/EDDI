import { describe, it, expect } from "vitest";
import { readdirSync, readFileSync } from "node:fs";
import { join, relative, resolve } from "node:path";

/**
 * No source file may carry a raw control character.
 *
 * `src/lib/api/groups.ts` once held a literal NUL byte inside a string (a
 * sentinel written as the character itself rather than as `\u0000`). The code
 * ran fine, but grep and most review tooling classify a file containing NUL as
 * binary, so every later diff of the group API module rendered as "binary files
 * differ" — the one module whose contract changes most was the one nobody could
 * review. Write the escape instead: the runtime value is identical.
 *
 * Tab, LF and CR are ordinary whitespace and allowed; everything else in
 * U+0000–U+001F and U+007F is not.
 */

const repoRoot = resolve(__dirname, "../..");
const ROOTS = ["src", "e2e", "scripts"];
const SOURCE = /\.(?:[cm]?[jt]sx?|json|css|html)$/;
// eslint-disable-next-line no-control-regex -- matching control characters is the point
const CONTROL = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/;

function sourceFiles(dir: string): string[] {
  let entries;
  try {
    entries = readdirSync(dir, { withFileTypes: true });
  } catch {
    return [];
  }
  return entries.flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === "node_modules" ? [] : sourceFiles(path);
    return SOURCE.test(entry.name) ? [path] : [];
  });
}

describe("source hygiene", () => {
  it("finds the files it is meant to grade", () => {
    // A walk that silently finds nothing would pass the check below vacuously.
    const files = ROOTS.flatMap((root) => sourceFiles(resolve(repoRoot, root)));
    expect(files.map((f) => relative(repoRoot, f))).toContain(join("src", "lib", "api", "groups.ts"));
  });

  it("has no raw control characters in any source file", () => {
    const offenders: string[] = [];
    for (const file of ROOTS.flatMap((root) => sourceFiles(resolve(repoRoot, root)))) {
      readFileSync(file, "utf8")
        .split("\n")
        .forEach((line, index) => {
          const match = CONTROL.exec(line);
          if (match) {
            const code = match[0].charCodeAt(0).toString(16).padStart(4, "0");
            offenders.push(`${relative(repoRoot, file)}:${index + 1} U+${code.toUpperCase()}`);
          }
        });
    }
    expect(offenders, "write the character as an escape such as \\u0000 instead").toEqual([]);
  });
});
