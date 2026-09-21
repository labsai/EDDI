/* eslint-disable @typescript-eslint/no-explicit-any */
import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderPage, userEvent } from "@/test/test-utils";

import * as useGroupsHook from "@/hooks/use-groups";
import * as useWorkforceThreadsHook from "@/hooks/use-workforce-threads";
import * as chatApi from "@/lib/api/chat";

import { WorkforceThread } from "../workforce-thread";

/**
 * What a retry does with the question that produced a failed turn.
 *
 * The rule is not the same in both directions, which is why it is worth a
 * test: a turn that produced nothing has its question withdrawn and re-sent,
 * so exactly one copy survives; a turn that streamed part of an answer keeps
 * it, because the partial reply is still on screen underneath and a retry that
 * removed the question would leave that half-answer stranded above the next
 * one.
 */

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;
window.HTMLElement.prototype.scrollIntoView = vi.fn();
window.HTMLElement.prototype.scrollTo = vi.fn();

function setupMocks(stream: () => AsyncGenerator<any>) {
  vi.spyOn(useWorkforceThreadsHook, "useWorkforceThreads").mockReturnValue({
    getThread: () => null,
    registerThread: vi.fn(),
    updateActivity: vi.fn(),
  } as any);
  vi.spyOn(useGroupsHook, "useGroup").mockReturnValue({
    data: {
      id: "board1",
      name: "Board",
      members: [{ agentId: "agent1", displayName: "Test Agent" }],
    },
  } as any);
  vi.spyOn(chatApi, "startConversation").mockResolvedValue("conv-1");
  vi.spyOn(chatApi, "readConversation").mockResolvedValue({
    conversationSteps: [],
  } as any);
  return (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(stream);
}

async function renderAndSend(text: string) {
  renderPage(
    "/workforce/board1/thread/agent1",
    <WorkforceThread />,
    "/workforce/:boardId/thread/:memberId",
  );
  await waitFor(() => {
    expect(screen.getByRole("textbox")).not.toBeDisabled();
  });
  const user = userEvent.setup();
  await user.type(screen.getByRole("textbox"), text);
  await user.keyboard("{Enter}");
}

function countOf(text: string) {
  return screen.queryAllByText(text).length;
}

describe("WorkforceThread – retry after a failed turn", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("keeps the question once tokens have arrived, so the partial reply stays under it", async () => {
    let call = 0;
    setupMocks(async function* () {
      call += 1;
      if (call === 1) {
        yield { type: "token", data: "Half an ans" };
        yield { type: "error", data: JSON.stringify({ message: "upstream died" }) };
      } else {
        yield { type: "token", data: "The whole answer" };
        yield { type: "done", data: "" };
      }
    });

    await renderAndSend("Why did it break?");
    await waitFor(() => expect(screen.getByText(/upstream died/i)).toBeInTheDocument());
    expect(countOf("Half an ans")).toBe(1);

    await userEvent.setup().click(screen.getByRole("button", { name: /retry/i }));

    // The failed attempt stays whole: its question, then the half-answer it
    // produced, then the retry's question. Withdrawing the first question is
    // what would strand "Half an ans" above the retry instead of below the
    // attempt that produced it.
    await waitFor(() => expect(screen.getByText("The whole answer")).toBeInTheDocument());
    expect(countOf("Why did it break?")).toBe(2);

    const [firstAsk, secondAsk] = screen.getAllByText("Why did it break?");
    const partial = screen.getByText("Half an ans");
    expect(firstAsk).toBeDefined();
    expect(secondAsk).toBeDefined();
    // `compareDocumentPosition` returns a bitmask, so test the bit.
    const precedes = (a: Element, b: Element) =>
      (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
    expect(precedes(firstAsk!, partial)).toBe(true);
    expect(precedes(partial, secondAsk!)).toBe(true);
  });

  it("takes the question back when a paused conversation refused the send", async () => {
    // A 409 means the backend never received the message, so leaving it in the
    // transcript would show as sent something that was not. There is no Retry
    // on a paused error either: the way forward is the approvals queue.
    // Not a generator: `sendMessageStreaming` rejects before it yields, which
    // is what a 409 on the send itself looks like.
    setupMocks((() => {
      throw { status: 409, message: "conversation is paused" };
    }) as never);

    await renderAndSend("Why did it break?");

    await waitFor(() => expect(screen.getByText(/paused waiting/i)).toBeInTheDocument());
    expect(countOf("Why did it break?")).toBe(0);
    expect(screen.getByTestId("thread-review-approvals")).toBeInTheDocument();
    expect(screen.queryByTestId("thread-retry")).toBeNull();
  });

  it("withdraws and re-sends the question when the turn produced nothing", async () => {
    let call = 0;
    setupMocks(async function* () {
      call += 1;
      if (call === 1) {
        yield { type: "error", data: JSON.stringify({ message: "upstream died" }) };
      } else {
        yield { type: "token", data: "Second time lucky" };
        yield { type: "done", data: "" };
      }
    });

    await renderAndSend("Why did it break?");
    await waitFor(() => expect(screen.getByText(/upstream died/i)).toBeInTheDocument());

    await userEvent.setup().click(screen.getByRole("button", { name: /retry/i }));

    await waitFor(() => expect(screen.getByText("Second time lucky")).toBeInTheDocument());
    expect(countOf("Why did it break?")).toBe(1);
  });
});
