import { useQuery, useQueries, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getEnrichedChannelDescriptors,
  getChannel,
  createChannel,
  updateChannel,
  deleteChannel,
  duplicateChannel,
  getEmailSenders,
  updateEmailSenders,
  getHeldEmail,
  getHeldEmailPreview,
  decideHeldEmail,
  type HeldEmailDecision,
  type HeldEmailQueue,
  type ChannelIntegrationConfiguration,
  type EmailSenderChange,
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
      confirmOpen,
    }: {
      id: string;
      version: number;
      config: ChannelIntegrationConfiguration;
      confirmOpen?: boolean;
    }) => updateChannel(id, version, config, confirmOpen ?? false),
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

// ─── Email sender lists ──────────────────────────────────────────

export function useEmailSenders(id: string, enabled = true) {
  return useQuery({
    queryKey: [...CHANNELS_KEY, id, "email-senders"],
    queryFn: () => getEmailSenders(id),
    enabled: !!id && enabled,
  });
}

/**
 * A targeted sender-list change. It writes a new version of the integration,
 * so every channel query is stale afterwards — including the document the
 * detail page was seeded from, which would otherwise write the old lists back
 * on its next save.
 */
export function useUpdateEmailSenders() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, change }: { id: string; change: EmailSenderChange }) => updateEmailSenders(id, change),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: CHANNELS_KEY });
    },
  });
}

// ─── Held mail ────────────────────────────────────────────────────

const HELD_EMAIL_KEY = ["held-email"] as const;

/**
 * The held mail of every email integration the caller can see — the approvals
 * page lists it as its own kind. One request per email integration; an
 * integration the caller may not review answers 403 and is left out.
 */
export function useAllHeldEmail(enabled = true) {
  const { data: channels, isLoading: channelsLoading } = useEnrichedChannelDescriptors(100);
  const emailIds = (channels ?? []).filter((c) => c.channelType === "email").map((c) => c.id);
  const results = useQueries({
    queries: emailIds.map((id) => ({
      queryKey: [...HELD_EMAIL_KEY, id],
      queryFn: () => getHeldEmail(id),
      enabled,
      retry: false,
      refetchInterval: 30_000,
    })),
  });
  const queues: HeldEmailQueue[] = results.flatMap((r) => (r.data ? [r.data] : []));
  return {
    queues,
    isLoading: channelsLoading || results.some((r) => r.isLoading),
  };
}

export function useHeldEmailPreview(id: string, rowId: string, enabled = true) {
  return useQuery({
    queryKey: [...HELD_EMAIL_KEY, id, rowId, "preview"],
    queryFn: () => getHeldEmailPreview(id, rowId),
    enabled: !!id && !!rowId && enabled,
    retry: false,
    // Live, never cached: the mail is re-read each time the dialog opens.
    gcTime: 0,
    staleTime: 0,
  });
}

/** A held-mail decision; runAndAllow writes a new integration version too. */
export function useDecideHeldEmail() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, rowId, decision, note }: { id: string; rowId: string; decision: HeldEmailDecision; note?: string }) =>
      decideHeldEmail(id, rowId, decision, note),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: HELD_EMAIL_KEY });
      queryClient.invalidateQueries({ queryKey: CHANNELS_KEY });
    },
  });
}
