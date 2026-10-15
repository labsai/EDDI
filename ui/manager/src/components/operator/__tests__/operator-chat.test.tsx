import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { OperatorChat, type OperatorChatProps } from "../operator-chat";
import type { ToolCallPauseDetails } from "@/lib/api/hitl";

beforeEach(() => {
  // jsdom has no scrollIntoView; the chat auto-scroll effect calls it.
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
});

const baseProps: OperatorChatProps = {
  messages: [],
  events: [],
  tracesByMessageId: {},
  isStreaming: false,
  error: null,
  onSend: vi.fn(),
  onStop: vi.fn(),
  onReset: vi.fn(),
  isPaused: true,
  pauseReason: "Creating a new agent — review the whole config",
  isResolvingPause: false,
  resolveError: null,
  onDecide: vi.fn(),
};

function renderChat(overrides: Partial<OperatorChatProps> = {}) {
  return render(
    <MemoryRouter>
      <OperatorChat {...baseProps} {...overrides} />
    </MemoryRouter>,
  );
}

describe("OperatorChat — the approved step is visibly running", () => {
  // The resumed turn is polled, not streamed, and routinely takes 30-60s when it
  // creates an agent. Without this row the transcript sits unchanged and
  // approving reads as "nothing happened" — reported that way twice from live
  // use.
  it("shows a running indicator while the decision is being resolved", () => {
    renderChat({ isResolvingPause: true });
    const status = screen.getByTestId("operator-chat-resolving");
    expect(status).toHaveTextContent(/running the approved step/i);
    expect(status).toHaveAttribute("role", "status");
  });

  it("shows nothing once the turn has settled", () => {
    renderChat({ isResolvingPause: false });
    expect(screen.queryByTestId("operator-chat-resolving")).not.toBeInTheDocument();
  });

  it("shows it on the compact surface too — the drawer waits just as long", () => {
    renderChat({ isResolvingPause: true, pauseSurface: "compact" });
    expect(screen.getByTestId("operator-chat-resolving")).toBeInTheDocument();
  });
});

const twoCalls: ToolCallPauseDetails = {
  type: "TOOL_CALL",
  calls: [
    { callId: "c1", toolName: "setupAgent", source: "http", arguments: "{}", argsTruncated: false, gateReason: "g", requestPinned: false },
    { callId: "c2", toolName: "createApiAgent", source: "http", arguments: "{}", argsTruncated: false, gateReason: "g", requestPinned: false },
  ],
  executedUngatedCalls: [],
  outcomeUnknown: [],
};

function confirm(name: string) {
  fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name }));
}

describe("OperatorChat — the card collapses into a summary once decided", () => {
  const props = (over: Partial<OperatorChatProps> = {}): OperatorChatProps => ({
    ...baseProps,
    pauseDetails: twoCalls,
    pauseKey: "2026-08-03T10:00:00Z",
    ...over,
  });
  const view = (p: OperatorChatProps) => (
    <MemoryRouter>
      <OperatorChat {...p} />
    </MemoryRouter>
  );

  it("shows per-call outcomes and hides (but keeps mounted) the full card while resolving", () => {
    const onDecide = vi.fn();
    const { rerender } = render(view(props({ onDecide })));
    fireEvent.click(screen.getByTestId("tool-approve-c1"));
    fireEvent.click(screen.getByTestId("tool-reject-c2"));
    fireEvent.click(screen.getByTestId("approve-button"));
    // Multi-call per-call review is the confirmation: no dialog, straight through.
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(onDecide).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId("approval-decision-summary")).not.toBeInTheDocument();

    rerender(view(props({ onDecide, isResolvingPause: true })));

    expect(screen.getByTestId("approval-decision-summary")).toHaveAttribute("data-verdict", "APPROVED");
    expect(screen.getByTestId("approval-decision-summary-call-c1")).toHaveAttribute("data-outcome", "APPROVED");
    expect(screen.getByTestId("approval-decision-summary-call-c1")).toHaveTextContent("setupAgent");
    expect(screen.getByTestId("approval-decision-summary-call-c2")).toHaveAttribute("data-outcome", "REJECTED");
    expect(screen.getByTestId("approval-card-slot").className).toContain("hidden");
    expect(screen.getByTestId("operator-chat-resolving")).toBeInTheDocument();
  });

  it("flags amended arguments and shows the note", () => {
    const { rerender } = render(view(props()));
    fireEvent.click(screen.getByTestId("tool-approve-c1"));
    fireEvent.click(screen.getByTestId("tool-approve-c2"));
    fireEvent.click(screen.getByTestId("tool-amend-toggle-c1"));
    fireEvent.change(screen.getByTestId("tool-amend-c1"), { target: { value: '{"a":1}' } });
    fireEvent.click(screen.getByTestId("toggle-note"));
    fireEvent.change(screen.getByTestId("approval-note"), { target: { value: "looks fine" } });
    fireEvent.click(screen.getByTestId("approve-button"));
    rerender(view(props({ isResolvingPause: true })));
    expect(screen.getByTestId("approval-decision-summary-call-c1")).toHaveTextContent(/amended/i);
    expect(screen.getByTestId("approval-decision-summary-call-c2")).not.toHaveTextContent(/amended/i);
    expect(screen.getByTestId("approval-decision-summary-note")).toHaveTextContent("looks fine");
  });

  it("says rejected, for every call, after a reject", () => {
    const { rerender } = render(view(props()));
    fireEvent.click(screen.getByTestId("reject-button"));
    confirm("Reject");
    rerender(view(props({ isResolvingPause: true })));
    expect(screen.getByTestId("approval-decision-summary")).toHaveAttribute("data-verdict", "REJECTED");
    expect(screen.getByTestId("approval-decision-summary-call-c1")).toHaveAttribute("data-outcome", "REJECTED");
    expect(screen.getByTestId("approval-decision-summary-call-c2")).toHaveAttribute("data-outcome", "REJECTED");
  });

  it("brings the full card back with the per-call choices intact when the resume fails", () => {
    const { rerender } = render(view(props()));
    fireEvent.click(screen.getByTestId("tool-approve-c1"));
    fireEvent.click(screen.getByTestId("tool-reject-c2"));
    fireEvent.click(screen.getByTestId("approve-button"));
    rerender(view(props({ isResolvingPause: true })));
    rerender(view(props({ isResolvingPause: false, resolveError: "boom" })));

    expect(screen.queryByTestId("approval-decision-summary")).not.toBeInTheDocument();
    expect(screen.getByTestId("approval-card-slot").className).not.toContain("hidden");
    expect(screen.getByTestId("tool-approve-c1")).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByTestId("tool-reject-c2")).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByTestId("approve-button")).toBeEnabled();
  });

  it("renders a different pause's card fresh and never shows the old summary on it", () => {
    const { rerender } = render(view(props()));
    fireEvent.click(screen.getByTestId("tool-approve-c1"));
    fireEvent.click(screen.getByTestId("tool-approve-c2"));
    fireEvent.click(screen.getByTestId("approve-button"));
    rerender(view(props({ isResolvingPause: true })));
    expect(screen.getByTestId("approval-decision-summary")).toBeInTheDocument();

    // The resume re-paused on something else, while still resolving.
    rerender(view(props({ isResolvingPause: true, pauseKey: "2026-08-03T10:05:00Z" })));
    expect(screen.queryByTestId("approval-decision-summary")).not.toBeInTheDocument();
    expect(screen.getByTestId("tool-approve-c1")).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByTestId("tool-approve-c2")).toHaveAttribute("aria-pressed", "false");
  });

  it("keeps the summary and the card's state when pausedAt changes mid-decision and pauseKey is null", () => {
    const base = props({ pauseKey: null, pausedAt: "2026-08-03T10:00:00Z" });
    const { rerender } = render(view(base));
    fireEvent.click(screen.getByTestId("tool-approve-c1"));
    fireEvent.click(screen.getByTestId("tool-reject-c2"));
    fireEvent.click(screen.getByTestId("approve-button"));
    rerender(view({ ...base, isResolvingPause: true, pausedAt: undefined }));
    expect(screen.getByTestId("approval-decision-summary")).toBeInTheDocument();
    rerender(view({ ...base, isResolvingPause: false, pausedAt: "2030-01-01T00:00:00Z", resolveError: "boom" }));
    expect(screen.queryByTestId("approval-decision-summary")).not.toBeInTheDocument();
    expect(screen.getByTestId("tool-approve-c1")).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByTestId("tool-reject-c2")).toHaveAttribute("aria-pressed", "true");
  });

  it("is gone once the conversation is no longer paused", () => {
    const { rerender } = render(view(props()));
    fireEvent.click(screen.getByTestId("reject-button"));
    confirm("Reject");
    rerender(view(props({ isResolvingPause: true })));
    rerender(view(props({ isResolvingPause: false, isPaused: false })));
    expect(screen.queryByTestId("approval-decision-summary")).not.toBeInTheDocument();
    expect(screen.queryByTestId("approval-banner")).not.toBeInTheDocument();
  });
});

describe("OperatorChat — pauseSurface", () => {
  it("renders the full ApprovalBanner by default", () => {
    renderChat();
    expect(screen.queryByTestId("operator-chat-compact-pause")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /approve/i })).toBeInTheDocument();
  });

  it("renders a compact notice with the pause reason and a link to the full page, not the banner", () => {
    renderChat({ pauseSurface: "compact" });
    const notice = screen.getByTestId("operator-chat-compact-pause");
    expect(notice).toHaveTextContent(/creating a new agent/i);
    expect(screen.queryByRole("button", { name: /approve/i })).not.toBeInTheDocument();
    const link = screen.getByTestId("operator-chat-compact-pause-link");
    expect(link).toHaveAttribute("href", "/manage/operator");
  });

  it("falls back to a generic message when no reason is available yet", () => {
    renderChat({ pauseSurface: "compact", pauseReason: null });
    expect(screen.getByTestId("operator-chat-compact-pause")).toHaveTextContent(/needs your approval/i);
  });

  it("shows nothing pause-related when not paused, regardless of surface", () => {
    renderChat({ pauseSurface: "compact", isPaused: false });
    expect(screen.queryByTestId("operator-chat-compact-pause")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /approve/i })).not.toBeInTheDocument();
  });
});

/**
 * The operator's answers are status reports full of headings, bold and inline
 * code. They rendered as literal ## and ** markers — every other chat surface
 * in the Manager renders markdown, and this one silently did not.
 */
describe("OperatorChat — markdown rendering", () => {
  it("renders agent markdown as markup, not literal markers", () => {
    renderChat({
      isPaused: false,
      messages: [
        {
          id: "a1",
          role: "agent",
          content: "## Current state\n\n**Coordinator** is `CONNECTED`",
          timestamp: Date.now(),
        },
      ],
    });

    expect(screen.getByRole("heading", { name: "Current state" })).toBeInTheDocument();
    expect(screen.getByText("Coordinator").tagName).toBe("STRONG");
    expect(screen.getByText("CONNECTED").tagName).toBe("CODE");
    expect(screen.queryByText(/##/)).not.toBeInTheDocument();
  });

  it("keeps USER input literal — a user typing markdown syntax sees what they typed", () => {
    renderChat({
      isPaused: false,
      messages: [
        { id: "u1", role: "user", content: "what does **bold** mean here?", timestamp: Date.now() },
      ],
    });

    expect(screen.getByText(/\*\*bold\*\*/)).toBeInTheDocument();
  });
});

describe("OperatorChat — input affordances", () => {
  it("is a textarea (multi-line capable), with the Shift+Enter hint visible", () => {
    renderChat({ isPaused: false });

    // An <input> silently swallows Shift+Enter — the old element's own keydown
    // handler even special-cased it, for a newline that could never happen.
    expect(screen.getByTestId("operator-input").tagName).toBe("TEXTAREA");
    expect(screen.getByTestId("input-hint")).toHaveTextContent(/shift\+enter/i);
  });
});
