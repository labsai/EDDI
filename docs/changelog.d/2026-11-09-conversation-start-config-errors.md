## 🐛 fix(engine): a conversation start refused by the agent's configuration answers 422, and unresolved vault references name their tenant (2026-11-09)

**Repo:** EDDI (`fix/conversation-start-config-errors`) — external security review finding B5 (low)

### What changed and why

An httpcall on `CONVERSATION_START` that EDDI refused — a `${vault:…}` reference naming no secret, a secret sent to a host its `allowedHosts` does not list — reached `POST /agents/{agentId}/start` as an opaque `500 Failed to start conversation`: `ApiCallExecutor` wraps the refusal in a `LifecycleException`, the pipeline wraps that again, and `ConversationService` turns it into a `ResourceStoreException`. A client (the Manager, the Chat UI, the Platform Operator) could not tell "fix the agent" from "the server broke".

- **New marker, [`AgentConfigurationRefusal`](../../src/main/java/ai/labs/eddi/engine/lifecycle/exceptions/AgentConfigurationRefusal.java)**, implemented only by exceptions that certainly mean "this configuration cannot run", with a bounded, cycle-safe `find(Throwable)` over the cause chain.
- **[`RestAgentEngine`](../../src/main/java/ai/labs/eddi/engine/internal/RestAgentEngine.java)**: when a start fails and the chain holds such a refusal, it answers **`422`** `{"error": "agent_misconfigured", "message": "...", "retryable": false}` — the flat envelope this endpoint already uses for its `503`, which the Manager's `apiErrorFromResponse` reads as code + sentence with no change. The message is the refusal's own (names only), passed through `SecretRedactionFilter` again and capped at 1000 characters; no stack trace. Everything else stays a `500`.
- **[`SecretResolver`](../../src/main/java/ai/labs/eddi/secrets/SecretResolver.java)**: `diagnoseUnresolved` says why a reference stayed unresolved (`VAULT_NOT_CONFIGURED`, `RESERVED`, `NOT_FOUND`, `PROVIDER_FAILED`) from one metadata read on the failure path, never the value; `unresolvedReferenceFailure` builds the error — a `MisconfiguredVaultReferenceException` (an `IllegalArgumentException`, as before) for the first three, a plain `IllegalArgumentException` for a vault outage. `requireResolved` (LLM parameters) and the "not found" log line now name the tenant too.
- **[`SecretReference.describe`](../../src/main/java/ai/labs/eddi/secrets/model/SecretReference.java)**: `${vault:name} (no tenant given, so tenant 'default')` / `${vault:acme/name} (tenant 'acme')`. A not-found short-form reference adds that it always looks in `default` and how to write `${vault:<tenant>/name}` — the tester's `${vault:name}` for a secret stored in tenant `eddi`.
- **[`ApiCallExecutor`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java)**: `resolveSecrets` throws the diagnosed failure; `buildRequest` prefixes a refusal with `API call '<name>':` so the 422 says which call. Two small hunks.
- **[`VaultHostAllowlist`](../../src/main/java/ai/labs/eddi/secrets/VaultHostAllowlist.java)**: new `HostRefusedException extends HostNotAllowedException` for the missing-secret, not-on-`allowedHosts` and plaintext-transport refusals. The "allowed hosts could not be read" case keeps the parent type and stays a `500`.
- **Platform Operator**: the test-drive section explains a `422 agent_misconfigured` start; revision bumped.
- **Docs**: [`rest-api-reference.md`](../rest-api-reference.md#errors) (422 row), [`secrets-vault.md`](../secrets-vault.md#when-a-reference-does-not-resolve) (new "When a reference does not resolve"), [`troubleshooting.md`](../troubleshooting.md).

### Design decisions

- **422, not 409.** The request is well-formed and nothing about the target's *state* conflicts (409 is what this API uses for an awaiting-approval or busy conversation); what cannot be processed is the agent the request addresses, and repeating it fails the same way until the configuration changes. `WebhookEventHandler` already answers 422 for "the routed target cannot run".
- **A marker, not message matching or a blanket `IllegalArgumentException` rule.** The SSRF validator, a bad URL and many genuine bugs all throw plain `IllegalArgumentException`; only exceptions written for a configuration refusal opt in. A vault that could not be read, and an `allowedHosts` list that could not be read, are deliberately left out: calling an outage a misconfiguration sends an admin to fix a config that is fine.
- **The message reaches the caller.** It already did on a say turn (the same sentence lands in `taskErrors`); the start path now matches. It carries reference names, hosts and the call name — never a value.

### Not done

- **The httpcall SSRF refusal at run time is still a 500.** `UrlValidationUtils` throws a plain `IllegalArgumentException`, and "Cannot resolve hostname" (DNS, possibly transient) shares that type, so it has no clean signal; another branch is moving the httpcall SSRF check to deploy time (`ApiCallsTask`). A run-time refusal type that implements `AgentConfigurationRefusal` would get the 422 with no further change here.
- **Only `POST /agents/{agentId}/start`** maps the refusal. A say turn still reports it as `TURN_FAILED` in the snapshot's `taskErrors`; MCP, A2A, `/v1` and the other start paths are unchanged.
- An unresolved reference in **LLM parameters** (`UnresolvedSecretReferenceException`) now names its tenant but is not marked: `requireResolved` cannot tell a missing secret from a vault outage.
