# Security gaps: canary tokens, role-filtered MCP tools, token exchange, step-up auth

**Status:** Proposal, revision 2 (2026-11-21), revised after an adversarial review. Nothing here is built.
**Origin:** A comparison of EDDI against the security patterns presented in Quarkus Insights #267/#268 (guardrails, zero-trust MCP auth, agent sandboxing).
**Read with:** [`guardrails-architecture.md`](guardrails-architecture.md), [`saas-connectors-plan.md`](saas-connectors-plan.md) (§12 "What I would NOT do" binds this plan), [`mcp-oauth-protected-resource-plan.md`](mcp-oauth-protected-resource-plan.md), [`docs/tool-call-guardrails.md`](../docs/tool-call-guardrails.md), [`docs/connections.md`](../docs/connections.md), [`docs/hitl.md`](../docs/hitl.md).

> **Provenance.** **(verified)** means checked in this repository, or in the jars it builds against, with a file reference. **(external)** means the claim comes from the video summary and has not been checked against the MCP specification, LangChain4j or Keycloak; check it before relying on it (AGENTS.md §2 rule 7). **(unverified)** means an open question that must be settled before or during the build.

## 0. Out of scope: agent sandboxing

ISX-style system-container sandboxes and credential-injecting TLS proxies guard an agent that runs arbitrary code with credentials in its environment. EDDI does neither:

- **Nothing runs code or processes** (verified: no `ProcessBuilder`, `Runtime.exec` or `ScriptEngine` in `src/main/java`). Some things do evaluate text, but none of them is a general-purpose runtime:
  - `SafeJsonExpression` and `SafeMathParser`, recursive-descent parsers.
  - JsonPath in behavior-rule conditions (`modules/rules/impl/conditions/ContextMatcher.java:237`).
  - Qute templating, where runtime templates run on the restricted engine (no `config:`/`inject:`/`cdi:` namespaces, no `{#eval}`/`{#include}`; [`docs/security.md`](../docs/security.md)).
- **Configs and the model hold credential references, never values.** `${vault:…}` / `${connection:…}` are resolved per request at the HTTP layer, behind the SSRF policy and a connection's `baseUrlAllowlist`. That is the property the TLS proxy adds, already built in. Two limits on that claim:
  - Resolution is not yet in one place: `httpcalls.json` and the langchain4j model builders still have their own HTTP clients, pending consolidation onto `OutboundHttp` (AGENTS.md "Reusable Infrastructure").
  - Response bodies do reach the model. An API or MCP server that returns a secret in its response hands it to the model. That is a data-handling concern for the API, and `toolResultGuardrails` covers it only partly. Sandboxing would not address it either.
- **stdio MCP servers are rejected** (verified, [`docs/mcp-client.md`](../docs/mcp-client.md) "Why not stdio"). The supported route is a bridge sidecar in its own container, which already is the sandbox.

Revisit this if EDDI adds a code-interpreter tool or native stdio MCP ([`saas-connectors-plan.md`](saas-connectors-plan.md) §12 allows that only behind an admin template catalog). At that point an isolation layer becomes a requirement.

## 1. Canary guardrail (system-prompt leak detection)

### Gap

`prompt-injection` catches the request ("reveal your system prompt"). Nothing catches the leak itself (verified: no canary or leak type among `modules/llm/guardrails/impl`).

### What it is honestly worth

A canary catches **verbatim** leaks of a marker. An encoded, translated or paraphrased leak passes, and so does a leak that copies the prompt but leaves out the marker line. Treat it as a cheap tripwire that produces an audit signal, not as confidentiality for the prompt. The marker is low-value by design: it grants no access, and knowing it only lets someone trigger the guardrail. This framing matters for the storage question below.

### Injection: one helper, after the cap

The system message is assembled in `LlmTask` in this order (verified):

1. Template.
2. RAG context.
3. Identity masking (~601).
4. The counterweight and response-format blocks.
5. `capSystemPrompt` (`LlmTask.java:638`, body at `:3829`), which keeps `substring(0, maxChars)`.

A marker appended before step 5 is silently cut from any long prompt. So:

- `CanaryMarker.apply(systemMessage, task, memory)` is a single shared helper, called **after** `capSystemPrompt`. It replaces the tail of the prompt when needed so that the marker always survives. Wherever a system message is built, it goes through this helper:
  - the normal turn path;
  - `ToolLoopResumer.fallbackRebuildMessages` (verified: it rebuilds from the raw `systemMessage` parameter at `:543-562`, so today that path would carry no marker);
  - any cascade step that rebuilds messages.
- The tool loop and cascade reuse the message list built once, so they inherit the marker. A test per path pins this: normal, cascade, tool loop, resume, degraded resume, streaming.
- Side calls do not carry the system prompt and so need no marker: the summarizer, the title generator and the convergence judge. Group member turns are ordinary `LlmTask` turns and are covered. (unverified: confirm each side-call builder while implementing.)

### Token scope

| `scope` | Token | Prompt caching | Cost |
| --- | --- | --- | --- |
| `conversation` (default) | `HMAC(deploymentKey, agentId ‖ conversationId)`, truncated | Works within a conversation. Ends cross-conversation reuse of a cached system prompt for that agent | On Anthropic with `cacheSystemMessages`, the first turn of every conversation pays full system-prompt input price |
| `agent` | `HMAC(deploymentKey, agentId)` | Unchanged | A marker leaked once is valid for every conversation of the agent |
| `call` | Random per model call | None | Must be carried through a HITL pause; for high-sensitivity agents only |

- `deploymentKey` comes from the vault, following the `VaultSaltManager` pattern. It is never a config field.
- `GuardrailContext` carries `conversationId` and `agentId`, but documents them as "for logging only" (`GuardrailContext.java:22-25`) and has no agent version (verified). The canary needs a deliberate exception to that rule, or a new `canaryToken` field set by `LlmTask`. **Prefer the field:** the token is computed in one place and passed to the detector, and nothing re-derives it. This is also why there is no `agentVersion` scope.

### Storage: scrub at the sinks

Revision 1 said the token is never stored. That was false (verified):

- `LlmTask.java:1216`, `:2507` and `:2910` store the compiled system prompt under `AUDIT_COMPILED_PROMPT`.
- Every tool pause serializes the full message list, system message included, into `PendingToolCallBatch.chatTranscriptJson` (`orchestration/ToolApprovalGateSupport.java:347`; `ChatTranscriptCodec` has no system-message filter).

So:

- `AUDIT_COMPILED_PROMPT` stores the prompt with the marker line replaced by `[canary]`. The audit ledger shows that a canary was armed without carrying a usable marker.
- The pause transcript **keeps** the marker. The resume needs the exact system message, and that store already holds the whole system prompt, which is more sensitive than the marker. Under `call` scope this is also what carries the token across the pause. Document it rather than invent a second scrub-and-restore path.
- The marker never goes to SSE, the conversation snapshot, or metrics labels.

### Detection

- Phases `OUTPUT` and `TOOL_CALL`. The output check needs nothing from the author once the type is listed. Screening tool calls requires a `toolCallGuardrails` entry with `tools: ["*"]`. Without one, a leak through a tool argument (`http.post` to a third party) is not caught. The Manager editor should offer both entries together.
- Not covered, and stated as such in the docs:
  - Leaks into `postResponse` properties or long-term user memory, which are written from tool results and not screened.
  - A user who has learned the marker pasting it into input. That stays in history, and every later answer that quotes it would trip the guardrail. So: run the canary type in phase `INPUT` too, in mode `warn`. It records the event and does not refuse, so a user cannot lock a conversation by quoting the marker.
- Model-backed guardrails (judge, classifier) may forward the system message to a third-party model (unverified). If they do, the marker is sent with it. Check `GuardrailModelClient` before building. If it does, strip the marker line before the call.

### Actions

`block` only, enforced at save time as `disclosure` enforces `redact`. `redact` would remove the marker and deliver the rest of the leaked prompt, which looks safe and is not. Buffering follows from `block`: `requiresBuffering` is true (verified), so a streamed answer is held until it is screened, as for every other blocking output guardrail.

### Companion, deferred: `system-prompt-overlap`

N-gram overlap between the answer and the system message, which `GuardrailContext.messages()` carries. It will produce false positives as designed: the system message contains RAG context, the rolling summary and the response schema, which a correct answer echoes. Build it only if it can overlap against the author-written template part alone, which means passing that part separately. Not part of the first delivery.

### Config sample

```json
"inputGuardrails":    [ { "type": "canary", "action": "warn" } ],
"outputGuardrails":   [ { "type": "canary", "action": "block",
                          "parameters": { "scope": "conversation" },
                          "message": "I can't share that." } ],
"toolCallGuardrails": [ { "tools": ["*"], "type": "canary", "action": "block" } ]
```

### Compatibility

An unknown guardrail type is skipped with a warning (`GuardrailService.java:1028`) (verified). So an older EDDI, or a rollback, silently runs agents without the canary. Say this in the docs' Compatibility section, as [`tool-call-guardrails.md`](../docs/tool-call-guardrails.md) does for 6.7.

## 2. Role-aware MCP tool visibility

### Gap

`McpToolFilter.test()` is `MCP_TOOLS.contains(toolInfo.name())` (verified), so every caller lists every tool. Role tiers are checked inside each tool with `requireAnyRole(identity, authEnabled, McpRoles.<TIER>)`.

### The filter also gates calls

Revision 1 called this "disclosure, not enforcement". That was wrong. In quarkus-mcp-server 2.0.4, the `tools/call` path (`ToolManagerImpl.getInvoker`) calls the same `ToolFilter.test` as listing (verified with `javap`). A role-aware filter would therefore:

- answer "tool not found" instead of `FORBIDDEN` for a hidden tool, which breaks the error contract in [`docs/mcp-server.md`](../docs/mcp-server.md) ("a missing role → `errorCode: FORBIDDEN`");
- turn any wrong tier entry into a lockout that `McpRoleTierParityTest` cannot see, because the test calls the tool methods directly.

**Design:** apply the role filter only to `TOOLS_LIST`. `FilterContext.method()` returns `McpMethod.TOOLS_LIST` / `TOOLS_CALL` (verified). For `TOOLS_CALL` the filter keeps today's name check, and the in-tool role check stays the only enforcement. Add a test through the real MCP handler proving that a call to a tool hidden from the caller's list still returns `FORBIDDEN`, not "not found".

### One source of truth for tiers

- Add `McpToolTiers`, a `Map<String, List<String>>` beside `McpRoles`. It is used by the list filter, by the tool methods (replacing the literal in each method), and by `McpRoleTierParityTest`, which then asserts that the measured tier equals the map entry.
- Tools whose tier depends on their arguments are listed under the **union** of their tiers. `read_agent_logs` is one (verified, comment in `McpRoleTierParityTest`). Showing a tool that then refuses some arguments is fine; hiding a tool a caller can use is not.
- HITL tools are listed by the HITL tier. The per-conversation `HitlAccessGuard` cannot be decided at list time.
- The parity test javadoc says 107 tools and the docs say 120. Reconcile the count as part of this change.

### Identity inside the filter

`FilterContext` carries no identity (verified with `javap`). The extension's operation path calls `contextStart()`, which sets the current identity (verified in review, not tested), so injecting `SecurityIdentity` into the filter bean will probably work for `tools/list` over HTTP. **Prove it with a test first.** If it fails, stop and raise it with the extension; do not key anything on the connection id.

### Caching and paging

`ToolMessageHandler.toolsList` honours `tools().ttlMs()` and `cacheScope()` and paginates (verified in review). A list that differs per caller must not be cached across callers. Pin the extension's cache to per-connection scope, or disable it, and test that paging over a filtered list is stable.

### Clients that see a different list

- `/mcp` cannot be opened selectively. An authenticated EDDI already refuses an unauthenticated `tools/list` ([`docs/mcp-server.md`](../docs/mcp-server.md):898), so nothing changes there.
- What does change: EDDI's own MCP client lists tools with the config's static key and calls them as the caller ([`docs/mcp-server.md`](../docs/mcp-server.md):1046-1049). With role filtering, a static key held by a low-tier service account lists fewer tools than the caller may use. Document that **the listing key determines the tool set**.
- Platform Operator: `tool-scopes.ts` allows REST endpoints and does not use EDDI's MCP surface (verified in review). No operator change and no revision bump.

### Auth off

The filter changes nothing. This is the dev posture; production is guarded by `AuthStartupGuard`.

## 3. OAuth 2.0 token exchange (RFC 8693) as a connection type

### What it is, and what it replaces

**Correction to the first comparison:** `${caller:token}` is not the passthrough the MCP spec warns about. It is released only to the caller's own origin or to EDDI itself (`CallerIdentityResolver`) (verified). It stays exactly as it is ([`saas-connectors-plan.md`](saas-connectors-plan.md) §12 and §5: never relax it).

Token exchange covers a different case: calling a downstream API protected by **the same IdP**, as the signed-in user, without a per-user consent flow. Today that needs:

- `PER_USER`: each user links an account through the authorization-code flow; or
- `CALLER_SUPPLIED`: the integrator passes in a second credential.

With exchange, EDDI sends the user's inbound token, with its own client credentials, to the IdP and gets back a token for the downstream audience. The result is meant to carry the user (`sub`) and EDDI as actor (`act`) (external: check against RFC 8693 §4.1 and the IdP in use).

### Trust boundary: the "two trust domains" rule

[`saas-connectors-plan.md`](saas-connectors-plan.md) rejects making "one credential fungible across two trust domains". The exchange keeps to that because the subject token goes back only to the service that issued it:

- **Exact token-endpoint match.** The subject token may be sent only to the `token_endpoint` from the OIDC discovery document of the tenant that validated it. Not "the same host", and not a value derived from `auth-server-url`: `quarkus.oidc.token.issuer` can differ from `auth-server-url` (`application.properties:938`), and the existing derivation assumes Keycloak (`:1009-1017`) (verified in review).
- **Checked when the connection is saved and again on every resolve.** ZIP import and instance sync skip save-time rules, so the resolve-time check is the real one.
- The downstream API never sees the EDDI token, only the exchanged one. Its audience is fixed by the connection (`oauth.audience` / `oauth.resource`) and its destination is bounded by `baseUrlAllowlist`.

### Other §12 rules, each addressed

| §12 rule | This design |
| --- | --- |
| "Not store a token anywhere outside `EnvelopeCrypto`" | **Explicit, narrow carve-out:** exchanged tokens live only in a bounded in-memory cache and are never persisted. Persisting them would be worse: they are short-lived and derived from a token EDDI does not store either. §12 gets a line recording the exception |
| No sixth bespoke `HttpClient` | Uses the existing OAuth token client on the shared outbound stack |
| No `PER_USER` on LLM, embedding or vector-store connections | The exchange binding is refused there at validation time, for the same reason |
| "Never accept a client-supplied header as the grant principal" | The subject is the validated inbound token, never a header. The video's interim `X-MCP-*` identity headers (external) are not adopted |
| Who may reference the connection | As for `${caller:token}`: only trusted authors, i.e. whoever may edit agents and connections (`CallerIdentityResolver` javadoc ~43-47). State it in [`docs/connections.md`](../docs/connections.md) |

### Model

- `AuthType.OAUTH2_TOKEN_EXCHANGE` with a new `Binding.CALLER_EXCHANGED`.
- `isOAuth()` stays **false** for it. That predicate means "completes a flow and needs a stored grant" (`AuthType` javadoc) (verified), and exchange stores no grant. Add `usesTokenEndpoint()` and go through the four `isOAuth()` call sites one by one, deciding for each which predicate it actually means.
- Fields: `tokenUrl`, `clientId`, `clientSecret` (`${vault:…}`), `audience` and/or `resource`, `scopes`, `requestedTokenType` (default `access_token`).

### Cache

- **Key:** connection, `sub`, and a SHA-256 of the subject token. Use the hash, not `jti`, which not every IdP issues.
- **Expiry:** the earlier of the two tokens' `exp`, minus a skew margin.
- **Bounded size** (Caffeine) with LRU eviction.
- **Per replica.** Each replica exchanges on its own; there is no cluster sharing, because sharing would mean storing the tokens.
- **Revocation:** a logout or revocation at the IdP is not seen until the cache entry expires. That is acceptable only because the entries are short-lived; say so in the docs. Back-channel logout eviction is a follow-up.

### When no live caller token exists: fail closed

| Situation | Behaviour |
| --- | --- |
| Synchronous turn from an authenticated caller | Exchange the caller's token |
| Scheduled fire, continuation, async group turn | `ConnectionException` (no subject). The same posture as `PER_USER` with `SELF_ASSERTED` provenance |
| `/v1` OpenAI-compatible API | Kept off OIDC (`application.properties:652`), so there is no subject. Refused at resolve time with a clear message |
| HITL resume | **Fail closed by default.** Exchanging the approver's token would run the requester's call with the approver's (often admin) rights downstream: a confused deputy. The only exception is when the decider is the conversation owner, which is the same principal. This matches [`saas-connectors-plan.md`](saas-connectors-plan.md) ~620-640 for `CALLER_SUPPLIED` and the existing fail-closed resumes in [`docs/hitl.md`](../docs/hitl.md):218 |
| Group member turn on the caller's thread | Exchange only when the member conversation's provenance is `VERIFIED` for that user. Otherwise refuse. The open group-binding question in [`docs/connections.md`](../docs/connections.md) Limitations applies unchanged |

### Observability

- Metrics: exchange count, failures by error, cache hits and misses.
- Audit entry per resolve: connection, `sub`, audience, `act` when present. **Never the token.**

### Open questions (unverified)

- Standard token exchange in Keycloak 26.8.0 (`docker-compose.auth.yml:90`): whether it is enabled by default and what it requires of the realm.
- Whether `act` survives a second exchange hop. If not, record the gap in the audit entry rather than reconstructing it from headers.

## 4. Step-up authentication on tool approvals

### Fit

A mid-turn "authenticate more strongly" challenge is a pause followed by a resume, and EDDI has that already: tool-level HITL approval. Step-up becomes **approving requires a stronger login**, possibly by the owner themselves.

Quarkus OIDC 3.40.1 ships `@AuthenticationContext(value, maxAge)` and `StepUpAuthenticationPolicy` (verified in the jar). Its challenge format (`insufficient_user_authentication`) is (external); confirm it against the Quarkus OIDC guide for 3.40 before the UIs depend on it.

### Config

`toolApprovals.rules[].requiredAuthLevel: { "acr": ["mfa", "hwk"], "maxAge": "PT5M" }`. The `acr` list is the set of acceptable values; the token's `acr` must be one of them.

- It adds friction and never gates or ungates ([`docs/hitl.md`](../docs/hitl.md):405) (verified). Whether a call pauses is still decided only by `requireApproval`/`exempt`. A rule with `requiredAuthLevel` whose match is not covered by `requireApproval` is refused at save time.

### The batch keeps a separately merged auth level

`ToolApprovalRules.governing` reduces a batch to one whole rule, chosen by timeout-policy strictness and then specificity (`ToolApprovalRules.java:85-96`) (verified). Riding on `effectiveRule` would drop a `requiredAuthLevel` from a losing rule. For example, an MFA-gated call batched with a `WAIT_INDEFINITELY` call would lose step-up entirely. So:

- `PendingToolCallBatch.effectiveAuthLevel` is a separate persisted field, merged field by field across **all** matched rules:
  - `acr`: the **intersection** of the acceptable sets. ACR values are unordered strings, so "highest" does not exist. An empty intersection is a configuration conflict, and the batch fails closed: it can only be rejected.
  - `maxAge`: the minimum.
- It goes through the persistence round trip. Test it with a store round trip, not in memory only.

### Decision path

- **Only approval needs step-up. Rejection never does.** Anyone with the right to decide may always say no, including through Slack and MCP.
- An approval checks the decider's token: `acr` is in the merged set, and `auth_time` is within `maxAge`. This check runs in the HITL decision service, per batch. (unverified: confirm that the resume endpoint has the decider's access token with `acr` and `auth_time`, not just a `SecurityIdentity` built from roles. Keycloak puts `acr` in access tokens; `auth_time` may need a mapper.) The failure response reuses Quarkus' step-up challenge, so the Manager and Chat UI can re-authenticate with `acr_values` and `max_age` and return to the approval card.
- `eddi-approver` / `eddi-admin` deciding on someone else's conversation need the same level. Step-up is about the decision, not about whose conversation it is.

| Approval surface | Approve | Reject |
| --- | --- | --- |
| Manager / Chat UI (OIDC session) | After step-up | Always |
| Slack approvals (no OIDC token) | `STEP_UP_REQUIRED` | Always |
| MCP `resume_conversation` (offline token, old `auth_time`) | `STEP_UP_REQUIRED` unless the token is fresh | Always |
| Timeout `AUTO_APPROVE` | Never takes effect | — |
| `system:*` automated approvals ([`docs/hitl.md`](../docs/hitl.md):322) | Never take effect | — |

### Automated approvals: check at decision time too

A save-time refusal of `AUTO_APPROVE` on such rules is not enough. Scalar and task-level settings merge with agent-level ones (strict mode), and automated `system:*` decisions exist. So **the decision-time check is authoritative:** any approval without a qualifying human token is converted to `AUTO_REJECT` and audited as such. The save-time check stays as early feedback.

### Auth off

`requiredAuthLevel` cannot be met, because there is no token. **Refuse to deploy** an agent that sets it while `authorization.enabled=false`, and say so in the error. Silently ignoring it would teach that the gate works.

### UI and realm

- Manager and Chat UI handle the challenge.
- The shipped realm import gets an ACR-to-LoA mapping and a conditional OTP step, as an example.

## 5. Cross-cutting

### Tests

Unit tests with JUnit 5 and Mockito, plus ITs where a real store or HTTP is needed:

| Item | Tests |
| --- | --- |
| §1 | Marker present on each path (normal, cascade, tool loop, resume, degraded resume, streaming); survives `capSystemPrompt`; scrubbed in `AUDIT_COMPILED_PROMPT`; `redact` refused at save; input `warn` does not refuse; mutation check (remove the injection, so the leak test must fail) |
| §2 | List filtered per role; a call to a hidden tool still returns `FORBIDDEN` through the real handler; parity test reads `McpToolTiers`; union tier for argument-dependent tools; no cross-caller caching; paging over a filtered list |
| §3 | Token endpoint mismatch refused at save and resolve (via ZIP import); every fail-closed row; cache expiry and bound; LLM/embedding/vector binding refused; IT against Keycloak for the real exchange |
| §4 | Field-wise merge across a mixed batch; empty `acr` intersection; persistence round trip of `effectiveAuthLevel`; approve refused and reject allowed on Slack/MCP; decision-time conversion of automated approvals; deploy refused with auth off |

### Docs and operator

- §1 → [`docs/tool-call-guardrails.md`](../docs/tool-call-guardrails.md) plus the output-guardrail page.
- §2 → [`docs/mcp-server.md`](../docs/mcp-server.md) (Tool Filtering, the tool count).
- §3 → [`docs/connections.md`](../docs/connections.md).
- §4 → [`docs/hitl.md`](../docs/hitl.md).
- A `docs/changelog.d/` fragment per item.
- Check the Platform Operator for §1, §3 and §4, which add config fields an admin will ask about. Any prompt change is a revision bump in `operator-revision.json`. §2 needs none (see above).

### Manager editors

§1 needs the guardrail type in the guardrail editor (output and tool-call together). §3 needs the connection type and binding. §4 needs the rule field and the re-authentication flow in the approval card.

### Suggested order

| # | Item | Size | Gate before starting |
| --- | --- | --- | --- |
| 1 | `McpToolTiers` single source (§2) | S | None; removes tier drift on its own |
| 2 | Canary guardrail (§1), without the overlap companion | M | Check `GuardrailModelClient` for system-message forwarding |
| 3 | Role-aware `tools/list` (§2) | S | Identity-in-filter spike passes |
| 4 | Step-up on approvals (§4) | M–L (backend, two UIs, realm) | Confirm the resume path sees `acr` / `auth_time` |
| 5 | Token exchange (§3) | M–L | Keycloak 26.8 exchange check; §12 carve-out agreed |
