import { useMemo } from "react";
import { keepPreviousData, useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  DISCUSSION_STYLES,
  type DiscussionStyle,
  getGroupDescriptors,
  getEnrichedGroupDescriptors,
  getGroup,
  getGroupCurrentVersion,
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
    // A new filter keeps showing the previous results until its own arrive,
    // instead of blanking the list to a skeleton on every search.
    placeholderData: keepPreviousData,
  });
}

export function useGroup(id: string, version?: number) {
  return useQuery({
    queryKey: [...GROUPS_KEY, id, version],
    queryFn: () => getGroup(id, version),
    enabled: !!id,
  });
}

/**
 * The version a page should read, given what its URL says.
 *
 * An explicit, valid `?version=` wins — a link can deliberately point at an
 * older version. Without one, the group's CURRENT version is looked up rather
 * than assumed to be 1: several Workforce links (the history page's way back,
 * an advisor's thread) carried no version, and the pages behind them read the
 * group's first version — its original name, members and phases — and linked
 * onward to it, so one version-less hop pinned the whole session to history.
 *
 * `undefined` while the lookup is in flight, so callers leave `useGroup`
 * disabled instead of fetching version 1 in the meantime. A failed lookup falls
 * back to 1, the old behaviour, rather than blocking the page on it.
 */
export function useResolvedGroupVersion(
  groupId: string | undefined,
  urlVersion: string | null,
): number | undefined {
  const explicit = urlVersion != null && /^[1-9]\d*$/.test(urlVersion) ? Number(urlVersion) : undefined;
  const { data, isError } = useQuery({
    queryKey: [...GROUPS_KEY, groupId, "currentVersion"],
    queryFn: () => getGroupCurrentVersion(groupId!),
    enabled: !!groupId && explicit === undefined,
    staleTime: 30_000,
  });
  if (explicit !== undefined) return explicit;
  if (typeof data === "number" && data > 0) return data;
  return isError ? 1 : undefined;
}

export function useDiscussionStyles() {
  return useQuery({
    queryKey: [...GROUPS_KEY, "styles"],
    queryFn: () => getDiscussionStyles(),
    staleTime: Infinity,
  });
}

/**
 * The discussion styles this UI should offer, reconciled with what the backend
 * actually supports (`GET /groupstore/groups/styles`).
 *
 * The static `DISCUSSION_STYLES` list was the only source before, so a style the
 * backend dropped (or an older backend never had) was still offered and then
 * failed at save time. While the request is in flight — or fails — the static
 * list stands, so pickers never render empty.
 *
 * Narrowed to styles this build KNOWS: everything that makes a style usable —
 * its localized name and flow text, phase expansion for the HITL approval
 * picker, the wizard hint, the transcript breadcrumb — is keyed off
 * `DiscussionStyle`, so a backend-only name would be an option the UI cannot
 * describe or configure. Such a style is simply not offered; one already SAVED
 * on a group still renders, via `styleDisplay`'s raw-value fallback.
 */
export function useAvailableStyles(): DiscussionStyle[] {
  const { data } = useDiscussionStyles();
  return useMemo(() => {
    const fallback = [...DISCUSSION_STYLES];
    // The wire format is an array of descriptors, so read each entry's `style`
    // field. Treating the payload as a map keyed by the enum — as this hook
    // first did — yields the array indices "0", "1", "2"… instead, matches
    // nothing, and silently disables the whole check against the real API.
    if (!Array.isArray(data)) return fallback;
    const supported = new Set(
      data
        .map((entry) => (entry && typeof entry === "object" ? entry.style : null))
        .filter((style): style is string => typeof style === "string"),
    );
    if (supported.size === 0) return fallback;

    const known = DISCUSSION_STYLES.filter((s) => supported.has(s));
    // A response with none of the known styles is far more likely a contract
    // change than a backend with zero presets — don't blank every picker over it.
    return known.length > 0 ? known : fallback;
  }, [data]);
}

/** Whether a style can still be created against the running backend. */
export function isStyleSupported(
  style: DiscussionStyle | null | undefined,
  supported: readonly DiscussionStyle[],
): boolean {
  return !style || supported.includes(style);
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
