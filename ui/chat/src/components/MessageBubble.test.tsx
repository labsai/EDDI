import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { MessageBubble } from "./MessageBubble";
import { ChatProvider } from "@/store/chat-store";
import type { ChatMessage } from "@/types";

function renderBubble(message: ChatMessage) {
  return render(
    <ChatProvider>
      <MessageBubble message={message} />
    </ChatProvider>,
  );
}

describe("MessageBubble", () => {
  it("renders user message content", () => {
    renderBubble({ id: "1", role: "user", content: "Hello", timestamp: 0 });
    expect(screen.getByText("Hello")).toBeInTheDocument();
  });

  it("renders bot message content", () => {
    renderBubble({ id: "2", role: "bot", content: "Hi there", timestamp: 0 });
    expect(screen.getByText("Hi there")).toBeInTheDocument();
  });

  it("renders markdown bold text in bot messages", () => {
    renderBubble({ id: "3", role: "bot", content: "This is **bold**", timestamp: 0 });
    const bold = screen.getByText("bold");
    expect(bold.tagName).toBe("STRONG");
  });

  it("applies user styling class", () => {
    const { container } = renderBubble({ id: "4", role: "user", content: "Hi", timestamp: 0 });
    expect(container.querySelector(".message--user")).toBeInTheDocument();
  });

  it("applies bot styling class", () => {
    const { container } = renderBubble({ id: "5", role: "bot", content: "Hi", timestamp: 0 });
    expect(container.querySelector(".message--bot")).toBeInTheDocument();
  });

  it("shows avatar with U for user and E for bot", () => {
    const { container } = renderBubble({ id: "6", role: "user", content: "Hi", timestamp: 0 });
    expect(container.querySelector(".message__avatar")?.textContent).toBe("U");
  });

  it("renders links in bot markdown", () => {
    renderBubble({ id: "7", role: "bot", content: "Visit [EDDI](https://eddi.labs.ai)", timestamp: 0 });
    const link = screen.getByRole("link", { name: "EDDI" });
    expect(link).toHaveAttribute("href", "https://eddi.labs.ai");
  });
});
