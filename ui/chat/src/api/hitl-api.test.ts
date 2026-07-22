/* ──────────────────────────────────────────────
   HITL — approval status, deadlines, cancel
   ────────────────────────────────────────────── */

import { describe, it, expect, afterEach } from "vitest";
import {
  parseIsoDuration,
  approvalDeadline,
  gatedToolNames,
  pauseHeadline,
  getApprovalStatus,
  nextPollDelay,
  isSettledState,
  cancelConversation,
  type ApprovalStatus,
} from "./hitl-api";
import { setBaseUrl } from "./http";
import { captureFetch, mockFetchResponse } from "@/test-utils/sse";

const originalFetch = globalThis.fetch;
afterEach(() => {
  globalThis.fetch = originalFetch;
});

const paused = (over: Partial<ApprovalStatus> = {}): ApprovalStatus => ({
  conversationId: "c1",
  state: "AWAITING_HUMAN",
  pausedAt: "2026-07-21T10:00:00Z",
  pauseReason: "manager approval required",
  timeoutPolicy: "AUTO_REJECT",
  approvalTimeout: "PT15M",
  pauseDetails: null,
  ...over,
});

describe("parseIsoDuration", () => {
  it.each([
    ["PT15M", 15 * 60_000],
    ["PT1H", 60 * 60_000],
    ["PT30S", 30_000],
    ["PT1H30M", 90 * 60_000],
    ["PT2H15M30S", (2 * 3600 + 15 * 60 + 30) * 1000],
    // The day component was matched and then discarded, so a multi-day
    // approval window showed a deadline that was days early.
    ["P1DT1H", (24 + 1) * 60 * 60_000],
    ["P2DT3H4M5S", (2 * 86400 + 3 * 3600 + 4 * 60 + 5) * 1000],
    // Duration.parse accepts a bare day span; requiring `T` reported no
    // deadline at all for one.
    ["P2D", 2 * 24 * 60 * 60_000],
    ["P1D", 24 * 60 * 60_000],
  ])("parses %s", (input, expected) => {
    expect(parseIsoDuration(input)).toBe(expected);
  });

  it("returns null for an empty or unparseable value", () => {
    expect(parseIsoDuration("")).toBeNull();
    expect(parseIsoDuration("nonsense")).toBeNull();
    // A bare "P" carries no components — not a zero-length duration.
    expect(parseIsoDuration("P")).toBeNull();
    expect(parseIsoDuration("PT")).toBeNull();
  });
});

describe("approvalDeadline", () => {
  it("adds the timeout to the pause instant", () => {
    // pausedAt on this endpoint is Instant.toString() — ISO-8601, NOT the
    // epoch-millis form that detail=full snapshot serialization produces.
    const deadline = approvalDeadline(paused());

    expect(deadline).toBe(Date.parse("2026-07-21T10:15:00Z"));
  });

  it("also accepts an epoch-millis pausedAt", () => {
    const deadline = approvalDeadline(
      paused({ pausedAt: String(Date.parse("2026-07-21T10:00:00Z")) }),
    );

    expect(deadline).toBe(Date.parse("2026-07-21T10:15:00Z"));
  });

  it("is null when the pause never auto-decides", () => {
    expect(approvalDeadline(paused({ approvalTimeout: "" }))).toBeNull();
  });

  it("is null when there is no pause instant", () => {
    expect(approvalDeadline(paused({ pausedAt: "" }))).toBeNull();
  });
});

describe("gatedToolNames", () => {
  it("lists the tools awaiting approval on a TOOL_CALL pause", () => {
    const names = gatedToolNames(
      paused({
        pauseDetails: {
          type: "TOOL_CALL",
          calls: [
            { callId: "1", toolName: "send_email", source: "mcp", arguments: "{}", argsTruncated: false, gateReason: "gated" },
            { callId: "2", toolName: "delete_record", source: "http", arguments: "{}", argsTruncated: false, gateReason: "gated" },
          ],
        } as never,
      }),
    );

    expect(names).toEqual(["send_email", "delete_record"]);
  });

  it("is empty for a RULE pause", () => {
    expect(
      gatedToolNames(
        paused({ pauseDetails: { type: "RULE", reason: "x", actions: [] } }),
      ),
    ).toEqual([]);
  });

  it("is empty when there are no pause details", () => {
    expect(gatedToolNames(paused({ pauseDetails: null }))).toEqual([]);
  });
});

describe("pauseHeadline", () => {
  it("names the gated tools on a TOOL_CALL pause", () => {
    const text = pauseHeadline(
      paused({
        pauseDetails: {
          type: "TOOL_CALL",
          calls: [
            { callId: "1", toolName: "send_email", source: "mcp", arguments: "{}", argsTruncated: false, gateReason: "g" },
          ],
        } as never,
      }),
    );

    expect(text).toContain("send_email");
  });

  it("falls back to the pause reason on a RULE pause", () => {
    expect(pauseHeadline(paused({ pauseDetails: { type: "RULE", reason: "manager approval required", actions: [] } })))
      .toContain("manager approval required");
  });

  it("still says something useful with no reason at all", () => {
    expect(pauseHeadline(paused({ pauseReason: "", pauseDetails: null }))).toBeTruthy();
  });
});

describe("getApprovalStatus", () => {
  it("requests the summary view", async () => {
    setBaseUrl("");
    const { calls } = captureFetch(200, JSON.stringify(paused()));

    await getApprovalStatus("c1");

    expect(calls[0].url).toContain("/agents/c1/approval-status");
    expect(calls[0].url).toContain("detail=summary");
  });
});

describe("cancelConversation", () => {
  it("resolves on a 200 with an empty body", async () => {
    setBaseUrl("");
    mockFetchResponse(200, "");

    await expect(cancelConversation("c1")).resolves.toBeUndefined();
  });

  it("reports nothing-to-cancel as a 409 rather than a generic failure", async () => {
    setBaseUrl("");
    mockFetchResponse(409, "Nothing to cancel");

    await expect(cancelConversation("c1")).rejects.toMatchObject({ status: 409 });
  });
});

describe("nextPollDelay", () => {
  it("polls promptly at first so a quick approval feels immediate", () => {
    expect(nextPollDelay(0)).toBe(3_000);
  });

  it("backs off as the wait drags on", () => {
    expect(nextPollDelay(1)).toBeGreaterThan(nextPollDelay(0));
    expect(nextPollDelay(5)).toBeGreaterThan(nextPollDelay(1));
  });

  it("caps the delay so a resolved pause is never missed for long", () => {
    expect(nextPollDelay(100)).toBe(30_000);
  });

  it("never returns a non-positive delay", () => {
    expect(nextPollDelay(-1)).toBeGreaterThan(0);
  });
});

describe("isSettledState", () => {
  it.each(["READY", "ENDED", "ERROR", "EXECUTION_INTERRUPTED"] as const)(
    "treats %s as settled",
    (s) => {
      expect(isSettledState(s)).toBe(true);
    },
  );

  it("does NOT treat IN_PROGRESS as settled", () => {
    // resume() CASes AWAITING_HUMAN -> IN_PROGRESS *before* running the
    // resumed turn, so IN_PROGRESS means "the approved turn is still being
    // produced". Declaring the pause over here loses the answer.
    expect(isSettledState("IN_PROGRESS")).toBe(false);
  });

  it("does NOT treat AWAITING_HUMAN as settled", () => {
    expect(isSettledState("AWAITING_HUMAN")).toBe(false);
  });

  it("treats an unknown/absent state as unsettled", () => {
    expect(isSettledState(null)).toBe(false);
  });
});
