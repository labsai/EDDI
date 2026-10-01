import { useQuery } from "@tanstack/react-query";
import { getAgentProfile } from "@/lib/api/agents";

/**
 * The deployed agent's profile, as far as conversation review is concerned: its
 * review notice, and whether the lookup is still out.
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
    // A disabled query also reports isPending, so it counts only with an agent.
    pending: !!agentId && query.isPending,
  };
}
