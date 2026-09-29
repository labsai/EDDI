import { getAgentDescriptors, parseResourceUri } from "./agents";
import { getWorkflowDescriptors } from "./workflows";
import { getConversationDescriptors } from "./conversations";

export interface DashboardStats {
  agentCount: number;
  workflowCount: number;
  conversationCount: number;
  resourceCount: number;
  /**
   * True when a count hit the page it was read with, so the real number may be
   * higher. Render such a count as "N+", never as exact.
   */
  agentCountCapped: boolean;
  workflowCountCapped: boolean;
  conversationCountCapped: boolean;
}

/** Descriptor page used to count agents and workflows. */
export const DASHBOARD_DESCRIPTOR_LIMIT = 1000;

/**
 * Page used to count conversations — the backend's own ceiling.
 * `RestConversationStore.readConversationDescriptors` clamps `limit` to 100, so
 * asking for 1000 (as this used to) silently got 100 and the dashboard showed
 * "100" as an exact total on any deployment with more.
 */
export const DASHBOARD_CONVERSATION_LIMIT = 100;

/** Aggregate stats from existing API endpoints */
export async function getDashboardStats(): Promise<DashboardStats> {
  const [agents, workflows, conversations] = await Promise.all([
    getAgentDescriptors(DASHBOARD_DESCRIPTOR_LIMIT, 0).catch(() => []),
    getWorkflowDescriptors(DASHBOARD_DESCRIPTOR_LIMIT, 0).catch(() => []),
    getConversationDescriptors(DASHBOARD_CONVERSATION_LIMIT, 0).catch(() => []),
  ]);

  // Deduplicate by resource ID (multiple versions of same resource count as one)
  const uniqueAgentIds = new Set(
    agents.map((a) => parseResourceUri(a.resource).id)
  );
  const uniqueWorkflowIds = new Set(
    workflows.map((w) => parseResourceUri(w.resource).id)
  );

  return {
    agentCount: uniqueAgentIds.size,
    workflowCount: uniqueWorkflowIds.size,
    conversationCount: conversations.length,
    resourceCount: 0, // No single endpoint for total resources
    agentCountCapped: agents.length >= DASHBOARD_DESCRIPTOR_LIMIT,
    workflowCountCapped: workflows.length >= DASHBOARD_DESCRIPTOR_LIMIT,
    conversationCountCapped: conversations.length >= DASHBOARD_CONVERSATION_LIMIT,
  };
}
