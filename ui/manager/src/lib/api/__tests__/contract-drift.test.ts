import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { isScanComplete, type OrphanReport } from "../orphans";
import {
  getSchedules,
  mayHaveMoreSchedules,
  SCHEDULE_PAGE_MAX,
} from "../schedules";

const report = (over: Partial<OrphanReport> = {}): OrphanReport => ({
  totalOrphans: 0,
  deletedCount: 0,
  orphans: [],
  ...over,
});

/**
 * An orphan report is only actionable when its reference scan finished.
 *
 * Every way of building the reference set incompletely makes MORE resources
 * look unreferenced, never fewer, so a partial scan is a list of false
 * positives rather than a shorter true one. EDDI refuses to purge on one (409
 * `incomplete_scan`), so this is not the last line of defence — it is the
 * difference between understanding why the list is untrustworthy and clicking
 * Purge into an error nobody expected.
 */
describe("isScanComplete", () => {
  it("reads an absent flag as complete", () => {
    // An EDDI predating the field sends neither. Reading absence as incomplete
    // would show the warning forever on every existing deployment.
    expect(isScanComplete(report())).toBe(true);
    expect(isScanComplete(undefined)).toBe(true);
  });

  it("reads an explicit false as incomplete", () => {
    expect(isScanComplete(report({ scanComplete: false }))).toBe(false);
  });

  it("reads an explicit true as complete", () => {
    expect(isScanComplete(report({ scanComplete: true }))).toBe(true);
  });
});

/**
 * Schedule listing became paged in EDDI 6.4: `limit` defaults to 500 and caps
 * at 1000. Sending no parameters, as this used to, meant a deployment holding
 * more than 500 lost the surplus silently — and those rows could not be
 * disabled or deleted through the list, because they were never in it.
 */
describe("getSchedules", () => {
  /** The query string one listing put on the wire. */
  async function capture(run: () => Promise<unknown>): Promise<URLSearchParams> {
    let search = new URLSearchParams();
    server.use(
      http.get(`${window.location.origin}/schedulestore/schedules`, ({ request }) => {
        search = new URL(request.url).searchParams;
        return HttpResponse.json([]);
      }),
    );
    await run();
    return search;
  }

  it("asks for the largest page EDDI will serve", async () => {
    const search = await capture(() => getSchedules());

    expect(search.get("limit")).toBe(String(SCHEDULE_PAGE_MAX));
    expect(search.get("offset")).toBe("0");
  });

  it("keeps the agent filter alongside the paging parameters", async () => {
    const search = await capture(() => getSchedules("agent-1"));

    expect(search.get("agentId")).toBe("agent-1");
    expect(search.get("limit")).toBe(String(SCHEDULE_PAGE_MAX));
  });

  it("passes an explicit offset through", async () => {
    const search = await capture(() => getSchedules(undefined, 50, 100));

    expect(search.get("limit")).toBe("50");
    expect(search.get("offset")).toBe("100");
  });
});

describe("mayHaveMoreSchedules", () => {
  it("treats a full page as possibly truncated", () => {
    // EDDI's own rule: a response holding exactly `limit` entries may be
    // truncated, and asking for the next page is the only way to find out.
    expect(mayHaveMoreSchedules(new Array(10).fill({}), 10)).toBe(true);
  });

  it("treats a short page as the end", () => {
    expect(mayHaveMoreSchedules(new Array(9).fill({}), 10)).toBe(false);
    expect(mayHaveMoreSchedules([], 10)).toBe(false);
  });
});
