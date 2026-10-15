import { useEffect, useRef } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  deleteSourceFile,
  getDocumentChunks,
  getSourceDocuments,
  getSourceFiles,
  getSourceRuns,
  getSourceSchedule,
  previewCron,
  previewSource,
  purgeSource,
  runSource,
  searchKnowledgeBase,
  type CronPreview,
  type IngestedDocumentPage,
  type IngestedFile,
  type IngestionReport,
  type IngestionRun,
  type KnowledgeSearchRequest,
  type KnowledgeSearchResult,
  type SourceSchedule,
} from "@/lib/api/ingestion-sources";

const ingestionKeys = {
  runs: (kbId: string, sourceId: string, version: number) =>
    ["ingestion", "runs", kbId, sourceId, version] as const,
  files: (kbId: string, sourceId: string, version: number) =>
    ["ingestion", "files", kbId, sourceId, version] as const,
  schedule: (kbId: string, sourceId: string, version: number) =>
    ["ingestion", "schedule", kbId, sourceId, version] as const,
  documents: (kbId: string, sourceId: string, version: number) =>
    ["ingestion", "documents", kbId, sourceId, version] as const,
  cronPreview: (cron: string) => ["ingestion", "cron-preview", cron] as const,
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
      query.state.data?.some((run) => run.status === "RUNNING") ? 2000 : false,
  });
}

export function useRunIngestionSource(kbId: string | undefined, version: number) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (sourceId: string) => runSource(kbId as string, sourceId, version),
    onSuccess: (_result, sourceId) => {
      // The run is asynchronous, so refetch the history to pick up the new row.
      queryClient.invalidateQueries({ queryKey: ingestionKeys.runs(kbId ?? "", sourceId, version) });
      // A run changes what each stored file's index state is. The files panel
      // also refetches when the run finishes (see IngestionFilesPanel).
      queryClient.invalidateQueries({ queryKey: ingestionKeys.files(kbId ?? "", sourceId, version) });
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
      // Purging forgets what was indexed, so every file reads as not indexed
      // again — the list said "indexed" until the page was reloaded.
      queryClient.invalidateQueries({ queryKey: ingestionKeys.files(kbId ?? "", sourceId, version) });
    },
  });
}

/**
 * The files an upload source holds.
 *
 * Not polled. What changes it — an upload, a delete, a run starting or
 * finishing, a purge — invalidates it or refetches it explicitly.
 */
export function useSourceFiles(
  kbId: string | undefined,
  sourceId: string | undefined,
  version: number,
  enabled = true,
) {
  return useQuery<IngestedFile[]>({
    queryKey: ingestionKeys.files(kbId ?? "", sourceId ?? "", version),
    queryFn: () => getSourceFiles(kbId as string, sourceId as string, version),
    enabled: Boolean(kbId && sourceId) && enabled,
  });
}

export function useDeleteSourceFile(
  kbId: string | undefined,
  sourceId: string | undefined,
  version: number,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (fileId: string) =>
      deleteSourceFile(kbId as string, sourceId as string, version, fileId),
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: ingestionKeys.files(kbId ?? "", sourceId ?? "", version),
      });
      // Deleting a file removes its chunks, which is a change to what the source
      // holds — the history's counters are about to read differently.
      queryClient.invalidateQueries({
        queryKey: ingestionKeys.runs(kbId ?? "", sourceId ?? "", version),
      });
    },
  });
}

/**
 * The scheduler's view of a source: next and last fire, recent fire outcomes.
 *
 * Refetched every few seconds while a run is in flight, because a scheduled fire
 * moves `lastFired` and `nextFire`; otherwise once a minute, which is often
 * enough for a "next run in 3 h" line.
 */
export function useSourceSchedule(
  kbId: string | undefined,
  sourceId: string | undefined,
  version: number,
  enabled = true,
  refreshWhileRunning = false,
) {
  return useQuery<SourceSchedule>({
    queryKey: ingestionKeys.schedule(kbId ?? "", sourceId ?? "", version),
    queryFn: () => getSourceSchedule(kbId as string, sourceId as string, version),
    enabled: Boolean(kbId && sourceId) && enabled,
    refetchInterval: refreshWhileRunning ? 5000 : 60_000,
  });
}

/**
 * Checks a cron the way a save would, while it is typed. The caller debounces;
 * a blank cron is not asked about.
 */
export function useCronPreview(cron: string | undefined) {
  const trimmed = cron?.trim() ?? "";
  return useQuery<CronPreview>({
    queryKey: ingestionKeys.cronPreview(trimmed),
    queryFn: () => previewCron(trimmed),
    enabled: trimmed.length > 0,
    staleTime: 60_000,
  });
}

/** A page of a source's ingested documents. */
export function useSourceDocuments(
  kbId: string | undefined,
  sourceId: string | undefined,
  version: number,
  options: { query?: string; offset?: number; limit?: number },
  enabled = true,
) {
  return useQuery<IngestedDocumentPage>({
    queryKey: [...ingestionKeys.documents(kbId ?? "", sourceId ?? "", version), options],
    queryFn: () => getSourceDocuments(kbId as string, sourceId as string, version, options),
    enabled: Boolean(kbId && sourceId) && enabled,
    // Keeps the previous page on screen while the next one loads, rather than
    // flashing an empty list on every keystroke of the filter.
    placeholderData: (previous) => previous,
  });
}

/**
 * The chunks one document was stored as. Each fetch costs an embedding call on
 * the server, so it runs only when a document is opened, is never retried, and
 * is kept for five minutes.
 */
export function useDocumentChunks(
  kbId: string | undefined,
  sourceId: string | undefined,
  version: number,
  documentId: string | null,
) {
  return useQuery({
    queryKey: [...ingestionKeys.documents(kbId ?? "", sourceId ?? "", version), "chunks", documentId],
    queryFn: () =>
      getDocumentChunks(kbId as string, sourceId as string, version, documentId as string),
    enabled: Boolean(kbId && sourceId && documentId),
    staleTime: 5 * 60_000,
    retry: false,
  });
}

/**
 * Refetches what a run changes — the document list, and the schedule a fire
 * moved — whenever the newest run changes identity or status. Keyed on the run
 * rather than on a timer, so an idle card costs nothing.
 */
export function useRefreshAfterRun(
  kbId: string | undefined,
  sourceId: string | undefined,
  version: number,
  latestRunKey: string | undefined,
) {
  const queryClient = useQueryClient();
  const previous = useRef(latestRunKey);
  useEffect(() => {
    if (previous.current === latestRunKey) return;
    previous.current = latestRunKey;
    if (!kbId || !sourceId) return;
    void queryClient.invalidateQueries({ queryKey: ingestionKeys.documents(kbId, sourceId, version) });
    void queryClient.invalidateQueries({ queryKey: ingestionKeys.schedule(kbId, sourceId, version) });
  }, [latestRunKey, kbId, sourceId, version, queryClient]);
}

/** A test retrieval. A mutation, not a query: it runs when asked, and costs money. */
export function useKnowledgeSearch(kbId: string | undefined, version: number) {
  return useMutation<KnowledgeSearchResult, Error, KnowledgeSearchRequest>({
    mutationFn: (request) => searchKnowledgeBase(kbId as string, version, request),
  });
}
