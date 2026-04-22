import { getWorkflowDescriptors, getWorkflow } from "./workflows";
import { getAgentDescriptors, getAgent, parseResourceUri } from "./agents";

export interface ResourceUsage {
  workflowId: string;
  packageVersion: number;
  packageName: string;
  agentId: string;
  agentVersion: number;
  agentName: string;
}

/**
 * Find all workflows and agents that reference a given resource URI.
 *
 * Scans all workflows for extensions whose config.uri contains
 * the resource ID, then scans all agents for references to those workflows.
 */
export async function findResourceUsage(
  resourceId: string,
  resourceStore: string,
  resourcePlural: string
): Promise<ResourceUsage[]> {
  const usages: ResourceUsage[] = [];

  // 1. Get all workflows and agents up-front (one API call each)
  const [pkgDescriptors, agentDescriptors] = await Promise.all([
    getWorkflowDescriptors(200, 0, ""),
    getAgentDescriptors(200, 0, ""),
  ]);

  // Cache for agent configs (avoid re-fetching the same agent)
  const agentCache = new Map<string, Awaited<ReturnType<typeof getAgent>>>();

  for (const pkgDesc of pkgDescriptors) {
    const { id: pkgId, version: pkgVersion } = parseResourceUri(pkgDesc.resource);

    try {
      const pkg = await getWorkflow(pkgId, pkgVersion);
      // Check if any extension references this resource
      const hasReference = pkg.workflowSteps.some((ext) => {
        const uri = ext.config?.uri;
        return (
          typeof uri === "string" &&
          uri.includes(`/${resourceStore}/${resourcePlural}/${resourceId}`)
        );
      });

      if (!hasReference) continue;

      // 2. Find agents that reference this workflow
      for (const agentDesc of agentDescriptors) {
        const { id: agentId, version: agentVersion } = parseResourceUri(agentDesc.resource);
        try {
          const cacheKey = `${agentId}@${agentVersion}`;
          let agent = agentCache.get(cacheKey);
          if (!agent) {
            agent = await getAgent(agentId, agentVersion);
            agentCache.set(cacheKey, agent);
          }
          const pkgUri = pkgDesc.resource;
          if (agent.workflows?.some((uri) => uri === pkgUri)) {
            usages.push({
              workflowId: pkgId,
              packageVersion: pkgVersion,
              packageName: pkgDesc.name || pkgId,
              agentId,
              agentVersion,
              agentName: agentDesc.name || agentId,
            });
          }
        } catch {
          // Skip agents that can't be loaded
        }
      }
    } catch {
      // Skip workflows that can't be loaded
    }
  }

  return usages;
}
