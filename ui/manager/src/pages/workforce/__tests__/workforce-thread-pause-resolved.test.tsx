/* eslint-disable @typescript-eslint/no-explicit-any */
import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderPage, userEvent } from "@/test/test-utils";

import * as useGroupsHook from "@/hooks/use-groups";
import * as useWorkforceThreadsHook from "@/hooks/use-workforce-threads";
import * as chatApi from "@/lib/api/chat";

import { WorkforceThread } from "../workforce-thread";

/**
 * A pause decided elsewhere, then a next turn that pauses again.
 *
 * The thread's onResolved used to clear only its error. The controller still
 * held AWAITING_HUMAN, so the second pausing turn read like one the server had
 * dropped: the question was withdrawn, the controller said it was not sent, and
 * the thread never raised its pause notice.
 *
 * The notice's own approval-status watch is replaced by a button that reports
 * the resolution, which is all the thread sees of it.
 */
vi.mock("@/components/hitl/pause-notice", () => ({
  PauseNotice: ({ onResolved, testIdBase }: { onResolved: () => void; testIdBase?: string }) => (
    <div data-testid={`${testIdBase}-banner`}>
      <button type="button" onClick={onResolved}>
        resolved elsewhere
      </button>
    </div>
  ),
}));

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;
window.HTMLElement.prototype.scrollIntoView = vi.fn();
window.HTMLElement.prototype.scrollTo = vi.fn();

const pausedDone = (text: string) => ({
  type: "done",
  data: JSON.stringify({ conversationState: "AWAITING_HUMAN", conversationOutputs: [{ output: [text] }] }),
});

describe("WorkforceThread – a pause resolved elsewhere, then a second pause", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(useWorkforceThreadsHook, "useWorkforceThreads").mockReturnValue({
      getThread: () => null,
      registerThread: vi.fn(),
      updateActivity: vi.fn(),
    } as any);
    vi.spyOn(useGroupsHook, "useGroup").mockReturnValue({
      data: { id: "board1", name: "Board", members: [{ agentId: "agent1", displayName: "Test Agent" }] },
    } as any);
    vi.spyOn(chatApi, "startConversation").mockResolvedValue("conv-1");
    vi.spyOn(chatApi, "readConversation").mockResolvedValue({ conversationSteps: [] } as any);
  });

  it("raises the pause notice again and keeps the second question", async () => {
    let call = 0;
    (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(async function* () {
      call += 1;
      yield pausedDone(call === 1 ? "Awaiting approval one" : "Awaiting approval two");
    });

    renderPage("/workforce/board1/thread/agent1", <WorkforceThread />, "/workforce/:boardId/thread/:memberId");
    await waitFor(() => expect(screen.getByTestId("thread-input")).not.toBeDisabled());
    const user = userEvent.setup();

    await user.type(screen.getByTestId("thread-input"), "first question");
    await user.keyboard("{Enter}");
    await waitFor(() => expect(screen.getByTestId("thread-pause-banner")).toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: "resolved elsewhere" }));
    await waitFor(() => expect(screen.queryByTestId("thread-pause-banner")).toBeNull());

    await waitFor(() => expect(screen.getByTestId("thread-input")).not.toBeDisabled());
    await user.type(screen.getByTestId("thread-input"), "second question");
    await user.keyboard("{Enter}");

    await waitFor(() => expect(call).toBe(2));
    await waitFor(() => expect(screen.getByTestId("thread-pause-banner")).toBeInTheDocument());
    expect(screen.getByText("second question")).toBeInTheDocument();
    expect(screen.getByText("Awaiting approval two")).toBeInTheDocument();
    expect(screen.queryByText(/was not sent/i)).toBeNull();
    expect(screen.queryByTestId("chat-notice")).toBeNull();
  });

  it("renders a controller notice as a system line, without a copy button", async () => {
    // A turn dropped into a conversation the thread already holds as paused:
    // the controller withdraws it and writes its own notice.
    (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(async function* () {
      yield pausedDone("Awaiting approval");
    });
    renderPage("/workforce/board1/thread/agent1", <WorkforceThread />, "/workforce/:boardId/thread/:memberId");
    await waitFor(() => expect(screen.getByTestId("thread-input")).not.toBeDisabled());
    const user = userEvent.setup();
    await user.type(screen.getByTestId("thread-input"), "one");
    await user.keyboard("{Enter}");
    await waitFor(() => expect(screen.getByTestId("thread-pause-banner")).toBeInTheDocument());
    // Sent again while still paused (the composer stays enabled in the thread).
    await user.type(screen.getByTestId("thread-input"), "two");
    await user.keyboard("{Enter}");

    const notice = await screen.findByTestId("chat-notice");
    expect(notice).toHaveTextContent(/not sent/i);
    expect(notice.closest('[class~="group/msg"]')).toBeNull();
    expect(notice.querySelector("button")).toBeNull();
  });
});
