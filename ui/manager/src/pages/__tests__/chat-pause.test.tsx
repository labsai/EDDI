import { describe, it, expect, afterEach } from "vitest";
import { act, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ChatPage } from "@/pages/chat";
import { useChatStore } from "@/hooks/use-chat";

// The chat store is module-global; reset it so paused state can't leak between
// tests in this file.
afterEach(() => act(() => useChatStore.getState().reset()));

describe("ChatPage — HITL pause UX", () => {
  it("shows the awaiting-approval banner with the reason and a review link, and disables input", () => {
    renderWithProviders(<ChatPage />);
    act(() =>
      useChatStore.setState({
        conversationId: "conv-1",
        isPaused: true,
        pauseReason: "Deletion needs sign-off",
      }),
    );

    expect(screen.getByTestId("chat-pause-banner")).toBeInTheDocument();
    expect(screen.getByText("Deletion needs sign-off")).toBeInTheDocument();
    const review = screen.getByTestId("chat-pause-review");
    expect(review).toBeInTheDocument();
    expect(review).toHaveAttribute("href", "/manage/conversationview/conv-1");
    expect(screen.getByTestId("chat-input")).toBeDisabled();
  });

  it("does not show the banner and keeps input enabled when not paused", () => {
    renderWithProviders(<ChatPage />);
    act(() => useChatStore.setState({ conversationId: "conv-1", isPaused: false }));

    expect(screen.queryByTestId("chat-pause-banner")).not.toBeInTheDocument();
    expect(screen.getByTestId("chat-input")).not.toBeDisabled();
  });

  it("hides quick-reply buttons while paused (a pill must not send against a paused conversation)", () => {
    renderWithProviders(<ChatPage />);
    act(() =>
      useChatStore.setState({
        conversationId: "conv-1",
        quickReplies: ["Yes", "No"],
        isPaused: true,
      }),
    );
    expect(screen.queryByTestId("quick-reply-btn")).not.toBeInTheDocument();
  });

  it("shows quick-reply buttons when not paused", () => {
    renderWithProviders(<ChatPage />);
    act(() =>
      useChatStore.setState({
        conversationId: "conv-1",
        quickReplies: ["Yes", "No"],
        isPaused: false,
      }),
    );
    expect(screen.getAllByTestId("quick-reply-btn")).toHaveLength(2);
  });
});
