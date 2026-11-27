import { beforeAll, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { BoardTranscript } from "@/components/workforce/board-transcript";
import { ConversationViewer } from "@/components/workforce/conversation-viewer";
import { ContextCard } from "@/components/workforce/context-card";
import { generateMarkdown } from "@/lib/group-transcript-export";
import {
  DEBATE_DECISION,
  FAILED_MEMBER,
  MESSAGES,
  VERDICT_JSON,
  conversation,
  decisionCase,
  entry,
  expectReadable,
} from "@eddi/app-kit/test/group-message-matrix";

/**
 * Every message and every decision a group discussion can produce, rendered
 * through every Workforce surface that shows one. The fixtures, and why this
 * matrix exists, are in @eddi/app-kit (src/test/group-message-matrix.ts); the
 * Manager's surfaces have their half in ui/manager.
 */

beforeAll(() => {
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
  window.HTMLElement.prototype.scrollTo = vi.fn();
});

describe("every group message reads cleanly on every surface", () => {
  describe.each(MESSAGES)("$name", ({ entry: e, facts }) => {
    it("Workforce board", () => {
      const { container } = renderWithProviders(<BoardTranscript transcript={[e]} boardId="board-1" />);
      facts.forEach((fact) => expect(container.textContent).toContain(fact));
      expectReadable(container.textContent ?? "");
    });
  });

  it("history viewer, with every message in one conversation", async () => {
    server.use(
      http.get("*/groups/:groupId/conversations/:convId", () =>
        HttpResponse.json(conversation([...MESSAGES.map((m) => m.entry), FAILED_MEMBER])),
      ),
    );
    const { container } = renderWithProviders(<ConversationViewer groupId="group-matrix" conversationId="gconv-matrix" />);
    await screen.findByText(/Cap debate rounds/);
    MESSAGES.flatMap((m) => m.facts).forEach((fact) => expect(container.textContent).toContain(fact));
    expect(screen.getByTestId("agent-failed-notice")).toBeInTheDocument();
    expectReadable(container.textContent ?? "");
  });

  it("the markdown export", () => {
    const md = generateMarkdown(
      conversation([...MESSAGES.map((m) => m.entry), FAILED_MEMBER], { decision: decisionCase("negotiated agreement").decision }),
      "Matrix",
    );
    MESSAGES.flatMap((m) => m.facts).forEach((fact) => expect(md).toContain(fact));
    expect(md).toContain("**Ballot:** Ship it (80% confident)");
    // Ties the assertion to the decision it exported, not just the transcript.
    expect(md).toContain("**Agreed terms:** 55/45 with support included");
    expect(md).not.toMatch(/\((VOTE|BID|BARGAIN|RETRO|ABSTAINED|HUMAN_INPUT|FOLLOW_UP|TASK_RESULT)\)/);
    expectReadable(md, { allowHeadings: true });
  });

  it("the board gives an error its reason and an abstention its meaning", () => {
    renderWithProviders(
      <BoardTranscript
        transcript={[entry("ERROR", null, { errorReason: "Model timed out" }), entry("ABSTAINED", null)]}
        boardId="board-1"
      />,
    );
    expect(screen.getByTestId("board-error-entry")).toHaveTextContent("Model timed out");
    expect(screen.getByTestId("board-abstained-entry")).toHaveTextContent("Declined to add anything new this round.");
    expect(screen.queryByText("No response generated")).not.toBeInTheDocument();
  });

  it("expands a pre-configured plan's one-line summary into its tasks on the Workforce board", () => {
    renderWithProviders(
      <BoardTranscript
        transcript={[entry("PLAN", "Pre-configured task plan: 2 tasks", { speakerAgentId: "system", speakerDisplayName: "System" })]}
        boardId="board-1"
        preConfiguredTasks={[
          { subject: "Unit economics sketch", description: "Per-bike P&L", assignToRole: "ALL", dependsOn: [], priority: 1 },
          { subject: "Operational risk list", description: "Top three risks", assignToRole: "ALL", dependsOn: [], priority: 1 },
        ]}
      />,
    );
    expect(screen.getByTestId("structured-items")).toHaveTextContent("Unit economics sketch");
    expect(screen.getByText("Operational risk list")).toBeInTheDocument();
    expect(screen.queryByText("Pre-configured task plan: 2 tasks")).not.toBeInTheDocument();
  });
});

describe("the Workforce 'Ask more' context card", () => {
  it.each([
    ["a stored ballot", '{"vote": "Ship it", "confidence": 0.8, "statement": "Ready to go."}', "Ship it"],
    ["a fenced verdict", "```json\n" + VERDICT_JSON + "\n```", "CON built the stronger case"],
  ])("reads %s instead of printing it", (_label, response, fact) => {
    const { container } = renderWithProviders(<ContextCard boardName="Board" question="Q" response={response} />);
    expect(container.textContent).toContain(fact);
    expectReadable(container.textContent ?? "");
  });
});

describe("every decision reads cleanly", () => {
  it("a debate's verdict card carries the headline, not the analysis, on the board", () => {
    const { container } = renderWithProviders(
      <BoardTranscript
        transcript={[entry("SYNTHESIS", VERDICT_JSON, { phaseName: "Judgment" })]}
        boardId="board-1"
        decision={DEBATE_DECISION}
      />,
    );
    expect(screen.getByTestId("decision-outcome")).toHaveTextContent(/^CON wins \(PRO 6\/10, CON 8\/10\)$/);
    expect(screen.getAllByRole("heading", { name: "Verdict" }).length).toBeGreaterThan(0);
    expectReadable(container.textContent ?? "");
  });

  it("the history viewer lays out a vote without its ballot records", async () => {
    server.use(
      http.get("*/groups/:groupId/conversations/:convId", () =>
        HttpResponse.json(
          conversation([entry("SYNTHESIS", "The board votes to ship.")], { decision: decisionCase("vote").decision }),
        ),
      ),
    );
    const { container } = renderWithProviders(<ConversationViewer groupId="group-matrix" conversationId="gconv-matrix" />);
    const card = await screen.findByTestId("decision-record");
    expect(card).toHaveTextContent("3 of 3 ballots valid");
    expectReadable(container.textContent ?? "");
  });
});
