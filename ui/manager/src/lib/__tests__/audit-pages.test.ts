import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import {
  getWholeAuditTrail,
  mergeById,
  refreshAuditTrail,
} from "@/lib/audit-pages";
import type { AuditEntry } from "@/lib/api/audit";

const row = (id: string, cost = 0.01) =>
  ({ id, stepIndex: 0, cost }) as unknown as AuditEntry;

describe("audit trail paging", () => {
  // The ledger sorts by timestamp only; entries sharing a millisecond can come
  // back on both sides of a page boundary.
  it("does not count an entry twice when it appears on two pages", async () => {
    server.use(
      http.get("*/auditstore/:conversationId", ({ request }) => {
        const skip = Number(new URL(request.url).searchParams.get("skip"));
        return HttpResponse.json(
          skip === 0 ? [row("a"), row("b")] : [row("b"), row("c")].slice(0, 1)
        );
      })
    );
    const result = await getWholeAuditTrail("conv", 2);
    expect(result.entries.map((e) => e.id)).toEqual(["a", "b"]);
    expect(mergeById([row("a")], [row("a"), row("b")]).map((e) => e.id)).toEqual(["a", "b"]);
  });

  it("refreshes from the newest page only when it overlaps what is loaded", async () => {
    const skips: number[] = [];
    server.use(
      http.get("*/auditstore/:conversationId", ({ request }) => {
        skips.push(Number(new URL(request.url).searchParams.get("skip")));
        return HttpResponse.json([row("new"), row("old1")]);
      })
    );
    const result = await refreshAuditTrail(
      "conv",
      { entries: [row("old1"), row("old2")], complete: true },
      2
    );
    expect(skips).toEqual([0]);
    expect(result.entries.map((e) => e.id).sort()).toEqual(["new", "old1", "old2"]);
  });

  it("re-walks the whole trail when a full newest page shares nothing with it", async () => {
    const skips: number[] = [];
    server.use(
      http.get("*/auditstore/:conversationId", ({ request }) => {
        const skip = Number(new URL(request.url).searchParams.get("skip"));
        skips.push(skip);
        return HttpResponse.json(skip === 0 ? [row("n1"), row("n2")] : [row("n3")]);
      })
    );
    const result = await refreshAuditTrail(
      "conv",
      { entries: [row("old")], complete: true },
      2
    );
    expect(skips).toEqual([0, 0, 2]);
    expect(result.entries.map((e) => e.id)).toEqual(["n1", "n2", "n3"]);
  });
});
