import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { join, sep } from "node:path";
import { matchRoutes, type RouteObject } from "react-router-dom";
import { WORKFORCE_SUBPAGES } from "@/components/workforce/workforce-subpages";

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

    // `<Route` is also a prefix of `<Routes`, and the enclosing <Routes> element
    // would otherwise be parsed as a pathless route that adopts every real route
    // as its child. matchRoutes tolerates that (a pathless parent behaves like a
    // layout route) so it stayed invisible, but the tree shape was wrong.
    const after = source[openIdx + OPEN.length];
    if (after !== undefined && !/[\s>/]/.test(after)) {
      i = openIdx + OPEN.length;
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
 *
 * This is quote-aware on purpose. A naive "strip from the first double-slash to
 * end of line" regex also eats the remainder of any line holding a double slash
 * inside a string literal (`className="a//b" to="/manage/x"`), which would drop
 * a real target and leave this test green while it silently stopped checking —
 * the worst possible failure mode for a guard like this.
 */
function stripComments(src: string): string {
  let out = "";
  let quote: string | null = null;
  let i = 0;

  while (i < src.length) {
    const c = src[i]!;
    const next = src[i + 1];

    if (quote) {
      out += c;
      if (c === "\\") {
        out += next ?? "";
        i += 2;
        continue;
      }
      if (c === quote) quote = null;
      // Bound a desync: a regex literal containing an unpaired quote (`/["']/`)
      // opens a state that never closes, and without this the scanner treats the
      // whole rest of the file as string content and stops finding targets.
      // Single- and double-quoted strings cannot span lines, so a newline ends
      // them — the damage is capped at one line instead of cascading.
      else if (c === "\n" && quote !== "`") quote = null;
      i++;
      continue;
    }

    if (c === '"' || c === "'" || c === "`") {
      quote = c;
      out += c;
      i++;
      continue;
    }

    // Line comment
    if (c === "/" && next === "/") {
      while (i < src.length && src[i] !== "\n") i++;
      continue;
    }

    // Block comment. Must be quote-aware: `path="/Workforce/*"` contains `/*`
    // inside a STRING, and a naive global regex paired it with a `*/` 32 lines
    // later, deleting most of app.tsx — including the `<Navigate to=…>` targets
    // this suite is supposed to be checking. Silent loss of coverage while
    // staying green is the worst outcome for a guard, so it is scanned properly.
    if (c === "/" && next === "*") {
      i += 2;
      while (i < src.length && !(src[i] === "*" && src[i + 1] === "/")) i++;
      i += 2;
      continue;
    }

    out += c;
    i++;
  }
  return out;
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
  // Strip comments FIRST. Reading app.tsx raw counted a commented-out <Route> as
  // a live route, so retiring a page by commenting it out (or writing a JSDoc
  // example containing a <Route>) kept every link to it passing while the link
  // was already dead at runtime.
  const routes = parseRouteTree(stripComments(readFileSync(APP, "utf8")));

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

  it("keeps WORKFORCE_SUBPAGES in sync with the /workforce child routes", () => {
    // workforce-bottom-tabs.tsx reads the second path segment as a board id and
    // must exclude the app's own pages. That set duplicates router knowledge, so
    // assert it here: adding /workforce/<page> without updating the set would
    // otherwise make the Threads tab treat "<page>" as a board and build a URL
    // that matches nothing.
    const workforce = routes.find((r) => r.path === "/workforce");
    expect(workforce, "no /workforce route found — did app.tsx change shape?").toBeDefined();

    const literalChildren = (workforce!.children ?? [])
      .map((c) => c.path)
      .filter((p): p is string => !!p && !p.includes(":"))
      .sort();

    expect(literalChildren.length).toBeGreaterThan(0);
    expect([...WORKFORCE_SUBPAGES].sort()).toEqual(literalChildren);
  });

  describe("stripComments", () => {
    // If this over-strips, the suite above silently stops checking links while
    // still reporting green. That is worse than a false alarm, so pin it.
    it("keeps a target that follows a string containing //", () => {
      const line = '<Link className="a//b" to="/manage/agents" />';
      expect(stripComments(line)).toContain('to="/manage/agents"');
    });

    it("keeps a URL inside a string literal", () => {
      const line = 'const docs = "https://docs.labs.ai/x";';
      expect(stripComments(line)).toContain("https://docs.labs.ai/x");
    });

    it("still removes a real line comment, including a trailing one", () => {
      expect(stripComments('// navigate("/manage/gone")').trim()).toBe("");
      expect(stripComments('navigate("/a"); // was "/manage/gone"')).toBe('navigate("/a"); ');
    });

    it("still removes block comments", () => {
      expect(stripComments('/* to="/manage/gone" */ const x = 1;')).toBe(" const x = 1;");
    });

    it("does not treat /* inside a string literal as a comment", () => {
      // `path="/Workforce/*"` in app.tsx paired with a `*/` 32 lines later and
      // deleted most of the file, so those targets went unchecked.
      const line = 'path="/Workforce/*" caseSensitive\nto="/manage/agents"\n/* real */';
      const out = stripComments(line);
      expect(out).toContain('path="/Workforce/*"');
      expect(out).toContain('to="/manage/agents"');
      expect(out).not.toContain("real");
    });

    it("preserves every route path in the real app.tsx", () => {
      // Direct regression guard: if the scanner ever desyncs (an unbalanced quote
      // inside a regex literal would do it) this fails instead of the suite
      // quietly checking a fraction of the app.
      const raw = readFileSync(APP, "utf8");
      const count = (s: string) => (s.match(/path="[^"]*"/g) ?? []).length;
      expect(count(stripComments(raw))).toBe(count(raw));
    });

    it("handles an escaped quote without losing the rest of the line", () => {
      const line = 'const s = "he said \\"hi\\""; to="/manage/agents";';
      expect(stripComments(line)).toContain('to="/manage/agents"');
    });
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
