import { describe, it, expect, beforeAll, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { DiscussionTranscript } from "../discussion-transcript";
import type { GroupConversation } from "@/lib/api/groups";
import type { GroupStreamState } from "@/hooks/use-group-discussion-stream";

/**
 * A rejected recommendation is a recorded human decision, not a system error.
 *
 * The backend used to set `FAILED` when a human rejected at a HITL gate, and
 * the Manager rendered a red "Failed" badge on the discussion card — telling
 * the operator that something had gone wrong when the run had done exactly
 * what it was asked. In a product whose selling point is the human in the loop,
 * that conflation is the wrong way round. The backend now has its own
 * `REJECTED` state; these are the assertions that it reads as a decision.
 */

const baseConversation: GroupConversation = {
  id: "conv-rejected",
  groupId: "group-1",
  userId: "user-1",
  state: "REJECTED",
  originalQuestion: "Should we fund the PHP modernization grant?",
  transcript: [],
  memberConversationIds: {},
  currentPhaseIndex: 2,
  currentPhaseName: "Synthesis",
  synthesizedAnswer: "Recommend funding at the reduced tier.",
  depth: 0,
  taskList: null,
  dynamicMembers: [],
  createdAgentIds: [],
  retainedAgentIds: [],
  created: "2026-09-22T10:00:00.000Z",
  lastModified: "2026-09-22T10:05:00.000Z",
};

describe("A rejected discussion", () => {
  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
    window.HTMLElement.prototype.scrollTo = vi.fn();
  });

  it('is labelled "Rejected", not "Failed"', () => {
    renderWithProviders(
      <DiscussionTranscript conversation={baseConversation} discussionStyle="ROUND_TABLE" />,
    );

    expect(screen.getByText("Rejected")).toBeInTheDocument();
    expect(screen.queryByText("Failed")).not.toBeInTheDocument();
  });

  it("does not wear the destructive badge a failure wears", () => {
    const { unmount } = renderWithProviders(
      <DiscussionTranscript
        conversation={{ ...baseConversation, state: "FAILED" }}
        discussionStyle="ROUND_TABLE"
      />,
    );
    const failedClasses = screen.getByText("Failed").className;
    expect(failedClasses).toContain("destructive");
    unmount();

    renderWithProviders(
      <DiscussionTranscript conversation={baseConversation} discussionStyle="ROUND_TABLE" />,
    );

    expect(screen.getByText("Rejected").className).not.toContain("destructive");
  });

  it("keeps the synthesis the human declined visible", () => {
    renderWithProviders(
      <DiscussionTranscript conversation={baseConversation} discussionStyle="ROUND_TABLE" />,
    );

    // The recommendation is the record of what was rejected; hiding it would
    // leave the decision unexplained.
    expect(screen.getByText(/Recommend funding at the reduced tier/)).toBeInTheDocument();
  });

  it("a live stream reports the state the backend sent, not COMPLETED", () => {
    // group_complete is the terminal notification for every outcome, including a
    // rejection. Hardcoding COMPLETED showed "Completed" for the seconds before
    // the persisted conversation loaded — the opposite of what happened.
    const streamState: GroupStreamState = {
      isStreaming: false,
      conversationId: "conv-rejected",
      state: "REJECTED",
      transcript: [],
      currentPhase: null,
      activeSpeakers: new Set<string>(),
      synthesizedAnswer: "Recommend funding at the reduced tier.",
      error: null,
      errorKind: null,
      startedAt: "2026-09-22T10:00:00.000Z",
      hitlPause: null,
      hitlResume: null,
      cancelInfo: null,
      humanInputRequest: null,
      retroRecorded: [],
      artifactUpdates: [],
      decision: null,
      plannedTasks: [],
      taskUpdates: [],
    } as unknown as GroupStreamState;

    renderWithProviders(
      <DiscussionTranscript
        conversation={null}
        streamState={streamState}
        discussionStyle="ROUND_TABLE"
      />,
    );

    expect(screen.getByText("Rejected")).toBeInTheDocument();
    expect(screen.queryByText("Completed")).not.toBeInTheDocument();
  });
});
