import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useNavigate } from "react-router-dom";
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

function GoTo({ to }: { to: string }) {
  const navigate = useNavigate();
  return (
    <button type="button" onClick={() => navigate(to)}>
      go
    </button>
  );
}

function renderAt(path: string, next = "/manage/groups/grp2?version=1") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <QueryClientProvider client={createTestQueryClient()}>
        <GoTo to={next} />
        <Routes>
          <Route path="/manage/groups/:id" element={<GroupDetailPage />} />
        </Routes>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

const paused = (state: string) => ({
  id: "gconv-paused",
  groupId: "grp1",
  userId: "manager-user",
  state,
  pausedAt: state === "AWAITING_APPROVAL" ? new Date(Date.now() - 60_000).toISOString() : null,
  hitlPauseType: state === "AWAITING_APPROVAL" ? "PHASE" : null,
  pausedPhaseName: state === "AWAITING_APPROVAL" ? "Synthesis" : null,
  originalQuestion: "Fund it?",
  transcript: [],
  memberConversationIds: {},
  currentPhaseIndex: 1,
  currentPhaseName: "Synthesis",
  synthesizedAnswer: "Recommend funding.",
  availableActions: state === "REJECTED" ? ["close"] : [],
  depth: 0,
  taskList: null,
  dynamicMembers: [],
  createdAgentIds: [],
  retainedAgentIds: [],
  created: new Date(Date.now() - 600_000).toISOString(),
  lastModified: new Date().toISOString(),
});

describe("GroupDetailPage — a rejection is confirmed", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // The stream store is module-level; a previous test's stream must not leak.
    useGroupStreamStore.setState({ streams: {} });
  });

  /**
   * The backend ends a rejected run on the spot with `group_complete` carrying
   * REJECTED — it sends no `hitl_resume`. The page confirmed a decision only on
   * that ack, so a rejection was never confirmed at all.
   */
  it("toasts the rejection when the run ends as REJECTED without a resume ack", async () => {
    let decided = false;
    server.use(
      http.get("*/groups/:groupId/conversations", () =>
        HttpResponse.json([paused(decided ? "REJECTED" : "AWAITING_APPROVAL")]),
      ),
      http.get("*/groups/:groupId/conversations/:convId", () =>
        HttpResponse.json(paused(decided ? "REJECTED" : "AWAITING_APPROVAL")),
      ),
      http.post("*/groups/:groupId/conversations/:gcId/approve/stream", () => {
        decided = true;
        const encoder = new TextEncoder();
        const body = new ReadableStream({
          start(controller) {
            controller.enqueue(
              encoder.encode('event: group_complete\ndata: {"state":"REJECTED","synthesizedAnswer":null}\n\n'),
            );
            controller.close();
          },
        });
        return new HttpResponse(body, { headers: { "Content-Type": "text/event-stream" } });
      }),
    );
    renderAt("/manage/groups/grp1?version=1&conversation=gconv-paused");

    await userEvent.click(await screen.findByTestId("reject-button"));
    await userEvent.click(await screen.findByTestId("alert-dialog-confirm"));

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith("Rejected"));
    expect(toast.error).not.toHaveBeenCalled();
  });
});

describe("GroupDetailPage — 'New Discussion' belongs to one group", () => {
  beforeEach(() => useGroupStreamStore.setState({ streams: {} }));

  /**
   * The page stays mounted when the route moves to another group. A "New
   * Discussion" pressed on the first used to keep the second group's newest
   * discussion from being selected on arrival.
   */
  it("auto-selects the next group's newest discussion after New on the previous one", async () => {
    server.use(
      http.get("*/groups/:groupId/conversations", ({ params }) =>
        HttpResponse.json([
          {
            ...paused("COMPLETED"),
            id: `${String(params.groupId)}-newest`,
            groupId: String(params.groupId),
            originalQuestion: `Newest in ${String(params.groupId)}`,
          },
        ]),
      ),
      http.get("*/groups/:groupId/conversations/:convId", ({ params }) =>
        HttpResponse.json({ ...paused("COMPLETED"), id: String(params.convId) }),
      ),
    );
    renderAt("/manage/groups/grp1?version=1");

    await waitFor(() =>
      expect(screen.getByTestId("discussion-item-grp1-newest")).toHaveAttribute("aria-current", "true"),
    );
    await userEvent.click(screen.getAllByRole("button", { name: /new discussion/i })[0]!);
    await waitFor(() =>
      expect(screen.getByTestId("discussion-item-grp1-newest")).not.toHaveAttribute("aria-current"),
    );

    await userEvent.click(screen.getByRole("button", { name: "go" }));

    await waitFor(() =>
      expect(screen.getByTestId("discussion-item-grp2-newest")).toHaveAttribute("aria-current", "true"),
    );
  });
});
