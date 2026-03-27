import { useSearchParams } from "react-router-dom";

export interface AgentContext {
  agentId: string;
  agentVer: number;
}

/**
 * Extract agent cascade context from the current URL search params.
 * Returns null when not navigating within an agent drill-down hierarchy.
 *
 * The agentId/agentVer params are set by:
 * - agent-detail → workflow links (?agentId=X&agentVer=Y)
 * - pipeline-builder → resource links (?pkgId=…&pkgVer=…&agentId=X&agentVer=Y)
 */
export function useAgentContext(): AgentContext | null {
  const [searchParams] = useSearchParams();
  const agentId = searchParams.get("agentId");
  const agentVer = searchParams.get("agentVer");

  if (!agentId || !agentVer) return null;

  const version = parseInt(agentVer, 10);
  if (isNaN(version)) return null;

  return { agentId, agentVer: version };
}
