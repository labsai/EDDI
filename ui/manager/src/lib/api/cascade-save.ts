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
 * the workflow → agent chain.
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
    newResourceVersion = requireVersionFromLocation(saveResult.location, "resource");
  }

  if (!context) {
    return { newResourceVersion };
  }

  // 2. Update the parent workflow
  const newResourceUri = buildResourceUri(rt, resourceId, newResourceVersion);

  const wf = await getWorkflow(context.workflowId, context.workflowVersion);
  const updatedWf = replaceExtensionUriByResourceId(
    wf, rt, resourceId, newResourceUri
  );
  const wfResult = await updateWorkflow(
    context.workflowId,
    context.workflowVersion,
    updatedWf
  );
  const newWorkflowVersion = requireVersionFromLocation(wfResult.location, "workflow");

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
  const newAgentVersion = requireVersionFromLocation(agentResult.location, "agent");

  return { newResourceVersion, newWorkflowVersion, newAgentVersion };
}

export interface CascadeVersionResult {
  newWorkflowVersion: number;
  newAgentVersion: number;
}

/**
 * Propagate an already-saved resource version change up through the
 * workflow → agent chain WITHOUT re-saving the resource itself.
 *
 * Use this when the resource was already saved (Path B: standalone editor)
 * and you just need to update parent references to point to the new version.
 */
export async function cascadeVersionUpdate(
  rt: ResourceTypeConfig,
  resourceId: string,
  _previousVersion: number,
  newVersion: number,
  context: CascadeContext
): Promise<CascadeVersionResult> {
  // 1. Update the parent workflow
  const newResourceUri = buildResourceUri(rt, resourceId, newVersion);

  const wf = await getWorkflow(context.workflowId, context.workflowVersion);
  const updatedWf = replaceExtensionUriByResourceId(wf, rt, resourceId, newResourceUri);
  const wfResult = await updateWorkflow(
    context.workflowId,
    context.workflowVersion,
    updatedWf
  );
  const newWorkflowVersion = requireVersionFromLocation(wfResult.location, "workflow");

  // 2. Update the parent agent
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
  const newAgentVersion = requireVersionFromLocation(agentResult.location, "agent");

  return { newWorkflowVersion, newAgentVersion };
}

/**
 * The version in a Location URI like `eddi://…?version=2`, or `null` when there
 * is none to read.
 *
 * Deliberately NOT `parseResourceUri`, which ends with
 * `parseInt(url.searchParams.get("version") || "1", 10)` and so cannot tell
 * "version 1" from "no version at all". Forty-odd call sites rely on that
 * forgiving behaviour, so it stays as it is; the cascade needs the strict
 * reading and gets its own.
 */
function parseVersionFromLocation(location: string | undefined | null): number | null {
  if (!location) {
    return null;
  }
  let raw: string | null;
  try {
    /*
     * Normalised exactly as `parseResourceUri` does it, and for the same two
     * reasons: `eddi://` is not a special scheme, and a Location header may be
     * a relative path with no origin at all — `new URL(location)` on its own
     * throws on the second, which would turn every relative Location into a
     * failed save.
     */
    const normalised = location.startsWith("eddi://")
      ? location.replace("eddi://", "http://")
      : location;
    raw = new URL(normalised, "http://dummy").searchParams.get("version");
  } catch {
    return null;
  }
  /*
   * The WHOLE value must be digits. A prefix match accepted `version=2.5` and
   * `version=2abc` as 2, so the cascade would have written a version into the
   * parent that the server never reported — the same class of silent wrong
   * reference this strict parser exists to prevent, one layer in.
   */
  if (raw === null || !/^\d+$/.test(raw)) {
    return null;
  }
  const version = Number(raw);
  return Number.isSafeInteger(version) ? version : null;
}

/**
 * The version a save reported, or a thrown error naming what could not be read.
 *
 * A cascade writes the version it just created into the PARENT document: the
 * resource version goes into the workflow, the workflow version into the agent.
 * Guessing wrong is not a display bug, it is a wrong reference written to the
 * database — and with the forgiving parser a missing `Location` header resolved
 * to version 1, so a save that lost its header would have quietly pointed the
 * parent at the very first revision while reporting success.
 *
 * Failing the save is the right answer: the user sees that it did not work and
 * retries, instead of finding out later that their agent runs an old config.
 */
function requireVersionFromLocation(location: string | undefined | null, what: string): number {
  const version = parseVersionFromLocation(location);
  if (version === null) {
    throw new Error(
      `The ${what} was saved but the server did not report its new version, so the parent ` +
        `reference cannot be updated safely. Nothing further was written — please retry.`,
    );
  }
  return version;
}

/** Build an EDDI resource URI from config type, id, and version */
function buildResourceUri(
  rt: ResourceTypeConfig,
  id: string,
  version: number
): string {
  return `eddi://${rt.extension}/${rt.store}/${rt.plural}/${id}?version=${version}`;
}

/**
 * Replace extension URI matching a resource ID inside a workflow config.
 * Matches by resource store path pattern (not exact URI) so it works
 * regardless of which version the workflow currently references.
 */
function replaceExtensionUriByResourceId(
  wf: WorkflowConfiguration,
  rt: ResourceTypeConfig,
  resourceId: string,
  newUri: string
): WorkflowConfiguration {
  const pattern = `/${rt.store}/${rt.plural}/${resourceId}`;
  return {
    ...wf,
    workflowSteps: wf.workflowSteps.map((ext) => {
      const uri = ext.config?.uri;
      if (typeof uri === "string" && uri.includes(pattern)) {
        return { ...ext, config: { ...ext.config, uri: newUri } };
      }
      return ext;
    }),
  };
}
