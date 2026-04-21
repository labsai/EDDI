import { parseResourceUri } from "./agents";
import {
  updateResource,
  type ResourceTypeConfig,
} from "./resources";
import {
  getWorkflow,
  updateWorkflow,
  type WorkflowConfiguration,
} from "./workflows";
import { getAgent, updateAgent, type Agent } from "./agents";

export interface CascadeContext {
  workflowId: string;
  workflowVersion: number;
  agentId: string;
  agentVersion: number;
}

export interface CascadeResult {
  newResourceVersion: number;
  newWorkflowVersion?: number;
  newAgentVersion?: number;
}

/**
 * Save a resource config, then cascade version updates up through
 * the package → agent chain.
 *
 * The EDDI backend increments version on every PUT, returning the
 * new URI in the Location header. We parse that to update parent references.
 */
export async function cascadeSaveResource(
  rt: ResourceTypeConfig,
  resourceId: string,
  resourceVersion: number,
  body: unknown,
  context?: CascadeContext
): Promise<CascadeResult> {
  // 1. Save the resource config
  const saveResult = await updateResource(rt, resourceId, resourceVersion, body);
  const newResourceVersion = parseVersionFromLocation(saveResult.location);

  if (!context) {
    return { newResourceVersion };
  }

  // 2. Update the parent workflow
  const oldResourceUri = buildResourceUri(rt, resourceId, resourceVersion);
  const newResourceUri = buildResourceUri(rt, resourceId, newResourceVersion);

  const wf = await getWorkflow(context.workflowId, context.workflowVersion);
  const updatedWf = replaceExtensionUri(wf, oldResourceUri, newResourceUri);
  const wfResult = await updateWorkflow(
    context.workflowId,
    context.workflowVersion,
    updatedWf
  );
  const newWorkflowVersion = parseVersionFromLocation(wfResult.location);

  // 3. Update the parent agent
  const oldWfUri = `eddi://ai.labs.workflow/workflowstore/workflows/${context.workflowId}?version=${context.workflowVersion}`;
  const newWfUri = `eddi://ai.labs.workflow/workflowstore/workflows/${context.workflowId}?version=${newWorkflowVersion}`;

  const agent = await getAgent(context.agentId, context.agentVersion);
  const updatedAgent: Agent = {
    ...agent,
    workflows: (agent.workflows ?? []).map((uri) =>
      uri === oldWfUri ? newWfUri : uri
    ),
  };
  const agentResult = await updateAgent(
    context.agentId,
    context.agentVersion,
    updatedAgent
  );
  const newAgentVersion = parseVersionFromLocation(agentResult.location);

  return { newResourceVersion, newWorkflowVersion, newAgentVersion };
}

/** Parse version number from a Location URI like `eddi://…?version=2` */
function parseVersionFromLocation(location: string): number {
  const { version } = parseResourceUri(location);
  return version;
}

/** Build an EDDI resource URI from config type, id, and version */
function buildResourceUri(
  rt: ResourceTypeConfig,
  id: string,
  version: number
): string {
  const baseType = `eddi://ai.labs.${rt.slug}`;
  return `${baseType}/${rt.store}/${rt.plural}/${id}?version=${version}`;
}

/** Replace old extension URI with new one inside a workflow config */
function replaceExtensionUri(
  wf: WorkflowConfiguration,
  oldUri: string,
  newUri: string
): WorkflowConfiguration {
  return {
    ...wf,
    workflowSteps: wf.workflowSteps.map((ext) => {
      const uri = ext.config?.uri;
      if (typeof uri === "string" && uri === oldUri) {
        return { ...ext, config: { ...ext.config, uri: newUri } };
      }
      return ext;
    }),
  };
}
