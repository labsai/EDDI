import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/test-utils";
// The global test setup already runs this server with ALL handler groups —
// including the group-conversation GET the viewer fetches. A file-local
// setupServer(...handlers) would shadow it with a subset that lacks them.
import { server } from "@/test/mocks/server";
import { ConversationViewer } from "@/components/workforce/conversation-viewer";

/**
 * The history viewer showed the newer collaboration modes' transcripts but not
 * their outcomes: a completed TASK_FORCE session had no task board and a DEBATE
 * had no verdict card, both of which the Manager page rendered for the same
 * conversation. These tests pin the parity.
 */

function conversation(extra: Record<string, unknown>) {
  return {
    id: "gconv-rich",
    groupId: "group1",
    userId: "manager-user",
    state: "COMPLETED",
    originalQuestion: "Ship it?",
    transcript: [
      {
        speakerAgentId: "agent-mod",
        speakerDisplayName: "Moderator",
        content: "Judge reasoning…",
        phaseIndex: 1,
        phaseName: "Judgment",
        type: "SYNTHESIS",
        timestamp: new Date().toISOString(),
        errorReason: null,
        targetAgentId: null,
      },
    ],
    memberConversationIds: {},
    currentPhaseIndex: 1,
    currentPhaseName: "Judgment",
    synthesizedAnswer: "Judge reasoning…",
    depth: 0,
    taskList: null,
    dynamicMembers: [],
    createdAgentIds: [],
    retainedAgentIds: [],
    created: new Date().toISOString(),
    lastModified: new Date().toISOString(),
    ...extra,
  };
}

describe("ConversationViewer — structured outcomes", () => {
  it("renders the persisted task board for a session that carries a task list", async () => {
    server.use(
      http.get("*/groups/:groupId/conversations/:convId", () =>
        HttpResponse.json(
          conversation({
            taskList: {
              tasks: [
                {
                  id: "t1",
                  subject: "Provision EU cluster",
                  assignedAgentId: "agent-4",
                  assignedDisplayName: "Tech Lead",
                  priority: 1,
                  status: "VERIFIED",
                  verified: true,
                  verificationNote: "Cluster reachable",
                  createdByAgentId: null,
                },
                {
                  id: "t2",
                  subject: "Draft DPA",
                  assignedAgentId: "agent-5",
                  assignedDisplayName: "Legal Counsel",
                  priority: 2,
                  status: "COMPLETED",
                  verified: false,
                  verificationNote: null,
                  createdByAgentId: "agent-1",
                },
              ],
              awardedBids: null,
            },
            memberDisplayNames: { "agent-1": "Marketing Expert" },
          }),
        ),
      ),
    );

    renderWithProviders(
      <ConversationViewer groupId="group1" conversationId="gconv-rich" />,
    );

    // Subject text appears in the task card (and its tooltip title) — presence
    // anywhere is what this test pins.
    await waitFor(() =>
      expect(screen.getAllByText("Provision EU cluster").length).toBeGreaterThan(0),
    );
    expect(screen.getAllByText("Draft DPA").length).toBeGreaterThan(0);
    // The board renders a desktop grid and a mobile list (CSS hides one, JSDOM
    // keeps both in the DOM) — so cards appear twice by test id.
    expect(screen.getAllByTestId("task-card-t1").length).toBeGreaterThan(0);
    expect(screen.getAllByTestId("task-card-t2").length).toBeGreaterThan(0);
  });

  it("renders the decision card before the synthesis", async () => {
    server.use(
      http.get("*/groups/:groupId/conversations/:convId", () =>
        HttpResponse.json(
          conversation({
            decision: {
              type: "VERDICT",
              winner: "PRO",
              outcome: "Proceed with the launch.",
              method: "judge",
              tally: { PRO: 2, CON: 1 },
              dissents: [
                { agentId: "a9", displayName: "Contrarian", position: "Timing is wrong." },
              ],
              decidedAtPhase: "Judgment",
              raw: null,
            },
          }),
        ),
      ),
    );

    renderWithProviders(
      <ConversationViewer groupId="group1" conversationId="gconv-rich" />,
    );

    const decision = await screen.findByTestId("decision-record");
    expect(screen.getByTestId("decision-winner")).toHaveTextContent("PRO");
    expect(screen.getByText("Timing is wrong.")).toBeInTheDocument();
    const synthesis = screen.getByText("Judge reasoning…");
    expect(
      decision.compareDocumentPosition(synthesis) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("keeps an unparsed judgment in the Markdown export", async () => {
    server.use(
      http.get("*/groups/:groupId/conversations/:convId", () =>
        HttpResponse.json(
          conversation({
            decision: {
              type: "NONE",
              winner: null,
              outcome: null,
              method: null,
              tally: null,
              dissents: [],
              decidedAtPhase: null,
              raw: "Unreadable judgment body.",
            },
          }),
        ),
      ),
    );

    // Capture what the export writes instead of downloading it.
    let exported = "";
    const originalCreate = URL.createObjectURL;
    URL.createObjectURL = ((blob: Blob) => {
      // Blob.text() is async; the export path is sync, so read the parts we
      // were handed via the constructor spy below instead.
      void blob;
      return "blob:mock";
    }) as typeof URL.createObjectURL;
    const OriginalBlob = globalThis.Blob;
    globalThis.Blob = class extends OriginalBlob {
      constructor(parts?: BlobPart[], options?: BlobPropertyBag) {
        super(parts, options);
        if (parts) exported = parts.join("");
      }
    } as typeof Blob;

    try {
      renderWithProviders(
        <ConversationViewer groupId="group1" conversationId="gconv-rich" />,
      );
      const exportBtn = await screen.findByLabelText("Export");
      exportBtn.click();
      await waitFor(() => expect(exported).toContain("## Decision (NONE)"));
      expect(exported).toContain("Unreadable judgment body.");
    } finally {
      URL.createObjectURL = originalCreate;
      globalThis.Blob = OriginalBlob;
    }
  });

  it("shows neither panel for a plain conversation", async () => {
    renderWithProviders(
      <ConversationViewer groupId="group1" conversationId="gconv1" />,
    );
    // The question renders in the header and as the question bubble.
    await waitFor(() =>
      expect(screen.getAllByText(/Should we expand/).length).toBeGreaterThan(0),
    );
    expect(screen.queryByTestId("decision-record")).not.toBeInTheDocument();
    expect(screen.queryByTestId("task-board-empty")).not.toBeInTheDocument();
  });
});
