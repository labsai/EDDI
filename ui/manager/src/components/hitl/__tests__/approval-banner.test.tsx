import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ApprovalBanner } from "@/components/hitl/approval-banner";

describe("ApprovalBanner", () => {
  it("submits an APPROVED decision (no note, no task approvals) on the regular surface", () => {
    const onDecide = vi.fn();
    renderWithProviders(<ApprovalBanner surface="regular" onDecide={onDecide} />);

    fireEvent.click(screen.getByTestId("approve-button"));
    expect(onDecide).toHaveBeenCalledWith("APPROVED", undefined, undefined);
  });

  it("submits a REJECTED decision", () => {
    const onDecide = vi.fn();
    renderWithProviders(<ApprovalBanner surface="regular" onDecide={onDecide} />);

    fireEvent.click(screen.getByTestId("reject-button"));
    expect(onDecide).toHaveBeenCalledWith("REJECTED", undefined, undefined);
  });

  it("passes the trimmed note when one is entered", () => {
    const onDecide = vi.fn();
    renderWithProviders(<ApprovalBanner surface="regular" onDecide={onDecide} />);

    fireEvent.click(screen.getByTestId("toggle-note"));
    fireEvent.change(screen.getByTestId("approval-note"), { target: { value: "  looks good  " } });
    fireEvent.click(screen.getByTestId("approve-button"));

    expect(onDecide).toHaveBeenCalledWith("APPROVED", "looks good", undefined);
  });

  it("invokes onCancel when the cancel button is clicked", () => {
    const onCancel = vi.fn();
    renderWithProviders(<ApprovalBanner surface="regular" onDecide={vi.fn()} onCancel={onCancel} />);

    fireEvent.click(screen.getByTestId("cancel-button"));
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

    expect(onDecide).toHaveBeenCalledWith("REJECTED", undefined, {
      t1: "REJECTED",
      t2: "REJECTED",
    });
  });
});
