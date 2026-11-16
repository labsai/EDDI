import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import * as chatApi from "@/lib/api/chat";
import { ChatPanel } from "../chat-panel";
import { useChatStore } from "@/hooks/use-chat";

/**
 * A pause decided elsewhere, then a next turn that pauses again.
 *
 * The notice's onResolved used to clear only the banner. The controller still
 * held AWAITING_HUMAN as the state before the next send, so a second turn that
 * paused read exactly like one the server dropped into a paused conversation:
 * its message was withdrawn, "…was not sent" appeared, and the panel never
 * showed the new pause.
 *
 * The notice's own watch polls approval-status every few seconds; here it is a
 * button that reports the resolution, which is all the panel sees of it.
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

const DESCRIPTORS = "descriptors";

function snapshot(conversationState: string, text: string) {
  return {
    agentId: "agent1",
    agentVersion: 1,
    conversationId: "conv-1",
    conversationState,
    environment: "production",
    conversationSteps: [],
    conversationOutputs: [{ output: [{ type: "text", text }] }],
  };
}

describe("ChatPanel — a pause resolved elsewhere, then a second pause", () => {
  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    vi.restoreAllMocks();
    useChatStore.getState().reset();
    useChatStore.setState({ streamingEnabled: false });
    server.use(
      http.get("*/agents/:conversationId", ({ params }) =>
        params.conversationId === DESCRIPTORS ? undefined : HttpResponse.json(snapshot("READY", "Welcome")),
      ),
    );
  });

  it("shows the second turn as paused and keeps its message", async () => {
    const send = vi
      .spyOn(chatApi, "sendMessage")
      .mockResolvedValueOnce(snapshot("AWAITING_HUMAN", "Waiting for approval one") as never)
      .mockResolvedValueOnce(snapshot("AWAITING_HUMAN", "Waiting for approval two") as never);

    renderWithProviders(<ChatPanel />, {
      initialRoute: "/manage/chat?agentId=agent1&conversationId=conv-1",
    });
    await waitFor(() => expect(useChatStore.getState().conversationId).toBe("conv-1"));
    await waitFor(() => expect(screen.getByRole("textbox")).not.toBeDisabled());
    const user = userEvent.setup();

    await user.type(screen.getByRole("textbox"), "first request");
    await user.keyboard("{Enter}");
    await waitFor(() => expect(useChatStore.getState().isPaused).toBe(true));
    expect(screen.getByTestId("chat-pause-banner")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "resolved elsewhere" }));
    expect(useChatStore.getState().isPaused).toBe(false);
    expect(useChatStore.getState().conversationState).not.toBe("AWAITING_HUMAN");

    await waitFor(() => expect(screen.getByRole("textbox")).not.toBeDisabled());
    await user.type(screen.getByRole("textbox"), "second request");
    await user.keyboard("{Enter}");

    await waitFor(() => expect(send).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(useChatStore.getState().isPaused).toBe(true));
    expect(screen.getByTestId("chat-pause-banner")).toBeInTheDocument();
    expect(screen.getByText("second request")).toBeInTheDocument();
    expect(screen.getByText("Waiting for approval two")).toBeInTheDocument();
    expect(screen.queryByText(/was not sent/i)).toBeNull();
  });
});
