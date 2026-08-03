/**
 * Tool scopes for the Platform Operator.
 *
 * The operator is an EDDI API Agent whose tools are generated from EDDI's own
 * OpenAPI spec. `setup-api` accepts an `endpoints` filter — a comma-separated
 * list of `"METHOD /path"` entries matched against the spec's path templates
 * (verbatim, including `{param}` placeholders). Anything not listed produces no
 * tool at all, so the filter is the actual capability boundary, not a hint.
 *
 * This is an allow-list on purpose. A deny-list ("bind everything, subtract the
 * dangerous ones") fails open: any endpoint added to the backend later would be
 * silently granted. Substring matching on words like "delete" fails the same way.
 */

/** A capability scope the operator can be provisioned with. */
export type OperatorScope = "read_only" | "read_write";

/**
 * `{store}/{resource}` path segments for every workflow-extension store an
 * agent's workflow can reference — the config documents that define what an
 * agent says and does (prompt/model, behavior rules, output messages,
 * slot-filling, NLU dictionaries, HTTP and MCP tool wiring). Named once here
 * and reused to build both the by-id READ_ENDPOINTS entries and the
 * PUT/POST WRITE_ENDPOINTS entries below, plus `grantsAgentModification` —
 * three lists that would otherwise drift independently if a ninth store were
 * ever added and one of the three forgot to change.
 *
 * Deliberately excludes `workflowstore/workflows` (listed by hand alongside
 * `groupstore/groups` below): a workflow is the pipeline that *references*
 * these stores, not one of the documents it references, and its own read is
 * already grouped with the agent/group reads it sits between.
 */
const WORKFLOW_EXTENSION_STORES = [
  "llmstore/llms",
  "rulestore/rulesets",
  "outputstore/outputsets",
  "propertysetterstore/propertysetters",
  "dictionarystore/dictionaries",
  "apicallstore/apicalls",
  "mcpcallsstore/mcpcalls",
] as const;

/**
 * Read endpoints the operator is allowed to call.
 *
 * These are OpenAPI path templates copied verbatim from EDDI's spec. Every entry
 * is asserted to exist in the fetched spec by `tool-scopes.test.ts`, so an
 * invented or renamed path fails CI rather than silently producing zero tools.
 *
 * The set is chosen so the operator can actually answer the questions we suggest
 * to users: descriptors alone cannot diagnose a failing deployment, so by-id
 * reads and deployment status are included.
 */
export const READ_ENDPOINTS: readonly string[] = [
  // Agents
  "GET /agentstore/agents/descriptors",
  "GET /agentstore/agents/{id}",
  // Workflows and groups
  "GET /workflowstore/workflows/descriptors",
  "GET /workflowstore/workflows/{id}",
  "GET /groupstore/groups/descriptors",
  "GET /groupstore/groups/{id}",
  // Workflow extensions — the by-id read half of every authoring pair below.
  // Each is reached by navigating agent -> workflow -> the exact id+version a
  // step's resourceUri names, never by browsing a store's full contents, so no
  // corresponding "descriptors" entry is needed here (see WRITE_ENDPOINTS' own
  // doc comment for why authoring is scoped to these stores).
  ...WORKFLOW_EXTENSION_STORES.map((store) => `GET /${store}/{id}`),
  // Conversations
  "GET /conversationstore/conversations",
  "GET /conversationstore/conversations/{conversationId}",
  // Operations
  "GET /administration/{environment}/deploymentstatus/{agentId}",
  "GET /administration/coordinator/status",
  "GET /administration/logs",
  "GET /administration/quotas",
  // Schedules — added alongside WRITE_ENDPOINTS' schedule disable: without this
  // the operator could stop a runaway job but never see it to know to.
  "GET /schedulestore/schedules",
  // Audit
  "GET /auditstore/agent/{agentId}",
] as const;

/**
 * Write endpoints.
 *
 * Populated only once the whole chain that makes a write safe actually exists:
 * the gate itself (backend), provisioning that installs it (setup / setup-api,
 * both `hitlConfig`-capable as of the request-fingerprint work), a verified
 * read-back of every version (`verifyGateInstalled`), the approval surface
 * that can resolve a pause (iteration 5, extended in iteration 22 to decide a
 * `TOOL_CALL` pause inline), and approval binding to the resolved REQUEST
 * rather than the tool name, so what an approver sees is what actually runs
 * (backend `IApiCallExecutor#resolve` + gate-time fingerprint + pre-execution
 * re-check). See `docs/hitl.md` "Request pinning" in the EDDI backend repo.
 *
 * Two shapes of entry, judged by two different standards:
 *
 * **Operational verbs** — each the narrowest verb that solves a real operator
 * need, chosen so the worst case of an approved-but-wrong write is small and
 * reversible:
 *
 * - `PATCH /descriptorstore/descriptors/{id}` — partial metadata edit only, no
 *   execution semantics, no egress, no persistence beyond a name/description.
 *   Also the highest-frequency real request ("tidy this deployment").
 * - `POST .../deploy/{agentId}` / `.../undeploy/{agentId}` — paired
 *   deliberately: deploy without rollback is worse than useless in an
 *   incident. Both can only activate or stop a config a human already
 *   authored; neither can create behavior. Availability-only blast radius,
 *   instantly reversible.
 * - `POST /schedulestore/schedules/{scheduleId}/disable` — the "stop the
 *   bleeding" verb for a runaway scheduled job burning LLM spend. Asymmetric
 *   by design: disable is bound, enable/create/fire/retry are not — creating a
 *   schedule is attacker persistence (a scheduled turn has no human present,
 *   so an approval prompt never appears), and disabling one is not.
 *
 * **Authoring endpoints** — full create/update of agent behavior. Judged
 * differently: not by blast radius (a bad prompt or a bad rule set can be as
 * consequential as any operational verb) but by whether the *document itself*
 * can defeat the approval mechanism reviewing it. That standard is what
 * separates what is bound from what is not:
 *
 * - `POST`/`PUT` on `llmstore`, `rulestore`, `outputstore`,
 *   `propertysetterstore`, `dictionarystore`, `apicallstore`, `mcpcallsstore`,
 *   and `workflowstore` — this is "create and modify any type of agent" in
 *   practice: prompt and model (`llm`), behavior rules, output messages,
 *   slot-filling, NLU dictionaries, HTTP and MCP tool wiring, and which of
 *   those a workflow's pipeline actually runs, in order. **None of these
 *   documents carry a `hitlConfig` or any other field that gates a write** —
 *   `AgentConfiguration.hitlConfig` lives one level up, on the agent document
 *   itself, never on a workflow extension. A bad edit here is reviewable and
 *   reversible exactly like any other config change; it cannot touch the gate
 *   that is reviewing it.
 * - `POST /administration/agents/setup` and `.../setup-api` — build a whole
 *   new agent (standard, and OpenAPI-spec-backed) in one call. Unlike an
 *   update, a create has no prior version to diff against, so "does this body
 *   carry a real gate" is an unambiguous, standalone question — which is
 *   exactly what `escalation-flags.ts`'s `agentCreatedWithoutGate` and
 *   `agentCreatedWithBroadEndpoints` checks answer for the approver, above the
 *   raw JSON. Both request bodies also carry a provider API key in plaintext;
 *   `SecretRedactionFilter`-based preview redaction (backend) covers the
 *   common key shapes, not every possible one — an honest, documented gap, not
 *   a solved one.
 * - `POST /groupstore/groups` — CREATE only, never `PUT`. A group references
 *   agents that already exist and already have their own gates; it composes
 *   authored behavior rather than authoring any. Create is also the shape
 *   where the generated tool's whole-document body is reviewable: there is no
 *   prior version, so the approver reads the document itself rather than
 *   diffing one they cannot see.
 *
 * Deliberately NOT here, regardless of how safe the request would look:
 * `PUT /agentstore/agents/{id}` and `PUT /groupstore/groups/{id}` — the one
 * pair of documents in this whole list that carry their own gate
 * (`AgentConfiguration.hitlConfig.toolApprovals`; `AgentGroupConfiguration
 * .hitlConfig` plus each `DiscussionPhase.requiresApproval`). A create can be
 * checked in isolation — "does this new document have a gate" needs no prior
 * state — but a full-document *update* cannot: "was the gate just weakened"
 * is a diff question, and nothing here has a prior version to diff against.
 * `escalation-flags.ts` deliberately stays a pure function of the resolved
 * body alone (see its own doc comment), so it cannot answer that question
 * either — extending it to try would mean fetching and comparing prior state
 * from inside a body-shape check, a different mechanism this file does not
 * have. Until a narrower primitive exists (e.g. a patch endpoint that cannot
 * touch `hitlConfig` at all, the same role `updateResourceUri` already plays
 * for repointing one workflow step without replacing the whole document),
 * "modify this agent" is served entirely by the workflow-extension stores
 * above, which is most of what a real request needs and none of what makes
 * this pair different. `POST /groupstore/groups/{id}` (duplicate) stays out
 * for the same document-integrity reason as `PUT`. Also excluded: any
 * schedule verb but disable (creating one is attacker persistence — see
 * above), and every `DELETE` (no undo exists in any of these stores).
 *
 * Because `buildToolApprovals` gates every `http.{post,put,patch,delete}:*`
 * unconditionally, anything added here is gated the moment it is added — the
 * failure mode of forgetting to update a pattern list does not exist.
 */
export const WRITE_ENDPOINTS: readonly string[] = [
  "PATCH /descriptorstore/descriptors/{id}",
  "POST /administration/{environment}/deploy/{agentId}",
  "POST /administration/{environment}/undeploy/{agentId}",
  "POST /schedulestore/schedules/{scheduleId}/disable",
  "POST /groupstore/groups",
  "POST /administration/agents/setup",
  "POST /administration/agents/setup-api",
  "PUT /workflowstore/workflows/{id}",
  "POST /workflowstore/workflows",
  ...WORKFLOW_EXTENSION_STORES.flatMap((store) => [`PUT /${store}/{id}`, `POST /${store}`]),
] as const;

/**
 * The tool-approval gate installed on every operator agent, read_only included.
 *
 * Every write method is gated broadly (`http.post:*` etc.), every read is
 * exempt — the same shape the backend itself documents and recommends
 * (`docs/hitl.md`), so the gate needs no separate maintenance as `WRITE_ENDPOINTS`
 * grows: a pattern addressed by HTTP method covers a new write endpoint the
 * moment it is allow-listed, with no parallel list to remember to update. That
 * is the "enumerate downward" invariant applied to the gate itself — gate
 * broadly, exempt narrowly, so a missed update costs an approval prompt rather
 * than an ungated write.
 *
 * Sent for `read_only` too. Today that gates zero real tools (no write endpoint
 * is allow-listed), but it installs a REAL, verifiable document — `read_write`
 * later reuses the identical config unchanged, and every operator agent this
 * screen has ever created is provably running the same gate shape from day one,
 * not just the ones activated after writes shipped.
 *
 * `timeoutPolicy` is hardcoded to `WAIT_INDEFINITELY` and not exposed as a
 * parameter: the operator must never be configurable into `AUTO_APPROVE`, which
 * would execute a gated write with nobody watching. A per-endpoint override
 * (backend `toolApprovals.rules`) can tighten this later without this function
 * ever being able to loosen it.
 */
export function buildToolApprovals(): import("@/lib/api/hitl").ToolApprovalsConfig {
  return {
    requireApproval: ["http.post:*", "http.put:*", "http.patch:*", "http.delete:*"],
    exempt: ["http.get:*"],
    timeoutPolicy: "WAIT_INDEFINITELY",
  };
}

/**
 * Verified facts `isWriteScopeAvailable` requires — never an optimistic flag a
 * caller can set to unblock the UI. Each fact names the specific thing that has
 * to be independently true; a caller that cannot honestly assert one leaves it
 * `false` rather than approximating.
 */
export interface WriteScopeFacts {
  /** setup-api accepted a `hitlConfig` and it round-trips on read-back — not
   *  merely that the request didn't 400. */
  backendAcceptsHitlConfig: boolean;
  /** Every version of the agent document was read back and the gate verified
   *  present and sane on each — not just the version most recently deployed. */
  gateVerifiedOnEveryVersion: boolean;
  /** Tool calls must run as the real caller. `"none"` cannot support attributed
   *  approval decisions or self-approval prevention. */
  authMode: "none" | "caller-identity";
  /** An approval surface capable of actually resolving a pause is mounted —
   *  otherwise a gated write pauses forever with no way to unblock it. */
  approvalSurfaceMounted: boolean;
}

/**
 * Whether the `read_write` scope can be offered.
 *
 * This is the approval seam. It is a function, not a constant, so the
 * invariant "no writes without a verified gate" is enforced at the one place
 * scope is chosen, rather than restated in the UI. Every fact must hold, and
 * `WRITE_ENDPOINTS` must be non-empty — so this returns `false` unconditionally
 * until a future change deliberately populates the write allow-list, whatever
 * the caller passes in.
 */
export function isWriteScopeAvailable(facts: WriteScopeFacts): boolean {
  return (
    WRITE_ENDPOINTS.length > 0 &&
    facts.backendAcceptsHitlConfig &&
    facts.gateVerifiedOnEveryVersion &&
    facts.authMode === "caller-identity" &&
    facts.approvalSurfaceMounted
  );
}

/** Resolve the endpoint list for a scope. */
export function endpointsForScope(scope: OperatorScope): readonly string[] {
  return scope === "read_write"
    ? [...READ_ENDPOINTS, ...WRITE_ENDPOINTS]
    : READ_ENDPOINTS;
}

/**
 * Whether a granted endpoint set contains anything that can change state.
 *
 * Takes the resolved set rather than a scope so the answer describes what was
 * actually granted: `read_write` grants no writes at all while `WRITE_ENDPOINTS`
 * is empty, and anything derived from this — the operator's own system prompt
 * above all — must say so rather than describing an intent.
 *
 * Fail-safe by construction: only a literal `GET` counts as a read. An entry
 * this function cannot parse, or one using a method nobody updated it for,
 * counts as a write. The failure mode is then an operator told it can change
 * things when it cannot, which costs a needlessly cautious answer — rather than
 * one told it is read-only while holding a tool that is not.
 */
export function grantsWriteCapability(endpoints: readonly string[]): boolean {
  return endpoints.some((entry) => {
    const parsed = parseEndpoint(entry);
    return parsed === null || parsed.method !== "GET";
  });
}

/**
 * Whether the granted endpoints can build a whole new agent from scratch
 * (standard or OpenAPI-spec-backed).
 *
 * Exact membership, not a substring or prefix match on `/administration/` —
 * that directory also holds deploy/undeploy/logs/quotas, none of which create
 * anything.
 */
export function grantsAgentCreation(endpoints: readonly string[]): boolean {
  const set = new Set(endpoints);
  return set.has("POST /administration/agents/setup") || set.has("POST /administration/agents/setup-api");
}

/**
 * Whether the granted endpoints can change an existing agent's prompt,
 * behavior, outputs, tool wiring, or pipeline — any workflow or
 * workflow-extension store's update verb.
 *
 * Deliberately silent on `PUT /agentstore/agents/{id}` and
 * `PUT /groupstore/groups/{id}`: neither is ever granted (see WRITE_ENDPOINTS'
 * doc comment), so checking for them here would just be dead code describing
 * a capability that can never exist.
 */
export function grantsAgentModification(endpoints: readonly string[]): boolean {
  const set = new Set(endpoints);
  return (
    set.has("PUT /workflowstore/workflows/{id}") ||
    WORKFLOW_EXTENSION_STORES.some((store) => set.has(`PUT /${store}/{id}`))
  );
}

/**
 * Build the `endpoints` filter string for `setup-api`.
 *
 * The backend splits on commas and trims, so a comma-joined list is the wire
 * format.
 */
export function buildEndpointFilter(scope: OperatorScope): string {
  return endpointsForScope(scope).join(", ");
}

/** Parse `"GET /a/{b}"` into its method and path template. */
export function parseEndpoint(
  entry: string,
): { method: string; path: string } | null {
  const match = /^([A-Z]+)\s+(\/\S*)$/.exec(entry.trim());
  if (!match) return null;
  return { method: match[1]!, path: match[2]! };
}
