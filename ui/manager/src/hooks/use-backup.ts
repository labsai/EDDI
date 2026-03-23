import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  exportAndDownloadAgent,
  importAgent,
  previewImport,
  importAgentMerge,
} from "@/lib/api/backup";
import type { ImportPreview } from "@/lib/api/backup";

const AGENTS_KEY = ["agents"] as const;

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
      selectedOriginIds,
    }: {
      file: File;
      selectedOriginIds?: string[];
    }) => importAgentMerge(file, selectedOriginIds),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: AGENTS_KEY });
    },
  });
}

export type { ImportPreview };
