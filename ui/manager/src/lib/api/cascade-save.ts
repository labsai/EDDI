import { getErrorMessage } from "@/lib/api-client";
import {
  updateResource,
  type ResourceTypeConfig,
} from "./resources";
import {
  getWorkflow,
  getWorkflowCurrentVersion,
  updateWorkflow,
  type WorkflowConfiguration,
} from "./workflows";
import { getAgent, getAgentCurrentVersion, updateAgent, type Agent } from "./agents";
import { parseVersionFromLocation, requireVersionFromLocation } from "./location-version";

export interface CascadeContext {
  workflowId: string;
  workflowVersion: number;
  agentId: string;
  agentVersion: number;
  /**
   * The workflow version the agent currently references, when that is NOT
   * `workflowVersion`. Only a cascade that stopped after the workflow hop leaves
   * the two apart — the workflow has moved on, the agent still points at the
   * version before — and its {@link CascadePartialResult.retryContext} sets this
   * so the retry repoints the agent instead of refusing it. Omit it otherwise.
   */
  agentWorkflowVersion?: number;
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
 * The versions a cascade had already created when a later hop failed.
 *
 * Every hop is a PUT that bumps a version, and the backend refuses a write to a
 * version that is no longer current. So once the resource hop has succeeded,
 * the version the caller opened the page with is gone: a retry that still
 * addresses it 409s on the resource, and keeps 409ing until the page is
 * reloaded — which throws away the edit the user was trying to save. The caller
 * adopts these versions instead, and the retry picks up where this one stopped.
 */
export interface CascadePartialResult {
  /** Set when the resource hop succeeded in this call. */
  newResourceVersion?: number;
  /** Set when the workflow hop succeeded too, so only the agent hop failed. */
  newWorkflowVersion?: number;
  /** The context a retry must use — set whenever the cascade had one. */
  retryContext?: CascadeContext;
}

/**
 * A cascade hop failed after at least one earlier hop had written a new version.
 *
 * The message is the underlying failure's, so a toast reads exactly as it did
 * before; `partial` carries the versions that now exist, and `cause` the
 * original error for anyone who needs its status.
 */
export class CascadeSaveError extends Error {
  readonly partial: CascadePartialResult;
  readonly cause: unknown;

  constructor(cause: unknown, partial: CascadePartialResult) {
    super(getErrorMessage(cause));
    this.name = "CascadeSaveError";
    this.partial = partial;
    this.cause = cause;
  }
}

/**
 * The parent documents do not reference what the cascade is about to update —
 * detected BEFORE anything was written.
 *
 * The cascade used to find out afterwards, or not at all: the workflow step was
 * replaced on a substring match and the agent's workflow on an exact string
 * match, and neither checked that it had replaced anything. An agent that
 * referenced its workflow in any other spelling (or at another version) was
 * re-saved unchanged — a new agent version WITHOUT the edit, reported as a
 * successful save, which Save & Deploy then deployed.
 */
export class CascadeReferenceError extends Error {
  /**
   * What was wrong, for a translated message (`describeSaveError`). The English
   * `message` stays for logs and for any caller that does not translate.
   */
  readonly code: CascadeReferenceCode;
  readonly params: Record<string, string | number>;

  constructor(code: CascadeReferenceCode, params: Record<string, string | number>, message: string) {
    super(message);
    this.name = "CascadeReferenceError";
    this.code = code;
    this.params = params;
  }
}

export type CascadeReferenceCode =
  | "workflowMissingResource"
  | "agentWorkflowMismatch"
  | "agentMissingWorkflow"
  | "workflowChanged"
  | "agentChanged";

/** The versions a failed cascade left behind, or `null` for any other error. */
export function cascadePartialResult(err: unknown): CascadePartialResult | null {
  return err instanceof CascadeSaveError ? err.partial : null;
}

/**
 * Save a resource config, then cascade version updates up through
 * the workflow → agent chain.
 *
 * The EDDI backend increments version on every PUT, returning the
 * new URI in the Location header. We parse that to update parent references.
 *
 * Both parents are read and checked before the resource is written, so a
 * workflow that does not reference the resource, or an agent that does not
 * reference that workflow version, fails the save without leaving an orphaned
 * resource version behind. A hop that fails after the resource was written
 * throws {@link CascadeSaveError} naming the versions that now exist.
 */
export async function cascadeSaveResource(
  rt: ResourceTypeConfig,
  resourceId: string,
  resourceVersion: number,
  body: unknown,
  context?: CascadeContext,
  options?: CascadeOptions
): Promise<CascadeResult> {
  if (!context) {
    if (options?.skipResourceSave) {
      // Resource was already saved — use the passed version directly
      return { newResourceVersion: resourceVersion };
    }
    const saveResult = await updateResource(rt, resourceId, resourceVersion, body);
    return { newResourceVersion: requireVersionFromLocation(saveResult.location, "resource") };
  }

  // 1. Read and check both parents — nothing has been written yet.
  const parents = await loadParents(rt, resourceId, context);

  // 2. Save the resource config
  let newResourceVersion: number;
  const partial: CascadePartialResult = {};
  if (options?.skipResourceSave) {
    newResourceVersion = resourceVersion;
  } else {
    const saveResult = await updateResource(rt, resourceId, resourceVersion, body);
    newResourceVersion = requireVersionFromLocation(saveResult.location, "resource");
    partial.newResourceVersion = newResourceVersion;
  }

  // 3. + 4. Point the workflow at it, then the agent at the new workflow
  const { newWorkflowVersion, newAgentVersion } = await writeParents(
    rt,
    resourceId,
    newResourceVersion,
    context,
    parents,
    partial,
  );
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
  const parents = await loadParents(rt, resourceId, context);
  return writeParents(rt, resourceId, newVersion, context, parents, {});
}

interface LoadedParents {
  workflow: WorkflowConfiguration;
  agent: Agent;
}

async function loadParents(
  rt: ResourceTypeConfig,
  resourceId: string,
  context: CascadeContext,
): Promise<LoadedParents> {
  const [workflow, agent, workflowCurrent, agentCurrent] = await Promise.all([
    getWorkflow(context.workflowId, context.workflowVersion),
    getAgent(context.agentId, context.agentVersion),
    currentOrUnknown(getWorkflowCurrentVersion(context.workflowId)),
    currentOrUnknown(getAgentCurrentVersion(context.agentId)),
  ]);

  /*
   * A parent this page addresses may have been superseded elsewhere (another
   * tab, another user). Its PUT would 409 — but only AFTER the hops below it had
   * written, leaving an orphaned resource (and workflow) version per attempt,
   * and a retry would do the same again. Refuse before anything is written.
   * Only a current version strictly NEWER than ours counts: an unanswered or
   * unexpected answer is no evidence, and the PUT still guards the rest.
   */
  if (workflowCurrent !== null && workflowCurrent > context.workflowVersion) {
    throw new CascadeReferenceError(
      "workflowChanged",
      { workflowId: context.workflowId, version: context.workflowVersion, current: workflowCurrent },
      `Workflow ${context.workflowId} changed elsewhere (now version ${workflowCurrent}, this page has ` +
        `version ${context.workflowVersion}) — reload it. Nothing was saved.`,
    );
  }
  if (agentCurrent !== null && agentCurrent > context.agentVersion) {
    throw new CascadeReferenceError(
      "agentChanged",
      { agentId: context.agentId, version: context.agentVersion, current: agentCurrent },
      `Agent ${context.agentId} changed elsewhere (now version ${agentCurrent}, this page has ` +
        `version ${context.agentVersion}) — reload it. Nothing was saved.`,
    );
  }

  if (!workflow.workflowSteps.some((step) => referencesResource(step.config?.uri, rt, resourceId))) {
    throw new CascadeReferenceError(
      "workflowMissingResource",
      { workflowId: context.workflowId, version: context.workflowVersion, resource: `${rt.plural}/${resourceId}` },
      `Workflow ${context.workflowId} (version ${context.workflowVersion}) does not reference ` +
        `${rt.plural}/${resourceId}, so saving it here would not change the agent. Nothing was saved.`,
    );
  }

  const expected = agentWorkflowVersion(context);
  const workflowRefs = (agent.workflows ?? [])
    .map(parseRef)
    .filter((ref) => ref?.id === context.workflowId);
  if (!workflowRefs.some((ref) => ref?.version === expected)) {
    const found = workflowRefs
      .map((ref) => (ref?.version != null ? String(ref.version) : "?"))
      .join(", ");
    throw new CascadeReferenceError(
      found ? "agentWorkflowMismatch" : "agentMissingWorkflow",
      {
        agentId: context.agentId,
        version: context.agentVersion,
        workflowId: context.workflowId,
        found,
        expected,
      },
      found
        ? `Agent ${context.agentId} (version ${context.agentVersion}) references workflow ` +
            `${context.workflowId} at version ${found}, not version ${expected}. ` +
            `The agent changed since this page was opened — reload it. Nothing was saved.`
        : `Agent ${context.agentId} (version ${context.agentVersion}) does not reference workflow ` +
            `${context.workflowId}. Nothing was saved.`,
    );
  }

  return { workflow, agent };
}

async function writeParents(
  rt: ResourceTypeConfig,
  resourceId: string,
  newResourceVersion: number,
  context: CascadeContext,
  { workflow, agent }: LoadedParents,
  partial: CascadePartialResult,
): Promise<CascadeVersionResult> {
  const newResourceUri = buildResourceUri(rt, resourceId, newResourceVersion);

  let newWorkflowVersion: number;
  try {
    const wfResult = await updateWorkflow(
      context.workflowId,
      context.workflowVersion,
      replaceResourceUri(workflow, rt, resourceId, newResourceUri),
    );
    newWorkflowVersion = requireVersionFromLocation(wfResult.location, "workflow");
  } catch (err) {
    if (partial.newResourceVersion === undefined) throw err;
    // Nothing above the resource moved, so the retry context is unchanged.
    throw new CascadeSaveError(err, { ...partial, retryContext: context });
  }

  const newWfUri = `eddi://ai.labs.workflow/workflowstore/workflows/${context.workflowId}?version=${newWorkflowVersion}`;
  const updatedAgent: Agent = {
    ...agent,
    workflows: (agent.workflows ?? []).map((uri) => {
      const ref = parseRef(uri);
      return ref?.id === context.workflowId && ref.version === agentWorkflowVersion(context)
        ? newWfUri
        : uri;
    }),
  };

  try {
    const agentResult = await updateAgent(context.agentId, context.agentVersion, updatedAgent);
    const newAgentVersion = requireVersionFromLocation(agentResult.location, "agent");
    return { newWorkflowVersion, newAgentVersion };
  } catch (err) {
    throw new CascadeSaveError(err, {
      ...partial,
      newWorkflowVersion,
      // The workflow moved on; the agent still references what it referenced.
      retryContext: {
        ...context,
        workflowVersion: newWorkflowVersion,
        agentWorkflowVersion: agentWorkflowVersion(context),
      },
    });
  }
}

/**
 * A `/currentversion` answer, or `null` when there is none to trust — a failed
 * read (an older backend, a network blip) must not block a save the PUTs would
 * guard anyway.
 */
async function currentOrUnknown(read: Promise<number>): Promise<number | null> {
  try {
    const value = await read;
    return typeof value === "number" && Number.isSafeInteger(value) ? value : null;
  } catch {
    return null;
  }
}

/** The workflow version the agent is expected to reference right now. */
function agentWorkflowVersion(context: CascadeContext): number {
  return context.agentWorkflowVersion ?? context.workflowVersion;
}

/**
 * The context for the save after a successful cascade: the versions it created,
 * with the agent and workflow back in step.
 */
export function nextCascadeContext(
  context: CascadeContext,
  result: { newWorkflowVersion?: number; newAgentVersion?: number },
): CascadeContext {
  return {
    workflowId: context.workflowId,
    workflowVersion: result.newWorkflowVersion ?? context.workflowVersion,
    agentId: context.agentId,
    agentVersion: result.newAgentVersion ?? context.agentVersion,
  };
}

/**
 * The path segments, id and version of a config URI, in any spelling a stored
 * document carries: `eddi://ext/store/plural/id?version=N`, a relative path, or
 * a legacy store path. `null` when it cannot be parsed at all.
 */
function parseRef(uri: unknown): { segments: string[]; id: string; version: number | null } | null {
  if (typeof uri !== "string" || !uri) return null;
  try {
    const normalised = uri.startsWith("eddi://") ? uri.replace("eddi://", "http://") : uri;
    const segments = new URL(normalised, "http://dummy").pathname.split("/").filter(Boolean);
    const id = segments[segments.length - 1];
    if (!id) return null;
    return { segments, id, version: parseVersionFromLocation(uri) };
  } catch {
    return null;
  }
}

/**
 * Whether a workflow step's URI points at this resource: the path must END in
 * `/{store}/{plural}/{id}`. A substring match also matched every id that merely
 * starts with this one.
 */
function referencesResource(uri: unknown, rt: ResourceTypeConfig, resourceId: string): boolean {
  const ref = parseRef(uri);
  if (!ref) return false;
  const [store, plural, id] = ref.segments.slice(-3);
  return store === rt.store && plural === rt.plural && id === resourceId;
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
 * Replace the URI of every step referencing the resource. Matches by store path
 * and id (not the exact URI), so it works regardless of which version the
 * workflow currently references.
 */
function replaceResourceUri(
  wf: WorkflowConfiguration,
  rt: ResourceTypeConfig,
  resourceId: string,
  newUri: string
): WorkflowConfiguration {
  return {
    ...wf,
    workflowSteps: wf.workflowSteps.map((ext) =>
      referencesResource(ext.config?.uri, rt, resourceId)
        ? { ...ext, config: { ...ext.config, uri: newUri } }
        : ext,
    ),
  };
}
