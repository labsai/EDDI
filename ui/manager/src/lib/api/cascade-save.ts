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
  packageVersion: number;
  agentId: string;
  agentVersion: number;
}

export interface CascadeResult {
  newResourceVersion: number;
  newWorkflowVersion?: number;
  newAgentVersion?: number;
}

export interface CascadeOptions {
  /**
   * When true, skip the resource save (step 1) — used when the resource
   * was already saved and we only need to cascade version updates to
   * parent workflow and agent.
   */
  skipResourceSave?: boolean;
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
  context?: CascadeContext,
  options?: CascadeOptions
): Promise<CascadeResult> {
  let newResourceVersion: number;

  if (options?.skipResourceSave) {
    // Resource was already saved — use the passed version directly
    newResourceVersion = resourceVersion;
  } else {
    // 1. Save the resource config
    const saveResult = await updateResource(rt, resourceId, resourceVersion, body);
    newResourceVersion = parseVersionFromLocation(saveResult.location);
  }

  if (!context) {
    return { newResourceVersion };
  }

  // 2. Update the parent package
  const newResourceUri = buildResourceUri(rt, resourceId, newResourceVersion);

  const pkg = await getWorkflow(context.workflowId, context.packageVersion);
  const updatedPkg = replaceExtensionUriByResourceId(
    pkg, rt, resourceId, newResourceUri
  );
  const pkgResult = await updateWorkflow(
    context.workflowId,
    context.packageVersion,
    updatedPkg
  );
  const newWorkflowVersion = parseVersionFromLocation(pkgResult.location);

  // 3. Update the parent agent
  const oldPkgUri = `eddi://ai.labs.workflow/workflowstore/workflows/${context.workflowId}?version=${context.packageVersion}`;
  const newPkgUri = `eddi://ai.labs.workflow/workflowstore/workflows/${context.workflowId}?version=${newWorkflowVersion}`;

  const agent = await getAgent(context.agentId, context.agentVersion);
  const updatedAgent: Agent = {
    ...agent,
    workflows: (agent.workflows ?? []).map((uri) =>
      uri === oldPkgUri ? newPkgUri : uri
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

/**
 * Replace extension URI matching a resource ID inside a workflow config.
 * Matches by resource store path pattern (not exact URI) so it works
 * regardless of which version the workflow currently references.
 */
function replaceExtensionUriByResourceId(
  pkg: WorkflowConfiguration,
  rt: ResourceTypeConfig,
  resourceId: string,
  newUri: string
): WorkflowConfiguration {
  const pattern = `/${rt.store}/${rt.plural}/${resourceId}`;
  return {
    ...pkg,
    workflowSteps: pkg.workflowSteps.map((ext) => {
      const uri = ext.config?.uri;
      if (typeof uri === "string" && uri.includes(pattern)) {
        return { ...ext, config: { ...ext.config, uri: newUri } };
      }
      return ext;
    }),
  };
}
