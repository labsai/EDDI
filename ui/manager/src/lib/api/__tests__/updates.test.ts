import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import {
  compareVersions,
  fetchLatestEddiRelease,
  getUpdateStatus,
  normalizeVersion,
  parseVersion,
  UpdateCheckError,
} from "../updates";

const LATEST_URL = "https://api.github.com/repos/labsai/EDDI/releases/latest";

describe("updates", () => {
  describe("normalizeVersion", () => {
    it("strips a leading v and surrounding whitespace", () => {
      expect(normalizeVersion(" v6.2.0 ")).toBe("6.2.0");
      expect(normalizeVersion("6.2.0")).toBe("6.2.0");
    });
  });

  describe("parseVersion", () => {
    it("parses the shapes EDDI actually tags", () => {
      expect(parseVersion("6.2.0")).toEqual({ core: [6, 2, 0], prerelease: [] });
      expect(parseVersion("6.2")).toEqual({ core: [6, 2], prerelease: [] });
      expect(parseVersion("6.0.0-RC1")).toEqual({ core: [6, 0, 0], prerelease: ["RC1"] });
      expect(parseVersion("6.3.0-SNAPSHOT")).toEqual({ core: [6, 3, 0], prerelease: ["SNAPSHOT"] });
    });

    it("ignores build metadata, which semver says never affects precedence", () => {
      expect(parseVersion("6.2.0+build.7")).toEqual({ core: [6, 2, 0], prerelease: [] });
    });

    it("returns null for anything that is not a version", () => {
      expect(parseVersion("Unknown")).toBeNull();
      expect(parseVersion("latest")).toBeNull();
      expect(parseVersion("")).toBeNull();
    });
  });

  describe("compareVersions", () => {
    it("orders by numeric core parts", () => {
      expect(compareVersions("6.1.0", "6.2.0")).toBeLessThan(0);
      expect(compareVersions("6.2.0", "6.1.9")).toBeGreaterThan(0);
      expect(compareVersions("6.2.0", "6.2.0")).toBe(0);
      expect(compareVersions("6.10.0", "6.9.0")).toBeGreaterThan(0);
    });

    it("treats a missing part as zero", () => {
      expect(compareVersions("6.2", "6.2.0")).toBe(0);
      expect(compareVersions("6.2", "6.2.1")).toBeLessThan(0);
    });

    it("ranks a release above any prerelease of the same core version", () => {
      expect(compareVersions("6.3.0-SNAPSHOT", "6.3.0")).toBeLessThan(0);
      expect(compareVersions("6.0.0", "6.0.0-RC1")).toBeGreaterThan(0);
    });

    it("still ranks a prerelease above the previous release", () => {
      expect(compareVersions("6.3.0-SNAPSHOT", "6.2.0")).toBeGreaterThan(0);
    });

    it("orders prerelease identifiers by semver rules", () => {
      expect(compareVersions("6.0.0-RC1", "6.0.0-RC2")).toBeLessThan(0);
      expect(compareVersions("6.0.0-alpha.1", "6.0.0-alpha.2")).toBeLessThan(0);
      expect(compareVersions("6.0.0-alpha.1", "6.0.0-alpha.1.1")).toBeLessThan(0);
      // Numeric identifiers rank below alphanumeric ones.
      expect(compareVersions("6.0.0-1", "6.0.0-alpha")).toBeLessThan(0);
    });

    it("returns null when either side is unparseable", () => {
      expect(compareVersions("Unknown", "6.2.0")).toBeNull();
      expect(compareVersions("6.2.0", "nightly")).toBeNull();
    });
  });

  describe("getUpdateStatus", () => {
    it("classifies behind, level and ahead", () => {
      expect(getUpdateStatus("6.1.0", "6.2.0")).toBe("update-available");
      expect(getUpdateStatus("6.2.0", "6.2.0")).toBe("up-to-date");
      expect(getUpdateStatus("6.3.0", "6.2.0")).toBe("ahead");
    });

    it("never claims up to date when a version cannot be read", () => {
      expect(getUpdateStatus("Unknown", "6.2.0")).toBe("unknown");
      expect(getUpdateStatus(undefined, "6.2.0")).toBe("unknown");
      expect(getUpdateStatus("6.2.0", null)).toBe("unknown");
    });
  });

  describe("fetchLatestEddiRelease", () => {
    it("maps the GitHub payload onto a release", async () => {
      server.use(
        http.get(LATEST_URL, () =>
          HttpResponse.json({
            tag_name: "v6.3.0",
            name: "6.3.0 — Highlights",
            html_url: "https://github.com/labsai/EDDI/releases/tag/6.3.0",
            published_at: "2026-08-01T10:00:00Z",
          }),
        ),
      );

      await expect(fetchLatestEddiRelease()).resolves.toEqual({
        version: "6.3.0", // leading v stripped
        name: "6.3.0 — Highlights",
        url: "https://github.com/labsai/EDDI/releases/tag/6.3.0",
        publishedAt: "2026-08-01T10:00:00Z",
      });
    });

    it("sends no Authorization header to GitHub", async () => {
      let sawAuth: string | null = "not-called";
      server.use(
        http.get(LATEST_URL, ({ request }) => {
          sawAuth = request.headers.get("authorization");
          return HttpResponse.json({ tag_name: "6.3.0" });
        }),
      );

      await fetchLatestEddiRelease();
      expect(sawAuth).toBeNull();
    });

    it("falls back to the version and releases page when fields are missing", async () => {
      server.use(http.get(LATEST_URL, () => HttpResponse.json({ tag_name: "6.3.0" })));

      await expect(fetchLatestEddiRelease()).resolves.toEqual({
        version: "6.3.0",
        name: "6.3.0",
        url: "https://github.com/labsai/EDDI/releases",
        publishedAt: null,
      });
    });

    it("reports an exhausted anonymous rate limit distinctly", async () => {
      server.use(
        http.get(LATEST_URL, () =>
          HttpResponse.json({ message: "rate limit exceeded" }, {
            status: 403,
            headers: { "x-ratelimit-remaining": "0" },
          }),
        ),
      );

      await expect(fetchLatestEddiRelease()).rejects.toMatchObject({
        name: "UpdateCheckError",
        reason: "rate-limited",
      });
    });

    it("treats a 403 with budget left as an ordinary failure", async () => {
      server.use(
        http.get(LATEST_URL, () =>
          HttpResponse.json({}, { status: 403, headers: { "x-ratelimit-remaining": "42" } }),
        ),
      );

      await expect(fetchLatestEddiRelease()).rejects.toMatchObject({ reason: "failed" });
    });

    it("reports a network failure as unreachable", async () => {
      server.use(http.get(LATEST_URL, () => HttpResponse.error()));

      const error = await fetchLatestEddiRelease().catch((e: unknown) => e);
      expect(error).toBeInstanceOf(UpdateCheckError);
      expect(error).toMatchObject({ reason: "unreachable" });
    });

    it("rejects a release with no tag rather than inventing a version", async () => {
      server.use(http.get(LATEST_URL, () => HttpResponse.json({ name: "untagged" })));

      await expect(fetchLatestEddiRelease()).rejects.toMatchObject({ reason: "failed" });
    });

    it("rejects a malformed body", async () => {
      server.use(
        http.get(LATEST_URL, () =>
          HttpResponse.text("not json", { headers: { "content-type": "application/json" } }),
        ),
      );

      await expect(fetchLatestEddiRelease()).rejects.toMatchObject({ reason: "failed" });
    });
  });
});
