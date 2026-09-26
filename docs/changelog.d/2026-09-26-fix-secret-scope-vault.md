## 🔒 fix(secrets): per-conversation secret properties, secret scope on every path, no echoed plaintext (2026-09-26)

**Repo:** EDDI (`fix/secret-scope-vault`)

Fixes the secret-handling findings of the 2026-09-25 whole-repo review (C2, C2b, H8/S3, S2, S4, S5, M-S1), and the follow-up pre-push review of this branch.

### What changed and why

- **C2 — one vault entry per conversation.**
  - A `scope: "secret"` property was stored under `<tenant>/<agentId>.<property>`. The vault write is an upsert, so every user of an agent shared a single entry: once user B entered a key, user A's calls went out with B's credential.
  - The key is now `<agentId>.<conversationId>.<property>` under the default tenant, defined once in [`AutoVaultReference`](../../src/main/java/ai/labs/eddi/secrets/model/AutoVaultReference.java).
  - The tenant used to come from the `tenantId` conversation property, which a client can set through a context expression. It is no longer read.
  - The resolver cache is invalidated after every write. Re-entering a secret in a conversation overwrites its entry, and the old plaintext would otherwise keep resolving for the cache TTL.
  - The writer is a new bean, [`SecretPropertyVault`](../../src/main/java/ai/labs/eddi/modules/properties/impl/SecretPropertyVault.java). The vaulting and step-scrub code moved there from `PropertySetterTask` unchanged.
  - The guard moved to a neutral package, as [`ai.labs.eddi.secrets.ConfigReferenceGuard`](../../src/main/java/ai/labs/eddi/secrets/ConfigReferenceGuard.java), and matches references with the same definition.
- **A new conversation gets its id before its first turn (review #1).**
  - The id used to exist only after the conversation was first stored. So a secret set on `CONVERSATION_START` (a property setter reading `{context.apiKey}`) had no key to be vaulted under, and the conversation could not be started. That applied to REST, schedules and group members alike.
  - `IConversationMemoryStore.newConversationId()` now allocates the id: an ObjectId on Mongo, a UUID on Postgres.
  - `ConversationService` passes it to the new `IAgent.startConversation(conversationId, …)` overload.
  - The memory is marked `unpersisted`, a transient flag on memory and snapshot. The store inserts under that id on the first write, then clears the flag.
- **Vault entries are deleted with their conversation (review #3).**
  - A permanent delete and the ended-conversation retention sweep call `SecretPropertyVault.deleteConversationSecrets`. The sweep does one vault listing for the whole batch.
  - An entry qualifies only when its key contains the conversation id and its description is the one the vault writes (`Auto-vaulted from conversation <id>`). An operator's secret is therefore never touched.
  - The deletion is best effort and never stops a conversation delete. A soft delete keeps the entries.
- **C2b — secret scope on every property path, or fail closed.**
  - These paths used to store the value as a plaintext property whatever the scope: the `fromObjectPath` branch and the typed value fields of the property setter, and every pre-request / post-response instruction (`PrePostUtils`: httpcalls, MCP calls, LLM tasks). Strings are now vaulted on all of them.
  - A value that turns out not to be a string at run time fails the turn.
  - In `PrePostUtils` that failure is `SecretPropertyVault.SecretPropertyException`. It is rethrown past the loop's log-and-skip handling (review #5): silently storing nothing left `{properties.x}` empty and produced a 401 on the next call that named nothing.
  - Statically impossible combinations are rejected when the configuration is saved by [`SecretScopeValidation`](../../src/main/java/ai/labs/eddi/configs/properties/SecretScopeValidation.java). It runs from the `validate` hook of `PropertySetterStore`, `ApiCallsStore`, `McpCallsStore` and `LlmStore`, and rejects typed values and names containing `/`, braces or `$`.
  - Two cases are no longer rejected on save, because they work at run time (review nit #10): `convertToObject: true`, which only converts values shaped like a JSON object, and names containing whitespace.
- **H8 / S3 — data-supplied `${vault:}` in LLM parameters.**
  - `ChatModelRegistry` resolves `${vars:}` and `${vault:}` in every builder parameter after templating. A parameter such as `"modelName": "{context.model}"` therefore resolved any secret a user typed into it, with no grant check.
  - The new shared helper `ConfigReferenceGuard.requireConfiguredParameters` applies the httpcall rule: a reference is resolved only where the configured template wrote it, or where the named property is this conversation's own auto-vaulted secret. A data-supplied `${vars:}` is refused as well.
  - The helper covers the task parameters (`LlmTask`) and, per review #2, the cascade step and judge parameters (`CascadingModelExecutor`).
  - A judge refusal fails the turn rather than falling back to the heuristic.
  - `systemMessage` and `prompt` are never resolved, so they are exempt.
  - The false comment at `LlmTask.escapeConfigReferenceMentions` is corrected.
- **S2 — responses echoing a substituted secret.**
  - The executor records every plaintext it substitutes. Per review #7, that now includes connection credentials, with and without their scheme, and `${caller:token}`, not only vault secrets.
  - These plaintexts are removed from the error body, the status message and the response header values, and from the INFO response log. The log goes through `RequestRedactor.safeResponseLog`, which also masks credential-named headers and `Set-Cookie`.
  - A success body is data, so it is redacted without mangling it (review #8):
    - A secret of 8 characters or more is removed from the text.
    - A JSON body is parsed first and scrubbed as a tree: shorter secrets only where a value *is* the secret, and numbers only whole, never digit by digit.
  - A token captured by a post-response `scope: secret` instruction is also removed from the map returned as the LLM tool result (review #6). `PrePostUtils.runPostResponse` now returns the plaintexts it vaulted.
- **S5 — `URI.create` message leak.**
  - The request path is resolved before it is parsed, and `URI.create` quotes the whole string it rejects. A secret in a URL path therefore reached the ERROR log and the task-error digest.
  - `buildRequest` and `execute`'s catch-all now redact any exception whose message chain carries a substituted plaintext, and drop the cause chain.
- **S4 — short secret context values.**
  - Values under 8 characters were only removed from their own context entry.
  - A context entry whose whole value is a 4–7 character string is now also replaced wherever a property, datum, list element, map value, number property or audit-payload field *is* that value. The match is exact, never a substring.
  - Per review #4, the leaves of a secret *object* are never exact-matched. Its `"tokenType": "Bearer"` or `"port": 8080` would otherwise blank every equal value of the turn, loaded `longTerm` properties included, and persist that loss.
- **A legacy shared-key reference gets its own message (review #9).** An apicall that uses a marked property holding the old `<agentId>.<property>` reference is refused with "stored by an earlier release … ask the user to enter the secret again", instead of the injection wording.
- **M-S1 — setup plaintext.**
  - `AgentSetupService.vaultApiKey` used to fall back to plaintext when the vault was configured but the write failed. It now fails the setup.
  - A plaintext `apiAuth` in `createApiAgent` is vaulted and recorded for rollback, and the spec is re-parsed so the generated headers carry the reference.

- **CodeRabbit full review of `058eddc31`.**
  - *Orphaned vault entries.* `deleteConversationSecrets` runs after the conversation is gone and is best effort, so a vault failure at that moment left the entry for good. A start turn that vaults a secret and then fails before the conversation is first stored left one too, and so does a bulk erasure. The retention sweep now also calls `SecretPropertyVault.deleteOrphanedConversationSecrets`. It deletes every auto-vaulted entry last written more than a day ago whose conversation is no longer stored. The one-day grace keeps an in-progress start turn's entry. An entry whose conversation cannot be looked up is kept. Existence is checked with the new `IConversationMemoryStore.conversationExists`, implemented by both stores. `getConversationState` also answers `null` for a document without a state, so it could not tell "gone" from "no state".
  - *Short secrets inside stored JSON text.* A paused tool call keeps its arguments as a JSON string, so `{"pin":"4711"}` did not equal `4711` and the exact match missed it. With exact-match values in play, `SecretValueScrubber` now parses a string that holds a JSON object or array, scrubs the parsed tree and writes it back. It does this only when something was replaced. Nested serialized JSON, such as a transcript's tool-call arguments, is reached by the same walk.
  - *Map keys.* A map key that *is* an exact-match value is replaced, like a value.
  - *Checked template failures.* `buildRequest` now redacts a `TemplateEngineException` as well as a `RuntimeException`. The first can follow a path that already resolved a secret, and before this fix it reached the caller before those plaintexts joined its redaction set.
  - *`${vars:}` parameter check.* The check now uses set membership of whole references, as the credential check does, instead of substring containment.
  - Two findings were not changed. The leaves of a secret *object* are still not exact-matched: that is decision review #4, and the docs now tell callers to send a short credential as its own string entry. `conversationIdOf` also keeps its segment match: the description, which only `vault` writes, already names the conversation.

### Behaviour changes to know about

- A conversation that auto-vaulted a secret before this change holds a reference to the old shared key. Apicalls now refuse it with a message saying so. The user has to enter the secret again, or start a new conversation.
- A `tenantId` conversation property no longer selects the vault tenant.
- **Default install (vault disabled):** every `scope: "secret"` instruction fails its turn with an error naming `EDDI_VAULT_MASTER_KEY`. That includes httpcall, MCP and LLM post-response instructions, which used to store the value in plaintext.
- Saving a configuration returns 400 for a `scope: "secret"` instruction that sets `valueObject` / `valueList` / `valueInt` / `valueFloat` / `valueBoolean`, or a literal name containing `/`, braces or `$`.
- A configured but failing vault now fails `setupAgent` / `createApiAgent`.
- `IAgent` has a new `startConversation(conversationId, …)` default method. `IConversationMemoryStore` has a new abstract `newConversationId()`, implemented by both stores.

### Deferred / follow-up

- GDPR erasure (`deleteConversationsByUserId`) does not yet delete the erased user's vault entries when the erasure runs. The retention sweep's orphan reconciliation removes them on its next run, a day or more later. Deleting them at erasure time belongs to `fix/gdpr-erasure` (PR #834), which can call `SecretPropertyVault.deleteConversationSecrets` with the erased conversation ids.
- `VaultSecretProvider.store` still resets `allowedAgents` on an existing entry. That is owned by `fix/vault-key-safety`.
- The HITL-resume rebuild in `LlmTask` goes through the same `runTemplateEngineOnParams`, and so through the same guard, as the normal path. It is not driven by a dedicated test.

```decision-log
| 2026-09-26 | Secret-scope vault key is `<agentId>.<conversationId>.<property>` under the default tenant; client-settable `tenantId` ignored | C2: shared per-agent entry let one user's key replace another's | Per-user key (userId needs escaping/hashing, anonymous users collide); honouring `tenantId` (client-settable) |
| 2026-09-26 | The conversation id is allocated by the store before the CONVERSATION_START turn, and the first write inserts under it | Per-conversation keys need the id on the init turn | Deriving the key from a separate server-side scope id; vaulting under a placeholder and re-keying after the first store |
| 2026-09-26 | Legacy per-agent auto-vault references are refused, with a dedicated message, not grandfathered | Resolving them keeps the cross-user credential mix-up alive | Accepting the old key shape in ConfigReferenceGuard; copying the old value into a per-conversation entry |
| 2026-09-26 | Short secret context values (4–7 chars) are exact-match scrubbed, top-level string entries only | S4: a PIN copied into a property survived; object leaves would blank unrelated data | Substring replacement; matching object leaves |
| 2026-09-26 | Orphaned auto-vault entries are reconciled by the retention sweep: last written more than a day ago, and the conversation is not stored | Delete-time cleanup is best effort, and failed start turns and bulk erasure never call it | A durable pending-deletion queue; deleting the entry before the conversation |
| 2026-09-26 | Success bodies: text redaction for secrets ≥ 8 chars, JSON scrubbed as a tree | Echo redaction must not corrupt the data a call fetches | Redacting every length by substring; redacting error bodies only |
```
