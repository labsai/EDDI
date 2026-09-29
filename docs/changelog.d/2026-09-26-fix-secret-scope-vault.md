## 🔒 fix(secrets): secret scope on every path, no echoed plaintext (2026-09-26)

**Repo:** EDDI (`fix/secret-scope-vault`)

Fixes the secret-handling findings of the 2026-09-25 whole-repo review (C2, C2b, H8/S3, S2, S4, S5, M-S1), and the follow-up pre-push review of this branch.

> **Reconciled with main on 2026-09-29** — main fixed C2 in parallel, and its implementation was kept. The items marked *superseded* below are no longer part of this branch; see the reconciliation entry that follows.

### What changed and why

- **C2 — one vault entry per conversation.** *Superseded by main's per-write slots* (`AutoVaultedSecrets`). What remains from this item:
  - The writer is a new bean, [`SecretPropertyVault`](../../src/main/java/ai/labs/eddi/modules/properties/impl/SecretPropertyVault.java). Main's vaulting and step-scrub code moved there from `PropertySetterTask` unchanged, so the other property paths share it.
- **A new conversation gets its id before its first turn (review #1).** *Superseded*: main's slot name needs no conversation id.
- **Vault entries are deleted with their conversation (review #3).** *Superseded* by main's deletion on permanent delete, the retention sweep and GDPR erasure.
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
- **A legacy shared-key reference gets its own message (review #9).** *Superseded*: main still accepts a legacy `<agentId>.<property>` reference in an older conversation.
- **M-S1 — setup plaintext.**
  - `AgentSetupService.vaultApiKey` used to fall back to plaintext when the vault was configured but the write failed. It now fails the setup.
  - A plaintext `apiAuth` in `createApiAgent` is vaulted and recorded for rollback, and the spec is re-parsed so the generated headers carry the reference.

- **CodeRabbit full review of `b4be2a61d`.**
  - *Orphaned vault entries.* *Superseded*, with the per-conversation key the sweep depended on.
  - *Short secrets inside stored JSON text.* A paused tool call keeps its arguments as a JSON string, so `{"pin":"4711"}` did not equal `4711` and the exact match missed it. With exact-match values in play, `SecretValueScrubber` now parses a string that holds a JSON object or array, scrubs the parsed tree and writes it back. It does this only when something was replaced. Nested serialized JSON, such as a transcript's tool-call arguments, is reached by the same walk.
  - *Map keys.* *Superseded*: main never renames a map key for a short secret, so exact matching replaces values only.
  - *Checked template failures.* `buildRequest` now redacts a `TemplateEngineException` as well as a `RuntimeException`. The first can follow a path that already resolved a secret, and before this fix it reached the caller before those plaintexts joined its redaction set.
  - *`${vars:}` parameter check.* The check now uses set membership of whole references, as the credential check does, instead of substring containment.
  - Two findings were not changed. The leaves of a secret *object* are still not exact-matched: that is decision review #4, and the docs now tell callers to send a short credential as its own string entry. `conversationIdOf` also keeps its segment match: the description, which only `vault` writes, already names the conversation.

### Behaviour changes to know about

- **Default install (vault disabled):** every `scope: "secret"` instruction fails its turn with an error naming `EDDI_VAULT_MASTER_KEY`. That includes httpcall, MCP and LLM post-response instructions, which used to store the value in plaintext.
- Saving a configuration returns 400 for a `scope: "secret"` instruction that sets `valueObject` / `valueList` / `valueInt` / `valueFloat` / `valueBoolean`, or a literal name containing `/`, braces or `$`.
- An LLM task, cascade step or judge parameter that carries a `${vault:…}`, `${connection:…}`, `${caller:…}` or `${vars:…}` reference its configured template did not write fails the turn.
- A configured but failing vault now fails `setupAgent` / `createApiAgent`.

### Deferred / follow-up

- The HITL-resume rebuild in `LlmTask` goes through the same `runTemplateEngineOnParams`, and so through the same guard, as the normal path. It is not driven by a dedicated test.

```decision-log
| 2026-09-26 | Short secret context values (4–7 chars) are exact-match scrubbed, top-level string entries only | S4: a PIN copied into a property survived; object leaves would blank unrelated data | Substring replacement; matching object leaves |
| 2026-09-26 | Success bodies: text redaction for secrets ≥ 8 chars, JSON scrubbed as a tree | Echo redaction must not corrupt the data a call fetches | Redacting every length by substring; redacting error bodies only |
| 2026-09-29 | Reconciled with main: main's per-write auto-vault slots (AutoVaultedSecrets) replace this branch's per-conversation key; only the deltas main lacked are kept | Both sides fixed C2; one implementation, main's | Keeping both; keeping the per-conversation key and pre-allocated conversation id |
```

## 🔒 fix(secrets): secret-scope branch reconciled with main (2026-09-29)

**Repo:** EDDI (`fix/secret-scope-vault`)

Main fixed the shared auto-vault slot (C2) while this branch was open, with per-write slots in `AutoVaultedSecrets`, and landed the secret-input scrub of #856. Where both sides fixed the same thing, main's implementation is kept and this branch's copy is dropped. What this branch still adds was ported onto main's code.

### Dropped (main covers it)

- The per-conversation key `<agentId>.<conversationId>.<property>` (`AutoVaultReference`), the resolver-cache invalidation and the ignored `tenantId` property — main's slot is per write, so nothing is overwritten, and main keeps reading the tenant from the property.
- The conversation id allocated before the first turn: `newConversationId`, the `unpersisted` flag, `IAgent.startConversation(conversationId, …)`. Main's slot name does not need it.
- `deleteConversationSecrets`, the orphan sweep and `conversationExists` — main deletes slots with the conversation, in the retention sweep and on GDPR erasure.
- The refusal of legacy `<agentId>.<property>` references — main still accepts them in older conversations.
- The move of `ConfigReferenceGuard` to `ai.labs.eddi.secrets`. It stays in `modules.apicalls.impl`, now public, with `requireConfiguredParameters` added.
- Map-key replacement for short secrets. Main never renames a key for a short secret, so the new `SecretValueScrubber` exact mode replaces values only.

### Kept, on main's code

- [`SecretPropertyVault`](../../src/main/java/ai/labs/eddi/modules/properties/impl/SecretPropertyVault.java) holds main's `autoVaultSecret` and step scrub, unchanged. `PropertySetterTask` and `PrePostUtils` both call it:
  - a `fromObjectPath` value, which is not rendered again, as main decided;
  - a refusal of typed values;
  - the httpcall, MCP and LLM pre-request and post-response instructions;
  - a check of the name at run time;
  - `SecretScopeValidation` when a configuration is saved.
- The parameter guard for LLM tasks, cascade steps and judges. It runs on the HITL-resume path too, through `resolveStepModel`.
- The `ApiCallExecutor` echo and exception redaction, and `RequestRedactor.safeResponseLog`.
- Exact-match scrubbing of short secret context values. It is a new `EXACT` mode of `SecretValueScrubber` beside main's whole-token mode, and it reaches `Conversation` and `TurnAuditBuffer`.
- The `AgentSetupService` fail-closed vaulting and the vaulting of `apiAuth`.


### CodeRabbit review of `d44edc478`

- **Scrubbed longTerm properties are written back.** `Conversation` kept the *same* `Property` objects in its longTerm baseline. The turn-end secret scrub changes a property in place, so a loaded longTerm property that it scrubbed still equalled its baseline and was never written: the user-memory store kept the secret. This was main's baseline code; the ≥ 8-character scrub hit it already, and the exact match for short values made it reachable for PINs too. The baseline now holds independent copies.
- **`apiAuth` references are recognised, not guessed.** `AgentSetupService.vaultApiAuth` used to treat any value containing `${` as a reference and store it as given. Now:
  - A value that is exactly a supported reference (`vault`, `eddivault`, `connection`, `vars` or `caller`), optionally after a scheme, is kept.
  - A value that mixes a reference with other text is refused.
  - Anything else is vaulted, so a literal such as `Bearer test-token${` no longer reaches the httpcalls in plaintext.
- **Post-response output cannot render a token its own instructions vaulted.** `PrePostUtils.runPostResponse` scrubs the vaulted plaintexts from the template data before it builds the output and quick replies. `{tokenResponse.access_token}` in an output template no longer reaches the conversation output.
- **An oversize JSON body is redacted before it is cut.** `ApiCallExecutor` parses and scrubs the whole application/json body first, then serializes and truncates it. Cutting first made the body invalid JSON, and the text fallback that followed misses secrets under 8 characters. A body that is genuinely invalid JSON still falls back to text redaction.

### CodeRabbit review of `cd0515dc1`

- **Non-string secret values from a response are refused.** In an httpcall, MCP or LLM instruction with `scope: "secret"`, a `fromObjectPath` value that is a native object, array, number or boolean now fails the turn. It used to be replaced by an empty string, which the secret branch then skipped, so the secret was dropped silently.
- **Vault references survive the template-data scrub.** The scrub after a post-response instruction now leaves `${vault:…}` / `${eddivault:…}` references intact and never renames map keys: `SecretValueScrubber.scrubDeepKeepingReferences`. Otherwise a plaintext that occurs inside the property name broke two things: the property's own reference, because the slot name ends in the name, and its key in `properties`.
- **Setup errors no longer carry the vault provider's message.** The detail is logged on the server; the caller gets the guidance only.
