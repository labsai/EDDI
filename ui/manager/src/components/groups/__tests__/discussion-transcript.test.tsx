import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { screen, fireEvent } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { DiscussionTranscript } from "../discussion-transcript";
import type { GroupConversation } from "@/lib/api/groups";
import type { GroupStreamState } from "@/hooks/use-group-discussion-stream";

const mockConversation: GroupConversation = {
  id: "conv-1",
  groupId: "group-1",
  userId: "user-1",
  state: "COMPLETED",
  originalQuestion: "Is AI ready for safety-critical systems?",
  transcript: [
    {
      speakerAgentId: "agent-1",
      speakerDisplayName: "Safety AI",
      content: "No, it lacks deterministic guarantees.",
      phaseIndex: 0,
      phaseName: "Opinion",
      type: "OPINION",
      timestamp: "2026-06-09T12:00:00Z",
      errorReason: null,
      targetAgentId: null,
    },
    {
      speakerAgentId: "agent-2",
      speakerDisplayName: "Optimist AI",
      content: "Yes, probabilistic safety is sufficient.",
      phaseIndex: 0,
      phaseName: "Opinion",
      type: "OPINION",
      timestamp: "2026-06-09T12:01:00Z",
      errorReason: null,
      targetAgentId: null,
    },
    {
      speakerAgentId: "agent-1",
      speakerDisplayName: "Safety AI",
      content: "We must synthesize a hybrid architecture.",
      phaseIndex: 2,
      phaseName: "Synthesis",
      type: "SYNTHESIS",
      timestamp: "2026-06-09T12:05:00Z",
      errorReason: null,
      targetAgentId: null,
    },
  ],
  memberConversationIds: {},
  currentPhaseIndex: 2,
  currentPhaseName: "Synthesis",
  synthesizedAnswer: "### Hybrid Systems\nWe need both deterministic guardrails and LLMs.",
  depth: 0,
  taskList: null,
  dynamicMembers: [],
  createdAgentIds: [],
  retainedAgentIds: [],
  created: "2026-06-09T12:00:00.000Z",
  lastModified: "2026-06-09T12:05:00.000Z",
};

describe("DiscussionTranscript", () => {
  const mockWriteText = vi.fn();

  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
    window.HTMLElement.prototype.scrollTo = vi.fn();
    
    if (typeof navigator !== "undefined") {
      if (!navigator.clipboard) {
        Object.defineProperty(navigator, "clipboard", {
          value: { writeText: mockWriteText },
          writable: true,
          configurable: true,
        });
      } else {
        vi.spyOn(navigator.clipboard, "writeText").mockImplementation(mockWriteText);
      }
    }
  });

  beforeEach(() => {
    mockWriteText.mockReset();
  });

  it("renders ready to discuss empty state when conversation is null", () => {
    renderWithProviders(<DiscussionTranscript conversation={null} />);

    expect(screen.getByText("Ready to discuss")).toBeInTheDocument();
    expect(screen.getByText(/Select a past discussion/)).toBeInTheDocument();
  });

  it("renders skeleton loader when isLoading is true", () => {
    const { container } = renderWithProviders(
      <DiscussionTranscript conversation={null} isLoading={true} />
    );

    // Skeletons should be rendered
    const skeletons = container.querySelectorAll(".animate-pulse");
    expect(skeletons.length).toBeGreaterThan(0);
  });

  it("renders static conversation question header and metadata", () => {
    renderWithProviders(
      <DiscussionTranscript conversation={mockConversation} discussionStyle="ROUND_TABLE" />
    );

    expect(screen.getByText("Is AI ready for safety-critical systems?")).toBeInTheDocument();
    expect(screen.getByText("Completed")).toBeInTheDocument();
  });

  it("renders phase flow steps and highlights current phase progress", () => {
    renderWithProviders(
      <DiscussionTranscript conversation={mockConversation} discussionStyle="ROUND_TABLE" />
    );

    // Checks breadcrumb steps (Opinion, Discussion, Synthesis)
    expect(screen.getAllByText("Opinion").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Discussion").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Synthesis").length).toBeGreaterThan(0);
  });

  it("groups entries by phase and renders agent responses", () => {
    renderWithProviders(
      <DiscussionTranscript conversation={mockConversation} discussionStyle="ROUND_TABLE" />
    );

    // Checks agent response details
    expect(screen.getAllByText("Safety AI").length).toBeGreaterThan(0);
    expect(screen.getByText("No, it lacks deterministic guarantees.")).toBeInTheDocument();
    expect(screen.getByText("Optimist AI")).toBeInTheDocument();
    expect(screen.getByText("Yes, probabilistic safety is sufficient.")).toBeInTheDocument();
  });

  it("renders synthesized answer card and supports copying content", async () => {
    renderWithProviders(
      <DiscussionTranscript conversation={mockConversation} discussionStyle="ROUND_TABLE" />
    );

    expect(screen.getByTestId("synthesis-card")).toBeInTheDocument();
    expect(screen.getByText("Hybrid Systems")).toBeInTheDocument();

    const copyBtn = screen.getByRole("button", { name: "Copy" });
    fireEvent.click(copyBtn);

    expect(mockWriteText).toHaveBeenCalledWith("### Hybrid Systems\nWe need both deterministic guardrails and LLMs.");
  });

  it("renders live streaming state and speaking indicators", () => {
    const mockStreamState: GroupStreamState = {
      conversationId: "conv-test-1", isStreaming: true,
      state: "IN_PROGRESS",
      startedAt: "2026-06-09T12:00:00.000Z",
      transcript: [
        {
          speakerAgentId: "agent-1",
          speakerDisplayName: "Safety AI",
          content: "I am drafting safety guidelines.",
          phaseIndex: 0,
          phaseName: "Opinion",
          type: "OPINION",
          timestamp: "2026-06-09T12:01:00Z",
          errorReason: null,
          targetAgentId: null,
        },
        {
          speakerAgentId: "agent-2",
          speakerDisplayName: "Optimist AI",
          content: null, // Still speaking
          phaseIndex: 0,
          phaseName: "Opinion",
          type: "OPINION",
          timestamp: "2026-06-09T12:02:00Z",
          errorReason: null,
          targetAgentId: null,
        },
      ],
      currentPhase: { index: 0, name: "Opinion", type: "OPINION" },
      activeSpeakers: new Set(["agent-2"]),
      synthesizedAnswer: null,
      error: null,
      taskPlan: null,
      taskVerifications: new Map(),
      tasksInProgress: new Set(),
      tasksCompleted: new Set(),
    };

    renderWithProviders(
      <DiscussionTranscript
        conversation={null}
        streamState={mockStreamState}
        discussionStyle="ROUND_TABLE"
      />
    );

    // Live badge
    expect(screen.getByText("● LIVE")).toBeInTheDocument();
    expect(screen.getByText("Agents are discussing…")).toBeInTheDocument();
    expect(screen.getByText("1 speaking")).toBeInTheDocument();

    // Check typing indicator for active speaker (agent-2 / Optimist AI)
    // The response card is rendered but isSpeaking prop is true.
    expect(screen.getByText("Optimist AI")).toBeInTheDocument();
  });

  it("renders stream error banner", () => {
    const mockStreamStateWithError: GroupStreamState = {
      conversationId: "conv-test-1", isStreaming: false,
      state: "FAILED",
      startedAt: "2026-06-09T12:00:00.000Z",
      transcript: [],
      currentPhase: null,
      activeSpeakers: new Set(),
      synthesizedAnswer: null,
      error: "SSE Connection Aborted",
      taskPlan: null,
      taskVerifications: new Map(),
      tasksInProgress: new Set(),
      tasksCompleted: new Set(),
    };

    renderWithProviders(
      <DiscussionTranscript
        conversation={null}
        streamState={mockStreamStateWithError}
        discussionStyle="ROUND_TABLE"
      />
    );

    expect(screen.getByText("⚠️ SSE Connection Aborted")).toBeInTheDocument();
  });
});
