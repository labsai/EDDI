## 🔒 fix(secrets): per-conversation secret properties, secret scope on every path, no echoed plaintext (2026-09-26)

**Repo:** EDDI (`fix/secret-scope-vault`)

Fixes the secret-handling findings of the 2026-09-25 whole-repo review (C2, C2b, H8/S3, S2, S4, S5, M-S1).

### What changed and why

- **C2 — one vault entry per conversation.** A `scope: "secret"` property was stored under
  `<tenant>/<agentId>.<property>`. The vault write is an upsert, so every user of an agent shared a
  single entry: once user B entered a key, user A's calls went out with B's credential. The key is
  now `<agentId>.<conversationId>.<property>` under the default tenant. The tenant used to come from
  the `tenantId` conversation property, which a client can set through a context expression; it is
  no longer read. The resolver cache is invalidated after every write, since re-entering a secret
  in a conversation overwrites its entry and the old plaintext would otherwise keep resolving for the
  cache TTL. The writer is a new bean,
  [`SecretPropertyVault`](../../src/main/java/ai/labs/eddi/modules/properties/impl/SecretPropertyVault.java)
  (the vaulting and step-scrub code moved out of `PropertySetterTask` unchanged).
  [`ConfigReferenceGuard`](../../src/main/java/ai/labs/eddi/modules/apicalls/impl/ConfigReferenceGuard.java)
  derives the expected reference from the same `SecretPropertyVault.referenceFor`.
- **C2b — secret scope on every property path, or fail closed.** The `fromObjectPath` branch and the
  typed value fields of the property setter, and every pre-request / post-response property
  instruction (`PrePostUtils`: httpcalls, MCP calls, LLM tasks), stored the value as a plaintext
  property whatever the scope. Strings are now vaulted on all of them. Anything else (a map, a
  number, `convertToObject`) is refused at run time. The property setter throws; `PrePostUtils`
  logs and stores nothing, which is how it already handles instruction failures. Statically
  impossible combinations are rejected when the configuration is saved:
  [`SecretScopeValidation`](../../src/main/java/ai/labs/eddi/configs/properties/SecretScopeValidation.java)
  is called from the `validate` hook of `PropertySetterStore`, `ApiCallsStore`, `McpCallsStore` and `LlmStore`.
- **H8 / S3 — data-supplied `${vault:}` in LLM parameters.** `ChatModelRegistry` resolves `${vars:}` and
  `${vault:}` in every builder parameter after templating. A parameter such as
  `"modelName": "{context.model}"` therefore resolved any secret a user typed into it, with no grant
  check. `LlmTask.guardRenderedParameters` now applies the httpcall rule: a reference is resolved only
  where the configured template wrote it, or where the named property is this conversation's own
  auto-vaulted secret. A data-supplied `${vars:}` is refused too, because it can expand to a vault
  reference. `systemMessage` and `prompt` are never resolved, so they are not guarded. The false
  comment at `LlmTask.escapeConfigReferenceMentions`, which said these values "never go through vault
  resolution", is corrected.
- **S2 — responses echoing a substituted secret.** The executor records every plaintext it substitutes.
  Those plaintexts are now removed by value from the error body, the status message, the success body
  (before truncation) and the response header values, before any of them reaches conversation memory,
  template data or the tool result. The INFO response log now goes through
  `RequestRedactor.safeResponseLog`, which does the same and also masks credential-named headers and
  `Set-Cookie`. That covers the synchronous, batch and fire-and-forget paths.
- **S5 — `URI.create` message leak.** The request path is resolved before it is parsed, and
  `URI.create` quotes the whole string it rejects. A secret in a URL path therefore reached the ERROR
  log and the task-error digest. `buildRequest` now redacts any exception whose message, or whose
  causes' messages, carry a substituted plaintext, and drops the cause chain. The same redaction also
  runs in `execute`'s catch-all.
- **S4 — short secret context values.** Values under 8 characters were only removed from their own
  context entry. From 4 characters up they are now also replaced wherever a property, datum, list
  element, map value, number property or audit-payload field *is* the value (exact match, never a
  substring). Values under 4 characters and `true`/`false` are left alone: a secret object's boolean
  fields would otherwise blank every such value of the turn. This goes through the new
  `SecretValueScrubber.scrubDeep(value, plaintexts, exactValues, placeholder)` overload and
  `TurnAuditBuffer.flush(memory, needles, exactValues)`.
- **M-S1 — setup plaintext.** `AgentSetupService.vaultApiKey` used to fall back to plaintext when the
  vault is configured but the write failed. It now fails the setup. `createApiAgent`'s plaintext
  `apiAuth` used to be written into every generated httpcall header verbatim. It is now vaulted
  (`setup.<agent>.<ts>-<rand>.apiAuth`, recorded for rollback), and the spec is re-parsed so the
  headers carry the reference. A value that already carries `${…}`, or a disabled vault, passes
  through as before.

### Behaviour changes to know about

- A conversation that auto-vaulted a secret before this change holds a reference to the old shared
  key. Apicalls now refuse it; the user has to enter the secret again, or start a new conversation.
  That entry held whichever user's value was written last, so resolving it would keep the defect alive.
- A `tenantId` conversation property no longer selects the vault tenant.
- Saving a property setter, httpcalls, MCP calls or LLM configuration with a `scope: "secret"`
  instruction that sets `valueObject` / `valueList` / `valueInt` / `valueFloat` / `valueBoolean`,
  `convertToObject: true`, or a literal name containing `/`, braces, `$` or whitespace now returns 400.
  Such configurations used to store the value in plaintext.
- A configured-but-failing vault now fails `setupAgent` / `createApiAgent` instead of storing the key
  in plaintext.

### Deferred / follow-up

- Per-conversation vault entries are not deleted when a conversation is deleted or a user is erased.
  The old shared entries were not either. This belongs with the GDPR erasure work
  (`fix/gdpr-erasure`); the entry description records the conversation id so a sweep can find them.
- `VaultSecretProvider.store` still resets `allowedAgents` on an existing entry. That is owned by
  `fix/vault-key-safety`.

```decision-log
| 2026-09-26 | Secret-scope vault key is `<agentId>.<conversationId>.<property>` under the default tenant; client-settable `tenantId` ignored | C2: shared per-agent entry let one user's key replace another's | Per-user key (userId needs escaping/hashing, anonymous users collide); honouring `tenantId` (client-settable) |
| 2026-09-26 | Legacy per-agent auto-vault references are refused, not grandfathered | Resolving them keeps the cross-user credential mix-up alive | Accepting the old key shape in ConfigReferenceGuard |
| 2026-09-26 | Short secret context values (4–7 chars) are exact-match scrubbed only | S4: a PIN copied into a property survived; substring replacement would mangle output | Substring replacement; leaving them unscrubbed |
```
