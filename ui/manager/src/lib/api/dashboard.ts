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
  /**
   * Which counts could not be read. A failed count is reported here rather than
   * as 0: "0 agents" and "could not ask" are different facts, and the dashboard
   * is the first screen an operator looks at to decide whether anything is
   * wrong. Optional so a consumer that only reads counts need not know.
   */
  failed?: DashboardCount[];
}

export type DashboardCount = "agents" | "workflows" | "conversations";

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
  const results = await Promise.allSettled([
    getAgentDescriptors(DASHBOARD_DESCRIPTOR_LIMIT, 0),
    getWorkflowDescriptors(DASHBOARD_DESCRIPTOR_LIMIT, 0),
    getConversationDescriptors(DASHBOARD_CONVERSATION_LIMIT, 0),
  ]);

  // Every read failed: there is nothing to show, so let the query fail and the
  // page say why (403, network, …) instead of drawing three zeros.
  const rejected = results.find((r): r is PromiseRejectedResult => r.status === "rejected");
  if (rejected && results.every((r) => r.status === "rejected")) throw rejected.reason;

  const failed: DashboardCount[] = [];
  const [agentsResult, workflowsResult, conversationsResult] = results;
  if (agentsResult.status === "rejected") failed.push("agents");
  if (workflowsResult.status === "rejected") failed.push("workflows");
  if (conversationsResult.status === "rejected") failed.push("conversations");
  const agents = agentsResult.status === "fulfilled" ? agentsResult.value : [];
  const workflows = workflowsResult.status === "fulfilled" ? workflowsResult.value : [];
  const conversations =
    conversationsResult.status === "fulfilled" ? conversationsResult.value : [];

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
    failed,
  };
}
