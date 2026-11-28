/* eslint-disable @typescript-eslint/no-explicit-any */
import { describe, it, expect, vi } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useNavigate, type NavigateFunction } from "react-router-dom";
import { createTestQueryClient, renderPage, userEvent } from "@eddi/app-kit/test/test-utils";

import * as useGroupsHook from "@eddi/app-kit/hooks/use-groups";
import * as useWorkforceThreadsHook from "@/hooks/use-workforce-threads";
import * as chatApi from "@eddi/app-kit/lib/api/chat";

import { WorkforceThread } from "@/pages/workforce/workforce-thread";

/**
 * Leaving a thread while the agent answers must not discard the exchange.
 *
 * The page used to reset its controller on unmount, which aborted the fetch;
 * the backend then discarded the turn. Now a running turn keeps its controller
 * for the shared grace period, so coming back shows it, and the tab warns
 * before it is closed.
 */

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;
window.HTMLElement.prototype.scrollIntoView = vi.fn();
window.HTMLElement.prototype.scrollTo = vi.fn();

const PATH = "/workforce/board-leave/thread/agent-leave";
const ROUTE = "/workforce/:boardId/thread/:memberId";

function mount() {
  return renderPage(PATH, <WorkforceThread />, ROUTE);
}

describe("WorkforceThread – leaving while a turn runs", () => {
  it("keeps the turn running after unmount, shows it on return, and warns before the tab closes", async () => {
    vi.spyOn(useWorkforceThreadsHook, "useWorkforceThreads").mockReturnValue({
      getThread: () => null,
      registerThread: vi.fn(),
      updateActivity: vi.fn(),
    } as any);
    vi.spyOn(useGroupsHook, "useGroup").mockReturnValue({
      data: { id: "board-leave", name: "Board", members: [{ agentId: "agent-leave", displayName: "Leave Agent" }] },
    } as any);
    vi.spyOn(chatApi, "startConversation").mockResolvedValue("conv-leave");
    const reads = vi.spyOn(chatApi, "readConversation").mockResolvedValue({ conversationSteps: [] } as any);

    let finish!: () => void;
    let signal: AbortSignal | undefined;
    (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(async function* (
      _env: string,
      _agent: string,
      _conv: string,
      _input: unknown,
      s?: AbortSignal,
    ) {
      signal = s;
      yield { type: "token", data: "Still thinking" };
      await new Promise<void>((resolve) => {
        finish = resolve;
        s?.addEventListener("abort", () => resolve());
      });
      if (s?.aborted) return;
      yield {
        type: "done",
        data: JSON.stringify({ conversationState: "READY", conversationOutputs: [{ output: [{ type: "text", text: "Still thinking, done" }] }] }),
      };
    });

    const first = mount();
    await waitFor(() => expect(screen.getByRole("textbox")).not.toBeDisabled());
    await userEvent.setup().type(screen.getByRole("textbox"), "Take your time");
    await userEvent.setup().keyboard("{Enter}");
    await waitFor(() => expect(screen.getByText("Still thinking")).toBeInTheDocument());

    // A turn in flight: closing the tab would lose it, so the browser is asked.
    const closing = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(closing);
    expect(closing.defaultPrevented).toBe(true);

    // Navigate away.
    first.unmount();
    expect(signal?.aborted).toBe(false);
    // The parked turn is still running, and closing the tab would abort it: the
    // warning stays until it settles.
    const parked = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(parked);
    expect(parked.defaultPrevented).toBe(true);

    // Back within the grace: the running turn is on screen, the thread is not
    // re-opened (no new conversation, no re-read of the stored one).
    const readsBefore = reads.mock.calls.length;
    mount();
    await waitFor(() => expect(screen.getByText("Take your time")).toBeInTheDocument());
    expect(screen.getByText("Still thinking")).toBeInTheDocument();
    expect(chatApi.startConversation).toHaveBeenCalledTimes(1);
    expect(reads.mock.calls.length).toBe(readsBefore);

    finish();
    await waitFor(() => expect(screen.getByText("Still thinking, done")).toBeInTheDocument());
    // Settled: the tab closes without a question.
    const settled = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(settled);
    expect(settled.defaultPrevented).toBe(false);
  });

  it("A -> B -> A without unmounting adopts A's running turn instead of resetting it", async () => {
    vi.spyOn(useWorkforceThreadsHook, "useWorkforceThreads").mockReturnValue({
      getThread: () => null,
      registerThread: vi.fn(),
      updateActivity: vi.fn(),
    } as any);
    vi.spyOn(useGroupsHook, "useGroup").mockReturnValue({
      data: {
        id: "board-ab",
        name: "Board",
        members: [
          { agentId: "agent-a", displayName: "Agent A" },
          { agentId: "agent-b", displayName: "Agent B" },
        ],
      },
    } as any);
    vi.spyOn(chatApi, "startConversation").mockImplementation(async (_env: string, agentId: string) => `conv-${agentId}`);
    vi.spyOn(chatApi, "readConversation").mockResolvedValue({ conversationSteps: [] } as any);
    let finish!: () => void;
    let signal: AbortSignal | undefined;
    (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(async function* (
      _env: string,
      _agent: string,
      _conv: string,
      _input: unknown,
      s?: AbortSignal,
    ) {
      signal = s;
      yield { type: "token", data: "A is thinking" };
      await new Promise<void>((resolve) => {
        finish = resolve;
        s?.addEventListener("abort", () => resolve());
      });
      if (s?.aborted) return;
      yield {
        type: "done",
        data: JSON.stringify({ conversationState: "READY", conversationOutputs: [{ output: [{ type: "text", text: "A is done" }] }] }),
      };
    });

    let navigate!: NavigateFunction;
    function Navigator() {
      navigate = useNavigate();
      return null;
    }
    render(
      <MemoryRouter initialEntries={["/workforce/board-ab/thread/agent-a"]}>
        <QueryClientProvider client={createTestQueryClient()}>
          <Navigator />
          <Routes>
            <Route path={ROUTE} element={<WorkforceThread />} />
          </Routes>
        </QueryClientProvider>
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.getByRole("textbox")).not.toBeDisabled());
    await userEvent.setup().type(screen.getByRole("textbox"), "Question for A");
    await userEvent.setup().keyboard("{Enter}");
    await waitFor(() => expect(screen.getByText("A is thinking")).toBeInTheDocument());

    // Same page, another advisor, then back — only the route param changes.
    await act(async () => navigate("/workforce/board-ab/thread/agent-b"));
    await waitFor(() => expect(screen.queryByText("Question for A")).not.toBeInTheDocument());
    await act(async () => navigate("/workforce/board-ab/thread/agent-a"));
    await waitFor(() => expect(screen.getByText("Question for A")).toBeInTheDocument());
    expect(signal?.aborted).toBe(false);
    expect(screen.getByText("A is thinking")).toBeInTheDocument();

    finish();
    await waitFor(() => expect(screen.getByText("A is done")).toBeInTheDocument());
  });

  it("a conversation that ends while the page is away shows its ended state on return", async () => {
    vi.spyOn(useWorkforceThreadsHook, "useWorkforceThreads").mockReturnValue({
      getThread: () => null,
      registerThread: vi.fn(),
      updateActivity: vi.fn(),
    } as any);
    vi.spyOn(useGroupsHook, "useGroup").mockReturnValue({
      data: { id: "board-leave", name: "Board", members: [{ agentId: "agent-end", displayName: "End Agent" }] },
    } as any);
    vi.spyOn(chatApi, "startConversation").mockResolvedValue("conv-end");
    vi.spyOn(chatApi, "readConversation").mockResolvedValue({ conversationSteps: [] } as any);
    let end!: () => void;
    (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(async function* () {
      await new Promise<void>((resolve) => (end = resolve));
      yield { type: "error", data: JSON.stringify({ code: "conversation_ended", message: "Conversation has ended" }) };
    });

    const first = renderPage("/workforce/board-leave/thread/agent-end", <WorkforceThread />, ROUTE);
    await waitFor(() => expect(screen.getByRole("textbox")).not.toBeDisabled());
    await userEvent.setup().type(screen.getByRole("textbox"), "Anyone there?");
    await userEvent.setup().keyboard("{Enter}");
    await waitFor(() => expect(end).toBeTypeOf("function"));
    first.unmount();

    end(); // the conversation ends while nobody is looking
    await new Promise((r) => setTimeout(r, 50));

    renderPage("/workforce/board-leave/thread/agent-end", <WorkforceThread />, ROUTE);
    // The controller's own handling ran: the ENDED notice, not a deferred Retry banner.
    expect(await screen.findByText(/conversation has ended|this conversation has ended/i)).toBeInTheDocument();
    expect(screen.queryByTestId("thread-retry")).not.toBeInTheDocument();
  });

  it("an idle thread asks nothing when the tab closes", async () => {
    vi.spyOn(useWorkforceThreadsHook, "useWorkforceThreads").mockReturnValue({
      getThread: () => null,
      registerThread: vi.fn(),
      updateActivity: vi.fn(),
    } as any);
    vi.spyOn(useGroupsHook, "useGroup").mockReturnValue({
      data: { id: "board-leave", name: "Board", members: [{ agentId: "agent-idle", displayName: "Idle Agent" }] },
    } as any);
    vi.spyOn(chatApi, "startConversation").mockResolvedValue("conv-idle");
    vi.spyOn(chatApi, "readConversation").mockResolvedValue({ conversationSteps: [] } as any);

    renderPage("/workforce/board-leave/thread/agent-idle", <WorkforceThread />, ROUTE);
    await waitFor(() => expect(screen.getByRole("textbox")).not.toBeDisabled());
    const closing = new Event("beforeunload", { cancelable: true });
    window.dispatchEvent(closing);
    expect(closing.defaultPrevented).toBe(false);
  });

  it("a turn that fails while the page is away is shown when the page comes back, with its question and Retry", async () => {
    vi.spyOn(useWorkforceThreadsHook, "useWorkforceThreads").mockReturnValue({
      getThread: () => null,
      registerThread: vi.fn(),
      updateActivity: vi.fn(),
    } as any);
    vi.spyOn(useGroupsHook, "useGroup").mockReturnValue({
      data: { id: "board-leave", name: "Board", members: [{ agentId: "agent-fail", displayName: "Fail Agent" }] },
    } as any);
    vi.spyOn(chatApi, "startConversation").mockResolvedValue("conv-fail");
    vi.spyOn(chatApi, "readConversation").mockResolvedValue({ conversationSteps: [] } as any);
    let fail!: () => void;
    (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(async function* () {
      yield { type: "token", data: "Half an answer" };
      await new Promise<void>((resolve) => (fail = resolve));
      yield { type: "error", data: JSON.stringify({ message: "upstream died" }) };
    });

    const first = renderPage("/workforce/board-leave/thread/agent-fail", <WorkforceThread />, ROUTE);
    await waitFor(() => expect(screen.getByRole("textbox")).not.toBeDisabled());
    await userEvent.setup().type(screen.getByRole("textbox"), "Will fail");
    await userEvent.setup().keyboard("{Enter}");
    await waitFor(() => expect(screen.getByText("Half an answer")).toBeInTheDocument());
    first.unmount();

    fail(); // the error arrives while nobody is looking
    await new Promise((r) => setTimeout(r, 50));

    renderPage("/workforce/board-leave/thread/agent-fail", <WorkforceThread />, ROUTE);
    expect(await screen.findByTestId("thread-send-error")).toHaveTextContent(/upstream died/i);
    expect(screen.getByText("Will fail")).toBeInTheDocument();
    expect(screen.getByTestId("thread-retry")).toBeInTheDocument();
  });
});
