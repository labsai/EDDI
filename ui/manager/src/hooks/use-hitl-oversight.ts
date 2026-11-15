import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { oversightKeys } from "@/lib/query-keys";
import { isApiError } from "@/lib/api-client";
import {
  applyRecommendation,
  countPendingSamples,
  dismissRecommendation,
  liftSuspension,
  listAutonomy,
  listSamples,
  readSample,
  reviewSample,
  summarizeApprovals,
  type OversightGroupBy,
  type OversightQuery,
  type SampleStatus,
  type SampleVerdict,
} from "@/lib/api/hitl-oversight";

/** The error code of an EDDI `{error, message}` body, if any. */
export function oversightErrorCode(error: unknown): string | undefined {
  return isApiError(error) ? (error as { code?: string }).code : undefined;
}

/** A non-administrator listed every agent (400 `AGENT_ID_REQUIRED`). */
export function isAgentRequired(error: unknown): boolean {
  return isApiError(error) && error.status === 400 && oversightErrorCode(error) === "AGENT_ID_REQUIRED";
}

export function useApprovalSummary(query: OversightQuery, groupBy: OversightGroupBy, enabled = true) {
  return useQuery({
    queryKey: oversightKeys.summary(query, groupBy),
    queryFn: () => summarizeApprovals(query, groupBy),
    enabled,
    retry: false,
  });
}

export function useAuditSamples(agentId: string | undefined, status: SampleStatus | "ALL", index: number, limit: number, enabled = true) {
  return useQuery({
    queryKey: oversightKeys.samples(agentId, status, index, limit),
    queryFn: () => listSamples(agentId, status, index, limit),
    enabled,
    placeholderData: keepPreviousData,
    retry: false,
  });
}

export function useAuditSample(id: string | null) {
  return useQuery({
    queryKey: oversightKeys.sample(id ?? ""),
    queryFn: () => readSample(id ?? ""),
    enabled: !!id,
    retry: false,
  });
}

export function usePendingSampleCount(agentId?: string, enabled = true) {
  return useQuery({
    queryKey: oversightKeys.pending(agentId),
    queryFn: () => countPendingSamples(agentId),
    enabled,
    retry: false,
  });
}

export function useAutonomy(agentId?: string, enabled = true) {
  return useQuery({
    queryKey: oversightKeys.autonomy(agentId),
    queryFn: () => listAutonomy(agentId),
    enabled,
    retry: false,
  });
}

function useOversightMutation<V, R>(fn: (vars: V) => Promise<R>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSettled: () => qc.invalidateQueries({ queryKey: oversightKeys.all }),
  });
}

export function useReviewSample() {
  return useOversightMutation((vars: { id: string; verdict: SampleVerdict }) => reviewSample(vars.id, vars.verdict));
}

export function useApplyRecommendation() {
  return useOversightMutation((vars: { agentId: string; category: string; recommendationId: string; expectedAgentVersion: number }) =>
    applyRecommendation(vars.agentId, vars.category, vars.recommendationId, vars.expectedAgentVersion),
  );
}

export function useDismissRecommendation() {
  return useOversightMutation((vars: { agentId: string; category: string; recommendationId: string; note: string }) =>
    dismissRecommendation(vars.agentId, vars.category, vars.recommendationId, vars.note),
  );
}

export function useLiftSuspension() {
  return useOversightMutation((vars: { agentId: string; category: string; note: string }) =>
    liftSuspension(vars.agentId, vars.category, vars.note),
  );
}
