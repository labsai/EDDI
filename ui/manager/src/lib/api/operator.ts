import { api } from "../api-client";
import { createApiAgent, type SetupResult } from "./agent-setup";
import {
  getVariable,
  upsertVariable,
  deleteVariable,
  type GlobalVariable,
} from "./variables";
import { deleteAgent, undeployAgent, getDeploymentStatus } from "./agents";
import { buildEndpointFilter, type OperatorScope } from "@/lib/operator/tool-scopes";
import { buildOperatorSystemPrompt } from "@/lib/operator/system-prompt";

/* ─── Config model ─── */

/**
 * How the operator authenticates its tool calls back to EDDI's admin API.
 *
 * `none` — no `Authorization` header is set on the generated tools. This is what
 * the backend does today when `apiAuth` is empty. It works on deployments with
 * OIDC disabled; on a Keycloak-protected deployment every tool call returns 401.
 *
 * `caller-context` — the operator's tools send `Bearer {context.eddiAuthToken}`.
 * `McpApiToolBuilder` writes `apiAuth` verbatim into the `Authorization` header,
 * and `ApiCallExecutor` runs header values through the template engine at call
 * time, so the placeholder resolves from the per-turn conversation context. The
 * operator chat hook puts the signed-in user's live bearer there on every
 * message, which makes tool calls run with the caller's real permissions and
 * audit identity.
 *
 * The trade-off for `caller-context` is real and must be surfaced to the admin:
 * per-turn context is written into conversation memory, so the token is stored
 * in MongoDB and is visible wherever conversation detail is rendered.
 */
export type OperatorAuthMode = "none" | "caller-context";

/** Context key carrying the caller's bearer when `authMode` is `caller-context`. */
export const CALLER_TOKEN_CONTEXT_KEY = "eddiAuthToken";

/** The `apiAuth` value that resolves to the caller's token at call time. */
export const CALLER_TOKEN_API_AUTH = `Bearer {context.${CALLER_TOKEN_CONTEXT_KEY}}`;

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
    return JSON.parse(variable.value) as OperatorConfig;
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
    const match = /^([A-Z]+)\s+(\/\S*)$/.exec(entry.trim());
    if (!match) return true;
    const [, method, path] = match;
    const pathItem = spec.paths[path!];
    return !pathItem || !(method!.toLowerCase() in pathItem);
  });
}

/* ─── Provisioning ─── */

export interface ProvisionOperatorParams {
  agentName: string;
  config: OperatorConfig;
  /** Vault reference or plain key for the LLM. Not the EDDI credential. */
  apiKey: string;
  /** Optional base URL for local providers (Ollama, Jlama). */
  baseUrl?: string;
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
  const { agentName, config, apiKey, baseUrl } = params;
  const spec = await fetchOpenApiSpec();

  return createApiAgent({
    agentName,
    systemPrompt: buildOperatorSystemPrompt(config.promptBody),
    openApiSpec: JSON.stringify(spec.raw),
    provider: config.provider,
    model: config.model,
    apiKey,
    apiBaseUrl: baseUrl || currentOrigin(),
    apiAuth: apiAuthForMode(config.authMode),
    endpoints: buildEndpointFilter(config.scope),
    deploy: true,
    environment: config.environment,
  });
}

/** The `apiAuth` value for an auth mode. `none` sends no header at all. */
export function apiAuthForMode(mode: OperatorAuthMode): string | undefined {
  return mode === "caller-context" ? CALLER_TOKEN_API_AUTH : undefined;
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

/* ─── Lifecycle ─── */

/** Deployment status for the configured operator agent. */
export async function readOperatorStatus(config: OperatorConfig) {
  if (!config.agentId || config.version == null) return null;
  return getDeploymentStatus(config.environment, config.agentId, config.version);
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
