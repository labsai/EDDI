import { describe, it, expect } from "vitest";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { AdvisorResponseCard } from "@/components/workforce/advisor-response-card";

const RETRO_BODY = '{"lessons": [{"lesson": "Timebox the debate", "context": "phase 2 overran"}]}';

/** No `{"`, `[{` or `":` anywhere on screen — the shape of a JSON blob. */
function expectNoRawJson(container: HTMLElement) {
  const text = container.textContent ?? "";
  expect(text).not.toContain('{"');
  expect(text).not.toContain("[{");
  expect(text).not.toContain('":');
}

/**
 * Structured turns through the Workforce board card. The Manager's transcript
 * card has the same cases, and StructuredTurnCard's own
 * (ui/manager/src/components/groups/__tests__/structured-turn-card.test.tsx):
 * the last time a group feature landed in only some renderers, a DISSENT
 * rendered as an ordinary opinion on two of the three.
 */
describe("structured turns on the Workforce board card", () => {
  it("shows a retro harvest rather than its JSON", () => {
    const { container } = renderWithProviders(
      <AdvisorResponseCard displayName="Ana" agentId="a1" content={RETRO_BODY} entryType="RETRO" boardId="b1" />,
    );
    expect(container).toHaveTextContent("Timebox the debate");
    expectNoRawJson(container);
  });

  it("unwraps a response envelope like the other two", () => {
    // It used to hand `content` straight to ReactMarkdown, so an envelope
    // rendered as its own JSON.
    const { container } = renderWithProviders(
      <AdvisorResponseCard
        displayName="Ana"
        agentId="a1"
        content={JSON.stringify({ output: [{ type: "text", text: "Plain answer." }] })}
        entryType="OPINION"
        boardId="b1"
      />,
    );
    expect(container).toHaveTextContent("Plain answer.");
    expectNoRawJson(container);
  });
});
