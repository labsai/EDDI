import { describe, it, expect, afterEach, afterAll, beforeAll } from "vitest";
import { screen } from "@testing-library/react";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/test-utils";
import { handlers } from "@/test/mocks/handlers";
import { BoardTranscript } from "@/components/workforce/board-transcript";
import { ConversationViewer } from "@/components/workforce/conversation-viewer";
import type { TranscriptEntry, TranscriptEntryType } from "@/lib/api/groups";

/**
 * The Workforce surface renders group transcripts through two components of its
 * own, separate from the Manager's `DiscussionTranscript`. Both looked their entry
 * type up in `ENTRY_TYPE_INFO` and guarded the result with `info && …` — so unlike
 * the Manager's card they did not crash on the eleven types EDDI's Wave 0 added,
 * they silently rendered **no badge at all**. For a DISSENT that is the one
 * outcome a minority report must not have: an unlabelled contribution,
 * indistinguishable from an ordinary opinion.
 *
 * **What each test here actually pins** — established by mutation-checking, not
 * assumed, because four of these five pass against the old code too:
 *
 *  - The Wave 0 cases (dissent, follow-up, convergence) pin the *map entries*.
 *    They would fail if `ENTRY_TYPE_INFO` lost those keys, which is the state
 *    that produced the bug; they do NOT distinguish a raw lookup from
 *    `entryTypeInfo`, because a key that is present resolves either way.
 *  - The unknown-type case is the only one that pins the accessor swap, and it
 *    is what the swap buys: a type from a newer backend gets a humanized label
 *    instead of none.
 */

const server = setupServer(...handlers);
beforeAll(() => server.listen({ onUnhandledRequest: "bypass" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const entry = (type: TranscriptEntryType, content = "body text"): TranscriptEntry => ({
  speakerAgentId: "agent-1",
  speakerDisplayName: "Backend Expert",
  type,
  content,
  timestamp: "2026-06-01T10:30:00Z",
  phaseIndex: 0,
  phaseName: "Synthesis",
  errorReason: null,
  targetAgentId: null,
});

describe("BoardTranscript — Wave 0 entry types", () => {
  it("labels a dissent rather than rendering it unmarked", () => {
    renderWithProviders(
      <BoardTranscript
        transcript={[entry("DISSENT", "The migration cost is understated.")]}
        boardId="g1"
      />,
    );
    expect(screen.getByText("The migration cost is understated.")).toBeInTheDocument();
    expect(screen.getByText("Dissent")).toBeInTheDocument();
  });

  it("labels a follow-up, which the Manager itself produces", () => {
    renderWithProviders(
      <BoardTranscript transcript={[entry("FOLLOW_UP", "What about latency?")]} boardId="g1" />,
    );
    expect(screen.getByText("Follow-up")).toBeInTheDocument();
  });

  it("falls back to a humanized label for a type this build does not know", () => {
    renderWithProviders(
      <BoardTranscript
        transcript={[entry("SOME_FUTURE_TYPE" as TranscriptEntryType)]}
        boardId="g1"
      />,
    );
    expect(screen.getByText("Some Future Type")).toBeInTheDocument();
  });
});

describe("ConversationViewer — Wave 0 entry types", () => {
  function serveTranscript(transcript: TranscriptEntry[]) {
    server.use(
      http.get("*/groups/:groupId/conversations/:convId", () =>
        HttpResponse.json({
          id: "gc1",
          groupId: "g1",
          userId: "u",
          state: "COMPLETED",
          originalQuestion: "Should we?",
          transcript,
          memberConversationIds: {},
          currentPhaseIndex: 0,
          currentPhaseName: "Synthesis",
          synthesizedAnswer: "Yes.",
          depth: 0,
          taskList: null,
          dynamicMembers: [],
          createdAgentIds: [],
          retainedAgentIds: [],
          created: "2026-06-01T10:00:00Z",
          lastModified: "2026-06-01T10:30:00Z",
        }),
      ),
    );
  }

  it("labels a dissent", async () => {
    serveTranscript([entry("DISSENT", "I disagree on the timeline.")]);
    renderWithProviders(<ConversationViewer groupId="g1" conversationId="gc1" />);

    expect(await screen.findByText("I disagree on the timeline.")).toBeInTheDocument();
    expect(screen.getAllByText("Dissent").length).toBeGreaterThan(0);
  });

  it("labels a convergence result", async () => {
    serveTranscript([entry("CONVERGENCE", "agreement 0.91")]);
    renderWithProviders(<ConversationViewer groupId="g1" conversationId="gc1" />);

    expect(await screen.findByText("agreement 0.91")).toBeInTheDocument();
    expect(screen.getAllByText("Convergence").length).toBeGreaterThan(0);
  });
});
