/* ──────────────────────────────────────────────
   Message and ThinkingIndicator: what a sent message shows of its files, what
   a reply shows of the guardrails that fired on it, and the guardrail state of
   the activity indicator (phase 3c).
   ────────────────────────────────────────────── */

import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { Message } from "./Message";
import { ThinkingIndicator, guardrailActive, indicatorStatusText } from "./ActivityIndicator";
import type { ConversationMessage } from "../controller/state";

const base = { timestamp: 0 };

describe("Message", () => {
  it("shows an image attachment as its preview and a file as a chip with its size", () => {
    render(
      <Message
        message={{
          ...base,
          id: "u1",
          role: "user",
          content: "see these",
          attachments: [
            { fileName: "cat.png", mimeType: "image/png", previewUrl: "blob:cat" },
            { fileName: "report.pdf", mimeType: "application/pdf", sizeBytes: 2048, forwardableInline: false },
          ],
        }}
      />,
    );
    expect(screen.getByRole("img", { name: "cat.png" })).toHaveAttribute("src", "blob:cat");
    expect(screen.getByText("report.pdf")).toBeInTheDocument();
    expect(screen.getByText("2.0 KB")).toBeInTheDocument();
    // Stored but too large to inline: say so.
    expect(screen.getByTestId("attachment-not-forwarded")).toHaveTextContent("too large to send directly");
    expect(screen.getByText("see these")).toBeInTheDocument();
  });

  it("names each guardrail that fired on a reply, never the screened text", () => {
    render(
      <Message
        enableMarkdown={false}
        message={{
          ...base,
          id: "a1",
          role: "agent",
          content: "I can't help with that.",
          guardrails: [
            { type: "toxicity", phase: "output", action: "block" },
            { type: "pii", phase: "input", action: "redact" },
          ],
        }}
      />,
    );
    const notes = screen.getAllByTestId("guardrail-notice").map((n) => n.textContent);
    expect(notes).toEqual(["Guardrail (output): toxicity blocked", "Guardrail (input): pii redacted"]);
  });
});

describe("ThinkingIndicator — guardrail state", () => {
  const streaming = (guardrails?: ConversationMessage["guardrails"]): ConversationMessage[] => [
    { ...base, id: "u", role: "user", content: "hi" },
    { ...base, id: "a", role: "agent", content: "", isStreaming: true, ...(guardrails ? { guardrails } : {}) },
  ];

  it("is active while the reply being written carries a guardrail", () => {
    expect(guardrailActive(streaming([{ type: "pii", phase: "output", action: "reprompt" }]), true)).toBe(true);
    expect(guardrailActive(streaming(), true)).toBe(false);
    // The turn is over: nothing is at work any more.
    expect(guardrailActive(streaming([{ type: "pii", phase: "output", action: "reprompt" }]), false)).toBe(false);
  });

  it("says the reply is being checked; a running tool wins over it", () => {
    const { rerender } = render(<ThinkingIndicator guardrail />);
    expect(screen.getByTestId("guardrail-indicator")).toHaveTextContent("Checking the reply…");
    rerender(<ThinkingIndicator guardrail tool="search" />);
    expect(screen.getByTestId("tool-indicator")).toHaveTextContent("Using search…");
    expect(indicatorStatusText(true, null, undefined, true)).toBe("Checking the reply…");
  });
});
