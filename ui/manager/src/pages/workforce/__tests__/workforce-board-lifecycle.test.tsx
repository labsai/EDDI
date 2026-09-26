import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderPage, userEvent } from "@/test/test-utils";
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
function openDiscussionStream(gcId = "gc-live") {
  server.use(
    http.post("*/groups/:groupId/conversations/stream", () => {
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
});
