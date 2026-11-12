## 🔒 fix(vault): references only in credential parameters, agent grants on standalone RAG, default endpoints, auto-vault tenant (2026-11-11)

**Repo:** EDDI (`fix/vault-reference-credential-keys`)

Four findings of the 2026-11 vault review.

### 1. A vault secret read back through a model parameter (high)

`ChatModelRegistry` and `EmbeddingModelFactory` resolved `${vault:…}` in every model parameter. An editor
with a working key of their own saved `"modelName": "${vault:any-secret}"`, and the provider's "model X does
not exist" error returned the plaintext — in RAG search and chunk responses, the ingestion status (VIEW
access), the conversation's `taskErrors`, the fallback handler, and the `gen_ai.request.model` span attribute
and `model` meter tag.

- `ILanguageModelBuilder.secretParameters()` declares the keys that carry a credential, per builder (OpenAI,
  Anthropic, Gemini, Mistral and the OpenAI-compatible types: `apiKey`, `customHeaders`; Azure adds
  `nonAzureApiKey`; HuggingFace `accessToken`; Jlama `authToken`; Ollama, Bedrock, Vertex `customHeaders`;
  Oracle none). The default is empty — fail closed. Embeddings: `EmbeddingParameters.credentialParameters`
  (`apiKey` for openai, azure-openai, mistral, cohere, gemini; none for ollama, bedrock, vertex).
- `VaultReferencePlacement` refuses a reference anywhere else, after `${vars:…}` expansion and before
  anything is resolved, naming only the key. Both registry paths and the embedding factory apply it, so no
  model — and no telemetry — ever sees such a value.
- Save time (the literal text only; a `${vars:…}` that expands to a reference is caught at build time): in `RestLlmStore` (create, update, patch, duplicate;
  task, cascade step and judge parameters, a templated type judged against every provider's keys),
  `RagConfiguration.validate()` covers the RAG create and update, `RestRagStore.duplicateRag` checks it
  separately, and the direct creates of `RestImportService` and `UpgradeExecutor` apply the guard.
- Defence in depth: `KnowledgeBaseFailureRedactor` scrubs the knowledge base's own secrets by value, then
  credential shapes (`SecretRedactionFilter.redact`), from the error `RestRagIngestion` returns and the
  status `RagIngestionService` stores.

### 2. `allowedAgents` not enforced on the standalone RAG endpoints (medium)

`SpaceReferenceGuard.requireMayUse` checked space membership only, and returned early without workspace
enforcement. A secret granted to one agent was resolved for any editor of any knowledge base naming it.
`VaultGrantChecker.findAgentRestrictedReferences` reports references whose grant lists agents without the
wildcard (variables expanded, connections followed; an unreadable grant, variable or connection fails
closed), and `requireMayUse` refuses them with 403 before the workspace check, for everyone but an
administrator. It follows `eddi.vault.grant-enforcement` (`warn` logs, `off` skips). Agent retrieval and
scheduled source runs do not pass through it and are unchanged.

### 3. `allowedHosts` did not bind a secret without a configured endpoint (low-medium)

Only explicit endpoint keys (and the OpenAI-compatible preset URL) were checked, so a key restricted to
`api.openai.com` could be sent to `api.anthropic.com` by switching the provider type. Every builder now
returns its real default from `defaultEndpoint` (langchain4j's vendor hosts, Bedrock's regional host,
Vertex's location host, HuggingFace, Jlama's model download, Ollama's localhost), and `EmbeddingModelFactory`
checks a per-provider default. A secret without `allowedHosts` is unaffected.

### 4. Auto-vaulted secrets written to a tenant chosen by `property.json` (low)

`SecretPropertyVault` took the vault tenant from the `tenantId` conversation property. With workspaces
enforced it is now honoured only when it names the agent's own space's tenant; otherwise the secret goes to
`default` with a WARN. Without enforcement, and without the property, behaviour is unchanged.
`ConfigReferenceGuard` accepts the conversation's own slot under `default` as well as the requested tenant,
so the fallback stays usable.

### Review follow-ups

- **Standalone RAG and `allowedAgents`:** a restricted secret is permitted to a caller who may EDIT at least one
  existing agent on its grant (`VaultGrantChecker.RestrictedReference` carries the existing granted agents,
  `SpaceReferenceGuard` asks `ResourceAccessGuard.hasAccess(…, EDIT)`); everyone else is refused without the
  agents being named. A grant naming only deleted agents lets no editor through.
- **Vector-store parameters:** `StoreParameters` declares the credential keys per store (pgvector `user`,
  `password`; Elasticsearch `apiKey`, `userName`, `password`; Qdrant `apiKey`; MongoDB Atlas
  `connectionString`; Chroma and in-memory none). `EmbeddingStoreFactory` refuses a reference anywhere else
  before resolving; `RagConfiguration.validate()`, `RestRagStore.duplicateRag` and the import/upgrade guard
  refuse it at save time.
- **By-value redaction on the chat error paths:** `ResolvedCredentials` records the plaintexts
  `ChatModelRegistry` resolves into a client; `LifecycleManager` (task-error digest, audit/SSE summary) and
  `LlmFallbackHandler` (`llm:error:<taskId>`) scrub them before the pattern redaction, with
  `RequestRedactor.redactResolvedSecrets`, the redactor the httpcall path uses.
- **Deploy time:** `LlmTask.configure` applies the placement rule, so a stored configuration that breaks it fails
  the deploy naming the field instead of the first turn.
- The refusal now says that references are only resolved in credential parameters and where to move one.

### Code-review follow-ups

- The standalone-RAG refusal names neither the reference nor the granted agents: a reference may have been
  reached through a variable the caller cannot read. The log line keeps them.
- `azure-openai` reports OpenAI's API as its destination whenever `nonAzureApiKey` is set (the client then
  sends there even with an `endpoint`), and a whitespace-only Vertex `apiEndpoint` falls back to the regional
  host check, as the builder treats it.
- The chat-error scrubber captures each plaintext where `ChatModelRegistry` substitutes its reference (each
  distinct reference resolved once, on its own), instead of resolving again or splitting the resolved text —
  a secret containing the separator, or two adjacent references, is captured as the separate secrets it is.
- A credential shorter than 8 characters cannot be replaced everywhere. On the chat error paths
  (`ResolvedCredentials`) a message containing one of 4 to 7 characters anywhere is withheld whole; under 4
  characters cannot be covered there, the set being process-wide. A knowledge base with any credential
  parameter shorter than 8 characters (not `user`/`userName`) never returns or stores its provider's failure
  text: `KnowledgeBaseFailureRedactor` returns a generic message naming the exception class, and the log line
  carries only the classes of the cause chain and the knowledge base — also when the top-level message is
  `null`, since a cause can quote the credential. Remedy: a vault reference or a credential of 8+ characters.
  Otherwise every inspected message of the cause chain is redacted, and the log always gets that redacted
  chain, never the exception object (ingestion status, inspection and evaluation endpoints alike): at most 16
  causes are inspected, and further causes and suppressed exceptions are named as not inspected rather than
  printed. A knowledge base whose vault reference cannot be resolved at failure time also has its provider
  text withheld (the failing client may hold the plaintext), and the redactor additionally removes every
  credential a live model or store client was built with, so a value rotated away while a request was in
  flight is still removed.
- `ResolvedCredentials` ties recorded plaintexts to the client built with them (`record(holder, values)`,
  weak keys compared by identity): kept while the chat model, embedding model or vector store client is
  cached or still used by a running request, however many other values are recorded; values without a
  holder keep the bounded set. The strict-write failure log line in `LifecycleManager` now logs the same
  scrubbed summary as the audit entry instead of the raw exception message.
- Credentials follow the failure, not the client's lifetime: `ObservableChatModel` and
  `ObservableStreamingChatModel` pin every failure to the credentials the registry built them with
  (`ResolvedCredentials.pin`), and the task-error digest, the audit/SSE summary, the strict-write log and
  `llm:error:<taskId>` scrub with what is pinned to the failure they describe — so an evicted or rotated
  model's key is still removed, with no reliance on garbage-collection timing. The knowledge-base paths
  (ingestion, inspection, evaluation) read what their model and store clients hold right after acquiring
  them and pass or pin it; a held credential under 8 characters withholds the provider text.
- `SecretCapture` (shared by `ChatModelRegistry` and both embedding factories) resolves each distinct
  reference once and captures the secret it stood for, so `prefix-${vault:key}` captures the secret itself.
- The save and deploy placement check also covers `classifier` and `judge` guardrail parameters, judged against
  the guardrail's own `provider`.
- `KnowledgeBaseFailureRedactor` also scrubs the literal values of credential parameters (8+ characters; a shorter
  one withholds every failure text, as above).
- The vault-tenant warning no longer logs the requested tenant value, and `ConfigReferenceGuard` trims the
  `tenantId` property as `SecretPropertyVault` does.
- A merge import over a stored version that breaks the placement rule is refused before it writes, since the
  rollback could not restore it.
### Upgrade note

Configurations saved before this change that hold a `${vault:…}` (or a `${vars:…}` expanding to one) outside a
credential parameter stop working: the LLM task's deploy fails, or its turn fails, and a knowledge base's search
and ingestion fail, each naming the parameter. Typical cases: a tokenised URL in `baseUrl`, a vaulted model or
deployment name, a vaulted `database`, `collectionName` or `indexName`. Find them by searching stored LLM and RAG
configurations (and global variables) for `${vault:`/`${eddivault:` outside the keys listed in
[Secrets Vault](../secrets-vault.md#model-parameters-credentials-only); move the secret into a credential
parameter and put a literal where it was. Editors who used the standalone RAG endpoints with a secret restricted
to agents they cannot edit now need an administrator, or a wider grant.

### Design decisions

- Refusing, not silently skipping, a misplaced reference: a skipped reference is sent as literal text and
  fails at the provider with nothing naming the cause.
- No configuration switch for finding 1 or 2: either would reopen the hole by default. Finding 2 reuses the
  existing grant-enforcement mode instead of a new one.
- `customQueryParams` is a credential parameter on the OpenAI types: some gateways authenticate only by query key, and refusing it would leave a plaintext key as the only option. It is backed by the by-value scrubbing below and by the digests stripping URLs.

**Docs:** [Secrets Vault](../secrets-vault.md) (model parameters, knowledge bases outside an agent, default
endpoints, auto-vault tenant), [Security](../security.md).

**Files:** [`VaultReferencePlacement.java`](../../src/main/java/ai/labs/eddi/secrets/VaultReferencePlacement.java),
[`VaultReferencePlacementGuard.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/VaultReferencePlacementGuard.java),
[`ChatModelRegistry.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ChatModelRegistry.java),
[`EmbeddingModelFactory.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/EmbeddingModelFactory.java),
[`EmbeddingParameters.java`](../../src/main/java/ai/labs/eddi/configs/rag/model/EmbeddingParameters.java),
[`KnowledgeBaseFailureRedactor.java`](../../src/main/java/ai/labs/eddi/modules/rag/KnowledgeBaseFailureRedactor.java),
[`VaultGrantChecker.java`](../../src/main/java/ai/labs/eddi/secrets/VaultGrantChecker.java),
[`SpaceReferenceGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/spaces/SpaceReferenceGuard.java),
[`SecretPropertyVault.java`](../../src/main/java/ai/labs/eddi/modules/properties/impl/SecretPropertyVault.java),
the builders under `modules/llm/impl/builder`.
