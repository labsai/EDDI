import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ChatMessage } from "@/components/chat/chat-message";
import type { ChatMessage as ChatMessageType } from "@/lib/api/chat";

const userMessage: ChatMessageType = {
  id: "msg-1", role: "user",
  content: "Hello, how are you?",
  timestamp: new Date("2024-01-15T10:30:00Z").getTime(),
  isStreaming: false,
};

const agentMessage: ChatMessageType = {
  id: "msg-2", role: "agent",
  content: "I'm doing well, thanks!",
  timestamp: new Date("2024-01-15T10:30:05Z").getTime(),
  isStreaming: false,
};

const streamingMessage: ChatMessageType = {
  id: "msg-3", role: "agent",
  content: "",
  timestamp: new Date("2024-01-15T10:30:10Z").getTime(),
  isStreaming: true,
};

const agentNoContent: ChatMessageType = {
  id: "msg-4", role: "agent",
  content: "",
  timestamp: new Date("2024-01-15T10:30:15Z").getTime(),
  isStreaming: false,
};

describe("ChatMessage", () => {
  it("renders user message content", () => {
    renderWithProviders(<ChatMessage message={userMessage} />);
    expect(screen.getByText("Hello, how are you?")).toBeInTheDocument();
  });

  it("renders agent message content", () => {
    renderWithProviders(<ChatMessage message={agentMessage} />);
    expect(screen.getByText("I'm doing well, thanks!")).toBeInTheDocument();
  });

  it("shows User icon for user messages", () => {
    const { container } = renderWithProviders(
      <ChatMessage message={userMessage} />
    );
    const userIcon = container.querySelector("svg.lucide-user");
    expect(userIcon).not.toBeNull();
  });

  it("shows Bot icon for agent messages", () => {
    const { container } = renderWithProviders(
      <ChatMessage message={agentMessage} />
    );
    const botIcon = container.querySelector("svg.lucide-bot");
    expect(botIcon).not.toBeNull();
  });

  it("shows typing indicator for streaming message with no content", () => {
    renderWithProviders(
      <ChatMessage message={streamingMessage} />
    );
    // Typing indicator has aria-label
    expect(
      screen.getByLabelText("Agent is typing")
    ).toBeInTheDocument();
  });

  it("shows 'No response' for agent message with empty content and not streaming", () => {
    renderWithProviders(
      <ChatMessage message={agentNoContent} />
    );
    expect(screen.getByText("No response")).toBeInTheDocument();
  });

  it("shows timestamp", () => {
    renderWithProviders(<ChatMessage message={userMessage} />);
    // Should have a time displayed (format varies by locale)
    const timeSpan = document.querySelector(
      "span.text-\\[10px\\]"
    );
    expect(timeSpan).not.toBeNull();
    expect(timeSpan?.textContent?.length).toBeGreaterThan(0);
  });

  it("applies animate-pulse class for streaming messages", () => {
    const streamingWithContent: ChatMessageType = {
      id: "msg-5", role: "agent",
      content: "Typing...",
      timestamp: Date.now(),
      isStreaming: true,
    };
    const { container } = renderWithProviders(
      <ChatMessage message={streamingWithContent} />
    );
    const pulseEl = container.querySelector(".animate-pulse");
    expect(pulseEl).not.toBeNull();
  });

  it("user message uses primary background", () => {
    const { container } = renderWithProviders(
      <ChatMessage message={userMessage} />
    );
    const bubble = container.querySelector(".bg-primary");
    expect(bubble).not.toBeNull();
  });

  it("agent message uses card background with border", () => {
    const { container } = renderWithProviders(
      <ChatMessage message={agentMessage} />
    );
    const bubble = container.querySelector(".bg-card");
    expect(bubble).not.toBeNull();
  });

  it("user message aligns right (flex-row-reverse)", () => {
    const { container } = renderWithProviders(
      <ChatMessage message={userMessage} />
    );
    const msgDiv = container.querySelector(".group.relative");
    expect(msgDiv?.className).toContain("flex-row-reverse");
  });

  it("agent message aligns left (flex-row)", () => {
    const { container } = renderWithProviders(
      <ChatMessage message={agentMessage} />
    );
    const msgDiv = container.querySelector(".group.relative");
    expect(msgDiv?.className).toContain("flex-row");
    expect(msgDiv?.className).not.toContain("flex-row-reverse");
  });
});
