import { readFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { dirname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, it, expect } from "vitest";
import { matchRoutes, type RouteObject } from "react-router-dom";
import { appOfPath, type AppName } from "../lib/app-paths";

/**
 * Guards against dead in-app navigation, for each of the two signed-in apps.
 *
 * `<Link to>` and `navigate()` accept any string. When the target matches no
 * route, React Router falls through to the `path="*"` catch-all — so a wrong
 * link silently ejects the user from whatever they were doing instead of
 * failing loudly. Four such targets shipped at once (the command palette's agent
 * results, the quick-create redirect, the *view breadcrumbs, and the mobile
 * Threads tab) because nothing asserted that a link goes anywhere. This reads
 * the route table straight out of the app's app.tsx, so it stays correct as
 * routes are added.
 *
 * Since the Manager and Workforce became two apps with two routers, a target
 * can also be the OTHER app's path. Through the router it would match nothing
 * here (or worse, this app's catch-all), so it must be a full page navigation:
 * `AppLink` / `AppNavigate` / `useAppNavigate` (@eddi/app-kit). A raw `<Link>`,
 * `<Navigate>` or `navigate()` to the other app fails this check.
 *
 * The files checked are the app's own and the @eddi/app-kit modules it imports
 * (transitively): a shared component's link has to work in each app that
 * renders it.
 */

export const CATCH_ALL = "__catch_all__";

/**
 * Structural mirror of the subset of `RouteObject` this test builds.
 * `RouteObject` is a union (index routes have no `children`), so extending it
 * loses the statically-known members.
 */
export interface ParsedRoute {
  path?: string;
  index?: boolean;
  caseSensitive?: boolean;
  id?: string;
  children?: ParsedRoute[];
}

export const asRoutes = (rs: ParsedRoute[]): RouteObject[] => rs as RouteObject[];

/**
 * Walk the <Route> elements in app.tsx, tracking nesting with a stack so child
 * paths resolve against their parent (e.g. `new` under `/workforce`).
 */
export function parseRouteTree(source: string): ParsedRoute[] {
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

// ─── Which files an app ships ────────────────────────────────────

const KIT_SRC = resolve(dirname(fileURLToPath(import.meta.url)), "..");

function isSource(name: string) {
  return /\.tsx?$/.test(name) && !/\.test\./.test(name) && !name.endsWith(".d.ts");
}

function walkSources(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name !== "__tests__" && entry.name !== "node_modules" && entry.name !== "test") {
        out.push(...walkSources(full));
      }
    } else if (isSource(entry.name)) {
      out.push(full);
    }
  }
  return out;
}

function resolveModule(base: string): string | null {
  for (const candidate of [base, `${base}.ts`, `${base}.tsx`, join(base, "index.ts"), join(base, "index.tsx")]) {
    if (existsSync(candidate) && statSync(candidate).isFile()) return candidate;
  }
  return null;
}

const SPECIFIER = /(?:import|export)\s[^;]*?\sfrom\s+["']([^"']+)["']|import\(\s*["']([^"']+)["']\s*\)/g;

/**
 * The app's own source files under `srcDir`, plus every @eddi/app-kit module
 * they reach — the shared components this app actually renders.
 */
export function appSourceFiles(srcDir: string): string[] {
  const own = walkSources(srcDir);
  const kit = new Set<string>();
  const queue = [...own];
  while (queue.length) {
    const file = queue.pop()!;
    for (const m of readFileSync(file, "utf8").matchAll(SPECIFIER)) {
      const spec = m[1] ?? m[2]!;
      let target: string | null = null;
      if (spec.startsWith("@eddi/app-kit/")) target = resolveModule(join(KIT_SRC, spec.slice("@eddi/app-kit/".length)));
      else if (spec.startsWith(".") && file.startsWith(KIT_SRC)) target = resolveModule(resolve(dirname(file), spec));
      if (target && target.startsWith(KIT_SRC) && isSource(target) && !target.includes(`${sep}test${sep}`) && !kit.has(target)) {
        kit.add(target);
        queue.push(target);
      }
    }
  }
  return [...own, ...kit];
}

// ─── Collect link targets ─────────────────────────────────────────

/** `to="/a"`, ``to={`/a/${x}`}``, `navigate("/a")`, ``navigate(`/a/${x}`)``. */
export const TARGET = /(?:\bto=|\bnavigate\()\{?\s*["'`](\/[^"'`]*)["'`]/g;

/**
 * Any string or template literal that *is* an app path, wherever it appears.
 *
 * TARGET alone requires the literal to sit immediately after `to=`/`navigate(`,
 * so it misses a value behind a ternary — exactly the shape of the mobile
 * Threads tab bug (`to: boardId ? \`/workforce/${id}/thread/\` : "/workforce"`).
 * Matching on the path shape instead catches those. Restricted to the three
 * top-level app prefixes so backend store paths (`/agentstore/…`) are ignored.
 */
export const APP_PATH = /["'`](\/(?:manage|workforce|welcome)(?:\/[^"'`\n]*)?)["'`]/g;

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
export function stripComments(src: string): string {
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

export interface Target {
  raw: string;
  url: string;
  where: string;
  /** How the target is reached: the JSX element of a `to=`, or "navigate". */
  via: string;
  /** For `navigate(…)`: whether the file's navigate is the cross-app one. */
  appNavigate: boolean;
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
 * Both are reported by the "unresolvable" test rather than dropped silently, so
 * an excluded target is a visible decision, not a blind spot.
 */
export function isResolvable(pathOnly: string): boolean {
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

/** The JSX element a `to=` at `index` belongs to: the nearest unclosed `<Name` before it. */
export function elementBefore(text: string, index: number): string {
  const head = text.slice(Math.max(0, index - 400), index);
  const m = /<([A-Za-z][\w.]*)(?![\s\S]*<[A-Za-z])/.exec(head);
  return m?.[1] ?? "";
}

export function collectTargets(files: string[]): { checkable: Target[]; unresolvable: Target[] } {
  const checkable: Target[] = [];
  const unresolvable: Target[] = [];
  const seen = new Set<string>();
  for (const file of files) {
    const text = stripComments(readFileSync(file, "utf8"));
    const appNavigate = /\bnavigate\s*=\s*useAppNavigate\(\)/.test(text);
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
        const via =
          pattern === TARGET ? (m[0].startsWith("navigate") ? "navigate" : elementBefore(text, m.index)) : "";
        const target: Target = {
          raw,
          url: pathOnly.replace(/\$\{[^}]*\}/g, "x"),
          where,
          via,
          appNavigate,
        };
        if (isResolvable(pathOnly)) checkable.push(target);
        else unresolvable.push(target);
      }
    }
  }
  return { checkable, unresolvable };
}

/** The router primitives that cannot leave the app they render in. */
const ROUTER_ONLY = new Set(["Link", "NavLink", "Navigate"]);

/** A target into the other app, reached through the router (so it never arrives). */
export function crossesAppThroughRouter(target: Target, app: AppName): boolean {
  const owner = appOfPath(target.url);
  if (owner === null || owner === app) return false;
  if (target.via === "navigate") return !target.appNavigate;
  return ROUTER_ONLY.has(target.via);
}

// ─── The shared suite ─────────────────────────────────────────────

export interface RouteIntegrityOptions {
  /** The app under test. */
  app: AppName;
  /** Its src/ directory (Vitest runs from the app's directory: "src"). */
  srcDir: string;
  /** A floor for the parsed route count, so a parser that returns nothing fails. */
  minRoutes: number;
  /** Paths that must match a real route, as a sanity check of the parse. */
  samplePaths: string[];
  /** A floor for the in-app targets found. */
  minTargets: number;
  /** How many targets may be unresolvable before the extractor counts as degraded. */
  maxUnresolvable: number;
}

/** The route-integrity suite for one app: its route table, its links and the shared ones it renders. */
export function describeRouteIntegrity(options: RouteIntegrityOptions): ParsedRoute[] {
  const { app, srcDir, minRoutes, samplePaths, minTargets, maxUnresolvable } = options;
  const appFile = join(srcDir, "app.tsx");
  // Strip comments FIRST. Reading app.tsx raw counted a commented-out <Route> as
  // a live route, so retiring a page by commenting it out (or writing a JSDoc
  // example containing a <Route>) kept every link to it passing while the link
  // was already dead at runtime.
  const routes = parseRouteTree(stripComments(readFileSync(appFile, "utf8")));
  const files = appSourceFiles(srcDir);
  const { checkable, unresolvable } = collectTargets(files);
  const inApp = checkable.filter((t) => {
    const owner = appOfPath(t.url);
    return owner === null || owner === app;
  });

  describe(`route integrity (${app})`, () => {
    it("parses a sane route table out of app.tsx", () => {
      const count = (rs: ParsedRoute[]): number => rs.reduce((n, r) => n + 1 + count(r.children ?? []), 0);
      expect(count(routes)).toBeGreaterThan(minRoutes);
      for (const path of samplePaths) expect(matchRoutes(asRoutes(routes), path), path).not.toBeNull();
    });

    it("checks the shared components it renders as well as its own", () => {
      expect(files.some((f) => f.startsWith(KIT_SRC))).toBe(true);
      expect(files.some((f) => !f.startsWith(KIT_SRC))).toBe(true);
    });

    it("finds link targets to check", () => {
      expect(inApp.length).toBeGreaterThan(minTargets);
    });

    it("every in-app link target resolves to a real route", () => {
      const dead = inApp.filter(({ url }) => {
        const matched = matchRoutes(asRoutes(routes), url);
        if (!matched || matched.length === 0) return true;
        return matched[matched.length - 1]!.route.id === CATCH_ALL;
      });
      const report = dead.map((d) => `  ${d.where}\n    to: ${d.raw}  ->  no route (falls through to the catch-all)`).join("\n");
      expect(dead, `Dead navigation targets:\n${report}`).toEqual([]);
    });

    it("never reaches the other app through the router", () => {
      const crossing = checkable.filter((t) => crossesAppThroughRouter(t, app));
      const report = crossing.map((d) => `  ${d.where}\n    <${d.via}> to: ${d.raw}`).join("\n");
      expect(
        crossing,
        `These go to the other app through this app's router, where nothing matches them. Use AppLink / AppNavigate / useAppNavigate (@eddi/app-kit):\n${report}`,
      ).toEqual([]);
    });

    /**
     * Not an assertion about correctness — a visible inventory of what this test
     * cannot check, so the coverage gap stays known. Verify these by hand when
     * they change.
     */
    it("keeps the set of unresolvable targets small and visible", () => {
      const listed = unresolvable
        .map((u) => `  ${u.where}\n    to: ${u.raw}`)
        .sort()
        .join("\n");
      // A bound rather than an exact list: exact equality would fail on every
      // unrelated link edit. If this trips, either the new target is genuinely
      // uncheckable (verify it by hand and raise the bound) or the extractor
      // needs to understand a new template shape.
      expect(unresolvable.length, `Targets this test cannot resolve — verify these by hand:\n${listed}`).toBeLessThanOrEqual(
        maxUnresolvable,
      );
      // Guard against the extractor silently degrading into "checks nothing".
      expect(unresolvable.length).toBeLessThan(checkable.length / 4);
    });
  });

  return routes;
}
