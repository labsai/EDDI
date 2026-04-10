import { api } from "../api-client";

// ─── Types ───

export interface AgentDeployment {
  environment: string;
  agentId: string;
  initialContext?: Record<string, unknown>;
}

export interface AgentTriggerConfiguration {
  intent: string;
  agentDeployments: AgentDeployment[];
}

// ─── API Functions ───

const BASE = "/AgentTriggerStore/agenttriggers";

export async function getAllTriggers(): Promise<AgentTriggerConfiguration[]> {
  return api.get<AgentTriggerConfiguration[]>(BASE);
}

export async function getTrigger(
  intent: string,
): Promise<AgentTriggerConfiguration> {
  return api.get<AgentTriggerConfiguration>(
    `${BASE}/${encodeURIComponent(intent)}`,
  );
}

export async function createTrigger(
  config: AgentTriggerConfiguration,
): Promise<void> {
  return api.post(BASE, config);
}

export async function updateTrigger(
  intent: string,
  config: AgentTriggerConfiguration,
): Promise<void> {
  return api.put(`${BASE}/${encodeURIComponent(intent)}`, config);
}

export async function deleteTrigger(intent: string): Promise<void> {
  return api.delete(`${BASE}/${encodeURIComponent(intent)}`);
}
