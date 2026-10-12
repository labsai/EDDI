import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import { useSaveAndDeploy } from "@/hooks/use-save-and-deploy";
import { useChatDrawerStore } from "@/hooks/use-chat-drawer";
import { useChatStore } from "@/hooks/use-chat";
import { http, HttpResponse } from "msw";

// We need the i18n provider for the hook's useTranslation
import "@/i18n/config";

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

  it("saves, deploys the saved version to production, waits for READY and opens a chat", async () => {
    // The real routes deployAgent / getDeploymentStatus call. The handlers used
    // to sit on /deploymentstore/... — a path the hook never requests — so the
    // test passed without a deploy ever being asked for.
    const deploys: string[] = [];
    const statusReads: string[] = [];
    server.use(
      http.post("*/administration/:env/deploy/:agentId", ({ params, request }) => {
        const version = new URL(request.url).searchParams.get("version");
        deploys.push(`${params.env}/${params.agentId}@${version}`);
        return new HttpResponse(null, { status: 202 });
      }),
      http.get("*/administration/:env/deploymentstatus/:agentId", ({ params, request }) => {
        const version = new URL(request.url).searchParams.get("version");
        statusReads.push(`${params.env}/${params.agentId}@${version}`);
        return HttpResponse.json({ status: "READY" });
      }),
    );
    vi.useFakeTimers({ toFake: ["setTimeout"] });
    try {
      const { result } = renderHook(() => useSaveAndDeploy(), {
        wrapper: createWrapper(),
      });
      const saveFn = vi.fn().mockResolvedValue({ newAgentVersion: 2 });

      let done = false;
      await act(async () => {
        void result.current
          .saveAndDeploy({ agentId: "agent-1", agentName: "Test Agent", save: saveFn })
          .then(() => {
            done = true;
          });
        for (let i = 0; i < 60 && !done; i++) {
          await vi.advanceTimersByTimeAsync(100);
        }
      });

      expect(done).toBe(true);
      expect(saveFn).toHaveBeenCalledTimes(1);
      expect(deploys).toEqual(["production/agent-1@2"]);
      expect(statusReads).toEqual(["production/agent-1@2"]);
      const drawer = useChatDrawerStore.getState();
      expect(drawer.agentId).toBe("agent-1");
      expect(drawer.errorMessage).toBeNull();
      expect(drawer.step).toBe("ready");
    } finally {
      vi.useRealTimers();
    }
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

  it("reports a deployment ERROR at once instead of timing out 30 s later", async () => {
    // The ERROR throw used to sit inside the poll's try/catch, which swallowed
    // it on every attempt but the last — so the flow polled for the full 30 s
    // and then said "Deploy timed out" about a deployment that had failed.
    let statusReads = 0;
    server.use(
      http.post("*/administration/:env/deploy/:agentId", () => new HttpResponse(null, { status: 202 })),
      http.get("*/administration/:env/deploymentstatus/:agentId", () => {
        statusReads++;
        return HttpResponse.json({ status: "ERROR" });
      }),
    );
    vi.useFakeTimers({ toFake: ["setTimeout"] });
    try {
      const { result } = renderHook(() => useSaveAndDeploy(), {
        wrapper: createWrapper(),
      });

      let done = false;
      await act(async () => {
        void result.current
          .saveAndDeploy({ agentId: "agent-1", save: async () => ({ newAgentVersion: 2 }) })
          .then(() => {
            done = true;
          });
        // One poll interval — the first status read answers ERROR. A few more
        // seconds let the request settle, far short of the 30 s timeout.
        for (let i = 0; i < 40 && !done; i++) {
          await vi.advanceTimersByTimeAsync(100);
        }
      });

      expect(done).toBe(true);
      expect(statusReads).toBe(1);
      expect(useChatDrawerStore.getState().step).toBe("error");
      expect(useChatDrawerStore.getState().errorMessage).toBe("Deployment failed");
    } finally {
      vi.useRealTimers();
    }
  });
});
