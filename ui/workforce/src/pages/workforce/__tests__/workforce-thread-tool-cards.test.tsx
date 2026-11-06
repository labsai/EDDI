/* eslint-disable @typescript-eslint/no-explicit-any */
import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { renderPage, userEvent } from "@eddi/app-kit/test/test-utils";

import * as useGroupsHook from "@eddi/app-kit/hooks/use-groups";
import * as useWorkforceThreadsHook from "@/hooks/use-workforce-threads";
import * as chatApi from "@eddi/app-kit/lib/api/chat";

import { WorkforceThread } from "@/pages/workforce/workforce-thread";

/** T1.9 in the member thread: the turn's `tool_result` events as cards under its question. */

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
    data: { id: "board1", name: "Board", members: [{ agentId: "agent1", displayName: "Test Agent" }] },
  } as any);
  vi.spyOn(chatApi, "startConversation").mockResolvedValue("conv-1");
  vi.spyOn(chatApi, "readConversation").mockResolvedValue({ conversationSteps: [] } as any);
  (vi.spyOn(chatApi, "sendMessageStreaming") as any).mockImplementation(stream);
}

async function renderAndSend(text: string) {
  renderPage("/workforce/board1/thread/agent1", <WorkforceThread />, "/workforce/:boardId/thread/:memberId");
  await waitFor(() => expect(screen.getByRole("textbox")).not.toBeDisabled());
  const user = userEvent.setup();
  await user.type(screen.getByRole("textbox"), text);
  await user.keyboard("{Enter}");
}

describe("WorkforceThread — tool cards", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("shows the turn's tool calls, collapsed, between the question and the reply", async () => {
    setupMocks(async function* () {
      yield { type: "tool_call", data: JSON.stringify({ tool: "lookup_order" }) };
      yield { type: "tool_result", data: JSON.stringify({ tool: "lookup_order", ok: true, summary: "Order <i>1042</i> shipped" }) };
      yield { type: "token", data: "It shipped yesterday." };
      yield { type: "done", data: "" };
    });

    await renderAndSend("Where is my order?");
    await waitFor(() => expect(screen.getByText("It shipped yesterday.")).toBeInTheDocument());

    const group = screen.getByTestId("tool-activity");
    const precedes = (a: Element, b: Element) => (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
    expect(precedes(screen.getByText("Where is my order?"), group)).toBe(true);
    expect(precedes(group, screen.getByText("It shipped yesterday."))).toBe(true);

    const toggle = within(group).getByTestId("tool-activity-toggle");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    await userEvent.setup().click(toggle);
    const card = within(group).getByTestId("tool-card");
    expect(card).toHaveTextContent("lookup_order");
    expect(within(card).getByTestId("tool-card-summary").textContent).toBe("Order <i>1042</i> shipped");
    expect(group.querySelector("i")).toBeNull();
  });

  it("shows no cards for a turn that ran no tools", async () => {
    setupMocks(async function* () {
      yield { type: "token", data: "Hello there." };
      yield { type: "done", data: "" };
    });
    await renderAndSend("Hi");
    await waitFor(() => expect(screen.getByText("Hello there.")).toBeInTheDocument());
    expect(screen.queryByTestId("tool-activity")).toBeNull();
  });
});
