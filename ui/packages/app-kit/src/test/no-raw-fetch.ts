import { readFileSync, readdirSync } from "node:fs";
import { dirname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, it, expect } from "vitest";

/**
 * One fetch path with token refresh.
 *
 * `api.authorizedFetch` (api-client.ts) renews the access token before the
 * request, retries once with a fresh one after a 401 and, if the session is
 * really gone, raises the "session expired" state. A bare `fetch(` with
 * `api.getAuthHeader()` does none of that: after an idle tab a group discussion
 * start, an attachment upload or a secrets call failed with "401 Unauthorized"
 * and the user's action was lost.
 *
 * This fails on a new bare `fetch(` in app-kit's sources. The allow-list is the
 * client itself and the places where a bare `fetch` is right; each says why.
 */

/** ui/ — this file is ui/packages/app-kit/src/test/no-raw-fetch.ts. */
const UI_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../../../..");
const SRC = join(UI_ROOT, "packages/app-kit/src");

/** Files (relative to app-kit/src) allowed a bare `fetch(`, and why. */
export const RAW_FETCH_ALLOW_LIST: Readonly<Record<string, string>> = {
  "lib/api-client.ts": "the client: `send` and `authorizedFetch` are the two places that call fetch",
  "lib/bearer-event-source.ts": "a reconnecting stream that re-reads its headers on every attempt (a 401 is retried with the new token)",
  "lib/start-app.tsx": "the boot probe, before any session or token exists",
};

function walk(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === "node_modules" || entry.name === "test" ? [] : walk(full);
    return /\.(ts|tsx)$/.test(entry.name) && !/\.test\./.test(entry.name) ? [full] : [];
  });
}

/** Source without comments, so prose that mentions `fetch(` is not a call. */
export function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:"'`])\/\/.*$/gm, "$1");
}

/** `file:line` of every bare `fetch(` call (including `window.fetch(` and `globalThis.fetch(`). */
export function rawFetchCalls(file: string, source: string): string[] {
  const rel = relative(SRC, file).split(sep).join("/");
  const hits: string[] = [];
  stripComments(source)
    .split("\n")
    .forEach((line, i) => {
      if (/(?<![A-Za-z0-9_$])fetch\s*\(/.test(line)) hits.push(`${rel}:${i + 1}`);
    });
  return hits;
}

export function describeNoRawFetch() {
  describe("app-kit sends every request through the refreshing client", () => {
    it("has no bare fetch( outside the allow-list", () => {
      const offenders = walk(SRC).flatMap((file) => {
        const rel = relative(SRC, file).split(sep).join("/");
        if (rel in RAW_FETCH_ALLOW_LIST) return [];
        return rawFetchCalls(file, readFileSync(file, "utf8"));
      });
      expect(
        offenders,
        "use api.authorizedFetch (token refresh, one 401 retry, session-expired state) instead of fetch(",
      ).toEqual([]);
    });

    it("every allow-listed file still exists and still has the call (a stale entry hides the next one)", () => {
      for (const rel of Object.keys(RAW_FETCH_ALLOW_LIST)) {
        const file = join(SRC, rel);
        expect(rawFetchCalls(file, readFileSync(file, "utf8")).length, rel).toBeGreaterThan(0);
      }
    });

    it("recognises a bare call, window.fetch and globalThis.fetch, and ignores prose and the refreshing client", () => {
      expect(rawFetchCalls(join(SRC, "x.ts"), "const r = await fetch(url);")).toHaveLength(1);
      expect(rawFetchCalls(join(SRC, "x.ts"), "window.fetch(url)")).toHaveLength(1);
      expect(rawFetchCalls(join(SRC, "x.ts"), "globalThis.fetch (url)")).toHaveLength(1);
      expect(rawFetchCalls(join(SRC, "x.ts"), "await api.authorizedFetch(url)")).toHaveLength(0);
      expect(rawFetchCalls(join(SRC, "x.ts"), "// a raw fetch( here\n/* fetch( */ const a = 1;")).toHaveLength(0);
    });
  });
}
