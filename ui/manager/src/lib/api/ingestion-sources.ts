import { api, apiErrorFromResponse } from "@/lib/api-client";

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

/** How much a source of type `upload` may hold. */
export interface UploadSource {
  maxFiles?: number;
  maxFileBytes?: number;
  maxTotalBytes?: number;
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
  /** `web` crawls a site; `upload` reads files stored on the source. */
  type?: string;
  web?: WebSource;
  upload?: UploadSource;
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

/** A file stored on an upload source. */
export interface IngestedFile {
  /** Derived from the name, so re-uploading the same name replaces the file. */
  fileId: string;
  fileName: string;
  mimeType: string;
  sizeBytes: number;
  contentHash: string;
  uploadedAt: string;
}

/** Per file, because the endpoint accepts and refuses each one on its own. */
export interface UploadFilesResult {
  stored: IngestedFile[];
  rejected: { fileName: string; reason: string }[];
}

const base = (kbId: string, sourceId: string) =>
  `/ragstore/rags/${encodeURIComponent(kbId)}/sources/${encodeURIComponent(sourceId)}`;

/** Starts a run. The backend answers 202; progress is followed through the run history. */
export function runSource(kbId: string, sourceId: string, version: number) {
  // A 202 carries no body the client can read, so typing one invites a caller to
  // use a value that is always undefined.
  return api.post<void>(`${base(kbId, sourceId)}/run?version=${version}`);
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

// ── Uploaded files ───────────────────────────────────────────────────────────

/** The files an upload source holds, oldest first. */
export function getSourceFiles(kbId: string, sourceId: string, version: number) {
  return api.get<IngestedFile[]>(`${base(kbId, sourceId)}/files?version=${version}`);
}

/** Removes a file and, at once, the chunks it produced. */
export function deleteSourceFile(
  kbId: string,
  sourceId: string,
  version: number,
  fileId: string,
) {
  return api.delete<{ status: string; fileId: string; warning?: string }>(
    `${base(kbId, sourceId)}/files/${encodeURIComponent(fileId)}?version=${version}`,
  );
}

/**
 * Uploads one file.
 *
 * One request per file, not one per batch, so each file gets its own progress
 * and its own error: a batch that fails three quarters of the way through a
 * 200 MB upload would otherwise lose the files that had already arrived, and
 * the operator could not tell which those were.
 *
 * `XMLHttpRequest` rather than `fetch` for the same reason — fetch cannot
 * report how far an upload has got, and a 25 MB PDF on a slow connection with
 * no feedback reads as a hung page.
 */
export function uploadSourceFile(
  kbId: string,
  sourceId: string,
  version: number,
  file: File,
  options: { onProgress?: (fraction: number) => void; signal?: AbortSignal } = {},
): Promise<UploadFilesResult> {
  const form = new FormData();
  form.append("files", file, file.name);
  const url = `${api.getBaseUrl()}${base(kbId, sourceId)}/files?version=${version}`;

  return new Promise<UploadFilesResult>((resolve, reject) => {
    const request = new XMLHttpRequest();
    request.open("POST", url);
    for (const [header, value] of Object.entries(api.getAuthHeader())) {
      request.setRequestHeader(header, value);
    }

    request.upload.onprogress = (event) => {
      if (event.lengthComputable && options.onProgress) {
        options.onProgress(event.loaded / event.total);
      }
    };

    request.onload = () => {
      // 400 is what the server answers when nothing in the request could be
      // stored, and its body says why per file — which is the message worth
      // showing. Only a response with no such body is a bare failure.
      const parsed = parseUploadBody(request.responseText);
      if (parsed) {
        resolve(parsed);
        return;
      }
      if (request.status >= 200 && request.status < 300) {
        resolve({ stored: [], rejected: [] });
        return;
      }
      void apiErrorFromResponse(
        new Response(request.responseText || null, { status: request.status }),
        request.statusText,
        url,
      ).then(reject);
    };

    request.onerror = () =>
      reject(new Error("Network error: unable to reach server"));
    request.onabort = () => reject(new DOMException("Upload cancelled", "AbortError"));

    options.signal?.addEventListener("abort", () => request.abort(), { once: true });
    request.send(form);
  });
}

function parseUploadBody(body: string): UploadFilesResult | null {
  if (!body) return null;
  try {
    const parsed: unknown = JSON.parse(body);
    if (
      parsed &&
      typeof parsed === "object" &&
      Array.isArray((parsed as UploadFilesResult).stored) &&
      Array.isArray((parsed as UploadFilesResult).rejected)
    ) {
      return parsed as UploadFilesResult;
    }
  } catch {
    // Not the endpoint's own body — an error page, or a proxy's.
  }
  return null;
}
