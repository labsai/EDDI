/* ──────────────────────────────────────────────
   Component-level regressions from the 2026-10 UX review
   ────────────────────────────────────────────── */

import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, fireEvent, act, waitFor } from "@testing-library/react";
import { ChatInput } from "./ChatInput";
import { SecretInput } from "./SecretInput";
import { MessageBubble } from "./MessageBubble";
import { PausedCard } from "./PausedCard";
import { ChatProvider } from "@/store/chat-store";
import { isImeComposing } from "@/ime";
import type { ApprovalStatus } from "@/api/hitl-api";

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("IME composition", () => {
  it("recognises both the standard flag and Safari's keyCode 229", () => {
    expect(isImeComposing({ nativeEvent: { isComposing: true } })).toBe(true);
    expect(isImeComposing({ nativeEvent: { keyCode: 229 } })).toBe(true);
    expect(isImeComposing({ keyCode: 229 })).toBe(true);
    expect(isImeComposing({ nativeEvent: { isComposing: false, keyCode: 13 } })).toBe(false);
  });

  it("does not send the composer while an IME candidate is being confirmed", () => {
    const onSend = vi.fn();
    render(
      <ChatProvider>
        <ChatInput onSend={onSend} />
      </ChatProvider>,
    );
    const input = screen.getByTestId("chat-input");
    fireEvent.change(input, { target: { value: "にほん" } });

    fireEvent.keyDown(input, { key: "Enter", isComposing: true });
    fireEvent.keyDown(input, { key: "Enter", keyCode: 229 });
    expect(onSend).not.toHaveBeenCalled();

    fireEvent.keyDown(input, { key: "Enter" });
    expect(onSend).toHaveBeenCalledWith("にほん", false);
  });

  it("does not submit an agent-requested field while composing", () => {
    const onSend = vi.fn();
    render(
      <ChatProvider>
        <SecretInput label="Name" subType="text" onSend={onSend} />
      </ChatProvider>,
    );
    const field = screen.getByTestId("secret-input-field");
    fireEvent.change(field, { target: { value: "ひと" } });

    fireEvent.keyDown(field, { key: "Enter", isComposing: true });
    expect(onSend).not.toHaveBeenCalled();
    fireEvent.keyDown(field, { key: "Enter" });
    expect(onSend).toHaveBeenCalledWith("ひと", false);
  });
});

describe("composer accessibility", () => {
  it("labels the textarea and exposes the secret toggle's state", () => {
    render(
      <ChatProvider>
        <ChatInput onSend={vi.fn()} />
      </ChatProvider>,
    );
    expect(screen.getByLabelText("Message")).toBe(screen.getByTestId("chat-input"));

    const toggle = screen.getByTestId("chat-secret-toggle");
    expect(toggle).toHaveAttribute("aria-pressed", "false");
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-pressed", "true");
    // The label does not flip; the state is aria-pressed's job.
    expect(toggle).toHaveAttribute("aria-label", "Secret mode");
  });

  it("puts focus back in the textarea after a secret send swaps the field", async () => {
    render(
      <ChatProvider>
        <ChatInput onSend={vi.fn()} />
      </ChatProvider>,
    );
    fireEvent.click(screen.getByTestId("chat-secret-toggle"));
    const secret = screen.getByTestId("chat-input");
    expect(secret.tagName).toBe("INPUT");
    fireEvent.change(secret, { target: { value: "hunter2" } });
    fireEvent.keyDown(secret, { key: "Enter" });

    await waitFor(() => {
      const next = screen.getByTestId("chat-input");
      expect(next.tagName).toBe("TEXTAREA");
      expect(next).toHaveFocus();
    });
  });

  it("does not steal focus on mount", () => {
    render(
      <ChatProvider>
        <ChatInput onSend={vi.fn()} />
      </ChatProvider>,
    );
    expect(screen.getByTestId("chat-input")).not.toHaveFocus();
  });
});

describe("MessageBubble", () => {
  it("copies an agent message and confirms", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    render(
      <ChatProvider>
        <MessageBubble message={{ id: "a", role: "agent", content: "copy **me**", timestamp: 0 }} />
      </ChatProvider>,
    );

    fireEvent.click(screen.getByLabelText("Copy message"));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith("copy **me**"));
    expect(await screen.findByLabelText("Copied")).toBeInTheDocument();
  });

  it("offers no copy button on user bubbles or while streaming", () => {
    const { rerender } = render(
      <ChatProvider>
        <MessageBubble message={{ id: "u", role: "user", content: "mine", timestamp: 0 }} />
      </ChatProvider>,
    );
    expect(screen.queryByTestId("copy-button")).toBeNull();
    rerender(
      <ChatProvider>
        <MessageBubble
          message={{ id: "a", role: "agent", content: "partial", timestamp: 0, isStreaming: true }}
        />
      </ChatProvider>,
    );
    expect(screen.queryByTestId("copy-button")).toBeNull();
  });

  it("gives a code block its own copy button that copies the code", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    render(
      <ChatProvider>
        <MessageBubble
          message={{ id: "c", role: "agent", content: "```js\nconst x = 1;\n```", timestamp: 0 }}
          enableCodeHighlight={false}
        />
      </ChatProvider>,
    );

    fireEvent.click(await screen.findByLabelText("Copy code"));

    await waitFor(() => expect(writeText).toHaveBeenCalledWith(expect.stringContaining("const x = 1;")));
  });

  it("names the speaker for assistive technology, with the agent's name when known", () => {
    const { container } = render(
      <ChatProvider>
        <MessageBubble
          message={{ id: "a", role: "agent", content: "hi", timestamp: 0 }}
          agentName="Ada"
        />
        <MessageBubble message={{ id: "u", role: "user", content: "yo", timestamp: 0 }} />
      </ChatProvider>,
    );
    const spoken = [...container.querySelectorAll(".chat-sr-only")].map((n) => n.textContent?.trim());
    expect(spoken).toEqual(["Ada said:", "You said:"]);
    for (const avatar of container.querySelectorAll(".message__avatar")) {
      expect(avatar).toHaveAttribute("aria-hidden", "true");
    }
  });

  it("throttles markdown re-parsing while streaming but shows the final text at once", async () => {
    vi.useFakeTimers();
    const msg = (content: string, isStreaming: boolean) => (
      <ChatProvider>
        <MessageBubble message={{ id: "s", role: "agent", content, timestamp: 0, isStreaming }} />
      </ChatProvider>
    );
    const { rerender } = render(msg("a", true));
    // First token after a quiet spell: immediate.
    rerender(msg("ab", true));
    expect(screen.getByText("ab")).toBeInTheDocument();
    // Inside the window: held back...
    rerender(msg("abc", true));
    expect(screen.queryByText("abc")).toBeNull();
    // ...and flushed when it ends.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60);
    });
    expect(screen.getByText("abc")).toBeInTheDocument();
    // Streaming over: the real value, no waiting.
    rerender(msg("abcd", false));
    expect(screen.getByText("abcd")).toBeInTheDocument();
  });

  it("renders a notice without an avatar or a speaker", () => {
    const { container } = render(
      <ChatProvider>
        <MessageBubble message={{ id: "n", role: "agent", kind: "notice", content: "⚠️ Undo failed.", timestamp: 0 }} />
      </ChatProvider>,
    );
    expect(screen.getByTestId("message-notice")).toHaveTextContent("Undo failed.");
    expect(container.querySelector(".message__avatar")).toBeNull();
    expect(container.querySelector(".chat-sr-only")).toBeNull();
  });
});

describe("PausedCard", () => {
  const status: ApprovalStatus = {
    conversationId: "c",
    state: "AWAITING_HUMAN",
    pausedAt: new Date().toISOString(),
    pauseReason: "manager approval required",
    timeoutPolicy: "AUTO_REJECT",
    approvalTimeout: "PT15M",
    pauseDetails: null,
  } as ApprovalStatus;

  it("hides the ticking countdown from assistive technology and offers a fixed clock time instead", () => {
    render(<PausedCard status={status} onCancel={vi.fn()} />);

    expect(screen.getByTestId("paused-deadline")).toHaveAttribute("aria-hidden", "true");
    expect(screen.getByTestId("paused-deadline-sr")).toHaveTextContent(/Otherwise rejected automatically at/);
    expect(screen.getByTestId("paused-deadline-sr").className).toContain("chat-sr-only");
  });
});
