import { describe, expect, it, vi } from "vitest";
import { ApiClientError } from "@/lib/api-client";
import type { ApprovalStatusSummary, HitlDecision } from "@/lib/api/hitl";
import {
  bindDecisionToPause,
  currentGroupPauseOf,
  currentPauseOf,
  describesPause,
  isPauseChanged,
  isSamePause,
  PauseChangedError,
  shownPauseOf,
} from "@/lib/hitl-pause-binding";

const ruleStatus: ApprovalStatusSummary = {
  conversationId: "c",
  state: "AWAITING_HUMAN",
  pausedAt: "2026-07-01T10:00:00.000Z",
  pauseDetails: { type: "RULE", reason: null, actions: [] },
};

const toolStatus = (callIds: string[], pausedAt = "2026-07-01T10:00:00.000Z"): ApprovalStatusSummary => ({
  conversationId: "c",
  state: "AWAITING_HUMAN",
  pausedAt,
  pauseDetails: {
    type: "TOOL_CALL",
    calls: callIds.map((callId) => ({
      callId,
      toolName: "t",
      source: "builtin",
      arguments: "{}",
      argsTruncated: false,
      requestPinned: false,
    })),
    executedUngatedCalls: [],
    outcomeUnknown: [],
  },
});

describe("isSamePause", () => {
  it("matches the pause that was shown", () => {
    expect(isSamePause(shownPauseOf(ruleStatus), currentPauseOf(ruleStatus))).toBe(true);
    expect(isSamePause(shownPauseOf(toolStatus(["a", "b"])), currentPauseOf(toolStatus(["b", "a"])))).toBe(true);
  });

  it("treats a missing pause type as a rule pause, as the backend does", () => {
    expect(
      isSamePause({ pausedAt: ruleStatus.pausedAt, pauseType: null }, currentPauseOf(ruleStatus)),
    ).toBe(true);
  });

  it("compares instants, not spellings", () => {
    expect(
      isSamePause(
        { pausedAt: "2026-07-01T10:00:00Z", pauseType: "RULE" },
        currentPauseOf(ruleStatus),
      ),
    ).toBe(true);
  });

  it("rejects a resolved conversation, another kind of pause, another start or another batch", () => {
    const shown = shownPauseOf(ruleStatus);
    expect(isSamePause(shown, currentPauseOf({ ...ruleStatus, state: "READY" }))).toBe(false);
    expect(isSamePause(shown, currentPauseOf(toolStatus(["a"])))).toBe(false);
    expect(
      isSamePause(shown, currentPauseOf({ ...ruleStatus, pausedAt: "2026-07-01T10:00:01.000Z" })),
    ).toBe(false);
    expect(isSamePause(shownPauseOf(toolStatus(["a"])), currentPauseOf(toolStatus(["a", "b"])))).toBe(false);
    expect(isSamePause(shownPauseOf(toolStatus(["a"])), currentPauseOf(toolStatus(["b"])))).toBe(false);
  });

  it("refuses a shown pause with neither an id nor a start instant — kind alone is not an identity", () => {
    expect(isSamePause({ pauseType: "RULE" }, currentPauseOf(ruleStatus))).toBe(false);
    expect(isSamePause({}, currentPauseOf(ruleStatus))).toBe(false);
  });

  it("lets pause ids decide when both sides have one", () => {
    expect(
      isSamePause({ pauseId: "1" }, { ...currentPauseOf(ruleStatus), pauseId: "2" }),
    ).toBe(false);
    expect(
      isSamePause({ pauseId: "1", pauseType: "TOOL_CALL" }, { ...currentPauseOf(ruleStatus), pauseId: "1" }),
    ).toBe(true);
  });
});

describe("currentGroupPauseOf", () => {
  it("reads the group summary's own state and blank-string fields", () => {
    expect(
      currentGroupPauseOf({ state: "AWAITING_APPROVAL", pausedAt: "x", pauseType: "PHASE", pauseId: "" }),
    ).toEqual({ paused: true, pauseId: undefined, pausedAt: "x", pauseType: "PHASE", callIds: null });
    expect(currentGroupPauseOf({ state: "RUNNING", pausedAt: "" }).paused).toBe(false);
  });
});

describe("bindDecisionToPause", () => {
  const approve: HitlDecision = { verdict: "APPROVED" };

  it("sends the shown pause id without re-reading", async () => {
    const read = vi.fn();
    await expect(bindDecisionToPause(approve, { pauseId: "42" }, read)).resolves.toEqual({
      verdict: "APPROVED",
      pauseId: "42",
    });
    expect(read).not.toHaveBeenCalled();
  });

  it("re-reads without a pause id, and forwards the fresh one when there is one", async () => {
    const shown = shownPauseOf(ruleStatus);
    await expect(
      bindDecisionToPause(approve, shown, async () => currentPauseOf(ruleStatus)),
    ).resolves.toEqual({ verdict: "APPROVED" });
    await expect(
      bindDecisionToPause(approve, shown, async () =>
        currentPauseOf({ ...ruleStatus, pauseId: "7" }),
      ),
    ).resolves.toEqual({ verdict: "APPROVED", pauseId: "7" });
  });

  it("throws PauseChangedError when the pause changed", async () => {
    await expect(
      bindDecisionToPause(approve, shownPauseOf(ruleStatus), async () =>
        currentPauseOf(toolStatus(["a"], "2026-07-01T10:01:00.000Z")),
      ),
    ).rejects.toBeInstanceOf(PauseChangedError);
  });
});

describe("isPauseChanged", () => {
  it("recognises both layers and nothing else", () => {
    expect(isPauseChanged(new PauseChangedError())).toBe(true);
    expect(
      isPauseChanged(
        new ApiClientError(409, "The pending approval changed since this decision was made (pauseId no longer current)"),
      ),
    ).toBe(true);
    expect(isPauseChanged(new ApiClientError(409, "Conversation is not awaiting approval (state READY)"))).toBe(false);
    // The group approve's only wrong-state wording — #839's pause mismatch included.
    expect(
      isPauseChanged(
        new ApiClientError(409, "Group conversation is not awaiting approval — it may have been resolved, cancelled, or timed out"),
      ),
    ).toBe(true);
    expect(isPauseChanged(new ApiClientError(409, "Conversation is not in a resumable state (current state: READY)"))).toBe(false);
    expect(isPauseChanged(new ApiClientError(500, "changed since this decision"))).toBe(false);
    expect(isPauseChanged(new Error("x"))).toBe(false);
  });
});

describe("describesPause", () => {
  const iso = "2026-07-01T10:00:00.000Z";
  const ms = String(Date.parse(iso));
  const paused = (over: Partial<ApprovalStatusSummary> = {}): ApprovalStatusSummary => ({
    conversationId: "c",
    state: "AWAITING_HUMAN",
    pausedAt: iso,
    pauseId: ms,
    pauseDetails: null,
    ...over,
  });

  it("is false for nothing at all", () => {
    expect(describesPause(null, iso)).toBe(false);
    expect(describesPause(undefined, iso)).toBe(false);
  });

  it("is false for the not-yet-persisted shape (READY, blank pauseId, no details)", () => {
    expect(
      describesPause({ conversationId: "c", state: "READY", pausedAt: "", pauseId: "", pauseDetails: null }, iso),
    ).toBe(false);
  });

  it("is false when the state is not AWAITING_HUMAN even if an id is present", () => {
    expect(describesPause(paused({ state: "IN_PROGRESS" }), iso)).toBe(false);
  });

  it("is false for a blank pauseId even when AWAITING_HUMAN", () => {
    expect(describesPause(paused({ pauseId: "" }), iso)).toBe(false);
    expect(describesPause(paused({ pauseId: "   " }), null)).toBe(false);
  });

  it("matches a pauseId against the expected ISO instant", () => {
    expect(describesPause(paused(), iso)).toBe(true);
    // The same instant serialized with sub-millisecond digits / an offset.
    expect(describesPause(paused(), "2026-07-01T12:00:00.000+02:00")).toBe(true);
  });

  it("is false for a pauseId that names a different pause", () => {
    expect(describesPause(paused({ pauseId: String(Date.parse(iso) - 5_000) }), iso)).toBe(false);
  });

  it("is false when the expected instant is unparseable", () => {
    expect(describesPause(paused(), "not-a-date")).toBe(false);
  });

  it("accepts any non-blank pauseId when no pause is expected", () => {
    expect(describesPause(paused(), null)).toBe(true);
    expect(describesPause(paused(), undefined)).toBe(true);
    expect(describesPause(paused(), "  ")).toBe(true);
  });

  describe("a backend that predates pauseId", () => {
    const legacy = (over: Partial<ApprovalStatusSummary> = {}): ApprovalStatusSummary => {
      const { pauseId: _omitted, ...rest } = paused(over);
      void _omitted;
      return rest;
    };

    it("falls back to pausedAt against the expected instant", () => {
      expect(describesPause(legacy(), iso)).toBe(true);
      expect(describesPause(legacy({ pausedAt: "2026-07-01T10:00:09.000Z" }), iso)).toBe(false);
    });

    it("needs a non-empty pausedAt", () => {
      expect(describesPause(legacy({ pausedAt: "" }), iso)).toBe(false);
      expect(describesPause(legacy({ pausedAt: undefined }), null)).toBe(false);
    });

    it("accepts a non-empty pausedAt when no pause is expected", () => {
      expect(describesPause(legacy(), null)).toBe(true);
    });

    it("still requires AWAITING_HUMAN", () => {
      expect(describesPause(legacy({ state: "READY" }), iso)).toBe(false);
    });
  });
});
