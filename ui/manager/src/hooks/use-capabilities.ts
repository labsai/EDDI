import { useQuery } from "@tanstack/react-query";
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
