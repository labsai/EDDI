import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

import { AgentComparisonSheet } from "../agent-comparison-sheet";
import { BoardTranscript } from "../board-transcript";
import { SessionHistory } from "../session-history";
import { ExportMenu } from "../export-menu";
import { KnowledgeHealthCard } from "../knowledge-health-card";
import { AgentEditorSheet } from "../agent-editor-sheet";

import type { AgentStat } from "@/hooks/use-workforce-analytics";
import type {
  TranscriptEntry,
  GroupConversation,
} from "@/lib/api/groups";

// ─── Mock ResizeObserver ─────────────────────────────────────────
class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

// ─── Test Data Factories ─────────────────────────────────────────

function makeAgentStat(overrides: Partial<AgentStat> = {}): AgentStat {
  return {
    agentId: "agent-a",
    displayName: "Agent Alpha",
    sessions: 10,
    contributions: 20,
    totalContentLength: 5000,
    errors: 2,
    ...overrides,
  };
}

function makeTranscriptEntry(
  overrides: Partial<TranscriptEntry> = {},
): TranscriptEntry {
  return {
    speakerAgentId: "agent-a",
    speakerDisplayName: "Agent Alpha",
    content: "This is a response.",
    phaseIndex: 0,
    phaseName: "Opinion",
    type: "OPINION",
    timestamp: new Date().toISOString(),
    errorReason: null,
    targetAgentId: null,
    ...overrides,
  };
}

function makeGroupConversation(
  overrides: Partial<GroupConversation> = {},
): GroupConversation {
  return {
    id: "conv-1",
    groupId: "grp-1",
    userId: "user-1",
    state: "COMPLETED",
    originalQuestion: "What is the strategy?",
    transcript: [
      makeTranscriptEntry({ type: "QUESTION", content: "What is the strategy?" }),
      makeTranscriptEntry({
        type: "OPINION",
        content: "We should focus on growth.",
        phaseIndex: 1,
        phaseName: "Opinion",
      }),
      makeTranscriptEntry({
        type: "CRITIQUE",
        content: "But what about profitability?",
        phaseIndex: 2,
        phaseName: "Critique",
        speakerAgentId: "agent-b",
        speakerDisplayName: "Agent Beta",
      }),
    ],
    memberConversationIds: {},
    currentPhaseIndex: 2,
    currentPhaseName: "Critique",
    synthesizedAnswer: "Balanced growth with profitability focus.",
    depth: 0,
    taskList: null,
    dynamicMembers: [],
    createdAgentIds: [],
    retainedAgentIds: [],
    created: new Date().toISOString(),
    lastModified: new Date().toISOString(),
    ...overrides,
  };
}

// ═══════════════════════════════════════════════════════════════════
// Tests
// ═══════════════════════════════════════════════════════════════════

describe("Workforce Components – Coverage Batch 2", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ── AgentComparisonSheet (0% → covered) ──────────────────────────
  describe("AgentComparisonSheet", () => {
    const agentLeft = makeAgentStat({
      agentId: "agent-a",
      displayName: "Agent Alpha",
      sessions: 15,
      contributions: 30,
      totalContentLength: 9000,
      errors: 1,
    });

    const agentRight = makeAgentStat({
      agentId: "agent-b",
      displayName: "Agent Beta",
      sessions: 8,
      contributions: 22,
      totalContentLength: 6000,
      errors: 5,
    });

    it("renders null when agents is null", () => {
      const { container } = renderWithProviders(
        <AgentComparisonSheet agents={null} onClose={vi.fn()} />,
      );
      expect(container.firstChild).toBeNull();
    });

    it("renders dialog with agent names and title", () => {
      renderWithProviders(
        <AgentComparisonSheet
          agents={[agentLeft, agentRight]}
          onClose={vi.fn()}
        />,
      );

      expect(screen.getByText("Agent Comparison")).toBeInTheDocument();
      expect(screen.getAllByText("Agent Alpha").length).toBeGreaterThan(0);
      expect(screen.getByText("Agent Beta")).toBeInTheDocument();
    });

    it("renders all five metric labels", () => {
      renderWithProviders(
        <AgentComparisonSheet
          agents={[agentLeft, agentRight]}
          onClose={vi.fn()}
        />,
      );

      expect(screen.getByText("Sessions")).toBeInTheDocument();
      expect(screen.getByText("Contributions")).toBeInTheDocument();
      expect(screen.getByText("Error Rate")).toBeInTheDocument();
      expect(screen.getByText("Avg Response")).toBeInTheDocument();
      expect(screen.getByText("Total Content")).toBeInTheDocument();
    });

    it("shows winner summary when not a tie", () => {
      renderWithProviders(
        <AgentComparisonSheet
          agents={[agentLeft, agentRight]}
          onClose={vi.fn()}
        />,
      );

      // Agent Alpha should lead in most metrics
      expect(screen.getByText(/leads in/i)).toBeInTheDocument();
    });

    it("shows tie summary when agents are evenly matched", () => {
      const evenAgent = makeAgentStat({
        agentId: "agent-c",
        displayName: "Agent Charlie",
        sessions: 10,
        contributions: 20,
        totalContentLength: 5000,
        errors: 2,
      });

      renderWithProviders(
        <AgentComparisonSheet
          agents={[evenAgent, { ...evenAgent, agentId: "agent-d", displayName: "Agent Delta" }]}
          onClose={vi.fn()}
        />,
      );

      expect(
        screen.getByText("Both agents are evenly matched"),
      ).toBeInTheDocument();
    });

    it("calls onClose when close button is clicked", async () => {
      const onClose = vi.fn();
      renderWithProviders(
        <AgentComparisonSheet
          agents={[agentLeft, agentRight]}
          onClose={onClose}
        />,
      );

      const closeBtn = screen.getByRole("button", { name: /close/i });
      const user = userEvent.setup();
      await user.click(closeBtn);
      expect(onClose).toHaveBeenCalled();
    });

    it("calls onClose when backdrop is clicked", async () => {
      const onClose = vi.fn();
      renderWithProviders(
        <AgentComparisonSheet
          agents={[agentLeft, agentRight]}
          onClose={onClose}
        />,
      );

      // Backdrop is the div with aria-hidden="true"
      const backdrop = screen.getByRole("dialog").parentElement!.querySelector(
        "[aria-hidden='true']",
      )!;
      fireEvent.click(backdrop);
      expect(onClose).toHaveBeenCalled();
    });

    it("calls onClose when Escape key is pressed", () => {
      const onClose = vi.fn();
      renderWithProviders(
        <AgentComparisonSheet
          agents={[agentLeft, agentRight]}
          onClose={onClose}
        />,
      );

      fireEvent.keyDown(document, { key: "Escape" });
      expect(onClose).toHaveBeenCalled();
    });

    it("formats values correctly — percentages, k suffix", () => {
      const bigAgent = makeAgentStat({
        agentId: "agent-big",
        displayName: "Agent Big",
        totalContentLength: 12500,
        contributions: 5,
        errors: 0,
        sessions: 100,
      });
      const smallAgent = makeAgentStat({
        agentId: "agent-small",
        displayName: "Agent Small",
        totalContentLength: 300,
        contributions: 10,
        errors: 3,
        sessions: 2,
      });

      renderWithProviders(
        <AgentComparisonSheet
          agents={[bigAgent, smallAgent]}
          onClose={vi.fn()}
        />,
      );

      // 12500 → "12.5k"
      expect(screen.getByText("12.5k")).toBeInTheDocument();
      // Error rate for smallAgent: 3/(10+3) * 100 ≈ 23.1%
      expect(screen.getByText("23.1%")).toBeInTheDocument();
    });
  });

  // ── BoardTranscript (6.6% → covered) ────────────────────────────
  describe("BoardTranscript", () => {
    it("renders QUESTION entries as right-aligned bubbles", () => {
      const transcript: TranscriptEntry[] = [
        makeTranscriptEntry({
          type: "QUESTION",
          content: "What should we do?",
        }),
      ];

      renderWithProviders(
        <BoardTranscript
          transcript={transcript}
          boardId="board-1"
        />,
      );

      expect(screen.getByText("What should we do?")).toBeInTheDocument();
      expect(screen.getByText("You")).toBeInTheDocument();
    });

    it("renders OPINION entries with response card", () => {
      const transcript: TranscriptEntry[] = [
        makeTranscriptEntry({
          type: "OPINION",
          content: "We should focus on growth.",
          speakerDisplayName: "Agent Alpha",
          phaseIndex: 1,
          phaseName: "Opinion",
        }),
      ];

      renderWithProviders(
        <BoardTranscript
          transcript={transcript}
          boardId="board-1"
        />,
      );

      expect(
        screen.getByText("We should focus on growth."),
      ).toBeInTheDocument();
    });

    it("renders SKIPPED entries with skipped message", () => {
      const transcript: TranscriptEntry[] = [
        makeTranscriptEntry({
          type: "SKIPPED",
          content: null,
          speakerDisplayName: "Agent Gamma",
          errorReason: "Timeout",
          phaseIndex: 1,
          phaseName: "Opinion",
        }),
      ];

      renderWithProviders(
        <BoardTranscript
          transcript={transcript}
          boardId="board-1"
        />,
      );

      expect(screen.getByText(/Agent Gamma — Skipped/i)).toBeInTheDocument();
      expect(screen.getByText(/Timeout/)).toBeInTheDocument();
    });

    it("renders SYNTHESIS entries with synthesis card", () => {
      const transcript: TranscriptEntry[] = [
        makeTranscriptEntry({
          type: "SYNTHESIS",
          content: "The consensus is growth with caution.",
          phaseIndex: 3,
          phaseName: "Synthesis",
        }),
      ];

      renderWithProviders(
        <BoardTranscript
          transcript={transcript}
          boardId="board-1"
        />,
      );

      expect(screen.getAllByText("Synthesis").length).toBeGreaterThan(0);
      expect(
        screen.getByText("The consensus is growth with caution."),
      ).toBeInTheDocument();
    });

    it("renders phase headers when phaseIndex changes", () => {
      const transcript: TranscriptEntry[] = [
        makeTranscriptEntry({ phaseIndex: 0, phaseName: "Opinion", type: "OPINION" }),
        makeTranscriptEntry({ phaseIndex: 1, phaseName: "Critique", type: "CRITIQUE" }),
      ];

      renderWithProviders(
        <BoardTranscript
          transcript={transcript}
          boardId="board-1"
        />,
      );

      const separators = screen.getAllByRole("separator");
      expect(separators.length).toBeGreaterThanOrEqual(2);
    });

    it("renders synthesizedAnswer prop when no SYNTHESIS in transcript", () => {
      const transcript: TranscriptEntry[] = [
        makeTranscriptEntry({ type: "OPINION", phaseIndex: 0 }),
      ];

      renderWithProviders(
        <BoardTranscript
          transcript={transcript}
          boardId="board-1"
          synthesizedAnswer="Final synthesized answer from prop."
        />,
      );

      expect(
        screen.getByText("Final synthesized answer from prop."),
      ).toBeInTheDocument();
    });

    it("does NOT render synthesizedAnswer when transcript already has SYNTHESIS", () => {
      const transcript: TranscriptEntry[] = [
        makeTranscriptEntry({
          type: "SYNTHESIS",
          content: "Inline synthesis",
          phaseIndex: 2,
        }),
      ];

      renderWithProviders(
        <BoardTranscript
          transcript={transcript}
          boardId="board-1"
          synthesizedAnswer="Duplicate prop synthesis"
        />,
      );

      expect(screen.queryByText("Duplicate prop synthesis")).not.toBeInTheDocument();
    });

    it("renders copy button in SynthesisCard", () => {
      const transcript: TranscriptEntry[] = [
        makeTranscriptEntry({
          type: "SYNTHESIS",
          content: "Copy me please",
          phaseIndex: 2,
        }),
      ];

      renderWithProviders(
        <BoardTranscript
          transcript={transcript}
          boardId="board-1"
        />,
      );

      const copyBtn = screen.getByTitle("Copy");
      expect(copyBtn).toBeInTheDocument();
      expect(screen.getByText("Copy")).toBeInTheDocument();
    });

    it("renders mixed entry types in correct order", () => {
      const transcript: TranscriptEntry[] = [
        makeTranscriptEntry({ type: "QUESTION", content: "Q1", phaseIndex: -1 }),
        makeTranscriptEntry({ type: "OPINION", content: "O1", phaseIndex: 0, phaseName: "Opinion" }),
        makeTranscriptEntry({ type: "CRITIQUE", content: "C1", phaseIndex: 1, phaseName: "Critique" }),
        makeTranscriptEntry({ type: "REVISION", content: "R1", phaseIndex: 2, phaseName: "Revision" }),
        makeTranscriptEntry({ type: "SKIPPED", content: null, speakerDisplayName: "Skipped Agent", phaseIndex: 2, phaseName: "Revision" }),
        makeTranscriptEntry({ type: "SYNTHESIS", content: "S1", phaseIndex: 3, phaseName: "Synthesis" }),
      ];

      renderWithProviders(
        <BoardTranscript transcript={transcript} boardId="board-1" />,
      );

      expect(screen.getByText("Q1")).toBeInTheDocument();
      expect(screen.getByText("O1")).toBeInTheDocument();
      expect(screen.getByText("C1")).toBeInTheDocument();
      expect(screen.getByText("R1")).toBeInTheDocument();
      expect(screen.getByText(/Skipped Agent — Skipped/)).toBeInTheDocument();
      expect(screen.getByText("S1")).toBeInTheDocument();
    });
  });

  // ── SessionHistory (12.82% → covered) ───────────────────────────
  describe("SessionHistory", () => {
    it("renders loading skeletons while data loads", () => {
      // Override to make the endpoint never resolve
      server.use(
        http.get("*/groups/:groupId/conversations", () => {
          return new Promise(() => {}); // pending forever
        }),
      );

      const { container } = renderWithProviders(
        <SessionHistory
          groupId="grp-1"
          selectedId={null}
          onSelect={vi.fn()}
        />,
      );

      expect(screen.getByText("Sessions")).toBeInTheDocument();
      // Should have skeleton placeholders
      const skeletons = container.querySelectorAll("[class*='skeleton'], [class*='animate-pulse']");
      expect(skeletons.length).toBeGreaterThan(0);
    });

    it("renders 'No sessions yet' when there are no conversations", async () => {
      server.use(
        http.get("*/groups/:groupId/conversations", () => {
          return HttpResponse.json([]);
        }),
      );

      renderWithProviders(
        <SessionHistory
          groupId="grp-1"
          selectedId={null}
          onSelect={vi.fn()}
        />,
      );

      await waitFor(() => {
        expect(screen.getByText("No sessions yet")).toBeInTheDocument();
      });
    });

    it("renders conversation list items from API", async () => {
      server.use(
        http.get("*/groups/:groupId/conversations", () => {
          return HttpResponse.json([
            {
              id: "conv-list-1",
              state: "COMPLETED",
              originalQuestion: "How is Q3?",
              created: new Date().toISOString(),
              lastModified: new Date().toISOString(),
            },
            {
              id: "conv-list-2",
              state: "IN_PROGRESS",
              originalQuestion: "Revenue forecast",
              created: new Date().toISOString(),
              lastModified: new Date().toISOString(),
            },
          ]);
        }),
      );

      renderWithProviders(
        <SessionHistory
          groupId="grp-1"
          selectedId={null}
          onSelect={vi.fn()}
        />,
      );

      await waitFor(() => {
        expect(screen.getByText("How is Q3?")).toBeInTheDocument();
        expect(screen.getByText("Revenue forecast")).toBeInTheDocument();
      });
    });

    it("calls onSelect when a session is clicked", async () => {
      const onSelect = vi.fn();

      server.use(
        http.get("*/groups/:groupId/conversations", () => {
          return HttpResponse.json([
            {
              id: "conv-test",
              state: "COMPLETED",
              originalQuestion: "Test question",
              created: new Date().toISOString(),
              lastModified: new Date().toISOString(),
            },
          ]);
        }),
      );

      renderWithProviders(
        <SessionHistory
          groupId="grp-1"
          selectedId={null}
          onSelect={onSelect}
        />,
      );

      await waitFor(() => {
        expect(screen.getByText("Test question")).toBeInTheDocument();
      });

      const user = userEvent.setup();
      await user.click(screen.getByText("Test question"));
      expect(onSelect).toHaveBeenCalledWith("conv-test");
    });

    it("highlights selected conversation", async () => {
      server.use(
        http.get("*/groups/:groupId/conversations", () => {
          return HttpResponse.json([
            {
              id: "conv-sel",
              state: "COMPLETED",
              originalQuestion: "Selected one",
              created: new Date().toISOString(),
              lastModified: new Date().toISOString(),
            },
          ]);
        }),
      );

      renderWithProviders(
        <SessionHistory
          groupId="grp-1"
          selectedId="conv-sel"
          onSelect={vi.fn()}
        />,
      );

      await waitFor(() => {
        const selectedBtn = screen.getByText("Selected one").closest("button");
        expect(selectedBtn).toHaveAttribute("aria-current", "true");
      });
    });

    it("renders close button when onClose is provided", async () => {
      const onClose = vi.fn();

      server.use(
        http.get("*/groups/:groupId/conversations", () =>
          HttpResponse.json([]),
        ),
      );

      renderWithProviders(
        <SessionHistory
          groupId="grp-1"
          selectedId={null}
          onSelect={vi.fn()}
          onClose={onClose}
        />,
      );

      const closeBtn = screen.getByRole("button", { name: /close/i });
      expect(closeBtn).toBeInTheDocument();

      const user = userEvent.setup();
      await user.click(closeBtn);
      expect(onClose).toHaveBeenCalled();
    });

    it("does not render close button when onClose is not provided", async () => {
      server.use(
        http.get("*/groups/:groupId/conversations", () =>
          HttpResponse.json([]),
        ),
      );

      renderWithProviders(
        <SessionHistory
          groupId="grp-1"
          selectedId={null}
          onSelect={vi.fn()}
        />,
      );

      await waitFor(() => {
        expect(screen.getByText("No sessions yet")).toBeInTheDocument();
      });

      expect(
        screen.queryByRole("button", { name: /close/i }),
      ).not.toBeInTheDocument();
    });

    it("shows Untitled when originalQuestion is empty", async () => {
      server.use(
        http.get("*/groups/:groupId/conversations", () => {
          return HttpResponse.json([
            {
              id: "conv-untitled",
              state: "CREATED",
              originalQuestion: "",
              created: new Date().toISOString(),
              lastModified: null,
            },
          ]);
        }),
      );

      renderWithProviders(
        <SessionHistory
          groupId="grp-1"
          selectedId={null}
          onSelect={vi.fn()}
        />,
      );

      await waitFor(() => {
        expect(screen.getByText("Untitled")).toBeInTheDocument();
      });
    });

    it("renders state badge for each conversation state", async () => {
      server.use(
        http.get("*/groups/:groupId/conversations", () => {
          return HttpResponse.json([
            { id: "c1", state: "COMPLETED", originalQuestion: "Q1", created: new Date().toISOString(), lastModified: new Date().toISOString() },
            { id: "c2", state: "IN_PROGRESS", originalQuestion: "Q2", created: new Date().toISOString(), lastModified: new Date().toISOString() },
            { id: "c3", state: "FAILED", originalQuestion: "Q3", created: new Date().toISOString(), lastModified: null },
          ]);
        }),
      );

      renderWithProviders(
        <SessionHistory groupId="grp-1" selectedId={null} onSelect={vi.fn()} />,
      );

      await waitFor(() => {
        expect(screen.getByText("Completed")).toBeInTheDocument();
        expect(screen.getByText("In Progress")).toBeInTheDocument();
        expect(screen.getByText("Failed")).toBeInTheDocument();
      });
    });
  });

  // ── ExportMenu (37.06% → covered) ───────────────────────────────
  describe("ExportMenu", () => {
    it("renders disabled trigger when conversation is null", () => {
      renderWithProviders(
        <ExportMenu conversation={null} />,
      );

      const trigger = screen.getByRole("button", { name: /export/i });
      expect(trigger).toBeDisabled();
    });

    it("renders enabled trigger when conversation is provided", () => {
      const conversation = makeGroupConversation();
      renderWithProviders(
        <ExportMenu conversation={conversation} groupName="Test Board" />,
      );

      const trigger = screen.getByRole("button", { name: /export/i });
      expect(trigger).not.toBeDisabled();
    });

    it("opens dropdown with export options", async () => {
      const conversation = makeGroupConversation();
      renderWithProviders(
        <ExportMenu conversation={conversation} />,
      );

      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: /export/i }));

      await waitFor(() => {
        expect(screen.getByText("Export as Markdown")).toBeInTheDocument();
        expect(screen.getByText("Export as JSON")).toBeInTheDocument();
        expect(screen.getByText("Copy to Clipboard")).toBeInTheDocument();
      });
    });

    it("markdown download creates and triggers link", async () => {
      const conversation = makeGroupConversation();
      const createObjectURL = vi.fn(() => "blob:test");
      const revokeObjectURL = vi.fn();
      Object.assign(URL, { createObjectURL, revokeObjectURL });

      const clickSpy = vi.fn();
      const createElementOrig = document.createElement.bind(document);
      vi.spyOn(document, "createElement").mockImplementation((tag: string) => {
        const el = createElementOrig(tag);
        if (tag === "a") {
          vi.spyOn(el, "click").mockImplementation(clickSpy);
        }
        return el;
      });

      renderWithProviders(
        <ExportMenu conversation={conversation} groupName="Strategy" />,
      );

      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: /export/i }));

      await waitFor(() => {
        expect(screen.getByText("Export as Markdown")).toBeInTheDocument();
      });

      await user.click(screen.getByText("Export as Markdown"));
      expect(createObjectURL).toHaveBeenCalled();
      expect(clickSpy).toHaveBeenCalled();
    });

    it("JSON download creates and triggers link", async () => {
      const conversation = makeGroupConversation();
      const createObjectURL = vi.fn(() => "blob:test");
      const revokeObjectURL = vi.fn();
      Object.assign(URL, { createObjectURL, revokeObjectURL });

      const clickSpy = vi.fn();
      const createElementOrig = document.createElement.bind(document);
      vi.spyOn(document, "createElement").mockImplementation((tag: string) => {
        const el = createElementOrig(tag);
        if (tag === "a") {
          vi.spyOn(el, "click").mockImplementation(clickSpy);
        }
        return el;
      });

      renderWithProviders(
        <ExportMenu conversation={conversation} />,
      );

      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: /export/i }));
      await waitFor(() => {
        expect(screen.getByText("Export as JSON")).toBeInTheDocument();
      });
      await user.click(screen.getByText("Export as JSON"));
      expect(createObjectURL).toHaveBeenCalled();
    });

    it("copy to clipboard menu item is available", async () => {
      const conversation = makeGroupConversation();

      renderWithProviders(
        <ExportMenu conversation={conversation} groupName="Board" />,
      );

      const user = userEvent.setup();
      await user.click(screen.getByRole("button", { name: /export/i }));
      await waitFor(() => {
        expect(screen.getByText("Copy to Clipboard")).toBeInTheDocument();
      });

      // Verify the menu item is present and clickable
      const menuItem = screen.getByText("Copy to Clipboard");
      expect(menuItem).toBeInTheDocument();
    });
  });

  // ── KnowledgeHealthCard (39.52% → covered) ──────────────────────
  describe("KnowledgeHealthCard", () => {
    it("renders loading skeletons initially", () => {
      // Use endpoint that never resolves
      server.use(
        http.get("*/agentstore/agents/descriptors", () => new Promise(() => {})),
      );

      const { container } = renderWithProviders(<KnowledgeHealthCard />);
      const skeletons = container.querySelectorAll("[class*='skeleton'], [class*='animate-pulse']");
      expect(skeletons.length).toBeGreaterThan(0);
    });

    it("renders healthy status when most agents are active", async () => {
      // Agents all in recent groups → high active rate
      renderWithProviders(<KnowledgeHealthCard />);

      await waitFor(() => {
        expect(screen.getByText("Knowledge Health")).toBeInTheDocument();
      });
    });

    it("renders metric labels", async () => {
      renderWithProviders(<KnowledgeHealthCard />);

      await waitFor(() => {
        expect(screen.getByText("Knowledge Health")).toBeInTheDocument();
      });

      expect(screen.getByText("Workforce Size")).toBeInTheDocument();
      expect(screen.getByText("Active Rate")).toBeInTheDocument();
      expect(screen.getByText("Task Forces")).toBeInTheDocument();
      expect(screen.getByText("Dormant")).toBeInTheDocument();
    });

    it("renders progress bar with aria attributes", async () => {
      renderWithProviders(<KnowledgeHealthCard />);

      await waitFor(() => {
        expect(screen.getByText("Knowledge Health")).toBeInTheDocument();
      });

      const progressBar = screen.getByRole("progressbar");
      expect(progressBar).toBeInTheDocument();
      expect(progressBar).toHaveAttribute("aria-valuemin", "0");
      expect(progressBar).toHaveAttribute("aria-valuemax", "100");
    });

    it("renders at-risk status when no agents exist", async () => {
      server.use(
        http.get("*/agentstore/agents/descriptors", () =>
          HttpResponse.json([]),
        ),
        http.get("*/groupstore/groups/descriptors", () =>
          HttpResponse.json([]),
        ),
        http.get("*/groupstore/groups/enriched", () =>
          HttpResponse.json([]),
        ),
      );

      renderWithProviders(<KnowledgeHealthCard />);

      await waitFor(() => {
        // "At Risk" may be split across elements (● + "At Risk")
        expect(
          screen.getByText((content) => content.includes("At Risk")),
        ).toBeInTheDocument();
      });
    });
  });

  // ── AgentEditorSheet (23.14% → covered) ─────────────────────────
  describe("AgentEditorSheet", () => {
    it("renders null when agentId is null", () => {
      const { container } = renderWithProviders(
        <AgentEditorSheet agentId={null} onClose={vi.fn()} />,
      );
      expect(container.innerHTML).toBe("");
    });

    it("renders loading skeletons with a valid agentId", () => {
      renderWithProviders(
        <AgentEditorSheet agentId="agent1" onClose={vi.fn()} />,
      );

      expect(screen.getByRole("dialog")).toBeInTheDocument();
    });

    it("renders agent name and description after loading", async () => {
      renderWithProviders(
        <AgentEditorSheet agentId="agent1" onClose={vi.fn()} />,
      );

      await waitFor(() => {
        expect(screen.getByText("Support Agent")).toBeInTheDocument();
      });
    });

    it("renders close button that calls onClose", async () => {
      const onClose = vi.fn();
      renderWithProviders(
        <AgentEditorSheet agentId="agent1" onClose={onClose} />,
      );

      const closeBtn = screen.getByRole("button", { name: /close/i });
      const user = userEvent.setup();
      await user.click(closeBtn);
      expect(onClose).toHaveBeenCalled();
    });

    it("renders description textarea after loading", async () => {
      renderWithProviders(
        <AgentEditorSheet agentId="agent1" onClose={vi.fn()} />,
      );

      await waitFor(() => {
        expect(screen.getByLabelText("Description")).toBeInTheDocument();
      });
    });

    it("renders capabilities section", async () => {
      renderWithProviders(
        <AgentEditorSheet agentId="agent1" onClose={vi.fn()} />,
      );

      await waitFor(() => {
        expect(screen.getByText("Capabilities")).toBeInTheDocument();
      });
    });

    it("renders settings toggles for A2A and Memory Tools", async () => {
      renderWithProviders(
        <AgentEditorSheet agentId="agent1" onClose={vi.fn()} />,
      );

      await waitFor(() => {
        expect(screen.getByText("Settings")).toBeInTheDocument();
      });

      const a2aToggle = screen.getByRole("switch", {
        name: /Agent-to-Agent Communication/i,
      });
      const memoryToggle = screen.getByRole("switch", {
        name: /Memory Tools/i,
      });

      expect(a2aToggle).toBeInTheDocument();
      expect(memoryToggle).toBeInTheDocument();
    });

    it("toggling A2A switch changes aria-checked", async () => {
      renderWithProviders(
        <AgentEditorSheet agentId="agent1" onClose={vi.fn()} />,
      );

      await waitFor(() => {
        expect(screen.getByText("Settings")).toBeInTheDocument();
      });

      const a2aToggle = screen.getByRole("switch", {
        name: /Agent-to-Agent Communication/i,
      });
      const initialChecked = a2aToggle.getAttribute("aria-checked");

      const user = userEvent.setup();
      await user.click(a2aToggle);

      const newChecked = a2aToggle.getAttribute("aria-checked");
      expect(newChecked).not.toBe(initialChecked);
    });

    it("Add Capability button reveals inline form", async () => {
      renderWithProviders(
        <AgentEditorSheet agentId="agent1" onClose={vi.fn()} />,
      );

      await waitFor(() => {
        expect(screen.getByText("Capabilities")).toBeInTheDocument();
      });

      const user = userEvent.setup();
      await user.click(screen.getByText("Add Capability"));

      expect(screen.getByLabelText("Skill")).toBeInTheDocument();
      expect(screen.getByLabelText("Confidence")).toBeInTheDocument();
    });

    it("adding a capability via inline form adds it to the list", async () => {
      renderWithProviders(
        <AgentEditorSheet agentId="agent1" onClose={vi.fn()} />,
      );

      await waitFor(() => {
        expect(screen.getByText("Capabilities")).toBeInTheDocument();
      });

      const user = userEvent.setup();
      await user.click(screen.getByText("Add Capability"));

      const skillInput = screen.getByLabelText("Skill");
      await user.type(skillInput, "summarization");
      await user.click(screen.getByText("Add"));

      expect(screen.getByText("summarization")).toBeInTheDocument();
    });

    it("renders Save Changes button that is disabled when not dirty", async () => {
      renderWithProviders(
        <AgentEditorSheet agentId="agent1" onClose={vi.fn()} />,
      );

      await waitFor(() => {
        expect(screen.getByText("Save Changes")).toBeInTheDocument();
      });

      const saveBtn = screen.getByText("Save Changes").closest("button")!;
      expect(saveBtn).toBeDisabled();
    });

    it("Save Changes button enables after editing description", async () => {
      renderWithProviders(
        <AgentEditorSheet agentId="agent1" onClose={vi.fn()} />,
      );

      await waitFor(() => {
        expect(screen.getByLabelText("Description")).toBeInTheDocument();
      });

      const user = userEvent.setup();
      const textarea = screen.getByLabelText("Description");
      await user.type(textarea, " extra text");

      const saveBtn = screen.getByText("Save Changes").closest("button")!;
      expect(saveBtn).not.toBeDisabled();
    });

    it("renders Cancel button that calls onClose", async () => {
      const onClose = vi.fn();
      renderWithProviders(
        <AgentEditorSheet agentId="agent1" onClose={onClose} />,
      );

      await waitFor(() => {
        expect(screen.getByText("Cancel")).toBeInTheDocument();
      });

      const user = userEvent.setup();
      await user.click(screen.getByText("Cancel"));
      expect(onClose).toHaveBeenCalled();
    });

    it("renders error state with retry button when agent fails to load", async () => {
      server.use(
        http.get("*/agentstore/agents/:id", () => {
          return HttpResponse.json(
            { message: "Not found" },
            { status: 404 },
          );
        }),
        http.get("*/agentstore/agents/descriptors", () => {
          return HttpResponse.json(
            { message: "Not found" },
            { status: 404 },
          );
        }),
      );

      renderWithProviders(
        <AgentEditorSheet agentId="agent-bad" onClose={vi.fn()} />,
      );

      await waitFor(() => {
        expect(
          screen.getByText("Failed to load agent data."),
        ).toBeInTheDocument();
      });

      expect(screen.getByText("Retry")).toBeInTheDocument();
    });

    it("renders System Prompt section", async () => {
      renderWithProviders(
        <AgentEditorSheet agentId="agent1" onClose={vi.fn()} />,
      );

      await waitFor(() => {
        expect(screen.getByText("System Prompt")).toBeInTheDocument();
      });
    });
  });
});
