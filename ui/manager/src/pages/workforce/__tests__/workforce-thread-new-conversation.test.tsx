/* eslint-disable @typescript-eslint/no-explicit-any */
import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderPage, userEvent } from "@/test/test-utils";

import * as useGroupsHook from "@/hooks/use-groups";
import * as useWorkforceThreadsHook from "@/hooks/use-workforce-threads";
import * as chatApi from "@/lib/api/chat";

import { WorkforceThread } from "../workforce-thread";

/**
 * The 1:1 advisor thread had no way to start over.
 *
 * Every other chat surface in the Manager offers one — `chat-panel` and
 * `chat-drawer` ("New Conversation"), `operator-chat`, and the Workforce board
 * ("New"). This page did not, and its conversation is pinned in localStorage by
 * (board, member): the thread it resumes on every visit is the one it started
 * the first time. A derailed thread could only be escaped by clearing site data.
 */

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;
window.HTMLElement.prototype.scrollIntoView = vi.fn();
window.HTMLElement.prototype.scrollTo = vi.fn();

const MEMBER = { agentId: "agent1", displayName: "Test Agent" };

/** A step the conversation reader returns, rendered as a user + agent pair. */
function step(input: string, output: string) {
  return {
    timestamp: "2026-09-22T10:00:00.000Z",
    conversationStep: [
      { key: "input:initial", value: input },
      { key: "output:text:default", value: output },
    ],
  };
}

function setup(options?: { existingConversationId?: string }) {
  const registerThread = vi.fn();
  vi.spyOn(useWorkforceThreadsHook, "useWorkforceThreads").mockReturnValue({
    getThread: () =>
      options?.existingConversationId
        ? {
            memberId: MEMBER.agentId,
            memberName: MEMBER.displayName,
            conversationId: options.existingConversationId,
            boardId: "board1",
            lastActivity: Date.now(),
          }
        : null,
    registerThread,
    updateActivity: vi.fn(),
  } as any);

  vi.spyOn(useGroupsHook, "useGroup").mockReturnValue({
    data: { id: "board1", name: "Board", members: [MEMBER] },
  } as any);

  const startConversation = vi
    .spyOn(chatApi, "startConversation")
    .mockResolvedValue("conv-new-1");

  const readConversation = vi
    .spyOn(chatApi, "readConversation")
    .mockImplementation(async (_env: any, _agent: any, convId: any) =>
      convId === options?.existingConversationId
        ? ({ conversationSteps: [step("what is the budget?", "Fifty thousand.")] } as any)
        : ({ conversationSteps: [] } as any),
    );

  return { registerThread, startConversation, readConversation };
}

function renderThread() {
  return renderPage(
    `/workforce/board1/thread/${MEMBER.agentId}`,
    <WorkforceThread />,
    "/workforce/:boardId/thread/:memberId",
  );
}

async function waitForInit() {
  await waitFor(() => {
    expect(screen.getAllByText(/Test Agent/i).length).toBeGreaterThan(0);
  });
  await waitFor(() => {
    expect(screen.getByRole("textbox")).not.toBeDisabled();
  });
}

describe("WorkforceThread — New conversation", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("offers the control", async () => {
    setup();
    renderThread();
    await waitForInit();

    const button = screen.getByTestId("thread-new-conversation");
    expect(button).toBeInTheDocument();
    expect(button).toHaveAccessibleName("New conversation");
  });

  it("starts a fresh conversation and repoints the stored thread at it", async () => {
    const { registerThread, startConversation } = setup({
      existingConversationId: "conv-old-9",
    });
    renderThread();
    await waitForInit();
    // Resumed: the old conversation's turns are on screen.
    await waitFor(() => {
      expect(screen.getByText("Fifty thousand.")).toBeInTheDocument();
    });
    expect(startConversation).not.toHaveBeenCalled();

    await userEvent.click(screen.getByTestId("thread-new-conversation"));

    await waitFor(() => {
      expect(startConversation).toHaveBeenCalledWith("production", MEMBER.agentId);
    });
    // The stored (board, member) entry must now name the NEW conversation, or
    // the next visit would resume the one the user just left.
    await waitFor(() => {
      expect(registerThread).toHaveBeenCalledWith(
        expect.objectContaining({
          boardId: "board1",
          memberId: MEMBER.agentId,
          conversationId: "conv-new-1",
        }),
      );
    });
  });

  it("clears the transcript so the new conversation starts empty", async () => {
    setup({ existingConversationId: "conv-old-9" });
    renderThread();
    await waitForInit();
    await waitFor(() => {
      expect(screen.getByText("Fifty thousand.")).toBeInTheDocument();
    });

    await userEvent.click(screen.getByTestId("thread-new-conversation"));

    await waitFor(() => {
      expect(screen.queryByText("Fifty thousand.")).not.toBeInTheDocument();
    });
  });

  it("keeps the composer usable afterwards", async () => {
    setup({ existingConversationId: "conv-old-9" });
    renderThread();
    await waitForInit();

    await userEvent.click(screen.getByTestId("thread-new-conversation"));

    await waitFor(() => {
      expect(screen.getByTestId("thread-new-conversation")).not.toBeDisabled();
    });
    expect(screen.getByRole("textbox")).not.toBeDisabled();
  });

  it("reports a failure instead of stranding the thread on a dead id", async () => {
    const { registerThread } = setup({ existingConversationId: "conv-old-9" });
    vi.spyOn(chatApi, "startConversation").mockRejectedValue(new Error("backend down"));
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
    renderThread();
    await waitForInit();
    await waitFor(() => {
      expect(screen.getByText("Fifty thousand.")).toBeInTheDocument();
    });

    await userEvent.click(screen.getByTestId("thread-new-conversation"));

    // The button comes back, and the thread still points at the old, working
    // conversation — a half-applied restart would be worse than none.
    await waitFor(() => {
      expect(screen.getByTestId("thread-new-conversation")).not.toBeDisabled();
    });
    expect(registerThread).not.toHaveBeenCalled();
    expect(screen.getByText("Fifty thousand.")).toBeInTheDocument();
    consoleError.mockRestore();
  });
});
