import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getEnrichedChannelDescriptors,
  getChannel,
  getChannelCurrentVersion,
  createChannel,
  updateChannel,
  deleteChannel,
  duplicateChannel,
  type ChannelIntegrationConfiguration,
} from "@/lib/api/channels";

const CHANNELS_KEY = ["channels"] as const;

// ─── Channel Config Hooks ────────────────────────────────────────

export function useEnrichedChannelDescriptors(
  limit = 20,
  index = 0,
  filter = "",
) {
  return useQuery({
    queryKey: [...CHANNELS_KEY, "enriched", { limit, index, filter }],
    queryFn: () => getEnrichedChannelDescriptors(limit, index, filter),
  });
}

export function useChannel(id: string, version?: number, enabled = true) {
  return useQuery({
    queryKey: [...CHANNELS_KEY, id, version],
    queryFn: () => getChannel(id, version),
    enabled: enabled && !!id,
  });
}

/** The channel's current version, for a link that names none. */
export function useChannelCurrentVersion(id: string, enabled = true) {
  return useQuery({
    queryKey: [...CHANNELS_KEY, id, "currentversion"],
    queryFn: () => getChannelCurrentVersion(id),
    enabled: enabled && !!id,
    // Always this navigation's answer, never a cached one: another editor may
    // have saved a newer version since.
    staleTime: 0,
    gcTime: 0,
    retry: false,
  });
}

export function useCreateChannel() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (config: ChannelIntegrationConfiguration) =>
      createChannel(config),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: CHANNELS_KEY });
    },
  });
}

export function useUpdateChannel() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      version,
      config,
    }: {
      id: string;
      version: number;
      config: ChannelIntegrationConfiguration;
    }) => updateChannel(id, version, config),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: CHANNELS_KEY });
    },
  });
}

export function useDeleteChannel() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      version,
      permanent,
    }: {
      id: string;
      version: number;
      permanent?: boolean;
    }) => deleteChannel(id, version, permanent),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: CHANNELS_KEY });
    },
  });
}

export function useDuplicateChannel() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version }: { id: string; version: number }) =>
      duplicateChannel(id, version),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: CHANNELS_KEY });
    },
  });
}
