import { catalogDisplayName, type CatalogAgent, type CatalogGroup } from "@eddi/app-kit/lib/api/catalog";
import type { ChatMentionCandidate } from "@eddi/app-kit/lib/chat-mention";

/**
 * Who a chat may @mention (T2.2): the catalog's agents and teams — the entries
 * the caller may USE that their owners listed (`GET /catalog/*`) — and never
 * the agent store, which would offer agents the person cannot run. The chat's
 * own agent is left out: handing a question to the agent already answering it
 * is a send.
 */
export function mentionCandidates(
  agents: readonly CatalogAgent[] | undefined,
  groups: readonly CatalogGroup[] | undefined,
  currentAgentId: string | null,
): ChatMentionCandidate[] {
  return [
    ...(agents ?? [])
      .filter((a) => a.agentId !== currentAgentId)
      .map((a) => ({ kind: "agent" as const, id: a.agentId, name: catalogDisplayName(a), description: a.description })),
    ...(groups ?? []).map((g) => ({
      kind: "team" as const,
      id: g.groupId,
      name: g.name || g.groupId,
      description: g.description,
    })),
  ];
}
