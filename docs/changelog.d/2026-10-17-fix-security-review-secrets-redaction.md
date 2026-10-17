## 🔒 fix(security): editors no longer read plaintext credentials in any config store (2026-10-17)

**Repo:** EDDI (`fix/security-review-secrets-redaction`)

### What changed and why

M1 of the 2026-10-17 security review. The 2026-10-15 fix (L12) redacted plaintext
keys for non-admins in `tasks[].parameters` of LLM configurations only; editors still
read them verbatim in `a2aAgents[].apiKey`, `modelCascade` step and judge parameters,
the MCP-calls `apiKey`, a Slack integration's `botToken`/`signingSecret` (enough to
forge `/integrations/slack/*` events), RAG `embeddingParameters`/`storeParameters`,
and literal `Authorization` headers and keys in an httpcall's `request.path` and
`request.body` — current and old (`?version=`) versions alike.

- **One shared read-redactor**, `ConfigSecretRedactor` (`secrets.sanitize`), walks the
  serialized document: credential-named fields are replaced whole, header and
  query-parameter maps are judged by header/parameter name, URLs lose their userinfo
  password and credential-named query values, and `body` fields go through
  `SecretRedactionFilter`. References (`${vault:}`, `${vars:}`, `${connection:}`,
  `${caller:}`) and templates pass unchanged, also beside a scheme word
  (`Bearer ${vault:k}`). `token` matches only singular, so `maxTokens` and
  `thinkingBudgetTokens` are untouched; `httpCalls[].parameters` (tool parameter
  descriptions) and `tasks[].toolCacheScopes` (keyed by tool name) are skipped.
- `RestLlmStore`, `RestMcpCallsStore`, `RestChannelIntegrationStore`, `RestRagStore`
  and `RestApiCallsStore` apply it for every caller who is not `eddi-admin`. MCP
  `read_resource`/`read_channel_integration` reach the same REST stores through the
  loopback client, which forwards the caller, so they are covered too.
- `RestLlmStore`'s write-time warning now uses the same detection, so it warns about
  exactly what an editor cannot read back.
- `RestRagIngestion` reads the knowledge base through the new
  `RestRagStore.readRagForIngestion` (same access check, no redaction): ingestion
  builds the embedding model from that document and must not send the marker.

**Design decision:** no merge-back of the stored literal when an editor saves a
redacted document — the marker is saved and fails at the provider. Merging it back
would let an editor move the marker next to a host of their choosing (H1 with extra
steps).

**Known consequence:** an editor-run upgrade import (`UpgradeExecutor`) reads the
target through the same REST stores, so a target literal it restores is the marker.
This was already so for LLM task parameters since L12; admins are unaffected.

**Files:** [`ConfigSecretRedactor.java`](../../src/main/java/ai/labs/eddi/secrets/sanitize/ConfigSecretRedactor.java),
[`RestLlmStore.java`](../../src/main/java/ai/labs/eddi/configs/llm/rest/RestLlmStore.java),
[`RestMcpCallsStore.java`](../../src/main/java/ai/labs/eddi/configs/mcpcalls/rest/RestMcpCallsStore.java),
[`RestChannelIntegrationStore.java`](../../src/main/java/ai/labs/eddi/configs/channels/rest/RestChannelIntegrationStore.java),
[`RestRagStore.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagStore.java),
[`RestRagIngestion.java`](../../src/main/java/ai/labs/eddi/configs/rag/rest/RestRagIngestion.java),
[`RestApiCallsStore.java`](../../src/main/java/ai/labs/eddi/configs/apicalls/rest/RestApiCallsStore.java),
[`security.md`](../security.md#editors-never-read-a-plaintext-credential-back),
[`secrets-vault.md`](../secrets-vault.md#configuration-read-redaction)

**Tests:** `ConfigSecretRedactorTest` (rules, idempotence, a linear-time check on
adversarial braces), one `*ReadRedactionTest` per store; each store test was
mutation-checked by removing the redaction and watching it fail.

## 🔒 fix(security): resetting a tenant's vault requires confirm=true (2026-10-17)

**Repo:** EDDI (`fix/security-review-secrets-redaction`)

L11 of the 2026-10-17 security review. `POST /secretstore/secrets/{tenantId}/reset`
deletes every secret and DEK of a tenant and had no confirmation, unlike
`adopt-master-key`. It now requires `?confirm=true`; without it the answer is a `400`
with `{"error": "confirm=true is required", "action": …}` and nothing is deleted. The
Manager's reset dialog (which already makes the admin type the tenant id) sends the
flag, and the `adopt-master-key` response names it.

**Files:** [`IRestSecretStore.java`](../../src/main/java/ai/labs/eddi/secrets/rest/IRestSecretStore.java),
[`RestSecretStore.java`](../../src/main/java/ai/labs/eddi/secrets/rest/RestSecretStore.java),
[`secrets.ts`](../../ui/manager/src/lib/api/secrets.ts),
[`secrets-vault.md`](../secrets-vault.md#lost-master-key)
