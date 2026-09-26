import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { agentKeys } from "@/lib/query-keys";
import {
  exportAndDownloadAgent,
  exportAgentSelective,
  importAgent,
  previewImport,
  importAgentMerge,
  previewExport,
  previewUpgrade,
  importAgentUpgrade,
  listRemoteAgents,
  previewSync,
  previewSyncBatch,
  executeSync,
  executeSyncBatch,
} from "@/lib/api/backup";
import type {
  ImportPreview,
  ExportPreview,
  SyncMapping,
  SyncRequest,
  DocumentDescriptor,
} from "@/lib/api/backup";

/**
 * Everything an import, merge, upgrade or sync can have written.
 *
 * These operations create or bump the agent AND its workflows and every
 * resource they reference (plus snippets and descriptors), yet they used to
 * invalidate only the agent queries. The workflow and resource lists, the
 * dashboard counts, the orphan scan and every open detail page kept showing
 * the pre-import state until each entry went stale on its own.
 */
const IMPORT_TOUCHED_KEYS = [
  agentKeys.all,
  ["agent"],
  ["agent-descriptor"],
  ["agent-prompt"],
  ["workflows"],
  ["resources"],
  ["latest-versions"],
  ["dashboard"],
  ["orphans"],
] as const;

function invalidateAfterImport(queryClient: QueryClient) {
  for (const queryKey of IMPORT_TOUCHED_KEYS) {
    void queryClient.invalidateQueries({ queryKey });
  }
}

// ==================== Existing Hooks ====================

export function useExportAgent() {
  return useMutation({
    mutationFn: ({ agentId, version = 1 }: { agentId: string; version?: number }) =>
      exportAndDownloadAgent(agentId, version),
  });
}

export function useImportAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => importAgent(file),
    onSuccess: () => {
      invalidateAfterImport(queryClient);
    },
  });
}

export function usePreviewImport() {
  return useMutation({
    mutationFn: (file: File) => previewImport(file),
  });
}

export function useImportAgentMerge() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      file,
      selectedSourceIds,
    }: {
      file: File;
      selectedSourceIds?: string[];
    }) => importAgentMerge(file, selectedSourceIds),
    onSuccess: () => {
      invalidateAfterImport(queryClient);
    },
  });
}

// ==================== Export Preview ====================

export function usePreviewExport(agentId: string, version: number, enabled: boolean) {
  return useQuery({
    queryKey: ["export-preview", agentId, version],
    queryFn: () => previewExport(agentId, version),
    enabled,
    staleTime: 30_000,
  });
}

export function useExportSelective() {
  return useMutation({
    mutationFn: ({
      agentId,
      version,
      selectedResourceIds,
    }: {
      agentId: string;
      version: number;
      selectedResourceIds: string[];
    }) => exportAgentSelective(agentId, version, selectedResourceIds),
  });
}

// ==================== Upgrade Import ====================

export function usePreviewUpgrade() {
  return useMutation({
    mutationFn: ({
      file,
      targetAgentId,
    }: {
      file: File;
      targetAgentId: string;
    }) => previewUpgrade(file, targetAgentId),
  });
}

export function useImportUpgrade() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      file,
      targetAgentId,
      selectedSourceIds,
      workflowOrder,
    }: {
      file: File;
      targetAgentId: string;
      selectedSourceIds?: string[];
      workflowOrder?: string[];
    }) => importAgentUpgrade(file, targetAgentId, selectedSourceIds, workflowOrder),
    onSuccess: () => {
      invalidateAfterImport(queryClient);
    },
  });
}

// ==================== Live Sync ====================

export function useListRemoteAgents() {
  return useMutation({
    mutationFn: ({
      sourceUrl,
      sourceAuth,
    }: {
      sourceUrl: string;
      sourceAuth: string;
    }) => listRemoteAgents(sourceUrl, sourceAuth),
  });
}

export function usePreviewSync() {
  return useMutation({
    mutationFn: ({
      sourceUrl,
      sourceAgentId,
      sourceVersion,
      targetAgentId,
      sourceAuth,
    }: {
      sourceUrl: string;
      sourceAgentId: string;
      sourceVersion: number | null;
      targetAgentId: string | null;
      sourceAuth: string;
    }) => previewSync(sourceUrl, sourceAgentId, sourceVersion, targetAgentId, sourceAuth),
  });
}

export function usePreviewSyncBatch() {
  return useMutation({
    mutationFn: ({
      sourceUrl,
      mappings,
      sourceAuth,
    }: {
      sourceUrl: string;
      mappings: SyncMapping[];
      sourceAuth: string;
    }) => previewSyncBatch(sourceUrl, mappings, sourceAuth),
  });
}

export function useExecuteSync() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      sourceUrl,
      sourceAgentId,
      sourceVersion,
      targetAgentId,
      selectedResources,
      workflowOrder,
      sourceAuth,
    }: {
      sourceUrl: string;
      sourceAgentId: string;
      sourceVersion: number | null;
      targetAgentId: string | null;
      selectedResources: string[] | null;
      workflowOrder: string[] | null;
      sourceAuth: string;
    }) =>
      executeSync(
        sourceUrl,
        sourceAgentId,
        sourceVersion,
        targetAgentId,
        selectedResources,
        workflowOrder,
        sourceAuth
      ),
    onSuccess: () => {
      invalidateAfterImport(queryClient);
    },
  });
}

export function useExecuteSyncBatch() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      sourceUrl,
      requests,
      sourceAuth,
    }: {
      sourceUrl: string;
      requests: SyncRequest[];
      sourceAuth: string;
    }) => executeSyncBatch(sourceUrl, requests, sourceAuth),
    onSuccess: () => {
      invalidateAfterImport(queryClient);
    },
  });
}

export type { ImportPreview, ExportPreview, DocumentDescriptor };
