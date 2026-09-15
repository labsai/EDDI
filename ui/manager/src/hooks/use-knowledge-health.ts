import { useMemo } from "react";
import { useAgentDescriptors } from "@/hooks/use-agents";
import { useEnrichedGroupDescriptors } from "@/hooks/use-groups";

// ─── Types ───────────────────────────────────────────────────────

export type HealthStatus = "healthy" | "moderate" | "at-risk";

export interface KnowledgeHealthData {
  /** Total number of deployed agents */
  workforceSize: number;
  /** Percentage of agents active in last 30 days (0–100) */
  activeRate: number;
  /** Number of agents NOT active in last 30 days */
  dormantCount: number;
  /** Total number of task forces (groups) */
  taskForceCount: number;
  /** Overall health status based on active rate */
  healthStatus: HealthStatus;
  /** Whether data is still loading */
  isLoading: boolean;
}

// ─── Constants ───────────────────────────────────────────────────

const THIRTY_DAYS_MS = 30 * 24 * 60 * 60 * 1000;

function deriveHealthStatus(activeRate: number): HealthStatus {
  if (activeRate >= 70) return "healthy";
  if (activeRate >= 40) return "moderate";
  return "at-risk";
}

// ─── Hook ────────────────────────────────────────────────────────

/**
 * Computes Knowledge Health metrics from existing API data.
 *
 * - **Workforce Size**: count of deployed agents
 * - **Active Rate**: % of agents that are members of a task force
 *   with recent activity (lastModifiedOn within 30 days)
 * - **Dormant Count**: agents NOT appearing in any recently-active task force
 * - **Task Force Count**: total groups
 * - **Health Status**: green (≥70%), yellow (40–69%), red (<40%)
 */
export function useKnowledgeHealth(): KnowledgeHealthData {
  const { data: agents, isLoading: agentsLoading } = useAgentDescriptors(200);
  const { data: groups, isLoading: groupsLoading } =
    useEnrichedGroupDescriptors(200);

  return useMemo(() => {
    const isLoading = agentsLoading || groupsLoading;

    if (isLoading || !agents || !groups) {
      return {
        workforceSize: 0,
        activeRate: 0,
        dormantCount: 0,
        taskForceCount: 0,
        healthStatus: "moderate" as HealthStatus,
        isLoading: true,
      };
    }

    const workforceSize = agents.length;
    const taskForceCount = groups.length;

    if (workforceSize === 0) {
      return {
        workforceSize: 0,
        activeRate: 0,
        dormantCount: 0,
        taskForceCount,
        healthStatus: "at-risk" as HealthStatus,
        isLoading: false,
      };
    }

    // Find agents that appear in task forces with recent activity
    const now = Date.now();
    const activeAgentIds = new Set<string>();

    for (const group of groups) {
      const lastModified = group.lastModifiedOn ?? group.createdOn;
      const ts = lastModified ? new Date(lastModified).getTime() : 0;
      const isRecent = now - ts < THIRTY_DAYS_MS;

      if (isRecent && group.members) {
        for (const member of group.members) {
          if (member.agentId) {
            activeAgentIds.add(member.agentId);
          }
        }
      }
    }

    const activeCount = agents.filter((a) => {
      // Extract agent ID from resource URI
      const match = a.resource?.match(
        /\/agentstore\/agents\/([^?]+)/,
      );
      const agentId = match?.[1] ?? "";
      return activeAgentIds.has(agentId);
    }).length;

    const activeRate =
      workforceSize > 0 ? Math.round((activeCount / workforceSize) * 100) : 0;
    const dormantCount = workforceSize - activeCount;
    const healthStatus = deriveHealthStatus(activeRate);

    return {
      workforceSize,
      activeRate,
      dormantCount,
      taskForceCount,
      healthStatus,
      isLoading: false,
    };
  }, [agents, groups, agentsLoading, groupsLoading]);
}
