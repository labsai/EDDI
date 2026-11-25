import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { GuardrailActivity } from "@eddi/app-kit/components/chat/guardrail-activity";
import type { ChatMessage } from "@eddi/app-kit/lib/api/chat";

// The activity indicator's guardrail state (@eddi/conversation), in the
// Manager's chat: a guardrail fired on the reply still being written.
describe("GuardrailActivity", () => {
  const turn = (guardrails?: ChatMessage["guardrails"]): ChatMessage[] => [
    { id: "u", role: "user", content: "hi", timestamp: 1 },
    { id: "a", role: "agent", content: "", timestamp: 2, isStreaming: true, ...(guardrails ? { guardrails } : {}) },
  ];

  it("says the reply is being checked while a guardrail is at work on it", () => {
    renderWithProviders(
      <GuardrailActivity messages={turn([{ type: "pii", phase: "output", action: "reprompt" }])} isProcessing />,
    );
    expect(screen.getByTestId("guardrail-activity")).toHaveTextContent("Checking the reply…");
  });

  it("shows nothing without a guardrail, or once the turn is over", () => {
    const { rerender } = renderWithProviders(<GuardrailActivity messages={turn()} isProcessing />);
    expect(screen.queryByTestId("guardrail-activity")).toBeNull();
    rerender(
      <GuardrailActivity
        messages={turn([{ type: "pii", phase: "output", action: "reprompt" }])}
        isProcessing={false}
      />,
    );
    expect(screen.queryByTestId("guardrail-activity")).toBeNull();
  });
});
