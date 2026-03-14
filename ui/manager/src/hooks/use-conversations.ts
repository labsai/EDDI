import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getConversationDescriptors,
  getSimpleConversationLog,
  getRawConversationLog,
  deleteConversation,
  type ConversationState,
} from "@/lib/api/conversations";

const CONVERSATIONS_KEY = ["conversations"] as const;

export function useConversationDescriptors(
  limit = 20,
  index = 0,
  filter = "",
  botId = "",
  conversationState?: ConversationState
) {
  return useQuery({
    queryKey: [
      ...CONVERSATIONS_KEY,
      "descriptors",
      { limit, index, filter, botId, conversationState },
    ],
    queryFn: () =>
      getConversationDescriptors(
        limit,
        index,
        filter,
        botId,
        undefined,
        conversationState
      ),
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
