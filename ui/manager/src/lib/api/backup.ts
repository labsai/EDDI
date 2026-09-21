import { api } from "../api-client";

// ==================== Export Types ====================

export interface ExportableResource {
  resourceId: string;
  resourceVersion: number | null;
  resourceType: string;
  name: string | null;
  parentWorkflowId: string | null;
  workflowIndex: number;
  required: boolean;
}

export interface ExportPreview {
  agentId: string;
  agentName: string;
  agentVersion: number;
  resources: ExportableResource[];
}

// ==================== Import/Sync Types ====================

export type DiffAction = "CREATE" | "UPDATE" | "SKIP" | "CONFLICT";
export type MatchStrategy = "position" | "type" | "name" | "originId" | null;

export interface ResourceDiff {
  sourceId: string;
  resourceType: string;
  name: string | null;
  action: DiffAction;
  targetId: string | null;
  targetVersion: number | null;
  matchStrategy: MatchStrategy;
  sourceContent: string | null;
  targetContent: string | null;
  workflowIndex: number;
}

export interface ImportPreview {
  sourceAgentId: string | null;
  sourceAgentName: string | null;
  targetAgentId: string | null;
  targetAgentName: string | null;
  resources: ResourceDiff[];
}

// ==================== Sync Types ====================

export interface SyncMapping {
  sourceAgentId: string;
  sourceAgentVersion: number | null;
  targetAgentId: string | null;
}

export interface SyncRequest {
  sourceAgentId: string;
  sourceAgentVersion: number | null;
  targetAgentId: string | null;
  selectedResources: string[] | null;
  workflowOrder: string[] | null;
}

export interface DocumentDescriptor {
  resource: string;
  name: string | null;
  description: string | null;
  lastModifiedOn: string;
}

// ==================== Upgrade / sync outcomes ====================

/** One resource an upgrade could not process. */
export interface ResourceFailure {
  sourceId: string;
  resourceType: string;
  name: string | null;
  reason: string;
}

/**
 * What an upgrade or sync actually did.
 *
 * `hasFailures` and `wroteAnything` exist on EDDI's record as derived methods
 * but are NOT on the wire — Jackson serialises a record's components, and
 * neither is one — so they are computed here from the fields that are.
 */
export interface UpgradeResult {
  agentUri: string | null;
  agentUpdated: boolean;
  updated: number;
  created: number;
  skipped: number;
  failures: ResourceFailure[];
}

/**
 * How an upgrade or sync ended.
 *
 * EDDI answers three different 2xx codes here and says so in its own javadoc:
 * "All three are 2xx, so a client must branch on the status code rather than on
 * response.ok." Before this existed the Manager read only the `Location`
 * header, so a half-applied sync, a clean one and a no-op were indistinguishable.
 */
export type SyncOutcome = "wrote" | "identical" | "partial";

export interface SyncExecution {
  outcome: SyncOutcome;
  result: UpgradeResult | null;
  /** The `Location` header, kept because callers used to read only this. */
  location: string;
}

/** One agent's outcome inside a batch sync. */
export interface BatchSyncResult {
  sourceAgentId: string;
  targetAgentId: string | null;
  /** What the upgrade did, or null when it could not run at all. */
  result: UpgradeResult | null;
  /** Why it could not run, or null on success. */
  error: string | null;
}

export interface BatchSyncExecution {
  /** True when at least one mapping failed — HTTP 207, or 500 when all did. */
  partial: boolean;
  results: BatchSyncResult[];
}

/** Whether an upgrade left any resource unprocessed. */
export function hasFailures(result: UpgradeResult | null | undefined): boolean {
  return (result?.failures?.length ?? 0) > 0;
}

/**
 * Read the outcome from the status, which is the only place it is stated.
 *
 * 201 wrote something, 200 means source and target were already identical (no
 * agent version was burned), 207 means some resources failed. The body is
 * consulted only as a fallback for a backend that predates the split.
 */
function outcomeOf(status: number, result: UpgradeResult | null): SyncOutcome {
  if (status === 207 || hasFailures(result)) return "partial";
  if (status === 200) return "identical";
  return "wrote";
}

/** Parse a JSON body, tolerating an empty or non-JSON one. */
async function readJson<T>(response: Response): Promise<T | null> {
  const text = await response.text();
  if (!text) return null;
  try {
    return JSON.parse(text) as T;
  } catch {
    return null;
  }
}

/**
 * A JSON body that must be a list, or an empty one.
 *
 * These calls bypass `ApiClient` because they send `application/zip` and custom
 * sync headers, so they also miss its guard against a non-JSON 2xx. A reverse
 * proxy answering 200 with an HTML page is the realistic case, and without this
 * the batch summary died on `results.some is not a function` — an unhandled
 * TypeError in place of an error the caller could report.
 */
async function readJsonArray<T>(response: Response): Promise<T[]> {
  const parsed = await readJson<unknown>(response);
  return Array.isArray(parsed) ? (parsed as T[]) : [];
}

/**
 * Parse an EDDI resource URI into its id and version.
 *
 * Accepted formats:
 *   - `eddi://ai.labs.agent/agentstore/agents/abc123?version=3`
 *   - `/agentstore/agents/abc123?version=3` (Location header path)
 *   - `simple-id` (bare string fallback)
 */
export function parseResourceUri(resource: string): { id: string; version: number | null } {
  try {
    const normalised = resource.startsWith("eddi://")
      ? resource.replace("eddi://", "http://")
      : resource;
    // Use a dummy base so relative paths parse correctly
    const url = new URL(normalised, "http://dummy");
    const parts = url.pathname.split("/").filter(Boolean);
    const id = parts[parts.length - 1] || resource;
    const versionStr = url.searchParams.get("version");
    const parsedVersion = versionStr ? parseInt(versionStr, 10) : NaN;
    const version = Number.isFinite(parsedVersion) ? parsedVersion : null;
    return { id, version };
  } catch {
    // Fallback for completely unparseable strings
    const parts = resource.split("/");
    return { id: parts[parts.length - 1] || resource, version: null };
  }
}

/**
 * Build auth headers for cross-instance sync.
 * Merges the local auth token (if set via Keycloak) with the optional
 * X-Source-Authorization header used for authenticating with the remote instance.
 */
function mergedHeaders(sourceAuth?: string): Record<string, string> {
  return {
    ...api.getAuthHeader(),
    ...(sourceAuth ? { "X-Source-Authorization": sourceAuth } : {}),
  };
}

// ==================== Existing Export Functions ==

/**
 * Step 1: Trigger export — backend prepares a zip and returns a Location header.
 * POST /backup/export/{agentId}?agentVersion={version}
 */
export async function exportAgent(
  agentId: string,
  version = 1
): Promise<string> {
  const res = await fetch(
    `${api.getBaseUrl()}/backup/export/${agentId}?agentVersion=${version}`,
    { method: "POST", headers: api.getAuthHeader() }
  );
  if (!res.ok) {
    throw new Error(`Export failed: ${res.statusText}`);
  }
  // Location header contains the download path, e.g. /backup/export/myagent-abc-1.zip
  const location = res.headers.get("Location");
  if (!location) {
    throw new Error("Export succeeded but no Location header returned");
  }
  return location;
}

/**
 * Step 2: Download the zip file at the given path.
 * GET /backup/export/{filename}
 */
export async function downloadAgentZip(downloadPath: string): Promise<void> {
  const url = downloadPath.startsWith("http")
    ? downloadPath
    : `${api.getBaseUrl()}${downloadPath}`;

  const res = await fetch(url, { headers: api.getAuthHeader() });
  if (!res.ok) {
    throw new Error(`Download failed: ${res.statusText}`);
  }

  const blob = await res.blob();
  const filename = downloadPath.split("/").pop() || "agent-export.zip";

  // Trigger browser download
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(a.href);
}

/**
 * Combined: export + download in one call.
 */
export async function exportAndDownloadAgent(
  agentId: string,
  version = 1
): Promise<void> {
  const location = await exportAgent(agentId, version);
  await downloadAgentZip(location);
}

// ==================== Existing Import Functions ====================

/**
 * Import a agent from a zip file (create new — default strategy).
 * POST /backup/import with Content-Type: application/zip
 * Returns the Location of the newly created agent.
 */
export async function importAgent(file: File): Promise<string> {
  const res = await fetch(`${api.getBaseUrl()}/backup/import`, {
    method: "POST",
    headers: { "Content-Type": "application/zip", ...api.getAuthHeader() },
    body: file,
  });

  if (!res.ok) {
    throw new Error(`Import failed: ${res.statusText}`);
  }

  const location = res.headers.get("Location");
  return location || "";
}

/**
 * Preview what a merge import would do — does NOT modify data.
 * POST /backup/import/preview
 */
export async function previewImport(file: File): Promise<ImportPreview> {
  const res = await fetch(`${api.getBaseUrl()}/backup/import/preview`, {
    method: "POST",
    headers: { "Content-Type": "application/zip", ...api.getAuthHeader() },
    body: file,
  });

  if (!res.ok) {
    throw new Error(`Preview failed: ${res.statusText}`);
  }

  return res.json();
}

/**
 * Import a agent with merge strategy.
 * POST /backup/import?strategy=merge&selectedResources=...
 */
export interface MergeImportResult {
  location: string;
  /**
   * How many of the archive's schedules `selectedResources` left out.
   *
   * Absent — null here — when it left out none, which is every import that does
   * not filter. `selectedResources` is one flat list over every preview row, so
   * naming extension ids only silently drops every schedule in the archive;
   * this header is the only thing that says so.
   */
  schedulesSkipped: number | null;
}

export async function importAgentMerge(
  file: File,
  selectedSourceIds?: string[]
): Promise<MergeImportResult> {
  const params = new URLSearchParams({ strategy: "merge" });
  if (selectedSourceIds && selectedSourceIds.length > 0) {
    params.set("selectedResources", selectedSourceIds.join(","));
  }

  const res = await fetch(`${api.getBaseUrl()}/backup/import?${params}`, {
    method: "POST",
    headers: { "Content-Type": "application/zip", ...api.getAuthHeader() },
    body: file,
  });

  if (!res.ok) {
    throw new Error(`Merge import failed: ${res.statusText}`);
  }

  const raw = res.headers.get("X-Schedules-Skipped");
  const parsed = raw === null ? Number.NaN : Number(raw);
  return {
    location: res.headers.get("Location") || "",
    schedulesSkipped:
      Number.isInteger(parsed) && parsed > 0 ? parsed : null,
  };
}

// ==================== Selective Export ====================

/**
 * Preview exportable resources for an agent.
 * POST /backup/export/{agentId}/preview?agentVersion={version}
 */
export async function previewExport(
  agentId: string,
  version = 1
): Promise<ExportPreview> {
  return api.post<ExportPreview>(
    `/backup/export/${agentId}/preview?agentVersion=${version}`
  );
}

/**
 * Which rows an export should carry, when the caller is filtering.
 *
 * Three separate lists rather than one, because EDDI reads them with three
 * different rules and conflating them loses data in two directions:
 *
 * - `resources` — extension resource ids. A BLANK value is a full export, so
 *   the parameter is omitted rather than sent empty when nothing is selected.
 * - `snippets` and `schedules` — newer than `selectedResources`, and inverted:
 *   OMIT the parameter and every referenced snippet / every schedule of the
 *   agent is exported; pass it, *even empty*, and only the listed ids are. So
 *   `[]` and `undefined` mean opposite things here, which is why they are
 *   `string[] | undefined` and not defaulted.
 */
export interface ExportSelection {
  resources?: string[];
  snippets?: string[];
  schedules?: string[];
}

/**
 * Export agent with selected resources only.
 * POST /backup/export/{agentId}?agentVersion={version}&selectedResources=id1,id2
 */
export async function exportAgentSelective(
  agentId: string,
  version: number,
  selectedResourceIds: string[],
  selection: Omit<ExportSelection, "resources"> = {}
): Promise<void> {
  const params = new URLSearchParams({ agentVersion: String(version) });
  if (selectedResourceIds.length > 0) {
    params.set("selectedResources", selectedResourceIds.join(","));
  }
  // `!== undefined`, not truthiness: an empty list is a meaningful value here
  // ("export none of these"), and dropping it would export all of them.
  if (selection.snippets !== undefined) {
    params.set("selectedSnippets", selection.snippets.join(","));
  }
  if (selection.schedules !== undefined) {
    params.set("selectedSchedules", selection.schedules.join(","));
  }
  const res = await fetch(
    `${api.getBaseUrl()}/backup/export/${agentId}?${params}`,
    { method: "POST", headers: api.getAuthHeader() }
  );
  if (!res.ok) throw new Error(`Export failed: ${res.statusText}`);
  const location = res.headers.get("Location");
  if (location) await downloadAgentZip(location);
}

// ==================== Upgrade Import ====================

/**
 * Preview upgrade import with structural matching against a target agent.
 * POST /backup/import/preview?targetAgentId={targetAgentId}
 */
export async function previewUpgrade(
  file: File,
  targetAgentId: string
): Promise<ImportPreview> {
  const params = new URLSearchParams({ targetAgentId });
  const res = await fetch(`${api.getBaseUrl()}/backup/import/preview?${params}`, {
    method: "POST",
    headers: { "Content-Type": "application/zip", ...api.getAuthHeader() },
    body: file,
  });
  if (!res.ok) throw new Error(`Upgrade preview failed: ${res.statusText}`);
  return res.json();
}

/**
 * Execute upgrade import.
 * POST /backup/import?strategy=upgrade&targetAgentId=...
 */
export async function importAgentUpgrade(
  file: File,
  targetAgentId: string,
  selectedSourceIds?: string[],
  workflowOrder?: string[]
): Promise<SyncExecution> {
  const params = new URLSearchParams({ strategy: "upgrade", targetAgentId });
  if (selectedSourceIds?.length) {
    params.set("selectedResources", selectedSourceIds.join(","));
  }
  if (workflowOrder?.length) {
    params.set("workflowOrder", workflowOrder.join(","));
  }

  const res = await fetch(`${api.getBaseUrl()}/backup/import?${params}`, {
    method: "POST",
    headers: { "Content-Type": "application/zip", ...api.getAuthHeader() },
    body: file,
  });
  if (!res.ok) throw new Error(`Upgrade import failed: ${res.statusText}`);
  const result = await readJson<UpgradeResult>(res);
  return {
    outcome: outcomeOf(res.status, result),
    result,
    location: res.headers.get("Location") || "",
  };
}

// ==================== Live Sync ====================

/**
 * List agents from a remote EDDI instance.
 * GET /backup/import/sync/agents?sourceUrl=...
 */
export async function listRemoteAgents(
  sourceUrl: string,
  sourceAuth: string
): Promise<DocumentDescriptor[]> {
  const params = new URLSearchParams({ sourceUrl });
  const res = await fetch(`${api.getBaseUrl()}/backup/import/sync/agents?${params}`, {
    headers: mergedHeaders(sourceAuth),
  });
  if (!res.ok) throw new Error(`Failed to list remote agents: ${res.statusText}`);
  return res.json();
}

/**
 * Preview sync for a single agent.
 * POST /backup/import/sync/preview?sourceUrl=...&sourceAgentId=...
 */
export async function previewSync(
  sourceUrl: string,
  sourceAgentId: string,
  sourceVersion: number | null,
  targetAgentId: string | null,
  sourceAuth: string
): Promise<ImportPreview> {
  const params = new URLSearchParams({ sourceUrl, sourceAgentId });
  if (sourceVersion != null) params.set("sourceAgentVersion", String(sourceVersion));
  if (targetAgentId) params.set("targetAgentId", targetAgentId);

  const res = await fetch(`${api.getBaseUrl()}/backup/import/sync/preview?${params}`, {
    method: "POST",
    headers: mergedHeaders(sourceAuth),
  });
  if (!res.ok) throw new Error(`Sync preview failed: ${res.statusText}`);
  return res.json();
}

/**
 * Batch preview for multiple agent mappings.
 * POST /backup/import/sync/preview/batch?sourceUrl=...
 */
export async function previewSyncBatch(
  sourceUrl: string,
  mappings: SyncMapping[],
  sourceAuth: string
): Promise<ImportPreview[]> {
  const params = new URLSearchParams({ sourceUrl });
  const res = await fetch(`${api.getBaseUrl()}/backup/import/sync/preview/batch?${params}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...mergedHeaders(sourceAuth),
    },
    body: JSON.stringify(mappings),
  });
  if (!res.ok) throw new Error(`Batch preview failed: ${res.statusText}`);
  return res.json();
}

/**
 * Execute sync for a single agent.
 * POST /backup/import/sync?sourceUrl=...&sourceAgentId=...
 */
export async function executeSync(
  sourceUrl: string,
  sourceAgentId: string,
  sourceVersion: number | null,
  targetAgentId: string | null,
  selectedResources: string[] | null,
  workflowOrder: string[] | null,
  sourceAuth: string
): Promise<SyncExecution> {
  const params = new URLSearchParams({ sourceUrl, sourceAgentId });
  if (sourceVersion != null) params.set("sourceAgentVersion", String(sourceVersion));
  if (targetAgentId) params.set("targetAgentId", targetAgentId);
  if (selectedResources?.length) params.set("selectedResources", selectedResources.join(","));
  if (workflowOrder?.length) params.set("workflowOrder", workflowOrder.join(","));

  const res = await fetch(`${api.getBaseUrl()}/backup/import/sync?${params}`, {
    method: "POST",
    headers: mergedHeaders(sourceAuth),
  });
  if (!res.ok) throw new Error(`Sync execute failed: ${res.statusText}`);
  const result = await readJson<UpgradeResult>(res);
  return {
    outcome: outcomeOf(res.status, result),
    result,
    location: res.headers.get("Location") || "",
  };
}

/**
 * Batch sync execution.
 * POST /backup/import/sync/batch?sourceUrl=...
 */
export async function executeSyncBatch(
  sourceUrl: string,
  requests: SyncRequest[],
  sourceAuth: string
): Promise<BatchSyncExecution> {
  const params = new URLSearchParams({ sourceUrl });
  const res = await fetch(`${api.getBaseUrl()}/backup/import/sync/batch?${params}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...mergedHeaders(sourceAuth),
    },
    body: JSON.stringify(requests),
  });

  // 500 when EVERY mapping failed, and the body is still the per-agent list.
  // Read it before throwing: "all five agents failed" is the one case where the
  // reasons matter most, and `res.statusText` alone carries none of them.
  if (res.status === 500) {
    const results = await readJsonArray<BatchSyncResult>(res);
    if (results.length > 0) return { partial: true, results };
  }
  if (!res.ok) throw new Error(`Batch sync failed: ${res.statusText}`);

  const results = await readJsonArray<BatchSyncResult>(res);
  return {
    partial: res.status === 207 || results.some((r) => r.error || hasFailures(r.result)),
    results,
  };
}
