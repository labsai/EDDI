/* ──────────────────────────────────────────────
   HITL — what the widget says about a pause
   (Status, deadlines, cancel and poll cadence are tested in @eddi/core.)
   ────────────────────────────────────────────── */

import { describe, it, expect } from "vitest";
import type { ApprovalStatus } from "@eddi/core";
import { pauseHeadline } from "./hitl-api";

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
