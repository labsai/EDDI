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

// ==================== Shared Utilities ====================

/**
 * Parse an EDDI resource URI into its id and version.
 * e.g. "eddi://ai.labs.agent/agentstore/agents/abc123?version=3" → { id: "abc123", version: 3 }
 */
export function parseResourceUri(resource: string): { id: string; version: number | null } {
  const match = resource.match(/\/([^/?]+)\?version=(\d+)/);
  if (match) return { id: match[1]!, version: parseInt(match[2]!, 10) };
  const parts = resource.split("/");
  return { id: parts[parts.length - 1] || resource, version: null };
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
export async function importAgentMerge(
  file: File,
  selectedSourceIds?: string[]
): Promise<string> {
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

  const location = res.headers.get("Location");
  return location || "";
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
 * Export agent with selected resources only.
 * POST /backup/export/{agentId}?agentVersion={version}&selectedResources=id1,id2
 */
export async function exportAgentSelective(
  agentId: string,
  version: number,
  selectedResourceIds: string[]
): Promise<void> {
  const params = new URLSearchParams({ agentVersion: String(version) });
  if (selectedResourceIds.length > 0) {
    params.set("selectedResources", selectedResourceIds.join(","));
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
): Promise<string> {
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
  return res.headers.get("Location") || "";
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
): Promise<void> {
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
}

/**
 * Batch sync execution.
 * POST /backup/import/sync/batch?sourceUrl=...
 */
export async function executeSyncBatch(
  sourceUrl: string,
  requests: SyncRequest[],
  sourceAuth: string
): Promise<void> {
  const params = new URLSearchParams({ sourceUrl });
  const res = await fetch(`${api.getBaseUrl()}/backup/import/sync/batch?${params}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...mergedHeaders(sourceAuth),
    },
    body: JSON.stringify(requests),
  });
  if (!res.ok) throw new Error(`Batch sync failed: ${res.statusText}`);
}
