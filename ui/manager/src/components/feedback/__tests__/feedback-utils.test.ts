import { describe, expect, it } from "vitest";
import { allRatingsQuery, attentionQuery, countDelta, percent, periodRange, pointDelta, readFilters, writeFilter } from "../feedback-utils";
import { ratingQueryString } from "@/lib/api/ratings";

const NOW = Date.parse("2026-10-24T12:00:00Z");

describe("feedback filters", () => {
  it("reads the URL with safe defaults and ignores unknown values", () => {
    const f = readFilters(new URLSearchParams("tab=all&period=7d&kind=bogus&agentVersion=x2&status=REVIEWED&score=low"));
    expect(f).toMatchObject({ tab: "all", period: "7d", kind: "", agentVersion: "", status: "REVIEWED", score: "low" });
    expect(readFilters(new URLSearchParams())).toMatchObject({ tab: "attention", period: "30d" });
  });

  it("drops defaults from the URL so links stay short", () => {
    const params = writeFilter(new URLSearchParams("tab=all&tag=x"), "tab", "attention");
    expect(params.toString()).toBe("tag=x");
    expect(writeFilter(params, "period", "30d").toString()).toBe("tag=x");
    expect(writeFilter(params, "tag", "").toString()).toBe("");
  });

  it("maps the filters onto the backend query", () => {
    const f = readFilters(new URLSearchParams("tab=all&agentId=a1&agentVersion=3&environment=test&period=7d&kind=message&tag=Slow&comment=yes&score=high&status=NEW"));
    expect(allRatingsQuery(f, NOW)).toEqual({
      agentId: "a1",
      agentVersion: 3,
      environment: "test",
      from: "2026-10-17T12:00:00.000Z",
      to: undefined,
      kind: "message",
      tag: "Slow",
      hasComment: true,
      minScore: 0.75,
      maxScore: undefined,
      reviewStatus: "NEW",
    });
    expect(attentionQuery(f)).toEqual({ agentId: "a1", needsAttention: true });
  });

  it("the previous period ends where the current one starts", () => {
    expect(periodRange("7d", NOW, 1)).toEqual({ from: "2026-10-10T12:00:00.000Z", to: "2026-10-17T12:00:00.000Z" });
    expect(periodRange("all", NOW, 1)).toEqual({});
  });

  it("formats shares and deltas", () => {
    expect(percent(0.756)).toBe("76%");
    expect(percent(null)).toBe("—");
    expect(pointDelta(0.8, 0.75)).toBe("+5 pp");
    expect(pointDelta(0.7, 0.75)).toBe("-5 pp");
    expect(pointDelta(null, 0.75)).toBeNull();
    expect(countDelta(12, 9)).toBe("+3");
    expect(countDelta(12, undefined)).toBeNull();
  });

  it("builds query strings without empty values", () => {
    expect(ratingQueryString({ agentId: "a 1", tag: undefined, hasComment: false }, { limit: 20 })).toBe("?agentId=a+1&hasComment=false&limit=20");
    expect(ratingQueryString({})).toBe("");
  });
});
