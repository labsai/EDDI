import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
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

const AGENTS_KEY = ["agents"] as const;

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
      queryClient.invalidateQueries({ queryKey: AGENTS_KEY });
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
      queryClient.invalidateQueries({ queryKey: AGENTS_KEY });
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
      queryClient.invalidateQueries({ queryKey: AGENTS_KEY });
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
      queryClient.invalidateQueries({ queryKey: AGENTS_KEY });
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
      queryClient.invalidateQueries({ queryKey: AGENTS_KEY });
    },
  });
}

export type { ImportPreview, ExportPreview, DocumentDescriptor };
