import { describe, it, expect } from "vitest";
import {
  advisories,
  isAccepted,
  devOnlyAdvisories,
} from "../../.github/scripts/audit-prod.mjs";

/**
 * The production-dependency audit gate (`npm run audit:prod`).
 *
 * Its whole value is that an accepted advisory is accepted *only* for the package
 * the exception was reasoned about. Two bugs made that untrue, and both are
 * pinned here because a silently-permissive security gate is worse than none.
 */

const advisory = (id: string) => ({
  url: `https://github.com/advisories/${id}`,
  severity: "high",
  title: `${id} title`,
});

/** Shape of the `npm audit --json` slice these helpers read. */
const report = (byPackage: Record<string, string[]>) => ({
  vulnerabilities: Object.fromEntries(
    Object.entries(byPackage).map(([pkg, ids]) => [
      pkg,
      { severity: "high", via: ids.map((id) => advisory(id)) },
    ]),
  ),
  metadata: {},
});

const ALLOW = { "GHSA-aaaa": { package: "react-router", reason: "test" } };

describe("audit gate advisory collection", () => {
  it("keeps one entry per (advisory, package) pair", () => {
    // Deduplicating by id alone let whichever package npm reported first win, so
    // a second production package carrying the same GHSA disappeared entirely.
    const found = advisories(
      report({ "react-router": ["GHSA-aaaa"], "some-other-pkg": ["GHSA-aaaa"] }),
    );
    expect(found).toHaveLength(2);
    expect(found.map((a) => a.package).sort()).toEqual([
      "react-router",
      "some-other-pkg",
    ]);
  });

  it("accepts the allowlisted package but NOT another package with the same advisory", () => {
    const found = advisories(
      report({ "react-router": ["GHSA-aaaa"], "some-other-pkg": ["GHSA-aaaa"] }),
    );
    const unaccepted = found.filter((a) => !isAccepted(a, ALLOW));

    expect(unaccepted).toHaveLength(1);
    expect(unaccepted[0]!.package).toBe("some-other-pkg");
  });

  it("does not accept an advisory that is not allowlisted at all", () => {
    const found = advisories(report({ "react-router": ["GHSA-zzzz"] }));
    expect(found.filter((a) => !isAccepted(a, ALLOW))).toHaveLength(1);
  });

  it("ignores `via` entries that are plain strings rather than advisories", () => {
    // npm lists a transitively-vulnerable package with a string `via`, which
    // carries no advisory of its own and must not be counted.
    const r = {
      vulnerabilities: {
        "react-router-dom": { severity: "high", via: ["react-router"] },
        "react-router": { severity: "high", via: [advisory("GHSA-aaaa")] },
      },
      metadata: {},
    };
    expect(advisories(r).map((a) => a.package)).toEqual(["react-router"]);
  });

  it("treats dev-only findings per package, not per advisory id", () => {
    const all = advisories(
      report({ "react-router": ["GHSA-aaaa"], "dev-tool": ["GHSA-aaaa"] }),
    );
    const prod = advisories(report({ "react-router": ["GHSA-aaaa"] }));

    const devOnly = devOnlyAdvisories(all, prod);
    expect(devOnly).toHaveLength(1);
    expect(devOnly[0]!.package).toBe("dev-tool");
  });
});
