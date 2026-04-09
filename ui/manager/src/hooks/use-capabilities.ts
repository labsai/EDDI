import { useQuery } from "@tanstack/react-query";
import {
  listCapabilities,
  getAgentCapabilities,
  findAgentsBySkill,
} from "@/lib/api/capabilities";

const capabilityKeys = {
  all: ["capabilities"] as const,
  list: ["capabilities", "list"] as const,
  agent: (agentId: string) => ["capabilities", "agent", agentId] as const,
  skill: (skill: string) => ["capabilities", "skill", skill] as const,
};

/** List all capabilities across agents. */
export function useCapabilities() {
  return useQuery({
    queryKey: capabilityKeys.list,
    queryFn: listCapabilities,
  });
}

/** Capabilities for a specific agent. */
export function useAgentCapabilities(agentId: string) {
  return useQuery({
    queryKey: capabilityKeys.agent(agentId),
    queryFn: () => getAgentCapabilities(agentId),
    enabled: !!agentId,
  });
}

/** Search for agents by skill. */
export function useSkillSearch(skill: string) {
  return useQuery({
    queryKey: capabilityKeys.skill(skill),
    queryFn: () => findAgentsBySkill(skill),
    enabled: !!skill,
  });
}
