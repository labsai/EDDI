# Security gaps: canary tokens, role-filtered MCP tools, token exchange, step-up auth

**Status:** Proposal, revision 2 (2026-11-21), revised after an adversarial review, then fact-checked against `main` @ `a2acb28e86` the same day (corrections are marked *Fact-check*). Nothing here is built yet.
**Origin:** A comparison of EDDI against the security patterns presented in Quarkus Insights #267/#268 (guardrails, zero-trust MCP auth, agent sandboxing).
**Read with:** [`guardrails-architecture.md`](guardrails-architecture.md), [`saas-connectors-plan.md`](saas-connectors-plan.md) (§12 "What I would NOT do" binds this plan), [`mcp-oauth-protected-resource-plan.md`](mcp-oauth-protected-resource-plan.md), [`docs/tool-call-guardrails.md`](../docs/tool-call-guardrails.md), [`docs/connections.md`](../docs/connections.md), [`docs/hitl.md`](../docs/hitl.md).

> **Provenance.** **(verified)** means checked in this repository, or in the jars it builds against, with a file reference. **(external)** means the claim comes from the video summary and has not been checked against the MCP specification, LangChain4j or Keycloak; check it before relying on it (AGENTS.md §2 rule 7). **(unverified)** means an open question that must be settled before or during the build.

## Second review (2026-11-21): open design gaps and corrections

A second adversarial review followed the fact-check. Its corrections to facts are written into the sections as *Second review* notes: the filter fails open (§2), there is no server-side list cache (§2), and the agent path builds its own system message (§1). The points below are **design gaps**. They are not decided here; each needs a decision before its item is built.

- **§1, `call` scope across a pause.** The detector gets its token from `GuardrailContext.canaryToken`, set by `LlmTask`. On resume the restored system message holds the *old* random token, so a freshly generated one never matches, and the canary is silently disarmed. When the transcript was omitted (`ToolApprovalGateSupport.java:347-349`), `fallbackRebuildMessages` has no marker at all. Either recover the token from the transcript, or persist it with the pause, or drop `call` scope.
- **§1, poisoning under `agent` scope.** "Knowing the marker only lets someone trigger the guardrail" understates it. Under `agent` scope, one leaked marker planted in shared content (a RAG document, a fetched page, a tool result, long-term memory, a group transcript) makes every answer that quotes it `block`, for every user of the agent. The INPUT `warn` does not help: it screens only user messages (`GuardrailService.screenHistory`, `:521-530`), and it re-screens earlier user messages each turn, so one pasted marker repeats the audit event on every turn.
- **§1, audit scrub target.** `:1216` stores the system prompt *before* the summary is composed. If the marker goes after the summary, `:1216` never contains it. Then the scrub is moot, and the audit does not show what the model saw. Decide which prompt the audit records.
- **§1, actions.** "`block` only" means for OUTPUT and TOOL_CALL. The INPUT entry is `warn` by design.
- **§4, step-up can be dropped per call.** `ToolApprovalRules.compile` (`:149-161`) sorts rules most-specific-first, and `firstMatch` returns one rule per call. If `http.*` sets `requiredAuthLevel` and a narrower `http.post:/agentstore/agents` only tunes `timeoutPolicy`, that call loses step-up. Merge `requiredAuthLevel` across every rule that matches a call, or warn at save time about shadowed rules.
- **§4, per-call decisions.** Batches are decided per call (`HitlDecision.getToolDecisions()`), and edited approvals exist (`APPROVED_EDITED`). A batch-level `effectiveAuthLevel` would demand step-up from someone who approves only the harmless call and rejects the gated one, which contradicts "only approval needs step-up". Compute the level over the calls being approved, edited ones included. "Empty intersection → reject only" is then also too broad when a subset could be approved.
- **§4, auth off.** Refusing to deploy an agent with `requiredAuthLevel` when auth is off also refuses a production agent imported into a dev instance. State that trade-off.
- **§4, challenge.** "The failure response reuses Quarkus' step-up challenge" means matching its format. EDDI emits the challenge itself (see the §4 fact-check).
- **§3, re-pointing an existing reference.** `CallerTokenAuthorPolicy` matches the literal `${caller:token}` (`:80`, `:125`). An exchange connection is referenced as `${connection:name}`, which carries no author rule. An editor saves an agent that references connection `crm` while it is `API_KEY`. An admin later changes `crm` to `OAUTH2_TOKEN_EXCHANGE`. Every existing editor-authored reference now spends the token of whoever chats, admins included: the confused deputy the `CallerIdentityResolver` javadoc (`:52-59`) describes. Needed: a resolve-time author check, or re-validation of referencing configs when a connection's type changes.
- **§3, more sites than `isOAuth()`.** A new `AuthType` and `Binding` also reach the binding matrix in `ConnectionConfiguration.java:258-272` and `:425-430`, `OAuthTokenService:658` and `RestConnectionAuthorization:143`/`:558`. `OAuthConfig` has `audience` (`:110`) but no `resource` field yet.
- **§1, compatibility with a pre-6.7 EDDI.** An EDDI that does not know `toolCallGuardrails` at all behaves as Jackson treats unknown properties. Not verified.
- **Citations.**
  - `ToolCallGuardrailValidation.java:28-31` is the class javadoc; the refusal code is at `:82-86`.
  - `system:*` tool decisions are described at `docs/hitl.md:192` and `:485`; `:322` is about group TASK-gate budgets.
  - `docs/mcp-server.md:898` says `/mcp` cannot be opened selectively; it does not itself say an unauthenticated `tools/list` is refused.
  - §12's wording is "Not accept a client-supplied header as the grant principal — ever."
- **Not yet checked:**
  - whether `"*"` passes `ToolApprovalPatterns.validate`;
  - the `token_endpoint` host in Keycloak's discovery document under `KC_HOSTNAME` (it matters for the exact-match rule);
  - how a caller token survives the NATS coordinator running a turn on another replica;
  - the streaming path's system-message source;
  - that the side-call builders carry no system prompt.

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

> *Fact-check.* Two refinements. (a) The cap is opt-in: `maxSystemPromptChars` defaults to `-1`, and `capSystemPrompt` returns the prompt unchanged when `maxChars <= 0` (`LlmTask.java:3830`), so the cut happens only where an author set it. (b) There is a sixth, uncapped step: `ConversationHistoryBuilder.composeSystemMessage` (`:72-76`, called at `:125` and `:225`) appends the rolling summary *after* the cap. "After `capSystemPrompt`" must therefore mean after the summary is composed too, or the helper must run inside `ConversationHistoryBuilder`; otherwise the marker is not at the tail whenever a summary is active.

- `CanaryMarker.apply(systemMessage, task, memory)` is a single shared helper, called **after** `capSystemPrompt`. It replaces the tail of the prompt when needed so that the marker always survives. Wherever a system message is built, it goes through this helper:
  - the normal turn path;
  - `ToolLoopResumer.fallbackRebuildMessages` (verified: it rebuilds from the raw `systemMessage` parameter at `:543-562`, so today that path would carry no marker);
  - any cascade step that rebuilds messages.
- The tool loop and cascade reuse the message list built once, so they inherit the marker. A test per path pins this: normal, cascade, tool loop, resume, degraded resume, streaming.

> *Second review: the agent and tool-loop path builds its own string.* `LlmTask.java:822` composes `agentSystemMessage = composeSystemMessage(systemMessage, summaryPrefix)`, and `:823` strips the history builder's leading `SystemMessage` only when it is exactly equal (`withoutLeadingSystemMessage`). `agentSystemMessage` then goes to the cascade and the tool loop (`:889`, `:980`, `:992`, `:1025`). So "the tool loop and cascade inherit the marker" is false. Running the helper only inside `ConversationHistoryBuilder` would break this path: `:822` would recompose the prompt without the marker, the strip would miss, and the loop would receive two system messages. The consistent placements are inside `composeSystemMessage` itself, or on both the builder input and `:822`. Add tests for "rolling summary active" and "agent mode with a summary". Also, the helper runs after the cap and the summary is never capped, so nothing truncates the marker: drop "replaces the tail of the prompt", which would only destroy author text.
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

- `LlmTask.java:1216`, `:2507` and `:2910` store the compiled system prompt under `AUDIT_COMPILED_PROMPT`. *Fact-check:* only `:1216` stores the assembled prompt. `:2507` (`executeResume`) and `:2910` (`storeRevision`) store the templated `systemMessage` parameter, without RAG, counterweight, format blocks or a marker, so the scrub is moot there, and those audit entries do not show what the model saw.
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
- ~~Model-backed guardrails (judge, classifier) may forward the system message to a third-party model (unverified).~~ *Fact-check, answered: they do not.* `GuardrailModelClient.templateData` (`:280-287`) offers only `input` (the last user message), `output` (the screened text) and, opt-in, retrieved `context`; `ClassifierGuardrail.buildMessages` and `JudgeGuardrail.buildMessages` send no `SystemMessage`. The marker can reach such a model only inside a leaked answer it is screening, so order the canary before model-backed output guardrails.

### Actions

`block` only, enforced as `disclosure` enforces `redact` (*fact-check:* that is at save time for `toolCallGuardrails`, `ToolCallGuardrailConfigValidator.java:83-84`, but only at deploy time for `outputGuardrails`, `ToolCallGuardrailValidation.checkOutputDisclosure`; either follow both or add a save-time output check). `redact` would remove the marker and deliver the rest of the leaked prompt, which looks safe and is not. Buffering follows from `block`: `requiresBuffering` is true (verified), so a streamed answer is held until it is screened, as for every other blocking output guardrail.

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

*Fact-check:* that holds for input and output only. An unknown `toolCallGuardrails` type is **refused** (`ToolCallGuardrailValidation.java:28-31`), so on an older EDDI that knows tool-call guardrails, an agent with a canary tool-call entry fails to deploy instead of running unguarded. The Compatibility section needs both behaviours.

## 2. Role-aware MCP tool visibility

### Gap

`McpToolFilter.test()` is `MCP_TOOLS.contains(toolInfo.name())` (verified), so every caller lists every tool. Role tiers are checked inside each tool with `requireAnyRole(identity, authEnabled, McpRoles.<TIER>)`.

### The filter also gates calls

Revision 1 called this "disclosure, not enforcement". That was wrong. In quarkus-mcp-server 2.0.4, the `tools/call` path (`ToolManagerImpl.getInvoker`) calls the same `ToolFilter.test` as listing (verified with `javap`). A role-aware filter would therefore:

- answer "tool not found" instead of `FORBIDDEN` for a hidden tool, which breaks the error contract in [`docs/mcp-server.md`](../docs/mcp-server.md) ("a missing role → `errorCode: FORBIDDEN`");
- turn any wrong tier entry into a lockout that `McpRoleTierParityTest` cannot see, because the test calls the tool methods directly.

**Design:** apply the role filter only to `TOOLS_LIST`. `FilterContext.method()` returns `McpMethod.TOOLS_LIST` / `TOOLS_CALL` (verified). For `TOOLS_CALL` the filter keeps today's name check, and the in-tool role check stays the only enforcement. Add a test through the real MCP handler proving that a call to a tool hidden from the caller's list still returns `FORBIDDEN`, not "not found".

### One source of truth for tiers

- Add `McpToolTiers`, a `Map<String, List<String>>` beside `McpRoles`. It is used by the list filter, by the tool methods (replacing the literal in each method), and by `McpRoleTierParityTest`, which then asserts that the measured tier equals the map entry. *Fact-check:* once the methods read the map, "measured equals map" compares the map with itself, so the test must also keep its own hand-written tool-to-tier record and assert that the map equals it.
- Tools whose tier depends on their arguments are listed under the **union** of their tiers. `read_agent_logs` is one (verified, comment in `McpRoleTierParityTest`). Showing a tool that then refuses some arguments is fine; hiding a tool a caller can use is not.
- HITL tools are listed by the HITL tier. The per-conversation `HitlAccessGuard` cannot be decided at list time.
- The parity test javadoc says 107 tools and the docs say 120. Reconcile the count as part of this change. *Fact-check:* the docs and the whitelist are right at 120. The test asserts 119 because it never measures `McpVersionTools` (`get_eddi_version`, gated by a literal `requireRole(…, "eddi-admin")`), and its javadoc is stale.

### Identity inside the filter

`FilterContext` carries no identity (verified with `javap`). The extension's operation path calls `contextStart()`, which sets the current identity (verified in review, not tested), so injecting `SecurityIdentity` into the filter bean will probably work for `tools/list` over HTTP. **Prove it with a test first.** If it fails, stop and raise it with the extension; do not key anything on the connection id.

> *Second review: the filter must not throw.* quarkus-mcp-server 2.0.4 runs each `ToolFilter` inside a `RuntimeException` handler. On an exception it logs "Unable to apply filter", skips that filter and returns **true** (`ToolManagerImpl.test`, verified with `javap`), and `getInvoker` uses the same method for `tools/call`. An identity lookup that throws (`ContextNotActiveException`, say) would therefore show every tool, and on `TOOLS_CALL` would bypass today's name whitelist, which is the second line of defence against langchain4j `@Tool` beans. So the filter catches everything itself and returns `false`. A test makes the identity lookup throw and asserts the tool is hidden *and* not callable. A test that only proves identity works passes with this path still open.

### Caching and paging

`ToolMessageHandler.toolsList` honours `tools().ttlMs()` and `cacheScope()` and paginates (verified in review). A list that differs per caller must not be cached across callers. Pin the extension's cache to per-connection scope, or disable it, and test that paging over a filtered list is stable.

> *Second review: there is no server-side cache to pin.* `ttlMs` and `cacheScope` only go into the response as hints (`MessageHandler.putCacheControl`). `CacheScope` has exactly two values, `PUBLIC` and `PRIVATE`, and a stateless protocol version with nothing configured emits `PUBLIC` with `ttlMs: 0` (verified with `javap`). The action is to set `cacheScope` to `PRIVATE` once the list differs per caller, so a shared client or intermediary does not hand one caller's list to another. The §5 test "no cross-caller caching" becomes "a filtered `tools/list` response carries `cacheScope: PRIVATE`".

### Clients that see a different list

- `/mcp` cannot be opened selectively. An authenticated EDDI already refuses an unauthenticated `tools/list` ([`docs/mcp-server.md`](../docs/mcp-server.md):898), so nothing changes there.
- What does change: EDDI's own MCP client lists tools with the config's static key and calls them as the caller ([`docs/mcp-server.md`](../docs/mcp-server.md):1046-1049). With role filtering, a static key held by a low-tier service account lists fewer tools than the caller may use. Document that **the listing key determines the tool set**. *Fact-check:* without a static key, the client sends `initialize` and `tools/list` unauthenticated (deliberately, because the client is cached across callers), so against an authenticated EDDI it lists nothing at all, with or without role filtering.
- Platform Operator: `tool-scopes.ts` allows REST endpoints and does not use EDDI's MCP surface (verified in review). No operator change and no revision bump.

### Auth off

The filter changes nothing. This is the dev posture; production is guarded by `AuthStartupGuard`.

## 3. OAuth 2.0 token exchange (RFC 8693) as a connection type

### What it is, and what it replaces

**Correction to the first comparison:** `${caller:token}` is not the passthrough the MCP spec warns about. It is released only to the caller's own origin or to EDDI itself (`CallerIdentityResolver`) (verified). It stays exactly as it is ([`saas-connectors-plan.md`](saas-connectors-plan.md) §5.5: never relax it; *fact-check:* §12 does not mention it).

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
| Who may reference the connection | As for `${caller:token}`: only trusted authors. *Fact-check:* that is not every editor but a configurable role set, `eddi.caller-identity.author-roles`, enforced on save and import by `CallerTokenAuthorPolicy` (`CallerIdentityResolver` javadoc `:52-59`). Reuse that policy. State it in [`docs/connections.md`](../docs/connections.md) |

### Model

- `AuthType.OAUTH2_TOKEN_EXCHANGE` with a new `Binding.CALLER_EXCHANGED`.
- `isOAuth()` stays **false** for it. That predicate means "completes a flow and needs a stored grant" (`AuthType` javadoc) (verified), and exchange stores no grant. Add `usesTokenEndpoint()` and go through the `isOAuth()` call sites one by one (*fact-check:* three, not four: `RestConnectionStore.java:899`, `ConnectionResolver.java:321`, `ConnectionStartupGuard.java:181`; `isServiceAccount()` is an existing sibling predicate), deciding for each which predicate it actually means.
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
| `/v1` OpenAI-compatible API | Under the default `eddi.openai-compat.http-policy=permit`, kept off OIDC (`application.properties:651-655`), so there is no subject: refused at resolve time with a clear message. *Fact-check:* under `http-policy=authenticated` Quarkus OIDC validates the bearer token, so a subject does exist there. Whether to allow the exchange under that policy is an open decision |
| HITL resume | **Fail closed by default.** Exchanging the approver's token would run the requester's call with the approver's (often admin) rights downstream: a confused deputy. The only exception is when the decider is the conversation owner, which is the same principal. This is stricter than [`saas-connectors-plan.md`](saas-connectors-plan.md) `:621-650` for `CALLER_SUPPLIED` (*fact-check:* its decision at `:642` is "C, with A as the failure mode": the integrator re-supplies the credential on resume, and only failing that does the call fail) and the existing fail-closed resumes in [`docs/hitl.md`](../docs/hitl.md):218 |
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

> *Fact-check.* The platform is 3.40.1.2 (`pom.xml:18`). `@AuthenticationContext` is public API, but it is static per endpoint, so a per-batch, rule-driven `acr` set cannot be expressed with it. `StepUpAuthenticationPolicy` is a package-private record in `io.quarkus.oidc.runtime`, not an API EDDI can call. So EDDI implements the `acr`/`auth_time` check and emits the challenge itself, matching Quarkus' format. The jar does contain the literals `insufficient_user_authentication`, `acr_values` and `max_age`; the exact header layout is still to be confirmed.

### Config

`toolApprovals.rules[].requiredAuthLevel: { "acr": ["mfa", "hwk"], "maxAge": "PT5M" }`. The `acr` list is the set of acceptable values; the token's `acr` must be one of them.

- It adds friction and never gates or ungates ([`docs/hitl.md`](../docs/hitl.md):405) (verified). Whether a call pauses is still decided only by `requireApproval`/`exempt`. A rule with `requiredAuthLevel` whose match is not covered by `requireApproval` is refused at save time.

### The batch keeps a separately merged auth level

`ToolApprovalRules.governing` reduces a batch to one whole rule, chosen by timeout-policy strictness and then specificity (`ToolApprovalRules.java:92-101`) (verified). Note that `matchByCallId` (`:65-84`) takes the first matching rule per call, so "all matched rules" below means one rule per call in the batch. Riding on `effectiveRule` would drop a `requiredAuthLevel` from a losing rule. For example, an MFA-gated call batched with a `WAIT_INDEFINITELY` call would lose step-up entirely. So:

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
| §1 | Marker present on each path (normal, cascade, tool loop and agent mode, resume, degraded resume, streaming, rolling summary active); marker at the tail after the summary; scrubbed wherever the audit stores the prompt the model saw; `redact` refused where `disclosure` enforces it (output: deploy; tool-call: save); input `warn` does not refuse; `call` scope across a pause; mutation check (remove the injection, so the leak test must fail) |
| §2 | List filtered per role; a call to a hidden tool still returns `FORBIDDEN` through the real handler; a filter whose identity lookup throws hides the tool and refuses the call (fail closed); the tier map equals the parity test's hand-written record, and every tool is measured (`McpVersionTools` included); union tier for argument-dependent tools; a filtered list carries `cacheScope: PRIVATE`; paging over a filtered list |
| §3 | Token endpoint mismatch refused at save and resolve (via ZIP import); every fail-closed row; cache expiry and bound; LLM/embedding/vector binding refused; IT against Keycloak for the real exchange |
| §4 | Field-wise merge across a mixed batch; empty `acr` intersection; persistence round trip of `effectiveAuthLevel`; approve refused and reject allowed on Slack/MCP; decision-time conversion of automated approvals; deploy refused with auth off |

### Docs and operator

- §1 → [`docs/tool-call-guardrails.md`](../docs/tool-call-guardrails.md) plus the output guardrails in [`docs/langchain.md`](../docs/langchain.md) ("Input and Output Guardrails"; there is no separate page).
- §2 → [`docs/mcp-server.md`](../docs/mcp-server.md) (Tool Filtering, Role Mapping). The documented count of 120 is right; what was stale was the parity test.
- §3 → [`docs/connections.md`](../docs/connections.md).
- §4 → [`docs/hitl.md`](../docs/hitl.md).
- A `docs/changelog.d/` fragment per item.
- Check the Platform Operator for §1, §3 and §4, which add config fields an admin will ask about. Any prompt change is a revision bump in `operator-revision.json`. §2 needs none (see above).

### Manager editors

§1 needs the guardrail type in the guardrail editor (output and tool-call together). §3 needs the connection type and binding. §4 needs the rule field and the re-authentication flow in the approval card.

### Suggested order

| # | Item | Size | Gate before starting |
| --- | --- | --- | --- |
| 1 | `McpToolTiers` single source (§2) | S | None. Built on branch `refactor/mcp-tool-tiers` (not yet merged) |
| 2 | Canary guardrail (§1), without the overlap companion | M | `GuardrailModelClient` was checked and forwards no system message. Still open: marker placement (inside `composeSystemMessage`, see the agent-path note), `call` scope across a pause, poisoning under `agent` scope |
| 3 | Role-aware `tools/list` (§2) | S | Identity-in-filter spike passes, *and* the filter is proven to fail closed when the lookup throws |
| 4 | Step-up on approvals (§4) | M–L (backend, two UIs, realm) | Confirm the resume path sees `acr` / `auth_time`; decide per-call shadowing and per-call decisions (open gaps below) |
| 5 | Token exchange (§3) | M–L | Keycloak 26.8 exchange check; §12 carve-out agreed; `/v1` under `http-policy=authenticated` decided; connection-type change re-validation decided |
