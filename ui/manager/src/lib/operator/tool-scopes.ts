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
 * slot-filling, NLU dictionaries, HTTP and MCP tool wiring).
 *
 * READ scope. Every one of these is safe to read; only a subset
 * (`WRITABLE_EXTENSION_STORES`) is safe to write — see there.
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
 * The subset of {@link WORKFLOW_EXTENSION_STORES} that is safe to WRITE.
 *
 * Currently all of them — but `llmstore/llms` is only here because a second,
 * separate control exists, and it must not be separated from that control.
 *
 * **Why it needed one.** An LLM document is not just a prompt and a model:
 * `LlmConfiguration.Task.toolApprovals` is a per-task approval-gate override
 * that, when present, **fully replaces the agent-level
 * `hitlConfig.toolApprovals`** for that task — `LlmTask.java` resolves
 * `task.getToolApprovals() != null ? task.getToolApprovals() : <agent default>`,
 * and the backend deliberately honours an explicit task-level policy as a
 * designer opt-in. So a bare `PUT` grant would let the operator propose a
 * document in which one nested field among forty silently disables every future
 * approval — reviewed, technically, but not reviewably.
 *
 * **What makes it safe.** `gate-guard.ts` refuses, as a hard control rather
 * than a warning label, any llmstore write whose body carries a
 * `toolApprovals` key at all (and any whose body cannot be read in full to
 * prove it does not). A body with no task-level `toolApprovals` resolves to
 * `null`, so the agent-level gate applies — the operator can therefore change
 * what an agent *says and runs*, and cannot change what *gates* it. Removing a
 * gate that was protecting something is not reachable: falling back to the
 * agent-level gate can only leave a task as protected as its own agent already
 * was.
 *
 * The residual, stated plainly: an agent whose task-level override was
 * *stricter* than its agent-level gate loses that extra strictness if the
 * operator rewrites the document without it. That is a narrowing of one task's
 * special protection, never an ungated agent, and the write is still approved
 * by a human who can see the whole document.
 *
 * An escalation flag would NOT have been sufficient — `escalation-flags.ts` is
 * an attention aid by its own explicit design ("not a security control"), and a
 * complete bypass of the approval mechanism is not something to defend with a
 * warning an approver can skim past. That is exactly why the guard blocks
 * Approve instead of annotating it.
 */
const WRITABLE_EXTENSION_STORES = WORKFLOW_EXTENSION_STORES;

/**
 * Read endpoints the operator is allowed to call.
 *
 * These are OpenAPI path templates copied verbatim from EDDI's spec.
 *
 * A typo or a renamed backend path binds ZERO tools rather than failing loudly,
 * so entries are checked against the REAL spec at activation time:
 * `useActivateOperator` fetches it and runs `findMissingEndpoints` over the
 * resolved scope BEFORE provisioning anything, refusing with the list of
 * missing paths. Note this is a runtime guard, not a CI one — no committed spec
 * fixture exists to check these against at build time, so a bad entry surfaces
 * when an admin activates, not when a test runs. `tool-scopes.test.ts` pins the
 * SHAPE of these entries (well-formed, no substituted ids, no duplicates), not
 * their existence in any real deployment's spec.
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
  // EDDI's own documentation, served read-only over REST since EDDI 6.2.0
  // precisely so an OpenAPI-generated agent picks it up as ordinary tools
  // (`DocsService`; see `docs/mcp-server.md` → "The same docs over REST" in the
  // EDDI backend repo). The `eddi://docs/*` MCP *resources* covering the same
  // files do NOT reach an agent — EDDI's own MCP client consumes tools and
  // never calls `resources/read` — so these two endpoints are the only way the
  // operator can read the platform's documentation. The runtime doc set is
  // smaller than the repository's, so the index has to be read rather than
  // assumed: hence both entries, not just the by-name one.
  //
  // These two entries also set the Manager's backend floor: findMissingEndpoints
  // hard-refuses activation when the deployment's spec lacks an allow-listed
  // path, so operator activation now requires EDDI >= 6.2.0 unconditionally
  // (previously only caller-identity auth did). Deliberate — accepted over an
  // optional-endpoints validation tier, which would let the prompt promise a
  // docs capability the agent was silently never granted.
  "GET /administration/docs",
  "GET /administration/docs/{name}",
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
 * - `POST`/`PUT` on `rulestore`, `outputstore`, `propertysetterstore`,
 *   `dictionarystore`, `apicallstore`, `mcpcallsstore`, and `workflowstore` —
 *   behavior rules, output messages, slot-filling, NLU dictionaries, HTTP and
 *   MCP tool wiring, and which of those a workflow's pipeline actually runs,
 *   in order. **None of these documents carry a `hitlConfig` or any other
 *   field that gates a write** — verified field-by-field against the backend
 *   models, not assumed. A bad edit here is reviewable and reversible exactly
 *   like any other config change; it cannot touch the gate that is reviewing
 *   it.
 * - `POST`/`PUT` on `llmstore` — the agent's prompt, model and tool switches.
 *   This is the ONE writable store whose document CAN carry a gate
 *   (`Task.toolApprovals`), so it is bound only in combination with
 *   `gate-guard.ts`, which hard-refuses any llmstore write carrying that field.
 *   See {@link WRITABLE_EXTENSION_STORES} for the full reasoning and the
 *   residual. Do not grant this without that guard.
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
 * `PUT /agentstore/agents/{id}` and `PUT /groupstore/groups/{id}` — the
 * full-document updates that carry a gate of their own
 * (`AgentConfiguration.hitlConfig.toolApprovals`; `AgentGroupConfiguration
 * .hitlConfig` plus each `DiscussionPhase.requiresApproval`). A create can be
 * checked in isolation — "does this new document have a gate" needs no prior
 * state — but a full-document *update* cannot: "was the gate just weakened" is
 * a diff question, and nothing here has a prior version to diff against.
 *
 * `llmstore` writes ARE granted, and are the one exception to that rule: its
 * `Task.toolApprovals` carries a gate too, but the exception is bought by a
 * separate control rather than by an argument. `gate-guard.ts` refuses any
 * llmstore write that carries the field, or that cannot be shown not to —
 * which converts "was the gate weakened" from an unanswerable diff question
 * into an answerable property of the body alone. Do not grant `llmstore`
 * without that guard, and do not weaken the guard while this stays granted.
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
  // The hop that makes every other authoring write actually take effect. EDDI
  // never mutates in place — each PUT writes version + 1 — so editing a rule set
  // produces rules K+1, repointing the workflow produces workflow M+1, and
  // WITHOUT this the deployed agent still references workflow M and runs rules
  // K. The edit is a silent no-op that reads back as success.
  //
  // Safe where a full `PUT /agentstore/agents/{id}` is not: it @Consumes
  // TEXT_PLAIN and its whole body is one bare URI, so it is structurally
  // incapable of carrying a hitlConfig. It prefix-matches the resource
  // reference and produces a new agent version with everything else — the gate
  // above all — copied forward untouched.
  //
  // It does complete a self-ungating chain, which is why `self-guard.ts` exists:
  // the operator could repoint its OWN workflow's LLM step at an llmstore
  // document carrying a permissive Task.toolApprovals and then redeploy itself
  // through the already-granted deploy verb. Editing its own workflow alone is
  // inert (the agent still references the old version); it is precisely THIS
  // endpoint, aimed at its own agent, that would close the loop.
  "PUT /agentstore/agents/{id}/updateResourceUri",
  ...WRITABLE_EXTENSION_STORES.flatMap((store) => [`PUT /${store}/{id}`, `POST /${store}`]),
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
 * Sent for `read_only` too, where it gates zero real tools — that scope resolves
 * to `READ_ENDPOINTS` alone, so there is no `http.post`/`put`/`patch`/`delete`
 * tool for the patterns to match. It is still installed, and deliberately: it is
 * a REAL, verifiable document, `read_write` reuses the identical config
 * unchanged, and every operator agent this screen has ever created is provably
 * running the same gate shape from day one, not just the ones activated after
 * writes shipped.
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
 * Whether the `read_write` scope can be offered.
 *
 * This used to be a four-fact precondition (backend accepts `hitlConfig`, gate
 * verified on every version of the PREVIOUS operator, caller-identity auth, a
 * mounted approval surface) — which made write access a two-step bootstrap:
 * activate read-only, come back, reconfigure. That gated the OFFER on facts the
 * activation pipeline now proves about the operator it is actually creating,
 * which is strictly stronger evidence than a verification remembered from a
 * predecessor agent:
 *
 * - `useActivateOperator` reads the gate back from the just-provisioned
 *   document (`verifyGateInstalled`) — the old "backend accepts hitlConfig" and
 *   "gate verified" facts, proven about the right agent.
 * - `enforceWriteCanaryGate` then proves EMPIRICALLY that a real gated write
 *   pauses, and rolls the whole activation back (undeploy, delete, clear the
 *   config variable) on anything but a clean pause. A first-activation write
 *   grant therefore cannot survive a broken gate — it is refused, not risked.
 * - The approval surface is unconditionally mounted (`ApprovalBanner` renders
 *   whenever a conversation pauses, for every active operator).
 * - Caller-identity is no longer demanded here: on an OIDC deployment,
 *   `authMode: "none"` cannot activate at all (the form blocks it — tool calls
 *   would 401), so every write-capable operator there runs as the caller
 *   anyway; on a no-auth deployment there are no identities to attribute
 *   approvals to in the first place, and refusing writes because of that would
 *   make the scope permanently unreachable exactly where EDDI is being
 *   evaluated.
 *
 * What remains is the one fact activation cannot establish: there must be
 * something to grant. `false` only while `WRITE_ENDPOINTS` is empty.
 */
export function isWriteScopeAvailable(): boolean {
  return WRITE_ENDPOINTS.length > 0;
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
 * actually granted, not what a scope name implies. `read_write` grants exactly
 * what `WRITE_ENDPOINTS` holds at the time — nothing at all if it were ever
 * emptied — and anything derived from this, the operator's own system prompt
 * above all, must describe that rather than an intent.
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
 * Whether the granted endpoints can change an existing agent's behavior,
 * outputs, tool wiring, or pipeline — any workflow or writable
 * workflow-extension store's update verb.
 *
 * Checks {@link WRITABLE_EXTENSION_STORES} rather than the full read list, so
 * this predicate tracks what is actually writable by construction instead of by
 * a comment that can rot. That set currently includes `llmstore/llms`, which IS
 * granted and IS reported here — its gate-carrying `Task.toolApprovals` field is
 * neutralised by `gate-guard.ts`, not by withholding the endpoint. Should the
 * store ever leave the writable set, this answer follows automatically.
 *
 * Deliberately silent on `PUT /agentstore/agents/{id}` and
 * `PUT /groupstore/groups/{id}`: neither is ever granted, so checking for them
 * here would just be dead code describing a capability that cannot exist.
 */
export function grantsAgentModification(endpoints: readonly string[]): boolean {
  const set = new Set(endpoints);
  return (
    set.has("PUT /workflowstore/workflows/{id}") ||
    WRITABLE_EXTENSION_STORES.some((store) => set.has(`PUT /${store}/{id}`))
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
