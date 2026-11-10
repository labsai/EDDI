import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  createReportLink,
  deleteHealthProfile,
  getAgentHealth,
  getHealthCompare,
  getHealthPresets,
  getHealthProfile,
  getHealthSeries,
  getHealthSessions,
  getOutcomeSuggestions,
  getPortfolio,
  listReportLinks,
  putHealthProfile,
  revokeReportLink,
  type HealthProfile,
  type HealthQuery,
  type PortfolioSort,
} from "@/lib/api/health";

/** Health refreshes with the rollup, not the second: a minute is plenty. */
const STALE_MS = 60_000;

/** Query keys, nested general-to-specific so `healthKeys.agent(id)` sweeps one agent. */
export const healthKeys = {
  all: ["health"] as const,
  agent: (agentId: string) => [...healthKeys.all, "agent", agentId] as const,
  summary: (agentId: string, q: HealthQuery) => [...healthKeys.agent(agentId), "summary", q] as const,
  series: (agentId: string, measures: readonly string[], q: HealthQuery) =>
    [...healthKeys.agent(agentId), "series", measures, q] as const,
  compare: (agentId: string, from: number, to: number) => [...healthKeys.agent(agentId), "compare", from, to] as const,
  profile: (agentId: string) => [...healthKeys.agent(agentId), "profile"] as const,
  sessions: (agentId: string, q: unknown) => [...healthKeys.agent(agentId), "sessions", q] as const,
  suggestions: (agentId: string) => [...healthKeys.agent(agentId), "suggestions"] as const,
  reports: (agentId: string) => [...healthKeys.agent(agentId), "reports"] as const,
  portfolio: (period: string, sort: string, status: readonly string[]) =>
    [...healthKeys.all, "portfolio", period, sort, status] as const,
  presets: () => [...healthKeys.all, "presets"] as const,
};

export function useAgentHealth(agentId: string, query: HealthQuery) {
  return useQuery({
    queryKey: healthKeys.summary(agentId, query),
    queryFn: () => getAgentHealth(agentId, query),
    staleTime: STALE_MS,
    placeholderData: keepPreviousData,
    retry: false,
  });
}

/** The series of the measures a panel draws; `enabled` waits for the summary. */
export function useHealthSeries(agentId: string, measures: readonly string[], query: HealthQuery, enabled = true) {
  return useQuery({
    queryKey: healthKeys.series(agentId, measures, query),
    queryFn: () => getHealthSeries(agentId, measures, query),
    enabled: enabled && measures.length > 0,
    staleTime: STALE_MS,
    placeholderData: keepPreviousData,
    retry: false,
  });
}

export function useHealthCompare(agentId: string, from: number | null, to: number | null) {
  return useQuery({
    queryKey: healthKeys.compare(agentId, from ?? -1, to ?? -1),
    queryFn: () => getHealthCompare(agentId, from!, to!),
    enabled: from !== null && to !== null && from !== to,
    retry: false,
  });
}

export function useHealthPortfolio(period: "7d" | "30d" | "90d", sort: PortfolioSort, status: readonly string[]) {
  return useQuery({
    queryKey: healthKeys.portfolio(period, sort, status),
    queryFn: () => getPortfolio({ period, sort, status }),
    staleTime: STALE_MS,
    placeholderData: keepPreviousData,
    retry: false,
  });
}

export function useHealthProfile(agentId: string, enabled = true) {
  return useQuery({ queryKey: healthKeys.profile(agentId), queryFn: () => getHealthProfile(agentId), enabled, retry: false });
}

export function useHealthPresets(enabled = true) {
  return useQuery({ queryKey: healthKeys.presets(), queryFn: getHealthPresets, enabled, staleTime: 10 * STALE_MS, retry: false });
}

export function useSaveHealthProfile(agentId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (profile: HealthProfile) => putHealthProfile(agentId, profile),
    // A saved profile changes bands and status, so the whole agent view is stale.
    onSuccess: () => qc.invalidateQueries({ queryKey: healthKeys.agent(agentId) }),
  });
}

export function useResetHealthProfile(agentId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => deleteHealthProfile(agentId),
    onSuccess: () => qc.invalidateQueries({ queryKey: healthKeys.agent(agentId) }),
  });
}

export function useHealthSessions(agentId: string, query: Parameters<typeof getHealthSessions>[1], enabled = true) {
  return useQuery({
    queryKey: healthKeys.sessions(agentId, query),
    queryFn: () => getHealthSessions(agentId, query),
    enabled,
    placeholderData: keepPreviousData,
    retry: false,
  });
}

export function useOutcomeSuggestions(agentId: string, enabled = true) {
  return useQuery({ queryKey: healthKeys.suggestions(agentId), queryFn: () => getOutcomeSuggestions(agentId), enabled, retry: false });
}

export function useReportLinks(agentId: string, enabled = true) {
  return useQuery({ queryKey: healthKeys.reports(agentId), queryFn: () => listReportLinks(agentId), enabled, retry: false });
}

export function useCreateReportLink(agentId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { expiresInDays: number; sections: string[] }) => createReportLink(agentId, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: healthKeys.reports(agentId) }),
  });
}

export function useRevokeReportLink(agentId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => revokeReportLink(agentId, id),
    onSuccess: () => qc.invalidateQueries({ queryKey: healthKeys.reports(agentId) }),
  });
}
