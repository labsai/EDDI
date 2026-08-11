import { useMemo } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  DISCUSSION_STYLES,
  type DiscussionStyle,
  getGroupDescriptors,
  getEnrichedGroupDescriptors,
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
  deleteGroupWithMembers,
  type AgentGroupConfiguration,
  type GroupConversationState,
} from "@/lib/api/groups";

const GROUPS_KEY = ["groups"] as const;
export const GROUP_CONVERSATIONS_KEY = ["groupConversations"] as const;

/** Conversation states in which the backend is still working on the discussion,
 *  so the UI should keep polling and show it as ongoing. */
export function isActiveConversationState(
  state: GroupConversationState | undefined,
): boolean {
  return state === "IN_PROGRESS" || state === "SYNTHESIZING";
}

// ─── Group Config Hooks ──────────────────────────────────────────

export function useGroupDescriptors(limit = 20, index = 0, filter = "") {
  return useQuery({
    queryKey: [...GROUPS_KEY, "descriptors", { limit, index, filter }],
    queryFn: () => getGroupDescriptors(limit, index, filter),
  });
}

export function useEnrichedGroupDescriptors(limit = 20, index = 0, filter = "") {
  return useQuery({
    queryKey: [...GROUPS_KEY, "enriched", { limit, index, filter }],
    queryFn: () => getEnrichedGroupDescriptors(limit, index, filter),
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

export interface AvailableStyles {
  /** The styles a picker should offer, in canonical order. */
  styles: DiscussionStyle[];
  /**
   * Backend-supplied display label per style — the fallback name for a style
   * this build has no localized entry for.
   */
  backendLabels: Record<string, string>;
}

/**
 * The discussion styles this UI should offer, reconciled with what the backend
 * actually supports (`GET /groupstore/groups/styles`).
 *
 * The static `DISCUSSION_STYLES` list was the only source before, which broke in
 * both directions: a style the backend dropped (or an older backend never had)
 * was still offered and failed at save time, and a style the backend added
 * simply never appeared. While the request is in flight — or fails — the static
 * list stands, so pickers never render empty.
 *
 * Backend-only styles are widened to `DiscussionStyle`: the wire format is a
 * plain string, and every consumer treats an unknown value with fallbacks
 * (`styleInfo` → undefined, colors → CUSTOM/default).
 */
export function useAvailableStyles(): AvailableStyles {
  const { data } = useDiscussionStyles();
  return useMemo(() => {
    const fallback: AvailableStyles = { styles: [...DISCUSSION_STYLES], backendLabels: {} };
    if (!data || typeof data !== "object") return fallback;
    const backendKeys = Object.keys(data);
    if (backendKeys.length === 0) return fallback;

    const known = DISCUSSION_STYLES.filter((s) => backendKeys.includes(s));
    // A response with none of the known styles is far more likely a contract
    // change than a backend with zero presets — don't blank every picker over it.
    if (known.length === 0) return fallback;

    const knownSet = new Set<string>(DISCUSSION_STYLES);
    const unknown = backendKeys.filter((k) => !knownSet.has(k)) as DiscussionStyle[];

    const backendLabels: Record<string, string> = {};
    for (const key of backendKeys) {
      const entry = (data as Record<string, unknown>)[key];
      if (entry && typeof entry === "object" && typeof (entry as { label?: unknown }).label === "string") {
        backendLabels[key] = (entry as { label: string }).label;
      }
    }

    return { styles: [...known, ...unknown], backendLabels };
  }, [data]);
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
    // Keep the list live while any discussion is still running, so its state
    // badge flips on its own instead of needing a manual reload.
    refetchInterval: (query) =>
      query.state.data?.some((c) => isActiveConversationState(c.state)) ? 5000 : false,
  });
}

export function useGroupConversation(groupId: string, conversationId: string) {
  return useQuery({
    queryKey: [...GROUP_CONVERSATIONS_KEY, groupId, conversationId],
    queryFn: () => getGroupConversation(groupId, conversationId),
    enabled: !!groupId && !!conversationId,
    refetchInterval: (query) =>
      // Poll while the discussion is in progress — this is what keeps a
      // reloaded page (no SSE connection) following a running discussion.
      isActiveConversationState(query.state.data?.state) ? 3000 : false,
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

export function useDeleteGroupWithMembers() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      groupId,
      version,
      config,
    }: {
      groupId: string;
      version: number;
      config: AgentGroupConfiguration;
    }) => deleteGroupWithMembers(groupId, version, config),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: GROUPS_KEY });
    },
  });
}
