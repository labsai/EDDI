import { api } from "../api-client";
import { createApiAgent, type SetupResult } from "./agent-setup";
import {
  getVariable,
  upsertVariable,
  deleteVariable,
  type GlobalVariable,
} from "./variables";
import {
  deleteAgent,
  undeployAgent,
  deployAgent,
  getDeploymentStatus,
  getAgent,
  type Agent,
} from "./agents";
import { startConversation, sendMessageStreaming, endConversation } from "./chat";
import {
  buildEndpointFilter,
  buildToolApprovals,
  parseEndpoint,
  type OperatorScope,
} from "@/lib/operator/tool-scopes";
import { buildOperatorSystemPrompt } from "@/lib/operator/system-prompt";

/* ─── Config model ─── */

/**
 * How the operator authenticates its tool calls back to EDDI's admin API.
 *
 * `none` — no `Authorization` header is set on the generated tools. Works only
 * where OIDC is disabled; on a Keycloak-protected deployment every tool call
 * returns 401.
 *
 * `caller-identity` — the tools send a `${caller:token}` reference, which
 * EDDI's CallerIdentityResolver replaces at call time with the bearer of
 * whoever is chatting. Tool calls run with the caller's real permissions and
 * audit identity, and the token never reaches conversation memory: the backend
 * releases it only for a same-origin call, only into a header, and scrubs
 * authorization headers before persisting the request.
 *
 * Requires EDDI 6.2.0+. An older backend has no `${caller:...}` resolver and
 * would send the placeholder verbatim.
 */
export type OperatorAuthMode = "none" | "caller-identity";

/**
 * The `apiAuth` value EDDI resolves per call.
 *
 * `McpApiToolBuilder` writes it verbatim into the generated tools'
 * `Authorization` header; `CallerIdentityResolver` substitutes the real token.
 */
export const CALLER_TOKEN_API_AUTH = "Bearer ${caller:token}";

export interface OperatorConfig {
  enabled: boolean;
  agentId: string | null;
  /** Resolved after provisioning; required by deployment-status and undeploy. */
  version: number | null;
  environment: string;
  provider: string;
  model: string;
  /** Vault key *name* of the LLM credential — never the secret itself. */
  credentialKey: string | null;
  scope: OperatorScope;
  authMode: OperatorAuthMode;
  /** Editable half of the system prompt; the safety preamble is prepended. */
  promptBody: string;
}

/**
 * The single global variable holding the operator config.
 *
 * One JSON blob rather than a field-per-variable: activation writes several
 * values that must land together, and the variable store has no transaction.
 */
export const OPERATOR_VARIABLE_KEY = "platform.operator";

export function defaultOperatorConfig(promptBody: string): OperatorConfig {
  return {
    enabled: false,
    agentId: null,
    version: null,
    environment: "production",
    provider: "anthropic",
    model: "claude-sonnet-4-6",
    credentialKey: null,
    scope: "read_only",
    authMode: "none",
    promptBody,
  };
}

/* ─── Config persistence ─── */

/**
 * Read the operator config.
 *
 * Returns `null` when the variable does not exist (the operator has never been
 * activated) or when its value is not parseable — a corrupt blob is treated as
 * "not configured" so the UI offers activation instead of erroring out.
 */
export async function readOperatorConfig(): Promise<OperatorConfig | null> {
  let variable: GlobalVariable;
  try {
    variable = await getVariable(OPERATOR_VARIABLE_KEY);
  } catch (error) {
    if (isNotFound(error)) return null;
    throw error;
  }
  if (!variable?.value) return null;
  try {
    const parsed: unknown = JSON.parse(variable.value);
    // `JSON.parse` also succeeds for `null`, a number or a bare string. Casting
    // one of those to OperatorConfig would surface later as undefined property
    // reads rather than the intended "not configured".
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
      return null;
    }
    return parsed as OperatorConfig;
  } catch {
    return null;
  }
}

export async function writeOperatorConfig(config: OperatorConfig): Promise<void> {
  await upsertVariable(OPERATOR_VARIABLE_KEY, {
    key: OPERATOR_VARIABLE_KEY,
    value: JSON.stringify(config),
    description: "Platform Operator configuration (managed by the manager UI)",
    // Holds no secrets — only pointers and the vault key *name* — but it is
    // deployment-specific, so it must not travel in agent exports.
    exportable: false,
  });
}

export async function clearOperatorConfig(): Promise<void> {
  await deleteVariable(OPERATOR_VARIABLE_KEY);
}

function isNotFound(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "status" in error &&
    (error as { status?: number }).status === 404
  );
}

/* ─── OpenAPI spec ─── */

/** Minimal shape we need from the spec: the paths map. */
export interface FetchedSpec {
  raw: unknown;
  paths: Record<string, Record<string, unknown>>;
}

/**
 * Fetch EDDI's own OpenAPI spec.
 *
 * The full spec is passed to `setup-api` untrimmed — the backend accepts it and
 * scopes tool generation with the `endpoints` filter, so trimming would only
 * risk dropping the `$ref` targets the retained paths depend on.
 */
export async function fetchOpenApiSpec(): Promise<FetchedSpec> {
  const raw = await api.get<unknown>("/openapi?format=json");
  const paths =
    typeof raw === "object" && raw !== null && "paths" in raw
      ? ((raw as { paths?: Record<string, Record<string, unknown>> }).paths ?? {})
      : {};
  return { raw, paths };
}

/**
 * Check the allow-list against a fetched spec.
 *
 * Endpoints the filter names but the spec does not contain produce no tool at
 * all — silently, which would leave the operator unable to answer questions it
 * advertises. Validating against the *fetched* spec catches drift on the actual
 * deployment, which a check against a committed snapshot cannot.
 *
 * Returns the entries that are missing; empty means the allow-list is fully
 * satisfied.
 */
export function findMissingEndpoints(
  spec: FetchedSpec,
  endpoints: readonly string[],
): string[] {
  return endpoints.filter((entry) => {
    const parsed = parseEndpoint(entry);
    if (!parsed) return true;
    const pathItem = spec.paths[parsed.path];
    return !pathItem || !(parsed.method.toLowerCase() in pathItem);
  });
}

/* ─── Provisioning ─── */

export interface ProvisionOperatorParams {
  agentName: string;
  config: OperatorConfig;
  /** Vault reference or plain key for the LLM. Not the EDDI credential. */
  apiKey: string;
  /**
   * Base URL of the LLM provider itself, for local models (Ollama, Jlama).
   *
   * Distinct from `apiBaseUrl`, which is the target server of the *generated
   * tools*. Sending this as `apiBaseUrl` pointed every operator tool at the
   * local model server instead of at EDDI.
   */
  baseUrl?: string;
  /**
   * The spec to send, already fetched by the caller.
   *
   * Required rather than fetched here so activation validates and sends the
   * *same* document — and so the 400+ KB spec is pulled once, not twice.
   */
  spec: FetchedSpec;
}

/**
 * Create and deploy the operator agent.
 *
 * `apiBaseUrl` is the manager's own origin: the generated tools call the same
 * EDDI instance the manager is talking to.
 */
export async function provisionOperator(
  params: ProvisionOperatorParams,
): Promise<SetupResult> {
  const { agentName, config, apiKey, baseUrl, spec } = params;

  return createApiAgent({
    agentName,
    systemPrompt: buildOperatorSystemPrompt(config.promptBody),
    openApiSpec: JSON.stringify(spec.raw),
    provider: config.provider,
    model: config.model,
    apiKey,
    // Always this deployment: the generated tools call the EDDI instance the
    // manager is talking to. Never the LLM's base URL.
    apiBaseUrl: currentOrigin(),
    llmBaseUrl: baseUrl || undefined,
    apiAuth: apiAuthForMode(config.authMode),
    endpoints: buildEndpointFilter(config.scope),
    deploy: true,
    environment: config.environment,
    // Sent unconditionally — including for read_only. See buildToolApprovals:
    // installing the real gate now, on v1, is what verifyGateInstalled proves
    // and what read_write reuses unchanged later. hitlConfig.timeoutPolicy is
    // left unset deliberately: the per-tool toolApprovals.timeoutPolicy already
    // pins WAIT_INDEFINITELY, and Task 10 on the backend demotes an *inherited*
    // AUTO_APPROVE to WAIT_INDEFINITELY for tool pauses anyway — setting it here
    // too would only be redundant, not safer.
    hitlConfig: { toolApprovals: buildToolApprovals() },
  });
}

/**
 * Reject a provisioning result that did not actually produce a live operator.
 *
 * `setup-api` answers 201 even when the deploy step failed, and falls back to
 * the literal id `"unknown"` when it cannot read the created agent's location.
 * Persisting either as `enabled: true` would leave the UI claiming a running
 * operator whose status and undeploy calls address a nonexistent agent.
 */
export function assertProvisioned(result: SetupResult): void {
  if (!result.agentId || result.agentId === "unknown") {
    throw new Error(
      "EDDI created the operator but did not return its agent id, so it cannot be managed. Check the platform logs and try again.",
    );
  }
  if (result.deployed === false || result.deploymentStatus === "ERROR") {
    throw new Error(
      `The operator agent was created but failed to deploy (status: ${result.deploymentStatus ?? "unknown"}).`,
    );
  }
}

/** The `apiAuth` value for an auth mode. `none` sends no header at all. */
export function apiAuthForMode(mode: OperatorAuthMode): string | undefined {
  return mode === "caller-identity" ? CALLER_TOKEN_API_AUTH : undefined;
}

function currentOrigin(): string {
  return typeof window !== "undefined" ? window.location.origin : "";
}

/**
 * Resolve the version of the agent that was just provisioned.
 *
 * `setup-api`'s response body has no version field, but the created resource
 * locations do (`…/agents/{id}?version=N`). Fall back to the currentversion
 * endpoint when the location is absent or unparseable.
 */
export async function resolveAgentVersion(
  result: SetupResult,
): Promise<number> {
  const location = (result.resources as { agentLocation?: unknown } | undefined)
    ?.agentLocation;
  if (typeof location === "string") {
    const parsed = parseVersionFromLocation(location);
    if (parsed != null) return parsed;
  }
  const current = await api.get<number>(
    `/agentstore/agents/${result.agentId}/currentversion`,
  );
  return current ?? 1;
}

export function parseVersionFromLocation(location: string): number | null {
  const match = /[?&]version=(\d+)/.exec(location);
  if (!match) return null;
  const value = Number(match[1]);
  return Number.isFinite(value) && value > 0 ? value : null;
}

/* ─── Gate verification ─── */

/** Write patterns `buildToolApprovals` gates. An `exempt` entry equal to any of
 *  these — or broad enough to subsume one — would exempt a write outright. */
const GATED_WRITE_PATTERNS = ["http.post:*", "http.put:*", "http.patch:*", "http.delete:*"] as const;

/** `exempt` patterns broad enough to swallow a gated write pattern above. */
const OVERBROAD_EXEMPT_PATTERNS = ["*", "http.*", "http.*:*", ...GATED_WRITE_PATTERNS] as const;

export interface GateVerificationResult {
  verified: boolean;
  /** Human-readable cause of the first failure found; undefined when verified. */
  reason?: string;
  /** Agent versions actually inspected, 1..currentVersion. */
  checkedVersions: number[];
}

/**
 * Judges a single fetched agent document against what `buildToolApprovals`
 * installs. Exported for direct unit testing without a network round trip.
 */
export function gateLooksInstalled(agent: Agent): { ok: boolean; reason?: string } {
  const hitl = agent.hitlConfig;
  if (!hitl) return { ok: false, reason: "hitlConfig is absent" };
  if (hitl.timeoutPolicy === "AUTO_APPROVE") {
    return { ok: false, reason: "hitlConfig.timeoutPolicy is AUTO_APPROVE" };
  }
  const toolApprovals = hitl.toolApprovals;
  if (!toolApprovals) return { ok: false, reason: "hitlConfig.toolApprovals is absent" };
  if (!toolApprovals.requireApproval || toolApprovals.requireApproval.length === 0) {
    return { ok: false, reason: "toolApprovals.requireApproval is empty — the gate is inactive" };
  }
  if (toolApprovals.timeoutPolicy === "AUTO_APPROVE") {
    return { ok: false, reason: "toolApprovals.timeoutPolicy is AUTO_APPROVE" };
  }
  const exempt = toolApprovals.exempt ?? [];
  const overbroadExempt = exempt.find((pattern) =>
    (OVERBROAD_EXEMPT_PATTERNS as readonly string[]).includes(pattern),
  );
  if (overbroadExempt) {
    return { ok: false, reason: `exempt pattern '${overbroadExempt}' would exempt a gated write` };
  }
  return { ok: true };
}

/**
 * Reads EVERY version of the agent document back and refuses unless the gate
 * is verifiably installed and sane on each one.
 *
 * Checking only the currently-deployed version is not enough: version skew is
 * real (a newer Manager against an older backend can have `hitlConfig` silently
 * dropped from the request it sent, or an older, ungated version can still be
 * reachable by a future redeploy), and the only defence is reading the actual
 * stored documents back rather than trusting what was requested or what is
 * currently live.
 *
 * `agentId` alone, not a config snapshot — the caller must not be able to
 * short-circuit this with cached state.
 */
export async function verifyGateInstalled(agentId: string): Promise<GateVerificationResult> {
  let currentVersion: number;
  try {
    currentVersion = (await api.get<number>(`/agentstore/agents/${agentId}/currentversion`)) ?? 0;
  } catch (error) {
    return {
      verified: false,
      reason: `could not resolve the current version: ${error instanceof Error ? error.message : String(error)}`,
      checkedVersions: [],
    };
  }
  if (currentVersion < 1) {
    return { verified: false, reason: "no version of this agent could be resolved", checkedVersions: [] };
  }

  const versions = Array.from({ length: currentVersion }, (_, i) => i + 1);
  const checkedVersions: number[] = [];
  for (const version of versions) {
    let agent: Agent;
    try {
      agent = await getAgent(agentId, version);
    } catch (error) {
      return {
        verified: false,
        reason: `version ${version} could not be read back: ${error instanceof Error ? error.message : String(error)}`,
        checkedVersions,
      };
    }
    checkedVersions.push(version);
    const judged = gateLooksInstalled(agent);
    if (!judged.ok) {
      return { verified: false, reason: `version ${version}: ${judged.reason}`, checkedVersions };
    }
  }
  return { verified: true, checkedVersions };
}

/* ─── Canary ─── */

/** Outcome of a single probe turn run through the deployed operator. */
export interface CanaryResult {
  ok: boolean;
  /** How many tools the operator actually invoked. */
  toolCalls: number;
  /** Populated when the probe failed; safe to show to an admin. */
  error?: string;
}

/** The probe message. Phrased to force exactly one cheap read. */
/** How long a probe read may take before it is treated as a failure. */
export const CANARY_TIMEOUT_MS = 60_000;

export const CANARY_PROMPT =
  "List the agents on this platform. Use your tools; do not guess.";

/**
 * Run one read through the operator and report whether it could reach the
 * platform.
 *
 * A `READY` deployment badge only means the agent config loaded — it says
 * nothing about whether the generated tools can authenticate. Since EDDI does
 * not forward the caller's identity on its own, a misconfigured `authMode`
 * produces exactly that shape of failure: deployed, responsive, and unable to
 * read anything. This probe is the only thing that distinguishes the two.
 *
 * A tool call that runs and errors still counts as a failure, because the
 * model will happily narrate an apology instead of surfacing the 401.
 */
export async function runOperatorCanary(
  config: OperatorConfig,
  signal?: AbortSignal,
): Promise<CanaryResult> {
  if (!config.agentId) {
    return { ok: false, toolCalls: 0, error: "No operator agent is configured." };
  }

  // A stalled stream would otherwise leave activation spinning with no way out
  // but a page reload.
  const timeout = new AbortController();
  const timer = setTimeout(() => timeout.abort(), CANARY_TIMEOUT_MS);
  const effectiveSignal = signal ?? timeout.signal;

  let conversationId = null;
  try {
    conversationId = await startConversation(config.environment, config.agentId);
    let toolCalls = 0;
    let toolError: string | undefined;
    let streamError: string | undefined;

    const stream = sendMessageStreaming(
      config.environment,
      config.agentId,
      conversationId,
      { input: CANARY_PROMPT },
      effectiveSignal,
    );

    for await (const event of stream) {
      if (event.type === "error") {
        streamError = event.data || "The operator returned an error.";
        continue;
      }
      if (event.type === "done") break;
      if (event.type !== "task_complete") continue;

      try {
        const parsed = JSON.parse(event.data) as {
          toolTrace?: { type: string; result?: string }[];
        };
        for (const entry of parsed.toolTrace ?? []) {
          if (entry.type === "tool_call") toolCalls += 1;
          if (entry.type === "tool_result" && looksLikeAuthFailure(entry.result)) {
            toolError =
              "The operator's tools were rejected by EDDI (unauthorized). Its authentication mode cannot reach this deployment.";
          }
        }
      } catch {
        // Non-JSON task payload — no trace to inspect.
      }
    }

    if (streamError) return { ok: false, toolCalls, error: streamError };
    if (toolError) return { ok: false, toolCalls, error: toolError };
    if (toolCalls === 0) {
      return {
        ok: false,
        toolCalls: 0,
        error:
          "The operator answered without calling any tool, so it could not actually read this platform.",
      };
    }
    return { ok: true, toolCalls };
  } catch (error) {
    return {
      ok: false,
      toolCalls: 0,
      error: timeout.signal.aborted
        ? "The connection check timed out."
        : error instanceof Error
          ? error.message
          : String(error),
    };
  } finally {
    clearTimeout(timer);
    // The probe turn is ours; do not leave it open on the platform.
    if (conversationId) {
      try {
        await endConversation(conversationId);
      } catch {
        // Best effort — the probe result is what matters.
      }
    }
  }
}

/**
 * Whether a tool result reports an auth rejection.
 *
 * Anchored to how a failed call is reported rather than matched anywhere in the
 * payload: an agent whose description contains "forbidden" is data, not a 401,
 * and flagging it pushed the admin toward a reconfigure that was not needed.
 */
function looksLikeAuthFailure(result: string | undefined): boolean {
  if (!result) return false;
  return /(^|[^0-9])(401|403)\s*(unauthorized|forbidden)?\b/i.test(result.slice(0, 200))
    && /error|unauthorized|forbidden|denied/i.test(result.slice(0, 200));
}

/* ─── Lifecycle ─── */

/** Deployment status for the configured operator agent. */
export async function readOperatorStatus(config: OperatorConfig) {
  if (!config.agentId || config.version == null) return null;
  return getDeploymentStatus(config.environment, config.agentId, config.version);
}

/**
 * Turn a previously-configured operator back on without rebuilding it.
 *
 * Deactivation only undeploys, so the agent and every resource behind it still
 * exist. Re-running the full provisioning flow here would create a second agent
 * and delete the first for no reason — and would force the admin to re-enter a
 * model key the vault already holds.
 */
export async function reactivateOperator(
  config: OperatorConfig,
): Promise<OperatorConfig> {
  if (!config.agentId || config.version == null) {
    throw new Error("The operator has no provisioned agent to re-enable.");
  }
  await deployAgent(config.environment, config.agentId, config.version);
  const next: OperatorConfig = { ...config, enabled: true };
  await writeOperatorConfig(next);
  return next;
}

/** Kill switch — undeploy the agent and mark the config disabled. */
export async function deactivateOperator(
  config: OperatorConfig,
): Promise<OperatorConfig> {
  if (config.agentId && config.version != null) {
    await undeployAgent(config.environment, config.agentId, config.version);
  }
  const next: OperatorConfig = { ...config, enabled: false };
  await writeOperatorConfig(next);
  return next;
}

/** Full reset — undeploy, delete the agent and its resources, drop the config. */
export async function resetOperator(config: OperatorConfig): Promise<void> {
  if (config.agentId && config.version != null) {
    try {
      await undeployAgent(config.environment, config.agentId, config.version);
    } catch {
      // Already undeployed, or the environment is gone — deletion is what matters.
    }
    await deleteAgent(config.agentId, config.version, {
      cascade: true,
      permanent: true,
    });
  }
  await clearOperatorConfig();
}
