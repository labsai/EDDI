/**
 * TanStack Query keys, in one place.
 *
 * ## Why this exists
 *
 * A query key is a plain array, so a reader and an invalidator that disagree by
 * one character simply never meet — and neither TypeScript nor the test suite
 * notices, because both sides are individually valid. The failure is silent and
 * looks exactly like a caching quirk.
 *
 * That is not hypothetical here. `agent-editor-sheet.tsx` read
 * `["agent-descriptor", id]` and invalidated it on save, while
 * `agent-details-panel.tsx` and `workforce-thread.tsx` read
 * `["agent-descriptor-direct", id]` — the same request to the same endpoint
 * under a second name that nothing in the codebase ever invalidated. Saving an
 * agent left the details panel and the thread header showing the old name until
 * the entry went stale on its own.
 *
 * Routing keys through here makes the two sides the same expression, so they
 * cannot drift apart without a type error.
 *
 * ## Hierarchy
 *
 * TanStack matches keys by PREFIX, so nesting is the invalidation API:
 * `invalidateQueries({ queryKey: agentKeys.all })` clears every agent query,
 * while `agentKeys.descriptor(id)` clears one. Keep the general-to-specific
 * ordering when adding keys — a key that puts its id first cannot be swept.
 *
 * Only keys with more than one call site, or with an invalidator somewhere other
 * than the file that reads them, need to live here. A query read and invalidated
 * inside a single hook is fine where it is.
 */

/**
 * How call sites actually hold an agent id: `useParams` yields `string |
 * undefined`, while a resolved-or-not lookup yields `string | null`. Accepting
 * both keeps the factory usable without a cast at either kind of site — and a
 * cast is exactly what would tempt someone back to a hand-written key.
 *
 * A nullish id is a legitimate key: the queries that use it are `enabled: false`
 * until the id resolves, so the entry is never fetched, and using the same shape
 * throughout means the eventual invalidation matches.
 */
type AgentId = string | null | undefined;

export const agentKeys = {
  all: ["agents"] as const,
  /** Paged descriptor list. */
  descriptors: (limit: number, index: number, filter: string) =>
    [...agentKeys.all, "descriptors", { limit, index, filter }] as const,
  /** Infinite-scroll descriptor list. */
  descriptorsInfinite: (filter: string) =>
    [...agentKeys.all, "descriptors-infinite", { filter }] as const,
  /** One agent's full document. */
  detail: (agentId: AgentId) => ["agent", agentId] as const,
  /**
   * One agent's descriptor, looked up by id.
   *
   * The single key for `getAgentDescriptors(1, 0, agentId)` wherever it is
   * called. It previously existed twice — see the file header.
   */
  descriptor: (agentId: AgentId) => ["agent-descriptor", agentId] as const,
  /** One agent's resolved system prompt. */
  prompt: (agentId: AgentId, version?: number) =>
    version === undefined
      ? (["agent-prompt", agentId] as const)
      : (["agent-prompt", agentId, version] as const),
} as const;

export const groupKeys = {
  all: ["groups"] as const,
  conversations: ["groupConversations"] as const,
  conversationsFor: (groupId: string | null | undefined) =>
    [...groupKeys.conversations, groupId] as const,
} as const;

/**
 * Everything a successful agent write invalidates.
 *
 * Callers that mutate an agent — the Workforce editor sheet, the prompt
 * mutation — must refresh the same set, or one surface updates and another does
 * not. Enumerating it once is what keeps them honest; a caller that hand-rolled
 * the list is how the descriptor bug happened.
 */
export function agentWriteInvalidations(agentId: AgentId) {
  return [agentKeys.detail(agentId), agentKeys.descriptor(agentId), agentKeys.all];
}
