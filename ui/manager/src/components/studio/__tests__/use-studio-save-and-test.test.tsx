import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useStudioSaveAndTest } from "@/components/studio/use-studio-save-and-test";

const deployAgent = vi.fn();
const getDeploymentStatus = vi.fn();
vi.mock("@/lib/api/agents", () => ({
  deployAgent: (...args: unknown[]) => deployAgent(...args),
  getDeploymentStatus: (...args: unknown[]) => getDeploymentStatus(...args),
}));

const clearMessages = vi.fn();
const setSelectedAgent = vi.fn();
const startConversation = vi.fn();
vi.mock("@/hooks/use-chat", () => ({
  useChatStore: { getState: () => ({ clearMessages, setSelectedAgent }) },
  useStartConversation: () => ({ mutateAsync: startConversation }),
}));

const toastSuccess = vi.fn();
const toastError = vi.fn();
vi.mock("sonner", () => ({
  toast: Object.assign(vi.fn(), {
    success: (...args: unknown[]) => toastSuccess(...args),
    error: (...args: unknown[]) => toastError(...args),
  }),
}));

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

async function run(save: () => Promise<{ newAgentVersion: number }>, advanceMs: number) {
  const { result } = renderHook(() => useStudioSaveAndTest(), { wrapper });
  await act(async () => {
    const done = result.current.saveAndTest({ agentId: "agent1", agentName: "Agent One", save });
    await vi.advanceTimersByTimeAsync(advanceMs);
    await done;
  });
  return result;
}

describe("useStudioSaveAndTest", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    deployAgent.mockResolvedValue(undefined);
    startConversation.mockResolvedValue(undefined);
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("saves, deploys what the save created, then opens the chat on this agent", async () => {
    getDeploymentStatus.mockResolvedValue({ status: "READY" });
    await run(async () => ({ newAgentVersion: 7 }), 2000);

    expect(deployAgent).toHaveBeenCalledWith("production", "agent1", 7);
    expect(getDeploymentStatus).toHaveBeenCalledWith("production", "agent1", 7);
    expect(setSelectedAgent).toHaveBeenCalledWith("agent1", "Agent One");
    expect(clearMessages).toHaveBeenCalled();
    expect(startConversation).toHaveBeenCalledWith({ agentId: "agent1", environment: "production" });
    expect(toastSuccess).toHaveBeenCalled();
    expect(toastError).not.toHaveBeenCalled();
  });

  it("deploys nothing when the save fails", async () => {
    await run(() => Promise.reject(new Error("save refused")), 0);
    expect(deployAgent).not.toHaveBeenCalled();
    expect(toastError).toHaveBeenCalledWith("save refused");
    expect(startConversation).not.toHaveBeenCalled();
  });

  it("stops with an error when the deployment never becomes ready", async () => {
    getDeploymentStatus.mockResolvedValue({ status: "IN_PROGRESS" });
    await run(async () => ({ newAgentVersion: 2 }), 40_000);
    expect(toastError).toHaveBeenCalledTimes(1);
    expect(startConversation).not.toHaveBeenCalled();
  });

  it("reports a failed deployment at once", async () => {
    getDeploymentStatus.mockResolvedValue({ status: "ERROR" });
    await run(async () => ({ newAgentVersion: 2 }), 2000);
    expect(toastError).toHaveBeenCalledTimes(1);
    expect(getDeploymentStatus).toHaveBeenCalledTimes(1);
    expect(startConversation).not.toHaveBeenCalled();
  });
});
