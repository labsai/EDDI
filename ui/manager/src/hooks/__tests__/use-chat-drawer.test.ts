import { describe, it, expect, beforeEach } from "vitest";
import { useChatDrawerStore } from "@/hooks/use-chat-drawer";
import type { ChatDrawerStep } from "@/hooks/use-chat-drawer";

describe("useChatDrawerStore", () => {
  beforeEach(() => {
    // Reset to initial state
    useChatDrawerStore.setState({
      isOpen: false,
      agentId: null,
      agentName: null,
      step: "idle",
      errorMessage: null,
    });
  });

  it("starts with default state", () => {
    const state = useChatDrawerStore.getState();
    expect(state.isOpen).toBe(false);
    expect(state.agentId).toBeNull();
    expect(state.agentName).toBeNull();
    expect(state.step).toBe("idle");
    expect(state.errorMessage).toBeNull();
  });

  it("opens with agentId and optional agentName", () => {
    useChatDrawerStore.getState().open("agent-123", "Test Agent");
    const state = useChatDrawerStore.getState();
    expect(state.isOpen).toBe(true);
    expect(state.agentId).toBe("agent-123");
    expect(state.agentName).toBe("Test Agent");
    expect(state.step).toBe("idle");
    expect(state.errorMessage).toBeNull();
  });

  it("defaults agentName to 'Agent' when not provided", () => {
    useChatDrawerStore.getState().open("agent-456");
    expect(useChatDrawerStore.getState().agentName).toBe("Agent");
  });

  it("closes and resets all fields", () => {
    useChatDrawerStore.getState().open("agent-123", "My Agent");
    useChatDrawerStore.getState().setStep("ready");
    useChatDrawerStore.getState().close();

    const state = useChatDrawerStore.getState();
    expect(state.isOpen).toBe(false);
    expect(state.agentId).toBeNull();
    expect(state.agentName).toBeNull();
    expect(state.step).toBe("idle");
    expect(state.errorMessage).toBeNull();
  });

  it("sets step without error", () => {
    useChatDrawerStore.getState().open("agent-1");
    useChatDrawerStore.getState().setStep("saving");
    expect(useChatDrawerStore.getState().step).toBe("saving");
    expect(useChatDrawerStore.getState().errorMessage).toBeNull();
  });

  it("sets step with error message", () => {
    useChatDrawerStore.getState().open("agent-1");
    useChatDrawerStore.getState().setStep("error", "Something went wrong");
    expect(useChatDrawerStore.getState().step).toBe("error");
    expect(useChatDrawerStore.getState().errorMessage).toBe(
      "Something went wrong",
    );
  });

  it("clears previous error when step changes without error", () => {
    useChatDrawerStore.getState().setStep("error", "Previous error");
    useChatDrawerStore.getState().setStep("deploying");
    expect(useChatDrawerStore.getState().errorMessage).toBeNull();
  });

  it("supports all step values", () => {
    const steps: ChatDrawerStep[] = [
      "idle",
      "saving",
      "deploying",
      "starting",
      "ready",
      "error",
    ];
    for (const step of steps) {
      useChatDrawerStore.getState().setStep(step);
      expect(useChatDrawerStore.getState().step).toBe(step);
    }
  });
});
