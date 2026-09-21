import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { DiscussionInsights } from "@/components/groups/discussion-insights";
import type { GroupConversation, SharedArtifact } from "@/lib/api/groups";

function conversation(overrides: Partial<GroupConversation> = {}): GroupConversation {
  return {
    id: "gc-1", groupId: "g1", userId: "u1", state: "COMPLETED",
    originalQuestion: "q", transcript: [], memberConversationIds: {},
    currentPhaseIndex: 0, currentPhaseName: null, synthesizedAnswer: null,
    depth: 0, taskList: null, dynamicMembers: [], createdAgentIds: [],
    retainedAgentIds: [], created: "2026-06-01T00:00:00Z", lastModified: "2026-06-01T00:00:00Z",
    ...overrides,
  } as GroupConversation;
}

const artifact: SharedArtifact = {
  id: "art-1", groupConversationId: "gc-1", ownerUserId: "u1",
  name: "notes.md", type: "MARKDOWN", content: "# Notes", version: 2,
  lastEditorAgentId: "agent-1", status: "DRAFT", history: [],
  createdAt: "2026-06-01T00:00:00Z", updatedAt: "2026-06-01T00:00:00Z",
};

describe("DiscussionInsights", () => {
  it("renders nothing when there is nothing to show", () => {
    const { container } = renderWithProviders(
      <DiscussionInsights conversation={conversation()} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing for a null conversation and no live payloads", () => {
    const { container } = renderWithProviders(<DiscussionInsights conversation={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders the artifacts panel from a persisted conversation", () => {
    renderWithProviders(
      <DiscussionInsights conversation={conversation({ artifacts: [artifact] })} />,
    );
    expect(screen.getByTestId("artifacts-panel")).toHaveTextContent("notes.md");
  });

  it("renders the negotiation ledger when the conversation carries one", () => {
    renderWithProviders(
      <DiscussionInsights
        conversation={conversation({
          negotiation: {
            proposals: [
              { id: "p1", byAgentId: "a", round: 1, terms: "50/50", status: "OPEN", acceptedBy: [], acceptanceEntryIndices: {} },
            ],
            concessions: [],
          },
        })}
      />,
    );
    expect(screen.getByTestId("negotiation-ledger")).toHaveTextContent("50/50");
  });

  it("stays silent for a negotiation object with two empty lists", () => {
    const { container } = renderWithProviders(
      <DiscussionInsights
        conversation={conversation({ negotiation: { proposals: [], concessions: [] } })}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("renders the windowing summary and exposes the rolling summary on hover", () => {
    renderWithProviders(
      <DiscussionInsights
        conversation={conversation({ summaryUpToIndex: 12, transcriptSummary: "Earlier: X and Y." })}
      />,
    );
    const badge = screen.getByTestId("transcript-window-summary");
    expect(badge).toHaveTextContent("12");
    expect(badge).toHaveAttribute("title", "Earlier: X and Y.");
  });

  it("renders live retro and artifact-write badges, distinguishing create from update", () => {
    renderWithProviders(
      <DiscussionInsights
        retroRecorded={[{ groupId: "g1", phaseName: "Retro", lessonsStored: 3 }]}
        artifactUpdates={[
          { artifactId: "a1", name: "notes.md", type: "MARKDOWN", version: 1, editorAgentId: "x", status: "DRAFT", created: true },
          { artifactId: "a1", name: "notes.md", type: "MARKDOWN", version: 2, editorAgentId: "y", status: "DRAFT", created: false },
        ]}
      />,
    );
    expect(screen.getByTestId("retro-recorded-summary")).toHaveTextContent("Retro");
    const updates = screen.getByTestId("artifact-updates-summary");
    expect(updates).toHaveTextContent("created");
    expect(updates).toHaveTextContent("updated");
  });
});
