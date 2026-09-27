import { describe, it, expect, beforeEach } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { DiscussionPanel } from "../discussion-panel";
import { setStoredDiscussionView } from "../discussion-view-mode";
import type { GroupConversation, TranscriptEntry } from "@/lib/api/groups";

/**
 * A round picked on one discussion used to follow the reader to the next: every
 * surface keeps this panel mounted while switching discussions.
 */

function row(agentId: string, type: TranscriptEntry["type"], content: string): TranscriptEntry {
  return {
    speakerAgentId: agentId,
    speakerDisplayName: agentId,
    content,
    phaseIndex: 0,
    phaseName: type === "QUESTION" ? "Question" : "Opinions",
    type,
    timestamp: "2026-09-22T10:00:00Z",
    errorReason: null,
    targetAgentId: null,
  };
}

function threeRounds(id: string): GroupConversation {
  return {
    id,
    groupId: "g1",
    userId: "u",
    state: "COMPLETED",
    originalQuestion: "Q1?",
    round: 3,
    roundStartTranscriptIndex: 4,
    transcript: [
      row("user", "QUESTION", "Q1?"),
      row("a", "OPINION", "One."),
      row("user", "QUESTION", "Q2?"),
      row("a", "OPINION", "Two."),
      row("user", "QUESTION", "Q3?"),
      row("a", "OPINION", "Three."),
    ],
    memberConversationIds: {},
    currentPhaseIndex: 0,
    currentPhaseName: null,
    synthesizedAnswer: null,
    depth: 0,
    taskList: null,
    dynamicMembers: [],
    createdAgentIds: [],
  } as unknown as GroupConversation;
}

describe("DiscussionPanel — the picked round belongs to one discussion", () => {
  beforeEach(() => setStoredDiscussionView("rounds-test", "overview"));

  it("opens the next discussion on its newest round", () => {
    const { rerender } = renderWithProviders(
      <DiscussionPanel surface="rounds-test" transcript={<div />} conversation={threeRounds("gc-a")} />,
    );
    const select = screen.getByTestId("overview-round-select") as HTMLSelectElement;
    expect(select.value).toBe("3");
    fireEvent.change(select, { target: { value: "1" } });
    expect((screen.getByTestId("overview-round-select") as HTMLSelectElement).value).toBe("1");

    rerender(<DiscussionPanel surface="rounds-test" transcript={<div />} conversation={threeRounds("gc-b")} />);

    expect((screen.getByTestId("overview-round-select") as HTMLSelectElement).value).toBe("3");
  });
});

/**
 * Every surface builds `outcome` from the stored or streamed decision, which is
 * the NEWEST round's (a continuation replaces it). An earlier round showed that
 * verdict beside its own, older synthesis.
 */
describe("DiscussionPanel — the outcome card belongs to the newest round", () => {
  beforeEach(() => setStoredDiscussionView("rounds-outcome-test", "overview"));

  function withRoundOneSynthesis(): GroupConversation {
    const base = threeRounds("gc-outcome");
    const transcript = [...base.transcript];
    transcript.splice(2, 0, row("a", "SYNTHESIS", "Round one concluded: keep MongoDB."));
    return { ...base, roundStartTranscriptIndex: 5, transcript } as GroupConversation;
  }

  it("hides the newest decision on an earlier round and keeps that round's synthesis", () => {
    renderWithProviders(
      <DiscussionPanel
        surface="rounds-outcome-test"
        transcript={<div />}
        conversation={withRoundOneSynthesis()}
        outcome={<div data-testid="latest-decision">Round three verdict</div>}
      />,
    );
    expect(screen.getByTestId("latest-decision")).toBeInTheDocument();

    fireEvent.change(screen.getByTestId("overview-round-select"), { target: { value: "1" } });

    expect(screen.queryByTestId("latest-decision")).not.toBeInTheDocument();
    expect(screen.getByTestId("overview-synthesis")).toHaveTextContent("Round one concluded: keep MongoDB.");

    fireEvent.change(screen.getByTestId("overview-round-select"), { target: { value: "3" } });
    expect(screen.getByTestId("latest-decision")).toBeInTheDocument();
  });
});
