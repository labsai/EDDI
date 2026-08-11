import { describe, it, expect, afterEach, afterAll, beforeAll } from "vitest";
import { screen } from "@testing-library/react";
import { setupServer } from "msw/node";
import { renderWithProviders } from "@/test/test-utils";
import { handlers } from "@/test/mocks/handlers";
import { BoardTranscript } from "@/components/workforce/board-transcript";
import type { DecisionRecord, TranscriptEntry, TranscriptEntryType } from "@/lib/api/groups";
import type { ConvergenceProgress } from "@/hooks/use-group-discussion-stream";

/**
 * The Workforce board historically rendered the newer collaboration modes'
 * *chatter* but not their *outcomes*: a DEBATE ended with prose and no verdict
 * card, a DELPHI convergence stop was invisible, and a TASK_FORCE showed its
 * task talk as ordinary messages. These tests pin the enriched rendering so the
 * board cannot silently regress to chat-only again.
 */

const server = setupServer(...handlers);
beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const entry = (
  type: TranscriptEntryType,
  content = "body",
  phaseIndex = 0,
  phaseName = "Phase",
): TranscriptEntry => ({
  speakerAgentId: "agent-1",
  speakerDisplayName: "Member One",
  type,
  content,
  timestamp: "2026-06-01T10:30:00Z",
  phaseIndex,
  phaseName,
  errorReason: null,
  targetAgentId: null,
});

const verdict: DecisionRecord = {
  type: "VERDICT",
  winner: "PRO",
  outcome: "The proposal should proceed.",
  method: "judge",
  tally: { PRO: 3, CON: 1 },
  dissents: [
    { agentId: "a2", displayName: "Contrarian", position: "Costs are understated." },
  ],
  decidedAtPhase: "Judgment",
  raw: null,
};

describe("BoardTranscript — structured decision", () => {
  it("renders the decision card before the synthesis entry", () => {
    renderWithProviders(
      <BoardTranscript
        transcript={[
          entry("OPINION", "First take", 0, "Opening Arguments (Pro)"),
          entry("SYNTHESIS", "Judge reasoning…", 1, "Judgment"),
        ]}
        boardId="g1"
        decision={verdict}
      />,
    );
    const decision = screen.getByTestId("decision-record");
    expect(decision).toBeInTheDocument();
    expect(screen.getByTestId("decision-winner")).toHaveTextContent("PRO");
    expect(screen.getByText("The proposal should proceed.")).toBeInTheDocument();
    // Minority report survives on this surface too.
    expect(screen.getByText("Costs are understated.")).toBeInTheDocument();
    // The finding precedes the reasoning that argues for it.
    const synthesis = screen.getByText("Judge reasoning…");
    expect(
      decision.compareDocumentPosition(synthesis) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("renders the decision before a trailing synthesizedAnswer when no SYNTHESIS entry exists", () => {
    renderWithProviders(
      <BoardTranscript
        transcript={[entry("OPINION", "Only opinion")]}
        boardId="g1"
        synthesizedAnswer="Trailing answer"
        decision={verdict}
      />,
    );
    const decision = screen.getByTestId("decision-record");
    const synthesis = screen.getByText("Trailing answer");
    expect(
      decision.compareDocumentPosition(synthesis) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("still shows the decision when the transcript has no synthesis at all", () => {
    renderWithProviders(
      <BoardTranscript
        transcript={[entry("OPINION", "Only opinion")]}
        boardId="g1"
        decision={verdict}
      />,
    );
    expect(screen.getByTestId("decision-record")).toBeInTheDocument();
  });

  it("shows a NONE decision that carries an unparsed judgment", () => {
    renderWithProviders(
      <BoardTranscript
        transcript={[entry("SYNTHESIS", "Prose only")]}
        boardId="g1"
        decision={{
          type: "NONE",
          winner: null,
          outcome: null,
          method: null,
          tally: null,
          dissents: [],
          decidedAtPhase: null,
          raw: "The judge replied in an unreadable shape.",
        }}
      />,
    );
    // `raw` means a judgment WAS produced but could not be parsed — hiding it
    // would turn a real failure into a blank space.
    expect(screen.getByTestId("decision-record")).toBeInTheDocument();
    expect(
      screen.getByText("The judge replied in an unreadable shape."),
    ).toBeInTheDocument();
  });

  it("hides an empty NONE decision (prose-only conclusion is the normal case)", () => {
    renderWithProviders(
      <BoardTranscript
        transcript={[entry("SYNTHESIS", "Prose only")]}
        boardId="g1"
        decision={{ type: "NONE", winner: null, outcome: null, method: null, tally: null, dissents: [], decidedAtPhase: null, raw: null }}
      />,
    );
    expect(screen.queryByTestId("decision-record")).not.toBeInTheDocument();
  });
});

describe("BoardTranscript — convergence (I2)", () => {
  const converged: ConvergenceProgress = {
    phaseIndex: 1,
    phaseName: "Round 2 (Anonymous)",
    repeat: 1,
    agreementScore: 0.91,
    converged: true,
    repeatsSkipped: 2,
    reason: "Estimates aligned",
  };

  it("shows the convergence badge on the matching phase header", () => {
    renderWithProviders(
      <BoardTranscript
        transcript={[
          entry("OPINION", "Round one", 0, "Round 1 (Independent)"),
          entry("OPINION", "Round two", 1, "Round 2 (Anonymous)"),
        ]}
        boardId="g1"
        convergence={new Map([[1, converged]])}
      />,
    );
    const badge = screen.getByTestId("board-phase-convergence-1");
    expect(badge).toHaveTextContent("Converged");
    expect(badge).toHaveTextContent("0.91");
    // Only the phase that was checked gets a badge.
    expect(screen.queryByTestId("board-phase-convergence-0")).not.toBeInTheDocument();
  });

  it("renders nothing convergence-related without stream state", () => {
    renderWithProviders(
      <BoardTranscript
        transcript={[entry("OPINION", "Round one", 0, "Round 1 (Independent)")]}
        boardId="g1"
      />,
    );
    expect(screen.queryByTestId(/board-phase-convergence/)).not.toBeInTheDocument();
  });
});
