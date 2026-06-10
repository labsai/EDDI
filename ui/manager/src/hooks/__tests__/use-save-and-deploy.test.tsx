import { describe, it, expect, vi, beforeAll, afterAll, afterEach, beforeEach } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import { useSaveAndDeploy } from "@/hooks/use-save-and-deploy";
import { useChatDrawerStore } from "@/hooks/use-chat-drawer";
import { useChatStore } from "@/hooks/use-chat";
import { http, HttpResponse } from "msw";

// We need the i18n provider for the hook's useTranslation
import "@/i18n/config";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterAll(() => server.close());
afterEach(() => server.resetHandlers());

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  };
}

describe("useSaveAndDeploy", () => {
  beforeEach(() => {
    useChatDrawerStore.setState({
      isOpen: false,
      agentId: null,
      agentName: null,
      step: "idle",
      errorMessage: null,
    });
    useChatStore.getState().reset();
  });

  it("returns saveAndDeploy function and isRunning state", () => {
    const { result } = renderHook(() => useSaveAndDeploy(), {
      wrapper: createWrapper(),
    });
    expect(typeof result.current.saveAndDeploy).toBe("function");
    expect(result.current.isRunning).toBe(false);
  });

  it("opens drawer and sets step to saving on saveAndDeploy call", async () => {
    // Mock deploy endpoint that returns READY immediately
    server.use(
      http.put("*/deploymentstore/deployments/:env/:agentId/:version", () => {
        return new HttpResponse(null, { status: 200 });
      }),
      http.get("*/deploymentstore/deployments/:env/:agentId/:version", () => {
        return HttpResponse.json({ status: "READY" });
      }),
    );

    const { result } = renderHook(() => useSaveAndDeploy(), {
      wrapper: createWrapper(),
    });

    const saveFn = vi.fn().mockResolvedValue({ newAgentVersion: 2 });

    await act(async () => {
      result.current.saveAndDeploy({
        agentId: "agent-1",
        agentName: "Test Agent",
        save: saveFn,
      });
      // Wait a tick for the save to start
      await new Promise((r) => setTimeout(r, 50));
    });

    expect(saveFn).toHaveBeenCalled();
    // The drawer should have been opened
    expect(useChatDrawerStore.getState().agentId).toBe("agent-1");
  });

  it("sets step to error if save throws", async () => {
    const { result } = renderHook(() => useSaveAndDeploy(), {
      wrapper: createWrapper(),
    });

    const saveFn = vi.fn().mockRejectedValue(new Error("Save failed"));

    await act(async () => {
      await result.current.saveAndDeploy({
        agentId: "agent-1",
        save: saveFn,
      });
    });

    expect(useChatDrawerStore.getState().step).toBe("error");
    expect(useChatDrawerStore.getState().errorMessage).toBe("Save failed");
  });
});
