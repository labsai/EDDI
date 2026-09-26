import { agentWriteInvalidations } from "@/lib/query-keys";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getAgent, parseResourceUri } from "@/lib/api/agents";
import { getWorkflow } from "@/lib/api/workflows";
import { getResource, getResourceType } from "@/lib/api/resources";
import {
  cascadePartialResult,
  cascadeSaveResource,
  type CascadeContext,
} from "@/lib/api/cascade-save";
import type { LlmConfig } from "@/components/editors/llm/types";

// ─── Types ───────────────────────────────────────────────────────

export interface AgentPromptData {
  systemMessage: string;
  /** Full LLM config — needed for cascade save (we preserve all other fields) */
  llmConfig: LlmConfig;
  /** IDs and versions needed to cascade-save */
  llmId: string;
  llmVersion: number;
  workflowId: string;
  workflowVersion: number;
  agentVersion: number;
}

// ─── Resolver ────────────────────────────────────────────────────

const LLM_RT = getResourceType("llm")!;
const LLM_EXTENSION = "ai.labs.llm";

/**
 * Resolves the system prompt for an agent by traversing:
 *   Agent → Workflow(0) → LLM extension step → tasks[0].parameters.systemMessage
 *
 * Returns null if the agent has no LLM step.
 */
async function resolveAgentPrompt(
  agentId: string,
  agentVersion: number
): Promise<AgentPromptData | null> {
  // 1. Fetch agent → get first workflow URI
  const agent = await getAgent(agentId, agentVersion);
  const workflowUri = agent.workflows?.[0];
  if (!workflowUri) return null;

  const { id: workflowId, version: workflowVersion } =
    parseResourceUri(workflowUri);

  // 2. Fetch workflow → find LLM extension step
  const workflow = await getWorkflow(workflowId, workflowVersion);
  const llmStep = workflow.workflowSteps.find(
    (step) => step.type === LLM_EXTENSION
  );
  if (!llmStep) return null;

  const llmUri = llmStep.config?.uri;
  if (typeof llmUri !== "string") return null;

  const { id: llmId, version: llmVersion } = parseResourceUri(llmUri);

  // 3. Fetch LLM resource → extract system message
  const llmConfig = await getResource<LlmConfig>(LLM_RT, llmId, llmVersion);
  const systemMessage =
    (llmConfig.tasks?.[0]?.parameters?.systemMessage as string) ?? "";

  return {
    systemMessage,
    llmConfig,
    llmId,
    llmVersion,
    workflowId,
    workflowVersion,
    agentVersion,
  };
}

// ─── Query Hook ──────────────────────────────────────────────────

/**
 * Fetches the system prompt for an agent, traversing the full pipeline.
 * Returns `data: null` when the agent has no LLM step.
 */
export function useAgentPrompt(agentId: string | null, agentVersion: number) {
  return useQuery({
    queryKey: ["agent-prompt", agentId, agentVersion],
    queryFn: () => resolveAgentPrompt(agentId!, agentVersion),
    enabled: !!agentId && agentVersion > 0,
    staleTime: 30_000, // Don't refetch too often — this is 3 serial API calls
  });
}

// ─── Mutation Hook ───────────────────────────────────────────────

interface UpdatePromptVars {
  agentId: string;
  promptData: AgentPromptData;
  newSystemMessage: string;
}

/**
 * Where a cascade that failed partway left an agent's prompt chain.
 *
 * `promptData` is resolved from the agent, and a cascade that stopped after the
 * LLM hop (or the workflow hop) left the agent untouched — so re-resolving
 * yields the same, now superseded, LLM version, and every retry 409'd on it.
 */
interface PromptRecovery {
  /** The versions the caller's `promptData` carries — the recovery applies only to them. */
  fromLlmVersion: number;
  fromAgentVersion: number;
  /** What the retry must use instead. */
  llmVersion: number;
  context: CascadeContext;
}

/**
 * Module scope, not the hook instance: the component that failed is often gone
 * by the time the user retries (the editor sheet closed, the page navigated),
 * and a fresh instance would resolve the same superseded LLM version again. The
 * `from*Version` guard keeps a recovery from applying to prompt data that has
 * since moved on, so sharing it is safe.
 */
const recoveries = new Map<string, PromptRecovery>();

/**
 * Updates the system prompt via cascade save:
 *   PUT LLM resource → update Workflow URI → update Agent URI
 */
export function useUpdateAgentPrompt() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ agentId, promptData, newSystemMessage }: UpdatePromptVars) => {
      // Build updated LLM config with new system message
      const updatedTasks = [...(promptData.llmConfig.tasks ?? [])];
      const firstTask = updatedTasks[0];
      if (firstTask) {
        updatedTasks[0] = {
          ...firstTask,
          parameters: {
            ...firstTask.parameters,
            systemMessage: newSystemMessage,
          },
        };
      }
      const updatedLlmConfig: LlmConfig = {
        ...promptData.llmConfig,
        tasks: updatedTasks,
      };

      const recoveryKey = agentId + "/" + promptData.llmId;
      const recovery = recoveries.get(recoveryKey);
      const resume =
        recovery &&
        recovery.fromLlmVersion === promptData.llmVersion &&
        recovery.fromAgentVersion === promptData.agentVersion
          ? recovery
          : undefined;

      const llmVersion = resume?.llmVersion ?? promptData.llmVersion;
      const context: CascadeContext = resume?.context ?? {
        workflowId: promptData.workflowId,
        workflowVersion: promptData.workflowVersion,
        agentId,
        agentVersion: promptData.agentVersion,
      };

      try {
        // cascadeSaveResource handles: PUT LLM → update Workflow → update Agent
        const result = await cascadeSaveResource(
          LLM_RT,
          promptData.llmId,
          llmVersion,
          updatedLlmConfig,
          context
        );
        recoveries.delete(recoveryKey);
        return result;
      } catch (err) {
        const partial = cascadePartialResult(err);
        if (partial?.retryContext) {
          recoveries.set(recoveryKey, {
            fromLlmVersion: promptData.llmVersion,
            fromAgentVersion: promptData.agentVersion,
            llmVersion: partial.newResourceVersion ?? llmVersion,
            context: partial.retryContext,
          });
        }
        throw err;
      }
    },
    onSuccess: (_data, vars) => {
      // Invalidate all related queries
      queryClient.invalidateQueries({
        queryKey: ["agent-prompt", vars.agentId],
      });
      for (const queryKey of agentWriteInvalidations(vars.agentId)) {
        queryClient.invalidateQueries({ queryKey });
      }
    },
  });
}
