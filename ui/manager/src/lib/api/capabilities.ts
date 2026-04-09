import { api } from "../api-client";

/* ─── Types ─── */

export interface CapabilityRecord {
  agentId: string;
  skill: string;
  attributes: Record<string, string>;
  confidence: string;
}

/* ─── API Functions ─── */

const BASE = "/capabilities";

/** List all capabilities across agents. */
export async function listCapabilities(): Promise<CapabilityRecord[]> {
  return api.get<CapabilityRecord[]>(BASE);
}

/** List capabilities for a specific agent. */
export async function getAgentCapabilities(
  agentId: string,
): Promise<CapabilityRecord[]> {
  return api.get<CapabilityRecord[]>(
    `${BASE}/agents/${encodeURIComponent(agentId)}`,
  );
}

/** Find agents that have a specific skill. */
export async function findAgentsBySkill(
  skill: string,
): Promise<CapabilityRecord[]> {
  return api.get<CapabilityRecord[]>(
    `${BASE}/search?skill=${encodeURIComponent(skill)}`,
  );
}
