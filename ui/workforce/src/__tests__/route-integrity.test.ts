import { describe, it, expect } from "vitest";
import { describeRouteIntegrity } from "@eddi/app-kit/test/route-integrity";
import { WORKFORCE_SUBPAGES } from "@/components/workforce/workforce-subpages";

/**
 * Every link Workforce renders — its own and the shared components' — goes to a
 * real Workforce route, or to the Manager by a page load. The suite, and why it
 * exists, is in @eddi/app-kit (src/test/route-integrity.ts); the Manager runs it
 * over its own table.
 */
const routes = describeRouteIntegrity({
  app: "workforce",
  srcDir: "src",
  minRoutes: 10,
  samplePaths: ["/workforce", "/workforce/board-1/thread/agent-1", "/workforce/chat"],
  minTargets: 10,
  maxUnresolvable: 5,
});

describe("Workforce sub-pages", () => {
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
      // "*" is the not-found catch-all, not a page.
      .filter((p): p is string => !!p && !p.includes(":") && p !== "*")
      .sort();

    expect(literalChildren.length).toBeGreaterThan(0);
    expect([...WORKFORCE_SUBPAGES].sort()).toEqual(literalChildren);
  });
});
