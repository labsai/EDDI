import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  getSourceRuns,
  previewSource,
  purgeSource,
  runSource,
  type IngestionReport,
  type IngestionRun,
} from "@/lib/api/ingestion-sources";

const ingestionKeys = {
  runs: (kbId: string, sourceId: string, version: number) =>
    ["ingestion", "runs", kbId, sourceId, version] as const,
};

/**
 * Run history for one source.
 *
 * Polled while a run is in flight — a crawl takes minutes and the start endpoint
 * answers 202 without waiting for it, so the history is how progress is seen.
 * Polling stops as soon as nothing is RUNNING, rather than ticking forever on an
 * idle screen.
 */
export function useIngestionRuns(
  kbId: string | undefined,
  sourceId: string | undefined,
  version: number,
  enabled = true,
) {
  return useQuery<IngestionRun[]>({
    queryKey: ingestionKeys.runs(kbId ?? "", sourceId ?? "", version),
    queryFn: () => getSourceRuns(kbId as string, sourceId as string, version),
    enabled: Boolean(kbId && sourceId) && enabled,
    refetchInterval: (query) =>
      query.state.data?.some((run) => run.status === "RUNNING") ? 3000 : false,
  });
}

export function useRunIngestionSource(kbId: string | undefined, version: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (sourceId: string) => runSource(kbId as string, sourceId, version),
    onSuccess: (_result, sourceId) => {
      // The run is asynchronous, so refetch the history to pick up the new row.
      queryClient.invalidateQueries({ queryKey: ingestionKeys.runs(kbId ?? "", sourceId, version) });
    },
  });
}

export function usePreviewIngestionSource(kbId: string | undefined, version: number) {
  return useMutation<IngestionReport, Error, string>({
    mutationFn: (sourceId: string) => previewSource(kbId as string, sourceId, version),
  });
}

export function usePurgeIngestionSource(kbId: string | undefined, version: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (sourceId: string) => purgeSource(kbId as string, sourceId, version),
    onSuccess: (_result, sourceId) => {
      queryClient.invalidateQueries({ queryKey: ingestionKeys.runs(kbId ?? "", sourceId, version) });
    },
  });
}
