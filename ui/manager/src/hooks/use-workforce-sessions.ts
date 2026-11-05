import { useMemo } from "react";
import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import {
  deleteConversation,
  getConversationDescriptors,
  parseConversationUri,
  patchConversation,
  type ConversationDescriptor,
  type ConversationPatch,
} from "@/lib/api/conversations";
import { deleteGroupConversation, listGroupConversations, type GroupConversation } from "@/lib/api/groups";
import { workforceKeys } from "@/lib/query-keys";
import {
  chatSession,
  memberConversationIds,
  teamSession,
  type WorkforceSession,
} from "@/lib/workforce-sessions";
import { useAuth } from "@/hooks/use-auth";
import { useDeployedAgents } from "@/hooks/use-chat";
import { useWorkforceAccess } from "@/hooks/use-workforce-access";
import { useCatalogGroups } from "@/hooks/use-catalog";
import { useEnrichedGroupDescriptors } from "@/hooks/use-groups";

/**
 * The Workforce sidebar's data (T1.1): the signed-in person's chats and team
 * sessions. Reads only endpoints an `eddi-user` may call:
 *
 * - `GET /conversationstore/conversations` — owner-scoped by the backend for
 *   everyone but an admin, who sees every user's conversations; for an admin
 *   the list is narrowed to their own here, by the owner the descriptor records.
 * - `GET /groups/{groupId}/conversations` for each team the person can see
 *   (the catalog for an end user, the group store for an author) — owner-scoped
 *   the same way.
 *
 * Writes go to the owner endpoints: `PATCH` and `DELETE
 * /conversationstore/conversations/{id}`, `DELETE
 * /groups/{groupId}/conversations/{id}`. Each one updates the cached list first
 * and puts it back if the server refuses.
 */

/** How many chats the sidebar reads (the backend clamps a page at 100). */
export const SIDEBAR_CHAT_LIMIT = 100;
/** How many teams' sessions it reads, and how many sessions per team. */
export const SIDEBAR_TEAM_LIMIT = 20;
export const SIDEBAR_SESSIONS_PER_TEAM = 10;

interface TeamSessionsData {
  sessions: GroupConversation[];
  names: Record<string, string>;
}

/** The person's own chats, as descriptors. */
export function useMyChatDescriptors() {
  const { method, user } = useAuth();
  const { isAdmin } = useWorkforceAccess();
  // An admin's listing holds every user's conversations; the sidebar is "mine".
  // With auth off there is no owner to compare with — everything is the user's.
  const ownOnly = method !== "none" && isAdmin && !!user?.username;
  const username = user?.username ?? "";
  return useQuery({
    queryKey: workforceKeys.chats,
    queryFn: async () => {
      const all = await getConversationDescriptors(SIDEBAR_CHAT_LIMIT, 0);
      return ownOnly ? all.filter((d) => !d.userId || d.userId === username) : all;
    },
    staleTime: 15_000,
  });
}

/** The person's team sessions across the teams they can see. */
export function useMyTeamSessions() {
  const { canAuthor } = useWorkforceAccess();
  const { data: storeGroups } = useEnrichedGroupDescriptors(SIDEBAR_TEAM_LIMIT, 0, "", canAuthor);
  const { data: catalogGroups } = useCatalogGroups(!canAuthor);
  const teams = useMemo(
    () =>
      (canAuthor
        ? (storeGroups ?? []).map((g) => ({ id: g.id, name: g.name ?? "" }))
        : (catalogGroups ?? []).map((g) => ({ id: g.groupId, name: g.name ?? "" }))
      ).slice(0, SIDEBAR_TEAM_LIMIT),
    [canAuthor, storeGroups, catalogGroups],
  );
  const teamIds = teams.map((t) => t.id).join(",");
  const { method, user } = useAuth();
  const { isAdmin } = useWorkforceAccess();
  const ownOnly = method !== "none" && isAdmin && !!user?.username;
  const username = user?.username ?? "";

  return useQuery<TeamSessionsData>({
    queryKey: [...workforceKeys.teams, teamIds],
    queryFn: async () => {
      const names: Record<string, string> = {};
      for (const team of teams) names[team.id] = team.name;
      // One failing team must not empty the whole list.
      const results = await Promise.allSettled(
        teams.map((team) => listGroupConversations(team.id, SIDEBAR_SESSIONS_PER_TEAM, 0)),
      );
      const sessions = results
        .flatMap((r) => (r.status === "fulfilled" ? r.value : []))
        .filter((gc) => !ownOnly || !gc.userId || gc.userId === username);
      return { sessions, names };
    },
    enabled: teams.length > 0,
    staleTime: 15_000,
  });
}

/**
 * Chats and team sessions as one list, newest first. Member conversations of
 * a team session are left out of the chats (see `memberConversationIds`).
 */
export function useWorkforceSessions(): {
  sessions: WorkforceSession[];
  isLoading: boolean;
  isError: boolean;
  refetch: () => void;
} {
  const chats = useMyChatDescriptors();
  const teams = useMyTeamSessions();
  const { data: agents } = useDeployedAgents();

  const sessions = useMemo(() => {
    const agentNames = new Map<string, string>((agents ?? []).map((a) => [a.id, a.name ?? ""]));
    const teamConversations = teams.data?.sessions ?? [];
    const hidden = memberConversationIds(teamConversations);
    const chatItems = (chats.data ?? [])
      .filter((d) => !hidden.has(parseConversationUri(d.resource)))
      .map((d) => chatSession(d, agentNames))
      .filter((s): s is WorkforceSession => s !== null);
    const teamItems = teamConversations.map((gc) => teamSession(gc, teams.data?.names[gc.groupId] ?? gc.groupId));
    return [...chatItems, ...teamItems].sort((a, b) => b.lastActivity - a.lastActivity);
  }, [chats.data, teams.data, agents]);

  return {
    sessions,
    isLoading: chats.isLoading,
    isError: chats.isError,
    refetch: () => {
      void chats.refetch();
      void teams.refetch();
    },
  };
}

// ─── Writes ──────────────────────────────────────────────────────

type ChatsSnapshot = ConversationDescriptor[] | undefined;

function updateCachedChat(
  queryClient: QueryClient,
  conversationId: string,
  update: (d: ConversationDescriptor) => ConversationDescriptor | null,
): ChatsSnapshot {
  const previous = queryClient.getQueryData<ConversationDescriptor[]>(workforceKeys.chats);
  if (previous) {
    queryClient.setQueryData<ConversationDescriptor[]>(
      workforceKeys.chats,
      previous.flatMap((d) => {
        if (parseConversationUri(d.resource) !== conversationId) return [d];
        const next = update(d);
        return next ? [next] : [];
      }),
    );
  }
  return previous;
}

/**
 * Rename or (un)pin a chat. The list shows the change at once; a refused
 * request puts the previous list back and rejects, so the caller can say so.
 */
export function usePatchChat() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ conversationId, patch }: { conversationId: string; patch: ConversationPatch }) =>
      patchConversation(conversationId, patch),
    onMutate: async ({ conversationId, patch }) => {
      await queryClient.cancelQueries({ queryKey: workforceKeys.chats });
      const previous = updateCachedChat(queryClient, conversationId, (d) => ({
        ...d,
        ...(patch.title !== undefined ? { title: patch.title, titleSource: "USER" as const } : {}),
        ...(patch.pinned !== undefined ? { pinned: patch.pinned } : {}),
      }));
      return { previous };
    },
    onError: (_err, _vars, context) => {
      if (context?.previous) queryClient.setQueryData(workforceKeys.chats, context.previous);
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: workforceKeys.chats });
    },
  });
}

/** Delete a chat (the owner's delete); optimistic like {@link usePatchChat}. */
export function useDeleteChat() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ conversationId }: { conversationId: string }) => deleteConversation(conversationId),
    onMutate: async ({ conversationId }) => {
      await queryClient.cancelQueries({ queryKey: workforceKeys.chats });
      return { previous: updateCachedChat(queryClient, conversationId, () => null) };
    },
    onError: (_err, _vars, context) => {
      if (context?.previous) queryClient.setQueryData(workforceKeys.chats, context.previous);
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: workforceKeys.chats });
    },
  });
}

/** Delete a team session; optimistic like {@link usePatchChat}. */
export function useDeleteTeamSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ groupId, conversationId }: { groupId: string; conversationId: string }) =>
      deleteGroupConversation(groupId, conversationId),
    onMutate: async ({ conversationId }) => {
      await queryClient.cancelQueries({ queryKey: workforceKeys.teams });
      const previous = queryClient.getQueriesData<TeamSessionsData>({ queryKey: workforceKeys.teams });
      for (const [key, data] of previous) {
        if (!data) continue;
        queryClient.setQueryData<TeamSessionsData>(key, {
          ...data,
          sessions: data.sessions.filter((gc) => gc.id !== conversationId),
        });
      }
      return { previous };
    },
    onError: (_err, _vars, context) => {
      for (const [key, data] of context?.previous ?? []) queryClient.setQueryData(key, data);
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: workforceKeys.teams });
    },
  });
}
