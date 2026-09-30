import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { renderWithProviders } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { ChatPanel } from "../chat-panel";
import { useChatStore } from "@/hooks/use-chat";

/**
 * "Continue in Chat" on the conversation page navigates to
 * `/manage/chat?agentId=…&conversationId=…`. The panel read only `agentId`, so it
 * reopened the agent's MOST RECENT conversation instead of the one the user was
 * looking at.
 */

/**
 * The `/agents/:conversationId` handlers also match `GET /agentstore/agents/descriptors`,
 * which the panel reads to list deployed agents — and a deep link now waits for
 * that list before it acts. Let that request fall through to the default mock.
 */
const DESCRIPTORS = "descriptors";

function snapshot(conversationId: string, text: string) {
  return {
    agentId: "agent1",
    agentVersion: 1,
    conversationId,
    conversationState: "READY",
    environment: "production",
    conversationSteps: [
      { conversationStep: [{ key: "input:initial", value: `question in ${conversationId}` }] },
    ],
    conversationOutputs: [
      { input: `question in ${conversationId}`, output: [{ type: "text", text }] },
    ],
  };
}

describe("ChatPanel — Continue in Chat opens the named conversation", () => {
  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  beforeEach(() => {
    useChatStore.getState().reset();
    useChatStore.setState({ streamingEnabled: false });
  });

  it("loads the conversationId from the URL, not the agent's most recent one", async () => {
    const reads: string[] = [];
    let started = false;
    server.use(
      http.post("*/agents/:agentId/start", () => {
        started = true;
        return new HttpResponse(null, { status: 201 });
      }),
      http.get("*/agents/:conversationId", ({ params }) => {
        const id = String(params.conversationId);
        if (id === DESCRIPTORS) return;
        reads.push(id);
        return HttpResponse.json(snapshot(id, `answer from ${id}`));
      }),
    );

    renderWithProviders(<ChatPanel />, {
      initialRoute: "/manage/chat?agentId=agent1&conversationId=conv-older",
    });

    await waitFor(() => expect(useChatStore.getState().conversationId).toBe("conv-older"));
    expect(await screen.findByText("answer from conv-older")).toBeInTheDocument();
    expect(reads).toContain("conv-older");
    // Reading the named conversation is all a deep link does: nothing is started.
    expect(started).toBe(false);
    expect(useChatStore.getState().selectedAgentId).toBe("agent1");
  });

  it("switches to the named conversation even when its agent is already selected", async () => {
    useChatStore.getState().setSelectedAgent("agent1", "Support Agent");
    useChatStore.getState().setConversationId("conv-current");
    server.use(
      http.get("*/agents/:conversationId", ({ params }) =>
        params.conversationId === DESCRIPTORS
          ? undefined
          : HttpResponse.json(snapshot(String(params.conversationId), "the older one")),
      ),
    );

    renderWithProviders(<ChatPanel />, {
      initialRoute: "/manage/chat?agentId=agent1&conversationId=conv-older",
    });

    await waitFor(() => expect(useChatStore.getState().conversationId).toBe("conv-older"));
  });

  /**
   * While the named conversation is read, the transcript on screen is still the
   * one being left. A send in that window was delivered to the OLD conversation
   * and then vanished when the load installed over it.
   */
  it.each([
    ["the chat input and quick replies", false],
    ["the requested secret field", true],
  ])("blocks %s until the named conversation has loaded", async (_what, withSecretField) => {
    useChatStore.getState().setSelectedAgent("agent1", "Support Agent");
    useChatStore.getState().setConversationId("conv-current");
    useChatStore.getState().setQuickReplies(["yes", "no"]);
    if (withSecretField) {
      useChatStore.getState().setInputField({ subType: "password", label: "API Key" });
    }
    let release!: () => void;
    const released = new Promise<void>((r) => (release = r));
    const says: string[] = [];
    server.use(
      http.get("*/agents/:conversationId", async ({ params }) => {
        if (params.conversationId === DESCRIPTORS) return;
        await released;
        return HttpResponse.json(snapshot(String(params.conversationId), "the older one"));
      }),
      http.post("*/agents/:conversationId", ({ params }) => {
        says.push(String(params.conversationId));
        return HttpResponse.json(snapshot(String(params.conversationId), "reply"));
      }),
    );

    renderWithProviders(<ChatPanel />, {
      initialRoute: "/manage/chat?agentId=agent1&conversationId=conv-older",
    });

    await waitFor(() => expect(useChatStore.getState().loadingConversationId).toBe("conv-older"));
    if (withSecretField) {
      expect(screen.getByTestId("secret-input-field")).toBeDisabled();
      expect(screen.getByTestId("secret-input-send")).toBeDisabled();
    } else {
      expect(screen.getByTestId("chat-input")).toBeDisabled();
      expect(screen.queryByTestId("quick-reply-btn")).not.toBeInTheDocument();
    }
    expect(says).toEqual([]);

    release();
    await waitFor(() => expect(useChatStore.getState().conversationId).toBe("conv-older"));
    await waitFor(() => expect(screen.getByTestId("chat-input")).toBeEnabled());
  });

  it("offers the masked field again when the backend refuses the secret answer", async () => {
    // The panel used to clear the field itself before the send had read it, so
    // a refused answer left the retry to the plain textarea.
    useChatStore.getState().setSelectedAgent("agent1", "Support Agent");
    useChatStore.getState().setConversationId("conv-current");
    useChatStore.getState().setInputField({ subType: "password", label: "API Key" });
    server.use(
      http.post("*/agents/:conversationId", () => new HttpResponse("busy", { status: 409 })),
      http.get("*/agents/:conversationId", () =>
        HttpResponse.json({ ...snapshot("conv-current", "x"), conversationState: "READY" }),
      ),
    );

    renderWithProviders(<ChatPanel />, { initialRoute: "/manage/chat" });
    // Sends open once the agent's review lookup has answered.
    await waitFor(() => expect(screen.getByLabelText("API Key")).toBeEnabled());

    await userEvent.type(screen.getByLabelText("API Key"), "sk-live-key{Enter}");

    await waitFor(() => expect(useChatStore.getState().isProcessing).toBe(false));
    await waitFor(() => expect(useChatStore.getState().messages).toEqual([]));
    expect(screen.getByLabelText("API Key")).toHaveValue("sk-live-key");
    expect(screen.queryByTestId("chat-input")).not.toBeInTheDocument();
  });

  it("keeps the typed secret when a load starts in the render gap before the send", async () => {
    // The field is disabled while a load reads, but a load that begins between
    // the last render and the keypress still sees it enabled.
    useChatStore.getState().setSelectedAgent("agent1", "Support Agent");
    useChatStore.getState().setConversationId("conv-current");
    useChatStore.getState().setInputField({ subType: "password", label: "API Key" });
    const says: string[] = [];
    server.use(
      http.post("*/agents/:conversationId", ({ params }) => {
        says.push(String(params.conversationId));
        return HttpResponse.json(snapshot(String(params.conversationId), "reply"));
      }),
    );

    renderWithProviders(<ChatPanel />, { initialRoute: "/manage/chat" });
    const field = screen.getByLabelText("API Key");
    // Sends open once the agent's review lookup has answered.
    await waitFor(() => expect(field).toBeEnabled());
    await userEvent.type(field, "sk-live-key");

    act(() => {
      useChatStore.setState({ loadingConversationId: "conv-older" });
      fireEvent.keyDown(field, { key: "Enter" });
    });

    expect(says).toEqual([]);
    expect(screen.getByLabelText("API Key")).toHaveValue("sk-live-key");
  });
});
