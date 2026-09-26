import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";

vi.mock("@/lib/api/cascade-save", async () => {
  const actual = await vi.importActual<typeof import("@/lib/api/cascade-save")>(
    "@/lib/api/cascade-save",
  );
  return { ...actual, cascadeSaveResource: vi.fn() };
});

import { CascadeSaveError, cascadeSaveResource } from "@/lib/api/cascade-save";
import { useUpdateAgentPrompt, type AgentPromptData } from "@/hooks/use-agent-prompt";

/**
 * The prompt editor re-resolves its versions from the agent, and a cascade that
 * failed after the LLM hop leaves the agent untouched — so the retry used to
 * resolve the same, superseded LLM version and 409 on every attempt.
 */

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

const PROMPT: AgentPromptData = {
  systemMessage: "old",
  llmConfig: { tasks: [{ parameters: { systemMessage: "old" } }] } as AgentPromptData["llmConfig"],
  llmId: "llm1",
  llmVersion: 1,
  workflowId: "wf1",
  workflowVersion: 1,
  agentVersion: 1,
};

beforeEach(() => {
  vi.mocked(cascadeSaveResource).mockReset();
});

describe("useUpdateAgentPrompt — retrying after a partial cascade", () => {
  it("retries from the versions the failed cascade wrote", async () => {
    const retryContext = {
      workflowId: "wf1",
      workflowVersion: 2,
      agentId: "agent1",
      agentVersion: 1,
      agentWorkflowVersion: 1,
    };
    vi.mocked(cascadeSaveResource)
      .mockRejectedValueOnce(
        new CascadeSaveError(new Error("agent conflict"), {
          newResourceVersion: 2,
          newWorkflowVersion: 2,
          retryContext,
        }),
      )
      .mockResolvedValueOnce({ newResourceVersion: 3, newWorkflowVersion: 3, newAgentVersion: 2 });

    const { result } = renderHook(() => useUpdateAgentPrompt(), { wrapper });
    const vars = { agentId: "agent1", promptData: PROMPT, newSystemMessage: "new" };

    await act(async () => {
      await result.current.mutateAsync(vars).catch(() => undefined);
    });
    await act(async () => {
      await result.current.mutateAsync(vars);
    });

    const [, , llmVersion, , context] = vi.mocked(cascadeSaveResource).mock.calls[1]!;
    expect(llmVersion).toBe(2);
    expect(context).toEqual(retryContext);
  });

  it("does not apply a recovery to prompt data that has moved on", async () => {
    vi.mocked(cascadeSaveResource)
      .mockRejectedValueOnce(
        new CascadeSaveError(new Error("wf conflict"), {
          newResourceVersion: 2,
          retryContext: { workflowId: "wf1", workflowVersion: 1, agentId: "agent1", agentVersion: 1 },
        }),
      )
      .mockResolvedValue({ newResourceVersion: 9, newWorkflowVersion: 9, newAgentVersion: 9 });

    const { result } = renderHook(() => useUpdateAgentPrompt(), { wrapper });
    await act(async () => {
      await result.current
        .mutateAsync({ agentId: "agent1", promptData: PROMPT, newSystemMessage: "new" })
        .catch(() => undefined);
    });
    // Someone else saved meanwhile: the prompt data now names agent v5.
    const moved = { ...PROMPT, llmVersion: 7, workflowVersion: 4, agentVersion: 5 };
    await act(async () => {
      await result.current.mutateAsync({ agentId: "agent1", promptData: moved, newSystemMessage: "new" });
    });

    const [, , llmVersion, , context] = vi.mocked(cascadeSaveResource).mock.calls[1]!;
    expect(llmVersion).toBe(7);
    expect(context).toEqual({ workflowId: "wf1", workflowVersion: 4, agentId: "agent1", agentVersion: 5 });
  });
});
