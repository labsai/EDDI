import { agentWriteInvalidations } from "@/lib/query-keys";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getAgent, parseResourceUri } from "@/lib/api/agents";
import { getWorkflow } from "@/lib/api/workflows";
import { getResource, getResourceType } from "@/lib/api/resources";
import { cascadeSaveResource, type CascadeContext } from "@/lib/api/cascade-save";
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

      const context: CascadeContext = {
        workflowId: promptData.workflowId,
        workflowVersion: promptData.workflowVersion,
        agentId,
        agentVersion: promptData.agentVersion,
      };

      // cascadeSaveResource handles: PUT LLM → update Workflow → update Agent
      return cascadeSaveResource(
        LLM_RT,
        promptData.llmId,
        promptData.llmVersion,
        updatedLlmConfig,
        context
      );
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
