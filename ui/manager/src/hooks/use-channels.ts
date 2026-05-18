import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getEnrichedChannelDescriptors,
  getChannel,
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

export function useChannel(id: string, version?: number) {
  return useQuery({
    queryKey: [...CHANNELS_KEY, id, version],
    queryFn: () => getChannel(id, version),
    enabled: !!id,
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
