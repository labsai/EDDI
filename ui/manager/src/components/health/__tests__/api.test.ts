import { describe, expect, it } from "vitest";
import { ApiClientError } from "../deps";
import { InvalidPathSegmentError } from "@/lib/api/path-segment";
import { drillDownDenial, healthPaths, normalizeProfile, profileViolations } from "../api";

describe("healthPaths", () => {
  it("builds the agent health query from period, version and repeated origins", () => {
    expect(healthPaths.health("a1")).toBe("/agents/a1/health");
    expect(healthPaths.health("a1", { period: "7d", version: 8, origins: ["api", "email"] })).toBe(
      "/agents/a1/health?period=7d&version=8&origin=api&origin=email",
    );
  });

  it("leaves out absent filters but keeps version 0 out only when null", () => {
    expect(healthPaths.health("a1", { period: "30d", version: null, origins: [] })).toBe("/agents/a1/health?period=30d");
  });

  it("repeats measure for the series and adds groupBy", () => {
    expect(healthPaths.series("a1", ["apdex", "agentFailureRate"], { period: "90d" }, "version")).toBe(
      "/agents/a1/health/series?measure=apdex&measure=agentFailureRate&period=90d&groupBy=version",
    );
  });

  it("builds compare, portfolio, sessions, export and report paths", () => {
    expect(healthPaths.compare("a1", 7, 8, 14)).toBe("/agents/a1/health/compare?from=7&to=8&days=14");
    expect(healthPaths.portfolio({ period: "7d", sort: "attention", status: ["WATCH", "NEEDS_ATTENTION"] })).toBe(
      "/health/agents?period=7d&sort=attention&status=WATCH&status=NEEDS_ATTENTION",
    );
    expect(healthPaths.sessions("a1", { period: "30d", outcome: ["ABANDONED"], evidence: ["silence", "inferred"], limit: 25, cursor: "c1" })).toBe(
      "/agents/a1/health/sessions?period=30d&outcome=ABANDONED&evidence=silence&evidence=inferred&limit=25&cursor=c1",
    );
    expect(healthPaths.export("a1", { format: "csv", period: "7d", kind: "daily" })).toBe(
      "/agents/a1/health/export?format=csv&period=7d&kind=daily",
    );
    expect(healthPaths.reports("a1")).toBe("/agents/a1/health/reports");
    expect(healthPaths.report("a1", "rep-1")).toBe("/agents/a1/health/reports/rep-1");
    expect(healthPaths.profile("a1")).toBe("/agents/a1/health/profile");
    expect(healthPaths.presets()).toBe("/health/presets");
    expect(healthPaths.suggestions("a1")).toBe("/agents/a1/health/outcome-suggestions");
  });

  it("refuses an agent id that would change the path", () => {
    expect(() => healthPaths.health("a/../b")).toThrow(InvalidPathSegmentError);
  });
});

describe("drillDownDenial", () => {
  it("tells REVIEW_DISABLED from FORBIDDEN on a 403", () => {
    expect(drillDownDenial(new ApiClientError(403, "no", "/x", undefined, { reason: "REVIEW_DISABLED" }))).toBe("REVIEW_DISABLED");
    expect(drillDownDenial(new ApiClientError(403, "no", "/x", undefined, { reason: "FORBIDDEN" }))).toBe("FORBIDDEN");
  });

  it("reads a 403 without a recognisable reason as FORBIDDEN, and other errors as no denial", () => {
    expect(drillDownDenial(new ApiClientError(403, "no"))).toBe("FORBIDDEN");
    expect(drillDownDenial(new ApiClientError(500, "boom"))).toBeNull();
    expect(drillDownDenial(new Error("x"))).toBeNull();
  });
});

describe("profileViolations", () => {
  it("returns the per-path violations of a 400", () => {
    const body = { violations: [{ path: "thresholds.resolutionRate.red", message: "Red must differ." }, { nope: 1 }] };
    expect(profileViolations(new ApiClientError(400, "bad", "/x", undefined, body))).toEqual([
      { path: "thresholds.resolutionRate.red", message: "Red must differ." },
    ]);
  });

  it("is empty for other statuses and bodies", () => {
    expect(profileViolations(new ApiClientError(500, "bad", "/x", undefined, { violations: [{ path: "a", message: "b" }] }))).toEqual([]);
    expect(profileViolations(new ApiClientError(400, "bad"))).toEqual([]);
    expect(profileViolations(null)).toEqual([]);
  });
});

describe("normalizeProfile", () => {
  it("fills the parts a stored profile may omit", () => {
    expect(normalizeProfile({ preset: "task" })).toEqual({
      preset: "task",
      critical: [],
      thresholds: {},
      budgets: {},
      slo: {},
      overrides: [],
    });
  });
});
