import { useLocation } from "react-router-dom";
import { useAgentVersions } from "@/hooks/use-agents";
import { entityFromPath } from "@/lib/route-registry";

/**
 * The display name of the entity the current detail route points at (the agent's
 * name rather than its raw id), or undefined when the route has none or the name
 * has not loaded yet.
 *
 * Reads the same query the agent detail page uses, so on that page it adds an
 * observer rather than a request.
 */
export function useRouteEntityName(): string | undefined {
  const { pathname } = useLocation();
  const entity = entityFromPath(pathname);
  const { data: versions } = useAgentVersions(entity?.kind === "agent" ? entity.id : "");
  return entity?.kind === "agent" ? versions?.[0]?.name || undefined : undefined;
}
