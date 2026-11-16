import { beforeAll, describe, expect, it, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { AgentResponseCard } from "@/components/groups/agent-response-card";
import { DecisionRecordCard } from "@eddi/conversation/team";
import { DiscussionTranscript } from "@/components/groups/discussion-transcript";
import { ChatMessage } from "@eddi/app-kit/components/chat/chat-message";
import {
  AT,
  DEBATE_DECISION,
  DECISIONS,
  FAILED_MEMBER,
  FAILURE,
  MESSAGES,
  VERDICT_JSON,
  conversation,
  entry,
  expectReadable,
} from "@eddi/app-kit/test/group-message-matrix";

/**
 * Every message and every decision a group discussion can produce, rendered
 * through every Manager surface that shows one. The fixtures, and why this
 * matrix exists, are in @eddi/app-kit (src/test/group-message-matrix.ts);
 * Workforce's surfaces have their half in ui/workforce.
 */

beforeAll(() => {
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
  window.HTMLElement.prototype.scrollTo = vi.fn();
});

describe("every group message reads cleanly on every surface", () => {
  describe.each(MESSAGES)("$name", ({ entry: e, facts }) => {
    it("Manager transcript card", () => {
      const { container } = renderWithProviders(<AgentResponseCard entry={e} />);
      facts.forEach((fact) => expect(container.textContent).toContain(fact));
      expectReadable(container.textContent ?? "");
    });
  });

  it("Manager discussion transcript, with every message in one conversation", () => {
    const { container } = renderWithProviders(
      <DiscussionTranscript conversation={conversation([...MESSAGES.map((m) => m.entry), FAILED_MEMBER])} />,
    );
    MESSAGES.flatMap((m) => m.facts).forEach((fact) => expect(container.textContent).toContain(fact));
    expect(screen.getByTestId("agent-failed-notice")).toBeInTheDocument();
    expectReadable(container.textContent ?? "");
  });

  it("the failure placeholder is a notice, not an answer", () => {
    renderWithProviders(<AgentResponseCard entry={FAILED_MEMBER} />);
    expect(screen.getByTestId("agent-failed-notice")).toBeInTheDocument();
  });

  it("names a live stream's planned assignees from the roster on the Manager transcript", () => {
    const plan = '```json\n[{"subject": "Unit economics", "assignedTo": "agent-cfo", "priority": 1}]\n```';
    renderWithProviders(
      <DiscussionTranscript
        conversation={conversation([entry("PLAN", plan)], { memberDisplayNames: undefined })}
        rosterDisplayNames={{ "agent-cfo": "Impact CFO" }}
      />,
    );
    expect(screen.getByTestId("structured-items")).toHaveTextContent("Impact CFO");
    expect(screen.queryByText("agent-cfo")).not.toBeInTheDocument();
  });
});

// ─── A member agent's own conversation in the 1:1 chat ───────────

describe("a group member's replies read cleanly in the 1:1 chat", () => {
  const replies: { name: string; content: string; facts: string[] }[] = [
    { name: "judge verdict", content: VERDICT_JSON, facts: ["Winner: CON", "CON built the stronger case"] },
    ...MESSAGES.filter((m) => ["VOTE", "BID", "RETRO", "BARGAIN", "PLAN"].includes(m.entry.type)).map((m) => ({
      name: m.name,
      content: m.entry.content!,
      facts: m.facts,
    })),
    { name: "convergence judge", content: '{"agreementScore": 0.82, "converged": true, "summary": "Positions have settled."}', facts: ["Positions have settled."] },
    { name: "facilitator move", content: '{"move": "RECRUIT", "args": {"role": "Security"}, "reason": "Coverage gap."}', facts: ["Coverage gap."] },
    { name: "failure placeholder", content: FAILURE, facts: ["This agent failed to produce a response."] },
  ];

  it.each(replies)("$name", ({ content, facts }) => {
    const { container } = renderWithProviders(
      <ChatMessage message={{ id: "m1", role: "agent", content, timestamp: Date.parse(AT), isStreaming: false }} />,
    );
    facts.forEach((fact) => expect(container.textContent).toContain(fact));
    expectReadable(container.textContent ?? "");
  });
});

// ─── Decisions × surfaces ────────────────────────────────────────

describe("every decision reads cleanly", () => {
  it.each(DECISIONS)("$name on the verdict card", ({ decision, facts, tie, forbid = [] }) => {
    const { container } = renderWithProviders(<DecisionRecordCard decision={decision} />);
    facts.forEach((fact) => expect(container.textContent).toContain(fact));
    forbid.forEach((text) => expect(container.textContent).not.toContain(text));
    expect(!!screen.queryByTestId("decision-tie")).toBe(tie);
    expectReadable(container.textContent ?? "");
  });

  it("a debate's reasoning is shown once, under its headline, on the Manager transcript", () => {
    const { container } = renderWithProviders(
      <DiscussionTranscript
        conversation={conversation([entry("SYNTHESIS", VERDICT_JSON, { phaseName: "Judgment" })], {
          synthesizedAnswer: DEBATE_DECISION.outcome,
          decision: DEBATE_DECISION,
        })}
      />,
    );
    expect(screen.getByTestId("decision-outcome")).toHaveTextContent(/^CON wins \(PRO 6\/10, CON 8\/10\)$/);
    const synthesis = screen.getByTestId("synthesis-card");
    expect(within(synthesis).getByRole("heading", { name: "Verdict" })).toBeInTheDocument();
    expectReadable(container.textContent ?? "");
  });

  it("a failed synthesis is a notice on every synthesis panel", async () => {
    const failed = conversation([], { synthesizedAnswer: FAILURE });
    renderWithProviders(<DiscussionTranscript conversation={failed} />);
    expect(screen.getByTestId("agent-failed-notice")).toBeInTheDocument();
  });
});
