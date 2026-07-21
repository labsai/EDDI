import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { PausedCard } from "./PausedCard";
import type { ApprovalStatus } from "@/api/hitl-api";

const NOW = Date.parse("2026-07-21T10:00:00Z");

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(NOW);
});
afterEach(() => {
  vi.useRealTimers();
});

const status = (over: Partial<ApprovalStatus> = {}): ApprovalStatus => ({
  conversationId: "c1",
  state: "AWAITING_HUMAN",
  pausedAt: "2026-07-21T10:00:00Z",
  pauseReason: "manager approval required",
  timeoutPolicy: "AUTO_REJECT",
  approvalTimeout: "PT15M",
  pauseDetails: null,
  ...over,
});

describe("PausedCard", () => {
  it("explains that a reviewer must act", () => {
    render(<PausedCard status={status()} onCancel={vi.fn()} />);

    expect(screen.getByTestId("paused-card")).toHaveTextContent(
      /reviewer must approve/i,
    );
  });

  it("names the gated tool on a TOOL_CALL pause", () => {
    render(
      <PausedCard
        status={status({
          pauseDetails: {
            type: "TOOL_CALL",
            calls: [
              {
                callId: "1",
                toolName: "send_email",
                source: "mcp",
                arguments: "{}",
                argsTruncated: false,
                gateReason: "gated",
              },
            ],
            executedUngatedCalls: [],
            outcomeUnknown: [],
          },
        })}
        onCancel={vi.fn()}
      />,
    );

    expect(screen.getByTestId("paused-card")).toHaveTextContent("send_email");
  });

  it("shows when the pause will auto-decide", () => {
    render(<PausedCard status={status()} onCancel={vi.fn()} />);

    expect(screen.getByTestId("paused-deadline")).toHaveTextContent("15m");
  });

  it("shows no deadline when the pause waits indefinitely", () => {
    render(
      <PausedCard
        status={status({ approvalTimeout: "", timeoutPolicy: "WAIT_INDEFINITELY" })}
        onCancel={vi.fn()}
      />,
    );

    expect(screen.queryByTestId("paused-deadline")).not.toBeInTheDocument();
  });

  it("offers cancelling as the way out of the pause", () => {
    const onCancel = vi.fn();
    render(<PausedCard status={status()} onCancel={onCancel} />);

    fireEvent.click(screen.getByTestId("paused-cancel"));

    expect(onCancel).toHaveBeenCalled();
  });

  it("does not offer approve or reject — deciding is the reviewer's job", () => {
    render(<PausedCard status={status()} onCancel={vi.fn()} />);

    // Read-only by design: an end user approving their own gate defeats the
    // oversight the pause exists to provide. Deciding lives in Manager UI.
    expect(screen.queryByRole("button", { name: /approve/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /reject/i })).toBeNull();
  });

  it("announces itself to assistive technology", () => {
    render(<PausedCard status={status()} onCancel={vi.fn()} />);

    expect(screen.getByTestId("paused-card")).toHaveAttribute("role", "status");
  });
});
