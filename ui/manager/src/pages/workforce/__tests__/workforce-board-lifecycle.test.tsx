import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useLocation, useNavigate, type NavigateFunction } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { createTestQueryClient, renderPage, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { useGroupStreamStore } from "@/hooks/use-group-discussion-stream";
import { WorkforceBoard } from "../workforce-board";

/**
 * How the board starts, stops and hands over a discussion: Stop and "+ New"
 * cancel it on the server, a settled stream gives way to the stored document,
 * a refused start can be recovered from, and a version-less link opens the
 * group's current version.
 */

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

const encoder = new TextEncoder();
const frame = (type: string, data: unknown) =>
  encoder.encode(`event: ${type}\ndata: ${JSON.stringify(data)}\n\n`);

/** A stream endpoint that opens the discussion and then stays open. */
function openDiscussionStream(gcId = "gc-live", onOpen?: () => void) {
  server.use(
    http.post("*/groups/:groupId/conversations/stream", () => {
      onOpen?.();
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(frame("group_start", { groupConversationId: gcId, question: "Ship it?" }));
          controller.enqueue(
            frame("phase_start", { phaseIndex: 0, phaseName: "Initial Opinions", phaseType: "OPINION" }),
          );
          controller.enqueue(
            frame("speaker_start", {
              agentId: "agent1",
              displayName: "Support Agent",
              phaseIndex: 0,
              phaseName: "Initial Opinions",
            }),
          );
          // Never closes: the discussion is still running.
        },
      });
      return new HttpResponse(body, { headers: { "Content-Type": "text/event-stream" } });
    }),
  );
}

function conversationDoc(id: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    groupId: "grp1",
    userId: "admin",
    state: "IN_PROGRESS",
    originalQuestion: "Ship it?",
    transcript: [],
    memberConversationIds: {},
    currentPhaseIndex: 0,
    currentPhaseName: "Initial Opinions",
    synthesizedAnswer: null,
    depth: 0,
    taskList: null,
    dynamicMembers: [],
    createdAgentIds: [],
    availableActions: [],
    created: new Date().toISOString(),
    lastModified: new Date().toISOString(),
    ...overrides,
  };
}

/**
 * The board on a router the test can drive. Switching task forces keeps the
 * page mounted (same route, new `:boardId`), which `renderPage` cannot do.
 */
function renderSwitchableBoard(path: string) {
  const router: { navigate?: NavigateFunction; search: string } = { search: "" };
  function RouterHandle() {
    router.navigate = useNavigate();
    router.search = useLocation().search;
    return null;
  }
  render(
    <MemoryRouter initialEntries={[path]}>
      <QueryClientProvider client={createTestQueryClient()}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <RouterHandle />
          <Routes>
            <Route path="/workforce/:boardId" element={<WorkforceBoard />} />
          </Routes>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
  return router;
}

async function startDiscussion() {
  const box = await screen.findByRole("textbox");
  await userEvent.type(box, "Ship it?");
  await userEvent.click(screen.getByTestId("board-send"));
}

describe("WorkforceBoard — lifecycle", () => {
  beforeEach(() => {
    localStorage.removeItem("workforce-board-config-panel");
  });
  afterEach(() => {
    useGroupStreamStore.getState().resetStream("grp1");
  });

  it("Stop cancels the discussion on the server, not just this tab's connection", async () => {
    openDiscussionStream();
    const cancelled: string[] = [];
    server.use(
      http.post("*/groups/:groupId/conversations/:gcId/cancel", ({ params }) => {
        cancelled.push(String(params.gcId));
        return HttpResponse.json(conversationDoc(String(params.gcId), { state: "CANCELLED" }));
      }),
      // The stored document follows the cancel, as the backend's does.
      http.get("*/groups/:groupId/conversations/:gcId", ({ params }) =>
        HttpResponse.json(
          conversationDoc(String(params.gcId), { state: cancelled.length ? "CANCELLED" : "IN_PROGRESS" }),
        ),
      ),
    );
    renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");
    await startDiscussion();

    await userEvent.click(await screen.findByTestId("board-stop-btn"));
    // Confirmed first: stopping abandons every member's work in progress.
    expect(cancelled).toEqual([]);
    await userEvent.click(await screen.findByRole("button", { name: /cancel discussion/i }));

    await waitFor(() => expect(cancelled).toEqual(["gc-live"]));
    await waitFor(() => expect(screen.queryByTestId("board-stop-btn")).not.toBeInTheDocument());
    expect(useGroupStreamStore.getState().streams.grp1?.state).toBe("CANCELLED");
  });

  it("'+ New' during a running discussion stops it first, after asking", async () => {
    openDiscussionStream();
    const cancelled: string[] = [];
    server.use(
      http.post("*/groups/:groupId/conversations/:gcId/cancel", ({ params }) => {
        cancelled.push(String(params.gcId));
        return HttpResponse.json(conversationDoc(String(params.gcId), { state: "CANCELLED" }));
      }),
      http.get("*/groups/:groupId/conversations/:gcId", ({ params }) =>
        HttpResponse.json(conversationDoc(String(params.gcId))),
      ),
    );
    renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");
    await startDiscussion();
    await screen.findByTestId("board-stop-btn");

    await userEvent.click(screen.getByTestId("new-discussion-btn"));
    const dialog = await screen.findByRole("dialog");
    // "Keep running" leaves it alone.
    await userEvent.click(within(dialog).getByRole("button", { name: /keep running/i }));
    expect(cancelled).toEqual([]);
    expect(screen.getByTestId("board-stop-btn")).toBeInTheDocument();

    await userEvent.click(screen.getByTestId("new-discussion-btn"));
    await userEvent.click(await screen.findByRole("button", { name: /stop and start new/i }));

    await waitFor(() => expect(cancelled).toEqual(["gc-live"]));
    await waitFor(() => expect(screen.getByText("Ready for discussion")).toBeInTheDocument());
  });

  /**
   * After a stream settles its transcript is frozen, while the stored document
   * keeps moving (a follow-up is appended to it over REST). The board used to
   * keep showing the frozen copy for good.
   */
  it("switches to the stored discussion once the stream has settled", async () => {
    server.use(
      http.post("*/groups/:groupId/conversations/stream", () => {
        const body = new ReadableStream<Uint8Array>({
          start(controller) {
            controller.enqueue(frame("group_start", { groupConversationId: "gc-done", question: "Ship it?" }));
            controller.enqueue(frame("group_complete", { state: "COMPLETED", synthesizedAnswer: "Yes." }));
            controller.close();
          },
        });
        return new HttpResponse(body, { headers: { "Content-Type": "text/event-stream" } });
      }),
      http.get("*/groups/:groupId/conversations/:gcId", ({ params }) =>
        HttpResponse.json(
          conversationDoc(String(params.gcId), {
            state: "COMPLETED",
            synthesizedAnswer: "Yes.",
            transcript: [
              {
                speakerAgentId: "user", speakerDisplayName: "User", content: "Ship it?", phaseIndex: 0,
                phaseName: "Question", type: "QUESTION", timestamp: new Date().toISOString(),
                errorReason: null, targetAgentId: null,
              },
              {
                speakerAgentId: "agent1", speakerDisplayName: "Support Agent",
                content: "Follow-up answer from the stored document", phaseIndex: 0, phaseName: "Follow-up",
                type: "FOLLOW_UP", timestamp: new Date().toISOString(), errorReason: null, targetAgentId: null,
              },
            ],
          }),
        ),
      ),
    );
    renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");
    await startDiscussion();

    expect(await screen.findByText("Follow-up answer from the stored document")).toBeInTheDocument();
  });

  it("recovers from a refused start instead of stranding the board on an error screen", async () => {
    server.use(
      http.post("*/groups/:groupId/conversations/stream", () =>
        new HttpResponse("question is required", { status: 400, headers: { "Content-Type": "text/plain" } }),
      ),
    );
    renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");
    await startDiscussion();

    const errorScreen = await screen.findByTestId("board-start-error");
    // The backend's sentence, not a bare status line.
    expect(within(errorScreen).getByText(/question is required/)).toBeInTheDocument();
    await userEvent.click(screen.getByTestId("board-error-start-over"));
    expect(await screen.findByRole("textbox")).toBeInTheDocument();
    expect(screen.queryByTestId("board-start-error")).not.toBeInTheDocument();
  });

  it("does not send files without a question", async () => {
    renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");
    const fileInput = await waitFor(() => {
      const el = document.querySelector<HTMLInputElement>('input[type="file"]');
      expect(el).not.toBeNull();
      return el!;
    });
    await userEvent.upload(fileInput, new File(["hello"], "brief.txt", { type: "text/plain" }));
    await waitFor(() => expect(screen.getByTestId("board-attachments")).toBeInTheDocument());

    expect(screen.getByTestId("board-send")).toBeDisabled();
    expect(screen.getByTestId("board-question-required")).toBeInTheDocument();
  });

  it("says a rejected discussion was rejected, not merely ended", async () => {
    server.use(
      http.get("*/groups/:groupId/conversations/:gcId", ({ params }) =>
        HttpResponse.json(conversationDoc(String(params.gcId), { state: "REJECTED", availableActions: ["close"] })),
      ),
    );
    renderPage("/workforce/grp1?version=1&conversation=gc-rej", <WorkforceBoard />, "/workforce/:boardId");
    expect(await screen.findByPlaceholderText("This recommendation was rejected")).toBeInTheDocument();
  });

  it("opens the group's current version when the link names none", async () => {
    const versionsRead: string[] = [];
    server.use(
      http.get("*/groupstore/groups/:id/currentversion", () => HttpResponse.json(4)),
      http.get("*/groupstore/groups/:id", ({ request }) => {
        versionsRead.push(new URL(request.url).searchParams.get("version") ?? "none");
        return HttpResponse.json({
          name: "Renamed Panel",
          description: "",
          members: [],
          style: "ROUND_TABLE",
          maxRounds: 1,
          phases: null,
        });
      }),
    );
    renderPage("/workforce/grp1", <WorkforceBoard />, "/workforce/:boardId");
    expect((await screen.findAllByText("Renamed Panel")).length).toBeGreaterThan(0);
    // Never the FIRST version, not even while the lookup is in flight.
    expect(versionsRead).toEqual(["4"]);
  });

  /**
   * A discussion running with no stream in this tab — the connection dropped,
   * or the board adopted it after a reload — used to have no Stop at all, and
   * "+ New" started a second run beside it without asking.
   */
  it("offers Stop for a running discussion this tab is not streaming, and cancels it by id", async () => {
    let cancelled = false;
    const ids: string[] = [];
    server.use(
      http.get("*/groups/:groupId/conversations/:gcId", ({ params }) =>
        HttpResponse.json(conversationDoc(String(params.gcId), { state: cancelled ? "CANCELLED" : "IN_PROGRESS" })),
      ),
      http.post("*/groups/:groupId/conversations/:gcId/cancel", ({ params }) => {
        cancelled = true;
        ids.push(String(params.gcId));
        return HttpResponse.json(conversationDoc(String(params.gcId), { state: "CANCELLED" }));
      }),
    );
    renderPage("/workforce/grp1?version=1&conversation=gc-remote", <WorkforceBoard />, "/workforce/:boardId");

    // "+ New" asks first rather than starting a second run beside it.
    await screen.findByTestId("board-stop-btn");
    await userEvent.click(screen.getByTestId("new-discussion-btn"));
    await userEvent.click(await screen.findByRole("button", { name: /keep running/i }));
    expect(ids).toEqual([]);

    await userEvent.click(screen.getByTestId("board-stop-btn"));
    await userEvent.click(await screen.findByRole("button", { name: /cancel discussion/i }));

    await waitFor(() => expect(ids).toEqual(["gc-remote"]));
    await waitFor(() => expect(screen.queryByTestId("board-stop-btn")).not.toBeInTheDocument());
  });

  /**
   * One discussion streams in this tab while the user opens another, still
   * running, from Sessions. Stop is about the discussion on screen: it used to
   * cancel the stream instead — the run the user was NOT looking at — and leave
   * the selected one running. A finished discussion browsed meanwhile offers no
   * Stop at all, since the only run it could stop is not on screen.
   */
  it("Stop cancels the selected running discussion, not the one streaming in this tab", async () => {
    let streamOpened = false;
    const cancelled: string[] = [];
    openDiscussionStream("gc-live", () => {
      streamOpened = true;
    });
    // Listed only once the stream is open, so the board's reload-restore does
    // not adopt the running one before the test starts its own discussion.
    server.use(
      http.get("*/groups/:groupId/conversations", () =>
        HttpResponse.json(
          streamOpened
            ? [
                conversationDoc("gc-other", { originalQuestion: "Other run?" }),
                conversationDoc("gc-done", { originalQuestion: "Finished run?", state: "COMPLETED" }),
              ]
            : [],
        ),
      ),
      http.get("*/groups/:groupId/conversations/:gcId", ({ params }) => {
        const id = String(params.gcId);
        const state =
          id === "gc-done" ? "COMPLETED" : cancelled.includes(id) ? "CANCELLED" : "IN_PROGRESS";
        return HttpResponse.json(conversationDoc(id, { state }));
      }),
      http.post("*/groups/:groupId/conversations/:gcId/cancel", ({ params }) => {
        cancelled.push(String(params.gcId));
        return HttpResponse.json(conversationDoc(String(params.gcId), { state: "CANCELLED" }));
      }),
    );
    renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");
    await startDiscussion();
    await waitFor(() => expect(useGroupStreamStore.getState().streams.grp1?.conversationId).toBe("gc-live"));

    await userEvent.click(screen.getByTestId("sessions-toggle"));
    await userEvent.click(await screen.findByText("Finished run?"));
    await screen.findByTestId("back-to-live-btn");
    await waitFor(() => expect(screen.queryByTestId("board-stop-btn")).not.toBeInTheDocument());

    await userEvent.click(screen.getByTestId("sessions-toggle"));
    await userEvent.click(await screen.findByText("Other run?"));
    await userEvent.click(await screen.findByTestId("board-stop-btn"));
    await userEvent.click(await screen.findByRole("button", { name: /cancel discussion/i }));

    await waitFor(() => expect(cancelled).toEqual(["gc-other"]));
    const live = useGroupStreamStore.getState().streams.grp1;
    expect(live?.conversationId).toBe("gc-live");
    expect(live?.isStreaming).toBe(true);
    expect(live?.state).not.toBe("CANCELLED");
  });

  /**
   * The confirmation is about a discussion on the board it was asked on.
   * Switching task forces keeps the page mounted and rebinds the stream hook to
   * the new board, so a dialog carried over sent the old board's discussion id
   * under the new board's group: a cancel that could not stop the old run.
   */
  it("dismisses the stop confirmation when the board changes, and cancels nothing", async () => {
    openDiscussionStream("gc-live");
    const cancelled: string[] = [];
    server.use(
      http.post("*/groups/:groupId/conversations/:gcId/cancel", ({ params }) => {
        cancelled.push(`${String(params.groupId)}/${String(params.gcId)}`);
        return HttpResponse.json(conversationDoc(String(params.gcId), { state: "CANCELLED" }));
      }),
      http.get("*/groups/:groupId/conversations/:gcId", ({ params }) =>
        HttpResponse.json(conversationDoc(String(params.gcId))),
      ),
    );
    const router = renderSwitchableBoard("/workforce/grp1?version=1");
    await startDiscussion();
    await userEvent.click(await screen.findByTestId("board-stop-btn"));
    await screen.findByRole("button", { name: /cancel discussion/i });

    act(() => router.navigate!("/workforce/grp2?version=1"));

    // Once the new board has rendered (its loading state unmounts everything,
    // dialog included), the confirmation must not come back.
    await screen.findByTestId("new-discussion-btn");
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    expect(screen.queryByRole("button", { name: /cancel discussion/i })).not.toBeInTheDocument();
    expect(cancelled).toEqual([]);
    // The run on the board that was left is untouched and still followed there.
    expect(useGroupStreamStore.getState().streams.grp1?.isStreaming).toBe(true);
    useGroupStreamStore.getState().resetStream("grp2");
  });

  /**
   * The same move while a confirmed "Stop and start new" is still waiting for
   * the server. The cancel was for the old board and still lands there; the
   * "start new" that follows it must not clear the board the user moved to.
   */
  it("does not clear the new board when a 'Stop and start new' from the old one settles after the switch", async () => {
    openDiscussionStream("gc-live");
    let answerCancel: () => void = () => {};
    const cancelled: string[] = [];
    server.use(
      http.post("*/groups/:groupId/conversations/:gcId/cancel", async ({ params }) => {
        cancelled.push(`${String(params.groupId)}/${String(params.gcId)}`);
        await new Promise<void>((resolve) => {
          answerCancel = resolve;
        });
        return HttpResponse.json(conversationDoc(String(params.gcId), { state: "CANCELLED" }));
      }),
      http.get("*/groups/:groupId/conversations/:gcId", ({ params }) =>
        HttpResponse.json(conversationDoc(String(params.gcId), { state: "COMPLETED" })),
      ),
    );
    const router = renderSwitchableBoard("/workforce/grp1?version=1");
    await startDiscussion();
    await waitFor(() => expect(useGroupStreamStore.getState().streams.grp1?.conversationId).toBe("gc-live"));
    await userEvent.click(screen.getByTestId("new-discussion-btn"));
    await userEvent.click(await screen.findByRole("button", { name: /stop and start new/i }));
    await waitFor(() => expect(cancelled).toEqual(["grp1/gc-live"]));

    act(() => router.navigate!("/workforce/grp2?version=1&conversation=gc-2a"));
    await screen.findByTestId("new-discussion-btn");
    await act(async () => {
      answerCancel();
      await new Promise((resolve) => setTimeout(resolve, 50));
    });

    await waitFor(() => expect(useGroupStreamStore.getState().streams.grp1?.state).toBe("CANCELLED"));
    expect(router.search).toContain("conversation=gc-2a");
    useGroupStreamStore.getState().resetStream("grp2");
  });

  /**
   * "Stop and start new" confirmed before `group_start`, then the user moves to
   * another board. When the pending cancel's wait ends there, the "start new"
   * it was waiting to do used to clear the board the user had moved to.
   */
  it("does not clear another board when a pending 'Stop and start new' was asked on the previous one", async () => {
    server.use(
      http.post("*/groups/:groupId/conversations/stream", () => {
        // group_start never arrives: the cancel stays pending.
        const body = new ReadableStream<Uint8Array>({ start() {} });
        return new HttpResponse(body, { headers: { "Content-Type": "text/event-stream" } });
      }),
      http.get("*/groups/:groupId/conversations/:gcId", ({ params }) =>
        HttpResponse.json(conversationDoc(String(params.gcId), { groupId: "grp2", state: "COMPLETED" })),
      ),
    );
    const router = renderSwitchableBoard("/workforce/grp1?version=1");
    await startDiscussion();
    await userEvent.click(await screen.findByTestId("new-discussion-btn"));
    await userEvent.click(await screen.findByRole("button", { name: /stop and start new/i }));
    await waitFor(() => expect(useGroupStreamStore.getState().streams.grp1?.cancelRequested).toBe(true));

    act(() => router.navigate!("/workforce/grp2?version=1&conversation=gc-2a"));

    await screen.findByTestId("new-discussion-btn");
    // Give any leftover "start new" effect its chance to run on the new board.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
    expect(router.search).toContain("conversation=gc-2a");
    useGroupStreamStore.getState().resetStream("grp2");
  });

  /**
   * "Stop and start new" pressed before `group_start` named the conversation:
   * the cancel waits for the id, and the new discussion must still follow it
   * rather than leave the user on the run they chose to leave.
   */
  it("starts the new discussion once a pending cancel lands", async () => {
    let deliverStart: () => void = () => {};
    const cancelled: string[] = [];
    server.use(
      http.post("*/groups/:groupId/conversations/stream", () => {
        const body = new ReadableStream<Uint8Array>({
          start(controller) {
            deliverStart = () =>
              controller.enqueue(frame("group_start", { groupConversationId: "gc-late", question: "Ship it?" }));
          },
        });
        return new HttpResponse(body, { headers: { "Content-Type": "text/event-stream" } });
      }),
      http.post("*/groups/:groupId/conversations/:gcId/cancel", ({ params }) => {
        cancelled.push(String(params.gcId));
        return HttpResponse.json(conversationDoc(String(params.gcId), { state: "CANCELLED" }));
      }),
    );
    renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");
    await startDiscussion();
    await screen.findByTestId("board-stop-btn");

    await userEvent.click(screen.getByTestId("new-discussion-btn"));
    await userEvent.click(await screen.findByRole("button", { name: /stop and start new/i }));
    expect(cancelled).toEqual([]);

    deliverStart();

    await waitFor(() => expect(cancelled).toEqual(["gc-late"]));
    await waitFor(() => expect(screen.getByText("Ready for discussion")).toBeInTheDocument());
  });

  /**
   * The same early Stop, refused by the server. The discussion is still
   * running, so the board reports a failed Stop — as it does when an immediate
   * cancel is refused — and not the inline banner that means the discussion
   * itself failed. Stop is offered again.
   */
  it("reports a refused pending cancel as a failed Stop, not a discussion failure", async () => {
    const toastError = vi.spyOn(toast, "error");
    let deliverStart: () => void = () => {};
    server.use(
      http.post("*/groups/:groupId/conversations/stream", () => {
        const body = new ReadableStream<Uint8Array>({
          start(controller) {
            deliverStart = () =>
              controller.enqueue(frame("group_start", { groupConversationId: "gc-late", question: "Ship it?" }));
          },
        });
        return new HttpResponse(body, { headers: { "Content-Type": "text/event-stream" } });
      }),
      http.post("*/groups/:groupId/conversations/:gcId/cancel", () =>
        HttpResponse.json({ message: "Cancel refused" }, { status: 500 }),
      ),
    );
    try {
      renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");
      await startDiscussion();
      await userEvent.click(await screen.findByTestId("board-stop-btn"));
      await userEvent.click(await screen.findByRole("button", { name: /cancel discussion/i }));
      expect(screen.getByTestId("board-stop-btn")).toBeDisabled();

      deliverStart();

      await waitFor(() =>
        expect(toastError).toHaveBeenCalledWith(expect.stringContaining("Could not stop the discussion")),
      );
      const stream = useGroupStreamStore.getState().streams.grp1;
      expect(stream?.error).toBeNull();
      expect(stream?.cancelError).toBeNull();
      expect(stream?.isStreaming).toBe(true);
      await waitFor(() => expect(screen.getByTestId("board-stop-btn")).toBeEnabled());
    } finally {
      toastError.mockRestore();
    }
  });
});
