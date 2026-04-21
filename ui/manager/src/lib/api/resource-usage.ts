import { getWorkflowDescriptors, getWorkflow } from "./workflows";
import { getAgentDescriptors, getAgent, parseResourceUri } from "./agents";

export interface ResourceUsage {
  workflowId: string;
  workflowVersion: number;
  workflowName: string;
  agentId: string;
  agentVersion: number;
  agentName: string;
}

/**
 * Find all packages and agents that reference a given resource URI.
 *
 * Scans all packages for extensions whose config.uri contains
 * the resource ID, then scans all agents for references to those packages.
 */
export async function findResourceUsage(
  resourceId: string,
  resourceStore: string,
  resourcePlural: string
): Promise<ResourceUsage[]> {
  const usages: ResourceUsage[] = [];

  // 1. Get all packages
  const pkgDescriptors = await getWorkflowDescriptors(200, 0, "");

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

      // 2. Find agents that reference this package
      const agentDescriptors = await getAgentDescriptors(200, 0, "");

      for (const agentDesc of agentDescriptors) {
        const { id: agentId, version: agentVersion } = parseResourceUri(agentDesc.resource);
        try {
          const agent = await getAgent(agentId, agentVersion);
          const pkgUri = pkgDesc.resource;
          if (agent.workflows?.some((uri) => uri === pkgUri)) {
            usages.push({
              workflowId: pkgId,
              workflowVersion: pkgVersion,
              workflowName: pkgDesc.name || pkgId,
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
      // Skip packages that can't be loaded
    }
  }

  return usages;
}
