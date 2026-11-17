import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import {
  crossesAppThroughRouter,
  describeRouteIntegrity,
  elementBefore,
  stripComments,
  type Target,
} from "@eddi/app-kit/test/route-integrity";

/**
 * Every link the Manager renders — its own and the shared components' — goes to
 * a real Manager route, or to Workforce by a page load. The suite, and why it
 * exists, is in @eddi/app-kit (src/test/route-integrity.ts); Workforce runs it
 * over its own table. The helper's own edge cases are pinned here, once.
 */
describeRouteIntegrity({
  app: "manager",
  srcDir: "src",
  minRoutes: 30,
  samplePaths: ["/manage/agents", "/welcome"],
  minTargets: 15,
  maxUnresolvable: 5,
});

describe("stripComments", () => {
  // If this over-strips, the suite silently stops checking links while still
  // reporting green. That is worse than a false alarm, so pin it.
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

  it("does not desync on a regex literal containing a quote", () => {
    // The desync this guard exists for: an unbalanced quote inside a regex
    // literal opens a string state that never closes, and everything after it
    // is treated as string content, so targets stop being extracted.
    const src = 'const re = /["\']/;\nconst x = <Link to="/manage/agents" />;';
    expect(stripComments(src)).toContain('to="/manage/agents"');
  });

  it("does not swallow most of the real app.tsx", () => {
    // A floor, deliberately NOT equality. Stripping a commented-out <Route> is
    // the suite's contract (see parseRouteTree), so asserting the count is
    // unchanged would fail the first time someone retires a route by
    // commenting it out — a false alarm, not a desync. A desync drops dozens.
    const raw = readFileSync("src/app.tsx", "utf8");
    const count = (s: string) => (s.match(/path="[^"]*"/g) ?? []).length;
    const rawCount = count(raw);
    expect(rawCount).toBeGreaterThan(30);
    expect(count(stripComments(raw))).toBeGreaterThan(rawCount * 0.9);
  });

  it("handles an escaped quote without losing the rest of the line", () => {
    const line = 'const s = "he said \\"hi\\""; to="/manage/agents";';
    expect(stripComments(line)).toContain('to="/manage/agents"');
  });
});

describe("crossing into the other app", () => {
  const target = (via: string, url: string, appNavigate = false): Target => ({
    raw: url,
    url,
    where: "x.tsx:1",
    via,
    appNavigate,
  });

  it("names the element a `to=` belongs to", () => {
    const src = '<div><AppLink className="a" to="/workforce">';
    expect(elementBefore(src, src.indexOf("to="))).toBe("AppLink");
    const raw = '<Link\n  to="/workforce"';
    expect(elementBefore(raw, raw.indexOf("to="))).toBe("Link");
  });

  it("fails a router Link, Navigate or plain navigate() into Workforce, and passes the cross-app ones", () => {
    expect(crossesAppThroughRouter(target("Link", "/workforce/b1"), "manager")).toBe(true);
    expect(crossesAppThroughRouter(target("Navigate", "/workforce"), "manager")).toBe(true);
    expect(crossesAppThroughRouter(target("navigate", "/workforce"), "manager")).toBe(true);
    expect(crossesAppThroughRouter(target("navigate", "/workforce", true), "manager")).toBe(false);
    expect(crossesAppThroughRouter(target("AppLink", "/workforce"), "manager")).toBe(false);
    expect(crossesAppThroughRouter(target("Link", "/manage/agents"), "manager")).toBe(false);
    expect(crossesAppThroughRouter(target("Link", "/manage/agents"), "workforce")).toBe(true);
  });
});
