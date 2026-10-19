import { getWorkflowDescriptors, getWorkflow } from "./workflows";
import { getAgentDescriptors, getAgent, parseResourceUri } from "./agents";
import { mapWithConcurrency } from "@/lib/concurrency";

/** Parallel requests per scan: fast, without exhausting the browser's connection pool. */
const USAGE_FETCH_CONCURRENCY = 8;

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

  // 1. Get all workflows and agents up-front (one API call each)
  const [wfDescriptors, agentDescriptors] = await Promise.all([
    getWorkflowDescriptors(200, 0, ""),
    getAgentDescriptors(200, 0, ""),
  ]);

  // 2. Load the workflows a few at a time instead of one round trip after
  //    another: with 200 workflows the sequential scan took long enough that the
  //    usage dialog appeared seconds after the save it belonged to.
  const needle = `/${resourceStore}/${resourcePlural}/${resourceId}`;
  const referencing = await mapWithConcurrency(wfDescriptors, USAGE_FETCH_CONCURRENCY, async (wfDesc) => {
    const { id, version } = parseResourceUri(wfDesc.resource);
    try {
      const wf = await getWorkflow(id, version);
      return wf.workflowSteps.some((ext) => {
        const uri = ext.config?.uri;
        return typeof uri === "string" && uri.includes(needle);
      });
    } catch {
      return false; // Skip workflows that can't be loaded
    }
  });
  const matchedWorkflows = wfDescriptors.filter((_, i) => referencing[i]);
  if (matchedWorkflows.length === 0) return usages;

  // 3. Load every agent once (not once per matching workflow), then match.
  const agents = await mapWithConcurrency(agentDescriptors, USAGE_FETCH_CONCURRENCY, async (agentDesc) => {
    const { id, version } = parseResourceUri(agentDesc.resource);
    try {
      return await getAgent(id, version);
    } catch {
      return null; // Skip agents that can't be loaded
    }
  });

  for (const wfDesc of matchedWorkflows) {
    const { id: wfId, version: wfVersion } = parseResourceUri(wfDesc.resource);
    agentDescriptors.forEach((agentDesc, i) => {
      if (!agents[i]?.workflows?.some((uri) => uri === wfDesc.resource)) return;
      const { id: agentId, version: agentVersion } = parseResourceUri(agentDesc.resource);
      usages.push({
        workflowId: wfId,
        workflowVersion: wfVersion,
        workflowName: wfDesc.name || wfId,
        agentId,
        agentVersion,
        agentName: agentDesc.name || agentId,
      });
    });
  }

  return usages;
}
