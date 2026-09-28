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

  it("renders agent message content", () => {
    renderBubble({ id: "2", role: "agent", content: "Hi there", timestamp: 0 });
    expect(screen.getByText("Hi there")).toBeInTheDocument();
  });

  it("renders markdown bold text in agent messages", () => {
    renderBubble({ id: "3", role: "agent", content: "This is **bold**", timestamp: 0 });
    const bold = screen.getByText("bold");
    expect(bold.tagName).toBe("STRONG");
  });

  it("applies user styling class", () => {
    const { container } = renderBubble({ id: "4", role: "user", content: "Hi", timestamp: 0 });
    expect(container.querySelector(".message--user")).toBeInTheDocument();
  });

  it("applies agent styling class", () => {
    const { container } = renderBubble({ id: "5", role: "agent", content: "Hi", timestamp: 0 });
    expect(container.querySelector(".message--agent")).toBeInTheDocument();
  });

  it("shows avatar with U for user and E for agent", () => {
    const { container } = renderBubble({ id: "6", role: "user", content: "Hi", timestamp: 0 });
    expect(container.querySelector(".message__avatar")?.textContent).toBe("U");
  });

  it("renders links in agent markdown", () => {
    renderBubble({ id: "7", role: "agent", content: "Visit [EDDI](https://eddi.labs.ai)", timestamp: 0 });
    const link = screen.getByRole("link", { name: "EDDI" });
    expect(link).toHaveAttribute("href", "https://eddi.labs.ai");
  });

  it("renders a markdown image as a link, not a live <img> (no zero-click fetch)", () => {
    const { container } = renderBubble({
      id: "8",
      role: "agent",
      content: "![pixel](https://attacker.example/pixel.png)",
      timestamp: 0,
    });
    // No live image element must be produced.
    expect(container.querySelector("img")).toBeNull();
    // The URL is preserved as a click-to-open link instead.
    const link = screen.getByRole("link", { name: "pixel" });
    expect(link).toHaveAttribute("href", "https://attacker.example/pixel.png");
    expect(link).toHaveAttribute("rel", expect.stringContaining("noopener"));
  });

  it("renders a raw <img> tag as a link, not a live <img>", () => {
    const { container } = renderBubble({
      id: "9",
      role: "agent",
      content: '<img src="https://attacker.example/beacon.gif" alt="b">',
      timestamp: 0,
    });
    expect(container.querySelector("img")).toBeNull();
    const link = screen.getByRole("link", { name: "b" });
    expect(link).toHaveAttribute("href", "https://attacker.example/beacon.gif");
  });
});
