import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getGroupDescriptors,
  getGroup,
  createGroup,
  updateGroup,
  deleteGroup,
  duplicateGroup,
  getDiscussionStyles,
  startGroupDiscussion,
  getGroupConversation,
  listGroupConversations,
  deleteGroupConversation,
  type AgentGroupConfiguration,
} from "@/lib/api/groups";

const GROUPS_KEY = ["groups"] as const;
const GROUP_CONVERSATIONS_KEY = ["groupConversations"] as const;

// ─── Group Config Hooks ──────────────────────────────────────────

export function useGroupDescriptors(limit = 20, index = 0, filter = "") {
  return useQuery({
    queryKey: [...GROUPS_KEY, "descriptors", { limit, index, filter }],
    queryFn: () => getGroupDescriptors(limit, index, filter),
  });
}

export function useGroup(id: string, version?: number) {
  return useQuery({
    queryKey: [...GROUPS_KEY, id, version],
    queryFn: () => getGroup(id, version),
    enabled: !!id,
  });
}

export function useDiscussionStyles() {
  return useQuery({
    queryKey: [...GROUPS_KEY, "styles"],
    queryFn: () => getDiscussionStyles(),
    staleTime: Infinity,
  });
}

export function useCreateGroup() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (config: AgentGroupConfiguration) => createGroup(config),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: GROUPS_KEY });
    },
  });
}

export function useUpdateGroup() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      version,
      config,
    }: {
      id: string;
      version: number;
      config: AgentGroupConfiguration;
    }) => updateGroup(id, version, config),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: GROUPS_KEY });
    },
  });
}

export function useDeleteGroup() {
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
    }) => deleteGroup(id, version, permanent),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: GROUPS_KEY });
    },
  });
}

export function useDuplicateGroup() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, version }: { id: string; version: number }) =>
      duplicateGroup(id, version),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: GROUPS_KEY });
    },
  });
}

// ─── Group Conversation Hooks ────────────────────────────────────

export function useGroupConversations(groupId: string, limit = 20, index = 0) {
  return useQuery({
    queryKey: [...GROUP_CONVERSATIONS_KEY, groupId, { limit, index }],
    queryFn: () => listGroupConversations(groupId, limit, index),
    enabled: !!groupId,
  });
}

export function useGroupConversation(groupId: string, conversationId: string) {
  return useQuery({
    queryKey: [...GROUP_CONVERSATIONS_KEY, groupId, conversationId],
    queryFn: () => getGroupConversation(groupId, conversationId),
    enabled: !!groupId && !!conversationId,
    refetchInterval: (query) => {
      // Poll while discussion is in progress
      const state = query.state.data?.state;
      return state === "IN_PROGRESS" || state === "SYNTHESIZING"
        ? 3000
        : false;
    },
  });
}

export function useStartDiscussion() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      groupId,
      question,
      userId,
    }: {
      groupId: string;
      question: string;
      userId?: string;
    }) => startGroupDiscussion(groupId, question, userId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: GROUP_CONVERSATIONS_KEY });
    },
  });
}

export function useDeleteGroupConversation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      groupId,
      conversationId,
    }: {
      groupId: string;
      conversationId: string;
    }) => deleteGroupConversation(groupId, conversationId),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: GROUP_CONVERSATIONS_KEY });
    },
  });
}
