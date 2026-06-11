import { act } from "@testing-library/react";
import { useChatDrawerStore } from "@/hooks/use-chat-drawer";

describe("useChatDrawerStore", () => {
  beforeEach(() => {
    act(() => {
      useChatDrawerStore.getState().close();
    });
  });

  it("starts closed with null agent", () => {
    const state = useChatDrawerStore.getState();
    expect(state.isOpen).toBe(false);
    expect(state.agentId).toBeNull();
    expect(state.agentName).toBeNull();
    expect(state.step).toBe("idle");
    expect(state.errorMessage).toBeNull();
  });

  it("open sets agentId and opens drawer", () => {
    act(() => {
      useChatDrawerStore.getState().open("agent1", "My Agent");
    });
    const state = useChatDrawerStore.getState();
    expect(state.isOpen).toBe(true);
    expect(state.agentId).toBe("agent1");
    expect(state.agentName).toBe("My Agent");
    expect(state.step).toBe("idle");
    expect(state.errorMessage).toBeNull();
  });

  it("open defaults agentName to 'Agent'", () => {
    act(() => {
      useChatDrawerStore.getState().open("agent2");
    });
    expect(useChatDrawerStore.getState().agentName).toBe("Agent");
  });

  it("close resets all state", () => {
    act(() => {
      useChatDrawerStore.getState().open("agent1", "Test");
      useChatDrawerStore.getState().setStep("deploying");
      useChatDrawerStore.getState().close();
    });
    const state = useChatDrawerStore.getState();
    expect(state.isOpen).toBe(false);
    expect(state.agentId).toBeNull();
    expect(state.agentName).toBeNull();
    expect(state.step).toBe("idle");
  });

  it("setStep changes step", () => {
    act(() => {
      useChatDrawerStore.getState().setStep("saving");
    });
    expect(useChatDrawerStore.getState().step).toBe("saving");
    expect(useChatDrawerStore.getState().errorMessage).toBeNull();
  });

  it("setStep with error message", () => {
    act(() => {
      useChatDrawerStore.getState().setStep("error", "Deploy failed");
    });
    const state = useChatDrawerStore.getState();
    expect(state.step).toBe("error");
    expect(state.errorMessage).toBe("Deploy failed");
  });

  it("setStep without error clears previous error", () => {
    act(() => {
      useChatDrawerStore.getState().setStep("error", "Oops");
      useChatDrawerStore.getState().setStep("ready");
    });
    expect(useChatDrawerStore.getState().errorMessage).toBeNull();
  });
});
