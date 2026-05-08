import { useQuery, useQueries } from "@tanstack/react-query";
import {
  searchBySkill,
  listSkills,
  type CapabilityMatch,
} from "@/lib/api/capabilities";

export function useSkills() {
  return useQuery<string[], Error>({
    queryKey: ["capabilities", "skills"],
    queryFn: listSkills,
  });
}

export function useCapabilitySearch(skill: string, strategy?: string) {
  return useQuery<CapabilityMatch[], Error>({
    queryKey: ["capabilities", "search", skill, strategy],
    queryFn: () => searchBySkill(skill, strategy),
    enabled: !!skill.trim(),
  });
}

/** Fetch all skills and their agent matches in parallel for the registry overview. */
export function useSkillRegistry() {
  const { data: skills, isLoading: skillsLoading, isError: skillsError, refetch: refetchSkills } = useSkills();

  const matchQueries = useQueries({
    queries: (skills ?? []).map((skill) => ({
      queryKey: ["capabilities", "search", skill, "all"],
      queryFn: () => searchBySkill(skill, "all"),
      enabled: !!skills,
      staleTime: 30_000, // cache for 30s to avoid hammering
    })),
  });

  const registry = (skills ?? []).map((skill, i) => ({
    skill,
    matches: (matchQueries[i]?.data ?? []) as CapabilityMatch[],
    isLoading: matchQueries[i]?.isLoading ?? true,
  }));

  const isLoading = skillsLoading || matchQueries.some((q) => q.isLoading);

  return { registry, isLoading, isError: skillsError, refetchSkills };
}
