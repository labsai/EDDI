import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";
import { createTestQueryClient } from "@/test/test-utils";
import { GroupDetailPage } from "@/pages/group-detail";
import { server } from "@/test/mocks/server";
import { useGroupStreamStore } from "@/hooks/use-group-discussion-stream";

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn(), info: vi.fn() },
}));

window.HTMLElement.prototype.scrollIntoView = vi.fn();
window.HTMLElement.prototype.scrollTo = vi.fn();

/**
 * The group page's approve was the one decision path that sent nothing to say
 * which pause it was for. It is bound now, the same way the approvals inbox
 * binds a group decision.
 */

const PAUSED_AT = "2026-07-01T10:00:00.000Z";

const pausedConversation = {
  id: "gconv-paused",
  groupId: "grp1",
  userId: "manager-user",
  state: "AWAITING_APPROVAL",
  pausedAt: PAUSED_AT,
  hitlPauseType: "PHASE",
  pausedPhaseName: "Synthesis",
  originalQuestion: "Fund it?",
  transcript: [],
  memberConversationIds: {},
  currentPhaseIndex: 1,
  currentPhaseName: "Synthesis",
  synthesizedAnswer: null,
  availableActions: [],
  depth: 0,
  taskList: null,
  dynamicMembers: [],
  createdAgentIds: [],
  retainedAgentIds: [],
  created: PAUSED_AT,
  lastModified: PAUSED_AT,
};

function serveConversation() {
  server.use(
    http.get("*/groups/:groupId/conversations", () => HttpResponse.json([pausedConversation])),
    http.get("*/groups/:groupId/conversations/:convId", () => HttpResponse.json(pausedConversation)),
  );
}

function recordApproves() {
  const bodies: Record<string, unknown>[] = [];
  server.use(
    http.post("*/groups/:groupId/conversations/:gcId/approve/stream", async ({ request }) => {
      bodies.push((await request.json()) as Record<string, unknown>);
      const body = new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode('event: group_complete\ndata: {"state":"REJECTED","synthesizedAnswer":null}\n\n'),
          );
          controller.close();
        },
      });
      return new HttpResponse(body, { headers: { "Content-Type": "text/event-stream" } });
    }),
  );
  return bodies;
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/manage/groups/grp1?version=1&conversation=gconv-paused"]}>
      <QueryClientProvider client={createTestQueryClient()}>
        <Routes>
          <Route path="/manage/groups/:id" element={<GroupDetailPage />} />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

async function approve() {
  const button = await screen.findByTestId("approve-button");
  await waitFor(() => expect(button).toBeEnabled());
  await userEvent.click(button);
  await userEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Approve" }));
}

describe("GroupDetailPage — the approve decision is bound to the pause shown", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useGroupStreamStore.setState({ streams: {} });
  });

  it("sends the pauseId the backend reports for the stored pause", async () => {
    serveConversation();
    server.use(
      http.get("*/groups/:groupId/conversations/:gcId/approval-status", () =>
        HttpResponse.json({ state: "AWAITING_APPROVAL", pausedAt: PAUSED_AT, pauseType: "PHASE", pauseId: "1782900000000" }),
      ),
    );
    const bodies = recordApproves();
    renderPage();
    await approve();
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ decision: { verdict: "APPROVED", pauseId: "1782900000000" } });
  });

  it("approves a backend that reports no pauseId when the pause start still matches", async () => {
    serveConversation();
    server.use(
      http.get("*/groups/:groupId/conversations/:gcId/approval-status", () =>
        HttpResponse.json({ state: "AWAITING_APPROVAL", pausedAt: PAUSED_AT, pauseType: "PHASE" }),
      ),
    );
    const bodies = recordApproves();
    renderPage();
    await approve();
    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ decision: { verdict: "APPROVED" } });
  });

  it("refuses, refreshes and does not approve when the discussion paused again", async () => {
    serveConversation();
    server.use(
      http.get("*/groups/:groupId/conversations/:gcId/approval-status", () =>
        HttpResponse.json({
          state: "AWAITING_APPROVAL",
          pausedAt: "2026-07-01T10:05:00.000Z",
          pauseType: "PHASE",
        }),
      ),
    );
    const bodies = recordApproves();
    renderPage();
    await approve();
    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith(expect.stringMatching(/changed since you opened it/)),
    );
    expect(bodies).toEqual([]);
    // The card is still there, not stuck in a resolving state.
    expect(await screen.findByTestId("approve-button")).toBeEnabled();
  });
});
