import { api } from "../api-client";

// ─── Types ───

export interface CapabilityMatch {
  agentId: string;
  skill: string;
  confidence: string;
  attributes?: Record<string, string>;
}

// ─── API Functions ───

const BASE = "/capabilities";

export async function searchBySkill(
  skill: string,
  strategy = "highest_confidence",
): Promise<CapabilityMatch[]> {
  return api.get<CapabilityMatch[]>(
    `${BASE}?skill=${encodeURIComponent(skill)}&strategy=${encodeURIComponent(strategy)}`,
  );
}

export async function listSkills(): Promise<string[]> {
  const result = await api.get<string[]>(`${BASE}/skills`);
  return Array.isArray(result) ? result : [];
}
