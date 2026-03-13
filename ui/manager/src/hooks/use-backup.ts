import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  exportAndDownloadBot,
  importBot,
  previewImport,
  importBotMerge,
} from "@/lib/api/backup";
import type { ImportPreview } from "@/lib/api/backup";

const BOTS_KEY = ["bots"] as const;

export function useExportBot() {
  return useMutation({
    mutationFn: ({ botId, version = 1 }: { botId: string; version?: number }) =>
      exportAndDownloadBot(botId, version),
  });
}

export function useImportBot() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => importBot(file),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: BOTS_KEY });
    },
  });
}

export function usePreviewImport() {
  return useMutation({
    mutationFn: (file: File) => previewImport(file),
  });
}

export function useImportBotMerge() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      file,
      selectedOriginIds,
    }: {
      file: File;
      selectedOriginIds?: string[];
    }) => importBotMerge(file, selectedOriginIds),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: BOTS_KEY });
    },
  });
}

export type { ImportPreview };
