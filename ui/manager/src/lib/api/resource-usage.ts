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

  // 1. Get all workflows
  const wfDescriptors = await getWorkflowDescriptors(200, 0, "");

  for (const wfDesc of wfDescriptors) {
    const { id: wfId, version: wfVersion } = parseResourceUri(wfDesc.resource);

    try {
      const wf = await getWorkflow(wfId, wfVersion);
      // Check if any extension references this resource
      const hasReference = wf.workflowSteps.some((ext) => {
        const uri = ext.config?.uri;
        return (
          typeof uri === "string" &&
          uri.includes(`/${resourceStore}/${resourcePlural}/${resourceId}`)
        );
      });

      if (!hasReference) continue;

      // 2. Find agents that reference this workflow
      const agentDescriptors = await getAgentDescriptors(200, 0, "");

      for (const agentDesc of agentDescriptors) {
        const { id: agentId, version: agentVersion } = parseResourceUri(agentDesc.resource);
        try {
          const agent = await getAgent(agentId, agentVersion);
          const wfUri = wfDesc.resource;
          if (agent.workflows?.some((uri) => uri === wfUri)) {
            usages.push({
              workflowId: wfId,
              workflowVersion: wfVersion,
              workflowName: wfDesc.name || wfId,
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
