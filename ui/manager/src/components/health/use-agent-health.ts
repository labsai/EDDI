import { keepPreviousData, useInfiniteQuery, useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
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
  type HealthMarker,
  type HealthPeriod,
  type HealthProfile,
  type HealthQuery,
  type SeriesPoint,
} from "./api";

/** Health refreshes with the rollup, not the second: a minute is plenty. */
const STALE_MS = 60_000;

/** The backend accepts at most six measures per series call. */
export const MAX_SERIES_MEASURES = 6;

/** Filters that decide a sessions page; the cursor is separate. */
export type SessionsFilter = HealthQuery & { outcome?: readonly string[]; evidence?: readonly string[] };

/**
 * A query with its origins sorted, so the same selection made in another order
 * is one cache entry and one request.
 */
function normalize(q: HealthQuery): HealthQuery {
  return { ...q, origins: q.origins ? [...q.origins].sort() : q.origins };
}

/**
 * Query keys, nested general-to-specific so `healthKeys.agent(id)` sweeps one
 * agent. They live here and not in `lib/query-keys.ts` on purpose: that file is
 * shared and is moving into `@eddi/app-kit`, and nothing outside the health
 * feature reads or invalidates these entries, so the keys stay with their only
 * users and the health folder stays self-contained.
 */
export const healthKeys = {
  all: ["health"] as const,
  agent: (agentId: string) => [...healthKeys.all, "agent", agentId] as const,
  summary: (agentId: string, q: HealthQuery) => [...healthKeys.agent(agentId), "summary", normalize(q)] as const,
  series: (agentId: string, measures: readonly string[], q: HealthQuery) =>
    [...healthKeys.agent(agentId), "series", measures, normalize(q)] as const,
  compare: (agentId: string, from: number, to: number, days: number | undefined) =>
    [...healthKeys.agent(agentId), "compare", from, to, days] as const,
  profile: (agentId: string) => [...healthKeys.agent(agentId), "profile"] as const,
  sessions: (agentId: string, f: SessionsFilter, cursor: string | undefined) =>
    [...healthKeys.agent(agentId), "sessions", normalize(f), f.outcome, f.evidence, cursor] as const,
  suggestions: (agentId: string) => [...healthKeys.agent(agentId), "suggestions"] as const,
  reports: (agentId: string) => [...healthKeys.agent(agentId), "reports"] as const,
  portfolio: (period: string, sort: string, status: readonly string[]) =>
    [...healthKeys.all, "portfolio", period, sort, status] as const,
  presets: () => [...healthKeys.all, "presets"] as const,
};

/** The agent's health; nothing is requested while `enabled` is false (a collapsed panel). */
export function useAgentHealth(agentId: string, query: HealthQuery, enabled = true) {
  return useQuery({
    queryKey: healthKeys.summary(agentId, query),
    queryFn: () => getAgentHealth(agentId, normalize(query)),
    enabled,
    staleTime: STALE_MS,
    placeholderData: keepPreviousData,
    retry: false,
  });
}

/** Splits measure ids into series calls of at most {@link MAX_SERIES_MEASURES}. */
export function chunkMeasures(ids: readonly string[]): string[][] {
  const out: string[][] = [];
  for (let i = 0; i < ids.length; i += MAX_SERIES_MEASURES) out.push(ids.slice(i, i + MAX_SERIES_MEASURES));
  return out;
}

export interface HealthSeriesResult {
  byMeasure: Map<string, SeriesPoint[]>;
  markers: HealthMarker[];
  isLoading: boolean;
}

/**
 * The trend of every given measure. More than six are split over several calls,
 * so none silently loses its sparkline.
 */
export function useHealthSeries(agentId: string, measures: readonly string[], query: HealthQuery, enabled = true): HealthSeriesResult {
  const results = useQueries({
    queries: chunkMeasures(measures).map((chunk) => ({
      queryKey: healthKeys.series(agentId, chunk, query),
      queryFn: () => getHealthSeries(agentId, chunk, normalize(query)),
      enabled,
      staleTime: STALE_MS,
      placeholderData: keepPreviousData,
      retry: false,
    })),
  });
  const byMeasure = new Map<string, SeriesPoint[]>();
  const markers = new Map<string, HealthMarker>();
  for (const r of results) {
    for (const s of r.data?.series ?? []) if (!s.group) byMeasure.set(s.measure, s.points);
    for (const m of r.data?.markers ?? []) markers.set(m.id, m);
  }
  return { byMeasure, markers: [...markers.values()], isLoading: results.some((r) => r.isLoading) };
}

/**
 * Compares two versions of an agent. Disabled until both versions are chosen and `from < to`
 * (the contract compares an older with a newer version); `null` is mapped to -1 only to keep the key stable.
 */
export function useHealthCompare(agentId: string, from: number | null, to: number | null, days?: number) {
  return useQuery({
    queryKey: healthKeys.compare(agentId, from ?? -1, to ?? -1, days),
    queryFn: () => getHealthCompare(agentId, from!, to!, days),
    enabled: from !== null && to !== null && from < to,
    retry: false,
  });
}

/** The portfolio, a page at a time: `fetchNextPage` follows `nextCursor`. */
export function useHealthPortfolio(period: HealthPeriod, sort: "attention" | "name" | "traffic", status: readonly string[]) {
  return useInfiniteQuery({
    queryKey: healthKeys.portfolio(period, sort, [...status].sort()),
    queryFn: ({ pageParam }) => getPortfolio({ period, sort, status, cursor: pageParam }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: STALE_MS,
    placeholderData: keepPreviousData,
    retry: false,
  });
}

/** The agent's health profile; disabled while `enabled` is false (the dialog is closed). */
export function useHealthProfile(agentId: string, enabled = true) {
  return useQuery({ queryKey: healthKeys.profile(agentId), queryFn: () => getHealthProfile(agentId), enabled, retry: false });
}

/** The preset list. Presets are static, so they are cached ten times longer than health data. */
export function useHealthPresets(enabled = true) {
  return useQuery({ queryKey: healthKeys.presets(), queryFn: getHealthPresets, enabled, staleTime: 10 * STALE_MS, retry: false });
}

/** Saves a profile and invalidates every health query of the agent on success, since bands and status derive from it. */
export function useSaveHealthProfile(agentId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (profile: HealthProfile) => putHealthProfile(agentId, profile),
    // A saved profile changes bands and status, so the whole agent view is stale.
    onSuccess: () => qc.invalidateQueries({ queryKey: healthKeys.agent(agentId) }),
  });
}

/** Deletes the stored profile (back to the preset defaults) and invalidates the agent's health queries. */
export function useResetHealthProfile(agentId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => deleteHealthProfile(agentId),
    onSuccess: () => qc.invalidateQueries({ queryKey: healthKeys.agent(agentId) }),
  });
}

/**
 * One page of sessions. No `keepPreviousData`: a page of another filter must
 * never show under the new title, so a new key starts empty.
 */
export function useHealthSessions(agentId: string, filter: SessionsFilter, cursor: string | undefined, enabled = true) {
  return useQuery({
    queryKey: healthKeys.sessions(agentId, filter, cursor),
    queryFn: () => getHealthSessions(agentId, { ...normalize(filter), outcome: filter.outcome, evidence: filter.evidence, limit: 25, cursor }),
    enabled,
    retry: false,
  });
}

/** Suggested outcome mappings for the agent; fetched only while `enabled` (the dialog is open). */
export function useOutcomeSuggestions(agentId: string, enabled = true) {
  return useQuery({ queryKey: healthKeys.suggestions(agentId), queryFn: () => getOutcomeSuggestions(agentId), enabled, retry: false });
}

/** The agent's report links; fetched only while `enabled`. */
export function useReportLinks(agentId: string, enabled = true) {
  return useQuery({ queryKey: healthKeys.reports(agentId), queryFn: () => listReportLinks(agentId), enabled, retry: false });
}

/** Creates a report link and refreshes the list. The new link's `url` is only in the mutation result. */
export function useCreateReportLink(agentId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: { expiresInDays: number; sections: string[] }) => createReportLink(agentId, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: healthKeys.reports(agentId) }),
  });
}

/** Revokes a report link by id and refreshes the list. */
export function useRevokeReportLink(agentId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => revokeReportLink(agentId, id),
    onSuccess: () => qc.invalidateQueries({ queryKey: healthKeys.reports(agentId) }),
  });
}
