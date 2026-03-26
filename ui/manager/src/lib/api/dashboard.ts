import { getAgentDescriptors, parseResourceUri } from "./agents";
import { getWorkflowDescriptors } from "./workflows";
import { getConversationDescriptors } from "./conversations";

export interface DashboardStats {
  agentCount: number;
  workflowCount: number;
  conversationCount: number;
  resourceCount: number;
}

/** Aggregate stats from existing API endpoints */
export async function getDashboardStats(): Promise<DashboardStats> {
  const [agents, workflows, conversations] = await Promise.all([
    getAgentDescriptors(1000, 0).catch(() => []),
    getWorkflowDescriptors(1000, 0).catch(() => []),
    getConversationDescriptors(1000, 0).catch(() => []),
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
  };
}
