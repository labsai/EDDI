import { useQuery } from "@tanstack/react-query";
import { getAgentProfile } from "../lib/api/agents";
import { resolveConversationControls } from "../lib/api/catalog";

/**
 * The deployed agent's profile, as far as a chat is concerned: its review
 * notice, its rating controls, its `conversationControls` (B4), and whether the
 * lookup is still out.
 *
 * One query for the notice and for the chat that must wait for it, so both see
 * the same answer. `pending` is true only while a lookup is actually outstanding:
 * a failed lookup (an EDDI without the endpoint) settles it, so a chat never
 * blocks on the notice, only waits for the request.
 */
export function useAgentReviewProfile(agentId: string | null, environment?: string) {
  const query = useQuery({
    queryKey: ["agents", "profile", agentId, environment ?? "production"],
    queryFn: () => getAgentProfile(agentId!, environment ?? "production"),
    enabled: !!agentId,
    retry: false,
    staleTime: 60_000,
  });
  return {
    reviewNotice: query.data?.reviewNotice ?? null,
    /** How the deployed version asks for ratings, or null when it does not. */
    rating: query.data?.rating ?? null,
    /**
     * Which controls to offer, defaults applied: everything on (and raw HTML
     * off) while the lookup is out, when it failed, or on an older backend.
     */
    controls: resolveConversationControls(query.data?.conversationControls),
    /** The deployed version's appearance, or null. */
    appearance: query.data?.appearance ?? null,
    // A disabled query also reports isPending, so it counts only with an agent.
    pending: !!agentId && query.isPending,
  };
}
