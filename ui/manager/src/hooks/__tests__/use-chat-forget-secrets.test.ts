import { afterEach, describe, expect, it, vi } from "vitest";
import { chatConversation, useChatStore } from "@/hooks/use-chat";

/**
 * The chat's controller is app-wide and keeps the raw text of every secret
 * turn as a masking fallback. Replacing the conversation in the store — another
 * agent, a cleared transcript, the reset on logout — must drop them, or they
 * stay in memory for the rest of the session.
 */
describe("chat store — secrets do not outlive the conversation", () => {
  const secrets = () => (chatConversation as unknown as { secretTexts: Set<string> }).secretTexts;

  afterEach(() => {
    secrets().clear();
    useChatStore.getState().reset();
  });

  it.each([
    ["setSelectedAgent", () => useChatStore.getState().setSelectedAgent("agent2", "Other")],
    ["clearMessages", () => useChatStore.getState().clearMessages()],
    ["reset", () => useChatStore.getState().reset()],
  ])("%s forgets the secrets typed so far", (_name, replace) => {
    const forget = vi.spyOn(chatConversation, "forgetSecrets");
    secrets().add("hunter2");
    replace();
    expect(forget).toHaveBeenCalled();
    expect(secrets().size).toBe(0);
    forget.mockRestore();
  });
});
