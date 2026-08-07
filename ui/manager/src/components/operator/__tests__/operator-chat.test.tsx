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
