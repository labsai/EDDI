import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { join, sep } from "node:path";
import { matchRoutes, type RouteObject } from "react-router-dom";

/**
 * Guards against dead in-app navigation.
 *
 * `<Link to>` and `navigate()` accept any string. When the target matches no
 * route, React Router falls through to the `path="*"` catch-all, which in this
 * app redirects to /welcome — so a wrong link silently ejects the user from
 * whatever they were doing instead of failing loudly.
 *
 * Four such targets shipped at once (the command palette's agent results, the
 * quick-create redirect, the *view breadcrumbs, and the mobile Threads tab)
 * because nothing asserted that a link goes anywhere. This test reads the route
 * table straight out of app.tsx, so it stays correct as routes are added.
 */

const SRC = "src";
const APP = join(SRC, "app.tsx");
const CATCH_ALL = "__catch_all__";

// ─── Build the route table from app.tsx ──────────────────────────

/**
 * Structural mirror of the subset of `RouteObject` this test builds.
 * `RouteObject` is a union (index routes have no `children`), so extending it
 * loses the statically-known members.
 */
interface ParsedRoute {
  path?: string;
  index?: boolean;
  caseSensitive?: boolean;
  id?: string;
  children?: ParsedRoute[];
}

const asRoutes = (rs: ParsedRoute[]): RouteObject[] => rs as RouteObject[];

/**
 * Walk the <Route> elements in app.tsx, tracking nesting with a stack so child
 * paths resolve against their parent (e.g. `new` under `/workforce`).
 */
function parseRouteTree(source: string): ParsedRoute[] {
  const roots: ParsedRoute[] = [];
  const stack: ParsedRoute[] = [];
  const OPEN = "<Route";
  const CLOSE = "</Route>";
  let i = 0;

  while (i < source.length) {
    const openIdx = source.indexOf(OPEN, i);
    const closeIdx = source.indexOf(CLOSE, i);
    if (openIdx === -1 && closeIdx === -1) break;

    if (closeIdx !== -1 && (openIdx === -1 || closeIdx < openIdx)) {
      stack.pop();
      i = closeIdx + CLOSE.length;
      continue;
    }

    // Find the end of the opening tag. Attributes contain JSX
    // (`element={<Page />}`), so a naive scan to the first ">" stops inside the
    // attribute and misreads every parent route as self-closing. Track brace
    // depth and quoting, and only accept a ">" at depth 0.
    let j = openIdx + OPEN.length;
    let depth = 0;
    let quote: string | null = null;
    let end = -1;
    while (j < source.length) {
      const c = source[j]!;
      if (quote) {
        if (c === quote) quote = null;
      } else if (c === '"' || c === "'" || c === "`") {
        quote = c;
      } else if (c === "{") {
        depth++;
      } else if (c === "}") {
        depth--;
      } else if (c === ">" && depth === 0) {
        end = j;
        break;
      }
      j++;
    }
    if (end === -1) break;

    const attrs = source.slice(openIdx + OPEN.length, end);
    const selfClosing = attrs.trimEnd().endsWith("/");

    const pathAttr = /\bpath="([^"]*)"/.exec(attrs)?.[1];
    const isIndex = /(^|\s)index(\s|=|$)/.test(attrs);
    const isCaseSensitive = /(^|\s)caseSensitive(\s|=|$)/.test(attrs);

    const route: ParsedRoute = {};
    if (isIndex) route.index = true;
    if (pathAttr !== undefined) route.path = pathAttr;
    if (isCaseSensitive) route.caseSensitive = true;
    if (pathAttr === "*") route.id = CATCH_ALL;

    const parent = stack[stack.length - 1];
    if (parent) (parent.children ??= []).push(route);
    else roots.push(route);

    if (!selfClosing && !isIndex) stack.push(route);
    i = end + 1;
  }
  return roots;
}

// ─── Collect link targets from the source tree ───────────────────

function sourceFiles(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name !== "__tests__" && entry.name !== "node_modules") {
        out.push(...sourceFiles(full));
      }
    } else if (/\.tsx?$/.test(entry.name) && !/\.test\./.test(entry.name)) {
      out.push(full);
    }
  }
  return out;
}

/** `to="/a"`, ``to={`/a/${x}`}``, `navigate("/a")`, ``navigate(`/a/${x}`)``. */
const TARGET = /(?:\bto=|\bnavigate\()\{?\s*["'`](\/[^"'`]*)["'`]/g;

/**
 * Any string or template literal that *is* an app path, wherever it appears.
 *
 * TARGET alone requires the literal to sit immediately after `to=`/`navigate(`,
 * so it misses a value behind a ternary — exactly the shape of the mobile
 * Threads tab bug (`to: boardId ? \`/workforce/${id}/thread/\` : "/workforce"`).
 * Matching on the path shape instead catches those. Restricted to the three
 * top-level app prefixes so backend store paths (`/agentstore/…`) are ignored.
 */
const APP_PATH = /["'`](\/(?:manage|workforce|welcome)(?:\/[^"'`\n]*)?)["'`]/g;

/**
 * Remove comments before scanning: prose frequently quotes a broken path while
 * explaining why it was broken, and that must not read as a live target.
 * The `[^:]` guard keeps `https://` from being treated as a line comment.
 */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:])\/\/[^\n]*/g, "$1");
}

interface Target {
  raw: string;
  url: string;
  where: string;
}

/**
 * An interpolation is only resolvable to a dummy segment when it fills a whole
 * path segment below the first one. Two shapes are excluded:
 *
 *  - Partial segments (`/workforce${sub}`) — the value carries its own slashes,
 *    so no substitution reproduces a real URL.
 *  - A first-segment interpolation (`/${pref}`) — those select a top-level area
 *    by literal name, and a dummy value can never match a literal route.
 *
 * Both are reported by the "unresolvable" test below rather than dropped
 * silently, so an excluded target is a visible decision, not a blind spot.
 */
function isResolvable(pathOnly: string): boolean {
  if (!pathOnly.includes("${")) return true;
  // every interpolation must be a whole segment...
  for (const m of pathOnly.matchAll(/\$\{[^}]*\}/g)) {
    const before = pathOnly[m.index - 1];
    const after = pathOnly[m.index + m[0].length];
    if (before !== "/") return false;
    if (after !== undefined && after !== "/") return false;
  }
  // ...and must not be the first segment.
  return !/^\/\$\{/.test(pathOnly);
}

function collectTargets(): { checkable: Target[]; unresolvable: Target[] } {
  const checkable: Target[] = [];
  const unresolvable: Target[] = [];
  const seen = new Set<string>();
  for (const file of sourceFiles(SRC)) {
    const text = stripComments(readFileSync(file, "utf8"));
    for (const pattern of [TARGET, APP_PATH]) {
      pattern.lastIndex = 0;
      let m: RegExpExecArray | null;
      while ((m = pattern.exec(text))) {
        const raw = m[1]!;
        const pathOnly = raw.split("?")[0]!.split("#")[0]!;
        const line = text.slice(0, m.index).split("\n").length;
        const where = `${file.split(sep).join("/")}:${line}`;
        const dedupe = `${where}|${raw}`;
        if (seen.has(dedupe)) continue;
        seen.add(dedupe);
        const target: Target = {
          raw,
          url: pathOnly.replace(/\$\{[^}]*\}/g, "x"),
          where,
        };
        if (isResolvable(pathOnly)) checkable.push(target);
        else unresolvable.push(target);
      }
    }
  }
  return { checkable, unresolvable };
}

// ─── The test ────────────────────────────────────────────────────

describe("route integrity", () => {
  const routes = parseRouteTree(readFileSync(APP, "utf8"));

  it("parses a sane route table out of app.tsx", () => {
    // Sanity: if the parser silently returns nothing, the assertions below
    // would vacuously pass.
    const count = (rs: ParsedRoute[]): number =>
      rs.reduce((n, r) => n + 1 + count(r.children ?? []), 0);
    expect(count(routes)).toBeGreaterThan(30);
    expect(matchRoutes(asRoutes(routes), "/manage/agents")).not.toBeNull();
    expect(matchRoutes(asRoutes(routes), "/workforce")).not.toBeNull();
  });

  it("finds link targets to check", () => {
    expect(collectTargets().checkable.length).toBeGreaterThan(15);
  });

  it("every in-app link target resolves to a real route", () => {
    const dead = collectTargets().checkable.filter(({ url }) => {
      const matched = matchRoutes(asRoutes(routes), url);
      if (!matched || matched.length === 0) return true;
      return matched[matched.length - 1]!.route.id === CATCH_ALL;
    });

    const report = dead
      .map((d) => `  ${d.where}\n    to: ${d.raw}  ->  no route (falls through to /welcome)`)
      .join("\n");

    expect(dead, `Dead navigation targets:\n${report}`).toEqual([]);
  });

  /**
   * Not an assertion about correctness — a visible inventory of what this test
   * cannot check, so the coverage gap stays known. Verify these by hand when
   * they change.
   */
  it("keeps the set of unresolvable targets small and visible", () => {
    const { checkable, unresolvable } = collectTargets();
    const listed = unresolvable
      .map((u) => `  ${u.where}\n    to: ${u.raw}`)
      .sort()
      .join("\n");

    // A bound rather than an exact list: exact equality would fail on every
    // unrelated link edit. If this trips, either the new target is genuinely
    // uncheckable (verify it by hand and raise the bound) or the extractor
    // needs to understand a new template shape.
    expect(
      unresolvable.length,
      `Targets this test cannot resolve — verify these by hand:\n${listed}`,
    ).toBeLessThanOrEqual(5);

    // Guard against the extractor silently degrading into "checks nothing".
    expect(unresolvable.length).toBeLessThan(checkable.length / 4);
  });
});
