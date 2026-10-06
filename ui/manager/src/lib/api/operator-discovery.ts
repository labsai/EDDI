import { api } from "../api-client";
import {
  getAgent,
  getAgentDescriptors,
  getDeploymentStatus,
  parseResourceUri,
  undeployAgent,
  deleteAgent,
  type Agent,
  type DeploymentFailure,
  type DeploymentStatus,
} from "./agents";
import { getDescriptor } from "./descriptors";
import { hasOperatorMarker, markOperatorDescriptor } from "./operator-marker";
import { getWorkflow } from "./workflows";
import {
  fetchPlatformSelfUrl,
  gateLooksInstalled,
  normalizeBaseUrl,
  reportOperatorGateStatus,
  verifyGateInstalled,
  writeOperatorConfig,
  type OperatorAuthMode,
  type OperatorConfig,
} from "./operator";
import { defaultOperatorPromptBody } from "@/lib/operator/system-prompt";
import { extractVaultKeyName } from "@/lib/operator/vault-ref";
import type { OperatorScope } from "@/lib/operator/tool-scopes";

/* ─── Discovery ─── */

/** The listing filter. Case-sensitive substring of name OR description on the backend. */
const DISCOVERY_FILTER = "perator";
/** How many descriptors the one listing reads. */
const DISCOVERY_LIMIT = 100;
/** How many marker-less candidates get their configuration read — the expensive half. */
const HEURISTIC_READ_LIMIT = 10;

/** An operator agent on this deployment that the operator config does not point at. */
export interface DiscoveredOperator {
  agentId: string;
  version: number;
  name: string;
  /** `marker`: the descriptor says so. `heuristic`: an approval gate plus tools aimed at this instance. */
  recognisedBy: "marker" | "heuristic";
  status: DeploymentStatus["status"];
  failure?: DeploymentFailure;
}

/**
 * Operator agents the Manager did not register — set up through the setup API
 * or MCP, imported, kept by an activation whose grant was refused, or simply
 * orphaned when the `platform.operator` variable was deleted. The operator
 * screen used to decide "is there an operator" from that variable alone, so
 * every one of them was invisible and unmanageable.
 *
 * The recognition rule, cheapest first:
 * 1. ONE descriptor listing, `filter=perator` — name or description.
 * 2. A descriptor whose description carries `OPERATOR_DESCRIPTOR_MARKER`
 *    is an operator. Every operator provisioned from this Manager on carries it.
 * 3. Otherwise only a name containing "operator" makes it a candidate, and only
 *    then is its configuration read: it counts when its stored approval gate
 *    passes `gateLooksInstalled` AND one of its HTTP-call toolsets targets this
 *    instance. That is what an older operator (pre-marker) looks like, and what
 *    no ordinary agent that happens to be called "operator" does.
 *
 * The agent the config points at is excluded — it is the registered one.
 */
export async function findUnregisteredOperators(
  config: OperatorConfig | null | undefined,
): Promise<DiscoveredOperator[]> {
  const descriptors = await getAgentDescriptors(DISCOVERY_LIMIT, 0, DISCOVERY_FILTER);
  const environment = config?.environment ?? "production";
  const seen = new Set<string>();
  const found: Omit<DiscoveredOperator, "status" | "failure">[] = [];
  let heuristicReads = 0;
  let selfUrls: Set<string> | null = null;

  for (const descriptor of descriptors) {
    const { id, version } = parseResourceUri(descriptor.resource);
    if (!id || seen.has(id) || id === config?.agentId) continue;
    seen.add(id);
    if (hasOperatorMarker(descriptor.description)) {
      found.push({ agentId: id, version, name: descriptor.name, recognisedBy: "marker" });
      continue;
    }
    if (!/operator/i.test(descriptor.name ?? "") || heuristicReads >= HEURISTIC_READ_LIMIT) continue;
    heuristicReads += 1;
    try {
      const agent = await getAgent(id, version);
      if (!gateLooksInstalled(agent).ok) continue;
      selfUrls ??= await selfAddresses(config);
      if (await targetsThisInstance(agent, selfUrls)) {
        found.push({ agentId: id, version, name: descriptor.name, recognisedBy: "heuristic" });
      }
    } catch {
      // Unreadable: not provably an operator, so not listed.
    }
  }

  return Promise.all(
    found.map(async (candidate) => {
      try {
        const status = await getDeploymentStatus(environment, candidate.agentId, candidate.version, { detailed: true });
        return { ...candidate, status: status.status, failure: status.failure };
      } catch {
        return { ...candidate, status: "NOT_FOUND" as const };
      }
    }),
  );
}

/** Every address this instance answers on that an operator's tools could have been pointed at. */
async function selfAddresses(config: OperatorConfig | null | undefined): Promise<Set<string>> {
  const urls = new Set<string>();
  const add = (value: string | null | undefined) => {
    const normalised = normalizeBaseUrl(value).toLowerCase();
    if (!normalised) return;
    urls.add(normalised);
    // Loopback is loopback whichever way it is spelled.
    for (const [a, b] of [["localhost", "127.0.0.1"], ["127.0.0.1", "localhost"]] as const) {
      if (normalised.includes(`//${a}`)) urls.add(normalised.replace(`//${a}`, `//${b}`));
    }
  };
  add(config?.apiBaseUrl);
  try {
    add((await fetchPlatformSelfUrl())?.baseUrl);
  } catch {
    // The listing still works with the addresses we have.
  }
  if (typeof window !== "undefined") add(window.location.origin);
  return urls;
}

interface ApiCallsDocument {
  targetServerUrl?: string;
  httpCalls?: { request?: { method?: string; headers?: Record<string, string> } }[];
}

interface LlmDocument {
  tasks?: { type?: string; parameters?: Record<string, string> }[];
}

/** The resource URIs of one kind of step across the agent's workflows. */
async function stepUris(agent: Agent, kind: "httpcalls" | "llm"): Promise<string[]> {
  const uris: string[] = [];
  for (const workflowUri of agent.workflows ?? []) {
    const { id, version } = parseResourceUri(workflowUri);
    const workflow = await getWorkflow(id, version);
    for (const step of workflow.workflowSteps ?? []) {
      if (!String(step.type ?? "").includes(`ai.labs.${kind}`)) continue;
      const uri = (step.config as { uri?: unknown } | undefined)?.uri;
      if (typeof uri === "string") uris.push(uri);
    }
  }
  return uris;
}

async function readApiCalls(uri: string): Promise<ApiCallsDocument> {
  const { id, version } = parseResourceUri(uri);
  return api.get<ApiCallsDocument>(`/apicallstore/apicalls/${encodeURIComponent(id)}?version=${version}`);
}

async function targetsThisInstance(agent: Agent, selfUrls: Set<string>): Promise<boolean> {
  for (const uri of await stepUris(agent, "httpcalls")) {
    const doc = await readApiCalls(uri);
    if (selfUrls.has(normalizeBaseUrl(doc.targetServerUrl).toLowerCase())) return true;
  }
  return false;
}

/* ─── Adopt ─── */

/**
 * Register an existing operator agent as THE operator: rebuild the config from
 * what the agent's own documents say, verify its gate, and write
 * `platform.operator`.
 *
 * Read back "as best it can": provider, model, credential key and model-server
 * address from the LLM config; base URL, scope and auth mode from the HTTP-call
 * toolsets. The prompt body cannot be split from the safety preamble it was
 * built with, so the default body for the scope is recorded and no
 * provisioning revision — which makes the operator screen offer an upgrade,
 * the one path that rebuilds every Manager-derived part.
 *
 * A write-capable agent whose gate does not verify is refused: adopting it would
 * put write tools behind an unverified gate on the operator screen.
 */
export async function adoptOperatorAgent(
  candidate: Pick<DiscoveredOperator, "agentId" | "version" | "status">,
  environment = "production",
): Promise<OperatorConfig> {
  const agent = await getAgent(candidate.agentId, candidate.version);

  let provider = "";
  let model = "";
  let credentialKey: string | null = null;
  let llmBaseUrl: string | null = null;
  for (const uri of await stepUris(agent, "llm")) {
    const { id, version } = parseResourceUri(uri);
    const doc = await api.get<LlmDocument>(`/llmstore/llms/${encodeURIComponent(id)}?version=${version}`);
    const task = doc.tasks?.[0];
    if (!task) continue;
    const p = task.parameters ?? {};
    provider = task.type ?? "";
    model = p.model ?? p.modelName ?? p.modelId ?? p.deploymentName ?? "";
    const key = p.apiKey ?? p.authToken ?? p.accessToken;
    credentialKey = key ? extractVaultKeyName(key) : null;
    llmBaseUrl = p.baseUrl ?? p.endpoint ?? null;
    break;
  }

  let apiBaseUrl: string | null = null;
  let writes = false;
  let authMode: OperatorAuthMode = "none";
  for (const uri of await stepUris(agent, "httpcalls")) {
    const doc = await readApiCalls(uri);
    apiBaseUrl ??= normalizeBaseUrl(doc.targetServerUrl) || null;
    for (const call of doc.httpCalls ?? []) {
      const method = (call.request?.method ?? "get").toLowerCase();
      if (method !== "get") writes = true;
      const auth = Object.entries(call.request?.headers ?? {}).find(([k]) => k.toLowerCase() === "authorization")?.[1];
      if (auth?.includes("${caller:token}")) authMode = "caller-identity";
    }
  }
  const scope: OperatorScope = writes ? "read_write" : "read_only";

  const gate = await verifyGateInstalled(candidate.agentId);
  await reportOperatorGateStatus(gate.verified);
  if (scope === "read_write" && !gate.verified) {
    throw new Error(
      `This agent has write tools but its approval gate could not be verified${gate.reason ? ` (${gate.reason})` : ""}, so it cannot be adopted as the operator. Remove it, and activate the operator from this screen instead.`,
    );
  }

  const next: OperatorConfig = {
    enabled: candidate.status === "READY",
    agentId: candidate.agentId,
    version: candidate.version,
    environment,
    provider,
    model,
    credentialKey,
    apiBaseUrl,
    llmBaseUrl,
    scope,
    authMode,
    promptBody: defaultOperatorPromptBody(scope),
  };
  await writeOperatorConfig(next);
  // Adopted from now on — mark it, so the next listing recognises it cheaply.
  try {
    const descriptor = await getDescriptor(candidate.agentId, candidate.version);
    if (!hasOperatorMarker(descriptor.description)) await markOperatorDescriptor(candidate.agentId, candidate.version);
  } catch {
    // Best-effort, as at provisioning.
  }
  return next;
}

/* ─── Remove ─── */

/** Undeploy (ending its conversations) and permanently delete an operator agent the config does not own. */
export async function removeOperatorAgent(
  candidate: Pick<DiscoveredOperator, "agentId" | "version">,
  environment = "production",
): Promise<void> {
  try {
    await undeployAgent(environment, candidate.agentId, candidate.version, { endAllActiveConversations: true });
  } catch {
    // Not deployed (the usual case for a refused one) — the delete is what matters.
  }
  await deleteAgent(candidate.agentId, candidate.version, { cascade: true, permanent: true });
}
