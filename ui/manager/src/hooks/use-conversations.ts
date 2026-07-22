import {
  useQuery,
  useMutation,
  useQueryClient,
  keepPreviousData,
} from "@tanstack/react-query";
import {
  getConversationDescriptors,
  getSimpleConversationLog,
  getRawConversationLog,
  deleteConversation,
  getActiveConversations,
  endActiveConversations,
  purgeEndedConversations,
  type ConversationState,
  type ConversationStatus,
  type ViewState,
} from "@/lib/api/conversations";

const CONVERSATIONS_KEY = ["conversations"] as const;

export function useConversationDescriptors(
  limit = 20,
  index = 0,
  filter = "",
  agentId = "",
  conversationState?: ConversationState,
  agentVersion?: number,
  viewState?: ViewState
) {
  return useQuery({
    queryKey: [
      ...CONVERSATIONS_KEY,
      "descriptors",
      { limit, index, filter, agentId, conversationState, agentVersion, viewState },
    ],
    queryFn: () =>
      getConversationDescriptors(
        limit,
        index,
        filter,
        agentId,
        agentVersion,
        conversationState,
        viewState
      ),
    // Keep the previous page visible while the next one loads so paging
    // doesn't flash the skeleton on every click.
    placeholderData: keepPreviousData,
  });
}

export function useSimpleConversation(
  id: string,
  returnDetailed = true,
  returnCurrentStepOnly = false
) {
  return useQuery({
    queryKey: [...CONVERSATIONS_KEY, "simple", id, { returnDetailed, returnCurrentStepOnly }],
    queryFn: () => getSimpleConversationLog(id, returnDetailed, returnCurrentStepOnly),
    enabled: !!id,
  });
}

export function useRawConversation(id: string) {
  return useQuery({
    queryKey: [...CONVERSATIONS_KEY, "raw", id],
    queryFn: () => getRawConversationLog(id),
    enabled: !!id,
  });
}

export function useDeleteConversation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      permanent = false,
    }: {
      id: string;
      permanent?: boolean;
    }) => deleteConversation(id, permanent),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: CONVERSATIONS_KEY });
    },
  });
}

// ─── Active-conversation monitoring + bulk lifecycle ────────────────

const ACTIVE_KEY = [...CONVERSATIONS_KEY, "active"] as const;

/**
 * Live list of active conversations for one agent+version.
 * Polls while enabled so the monitoring view stays current. The backend
 * requires both agentId and a numeric agentVersion, so the query is disabled
 * until both are provided.
 */
export function useActiveConversations(
  agentId: string,
  agentVersion?: number,
  options: { pollMs?: number } = {}
) {
  const { pollMs = 10_000 } = options;
  return useQuery({
    queryKey: [...ACTIVE_KEY, agentId, agentVersion],
    queryFn: () => getActiveConversations(agentId, agentVersion as number),
    enabled: !!agentId && agentVersion != null,
    refetchInterval: pollMs,
    placeholderData: keepPreviousData,
  });
}

/** Bulk-end selected active conversations, then refresh the active list. */
export function useEndActiveConversations() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (statuses: ConversationStatus[]) =>
      endActiveConversations(statuses),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: CONVERSATIONS_KEY });
    },
  });
}

/** Admin bulk-purge of ENDED conversations older than N days. */
export function usePurgeEndedConversations() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (deleteOlderThanDays: number) =>
      purgeEndedConversations(deleteOlderThanDays),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: CONVERSATIONS_KEY });
    },
  });
}

/**
 * Lazily fetch step count for a single conversation.
 * Uses returnDetailed=false to minimize data transfer.
 */
export function useConversationStepCount(id: string) {
  return useQuery({
    queryKey: [...CONVERSATIONS_KEY, "stepCount", id],
    queryFn: async () => {
      const data = await getSimpleConversationLog(id, false, false);
      return data.conversationSteps?.length ?? 0;
    },
    enabled: !!id,
    staleTime: 60_000,
  });
}
