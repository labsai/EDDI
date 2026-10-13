import { useCallback, useMemo } from "react";
import { useAllAgentDescriptors, groupAgentsByName } from "@/hooks/use-agents";

/**
 * Agent id -> display name for every agent the user can see.
 *
 * Admin tables (logs, schedules, secrets...) store bare agent ids, which read
 * as noise. This resolves them to the agent's name and falls back to the id
 * while the list loads or for an agent that no longer exists, so a row never
 * goes blank. Shares the all-pages descriptor query with the import dialogs.
 */
export function useAgentNames() {
  const { data } = useAllAgentDescriptors();
  const names = useMemo(() => {
    const map = new Map<string, string>();
    if (!data) return map;
    for (const agent of groupAgentsByName(data.pages.flat())) {
      if (agent.name) map.set(agent.id, agent.name);
    }
    return map;
  }, [data]);
  const nameOf = useCallback(
    (id: string | null | undefined): string => (id ? (names.get(id) ?? id) : ""),
    [names],
  );
  return { names, nameOf };
}
