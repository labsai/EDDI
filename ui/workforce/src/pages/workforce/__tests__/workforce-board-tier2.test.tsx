import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";
import { ThemeProvider } from "@eddi/ui";
import { createTestQueryClient } from "@eddi/app-kit/test/test-utils";
import { server } from "@eddi/app-kit/test/mocks/server";
import { AuthContext, GUEST_CONTEXT, type AuthUser } from "@eddi/app-kit/components/auth/auth-context";
import { useGroupStreamStore } from "@eddi/app-kit/hooks/use-group-discussion-stream";
import { addHandoff, clearWorkforceHandoffs, readHandoffs } from "@eddi/app-kit/lib/workforce-handoffs";
import { WorkforceBoard } from "@/pages/workforce/workforce-board";
import { workforceTeamStart } from "@/lib/workforce-team-start";

/**
 * Tier 2 on the team board: a question handed in from Home or a chat starts a
 * session once (T2.1, T2.2), a HUMAN member's turn says whose it is (T2.1),
 * and the session's shared artifacts open in a side panel with their versions
 * (T2.6).
 */

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

const encoder = new TextEncoder();
const frame = (type: string, data: unknown) => encoder.encode(`event: ${type}\ndata: ${JSON.stringify(data)}\n\n`);

function conversationDoc(id: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    groupId: "grp1",
    userId: "alice",
    state: "COMPLETED",
    originalQuestion: "Ship it?",
    transcript: [
      { speakerAgentId: "agent1", speakerDisplayName: "Support Agent", content: "Yes.", phase: "Opinions", type: "OPINION", timestamp: new Date().toISOString() },
    ],
    memberConversationIds: {},
    currentPhaseIndex: 0,
    currentPhaseName: "Opinions",
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

function LocationProbe() {
  const location = useLocation();
  return (
    <div data-testid="location">
      {location.pathname}
      {location.search}|{location.state ? "state" : "no-state"}
    </div>
  );
}

function renderBoard(entry: string | { pathname: string; search?: string; state?: unknown }, user: Partial<AuthUser> | null = null) {
  return render(
    <AuthContext.Provider
      value={
        user
          ? { ...GUEST_CONTEXT, method: "keycloak", roles: ["eddi-user"], user: { firstName: "", lastName: "", fullName: "", email: "", username: "", ...user } }
          : GUEST_CONTEXT
      }
    >
      <MemoryRouter initialEntries={[entry]}>
        <QueryClientProvider client={createTestQueryClient()}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            <Routes>
              <Route
                path="/workforce/:boardId"
                element={
                  <>
                    <WorkforceBoard />
                    <LocationProbe />
                  </>
                }
              />
            </Routes>
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>
    </AuthContext.Provider>,
  );
}

describe("WorkforceBoard — a question handed in", () => {
  afterEach(() => {
    useGroupStreamStore.getState().resetStream("grp1");
    clearWorkforceHandoffs();
  });

  it("starts one new session with it, drops it from the history entry, and links the hand-off", async () => {
    const bodies: string[] = [];
    server.use(
      http.post("*/groups/:groupId/conversations/stream", async ({ request }) => {
        bodies.push(await request.text());
        const body = new ReadableStream<Uint8Array>({
          start(controller) {
            controller.enqueue(frame("group_start", { groupConversationId: "gc-handed", question: "Should we ship?" }));
          },
        });
        return new HttpResponse(body, { headers: { "Content-Type": "text/event-stream" } });
      }),
      http.get("*/groups/:groupId/conversations/:convId", ({ params }) =>
        HttpResponse.json(conversationDoc(String(params.convId), { state: "IN_PROGRESS", transcript: [] })),
      ),
    );
    const handoff = addHandoff(null, {
      from: { agentId: "agent1", agentName: "Support Bot", conversationId: "conv1", afterStep: 1 },
      to: { kind: "team", id: "grp1", name: "Product Review Panel", version: 1, conversationId: null },
      question: "Should we ship?",
    });
    const start = workforceTeamStart("grp1", "Should we ship?", { version: 1, handoffId: handoff.id });
    const [pathname, search] = start.to.split("?");
    renderBoard({ pathname: pathname!, search: `?${search}`, state: start.state });

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(JSON.parse(bodies[0]!).question).toBe("Should we ship?");
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("no-state"));
    await waitFor(() => expect(readHandoffs(null)[0]!.to.conversationId).toBe("gc-handed"));
    // Once: the board re-rendering does not start it again.
    await new Promise((r) => setTimeout(r, 50));
    expect(bodies).toHaveLength(1);
    // The session shows where its question came from.
    expect(await screen.findByTestId("board-handoff-marker")).toHaveTextContent(
      "Handed over from your chat with Support Bot.",
    );
  });

  it("ignores a question meant for another team", async () => {
    const posted = vi.fn();
    server.use(http.post("*/groups/:groupId/conversations/stream", () => {
      posted();
      return new HttpResponse(null, { status: 500 });
    }));
    const start = workforceTeamStart("other-team", "Hello");
    renderBoard({ pathname: "/workforce/grp1", state: start.state });
    await screen.findByTestId("new-discussion-btn");
    await new Promise((r) => setTimeout(r, 50));
    expect(posted).not.toHaveBeenCalled();
  });
});

describe("WorkforceBoard — a HUMAN member's turn", () => {
  const pending = {
    state: "AWAITING_HUMAN_INPUT",
    pendingHumanInput: {
      memberId: "gregor@example.com",
      displayName: "Gregor",
      renderedPrompt: "Your view on the launch?",
      onTimeout: "SKIP_TURN",
      requestedAt: new Date().toISOString(),
    },
    availableActions: ["submitHumanInput"],
  };

  beforeEach(() => {
    server.use(
      http.get("*/groups/:groupId/conversations/:convId", ({ params }) =>
        HttpResponse.json(conversationDoc(String(params.convId), pending)),
      ),
    );
  });

  it("tells the member it is their turn, and announces it", async () => {
    renderBoard("/workforce/grp1?conversation=gc-h", { username: "gregor@example.com" });
    expect(await screen.findByRole("region", { name: "It's your turn, Gregor" })).toBeInTheDocument();
    expect(screen.getByTestId("board-team-announcement")).toHaveTextContent("It's your turn in this discussion.");
  });

  it("tells anyone else whose turn it is and who may answer", async () => {
    renderBoard("/workforce/grp1?conversation=gc-h", { username: "alice" });
    expect(await screen.findByRole("region", { name: "Gregor's turn" })).toBeInTheDocument();
    expect(screen.getByTestId("human-turn-who-may-answer")).toBeInTheDocument();
    expect(screen.getByTestId("board-team-announcement")).toHaveTextContent("The discussion is waiting for Gregor.");
  });

  it("says plainly who may answer when the server refuses the submission", async () => {
    const error = vi.spyOn(toast, "error");
    server.use(
      http.post("*/groups/:groupId/conversations/:gcId/human-input", () =>
        HttpResponse.json({ error: "FORBIDDEN", message: "Access denied" }, { status: 403 }),
      ),
    );
    renderBoard("/workforce/grp1?conversation=gc-h", { username: "alice" });
    fireEvent.change(await screen.findByTestId("human-turn-input"), { target: { value: "I think yes" } });
    fireEvent.click(screen.getByTestId("human-turn-submit"));
    await waitFor(() =>
      expect(error).toHaveBeenCalledWith(
        "Only the member whose turn it is, or an administrator, can answer this turn.",
      ),
    );
    error.mockRestore();
  });
});

describe("WorkforceBoard — shared artifacts with versions", () => {
  const artifacts = [
    {
      id: "art1",
      groupConversationId: "gc-a",
      ownerUserId: "alice",
      name: "Launch plan",
      type: "MARKDOWN",
      content: "Ship on **Friday**.",
      version: 2,
      lastEditorAgentId: "agent1",
      status: "DRAFT",
      history: [{ content: "Ship on Monday.", editorAgentId: "agent2", version: 1, at: "2026-11-01T10:00:00Z" }],
      createdAt: "2026-11-01T10:00:00Z",
      updatedAt: "2026-11-02T10:00:00Z",
    },
  ];

  it("offers no artifacts button for a session without any", async () => {
    server.use(
      http.get("*/groups/:groupId/conversations/:convId", ({ params }) => HttpResponse.json(conversationDoc(String(params.convId)))),
    );
    renderBoard("/workforce/grp1?conversation=gc-a");
    await screen.findByTestId("new-discussion-btn");
    await screen.findByText("Yes.");
    expect(screen.queryByTestId("artifacts-toggle")).toBeNull();
  });

  it("opens them in a side panel, switches versions, and gives focus back on Escape", async () => {
    server.use(
      http.get("*/groups/:groupId/conversations/:convId", ({ params }) =>
        HttpResponse.json(conversationDoc(String(params.convId), { artifacts })),
      ),
    );
    renderBoard("/workforce/grp1?conversation=gc-a");
    const toggle = await screen.findByTestId("artifacts-toggle");
    expect(toggle).toHaveAccessibleName("Shared artifacts (1)");
    toggle.focus();
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");

    const close = await screen.findByTestId("artifacts-drawer-close");
    await waitFor(() => expect(close).toHaveFocus());
    expect(screen.getByRole("dialog", { name: "Shared artifacts panel" })).toBeInTheDocument();
    expect(screen.getByTestId("artifact-version-content")).toHaveTextContent("Ship on Friday.");

    fireEvent.change(screen.getByTestId("artifact-version-select"), { target: { value: "1" } });
    expect(screen.getByTestId("artifact-version-content")).toHaveTextContent("Ship on Monday.");
    expect(screen.getByTestId("artifact-older-version")).toBeInTheDocument();

    fireEvent.keyDown(document, { key: "Escape" });
    await waitFor(() => expect(screen.queryByTestId("artifacts-drawer")).toBeNull());
    await waitFor(() => expect(toggle).toHaveFocus());
  });
});
