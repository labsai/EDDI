import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen, act, within } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ApprovalBanner } from "@/components/hitl/approval-banner";
import type { ToolCallPauseDetails } from "@/lib/api/hitl";

/** A two-call TOOL_CALL pause fixture. */
function toolPause(overrides: Partial<ToolCallPauseDetails> = {}): ToolCallPauseDetails {
  return {
    type: "TOOL_CALL",
    calls: [
      { callId: "c1", toolName: "sendEmail", source: "mcp", arguments: '{"to":"[REDACTED]"}', argsTruncated: false, gateReason: "mcp:*" },
      { callId: "c2", toolName: "transfer_funds", source: "builtin", arguments: '{"amount":100}', argsTruncated: false, gateReason: "transfer_*" },
    ],
    executedUngatedCalls: [],
    outcomeUnknown: [],
    ...overrides,
  };
}

/** Click a labelled button inside the confirmation dialog (Approve/Reject/Cancel).
 *  Every Approve/Reject/Cancel is gated behind this confirm step, so the tests
 *  drive it explicitly and also verify nothing fired on the first click. */
function confirmInDialog(name: string | RegExp) {
  const dialog = screen.getByRole("dialog");
  fireEvent.click(within(dialog).getByRole("button", { name }));
}

describe("ApprovalBanner", () => {
  it("submits an APPROVED decision (no note, no task approvals) on the regular surface", () => {
    const onDecide = vi.fn();
    renderWithProviders(<ApprovalBanner surface="regular" onDecide={onDecide} />);

    fireEvent.click(screen.getByTestId("approve-button"));
    // Nothing fires on the first click — a confirmation is shown instead.
    expect(onDecide).not.toHaveBeenCalled();
    confirmInDialog("Approve");
    expect(onDecide).toHaveBeenCalledWith("APPROVED", undefined, undefined);
  });

  it("submits a REJECTED decision", () => {
    const onDecide = vi.fn();
    renderWithProviders(<ApprovalBanner surface="regular" onDecide={onDecide} />);

    fireEvent.click(screen.getByTestId("reject-button"));
    expect(onDecide).not.toHaveBeenCalled();
    confirmInDialog("Reject");
    expect(onDecide).toHaveBeenCalledWith("REJECTED", undefined, undefined);
  });

  it("passes the trimmed note when one is entered", () => {
    const onDecide = vi.fn();
    renderWithProviders(<ApprovalBanner surface="regular" onDecide={onDecide} />);

    fireEvent.click(screen.getByTestId("toggle-note"));
    fireEvent.change(screen.getByTestId("approval-note"), { target: { value: "  looks good  " } });
    fireEvent.click(screen.getByTestId("approve-button"));
    confirmInDialog("Approve");

    expect(onDecide).toHaveBeenCalledWith("APPROVED", "looks good", undefined);
  });

  it("invokes onCancel when the cancel button is clicked and confirmed", () => {
    const onCancel = vi.fn();
    renderWithProviders(<ApprovalBanner surface="regular" onDecide={vi.fn()} onCancel={onCancel} />);

    fireEvent.click(screen.getByTestId("cancel-button"));
    // Cancel aborts in-flight work — must not fire on the first click.
    expect(onCancel).not.toHaveBeenCalled();
    confirmInDialog("Cancel conversation");
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it("does not render the per-task UI without TASK granularity", () => {
    renderWithProviders(
      <ApprovalBanner surface="group" pendingTaskIds={["t1"]} onDecide={vi.fn()} />,
    );
    expect(screen.queryByTestId("task-approve-t1")).not.toBeInTheDocument();
  });

  it("sends an explicit verdict for every pending task, defaulting untoggled ones (TASK granularity)", () => {
    const onDecide = vi.fn();
    renderWithProviders(
      <ApprovalBanner
        surface="group"
        granularity="TASK"
        pendingTaskIds={["t1", "t2"]}
        onDecide={onDecide}
      />,
    );

    // Reject t2 individually, leave t1 untoggled, then approve overall.
    fireEvent.click(screen.getByTestId("task-reject-t2"));
    fireEvent.click(screen.getByTestId("approve-button"));
    confirmInDialog("Approve");

    expect(onDecide).toHaveBeenCalledWith("APPROVED", undefined, {
      t1: "APPROVED",
      t2: "REJECTED",
    });
  });

  it("Reject All marks every pending task REJECTED", () => {
    const onDecide = vi.fn();
    renderWithProviders(
      <ApprovalBanner
        surface="group"
        granularity="TASK"
        pendingTaskIds={["t1", "t2"]}
        onDecide={onDecide}
      />,
    );

    fireEvent.click(screen.getByTestId("reject-all-tasks"));
    fireEvent.click(screen.getByTestId("reject-button"));
    confirmInDialog("Reject");

    expect(onDecide).toHaveBeenCalledWith("REJECTED", undefined, {
      t1: "REJECTED",
      t2: "REJECTED",
    });
  });

  it("shows a live countdown that ticks down and flips to Overdue", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-01-01T00:00:00.000Z"));
    try {
      renderWithProviders(
        <ApprovalBanner
          surface="group"
          pausedAt="2026-01-01T00:00:00.000Z"
          approvalTimeout="PT1M"
          timeoutPolicy="AUTO_REJECT"
          onDecide={vi.fn()}
        />,
      );

      // Before the deadline: a "Remaining: …" chip is shown, not "Overdue".
      expect(screen.getByText(/Remaining:/)).toBeInTheDocument();
      expect(screen.queryByText("Overdue")).not.toBeInTheDocument();

      // Advance past the 1-minute deadline — the 1s interval flips it to Overdue.
      act(() => {
        vi.advanceTimersByTime(65_000);
      });
      expect(screen.getByText("Overdue")).toBeInTheDocument();
      expect(screen.queryByText(/Remaining:/)).not.toBeInTheDocument();
    } finally {
      vi.useRealTimers();
    }
  });

  it("disables Approve (not Reject/Cancel) while pause details are still loading", () => {
    const onDecide = vi.fn();
    renderWithProviders(
      <ApprovalBanner surface="regular" pauseDetailsPending onDecide={onDecide} onCancel={vi.fn()} />,
    );
    expect(screen.getByTestId("approve-button")).toBeDisabled();
    expect(screen.getByTestId("reject-button")).not.toBeDisabled();
    expect(screen.getByTestId("cancel-button")).not.toBeDisabled();
    expect(screen.getByTestId("approval-details-pending")).toBeInTheDocument();
    // Reject stays safe with details unknown (it rejects everything) — still gated by confirm.
    fireEvent.click(screen.getByTestId("reject-button"));
    confirmInDialog("Reject");
    expect(onDecide).toHaveBeenCalledWith("REJECTED", undefined, undefined);
  });

  // ── Confirmation gate (regression for one-click irreversible actions) ──
  describe("confirmation gate", () => {
    it("Approve on a RULE pause confirms before resuming (not one-click)", () => {
      const onDecide = vi.fn();
      renderWithProviders(<ApprovalBanner surface="regular" onDecide={onDecide} />);

      fireEvent.click(screen.getByTestId("approve-button"));
      expect(onDecide).not.toHaveBeenCalled();
      // A confirmation dialog is presented.
      expect(screen.getByRole("dialog")).toBeInTheDocument();
      expect(screen.getByText("Approve and resume this conversation?")).toBeInTheDocument();

      confirmInDialog("Approve");
      expect(onDecide).toHaveBeenCalledTimes(1);
    });

    it("Reject confirms and does not fire until confirmed", () => {
      const onDecide = vi.fn();
      renderWithProviders(<ApprovalBanner surface="regular" onDecide={onDecide} />);

      fireEvent.click(screen.getByTestId("reject-button"));
      expect(onDecide).not.toHaveBeenCalled();
      expect(screen.getByText("Reject this request? The conversation will not proceed.")).toBeInTheDocument();

      confirmInDialog("Reject");
      expect(onDecide).toHaveBeenCalledTimes(1);
    });

    it("dismissing the confirmation does not fire the action", () => {
      const onDecide = vi.fn();
      renderWithProviders(<ApprovalBanner surface="regular" onDecide={onDecide} />);

      fireEvent.click(screen.getByTestId("approve-button"));
      confirmInDialog("Go back");
      expect(onDecide).not.toHaveBeenCalled();
    });

    it("Approve on a TOOL_CALL pause confirms and lists the gated tool names", () => {
      const onDecide = vi.fn();
      renderWithProviders(
        <ApprovalBanner surface="regular" pauseDetails={toolPause()} onDecide={onDecide} />,
      );

      fireEvent.click(screen.getByTestId("approve-button"));
      // Real tools have NOT run yet.
      expect(onDecide).not.toHaveBeenCalled();
      const dialog = screen.getByRole("dialog");
      // The confirmation summarizes which tool(s) will execute.
      expect(within(dialog).getByText(/sendEmail/)).toBeInTheDocument();
      expect(within(dialog).getByText(/transfer_funds/)).toBeInTheDocument();
      expect(within(dialog).getByText(/cannot be undone/i)).toBeInTheDocument();

      fireEvent.click(within(dialog).getByRole("button", { name: "Approve" }));
      expect(onDecide).toHaveBeenCalledWith("APPROVED", undefined, undefined, undefined);
    });
  });

  describe("TOOL_CALL pause", () => {
    it("renders each gated call's tool name, source, gate reason and redacted arguments", () => {
      renderWithProviders(
        <ApprovalBanner surface="regular" pauseDetails={toolPause()} onDecide={vi.fn()} />,
      );
      expect(screen.getByTestId("approval-banner")).toHaveAttribute("data-pause-type", "TOOL_CALL");
      expect(screen.getByText("sendEmail")).toBeInTheDocument();
      expect(screen.getByText("transfer_funds")).toBeInTheDocument();
      expect(screen.getByText("mcp:*")).toBeInTheDocument();
      expect(screen.getByTestId("tool-args-c1")).toHaveTextContent('"to":"[REDACTED]"');
    });

    it("approving with no per-call changes sends no toolDecisions (calls inherit APPROVED)", () => {
      const onDecide = vi.fn();
      renderWithProviders(
        <ApprovalBanner surface="regular" pauseDetails={toolPause()} onDecide={onDecide} />,
      );
      fireEvent.click(screen.getByTestId("approve-button"));
      confirmInDialog("Approve");
      expect(onDecide).toHaveBeenCalledWith("APPROVED", undefined, undefined, undefined);
    });

    it("rejecting one call then approving the batch sends a per-call REJECTED override", () => {
      const onDecide = vi.fn();
      renderWithProviders(
        <ApprovalBanner surface="regular" pauseDetails={toolPause()} onDecide={onDecide} />,
      );
      fireEvent.click(screen.getByTestId("tool-reject-c2"));
      fireEvent.click(screen.getByTestId("approve-button"));
      confirmInDialog("Approve");
      expect(onDecide).toHaveBeenCalledWith("APPROVED", undefined, undefined, {
        c2: { verdict: "REJECTED" },
      });
    });

    it("rejecting the batch is all-or-nothing (no contradictory per-call approvals sent)", () => {
      const onDecide = vi.fn();
      renderWithProviders(
        <ApprovalBanner surface="regular" pauseDetails={toolPause()} onDecide={onDecide} />,
      );
      // Even if a call was individually toggled APPROVED, Reject rejects the batch.
      fireEvent.click(screen.getByTestId("tool-approve-c1"));
      fireEvent.click(screen.getByTestId("reject-button"));
      confirmInDialog("Reject");
      expect(onDecide).toHaveBeenCalledWith("REJECTED", undefined, undefined, undefined);
    });

    it("sends amendedArguments for an approved call when valid JSON is entered", () => {
      const onDecide = vi.fn();
      renderWithProviders(
        <ApprovalBanner surface="regular" pauseDetails={toolPause()} onDecide={onDecide} />,
      );
      fireEvent.click(screen.getByTestId("tool-amend-toggle-c1"));
      fireEvent.change(screen.getByTestId("tool-amend-c1"), {
        target: { value: '{"to":"ops@acme.com"}' },
      });
      fireEvent.click(screen.getByTestId("approve-button"));
      confirmInDialog("Approve");
      expect(onDecide).toHaveBeenCalledWith("APPROVED", undefined, undefined, {
        c1: { verdict: "APPROVED", amendedArguments: '{"to":"ops@acme.com"}' },
      });
    });

    it("blocks submission and shows an error when amended arguments are not a JSON object", () => {
      const onDecide = vi.fn();
      renderWithProviders(
        <ApprovalBanner surface="regular" pauseDetails={toolPause()} onDecide={onDecide} />,
      );
      fireEvent.click(screen.getByTestId("tool-amend-toggle-c1"));
      fireEvent.change(screen.getByTestId("tool-amend-c1"), { target: { value: "not json" } });
      fireEvent.click(screen.getByTestId("approve-button"));
      confirmInDialog("Approve");
      expect(onDecide).not.toHaveBeenCalled();
      expect(screen.getByTestId("approval-submit-error")).toBeInTheDocument();
    });

    it("does not offer amendment for a call whose arguments were truncated", () => {
      const details = toolPause({
        calls: [
          { callId: "c1", toolName: "bulkUpdate", source: "http", arguments: "{…}", argsTruncated: true, gateReason: "http:*" },
        ],
      });
      renderWithProviders(
        <ApprovalBanner surface="regular" pauseDetails={details} onDecide={vi.fn()} />,
      );
      expect(screen.queryByTestId("tool-amend-toggle-c1")).not.toBeInTheDocument();
      expect(screen.getByText("arguments truncated")).toBeInTheDocument();
    });

    it("surfaces executedUngatedCalls and the outcome-unknown warning", () => {
      const details = toolPause({
        calls: [
          { callId: "c1", toolName: "sendEmail", source: "mcp", arguments: "{}", argsTruncated: false, gateReason: "mcp:*" },
        ],
        executedUngatedCalls: ["getCurrentDateTime"],
        outcomeUnknown: ["c1"],
      });
      renderWithProviders(
        <ApprovalBanner surface="regular" pauseDetails={details} onDecide={vi.fn()} />,
      );
      expect(screen.getByTestId("executed-ungated")).toHaveTextContent("getCurrentDateTime");
      expect(screen.getByTestId("outcome-unknown")).toBeInTheDocument();
    });

    it("does not render the tool UI for a RULE pause", () => {
      renderWithProviders(
        <ApprovalBanner
          surface="regular"
          pauseDetails={{ type: "RULE", reason: "Deletion needs sign-off", actions: ["PAUSE_CONVERSATION"] }}
          onDecide={vi.fn()}
        />,
      );
      expect(screen.queryByTestId("tool-call-approvals")).not.toBeInTheDocument();
      expect(screen.getByTestId("approval-banner")).toHaveAttribute("data-pause-type", "RULE");
    });

    it("always shows the redaction caveat, regardless of requireExplicitPerCall", () => {
      renderWithProviders(
        <ApprovalBanner surface="regular" pauseDetails={toolPause()} onDecide={vi.fn()} />,
      );
      expect(screen.getByTestId("redaction-caveat")).toHaveTextContent("[REDACTED]");
    });

    it("renders renderCallExtra content for each call", () => {
      renderWithProviders(
        <ApprovalBanner
          surface="regular"
          pauseDetails={toolPause()}
          onDecide={vi.fn()}
          renderCallExtra={(call) => <span data-testid={`extra-${call.callId}`}>POST /agentstore/agents</span>}
        />,
      );
      expect(screen.getByTestId("extra-c1")).toBeInTheDocument();
      expect(screen.getByTestId("extra-c2")).toBeInTheDocument();
    });
  });

  describe("requireExplicitPerCall", () => {
    it("disables Approve until every call has an explicit verdict", () => {
      renderWithProviders(
        <ApprovalBanner surface="regular" pauseDetails={toolPause()} onDecide={vi.fn()} requireExplicitPerCall />,
      );
      expect(screen.getByTestId("approve-button")).toBeDisabled();
      expect(screen.getByTestId("explicit-review-missing")).toBeInTheDocument();
    });

    it("enables Approve once EVERY call has been explicitly toggled", () => {
      const onDecide = vi.fn();
      renderWithProviders(
        <ApprovalBanner surface="regular" pauseDetails={toolPause()} onDecide={onDecide} requireExplicitPerCall />,
      );
      fireEvent.click(screen.getByTestId("tool-approve-c1"));
      // Still missing c2 — must stay disabled.
      expect(screen.getByTestId("approve-button")).toBeDisabled();

      fireEvent.click(screen.getByTestId("tool-approve-c2"));
      expect(screen.getByTestId("approve-button")).not.toBeDisabled();
      expect(screen.queryByTestId("explicit-review-missing")).not.toBeInTheDocument();

      fireEvent.click(screen.getByTestId("approve-button"));
      confirmInDialog("Approve");
      expect(onDecide).toHaveBeenCalledWith("APPROVED", undefined, undefined, {
        c1: { verdict: "APPROVED" },
        c2: { verdict: "APPROVED" },
      });
    });

    it("un-reviewing a call (toggling it back off) re-disables Approve", () => {
      renderWithProviders(
        <ApprovalBanner surface="regular" pauseDetails={toolPause()} onDecide={vi.fn()} requireExplicitPerCall />,
      );
      fireEvent.click(screen.getByTestId("tool-approve-c1"));
      fireEvent.click(screen.getByTestId("tool-approve-c2"));
      expect(screen.getByTestId("approve-button")).not.toBeDisabled();

      // Toggling an already-selected verdict off is the existing "un-toggle"
      // behavior (see the onToggle handler) — Approve must track it live.
      fireEvent.click(screen.getByTestId("tool-approve-c2"));
      expect(screen.getByTestId("approve-button")).toBeDisabled();
    });

    it("Reject stays available regardless — rejecting the batch needs no per-call review", () => {
      const onDecide = vi.fn();
      renderWithProviders(
        <ApprovalBanner surface="regular" pauseDetails={toolPause()} onDecide={onDecide} requireExplicitPerCall />,
      );
      expect(screen.getByTestId("reject-button")).not.toBeDisabled();
      fireEvent.click(screen.getByTestId("reject-button"));
      confirmInDialog("Reject");
      expect(onDecide).toHaveBeenCalledWith("REJECTED", undefined, undefined, undefined);
    });

    it("does not affect a RULE pause, which has no per-call state to require", () => {
      const onDecide = vi.fn();
      renderWithProviders(
        <ApprovalBanner
          surface="regular"
          pauseDetails={{ type: "RULE", reason: "x", actions: [] }}
          onDecide={onDecide}
          requireExplicitPerCall
        />,
      );
      expect(screen.getByTestId("approve-button")).not.toBeDisabled();
    });

    it("defaults to off — existing callers (conversation-detail, discussion-transcript) keep sweep-approve", () => {
      renderWithProviders(
        <ApprovalBanner surface="regular" pauseDetails={toolPause()} onDecide={vi.fn()} />,
      );
      expect(screen.getByTestId("approve-button")).not.toBeDisabled();
      expect(screen.queryByTestId("explicit-review-missing")).not.toBeInTheDocument();
    });
  });
});
