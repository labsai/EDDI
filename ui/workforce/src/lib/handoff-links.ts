import type { WorkforceHandoff } from "@eddi/app-kit/lib/workforce-handoffs";

/** Where the conversation a hand-off started lives, or null while it is not known. */
export function handoffTargetHref(handoff: WorkforceHandoff): string | null {
  const conversationId = handoff.to.conversationId;
  if (!conversationId) return null;
  if (handoff.to.kind === "agent") {
    return `/workforce/chat?${new URLSearchParams({ agentId: handoff.to.id, conversationId }).toString()}`;
  }
  const params = new URLSearchParams();
  if (handoff.to.version) params.set("version", String(handoff.to.version));
  params.set("conversation", conversationId);
  return `/workforce/${encodeURIComponent(handoff.to.id)}?${params.toString()}`;
}

/** The chat a hand-off came from. */
export function handoffOriginHref(handoff: WorkforceHandoff): string {
  return `/workforce/chat?${new URLSearchParams({
    agentId: handoff.from.agentId,
    conversationId: handoff.from.conversationId,
  }).toString()}`;
}
