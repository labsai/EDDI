/* eslint-disable @typescript-eslint/no-explicit-any */
import { describe, it, expect, beforeEach, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useNavigate } from "react-router-dom";
import { createTestQueryClient, userEvent } from "@/test/test-utils";
import { ThemeProvider } from "@/components/layout/theme-provider";

import * as useGroupsHook from "@/hooks/use-groups";
import * as useWorkforceThreadsHook from "@/hooks/use-workforce-threads";
import * as chatApi from "@/lib/api/chat";
import { ApiClientError } from "@/lib/api-client";

import { WorkforceThread } from "../workforce-thread";

/**
 * Opening an advisor thread: a failure is shown rather than swallowed, a
 * conversation that no longer exists is replaced rather than resumed, and
 * moving to another advisor's thread (same page, new route param) opens THAT
 * advisor's conversation.
 */

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;
window.HTMLElement.prototype.scrollIntoView = vi.fn();
window.HTMLElement.prototype.scrollTo = vi.fn();

const MEMBERS = [
  { agentId: "agent1", displayName: "First Advisor" },
  { agentId: "agent2", displayName: "Second Advisor" },
];

function step(input: string, output: string) {
  return {
    timestamp: "2026-09-22T10:00:00.000Z",
    conversationStep: [
      { key: "input:initial", value: input },
      { key: "output:text:default", value: output },
    ],
  };
}

function mockThreads(stored: Record<string, string>) {
  const registerThread = vi.fn();
  vi.spyOn(useWorkforceThreadsHook, "useWorkforceThreads").mockReturnValue({
    getThread: (_board: string, member: string) =>
      stored[member]
        ? {
            memberId: member,
            memberName: member,
            conversationId: stored[member],
            boardId: "board1",
            lastActivity: Date.now(),
          }
        : null,
    registerThread,
    updateActivity: vi.fn(),
  } as any);
  vi.spyOn(useGroupsHook, "useGroup").mockReturnValue({
    data: { id: "board1", name: "Board", members: MEMBERS },
  } as any);
  return { registerThread };
}

function GoTo({ to }: { to: string }) {
  const navigate = useNavigate();
  return (
    <button type="button" onClick={() => navigate(to)}>
      go
    </button>
  );
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <QueryClientProvider client={createTestQueryClient()}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <GoTo to="/workforce/board1/thread/agent2" />
          <Routes>
            <Route path="/workforce/:boardId/thread/:memberId" element={<WorkforceThread />} />
          </Routes>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe("WorkforceThread — opening a thread", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("shows why a thread could not be opened, and offers a way on", async () => {
    mockThreads({});
    vi.spyOn(chatApi, "startConversation").mockRejectedValue(new Error("Agent is not deployed"));
    renderAt("/workforce/board1/thread/agent1");

    const panel = await screen.findByTestId("thread-init-error");
    expect(panel).toHaveTextContent("Agent is not deployed");
    expect(screen.getByTestId("thread-init-retry")).toBeInTheDocument();
    expect(screen.getByTestId("thread-init-new")).toBeInTheDocument();
  });

  it("starts a fresh conversation when the stored one no longer exists", async () => {
    const { registerThread } = mockThreads({ agent1: "conv-gone" });
    vi.spyOn(chatApi, "readConversation").mockImplementation(async (_e: any, _a: any, id: any) => {
      if (id === "conv-gone") throw new ApiClientError(404, "Not Found", "/x");
      return { conversationSteps: [] } as any;
    });
    const start = vi.spyOn(chatApi, "startConversation").mockResolvedValue("conv-fresh");
    renderAt("/workforce/board1/thread/agent1");

    await waitFor(() => expect(start).toHaveBeenCalledWith("production", "agent1"));
    await waitFor(() =>
      expect(registerThread).toHaveBeenCalledWith(expect.objectContaining({ conversationId: "conv-fresh" })),
    );
    expect(screen.queryByTestId("thread-init-error")).not.toBeInTheDocument();
  });

  it("opens the other advisor's own conversation when the route moves to it", async () => {
    mockThreads({ agent1: "conv-a1", agent2: "conv-a2" });
    const read = vi.spyOn(chatApi, "readConversation").mockImplementation(async (_e: any, _a: any, id: any) =>
      id === "conv-a1"
        ? ({ conversationSteps: [step("hi one", "Answer from the first advisor")] } as any)
        : ({ conversationSteps: [step("hi two", "Answer from the second advisor")] } as any),
    );
    renderAt("/workforce/board1/thread/agent1");
    expect(await screen.findByText("Answer from the first advisor")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "go" }));

    expect(await screen.findByText("Answer from the second advisor")).toBeInTheDocument();
    expect(screen.queryByText("Answer from the first advisor")).not.toBeInTheDocument();
    expect(read).toHaveBeenCalledWith("production", "agent2", "conv-a2");
  });
});
