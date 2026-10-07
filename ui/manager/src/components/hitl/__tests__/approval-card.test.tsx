import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen, within } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ApprovalCard, type ApprovalCardProps } from "@/components/hitl/approval-card";
import { summariseDecision } from "@/lib/hitl-decision";
import type { ToolCallPauseDetails } from "@/lib/api/hitl";

const twoCalls: ToolCallPauseDetails = {
  type: "TOOL_CALL",
  calls: [
    { callId: "c1", toolName: "setupAgent", source: "http", arguments: "{}", argsTruncated: false, gateReason: "g", requestPinned: false },
    { callId: "c2", toolName: "createApiAgent", source: "http", arguments: "{}", argsTruncated: false, gateReason: "g", requestPinned: false },
  ],
  executedUngatedCalls: [],
  outcomeUnknown: [],
};

const confirm = (name: string) =>
  fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name }));

function card(over: Partial<ApprovalCardProps> = {}) {
  return (
    <ApprovalCard
      surface="regular"
      cardKey="k1"
      isResolving={false}
      pauseDetails={twoCalls}
      requireExplicitPerCall
      onDecide={vi.fn()}
      {...over}
    />
  );
}

describe("ApprovalCard", () => {
  it("collapses into a receipt while resolving and keeps the banner mounted but hidden", () => {
    const { rerender } = renderWithProviders(card());
    fireEvent.click(screen.getByTestId("tool-approve-c1"));
    fireEvent.click(screen.getByTestId("tool-reject-c2"));
    fireEvent.click(screen.getByTestId("approve-button"));
    rerender(card({ isResolving: true }));
    expect(screen.getByTestId("approval-decision-summary-call-c1")).toHaveAttribute("data-outcome", "APPROVED");
    expect(screen.getByTestId("approval-decision-summary-call-c2")).toHaveAttribute("data-outcome", "REJECTED");
    expect(screen.getByTestId("approval-card-slot").className).toContain("hidden");
  });

  it("brings the card back with its choices when the resume fails", () => {
    const { rerender } = renderWithProviders(card());
    fireEvent.click(screen.getByTestId("tool-approve-c1"));
    fireEvent.click(screen.getByTestId("tool-reject-c2"));
    fireEvent.click(screen.getByTestId("approve-button"));
    rerender(card({ isResolving: true }));
    rerender(card({ isResolving: false }));
    expect(screen.queryByTestId("approval-decision-summary")).not.toBeInTheDocument();
    expect(screen.getByTestId("tool-approve-c1")).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByTestId("tool-reject-c2")).toHaveAttribute("aria-pressed", "true");
  });

  it("renders a different pause fresh and never shows the old receipt on it", () => {
    const { rerender } = renderWithProviders(card());
    fireEvent.click(screen.getByTestId("tool-approve-c1"));
    fireEvent.click(screen.getByTestId("tool-approve-c2"));
    fireEvent.click(screen.getByTestId("approve-button"));
    rerender(card({ isResolving: true }));
    rerender(card({ isResolving: true, cardKey: "k2" }));
    expect(screen.queryByTestId("approval-decision-summary")).not.toBeInTheDocument();
    expect(screen.getByTestId("tool-approve-c1")).toHaveAttribute("aria-pressed", "false");
  });

  it("never shows a spent receipt for a later resolution (a cancel after a failed approve)", () => {
    const onCancel = vi.fn();
    const { rerender } = renderWithProviders(card({ onCancel }));
    fireEvent.click(screen.getByTestId("tool-approve-c1"));
    fireEvent.click(screen.getByTestId("tool-approve-c2"));
    fireEvent.click(screen.getByTestId("approve-button"));
    rerender(card({ onCancel, isResolving: true }));
    expect(screen.getByTestId("approval-decision-summary")).toBeInTheDocument();
    // The resume failed: card is back.
    rerender(card({ onCancel, isResolving: false }));
    expect(screen.queryByTestId("approval-decision-summary")).not.toBeInTheDocument();
    // Cancel, which makes the surface "resolving" again.
    fireEvent.click(screen.getByTestId("cancel-button"));
    confirm("Cancel conversation");
    expect(onCancel).toHaveBeenCalledTimes(1);
    rerender(card({ onCancel, isResolving: true }));
    expect(screen.queryByTestId("approval-decision-summary")).not.toBeInTheDocument();
  });

  it("does not resurrect an old receipt when the surface goes resolving again without a new submit", () => {
    const { rerender } = renderWithProviders(card());
    fireEvent.click(screen.getByTestId("tool-approve-c1"));
    fireEvent.click(screen.getByTestId("tool-approve-c2"));
    fireEvent.click(screen.getByTestId("approve-button"));
    rerender(card({ isResolving: true }));
    rerender(card({ isResolving: false }));
    rerender(card({ isResolving: true }));
    expect(screen.queryByTestId("approval-decision-summary")).not.toBeInTheDocument();
  });

  it("group PHASE: Approve + confirm, receipt names the phase", () => {
    const onDecide = vi.fn();
    const props = (r: boolean): ApprovalCardProps => ({
      surface: "group",
      cardKey: "g1",
      isResolving: r,
      pausedPhaseName: "Review",
      granularity: "PHASE",
      onDecide,
    });
    const { rerender } = renderWithProviders(<ApprovalCard {...props(false)} />);
    fireEvent.click(screen.getByTestId("approve-button"));
    confirm("Approve");
    expect(onDecide).toHaveBeenCalledWith("APPROVED", undefined, undefined, undefined);
    rerender(<ApprovalCard {...props(true)} />);
    expect(screen.getByTestId("approval-decision-summary-phase")).toHaveTextContent("Review");
  });

  it("group TASK: receipt lists per-task outcomes", () => {
    const props = (r: boolean): ApprovalCardProps => ({
      surface: "group",
      cardKey: "g1",
      isResolving: r,
      granularity: "TASK",
      pendingTaskIds: ["t1", "t2"],
      onDecide: vi.fn(),
    });
    const { rerender } = renderWithProviders(<ApprovalCard {...props(false)} />);
    fireEvent.click(screen.getByTestId("task-reject-t2"));
    fireEvent.click(screen.getByTestId("approve-button"));
    confirm("Approve");
    rerender(<ApprovalCard {...props(true)} />);
    expect(screen.getByTestId("approval-decision-summary-call-t1")).toHaveAttribute("data-outcome", "APPROVED");
    expect(screen.getByTestId("approval-decision-summary-call-t2")).toHaveAttribute("data-outcome", "REJECTED");
  });
});

describe("summariseDecision", () => {
  it("a top-level reject rejects every call", () => {
    const s = summariseDecision({ cardKey: "k", verdict: "REJECTED", pauseDetails: twoCalls });
    expect(s.items.map((i) => i.outcome)).toEqual(["REJECTED", "REJECTED"]);
  });
  it("unlisted calls inherit APPROVED and amended calls are flagged; the note is trimmed", () => {
    const s = summariseDecision({
      cardKey: "k",
      verdict: "APPROVED",
      note: "  ok ",
      pauseDetails: twoCalls,
      toolDecisions: { c2: { verdict: "APPROVED", amendedArguments: "{}" } },
    });
    expect(s.items.map((i) => [i.outcome, i.amended])).toEqual([["APPROVED", false], ["APPROVED", true]]);
    expect(s.note).toBe("ok");
  });
});
