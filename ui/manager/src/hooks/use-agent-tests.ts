import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  cancelAgentTestRun,
  getAgentTestRun,
  isRunFinished,
  listAgentTestRuns,
  startAgentTestRun,
  type AgentTestRun,
  type StartRunRequest,
} from "@/lib/api/agent-tests";

/** How often a RUNNING run is re-read. A suite takes seconds to minutes. */
export const RUN_POLL_MS = 2000;

export const agentTestKeys = {
  all: ["agent-tests"] as const,
  runs: (agentId: string) => [...agentTestKeys.all, "runs", agentId] as const,
  run: (runId: string) => [...agentTestKeys.all, "run", runId] as const,
};

/**
 * An agent's run history. Polls while any listed run is still RUNNING, so a run
 * started here (or by CI, or over MCP) turns into its result without a reload.
 */
export function useAgentTestRuns(agentId: string) {
  return useQuery({
    queryKey: agentTestKeys.runs(agentId),
    queryFn: () => listAgentTestRuns(agentId),
    enabled: !!agentId,
    refetchInterval: (query) =>
      query.state.data?.some((r) => !isRunFinished(r.status)) ? RUN_POLL_MS : false,
  });
}

/** One run in full (the list carries it too, but the sheet keeps its own live copy). */
export function useAgentTestRun(runId: string | null) {
  return useQuery({
    queryKey: agentTestKeys.run(runId ?? ""),
    queryFn: () => getAgentTestRun(runId!),
    enabled: !!runId,
    refetchInterval: (query) =>
      query.state.data && !isRunFinished(query.state.data.status) ? RUN_POLL_MS : false,
  });
}

/** Asks a RUNNING run to stop; the run and its agent's history are re-read. */
export function useCancelAgentTestRun() {
  const queryClient = useQueryClient();
  return useMutation<AgentTestRun, Error, string>({
    mutationFn: cancelAgentTestRun,
    onSuccess: (run, runId) => {
      void queryClient.invalidateQueries({ queryKey: agentTestKeys.run(runId) });
      if (run.agentId) void queryClient.invalidateQueries({ queryKey: agentTestKeys.runs(run.agentId) });
    },
  });
}

export function useStartAgentTestRun() {
  const queryClient = useQueryClient();
  return useMutation<AgentTestRun, Error, StartRunRequest>({
    mutationFn: startAgentTestRun,
    onSuccess: (_run, request) => {
      void queryClient.invalidateQueries({ queryKey: agentTestKeys.runs(request.agentId) });
    },
  });
}
