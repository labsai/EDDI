import { api } from "../api-client";
import { operatorEnvironment } from "@/lib/operator/operator-environment";
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
import {
  buildOperatorSystemPrompt,
  defaultOperatorPromptBody,
} from "@/lib/operator/system-prompt";

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
  /**
   * The base URL the operator's generated tools target — the address **EDDI can
   * reach itself at**, which is NOT the address this browser reached EDDI at.
   *
   * This used to be `window.location.origin`, taken at provisioning time and
   * never stored. It was wrong on any deployment with something in between: a
   * staging instance reached through an SSH tunnel on `localhost:7080`, fronting
   * a container listening on `:7070`, got all 22 of its api-call resources
   * pointed at `localhost:7080` — meaningless inside the container. The operator
   * deployed, reported "Gate verified", and then failed every tool call while
   * blaming the platform's internal services.
   *
   * `null` means "ask the backend" (`fetchPlatformSelfUrl`); a non-empty value is
   * an explicit admin override and is used verbatim. Stored rather than derived
   * so the operator screen can SHOW the address the live tools use — the field
   * whose value the incident turned on was, until now, nowhere on screen.
   *
   * Optional, not just nullable: a config blob written before this field existed
   * has no key at all, and the variable store hands those back verbatim. Every
   * reader here must cope with `undefined` as well as `null` — they mean the same
   * thing ("nobody has decided yet"), and a required type would only have moved
   * the problem to a cast.
   */
  apiBaseUrl?: string | null;
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

/**
 * Tool-loop budget provisioned onto the operator's LLM task — the backend
 * ceiling (`AgentSetupService.MAX_TOOL_ITERATIONS`), on purpose. One operator
 * turn is one admin task of arbitrary length; the safety mechanism is the HITL
 * gate on every write, not a scarce round budget. Ordinary agents keep the
 * engine default (10). See provisionOperator for the incident this fixes.
 */
export const OPERATOR_MAX_TOOL_ITERATIONS = 100;

export function defaultOperatorConfig(promptBody?: string): OperatorConfig {
  // Derived from the scope set here rather than restated by callers, so the
  // seeded body can never describe a capability this config does not grant.
  //
  // Write-gated is the DEFAULT posture, not an upgrade: every write pauses for
  // human approval (buildToolApprovals gates all non-GET methods), and
  // activation refuses to leave a write-capable operator deployed unless a real
  // test write provably paused (enforceWriteCanaryGate rolls back otherwise).
  // An admin who wants a purely inspecting operator picks read_only in the
  // activation form.
  const scope: OperatorScope = "read_write";
  return {
    enabled: false,
    agentId: null,
    version: null,
    environment: "production",
    provider: "anthropic",
    model: "claude-sonnet-5",
    credentialKey: null,
    // Resolved from the backend at activation time — see apiBaseUrl's doc comment.
    apiBaseUrl: null,
    scope,
    authMode: "none",
    promptBody: promptBody ?? defaultOperatorPromptBody(scope),
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

/** Exported for the write canary, which must tell an old backend (404) from a broken one. */
export function isNotFound(error: unknown): boolean {
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
    // Same scope as the endpoint filter below, so the preamble describes the
    // boundary the agent is actually behind.
    systemPrompt: buildOperatorSystemPrompt(config.promptBody, config.scope),
    openApiSpec: JSON.stringify(spec.raw),
    provider: config.provider,
    model: config.model,
    apiKey,
    // The address EDDI can reach ITSELF at, resolved by the caller (see
    // resolveOperatorApiBaseUrl). Never this browser's origin, and never the
    // LLM's base URL.
    apiBaseUrl: requireApiBaseUrl(config),
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
    // The engine default (10) suits a conversational agent with a handful of
    // tools. The operator's whole toolset is this deployment's API, and one
    // admin task is a long chain — an agent build via granular endpoints was
    // observed dying at the default cap after 22 calls, answering only "max
    // tool iterations reached". 100 is the backend ceiling
    // (AgentSetupService.MAX_TOOL_ITERATIONS), chosen deliberately: budget is
    // not the safety mechanism here, the HITL gate is — every write pauses for
    // approval no matter how many rounds remain.
    maxToolIterations: OPERATOR_MAX_TOOL_ITERATIONS,
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

/**
 * The base URL to provision the tools with, or a loud failure.
 *
 * Deliberately NOT a fallback to `window.location.origin`: that silent fallback
 * IS the defect. The browser's origin is only ever a last-resort guess, and the
 * one caller allowed to make it (`resolveOperatorApiBaseUrl`) does so explicitly
 * and says so. By the time provisioning runs, the address must have been decided.
 */
function requireApiBaseUrl(config: OperatorConfig): string {
  const resolved = normalizeBaseUrl(config.apiBaseUrl);
  if (!resolved) {
    throw new Error(
      "The operator has no platform base URL to point its tools at. This is the address EDDI can reach itself at (for example http://127.0.0.1:7070) — resolve it before provisioning.",
    );
  }
  return resolved;
}

/**
 * Trim, and strip trailing slashes. `setup-api` concatenates the base URL with
 * each path verbatim, so `http://eddi:7070/` would become `http://eddi:7070//…`
 * in all 22 tools — which some routers answer and some 404. The backend's own
 * answer is already normalised this way; an admin-typed value was not.
 */
export function normalizeBaseUrl(value: string | null | undefined): string {
  return (value ?? "").trim().replace(/\/+$/, "");
}

/**
 * Whether a (normalised) value is a bare origin — `http(s)://host[:port]` and
 * nothing else. Parsed with `URL` rather than matched with a pattern alone: a
 * character class that merely excludes `/` still admits `?tenant=x` (every
 * generated path would become query content) and `user:pass@` (credentials baked
 * into 22 resources). The textual check in front catches what `URL` normalises
 * away, such as a trailing `/.`, so the value provisioned is the value validated.
 */
export function isOriginOnlyBaseUrl(value: string): boolean {
  if (!/^https?:\/\/[^\s/?#@\\]+$/i.test(value)) return false;
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return false;
  }
  return (
    (url.protocol === "http:" || url.protocol === "https:") &&
    url.hostname.length > 0 &&
    url.username === "" &&
    url.password === "" &&
    url.search === "" &&
    url.hash === "" &&
    url.pathname === "/"
  );
}

/** What the backend reports as its own reachable address. */
export interface PlatformSelfUrl {
  /** `null` when `source` is `unresolved`. */
  baseUrl: string | null;
  /**
   * `configured` (eddi.self.base-url), `loopback` (derived from the HTTP port), or
   * `unresolved` — the deployment runs on a random port and set no override, so
   * there is nothing the server can honestly answer.
   */
  source: string;
}

/**
 * Ask EDDI for the address it can reach itself at.
 *
 * Returns `null` on a 404 — a backend older than the endpoint — so the caller can
 * tell "this deployment cannot tell me" from a transport failure it should
 * surface. Every other error propagates.
 */
export async function fetchPlatformSelfUrl(): Promise<PlatformSelfUrl | null> {
  try {
    return await api.get<PlatformSelfUrl>("/administration/operator/self-url");
  } catch (error) {
    if (isNotFound(error)) return null;
    throw error;
  }
}

/**
 * Decide the base URL the operator's tools will target.
 *
 * Precedence, and the reasoning for it:
 * 1. **An explicit value on the config.** An admin who typed an address into the
 *    activation form knows something neither the browser nor the server does — a
 *    service name, a mesh hostname, an in-cluster port.
 * 2. **The backend's own answer.** `eddi.self.base-url` when the deployment sets
 *    it, otherwise loopback on `quarkus.http.port`. This is the correct default
 *    on every topology: a process reaching itself does not traverse the tunnel,
 *    the port mapping or the reverse proxy that made the browser's origin wrong.
 * 3. **This browser's origin**, only when the backend is too old to answer, and
 *    with a warning. On a single-host deployment with nothing in between it is
 *    right, which is exactly why the bug survived — it must never be reached
 *    silently on anything else.
 */
export async function resolveOperatorApiBaseUrl(config: OperatorConfig): Promise<string> {
  const explicit = normalizeBaseUrl(config.apiBaseUrl);
  if (explicit) return explicit;

  const self = await fetchPlatformSelfUrl();
  const answered = normalizeBaseUrl(self?.baseUrl);
  if (answered) return answered;
  if (self) {
    // The server ANSWERED, and its answer is that it cannot know (a random HTTP
    // port and no eddi.self.base-url). Guessing the browser's origin here would be
    // provisioning the one value known to be wrong on any proxied deployment —
    // the original defect. Only a backend too old to answer (404 -> null) earns
    // the fallback below.
    throw new Error(
      "This EDDI deployment cannot determine its own address (it runs on a random HTTP port and eddi.self.base-url is not set). Enter the platform base URL explicitly, or set eddi.self.base-url.",
    );
  }

  const origin = currentOrigin();
  // Checked BEFORE the warning: "falling back to ()" would be a misleading thing
  // to log on the way to throwing.
  if (!origin) {
    throw new Error(
      "This EDDI deployment cannot report its own base URL, and there is no browser origin to fall back on. Enter the platform base URL explicitly.",
    );
  }
  console.warn(
    "[operator] This EDDI deployment cannot report its own base URL (it predates /administration/operator/self-url). " +
      `Falling back to this browser's origin (${origin}), which is only correct when nothing sits between the browser and EDDI. ` +
      "If the operator's tools fail to connect, set the platform base URL explicitly.",
  );
  return origin;
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

/**
 * The method-qualified prefixes above, with the trailing `*` stripped —
 * `["http.post:", "http.put:", "http.patch:", "http.delete:"]`.
 *
 * The blanket gate patterns already match every call of that method, so any
 * pattern starting with one of these prefixes — narrow
 * (`http.post:/agentstore/agents`) or equally broad (`http.post:*`) — addresses
 * a strict subset of what the blanket pattern gates. No glob-intersection logic
 * is needed: prefix membership alone is sufficient to prove overlap.
 */
const GATED_WRITE_PREFIXES = GATED_WRITE_PATTERNS.map((pattern) => pattern.slice(0, -1));

/**
 * Exempt prefixes that can match a write call.
 *
 * `http.*:` is included because the method segment is itself a wildcard, and
 * `ToolApprovalPatterns.compile` turns `*` into `.*` — so an exempt of
 * `http.*:/agentstore/agents` matches the address `http.post:/agentstore/agents`
 * exactly as readily as the GET its author had in mind.
 *
 * Known limit: a pattern addressing a tool by its bare dispatch name (an exempt
 * of `deployAgent`) also exempts a write, and cannot be recognised from the
 * config alone — `ToolApprovalGate.addressesOf` matches bare names too, but the
 * name-to-method mapping lives in the fetched spec, not here. The method-
 * qualified forms below are what `buildToolApprovals` writes and what a hand
 * edit realistically reaches for.
 */
const WRITE_EXEMPT_PREFIXES = [...GATED_WRITE_PREFIXES, "http.*:"] as const;

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
  // Non-empty is not the same as effective. `requireApproval: ["http.get:*"]`
  // is a populated list that gates only reads, so every write runs unapproved
  // while the config reads as gated at a glance — a decoy the length check
  // alone accepts. At least one pattern must actually address a write.
  //
  // Known limit, the mirror of the one WRITE_EXEMPT_PREFIXES documents: a gate
  // written against bare dispatch names (`requireApproval: ["deployAgent"]`)
  // genuinely gates that write but cannot be recognised as such without the
  // spec's name-to-method mapping, so it reports as ungated. That direction is
  // the safe one — it withholds write scope, or raises a warning on a created
  // agent, rather than certifying a gate nobody verified. `buildToolApprovals`
  // writes the method-qualified form.
  const gatesAWrite = toolApprovals.requireApproval.some(
    (pattern) =>
      pattern === "*" ||
      pattern.startsWith("http.*") ||
      GATED_WRITE_PREFIXES.some((prefix) => pattern.startsWith(prefix)),
  );
  if (!gatesAWrite) {
    return {
      ok: false,
      reason: "toolApprovals.requireApproval gates no write method — reads only, so every write runs unapproved",
    };
  }
  if (toolApprovals.timeoutPolicy === "AUTO_APPROVE") {
    return { ok: false, reason: "toolApprovals.timeoutPolicy is AUTO_APPROVE" };
  }
  // `exempt` is the more dangerous of the two lists: ToolApprovalGate.classify
  // tests it FIRST and short-circuits to `allowed`, so a matching entry means
  // the call never pauses at all — strictly worse than an AUTO_APPROVE rule,
  // which at least records a pause. It therefore gets the same prefix test the
  // rules check below uses, not just an exact-match list: an exempt of
  // `http.post:/agentstore/agents` is narrower than `http.post:*` and every bit
  // as effective at un-gating that write.
  const exempt = toolApprovals.exempt ?? [];
  const overbroadExempt = exempt.find(
    (pattern) =>
      (OVERBROAD_EXEMPT_PATTERNS as readonly string[]).includes(pattern) ||
      WRITE_EXEMPT_PREFIXES.some((prefix) => pattern.startsWith(prefix)),
  );
  if (overbroadExempt) {
    return { ok: false, reason: `exempt pattern '${overbroadExempt}' would exempt a gated write` };
  }
  // A per-tool rule takes precedence over the toolApprovals-level scalar for any
  // call it matches (the backend's ToolApprovalRules.governing — most specific
  // statement wins), so a safe-looking top-level WAIT_INDEFINITELY does not
  // guarantee the effective policy for a write endpoint actually IS
  // WAIT_INDEFINITELY. Checking only the scalar above would let a rule such as
  // { match: "http.post:/agentstore/agents", timeoutPolicy: "AUTO_APPROVE" }
  // pass verification while that one endpoint auto-executes unreviewed.
  const autoApproveWriteRule = (toolApprovals.rules ?? []).find(
    (rule) =>
      rule.timeoutPolicy === "AUTO_APPROVE" &&
      GATED_WRITE_PREFIXES.some((prefix) => rule.match.startsWith(prefix)),
  );
  if (autoApproveWriteRule) {
    return {
      ok: false,
      reason: `rule '${autoApproveWriteRule.match}' sets timeoutPolicy AUTO_APPROVE on a gated write`,
    };
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

/**
 * `userId` stamped on every conversation the activation probes start.
 *
 * The probes run against the operator's OWN agent, and both are fire-and-forget
 * after activation returns — so for ~30 seconds there is a conversation newer
 * than anything the admin has, belonging to the same agent, that is about to be
 * ended. Without a marker, a second tab opened in that window restored the
 * canary as the admin's own transcript ("List the agents on this platform. Use
 * your tools; do not guess.") and was then parked on a dead conversation.
 *
 * A `userId` rather than a naming convention on the message: it lands on the
 * conversation DESCRIPTOR, so a caller can filter probes out of a descriptor
 * list without reading each transcript to find out what it is.
 */
export const OPERATOR_PROBE_USER_ID = "eddi-operator-probe";

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
  // COMPOSED with the caller's signal, not replaced by it: `signal ??
  // timeout.signal` silently disabled this ceiling for any caller passing an
  // abort signal (the parallel-canary activation does).
  const effectiveSignal = signal ? AbortSignal.any([signal, timeout.signal]) : timeout.signal;

  let conversationId = null;
  try {
    conversationId = await startConversation(operatorEnvironment(config), config.agentId, OPERATOR_PROBE_USER_ID);
    let toolCalls = 0;
    let toolError: string | undefined;
    let streamError: string | undefined;

    const stream = sendMessageStreaming(
      operatorEnvironment(config),
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
          if (entry.type !== "tool_result") continue;
          if (looksLikeAuthFailure(entry.result)) {
            toolError =
              "The operator's tools were rejected by EDDI (unauthorized). Its authentication mode cannot reach this deployment.";
          }
          // Checked SECOND and allowed to win: an unreachable address is the more
          // actionable of the two, and it is the diagnosis the admin will not
          // reach on their own — a connection failure narrated by the model reads
          // as a broken platform, not as a wrong URL.
          if (looksLikeConnectionFailure(entry.result)) {
            toolError = connectionFailureMessage(config);
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
 * Whether a tool result reports that the tool could not reach its target at all.
 *
 * Distinct from every other failure on purpose. The operator's whole toolset
 * shares one base URL, so "cannot connect" is almost never a fault in the thing
 * being called — it is that URL. Left unlabelled, the model paraphrases the
 * transport error into a confident diagnosis of the wrong subsystem; on the
 * incident this was written for, "connection refused" became "a problem with the
 * platform's internal services" and cost an afternoon of looking at a healthy
 * server.
 *
 * Matched on the backend's own wording (`HttpCallToolsProvider.describeToolFailure`)
 * plus the bare transport phrases an older backend emits, so this still fires
 * against a deployment that predates that message.
 */
function looksLikeConnectionFailure(result: string | undefined): boolean {
  if (!result) return false;
  const head = result.slice(0, 400);
  // Anchored to the shape of a FAILED call — `{"error": …}`, which is how the
  // backend reports every tool failure — for the same reason looksLikeAuthFailure
  // is anchored: an agent whose description mentions a refused connection is data,
  // and flagging it would send the admin to "fix" a base URL that works.
  if (!/^\s*\{\s*"error"\s*:/.test(head)) return false;
  return (
    /connection was refused|connection refused|econnrefused/i.test(head) ||
    /host name could not be resolved|unknownhost|unresolved address|enotfound/i.test(head) ||
    /no route to the host|no route to host/i.test(head) ||
    /connection timed out|connect timed out/i.test(head)
  );
}

/**
 * What the admin is told when the operator's tools cannot reach their target.
 *
 * Names the address and the field, and says outright that EDDI's own health is
 * not the thing to check. The URL is safe to show — it is configuration the admin
 * can already read on this screen; credentials and headers deliberately are not
 * mentioned at all.
 */
function connectionFailureMessage(config: OperatorConfig): string {
  const target = config.apiBaseUrl?.trim();
  return (
    `The operator could not connect to the platform API at ${target || "its configured base URL"}. ` +
    "This is the operator's own base URL being unreachable from the EDDI server — not an outage of EDDI or its internal services. " +
    "It must be an address EDDI can reach itself at (for example http://127.0.0.1:7070), not the address your browser uses. " +
    "Reconfigure the operator and set the platform base URL."
  );
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

/**
 * Kill switch — undeploy the agent and mark the config disabled.
 *
 * `endAllActiveConversations` is deliberate, not a shortcut. The backend refuses
 * to undeploy an agent that still has active conversations (409, carrying a
 * TEXT_PLAIN explanation), and the operator's active conversation is almost
 * always the admin's own operator chat — on the very screen the deactivate
 * button lives on. Without the flag, having *used* the operator made it
 * undeployable: you had to find and end your own chat first, with only a bare
 * 409 to say why. The conversations ended belong to this operator at this
 * version, and the admin is explicitly asking for it to stop.
 */
export async function deactivateOperator(
  config: OperatorConfig,
): Promise<OperatorConfig> {
  if (config.agentId && config.version != null) {
    await undeployAgent(config.environment, config.agentId, config.version, {
      endAllActiveConversations: true,
    });
  }
  const next: OperatorConfig = { ...config, enabled: false };
  await writeOperatorConfig(next);
  return next;
}

/**
 * Full reset — undeploy, delete the agent and its resources, drop the config.
 *
 * Same `endAllActiveConversations` reasoning as {@link deactivateOperator}, and
 * here it also removes a misleading failure: the catch below swallows the 409,
 * so a reset triggered while an operator chat was open still SUCCEEDED, but left
 * a red request in the network panel for an operation that worked. Deleting the
 * agent moments later ends those conversations regardless — asking for it up
 * front just makes the intent explicit instead of incidental.
 */
export async function resetOperator(config: OperatorConfig): Promise<void> {
  if (config.agentId && config.version != null) {
    try {
      await undeployAgent(config.environment, config.agentId, config.version, {
        endAllActiveConversations: true,
      });
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

/* ─── Metrics relay ─── */

/**
 * Report a client-run canary outcome onto this deployment's `/q/metrics`.
 *
 * Purely a relay — see `docs/hitl.md` (EDDI backend repo) "Operator canary/gate
 * metrics" for why this has to exist at all: the canary runs entirely in this
 * browser tab, and the backend has no server-side event to hang a meter on.
 *
 * Best-effort BY DESIGN: a caller must never let a failed report change the
 * canary's own result. Losing the metric for one run is a worse UX than
 * treating a Grafana dashboard as more important than the security probe it
 * is merely reporting on.
 */
export async function reportOperatorCanaryResult(outcome: string, durationMs?: number): Promise<void> {
  try {
    await api.post("/administration/operator/canary-result", { outcome, durationMs });
  } catch {
    // See doc comment.
  }
}

/** Report a client-run gate-verification outcome. Same best-effort contract as {@link reportOperatorCanaryResult}. */
export async function reportOperatorGateStatus(verified: boolean): Promise<void> {
  try {
    await api.post("/administration/operator/gate-status", { verified });
  } catch {
    // Best-effort — see reportOperatorCanaryResult.
  }
}

/** Backend answer to a gate dry-run classification. */
export interface GateDryRunResult {
  policyPresent: boolean;
  gated: boolean;
  matchedPattern: string | null;
}

/**
 * Deterministically classify one synthetic tool call against the operator's
 * STORED approval policy, using the backend's own runtime gate
 * (`ToolApprovalGate.classify`) — nothing executed, nothing written.
 *
 * NOT best-effort, unlike the two reporters above: this answer gates
 * activation, so a transport failure must surface to the caller rather than
 * be swallowed into a guess. Backends older than the endpoint 404 here — the
 * caller decides what that means for it.
 */
export async function gateDryRun(
  config: OperatorConfig,
  toolName: string,
  endpoint: string,
): Promise<GateDryRunResult> {
  const [method = "", path = ""] = endpoint.split(" ", 2);
  return api.post<GateDryRunResult>("/administration/operator/gate-dry-run", {
    agentId: config.agentId,
    version: config.version,
    toolName,
    source: "http",
    // Backend address form: lowercase `method:path`, the same shape discovery
    // records into toolEndpoints.
    endpoint: `${method.toLowerCase()}:${path}`,
  });
}
