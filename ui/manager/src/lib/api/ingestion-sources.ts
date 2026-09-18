import { api } from "@/lib/api-client";

/**
 * Ingestion sources — where a knowledge base pulls its own documents from.
 *
 * Sources live ON the knowledge base (`RagConfiguration.sources[]`), so creating
 * and editing one is an ordinary save of the RAG config. Only the four verbs
 * that act on a source at runtime have endpoints of their own.
 */

/** Crawl configuration for a `web` source. */
export interface WebSource {
  startUrl?: string;
  sameSiteOnly?: boolean;
  includeSubdomains?: boolean;
  pathPrefix?: string;
  maxDepth?: number;
  maxPages?: number;
  excludePatterns?: string[];
  requestDelayMs?: number;
  timeoutSeconds?: number;
  userAgent?: string;
  respectRobots?: boolean;
}

/** Limits that apply to the ingestion rather than to the crawl. */
export interface IngestionSettings {
  maxContentLength?: number;
  tombstoneAfterMissedRuns?: number;
  maxSegmentsPerRun?: number;
  costPerThousandSegments?: number;
  maxBytesPerPage?: number;
  timeBudgetMinutes?: number;
}

export interface IngestionSource {
  /** Assigned by the backend on save; the runtime endpoints address it. */
  id?: string;
  name?: string;
  enabled?: boolean;
  type?: string;
  web?: WebSource;
  settings?: IngestionSettings;
  /** Standard cron; absent means the source only runs when triggered by hand. */
  cron?: string;
}

export type IngestionRunStatus = "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";

/** One past execution of a source. */
export interface IngestionRun {
  runId: string;
  sourceId: string;
  status: IngestionRunStatus;
  startedAt?: string;
  finishedAt?: string;
  documentsSeen: number;
  documentsIngested: number;
  documentsUnchanged: number;
  documentsFailed: number;
  documentsTombstoned: number;
  segmentsStored: number;
  costUsd: number;
  error?: string | null;
}

/** What a preview says a run would do. */
export interface IngestionReport {
  runId?: string;
  sourceId?: string;
  outcome: "COMPLETED" | "PREVIEW" | "FAILED" | "SKIPPED" | "ALREADY_RUNNING";
  documentsSeen: number;
  documentsIngested: number;
  documentsUnchanged: number;
  documentsSkipped: number;
  documentsFailed: number;
  documentsTombstoned: number;
  segmentsStored: number;
  costUsd: number;
  replaceUnsupported: boolean;
  tombstoningSkipped: boolean;
  stopReason?: string | null;
  message?: string | null;
}

const base = (kbId: string, sourceId: string) =>
  `/ragstore/rags/${encodeURIComponent(kbId)}/sources/${encodeURIComponent(sourceId)}`;

/** Starts a run. The backend answers 202; progress is followed through the run history. */
export function runSource(kbId: string, sourceId: string, version: number) {
  return api.post<{ status: string; sourceId: string }>(
    `${base(kbId, sourceId)}/run?version=${version}`,
  );
}

/** Crawls and reports what would change, embedding and recording nothing. */
export function previewSource(kbId: string, sourceId: string, version: number) {
  return api.post<IngestionReport>(`${base(kbId, sourceId)}/preview?version=${version}`);
}

/** Past runs, newest first. */
export function getSourceRuns(kbId: string, sourceId: string, version: number, limit = 20) {
  return api.get<IngestionRun[]>(`${base(kbId, sourceId)}/runs?version=${version}&limit=${limit}`);
}

/** Forgets what the source has ingested, so the next run re-ingests everything. */
export function purgeSource(kbId: string, sourceId: string, version: number) {
  return api.delete<{ status: string; sourceId: string }>(
    `${base(kbId, sourceId)}/documents?version=${version}`,
  );
}
