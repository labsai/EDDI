import { getAgentDescriptors } from "./agents";
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
  const [agents, packages, conversations] = await Promise.all([
    getAgentDescriptors(1000, 0).catch(() => []),
    getWorkflowDescriptors(1000, 0).catch(() => []),
    getConversationDescriptors(1000, 0).catch(() => []),
  ]);

  return {
    agentCount: agents.length,
    workflowCount: packages.length,
    conversationCount: conversations.length,
    resourceCount: 0, // No single endpoint for total resources
  };
}
