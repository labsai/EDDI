/* eslint-disable @typescript-eslint/no-explicit-any */
import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, renderPage, userEvent } from "@/test/test-utils";

// Mocking hooks directly can be easier for coverage if MSW isn't fully returning data
import * as useGroupsHook from "@/hooks/use-groups";
import * as useWorkforceAnalyticsHook from "@/hooks/use-workforce-analytics";
import * as useWorkforceThreadsHook from "@/hooks/use-workforce-threads";
import * as chatApi from "@/lib/api/chat";

import { WorkforceAnalytics } from "../workforce-analytics";
import { WorkforceHistory } from "../workforce-history";
import { WorkforceThread } from "../workforce-thread";
import { ConversationViewer } from "@/components/workforce/conversation-viewer";

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;
window.HTMLElement.prototype.scrollIntoView = vi.fn();
window.HTMLElement.prototype.scrollTo = vi.fn();

describe("Workforce Coverage Tests", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("WorkforceAnalytics", () => {
    it("renders loading state", () => {
      vi.spyOn(useWorkforceAnalyticsHook, "useWorkforceAnalytics").mockReturnValue({
        isLoading: true,
        hasError: false,
        totalDiscussions: 0,
        unfilteredTotal: 0,
        groupCount: 0,
        completionRate: 0,
        activeExperts: 0,
        totalExperts: 0,
        avgDurationMs: 0,
        dailyActivity: [],
        outcomeDistribution: [],
        styleDistribution: [],
        phaseDistribution: [],
        agentStats: [],
        recentDiscussions: [],
        isFiltered: false,
        outcomeCounts: {},
        styleCounts: {}
      } as any);

      renderWithProviders(<WorkforceAnalytics />);
      // Should show skeleton and no text
    });

    it("renders error state", () => {
      vi.spyOn(useWorkforceAnalyticsHook, "useWorkforceAnalytics").mockReturnValue({
        isLoading: false,
        hasError: true,
        totalDiscussions: 0,
      } as any);

      renderWithProviders(<WorkforceAnalytics />);
      expect(screen.getByText(/Unable to load insights/i)).toBeInTheDocument();
    });

    it("renders empty state", () => {
      vi.spyOn(useWorkforceAnalyticsHook, "useWorkforceAnalytics").mockReturnValue({
        isLoading: false,
        hasError: false,
        totalDiscussions: 0,
        groupCount: 1,
      } as any);

      renderWithProviders(<WorkforceAnalytics />);
      expect(screen.getByText(/No insights yet/i)).toBeInTheDocument();
      expect(screen.getByText(/Assemble Task Force/i)).toBeInTheDocument();
    });

    it("renders data and filters", async () => {
      const user = userEvent.setup();
      vi.spyOn(useWorkforceAnalyticsHook, "useWorkforceAnalytics").mockReturnValue({
        isLoading: false,
        hasError: false,
        totalDiscussions: 10,
        unfilteredTotal: 10,
        groupCount: 2,
        completionRate: 100,
        activeExperts: 5,
        totalExperts: 5,
        avgDurationMs: 120000,
        dailyActivity: [],
        outcomeDistribution: [],
        styleDistribution: [],
        phaseDistribution: [],
        agentStats: [{ agentId: "a1", displayName: "Agent 1" }],
        recentDiscussions: [],
        isFiltered: false,
        outcomeCounts: { COMPLETED: 10 },
        styleCounts: { DEBATE: 10 }
      } as any);

      renderWithProviders(<WorkforceAnalytics />);
      
      expect(screen.getByText(/Insights/i)).toBeInTheDocument();
      expect(screen.getByText(/Completion Rate/i)).toBeInTheDocument();
      
      // Attempt to click an agent stat to trigger handleAgentClick
      const agentEl = screen.getByText("Agent 1");
      await user.click(agentEl);
      
      // Should open AgentPerformanceSheet (mocked or full component)
      await waitFor(() => {
        expect(screen.getAllByRole("dialog").length).toBeGreaterThan(0);
      });
    });
  });

  describe("WorkforceHistory", () => {
    it("renders list and allows search", async () => {
      const user = userEvent.setup();
      vi.spyOn(useGroupsHook, "useGroupConversations").mockReturnValue({
        data: [
          { id: "conv1", originalQuestion: "Test Question 1", state: "COMPLETED", created: Date.now() },
          { id: "conv2", originalQuestion: "Another Q", state: "IN_PROGRESS", created: Date.now() }
        ],
        isLoading: false,
        isError: false
      } as any);

      renderPage("/workforce/board1/history", <WorkforceHistory />, "/workforce/:boardId/history");
      
      expect(screen.getByText(/Conversation History/i)).toBeInTheDocument();
      expect(screen.getByText(/Test Question 1/i)).toBeInTheDocument();
      
      const searchInput = screen.getByPlaceholderText(/Search conversations/i);
      await user.type(searchInput, "Another");
      
      expect(screen.queryByText(/Test Question 1/i)).not.toBeInTheDocument();
      expect(screen.getByText(/Another Q/i)).toBeInTheDocument();
    });

    it("allows deleting a conversation", async () => {
      const user = userEvent.setup();
      const deleteMock = vi.fn();
      vi.spyOn(useGroupsHook, "useGroupConversations").mockReturnValue({
        data: [{ id: "conv1", originalQuestion: "Test Question 1", state: "COMPLETED", created: Date.now() }],
        isLoading: false,
      } as any);
      vi.spyOn(useGroupsHook, "useDeleteGroupConversation").mockReturnValue({
        mutate: deleteMock,
        isPending: false
      } as any);

      renderPage("/workforce/board1/history", <WorkforceHistory />, "/workforce/:boardId/history");
      
      // Find delete button
      const deleteBtn = screen.getByRole("button", { name: /Delete conversation/i });
      await user.click(deleteBtn);
      
      // Confirmation dialog
      const confirmBtn = await screen.findByRole("button", { name: /^Delete$/i });
      await user.click(confirmBtn);
      
      expect(deleteMock).toHaveBeenCalled();
    });
  });

  describe("WorkforceThread", () => {
    it("initializes thread and sends message", async () => {
      const user = userEvent.setup();
      const registerThreadMock = vi.fn();
      const updateActivityMock = vi.fn();
      vi.spyOn(useWorkforceThreadsHook, "useWorkforceThreads").mockReturnValue({
        getThread: () => null,
        registerThread: registerThreadMock,
        updateActivity: updateActivityMock
      } as any);
      
      vi.spyOn(useGroupsHook, "useGroup").mockReturnValue({
        data: { id: "board1", name: "Board", members: [{ agentId: "agent1", displayName: "Test Agent" }] }
      } as any);

      vi.spyOn(chatApi, "startConversation").mockResolvedValue("conv-123");
      vi.spyOn(chatApi, "readConversation").mockResolvedValue({ conversationSteps: [] } as any);
      const sendMsgMock = vi.spyOn(chatApi, "sendMessage").mockResolvedValue({
        conversationSteps: [
          {
            timestamp: Date.now(),
            conversationStep: [
              { key: "output:text:0", value: "Hello from agent" }
            ]
          }
        ]
      } as any);

      renderPage("/workforce/board1/thread/agent1", <WorkforceThread />, "/workforce/:boardId/thread/:memberId");
      
      await waitFor(() => {
        expect(screen.getAllByText(/Test Agent/i).length).toBeGreaterThan(0);
      });

      const input = screen.getByPlaceholderText(/Message Test Agent/i);
      await user.type(input, "Hello");
      
      const sendBtn = screen.getByRole("button", { name: /Send/i });
      await user.click(sendBtn);

      expect(sendMsgMock).toHaveBeenCalledWith("production", "agent1", "conv-123", "Hello");
      
      await waitFor(() => {
        expect(screen.getAllByText(/Hello from agent/i).length).toBeGreaterThan(0);
      });
    });
  });

  describe("ConversationViewer", () => {
    it("renders empty state or viewer", () => {
      vi.spyOn(useGroupsHook, "useGroupConversation").mockReturnValue({
        data: {
          id: "conv1",
          originalQuestion: "Some Question",
          state: "COMPLETED",
          transcript: [],
        },
        isLoading: false,
        isError: false,
      } as any);

      renderWithProviders(<ConversationViewer groupId="grp1" conversationId="conv1" onClose={vi.fn()} />);
      
      expect(screen.getByText(/Some Question/i)).toBeInTheDocument();
      expect(screen.getByText(/Completed/i)).toBeInTheDocument();
    });

    it("renders loading skeleton", () => {
      vi.spyOn(useGroupsHook, "useGroupConversation").mockReturnValue({
        isLoading: true,
        data: undefined,
      } as any);

      const { container } = renderWithProviders(<ConversationViewer groupId="grp1" conversationId="conv1" onClose={vi.fn()} />);
      expect(container.querySelector(".animate-pulse")).toBeInTheDocument();
    });

    it("renders error state", () => {
      vi.spyOn(useGroupsHook, "useGroupConversation").mockReturnValue({
        isError: true,
        data: undefined,
      } as any);

      renderWithProviders(<ConversationViewer groupId="grp1" conversationId="conv1" onClose={vi.fn()} />);
      expect(screen.getByText(/Failed to load conversation/i)).toBeInTheDocument();
    });

    it("renders not found state", () => {
      vi.spyOn(useGroupsHook, "useGroupConversation").mockReturnValue({
        isLoading: false,
        isError: false,
        data: null,
      } as any);

      renderWithProviders(<ConversationViewer groupId="grp1" conversationId="conv1" onClose={vi.fn()} />);
      expect(screen.getByText(/Conversation not found/i)).toBeInTheDocument();
    });

    it("renders transcript with OPINION entry", () => {
      vi.spyOn(useGroupsHook, "useGroupConversation").mockReturnValue({
        isLoading: false,
        isError: false,
        data: {
          id: "conv1",
          originalQuestion: "How do we proceed?",
          state: "COMPLETED",
          transcript: [{
            speakerAgentId: "agent-001",
            speakerDisplayName: "Research Agent",
            type: "OPINION",
            content: "We should analyze the data first.",
            phaseIndex: 0,
            timestamp: "2024-06-01T10:00:00Z",
          }],
        },
      } as any);

      renderWithProviders(<ConversationViewer groupId="grp1" conversationId="conv1" onClose={vi.fn()} />);
      expect(screen.getByText("Research Agent")).toBeInTheDocument();
      expect(screen.getByText(/We should analyze the data first/i)).toBeInTheDocument();
    });

    it("renders SYNTHESIS entry with amber styling", () => {
      vi.spyOn(useGroupsHook, "useGroupConversation").mockReturnValue({
        isLoading: false,
        isError: false,
        data: {
          id: "conv1",
          originalQuestion: "Question?",
          state: "COMPLETED",
          transcript: [{
            speakerAgentId: "agent-002",
            speakerDisplayName: "Synthesizer",
            type: "SYNTHESIS",
            content: "Final synthesis output.",
            phaseIndex: 0,
            timestamp: "2024-06-01T10:00:00Z",
          }],
        },
      } as any);

      renderWithProviders(<ConversationViewer groupId="grp1" conversationId="conv1" onClose={vi.fn()} />);
      expect(screen.getAllByText(/Synthesis/i).length).toBeGreaterThan(0);
      expect(screen.getByText(/Final synthesis output/i)).toBeInTheDocument();
    });

    it("renders ERROR entry", () => {
      vi.spyOn(useGroupsHook, "useGroupConversation").mockReturnValue({
        isLoading: false,
        isError: false,
        data: {
          id: "conv1",
          originalQuestion: "Question?",
          state: "COMPLETED",
          transcript: [{
            speakerAgentId: "agent-003",
            speakerDisplayName: "Agent X",
            type: "ERROR",
            content: "Something went wrong.",
            errorReason: "timeout",
            phaseIndex: 0,
            timestamp: "2024-06-01T10:00:00Z",
          }],
        },
      } as any);

      renderWithProviders(<ConversationViewer groupId="grp1" conversationId="conv1" onClose={vi.fn()} />);
      expect(screen.getByText(/Something went wrong\./i)).toBeInTheDocument();
      expect(screen.getByText(/timeout/i)).toBeInTheDocument();
    });

    it("renders SKIPPED entry", () => {
      vi.spyOn(useGroupsHook, "useGroupConversation").mockReturnValue({
        isLoading: false,
        isError: false,
        data: {
          id: "conv1",
          originalQuestion: "Question?",
          state: "COMPLETED",
          transcript: [{
            speakerAgentId: "agent-004",
            speakerDisplayName: "Agent Y",
            type: "SKIPPED",
            errorReason: "rate limited",
            phaseIndex: 0,
            timestamp: "2024-06-01T10:00:00Z",
          }],
        },
      } as any);

      renderWithProviders(<ConversationViewer groupId="grp1" conversationId="conv1" onClose={vi.fn()} />);
      expect(screen.getByText(/Agent Y/i)).toBeInTheDocument();
      expect(screen.getByText(/\(rate limited\)/i)).toBeInTheDocument();
    });

    it("renders QUESTION bubble", () => {
      vi.spyOn(useGroupsHook, "useGroupConversation").mockReturnValue({
        isLoading: false,
        isError: false,
        data: {
          id: "conv1",
          originalQuestion: "Question?",
          state: "COMPLETED",
          transcript: [{
            speakerAgentId: "user-1",
            speakerDisplayName: "User",
            type: "QUESTION",
            content: "What is the best approach?",
            phaseIndex: 0,
            timestamp: "2024-06-01T10:00:00Z",
          }],
        },
      } as any);

      renderWithProviders(<ConversationViewer groupId="grp1" conversationId="conv1" onClose={vi.fn()} />);
      expect(screen.getByText(/What is the best approach\?/i)).toBeInTheDocument();
    });

    it("renders SynthesizedAnswerFooter when synthesizedAnswer exists and no SYNTHESIS entry", () => {
      vi.spyOn(useGroupsHook, "useGroupConversation").mockReturnValue({
        isLoading: false,
        isError: false,
        data: {
          id: "conv1",
          originalQuestion: "Question?",
          state: "COMPLETED",
          synthesizedAnswer: "The final answer is 42.",
          transcript: [{
            speakerAgentId: "agent-001",
            speakerDisplayName: "Agent X",
            type: "OPINION",
            content: "Some opinion",
            phaseIndex: 0,
            timestamp: "2024-06-01T10:00:00Z",
          }],
        },
      } as any);

      renderWithProviders(<ConversationViewer groupId="grp1" conversationId="conv1" onClose={vi.fn()} />);
      expect(screen.getByText(/Final Synthesized Answer/i)).toBeInTheDocument();
      expect(screen.getByText(/The final answer is 42\./i)).toBeInTheDocument();
    });

    it("does not render SynthesizedAnswerFooter when a SYNTHESIS transcript entry already exists", () => {
      vi.spyOn(useGroupsHook, "useGroupConversation").mockReturnValue({
        isLoading: false,
        isError: false,
        data: {
          id: "conv1",
          originalQuestion: "Question?",
          state: "COMPLETED",
          synthesizedAnswer: "Something",
          transcript: [{
            speakerAgentId: "agent-syn",
            speakerDisplayName: "Synthesizer",
            type: "SYNTHESIS",
            content: "Synthesized from transcript",
            phaseIndex: 1,
            timestamp: "2024-06-01T10:00:00Z",
          }],
        },
      } as any);

      renderWithProviders(<ConversationViewer groupId="grp1" conversationId="conv1" onClose={vi.fn()} />);
      expect(screen.queryByText(/Final Synthesized Answer/i)).not.toBeInTheDocument();
    });

    it("renders phase separator when entries have different phaseIndex", () => {
      vi.spyOn(useGroupsHook, "useGroupConversation").mockReturnValue({
        isLoading: false,
        isError: false,
        data: {
          id: "conv1",
          originalQuestion: "Question?",
          state: "COMPLETED",
          transcript: [
            {
              speakerAgentId: "agent-1",
              speakerDisplayName: "Agent 1",
              type: "OPINION",
              content: "First phase",
              phaseIndex: 0,
              phaseName: "Phase 1",
              timestamp: "2024-06-01T10:00:00Z",
            },
            {
              speakerAgentId: "agent-2",
              speakerDisplayName: "Agent 2",
              type: "CRITIQUE",
              content: "Second phase",
              phaseIndex: 1,
              phaseName: "Phase 2",
              timestamp: "2024-06-01T10:01:00Z",
            }
          ],
        },
      } as any);

      renderWithProviders(<ConversationViewer groupId="grp1" conversationId="conv1" onClose={vi.fn()} />);
      expect(screen.getByText("Phase 1")).toBeInTheDocument();
      expect(screen.getByText("Phase 2")).toBeInTheDocument();
    });

    it("renders IN_PROGRESS state badge", () => {
      vi.spyOn(useGroupsHook, "useGroupConversation").mockReturnValue({
        isLoading: false,
        isError: false,
        data: {
          id: "conv1",
          originalQuestion: "Question?",
          state: "IN_PROGRESS",
          transcript: [],
        },
      } as any);

      renderWithProviders(<ConversationViewer groupId="grp1" conversationId="conv1" onClose={vi.fn()} />);
      expect(screen.getByText(/In Progress/i)).toBeInTheDocument();
    });

    it("renders empty content fallback for JSON with no text", () => {
      vi.spyOn(useGroupsHook, "useGroupConversation").mockReturnValue({
        isLoading: false,
        isError: false,
        data: {
          id: "conv1",
          originalQuestion: "Question?",
          state: "COMPLETED",
          transcript: [{
            speakerAgentId: "agent-1",
            speakerDisplayName: "Agent 1",
            type: "OPINION",
            content: '{"output":[]}',
            phaseIndex: 0,
            timestamp: "2024-06-01T10:00:00Z",
          }],
        },
      } as any);

      renderWithProviders(<ConversationViewer groupId="grp1" conversationId="conv1" onClose={vi.fn()} />);
      expect(screen.getByText(/No content/i)).toBeInTheDocument();
    });

    it("calls onClose when close button clicked", async () => {
      const user = userEvent.setup();
      const closeFn = vi.fn();
      vi.spyOn(useGroupsHook, "useGroupConversation").mockReturnValue({
        isLoading: false,
        isError: false,
        data: {
          id: "conv1",
          originalQuestion: "Question?",
          state: "COMPLETED",
          transcript: [],
        },
      } as any);

      renderWithProviders(<ConversationViewer groupId="grp1" conversationId="conv1" onClose={closeFn} />);
      
      const closeBtn = screen.getByRole("button", { name: /close/i });
      await user.click(closeBtn);
      expect(closeFn).toHaveBeenCalled();
    });
  });
});
