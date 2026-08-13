import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { OperatorChat, type OperatorChatProps } from "../operator-chat";

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
