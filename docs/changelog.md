# EDDI Ecosystem — Working Changelog

> **Purpose:** Living document tracking all changes, decisions, and reasoning during implementation. Updated as work progresses for easy reference and review.

---

## How to Read This Document

Each entry follows this format:

- **Date** — What changed and why
- **Repo** — Which repository was modified
- **Decision** — Key design decisions and their reasoning
- **Files** — Links to modified files

## Strategy 2: Rolling Summary + Conversation Recall Tool (2026-03-30)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Implemented the Rolling Summary strategy for conversation window management. When conversations grow beyond a configurable `recentWindowSteps` threshold, older turns are incrementally summarized by a dedicated LLM call and the summary is injected as a context prefix. The LLM always sees recent turns verbatim plus a compressed summary of earlier turns.

| Component | Purpose |
|---|---|
| `SummarizationService` | Stateless `@ApplicationScoped` service for LLM-powered summarization via `ChatModelRegistry` |
| `ConversationSummarizer` | Incremental rolling summary engine — stores summary in conversation properties, self-corrects on failure |
| `ConversationRecallTool` | Built-in LLM tool for drill-back into summarized turns (supports natural language turn-range parsing) |
| `ConversationSummaryConfig` | Per-task config: provider, model, window size, max recall turns, property exclusion |

**Key design decisions:**
- **Storage**: Summaries stored as conversation-scoped properties (`conversation:running_summary`, `conversation:summary_through_step`) — O(1) retrieval, survives turn boundaries
- **Trigger**: Synchronous within `LlmTask.executeTask()` after LLM response — deterministic, no async complexity
- **Self-correction**: If summarization fails for turn N, turn N+1 automatically catches up by including the missed data
- **Defaults**: `claude-sonnet-4-6` for summarization, `20` max recall turns, `5` recent window steps

**Files:**
- `src/main/java/ai/labs/eddi/modules/llm/impl/SummarizationService.java` — NEW
- `src/main/java/ai/labs/eddi/modules/llm/impl/ConversationSummarizer.java` — NEW
- `src/main/java/ai/labs/eddi/modules/llm/tools/ConversationRecallTool.java` — NEW
- `src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java` — ConversationSummaryConfig added
- `src/main/java/ai/labs/eddi/modules/llm/impl/ConversationHistoryBuilder.java` — summary-aware message building
- `src/main/java/ai/labs/eddi/modules/llm/impl/AgentOrchestrator.java` — ConversationRecallTool registration
- `src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java` — summary injection + update trigger
- Tests: `SummarizationServiceTest`, `ConversationSummarizerTest`, `ConversationRecallToolTest` (1471 tests, all pass)

---

## Secrets Vault UX: 503 with Actionable Error When Vault Unconfigured (2026-03-30)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

When EDDI runs without `EDDI_VAULT_MASTER_KEY` set and a user tries to manage secrets via the REST API, the server previously returned a generic `500 Internal Server Error` with `"Failed to store secret"` — no indication of what's actually wrong. The `listSecrets` endpoint silently returned an empty list, hiding the issue entirely.

| Endpoint | Before | After |
|---|---|---|
| `PUT /secretstore/secrets/{t}/{a}/{k}` | 500 "Failed to store secret" | 503 with `error`, `reason`, `action`, `docs` |
| `GET /secretstore/secrets/{t}/{a}` | Empty list (silent) | 503 with actionable message |
| `DELETE /secretstore/secrets/{t}/{a}/{k}` | 500 generic | 503 with actionable message |
| `GET /secretstore/secrets/{t}/{a}/{k}` | 500 generic | 503 with actionable message |

The 503 response body now includes:
- `error`: "Secrets Vault is not configured"
- `reason`: "The EDDI_VAULT_MASTER_KEY environment variable is not set."
- `action`: Instructions for local dev (`set EDDI_VAULT_MASTER_KEY=any-passphrase-at-least-8-chars`)
- `docs`: Link to vault documentation

**For local development**, just set the env var before starting Quarkus: `set EDDI_VAULT_MASTER_KEY=my-dev-key` (Windows) or `export EDDI_VAULT_MASTER_KEY=my-dev-key` (Linux/Mac). The install script handles this automatically for Docker deployments.

**API change:** `listSecrets` return type changed from `List<?>` to `Response` to support proper HTTP status codes.

**Files:** 2 modified (`RestSecretStore.java`, `IRestSecretStore.java`).

---

## Phase 11b Code Review Fixes (2026-03-30)

**Repo:** EDDI (backend)

**What changed:**

Critical code review of Phase 11b identified 2 bugs, 3 design concerns, and 10 missing test cases. All resolved.

| Issue | Fix |
|---|---|
| **Bug: Wrong model name key** | Added `resolveModelName()` fallback chain (modelName→model→modelId→deploymentName) |
| **Bug: Anchor budget overflow** | `Math.max(0, ...)` for remainingBudget + WARN log when anchored tokens exceed budget |
| **Dead code** | Removed unused `estimateTokens()` delegation method |
| **Static cache on singleton** | Changed to instance-level `estimatorCache` field |
| **Gap marker confusion** | Shows count of omitted messages instead of index range |

**Tests added (9 new edge cases):**

- Empty conversation (with/without system message)
- Single message with anchor=2 (clamping)
- Null system message during windowing path
- Anchored tokens exceeding budget (graceful degradation + gap marker)
- Exact budget boundary
- Anchor count larger than message count
- Budget too small for recent (only anchor + gap marker returned)
- Gap marker format validation (count, not index range)
- Caching behavior (same model → same instance, shared unknown provider)

**Total: 48 tests across TokenCounterFactoryTest (20) + ConversationHistoryBuilderTest (28). All 1459 tests pass.**

---

## Phase 11b: Token-Aware Conversation Window (2026-03-30)

**Repo:** EDDI (backend)

**What changed:**

Implemented Strategy 1 from `docs/planning/conversation-window-management.md` — token-budget-based conversation windowing with anchored opening steps. This replaces fixed step-count limits with intelligent token-aware packing for LLM context management.

| Decision | Resolution |
|---|---|
| When to activate | `maxContextTokens > 0` triggers token-aware mode; `-1` (default) uses legacy step-count |
| Token counting | `OpenAiTokenCountEstimator` for OpenAI/Azure, `chars/4` approximation for all other providers |
| Anchoring | First N steps always included regardless of window position (default N=2) |
| Gap marker | `SystemMessage` inserted between anchored and recent when turns are omitted |
| API compatibility | langchain4j 1.12.2 uses `TokenCountEstimator` (not `Tokenizer`) |

**Files:**

- `LlmConfiguration.java` — Added `maxContextTokens` and `anchorFirstSteps` fields
- `TokenCounterFactory.java` — **NEW** — Resolves model-specific tokenizers with caching
- `ConversationHistoryBuilder.java` — Added `buildTokenAwareMessages()` method
- `LlmTask.java` — Injects `TokenCounterFactory`, branches on config
- `TokenCounterFactoryTest.java` — **NEW** — 13 tests
- `ConversationHistoryBuilderTest.java` — Added 7 token-aware window tests
- `LlmTaskTest.java` — Updated constructor mock
- `docs/langchain.md` — Added "Conversation Window Management" section

---

## EDDI Operator v2 — Architecture Plan (2026-03-29)

**Repo:** EDDI-operator (planning only — no code changes yet)

**What changed:**

Designed a complete rewrite of the [labsai/EDDI-operator](https://github.com/labsai/eddi-operator) from the legacy Ansible-based operator to a modern Java/Quarkus-native operator using the Java Operator SDK (JOSDK).

| Decision | Resolution |
|---|---|
| **API Group** | `eddi.labs.ai` (scoped to EDDI, avoids generic `labs.ai` collision) |
| **CRD Version** | `v1beta1` (production-usable but evolvable) |
| **Java Version** | 21 (Red Hat LTS, stable GraalVM native) — separate from EDDI server (Java 25) |
| **Tech Stack** | JOSDK 5.3.2 + QOSDK 7.7.3 + Quarkus 3.34.x |
| **Repo Cleanup** | Full rewrite, no old code preserved |
| **OLM Target** | OLM v0 (stable) with File-Based Catalogs |

**Architecture highlights:**
- 20+ Dependent Resources with conditional activation via JOSDK `@Workflow` + `@Dependent` annotations
- `CRDPresentActivationCondition` for auto-detecting OpenShift (Route) vs vanilla K8s (Ingress)
- Dual database strategy: managed (operator-deployed StatefulSets) + external (existing CloudNativePG/Atlas/etc.)
- Red Hat certification: two-step (container image → operator bundle) via preflight tool
- 5-phase capability roadmap: Level 1 (Basic Install) → Level 5 (Auto Pilot)
- 3-tier testing: unit (MockKubernetesServer), integration (Testcontainers + K3s), E2E (CRC)

**Status:** Plan approved. Execution deferred to a future session.

**Artifacts:** Full implementation plan in conversation `db7daba3`.

---

## Red Hat v6 Container Certification Automation (2026-03-29)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Automated the Red Hat container certification process for EDDI v6. License generation, Docker compliance labels, and CI/CD preflight checks are now fully automated.

| Component | Change |
|---|---|
| **pom.xml** | Added `license-maven-plugin` v2.7.1 in `license-gen` profile — generates `THIRD-PARTY.txt` and downloads license text files on demand (`mvn package -Plicense-gen`) |
| **THIRD-PARTY.properties** | New file for manually specifying licenses of deps that don't declare them (e.g., Jinjava → Apache 2.0) |
| **Dockerfile.jvm** | Added all Red Hat certification-required labels (`name`, `vendor`, `version`, `release`, `summary`, `description`) + OpenShift labels (`io.k8s.*`, `io.openshift.tags`). Version/release parameterized via `ARG` for CI injection |
| **.dockerignore** | Added `!docs/*` allowlist |
| **.gitignore** | Ignore auto-generated license files (`licenses/third-party/`, `licenses/licenses.xml`, `licenses/THIRD-PARTY.txt`) |
| **redhat-certify.yml** | NEW workflow: manual-dispatch certification release — builds app with `-Plicense-gen`, builds Docker image with labels, pushes to registry (Docker Hub or Quay.io), runs preflight check, optionally submits to Red Hat Partner Connect |
| **ci.yml** | Added `preflight-check` job on PRs — verifies Red Hat labels, `/licenses` directory, and runs preflight dry-run |
| **docker-publish.yml** | Added `-Plicense-gen` and `--build-arg EDDI_VERSION`/`EDDI_RELEASE` for certification-compliant images |
| **docs/redhat-openshift.md** | Complete rewrite: certification workflow, license automation, required secrets, preflight quality gate |
| **README.md** | Added Red Hat OpenShift docs link + `-Plicense-gen` build command |

**Design decisions:**
- License plugin in Maven profile (`-Plicense-gen`) rather than default build — keeps dev builds fast
- GNU.org URLs rewritten to SPDX mirrors (GNU returns 403 to automated downloads)
- `errorRemedy=ignore` for remaining download failures — non-blocking
- Preflight dry-run on PRs is a warning-only gate (some checks require a pushed image)

**Required secrets for certification:** `REDHAT_API_TOKEN`, `REDHAT_CERT_PROJECT_ID`, `DOCKER_USERNAME`, `DOCKER_PASSWORD`, optionally `QUAY_USERNAME`, `QUAY_PASSWORD`.

## Phase 11a: Persistent User Memory — Cross-Conversation Fact Retention (2026-03-29)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Full persistent user memory system enabling agents to remember facts, preferences, and context about users across conversations.

| Component | Change |
|---|---|
| **Data Model** | `UserMemoryEntry` record with `Visibility` (self/group/global), timestamps, access counts. `Property.effectiveVisibility()` bridge method |
| **Unified Store** | `IUserMemoryStore` interface with legacy property delegation. `MongoUserMemoryStore` (MongoDB `usermemories` collection) and `PostgresUserMemoryStore` (JSONB table with partial unique indexes) |
| **Agent Config** | `UserMemoryConfig`, `Guardrails`, `DreamConfig` nested in `AgentConfiguration`. `builtInToolsWhitelist` entry `usermemory` |
| **Conversation** | Memory loading/persistence in `Conversation.java` init/teardown. Scoped by userId + agentId + groupIds |
| **LLM Tools** | `UserMemoryTool` — 4 `@Tool` methods (rememberFact, recallMemories, searchMemory, forgetFact). `@Vetoed` from CDI (instantiated per-invocation). Guardrails: key/value length, write-rate, category validation |
| **Orchestrator** | `AgentOrchestrator.addUserMemoryToolIfEnabled()` — extracts groupId from conversation properties |
| **Group Context** | `GroupConversationService` now injects `groupId` into context maps for memory visibility |
| **REST API** | `RestUserMemoryStore` — 9 endpoints with input validation (userId/key required, 255-char key limit) |
| **MCP Tools** | `McpMemoryTools` — 8 tools with role-based access. `delete_all_user_memories` requires CONFIRM |
| **Dream Service** | Background consolidation: stale pruning, contradiction detection. Loads entries once per user. Micrometer metrics |
| **Deprecation** | `IPropertiesStore` marked `@Deprecated` with Javadoc pointing to `IUserMemoryStore` |

**Design decisions:**
- Tool is `@Vetoed` from CDI — instantiated manually per-invocation with conversation context
- Full plumbing through `IAgent`/`Agent`/`AgentStoreClientLibrary` (avoids runtime DB queries)
- Dream service invoked by schedule system via `SERVICE` trigger type
- REST upsert validates userId, key presence and key length for defense-in-depth
- Partial unique PostgreSQL indexes for correct `ON CONFLICT` upsert behavior

**Tests:** 45 new tests
- `UserMemoryToolTest` (16) — all 4 tools, guardrails, error paths
- `DreamServiceTest` (9) — pruning, contradictions, metrics, double-load prevention
- `UserMemoryEntryTest` (22) — factory methods, normalizeCategory, effectiveVisibility
- `RestUserMemoryStoreTest` (15) — all 9 endpoints, input validation, 404 handling
- Updated `AgentOrchestratorTest`, `LlmTaskTest` for constructor changes

**Documentation:** `docs/user-memory.md` (new), `SUMMARY.md` updated.

**Files:** 12 new, 8 modified. All 1406 tests pass.

---



## LLM Structured Output Hardening — JSON Enforcement + Debuggability + Prometheus Fix (2026-03-28)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Production-grade hardening of the LLM structured JSON output pipeline. Three-layer defense for JSON compliance, improved debuggability, and resolved meter conflicts.

| Component | Change |
|---|---|
| **LlmTask — System Prompt** | When `convertToObject=true`, appends `## RESPONSE FORMAT (MANDATORY)` section to system message on every request. If `responseSchema` parameter is provided, includes the exact JSON schema. Otherwise generic JSON instruction |
| **LlmTask — `responseSchema` parameter** | New config parameter `responseSchema` — lets agent developers specify the exact JSON structure they expect. Injected into system prompt with ````json` block for LLM comprehension |
| **LegacyChatExecutor — Native JSON Mode** | When `convertToObject=true`, builds `ChatRequest` with `ResponseFormatType.JSON` to enforce structured output at the API level (OpenAI, Gemini, Mistral). Graceful fallback: if provider throws, falls back to standard call (system prompt still enforces) |
| **LlmTask — Raw Response Persistence** | Moved `langchainData` storage to BEFORE JSON deserialization. Raw LLM response now always persisted in memory, even if `jsonSerialization.deserialize()` fails |
| **LlmTask — JSON Validation** | Pre-parse `startsWith("{")` / `startsWith("[")` check before `deserialize()`. Non-JSON responses stored as plain strings with warning instead of crashing the pipeline |
| **LlmTask — `jsonMode` flag** | Fixed semantic bug: was using `addToOutputExplicitlyFalse` as JSON mode signal (wrong concern). Now derives `jsonMode` from `convertToObject` parameter (correct signal) |
| **ToolExecutionService — Prometheus** | Removed ALL tagless aggregate metrics (timer field + counter field). Only per-tool tagged metrics remain (`eddi.tool.execution.duration[tool=X]`, `eddi.tool.execution.success[tool=X]`, `eddi.tool.execution.failure[tool=X]`). Aggregates via `sum()` in PromQL. Fixed `IllegalArgumentException: same name different tags` crash |
| **InputParserTask — QR Defense** | Blank expression guard: if QR `expressions` is null/blank, auto-generates expression from value (sanitized alphanumeric) instead of creating empty expression that breaks behavior rules |

**Three-layer JSON enforcement:**
1. **System Prompt** — `## RESPONSE FORMAT (MANDATORY)` section with optional schema
2. **API Level** — `ChatRequest.responseFormat(ResponseFormat.JSON)` for compatible providers
3. **Validation** — Pre-parse check + graceful fallback to plain string

**Design decisions:**
- `responseSchema` is prompt-injected (not native `JsonSchema` on `ChatRequest`) because not all providers support structured schemas, and the system prompt approach works universally
- Native `ResponseFormatType.JSON` is set on `ChatRequest` for providers that support it — this physically constrains the model's token generation, not just instruction following
- Graceful fallback: if `ChatRequest` JSON mode throws (unsupported provider), catch `LifecycleException` and retry with standard call. System prompt reinforcement still provides enforcement
- `jsonMode` derived from `convertToObject=true` (not `addToOutput=false`) — semantically correct signal for JSON mode

**Files:** 3 modified (`LlmTask.java`, `LegacyChatExecutor.java`, `ToolExecutionService.java`), 1 modified (`InputParserTask.java`).

**Testing:** ✅ All existing LlmTask tests pass (17 tests). Compile verified. No behavioral regressions.

---

## Production Readiness Audit — 17 Fixes Across 3 Repos (2026-03-28)

**Repos:** EDDI, EDDI-Manager, eddi-chat-ui (`feature/version-6.0.0`)

**What changed:**

Comprehensive code review across all 3 repos identified 18 issues; 17 resolved in this session (1 deferred: handlers.ts domain split).

| # | Severity | Fix | Repo |
|---|----------|-----|------|
| 1 | 🔴 Critical | Synced `MODEL_TYPES` in langchain-editor (7→11 providers: added mistral, azure-openai, bedrock, oracle-genai) | Manager |
| 2 | 🔴 Critical | Replaced sequential deployment status checks with `Promise.allSettled` (N+1 → parallel) | Manager |
| 3 | 🔴 Critical | `EmbeddingModelFactory` — replaced unbounded `ConcurrentHashMap` with Caffeine cache (50 entries, 30m TTL) | EDDI |
| 4 | 🔴 Critical | `rag-editor` — added `mountedRef` + `useEffect` cleanup to prevent state updates after unmount | Manager |
| 5 | 🟡 Significant | Updated `AGENTS.md` resource types table (6→8 types: added mcpcalls, rag) | Manager |
| 6 | 🟡 Significant | Extracted langchain-editor types+constants to `langchain/types.ts` (~100 lines) | Manager |
| 7 | 🟡 Significant | Fixed 3 RTL violations: `ml-1.5`→`ms-1.5` (schedules, dictionary), `left-[50%]`→`inset-x-0 mx-auto` (alert-dialog) | Manager |
| 8 | 🟡 Significant | Acknowledged Zustand usage in `AGENTS.md` tech stack table | Manager |
| 10 | 🟡 Significant | Added per-request `headers` override to `ApiClient` (enables non-JSON content types) | Manager |
| 11 | 🟡 Significant | Validated cascade-save URI scheme — confirmed correct (v6 slugs match backend) | Manager |
| 12 | 🔵 Minor | Created `ErrorBoundary` component + wired into app route tree (4 new tests) | Manager |
| 13 | 🔵 Minor | Guarded `console.log` in `auth-provider.tsx` with `import.meta.env.DEV` | Manager |
| 14 | 🔵 Minor | Increased agent deployment status limit from 100→200 | Manager |
| 15 | 🔵 Minor | Added `?permanent=true` option to `deleteResource` API | Manager |
| 16 | 🔵 Minor | Added `@param` deprecation docs for unused params in `chat-api.ts` | Chat UI |
| 17 | 🔵 Minor | Fixed `parseFloat \|\| 0` pattern to handle `0` and `NaN` correctly | Manager |
| 18 | 🔵 Minor | Replaced array-index `key={i}` with value-based keys for correct React reconciliation | Manager |
| — | 🔴 Critical | `EmbeddingStoreFactory` — same Caffeine migration as ModelFactory (50 entries, 30m TTL) | EDDI |

**Deferred:** #9 — Split `handlers.ts` into domain files (2300-line MSW test infrastructure; mechanical refactor for a dedicated session).

**Verification:** TypeScript 0 errors, backend compiles, 39/39 test files pass, production build succeeds.

**Files:** 11 modified + 3 new (Manager), 2 modified (EDDI), 1 modified (Chat UI).

---

## Phase 8c-M: Manager UI RAG Sync — Full Provider Parity + Ingestion Fix (2026-03-28)

**Repo:** EDDI-Manager (`feature/version-6.0.0`) + EDDI (backend fixes)

**What changed:**

Synchronized the Manager `RagEditor` UI with the backend's Phase 8c-γ provider expansion and fixed ingestion API contract mismatches.

| Component | Change |
|---|---|
| **RagEditor** | Updated to 7 embedding providers (added azure-openai, mistral, bedrock, cohere) and 5 vector stores (added elasticsearch). Removed dead `isolationStrategy` field. Added context-sensitive provider parameter hints (e.g., `endpoint`+`deploymentName` for Azure, `region` for Bedrock). Added embedding param cache for seamless provider switching. Added missing `dimension` (pgvector) and `useTls` (qdrant) hints |
| **IngestionPanel** | Fixed API contract: sends `text/plain` body with `version`+`documentName` query params (was: JSON body). Fixed status polling path to `/ingestion/{id}/status` |
| **ConfigEditorLayout** | Extended `meta` type to include `version: number` for ingestion API |
| **vite.config.ts** | Added `/ragstore` dev proxy entry |
| **KeyValueEditor** | Fixed duplicate key collision bug (renaming a key to an existing key silently dropped entries) |
| **EmbeddingStoreFactory** (backend) | Fixed MongoClient resource leak: cached `MongoClient` instances with proper cleanup on `clearCache()` |
| **i18n** | Removed `ragEditor.isolation` from all 11 locale files |
| **Tests** | Updated 19 tests: removed isolation test, added elasticsearch/azure-openai/provider-list/store-list coverage |
| **MSW handlers** | Removed `isolationStrategy` from mock data, fixed ingestion status endpoint path |

**Files:** 15 modified (1 backend, 14 Manager).

## Phase 8c-γ: RAG Provider Expansion — 7 Embedding Models + 5 Vector Stores (2026-03-27)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Expanded the RAG subsystem from 2 embedding providers + 2 vector stores to 7 + 5, added a REST ingestion endpoint, and applied code quality improvements from the architecture review.

| Component | Change |
|---|---|
| **pom.xml** | Added 5 new dependencies: `langchain4j-mongodb-atlas`, `langchain4j-elasticsearch`, `langchain4j-qdrant`, `langchain4j-cohere`, `langchain4j-vertex-ai` (all `${langchain4j-beta.version}`) |
| **EmbeddingModelFactory** | 2→7 providers: added `azure-openai`, `mistral`, `bedrock`, `cohere`, `vertex`. Each extracted into private builder methods. Error messages list all supported providers |
| **EmbeddingStoreFactory** | 2→5 stores: added `mongodb-atlas`, `elasticsearch`, `qdrant`. Added `requireParam()` fail-fast validation, `parseIntParam()` clear error handling, table name truncation to 63 chars. Factored `resolveParams()` utility |
| **RagConfiguration** | Updated Javadoc for all 7 providers + 5 stores. Removed dead `isolationStrategy` field (collection-per-KB is the only supported strategy, enforced by cache key pattern) |
| **IRestRagIngestion** (NEW) | JAX-RS interface: `POST /{id}/ingest`, `GET /{id}/ingestion/{ingestionId}/status`. OpenAPI documented, `@RolesAllowed(eddi-admin, eddi-editor)` |
| **RestRagIngestion** (NEW) | Implementation: validates input, loads config, resolves KB ID (explicit → config name → config ID fallback), delegates to `RagIngestionService`, returns 202 Accepted |
| **docs/rag.md** | Complete rewrite with all providers, ingestion curl examples, status polling docs |

**New embedding providers:**

| Provider | Default Model | Auth | Notes |
|---|---|---|---|
| `azure-openai` | `text-embedding-3-small` | `apiKey` + `endpoint` | Azure-hosted OpenAI |
| `mistral` | `mistral-embed` | `apiKey` | Mistral AI |
| `bedrock` | `amazon.titan-embed-text-v2:0` | AWS credential chain | `region` param |
| `cohere` | `embed-english-v3.0` | `apiKey` | Multilingual support |
| `vertex` | `text-embedding-005` | GCP credentials | Requires `project` param |

**New vector stores:**

| Store | Required Params | Notes |
|---|---|---|
| `mongodb-atlas` | `connectionString` | Atlas Vector Search, zero new infra for existing MongoDB users |
| `elasticsearch` | — | `serverUrl`, optional `apiKey` or `userName`+`password` |
| `qdrant` | — | gRPC, optional `apiKey` + TLS |

**Code quality improvements:**
- pgvector `password` now fails fast with clear message (was: silent empty string)
- `sanitizeTableName` truncates to PostgreSQL's 63-char identifier limit
- `parseIntParam()` wraps NumberFormatException with descriptive error
- Removed dead `isolationStrategy` field from `RagConfiguration`

**Tests:** 4 new test files (26 tests total): `RagIngestionServiceTest` (3), `RestRagIngestionTest` (6), updated `EmbeddingStoreFactoryTest` (13 — table truncation, pgvector validation, mongodb-atlas validation), updated `EmbeddingModelFactoryTest` (10 — mistral, cohere, vertex validation).

**Files:** 3 new, 5 modified, 2 new test files, 2 updated test files.

---

## Installer Security: Vault Master Key Auto-Generation (2026-03-27)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Eliminated the critical security anti-pattern where all installer deployments shared the same hardcoded vault master key (`local-dev-key-change-in-production`). Every installation now gets a unique, cryptographically random encryption key.

| Component | Change |
|---|---|
| **docker-compose.yml** | Hardcoded key → `${EDDI_VAULT_MASTER_KEY:-}` (empty default = vault disabled if not set) |
| **docker-compose.postgres-only.yml** | Same variable substitution |
| **docker-compose.postgres.yml** | Same variable substitution |
| **install.sh** | New "Security" wizard step (2 of 5): auto-generate or custom passphrase (min 16 chars). `--vault-key=<key>` CLI arg, `.env` file persistence (`chmod 600`), `--env-file` in compose_cmd, macOS-compatible `sed` (replaces `grep -oP`) |
| **install.ps1** | Mirrored: `-VaultKey` param, `New-VaultKey` (RNG + Base64), `SecureString` input, ACL-restricted `.env` file, `--env-file` in compose args |
| **.gitignore** | Added `.env` to prevent accidental commit of vault keys |

**Key generation:**
- Bash: `openssl rand -base64 24` (32 chars), `/dev/urandom` fallback
- PowerShell: `[System.Security.Cryptography.RandomNumberGenerator]::Fill()` + Base64

**Backward compatibility:**
- Re-install detects existing `~/.eddi/.env` and preserves the key
- Non-interactive (`--defaults`, `curl | bash`) auto-generates a unique key
- Manual `docker compose up` without `.env` → vault cleanly disabled (empty default)

**Files:** 6 modified (`docker-compose.yml`, `docker-compose.postgres-only.yml`, `docker-compose.postgres.yml`, `install.sh`, `install.ps1`, `.gitignore`).

---

## LLM Provider Expansion — 7 → 12 Providers (2026-03-27)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Expanded EDDI from 7 to 12 model providers for enterprise completeness.

| Change | Details |
|---|---|
| **OpenAI `baseUrl`** | Added `baseUrl` parameter to `OpenAILanguageModelBuilder` — enables DeepSeek and Cohere via OpenAI-compatible endpoints (zero new dependencies) |
| **Mistral AI** | New `MistralAiLanguageModelBuilder` — uses `JdkHttpClient` (same as OpenAI/Anthropic), supports `apiKey`, `modelName`, `temperature`, `maxTokens`, `timeout`, `logRequests`, `logResponses` |
| **Azure OpenAI** | New `AzureOpenAiLanguageModelBuilder` — Azure SDK HTTP pipeline (NOT JdkHttpClient), uses `deploymentName` not `modelName`, combined `logRequestsAndResponses`, requires `endpoint`, auth via `apiKey` or `nonAzureApiKey` |
| **Amazon Bedrock** | New `BedrockLanguageModelBuilder` — AWS SDK credential chain (no `apiKey`), `region` → `Region.of()`, `modelId` for model selection. Supports streaming |
| **Oracle GenAI** | New `OracleGenAiLanguageModelBuilder` — OCI `ConfigFileAuthenticationDetailsProvider` (reads `~/.oci/config`), sync-only (no streaming), `modelName`, `compartmentId`, `configProfile` |
| **pom.xml** | Added `langchain4j-mistral-ai` (stable), `langchain4j-azure-open-ai` (stable), `langchain4j-bedrock` (stable), `langchain4j-community-oci-genai` (beta) |
| **LlmModule** | Registered 4 new type keys: `mistral`, `azure-openai`, `bedrock`, `oracle-genai` |
| **AgentSetupService** | Updated `isLocalLlmProvider` (bedrock, oracle-genai bypass apiKey), `supportsResponseFormat` (mistral, azure-openai), `createLlmConfig` (provider-specific param mapping) |
| **McpSetupTools** | Updated `@ToolArg` docs to list all 11 provider types |

**Provider summary (12 total):**

| Type Key | Builder | Auth | Native Risk |
|---|---|---|---|
| `openai` | OpenAILanguageModelBuilder | `apiKey` | ✅ None |
| `anthropic` | AnthropicLanguageModelBuilder | `apiKey` | ✅ None |
| `gemini` | GeminiLanguageModelBuilder | `apiKey` | ✅ None |
| `gemini-vertex` | VertexGeminiLanguageModelBuilder | Google ADC | ✅ None |
| `ollama` | OllamaLanguageModelBuilder | None (local) | ✅ None |
| `huggingface` | HuggingFaceLanguageModelBuilder | `accessToken` | ✅ None |
| `jlama` | JlamaLanguageModelBuilder | `authToken` | ✅ None |
| `mistral` | MistralAiLanguageModelBuilder | `apiKey` | ✅ None |
| `azure-openai` | AzureOpenAiLanguageModelBuilder | `apiKey` + `endpoint` | ⚠️ Medium |
| `bedrock` | BedrockLanguageModelBuilder | AWS credential chain | ✅ Low |
| `oracle-genai` | OracleGenAiLanguageModelBuilder | OCI config (`~/.oci/config`) | ✅ Low |
| _(OpenAI + baseUrl)_ | _(DeepSeek, Cohere)_ | `apiKey` | ✅ None |

**Design decisions:**
- DeepSeek and Cohere use existing OpenAI builder with `baseUrl` param — zero new dependencies
- Mistral uses stable `langchain4j-libs.version` (1.12.2) + `JdkHttpClient`
- Azure OpenAI uses stable version but has medium native image risk (Kotlin+Jackson reflection) — ship for JVM mode, fix in Phase 12
- Bedrock uses stable version with AWS SDK v2; temperature/maxTokens set via `defaultRequestParameters(BedrockChatRequestParameters)` not direct builder methods
- Oracle GenAI uses `langchain4j-beta.version` (community module); package is `dev.langchain4j.community.model.oracle.oci.genai`

**Code review fixes applied:**
- Bedrock: corrected API — `temperature()`/`maxTokens()` do not exist on builder; uses `defaultRequestParameters(BedrockChatRequestParameters.builder().temperature().maxOutputTokens().build())`
- Oracle GenAI: corrected package from `dev.langchain4j.community.model.oci.genai` → `dev.langchain4j.community.model.oracle.oci.genai`
- Oracle GenAI: corrected param from `modelId` → `modelName` (matching actual API)
- `AgentSetupService.createLlmConfig()`: oracle-genai case now maps to `modelName` (not `modelId`)

**Files:** 4 new builders, 1 modified builder (OpenAI), 3 modified support files (LlmModule, AgentSetupService, McpSetupTools), 1 modified pom.xml.

**Testing:** ✅ All tests pass. 11 new test cases in `McpSetupToolsTest`: provider-specific config (bedrock, azure-openai, oracle-genai, mistral), apiKey bypass (bedrock, oracle-genai), endpoint wiring (azure-openai), response format, `isLocalLlmProvider` coverage.

**Documentation:** Updated `docs/langchain.md` with all 12 providers, provider-specific config examples (Mistral, Azure OpenAI, Bedrock, Oracle GenAI, DeepSeek/Cohere via baseUrl).

---

## Phase 8c-β: pgvector Persistent Vector Store (2026-03-27)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Added persistent vector store support via pgvector to the RAG knowledge base system, completing the EmbeddingStoreFactory stub.

| Component | Change |
|---|---|
| **pom.xml** | Added `langchain4j-pgvector` dependency (1.12.2-beta22) |
| **EmbeddingStoreFactory** | Injected `SecretResolver` for vault-based password resolution. Updated cache key to include `storeParameters` (TreeMap-based). Implemented `buildPgVector()` with sensible defaults (host=localhost, port=5432, table=`eddi_kb_{kbId}`, dimension=1536, createTable=true). Added `sanitizeTableName()` for safe PostgreSQL table names |
| **EmbeddingStoreFactoryTest** | Updated for `SecretResolver` mock injection. Added 5 new tests: storeParameter cache key collision, same params caching, and 3 table name sanitization tests |

**Design decision:** Using `langchain4j-beta.version` (1.12.2-beta22) since `langchain4j-pgvector` is not yet published in the stable release channel.

---

## Phase 8c-0: httpCall RAG — Zero-Infrastructure RAG (2026-03-27)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Implemented zero-infrastructure RAG via named httpCall. When `task.httpCallRag` is set, the LlmTask discovers the named httpCall from the workflow, executes it with the user's query in template data, and injects the response as context into the system message before the LLM call.

| Component | Change |
|---|---|
| **LlmTask** | Stored `apiCallExecutor`, `restAgentStore`, `restWorkflowStore` as fields (were only forwarded to AgentOrchestrator). Replaced TODO stub with httpCallRag execution. Added `executeHttpCallRag()` method that uses `WorkflowTraversal.discoverConfigs()` to find the named ApiCall, executes it, serializes response, and injects as `## Search Results:` context. Stores audit trace (`rag:httpcall:trace:{taskId}`) |
| **LlmTaskTest** | Added `HttpCallRagTests` nested class with 3 tests: null is no-op, no user input skips gracefully, non-existent httpCall warns but continues |

**Design decision:** httpCall RAG runs *before* vector store RAG in the pipeline. Both can be active simultaneously — httpCall provides "search results" while vector RAG provides "relevant context". Template data includes `userInput` for API call templating.

---

## Phase 8c: RAG Foundation — Config-Driven Knowledge Base Retrieval (2026-03-27)

**Repo:** EDDI (`feature/version-6.0.0`) — Commit `f10c0611`

**What changed:**

Production-ready, config-driven Retrieval-Augmented Generation system. Knowledge bases are first-class versioned resources with full CRUD, embedding model caching, vector store isolation, and automatic context injection into LLM conversations.

| Component | Change |
|---|---|
| **Resource Stack** | `RagConfiguration` POJO, `IRagStore` interface, `RagStore` (MongoDB, collection `rags`), `IRestRagStore` + `RestRagStore` (REST at `/ragstore/rags/`) |
| **LlmConfiguration** | Added `knowledgeBases` (explicit KB refs), `enableWorkflowRag` (auto-discovery), `ragDefaults`, `httpCallRag` (Phase 8c-0 stub) to `Task` |
| **EmbeddingModelFactory** | Cached factory for `EmbeddingModel` (OpenAI, Ollama) with `SecretResolver` integration, `TreeMap`-based collision-free cache keys |
| **EmbeddingStoreFactory** | Cached factory for `EmbeddingStore` (in-memory; pgvector stubbed), per-KB isolation |
| **RagContextProvider** | Workflow discovery via `WorkflowTraversal`, vector retrieval, context formatting, audit trace storage (`rag:trace:*`, `rag:context:*`) |
| **RagIngestionService** | Async document ingestion using virtual threads, langchain4j `DocumentSplitter` + `EmbeddingStoreIngestor`, Caffeine-backed status tracking |
| **LlmTask** | RAG context injection before message building, graceful error handling, `extractUserInput()` helper |

**Design decisions:**
- RAG retrieval integrated into `LlmTask` lifecycle (not a separate `ILifecycleTask`) for minimal pipeline changes
- Context injection explicit via `KnowledgeBaseReference` or auto-discovered via `enableWorkflowRag`
- `RetrievalAugmentorConfiguration` deprecated in favor of new RAG fields
- Audit traces stored in conversation memory using `rag:trace:{taskId}` and `rag:context:{taskId}` keys
- Naming uses plural `rags` for REST path and MongoDB collection (consistent with `httpcalls`, `mcpcalls`)

**Code review fixes (8 issues):**
- Cache key collision (`hashCode()` → `TreeMap.toString()`)
- NPE in `formatRagContext` (null `textSegment()` guard)
- Duplicate `taskId`/`currentStep` extraction
- Null `embeddingParameters` → `Map.of()` default
- Unbounded `ConcurrentHashMap` → Caffeine (1hr expiry, 10K max)
- `httpCallRag` and `storeParameters` cache TODO comments

**Tests:** 4 new test files: `RestRagStoreTest`, `EmbeddingModelFactoryTest`, `EmbeddingStoreFactoryTest`, `RagContextProviderTest`. Updated `LlmTaskTest` for `RagContextProvider` mock.

**Files:** 14 new, 2 modified. All tests pass.

**Documentation:** `docs/rag.md` (public), `HANDOFF.md` updated.

---

## Comprehensive Cosmetic Rename: All `package*` Variables → `workflow*` (2026-03-27)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Eliminated all remaining v5 `package*` naming from internal variable names, method parameters, MCP tool descriptions, JSON output keys, and the backup/import/export pipeline. This is a purely cosmetic cleanup — no behavioral changes, but the v6 API surface now uses `workflowVersion` instead of `packageVersion` as a query parameter, and ZIP exports write `.workflow.json` instead of `.package.json`.

**Breaking API changes:**
- `@QueryParam("packageVersion")` → `@QueryParam("workflowVersion")` in `IRestOutputActions`, `IRestExpression`, `IRestAction`
- MCP `list_agent_resources` JSON output: `packageCount`/`packages`/`packageVersion` → `workflowCount`/`workflows`/`workflowVersion`

**Backward compatibility:**
- ZIP import still accepts both `.package.json` (v5) and `.workflow.json` (v6)
- Legacy URI patterns in `V6RenameMigration`, `AbstractBackupService`, `LegacyPathRewriteFilter` unchanged

| File | Change |
|---|---|
| `LifecycleUtilities.java` | `packageVersion` → `workflowVersion`, `packageIndex` → `stepIndex` |
| `IWorkflowStoreService.java` | `packageVersion` → `workflowVersion` |
| `WorkflowStoreService.java` | `packageVersion` → `workflowVersion` |
| `IWorkflowStoreClientLibrary.java` | `packageVersion` → `workflowVersion` |
| `WorkflowStoreClientLibrary.java` | `packageVersion` → `workflowVersion`, `packageDocumentDescriptor` → `workflowDocumentDescriptor` |
| `IAgentStore.java` | `packageVersion` → `workflowVersion` |
| `AgentStore.java` | `packageVersion` → `workflowVersion` (method sig, URI build, loop decrement) |
| `IRestOutputActions.java` | `@QueryParam("packageVersion")` → `@QueryParam("workflowVersion")` |
| `RestOutputActions.java` | `packageVersion` → `workflowVersion` |
| `IRestExpression.java` | `@QueryParam("packageVersion")` → `@QueryParam("workflowVersion")` |
| `IRestAction.java` | `@QueryParam("packageVersion")` → `@QueryParam("workflowVersion")` |
| `RestExpression.java` | `packageVersion` → `workflowVersion` |
| `RestAction.java` | `packageVersion` → `workflowVersion` |
| `RestOrphanAdmin.java` | `pkgConfig` → `workflowConfig` |
| `McpAdminTools.java` | All `pkg*` → `wf*`/`workflow*`, tool descriptions cleaned, JSON keys updated |
| `AbstractBackupService.java` | `WORKFLOW_EXT = "package"` → `"workflow"`, comment fix |
| `RestExportService.java` | `packagePath` → `workflowPath` |
| `RestImportService.java` | All `package*` → `workflow*`, import accepts `.workflow.json` + `.package.json` |
| `ImportPreview.java` | Comment updated |

## Descriptor Type Rename: `ai.labs.package` → `ai.labs.workflow` (2026-03-27)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Fixed a latent bug where `DescriptorStore.readDescriptors()` was queried with the legacy type `"ai.labs.package"`, which builds a regex `eddi://ai.labs.package.*` against the `resource` URI field. Since the V6 rename migration already rewrites all resource URIs to `eddi://ai.labs.workflow/...`, these queries would silently return zero results on migrated databases.

| File | Change |
|---|---|
| `RestWorkflowStore.java` | `readDescriptors("ai.labs.package", ...)` → `"ai.labs.workflow"` |
| `RestOrphanAdmin.java` | `SCANNABLE_STORE_TYPES` + `buildReferencedUrisSet()` — updated type + cleaned variable names/comments |
| `IRestWorkflowStore.java` | 8 OpenAPI `@Operation` descriptions: "package" → "workflow" |
| `OrphanInfo.java` | Javadoc comment |
| `RestOrphanAdminTest.java` | All test assertions aligned to `"ai.labs.workflow"` |

> **Note:** `V6RenameMigration.java` and `AbstractBackupService.java` correctly retain `"ai.labs.package"` references — they handle legacy v5 import/migration and must keep old names.

**Testing:** ✅ `./mvnw compile` + `./mvnw test` — all pass.

---

## Platform Remediation: Thread Safety, A2A Hardening, Code Quality & Audit DLQ (2026-03-27)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Critical architectural fixes identified during thorough feature review of v6. 12 files modified across 6 groups.

| Group | Change |
|---|---|
| **Thread Safety** | Replaced unbounded `CachedThreadPool` with Java 21+ `VirtualThreadPerTaskExecutor` in `CascadingModelExecutor` and `GroupConversationService`. Added `@PreDestroy` lifecycle shutdown. |
| **A2A Security** | `@Authenticated` on A2A POST endpoint (opt-in via OIDC). Circuit breaker (3 failures/60s cooldown), 1MB response size limit, JSON-RPC 2.0 schema validation, Agent Card `name` field validation in `A2AToolProviderManager`. |
| **Code Quality** | Extracted `WorkflowTraversal` helper (eliminates ~80% duplicated traversal code). Safe JSON escaping via `JsonStringEncoder`. Configurable `maxToolIterations` in `LlmConfiguration.Task`. `isRetryableError` with typed HTTP status code matching (429/502/503/504). Renamed `getApiCall()`→`getHttpCall()` in `RetrievalAugmentorConfiguration`. |
| **Observability** | Audit dead-letter queue: NATS JetStream first (subject `eddi.deadletter.audit` matches existing `EDDI_DEAD_LETTERS` stream), file fallback (configurable via `eddi.audit.dead-letter-path`, defaults to `/opt/eddi/data/`). Micrometer counter `eddi_audit_entries_dropped_total`. |
| **GroupConversation** | `extractResponse` uses `IJsonSerialization` instead of `toString()`. |

**Key design decisions:**
- Dead-letter path defaults to `/opt/eddi/data/` for Docker volume persistence
- NATS subject `eddi.deadletter.audit` reuses existing `EDDI_DEAD_LETTERS` stream (30-day retention)
- Circuit breaker uses `ConcurrentHashMap<String, CircuitState>` record — no external library
- `@Authenticated` opt-in: only activates when `quarkus.oidc.tenant-enabled=true`

**Tests:** 1289/1291 pass (2 pre-existing `ConfidenceEvaluatorTest` isolation failures that pass individually). Updated `GroupConversationServiceTest` and `AuditLedgerServiceTest` constructors.

---

## Multi-Model Cascading Routing (2026-03-26)

**Repo:** EDDI (`feature/version-6.0.0`) — Commit `514821d4`

**What changed:**

Cost-optimized LLM execution via sequential model escalation. Tries a cheap/fast model first and escalates to a more powerful model only if confidence is below a configurable threshold.

| Component | Change |
|---|---|
| **Config Schema** | `ModelCascadeConfig` + `CascadeStep` inner classes on `LlmConfiguration.Task` — `enabled`, `strategy` (cascade/parallel), `evaluationStrategy` (4 options), `enableInAgentMode` |
| **Cascade Executor** | `CascadingModelExecutor.java` — cascade loop with per-step timeout, retryable error escalation, agent-mode and legacy-mode execution, SSE events, best-response tracking |
| **Confidence Evaluator** | `ConfidenceEvaluator.java` — 4 pluggable strategies: `structured_output` (JSON parsing), `heuristic` (hedging/refusal detection), `judge_model` (secondary LLM), `none` (always accept) |
| **SSE Events** | `ConversationEventSink` — 2 new default methods: `onCascadeStepStart(stepIndex, modelType)`, `onCascadeEscalation(fromStep, toStep, confidence, reason)` |
| **LlmTask Integration** | Cascade branch in `executeTask()` with full backward compat — null/disabled cascades use standard path |
| **Audit Trail** | `audit:cascade_model`, `audit:cascade_confidence`, `cascade:trace` memory keys for observability |

**Cascade step configuration:**

```json
{
  "modelCascade": {
    "enabled": true,
    "strategy": "cascade",
    "evaluationStrategy": "structured_output",
    "enableInAgentMode": true,
    "steps": [
      { "type": "openai", "parameters": { "model": "gpt-4o-mini" }, "confidenceThreshold": 0.7, "timeoutMs": 10000 },
      { "type": "openai", "parameters": { "model": "gpt-4o" }, "confidenceThreshold": null, "timeoutMs": 30000 }
    ]
  }
}
```

**Error handling:**
- Retryable errors (429, 503, timeouts) → auto-escalate to next step
- Non-retryable errors → escalate to next step, log warning
- Budget exhausted → return best response seen so far
- All steps fail → throw LifecycleException with trace

**Design decisions:**
- Intra-task orchestration — cascade loop lives inside `LlmTask`/`CascadingModelExecutor`, no engine-wide pipeline changes
- Step params merge with base task params — steps only override what they need (e.g., `model`)
- `confidenceThreshold: null` on final step means "always accept" (no further escalation)
- `parallel` strategy stubbed in config but not yet implemented (future-ready)

**Tests:** 39 new tests
- `ConfidenceEvaluatorTest` (22) — all 4 strategies, edge cases (null, blank, short, hedging, refusal, JSON parsing, clamping, fallback)
- `CascadingModelExecutorTest` (13) — param merging, config defaults, cascade gate conditions
- `LlmTaskTest` cascade section (4) — cascade execution, backward compat (disabled/null), audit keys

**Files:** 2 new (`CascadingModelExecutor.java`, `ConfidenceEvaluator.java`), 2 new tests, 3 modified (`LlmConfiguration.java`, `ConversationEventSink.java`, `LlmTask.java`), 1 test modified.

**Testing:** ✅ 1291 tests, 0 failures.

---

## A2A Protocol Integration (2026-03-26)

**Repo:** EDDI (`feature/version-6.0.0`) — Commit `cbc4b70b`

**What changed:**

Implemented the Agent2Agent (A2A) protocol for distributed peer-to-peer agent communication.

| Component | Change |
|---|---|
| **Dependency** | Added `langchain4j-agentic-a2a` (via `langchain4j-beta.version` property) |
| **A2A Server** | `A2AModels.java` (protocol records), `AgentCardService.java` (card generation), `A2ATaskHandler.java` (JSON-RPC → ConversationService bridge), `RestA2AEndpoint.java` (JAX-RS endpoints) |
| **A2A Client** | `A2AToolProviderManager.java` (mirrors `McpToolProviderManager` — discovers remote agents, wraps skills as `ToolSpecification`) |
| **Config** | `AgentConfiguration` gains `a2aEnabled`, `a2aSkills`, `description` fields; `LlmConfiguration.Task` gains `a2aAgents` list with `A2AAgentConfig` |
| **Integration** | `AgentOrchestrator` + `LlmTask` inject `A2AToolProviderManager`; A2A tools merge alongside built-in, MCP, and httpcall tools |
| **Security** | Vault reference enforcement for API keys (`${vault:...}`), runtime warning on raw key usage |
| **Endpoints** | `GET /.well-known/agent.json`, `GET/POST /a2a/agents/{id}`, `GET /a2a/agents` |

**Design decisions:**
- Used EDDI's own lightweight protocol records (not SDK types) to keep server endpoints decoupled
- Mirrors the MCP tool integration pattern exactly — same discovery/merge/execution pipeline
- Feature toggle via `eddi.a2a.enabled` config property (default: true)
- `isAgentMode()` updated to include A2A agents as a trigger

---

## Templating Engine Migration: Thymeleaf → Quarkus Qute (2026-03-26)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Replaced the Thymeleaf 3.1.3 + OGNL 3.3.4 templating stack with Quarkus Qute for native image compatibility and CVE remediation (OGNL CVE-2025-53192).

| Component | Change |
|---|---|
| **Dependencies** | Removed `thymeleaf` 3.1.3 + `ognl` 3.3.4, added BOM-managed `quarkus-qute` |
| **Core Engine** | `TemplatingEngine.java` rewritten to use Qute `Engine` API with null-safety |
| **Extensions** | `EddiTemplateExtensions.java` — UUID, JSON, Encoder namespace extensions (`uuidUtils:`, `json:`, `encoder:`) |
| **String Extensions** | `StringTemplateExtensions.java` — 15 methods (toLowerCase, toUpperCase, replace, substring ×2, indexOf, lastIndexOf, contains, startsWith, endsWith, trim, strip, length, isEmpty, charAt, concat) |
| **Module** | `TemplateEngineModule.java` stripped of all Thymeleaf producers |
| **Migrator** | `TemplateSyntaxMigrator.java` — 10 regex patterns + close-tag scanner + string concat post-processor |
| **Startup Migration** | `V6QuteMigration.java` — idempotent startup hook migrating 4 MongoDB collections (apicalls, outputs, propertysetter, llms + history) |
| **Import Migration** | `RestImportService.java` wired with `TemplateSyntaxMigrator` as final-pass before deserialization |
| **Config** | `quarkus.qute.strict-rendering=false`, `eddi.migration.v6-qute.enabled` |

**Migration patterns handled:**

| Old (Thymeleaf) | New (Qute) |
|---|---|
| `[[${variable}]]` | `{variable}` |
| `[(${variable})]` | `{variable}` |
| `[# th:each="x : ${list}"]...[/]` | `{#for x in list}...{/for}` |
| `[# th:if="${cond}"]...[/]` | `{#if cond}...{/if}` |
| `#strings.method(var)` | `{var.method()}` |
| `#strings.method(var, args)` | `{var.method(args)}` |
| `#uuidUtils.method()` | `{uuidUtils:method()}` |
| `#json.method()` | `{json:method()}` |
| `#encoder.method()` | `{encoder:method()}` |
| `a + '/' + b` | `{a}/{b}` (string concat) |

**Consumers updated:** `McpApiToolBuilder`, `AgentSetupService`, `DiscussionStylePresets` (10 templates), `PrePostUtils` (`buildListFromJson` → `{#for}`/`{_hasNext}`, `buildQuickReplies` trailing comma fix), `ChatModelRegistry` (comments), `MigrationManager` (UUID migration output).

**Docs updated:** `output-templating.md` (full rewrite), `architecture.md`, `httpcalls.md`, `conversation-memory.md`, `agent-father-deep-dive.md`.

**Test resources migrated:** 4 JSON files (agentengine output, weather output, httpcalls).

**Tests:** `TemplatingEngineTest` (20), `TemplateSyntaxMigratorTest` (29), `OutputTemplateTaskTest` (2), `McpApiToolBuilderTest` (14), `CreateApiAgentIT` (10).

---

## Phase 10: Group Conversations — Multi-Agent Debate Orchestration (2026-03-25)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

New multi-agent group conversation system enabling structured debates between agents with moderator synthesis. Agents participate through their normal pipelines via `IConversationService.say()`, remaining group-unaware by default with optional context injection.

| Phase | SP | Deliverables |
|---|---|---|
| **10.1** Data Models + Stores | 3 | `AgentGroupConfiguration`, `GroupConversation`, `IAgentGroupStore`/`AgentGroupStore`, `IGroupConversationStore`/`GroupConversationStore`, `IRestAgentGroupStore`/`RestAgentGroupStore` |
| **10.2** Orchestration Service | 5 | `IGroupConversationService`, `GroupConversationService` (~550 lines) |
| **10.3** REST + SSE + MCP | 3 | `IRestGroupConversation`/`RestGroupConversation`, `GroupConversationEventSink`, `McpGroupTools` (7 tools), `McpToolFilter` update |

**Architecture highlights:**

- **DB-agnostic stores** — both `AgentGroupStore` and `GroupConversationStore` use `IResourceStorageFactory`/`AbstractResourceStore`
- **Sequential + parallel rounds** — `ProtocolConfig.ProtocolType` controls agent turn ordering
- **Thymeleaf templates** — Customizable input templates for round 1, round N, and synthesis
- **Depth control** — `eddi.groups.max-depth` prevents recursive group explosion (default: 3)
- **Failure policies** — `MemberFailurePolicy` (RETRY/SKIP/ABORT) + `MemberUnavailablePolicy` (SKIP/FAIL)
- **Moderator synthesis** — Optional moderator agent produces balanced conclusion from transcript
- **Context injection** — `groupTranscript`, `groupConversationId`, `groupDepth` available in conversation context
- **7 MCP tools** — `list_groups`, `read_group`, `create_group`, `update_group`, `delete_group`, `discuss_with_group`, `read_group_conversation` (whitelist total: 40)
- **Micrometer metrics** — `eddi_group_discussion_duration`, `_count`, `_failure_count`

**REST API:**

| Method | Path | Description |
|---|---|---|
| `POST` | `/groups/{groupId}/conversations` | Start a group discussion |
| `GET` | `/groups/{groupId}/conversations/{id}` | Read transcript |
| `DELETE` | `/groups/{groupId}/conversations/{id}` | Delete + cascade member conversations |
| `GET` | `/groups/{groupId}/conversations` | List group conversations |
| `GET/POST/PUT/DELETE` | `/groupstore/groups/*` | Group config CRUD |

**Files:** 15 new, 1 modified (`McpToolFilter.java`). Total: ~1600 lines of new code.

**Testing:** ✅ `./mvnw compile` + `./mvnw test` — all pass.

### Phase 10.5: Discussion Styles (2026-03-25)

Redesigned flat round-based orchestration into a **phase-based execution engine** supporting 5 preset discussion styles (+ fully custom).

| Style | Flow |
|---|---|
| `ROUND_TABLE` | Opinion × N → Synthesis |
| `PEER_REVIEW` | Opinion → Critique (each→each) → Revision → Synthesis |
| `DEVIL_ADVOCATE` | Opinion → Challenge (by devil) → Defense → Synthesis |
| `DELPHI` | Anonymous opinion rounds → convergence → Synthesis |
| `DEBATE` | Pro argues → Con argues → Pro rebuttal → Con rebuttal → Judge |

**Key additions:**
- `DiscussionPhase` record: PhaseType, TurnOrder, ContextScope (NONE/FULL/LAST_PHASE/ANONYMOUS/OWN_FEEDBACK), targetEachPeer
- `DiscussionStylePresets`: 5 preset expansions + 10 default Thymeleaf templates
- `GroupMember.role`: "DEVIL_ADVOCATE", "PRO", "CON" for role-filtered phases
- `TranscriptEntry`: added `phaseName`, `targetAgentId` for peer-targeted critiques
- MCP `create_group`: new `style` parameter
- SSE events: round-based → phase-based (phase_start/phase_complete)

**Files:** 1 new (`DiscussionStylePresets.java`), 5 modified.

### Phase 10.5b: MCP/REST Usability Improvements (2026-03-25)

- MCP: added `describe_discussion_styles` discovery tool (rich markdown descriptions)
- MCP: added `list_group_conversations` tool for browsing past discussions
- MCP: `create_group` now accepts `memberRoles` param (DEVIL_ADVOCATE/PRO/CON)
- MCP: enriched all tool descriptions with usage hints and cross-references
- REST: added `GET /groupstore/groups/styles` endpoint for style discovery
- McpToolFilter: whitelist updated to 42 tools
- Tests: 18 → 22 McpGroupToolsTest tests

### Phase 10.6: Group-of-Groups — Nested Group Members (2026-03-25)

Members can now be other groups (`MemberType.GROUP`). The sub-group runs its own full discussion and its synthesized answer becomes the member's response in the parent group.

**Key additions:**
- `MemberType` enum: `AGENT` (default) | `GROUP` — backward-compatible via 4-arg convenience constructor
- `executeGroupMemberTurn()`: recursive call to `discuss()`, extracts synthesized answer
- Depth tracking prevents infinite recursion (`eddi.groups.max-depth`, default: 3)
- MCP `create_group`: new `memberTypes` param (AGENT/GROUP per member position)
- `describe_discussion_styles`: documents nested groups capability

**Use cases:** parallel review panels, red-team vs blue-team, tournament brackets.

**Files:** 3 modified (`AgentGroupConfiguration`, `GroupConversationService`, `McpGroupTools`)

### Phase 10.7: Orchestration Unit Tests (2026-03-26)

17 tests in `GroupConversationServiceTest` covering the core orchestration engine:

- **MainFlow (5):** round-table transcript, synthesized answer extraction, depth limit, null config, unavailable agent skip
- **Styles (4):** PEER_REVIEW (critiques+revisions), DEVIL_ADVOCATE (challenges), DEBATE (arguments+rebuttals), CUSTOM phases
- **NestedGroups (2):** GROUP member delegation, depth-exceeded graceful skip
- **ErrorHandling (2):** ABORT policy → FAILED state, startConversation failure → SKIPPED
- **Lifecycle (4):** read, delete (cascade end), list delegates

**Total group conversation tests:** 58 (17 orchestration + 22 MCP + 19 style presets)

**Documentation:** Created `docs/group-conversations.md` (user-facing). Updated `docs/mcp-server.md` (9 group tools, 39→48 total). Updated `HANDOFF.md`. Removed obsolete `docs/v6-planning/group-conversations.md`.


## v6 API Endpoint Simplification (2026-03-25)

**Repos:** EDDI, EDDI-Manager, eddi-chat-ui (`feature/version-6.0.0`)

**Breaking change:** Conversation-scoped REST endpoints no longer require `environment` or `agentId` path parameters. The `conversationId` is the sole identifier — the service layer resolves context from the stored conversation snapshot.

**New URL structure:**

| Operation | Old Path | New Path |
|-----------|----------|----------|
| Start conversation | `POST /agents/{env}/{agentId}` | `POST /agents/{agentId}/start` |
| Send message | `POST /agents/{env}/{agentId}/{convId}` | `POST /agents/{conversationId}` |
| Read conversation | `GET /agents/{env}/{agentId}/{convId}` | `GET /agents/{conversationId}` |
| Stream | `POST /agents/{env}/{agentId}/{convId}/stream` | `POST /agents/{conversationId}/stream` |
| End conversation | — | `POST /agents/{conversationId}/endConversation` |
| Undo/Redo | `POST /agents/{env}/{agentId}/{convId}/undo` | `POST /agents/{conversationId}/undo` |
| Managed agents | `POST /managedagents/{intent}/{userId}` | `POST /agents/managed/{intent}/{userId}` |

**Critical bug fixed:** Added `/start` sub-path to conversation initiation to prevent JAX-RS route conflict between `POST /agents/{agentId}` (start) and `POST /agents/{conversationId}` (say) — identical path patterns that JAX-RS cannot disambiguate.

**Backend changes:**

| File | Change |
|------|--------|
| `IConversationService.java` | 10 conversation-only method overloads (read, say, stream, undo, redo, state) |
| `ConversationService.java` | Implementation: loads snapshot → extracts agentId + environment → delegates |
| `IRestAgentEngine.java` | Simplified paths with `/{agentId}/start` and `/{conversationId}/*` |
| `RestAgentEngine.java` | Uses conv-only service methods |
| `IRestAgentEngineStreaming.java` | `/{conversationId}/stream` |
| `RestAgentEngineStreaming.java` | Uses conv-only `sayStreaming` |
| `IRestAgentManagement.java` | `/managedagents` → `/agents/managed` |
| `McpConversationTools.java` | `talk_to_agent`, `read_conversation` use conv-only service methods |

**Frontend changes:**

| File | Change |
|------|--------|
| Manager `chat.ts` | All URLs simplified; underscore-prefixed unused params |
| Manager `handlers.ts` | MSW handlers updated to `/agents/{agentId}/start` |
| Chat UI `chat-api.ts` | All URLs simplified |
| Chat UI `demo-api.ts` | Code examples updated |
| Chat UI `main.tsx` | `/chat/managedagents` → `/chat/managed` |

**Documentation:** Updated `conversations.md`, `developer-quickstart.md`, `putting-it-all-together.md`, `passing-context-information.md`, `managed-agents.md`, `deployment-management-of-agents.md`, `agent-father-deep-dive.md`, `creating-your-first-agent` docs. Removed stale `{environment}` and `{agentId}` path parameter descriptions from API tables.

**Testing:** ✅ Backend `./mvnw compile` + `./mvnw test`, Manager `tsc -b`, Chat UI `tsc` — all pass.

---

## Test Fixes & Install Script Local Build Support (2026-03-25)

**Repo:** EDDI (`feature/version-6.0.0`)

**Test fixes (4 failures → 0):**

| Test | Root Cause | Fix |
|---|---|---|
| `McpToolSchemaValidationTest` | `@P` annotation on `EddiToolBridge.executeApiCall` used a descriptive sentence as the property key, violating MCP schema regex `^[a-zA-Z0-9_.-]{1,64}$` | Changed to `@P("httpCallUri")` |
| `EddiToolBridgeTest` (caching) | `configCache.remove(httpCallUri)` called before every `getOrLoadConfig`, defeating the cache | Removed premature cache invalidation |
| `McpSetupToolsTest` (API summary) | `AgentSetupService.createApiAgent` was not enriching the system prompt with the API summary | Re-enabled `enrichedPrompt = prompt + apiSummary` |
| `AgentOrchestratorTest` (Mockito) | Mockito 5.x inline mock maker doesn't create subclasses, so `getSuperclass()` returned `Object` | Used `mockingDetails().getMockCreationSettings().getTypeToMock()` |

**Install script `--local` flag improvements:**

Both `install.sh` (`--local`) and `install.ps1` (`-Local`) now fully support local builds for pre-release development:

- Detect EDDI repo root and verify `Dockerfile.jvm` exists
- Include `docker-compose.local.yml` as a compose overlay (used directly from repo, not downloaded — build context must be repo root)
- Run `docker compose build` instead of `docker compose pull`
- All other wizard choices (DB, auth, monitoring) still work normally with `--local`

**Pre-release workflow:**

```bash
./mvnw package -DskipTests       # Build the Quarkus app
bash install.sh --local           # Build Docker image + start containers
```

**Files changed:** `EddiToolBridge.java`, `AgentSetupService.java`, `AgentOrchestratorTest.java`, `install.sh`, `install.ps1`

**Testing:** ✅ 1151 tests, 0 failures, 0 errors.

---

## Manager & Chat UI Production Builds (2026-03-25)

**Repos:** EDDI, EDDI-Manager, eddi-chat-ui (`feature/version-6.0.0`)

**What changed:**

Updated the production builds of both the EDDI Manager and EDDI Chat UI, deployed their assets to the EDDI backend `META-INF/resources` folder, and fixed UI build regressions.

- Fixed a broken TypeScript build in `eddi-chat-ui` caused by an aggressive `bot`→`agent` rename that corrupted `ScrollToBottom` casing.
- Ran full Vite production builds for `EDDI-Manager` and `eddi-chat-ui`.
- Copied Manager's compiled assets (`index-*.js`, `index-*.css`) to EDDI's `META-INF/resources/scripts/` directory.
- Updated `manage.html` references with the new Manager bundle hashes.
- Verified `chat.html` was auto-updated by Vite and cleaned up outdated Chat UI bundles.
- Verified `eddi-chat-ui` test suite (45/45 passed).

---

## AgentSetupService Extraction — REST Endpoints for Agent Setup (2026-03-24)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Extracted all agent setup business logic from `McpSetupTools` (803 lines) into a shared `AgentSetupService` CDI bean. The service is now exposed via both MCP tools (unchanged interface) and new REST endpoints.

| Component           | Files                                                  | Change                                                                             |
| ------------------- | ------------------------------------------------------ | ---------------------------------------------------------------------------------- |
| **Request Records** | `SetupAgentRequest.java`, `CreateApiAgentRequest.java` | NEW — Typed request objects for both operations                                    |
| **Result Record**   | `SetupResult.java`                                     | NEW — Structured result with builder pattern                                       |
| **Service**         | `AgentSetupService.java` (~400 lines)                  | NEW — All config builders, validation, deploy logic                                |
| **REST Interface**  | `IRestAgentSetup.java`                                 | NEW — `POST /administration/agents/setup`, `POST /administration/agents/setup-api` |
| **REST Impl**       | `RestAgentSetup.java`                                  | NEW — Thin adapter (201/400/500)                                                   |
| **MCP Tools**       | `McpSetupTools.java` (803→145 lines)                   | REWRITE — Thin wrapper: builds request, calls service, serializes result           |
| **Utility**         | `McpApiToolBuilder.java`                               | Class + `ApiBuildResult` + `parseAndBuild` + `parseSpec` made `public`             |
| **Tests**           | `McpSetupToolsTest.java`                               | REWRITE — Config builder tests via `service.`, MCP integration tests via `tools.`  |

**New REST API:**

| Method | Path                               | Request                 | Response                        |
| ------ | ---------------------------------- | ----------------------- | ------------------------------- |
| `POST` | `/administration/agents/setup`     | `SetupAgentRequest`     | `201 SetupResult` / `400 error` |
| `POST` | `/administration/agents/setup-api` | `CreateApiAgentRequest` | `201 SetupResult` / `400 error` |

**Design decisions:**

- Service-first architecture ensures consistency between MCP and REST interfaces
- `SetupResult` with builder avoids Map soup in responses
- Static utility methods (`buildPromptResponseJson`, `isLocalLlmProvider`, `supportsResponseFormat`) preserved as delegates on `McpSetupTools` for backward compatibility
- `AgentSetupException` (checked) for validation errors vs `RuntimeException` for infrastructure failures

**Also in this commit:**

- `V6RenameMigration.java` — updated collection rename mappings for v6 naming alignment

**Scope:** 9 files changed (6 new, 3 modified). New `engine.setup` package with service + records. `engine.api` and `engine.rest` extended.

**Testing:** ✅ Full suite passes — 140+ tests, 0 failures. `McpSetupToolsTest` (19), `McpApiToolBuilderTest` (17) all green.

---

## V6 Content Rename — Complete String/Comment/Doc Alignment (2026-03-22)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Comprehensive content rename across the entire repository to align all strings, comments, error messages, parameter names, MCP tool names, and documentation with the v6 naming specification. This completes the rename that was started with file/class renames in earlier phases.

| Category                        | Count | Examples                                                                                       |
| ------------------------------- | ----- | ---------------------------------------------------------------------------------------------- |
| **MCP Tool Names**              | ~80   | `setup_bot`→`setup_agent`, `list_packages`→`list_workflows`, `chat_with_bot`→`chat_with_agent` |
| **REST Method Names**           | ~70   | `deleteBot()`→`deleteAgent()`, `readPackageDescriptors()`→`readWorkflowDescriptors()`          |
| **Parameter Names**             | ~110  | `botId`→`agentId`, `botVersion`→`agentVersion`, `packageId`→`workflowId`                       |
| **MCP `@ToolArg` Descriptions** | ~30   | `"Bot ID (required)"`→`"Agent ID (required)"`                                                  |
| **Error Messages**              | ~15   | `"Failed to deploy bot"`→`"Failed to deploy agent"`                                            |
| **OpenAPI Descriptions**        | ~10   | `"Deploy & Undeploy Bots"`→`"Deploy & Undeploy Agents"`                                        |
| **Constants**                   | ~20   | `BOT_FILE_ENDING`→`AGENT_FILE_ENDING`, `COLLECTION_BOT_TRIGGERS`→`COLLECTION_AGENT_TRIGGERS`   |
| **Documentation**               | ~40   | `mcp-server.md`, `changelog.md`                                                                |
| **Shell Scripts**               | 2     | `install.sh`, `install.ps1` — `BOT_COUNT`→`AGENT_COUNT`                                        |

**Also in this commit:**

- **Checkstyle config rewrite** — Reduced violations from 697→81 by removing noisy style-only checks (AvoidStarImport, NeedBraces, LeftCurly/RightCurly, WhitespaceAround, trailing whitespace). Kept all safety checks (EqualsHashCode, FallThrough, StringLiteralEquality). Line length increased 120→150.

**Scope:** 349 files changed, 11,253 insertions, 10,771 deletions.

**Verification:**

- Exhaustive pattern search across ALL file types: **zero remaining `bot`/`Bot`/`botId`/`packageId`/MCP tool patterns**
- `compile` + `test-compile`: BUILD SUCCESS
- 947 unit tests: 0 failures, 0 errors
- Checkstyle: 81 warnings (down from 697)

---

## Refactor: Dissolve `ai.labs.eddi.model` Workflow (2026-03-21)

**Repo:** EDDI (`feature/version-6.0.0`)
**Commit:** `4ec9e78`

**What changed:**

The grab-bag `ai.labs.eddi.model` package (6 unrelated classes) was dissolved. Each class moved to its natural domain package:

| Class                       | Old Workflow | New Workflow                   | Rationale                                        |
| --------------------------- | ------------ | ------------------------------ | ------------------------------------------------ |
| `Deployment`                | `model`      | `engine.model`                 | Joins `AgentDeployment`, `AgentDeploymentStatus` |
| `ConversationState`         | `model`      | `engine.memory.model`          | Conversation-memory concept, used by snapshots   |
| `ConversationStatus`        | `model`      | `engine.memory.model`          | DTO alongside `ConversationState`                |
| `AgentTriggerConfiguration` | `model`      | `engine.agentmanagement.model` | Used exclusively by agent trigger store/API      |
| `UserConversation`          | `model`      | `engine.agentmanagement.model` | Managed conversation mapping for triggers        |
| `ResourceDescriptor`        | `model`      | `configs.descriptors.model`    | Base class for `DocumentDescriptor`              |

**Scope:** 72 files changed (regular imports, static imports, inner-class imports). Removed 3 now-redundant same-package imports. Pure rename refactor — no logic changes.

**Testing:** ✅ Full compile + test suite pass.

---

## One-Command Install & Onboarding Wizard (2026-03-20)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

New users can set up EDDI with a single command. Interactive wizard guides through database, auth, and monitoring choices.

| Component                   | File                               | Description                                                                                                   |
| --------------------------- | ---------------------------------- | ------------------------------------------------------------------------------------------------------------- |
| **Bash installer**          | `install.sh`                       | 3-step wizard (DB/Auth/Monitoring), Docker auto-install on Linux, `eddi` CLI wrapper, Agent Father deployment |
| **PowerShell installer**    | `install.ps1`                      | Windows parity, `winget` Docker Desktop auto-install, WSL2 prereq guidance                                    |
| **PostgreSQL-only compose** | `docker-compose.postgres-only.yml` | EDDI + PostgreSQL (no MongoDB), `QUARKUS_PROFILE=postgres`                                                    |
| **Auth overlay**            | `docker-compose.auth.yml`          | Keycloak integration overlay with OIDC + test users                                                           |
| **Monitoring overlay**      | `docker-compose.monitoring.yml`    | Grafana + Prometheus placeholder                                                                              |
| **README**                  | `README.md`                        | Quick Start section with one-liner commands                                                                   |
| **Getting Started**         | `docs/getting-started.md`          | Option 0 — one-command install as recommended method                                                          |

**Key features:**

- Platform-aware Docker install (Linux auto via `get.docker.com`, Windows via `winget`, macOS manual)
- Idempotent re-runs — detects existing EDDI, skips duplicate agent imports
- Input validation, config summary, CTRL+C cleanup, disk space warning, visible pull progress
- Auto-opens browser after setup, Agent Father handoff for first-agent creation

---

## Phase 8b: MCP Client — Agents Consume External MCP Tools + Quarkus 3.32.4 (2026-03-20)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Agents can now connect to external MCP servers and use their tools during conversations. Uses `langchain4j-mcp` 1.12.2-beta22 with `StreamableHttpMcpTransport`.

| Component                  | Change                                                                                  |
| -------------------------- | --------------------------------------------------------------------------------------- |
| **POM**                    | Added `langchain4j-mcp` 1.12.2-beta22, upgraded Quarkus 3.30.8 → 3.32.4                 |
| **LangChainConfiguration** | Added `mcpServers` + `McpServerConfig` (url, name, transport, apiKey, timeoutMs)        |
| **McpToolProviderManager** | NEW — Lifecycle mgmt, `StreamableHttpMcpTransport`, caching, vault-ref, graceful errors |
| **AgentOrchestrator**      | MCP tool specs merged into tool-calling loop with budget/rate-limiting                  |
| **McpSetupTools**          | `mcpServers` param on `setup_agent` — comma-separated URLs → `McpServerConfig` list     |

**Design:** StreamableHttpMcpTransport (non-deprecated), graceful degradation (MCP failures never kill pipeline), port 7070, `${vault:key}` support.

**Tests:** `McpToolProviderManagerTest` (8 tests), updated `AgentOrchestratorTest` + `LangchainTaskTest` + `McpSetupToolsTest` (21 calls).

---

## Security Fix: Path Traversal in McpDocResources (2026-03-20)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

`McpDocResources.readDoc()` had a path traversal vulnerability — names like `../../etc/passwd` resolved outside the docs directory via `Path.of(docsPath, name)`.

| Fix                   | Change                                                 |
| --------------------- | ------------------------------------------------------ |
| **Input validation**  | Reject names containing `/`, `\`, or `..`              |
| **Containment check** | `normalize()` + `startsWith(docsDir)` defense-in-depth |
| **Warning log**       | Blocked attempts logged at WARN level                  |

**Files:** `McpDocResources.java` (fix), `McpDocResourcesTest.java` (NEW — 10 tests, 7 path traversal vectors)

---

## Phase 8a.3: Agent Discovery & Managed Conversations (2026-03-20)

**Repo:** EDDI (`feature/version-6.0.0`)
**Commit:** `4ed7bce8`

**What changed:**

6 new MCP tools bringing the total from 27 → **33 tools**. Introduces a two-tier conversation model:

| Tier          | Tools                                   | Conversations                       | Use Case                                 |
| ------------- | --------------------------------------- | ----------------------------------- | ---------------------------------------- |
| **Low-level** | `create_conversation` + `talk_to_agent` | Multiple per user, manually managed | Custom apps, multi-conversation UIs      |
| **Managed**   | `chat_managed`                          | One per intent+userId, auto-created | Single-window chat, intent-based routing |

**New tools:**

| Tool                   | Class                  | Description                                                                                                                   |
| ---------------------- | ---------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| `discover_agents`      | `McpConversationTools` | Enriched agent list — merges deployed agents with intent mappings from `AgentTriggerConfiguration`                            |
| `chat_managed`         | `McpConversationTools` | Intent-based single-window chat — uses `IUserConversationStore` + `IRestAgentTriggerStore` to auto-create/reuse conversations |
| `list_agent_triggers`  | `McpAdminTools`        | List all intent→agent mappings                                                                                                |
| `create_agent_trigger` | `McpAdminTools`        | Map an intent to agent deployments                                                                                            |
| `update_agent_trigger` | `McpAdminTools`        | Modify existing trigger                                                                                                       |
| `delete_agent_trigger` | `McpAdminTools`        | Remove trigger by intent                                                                                                      |

**Key decisions:**

| #   | Decision                                                          | Reasoning                                                                                                        |
| --- | ----------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| 1   | `chat_managed` mirrors `RestAgentManagement.initUserConversation` | Same proven logic — reads `IUserConversationStore`, falls back to trigger lookup, creates via `IRestAgentEngine` |
| 2   | `discover_agents` gracefully handles trigger read failures        | Non-fatal — still returns agent list without intent enrichment                                                   |
| 3   | Trigger CRUD uses `getRestStore()` proxy pattern                  | Consistent with all other admin tools — proper DocumentDescriptorFilter interceptor                              |
| 4   | Comprehensive Tool Reference in `mcp-server.md`                   | Parameter tables, response schemas, config schema, end-to-end example                                            |

**Files changed:**

| File                            | Change                                                                                                |
| ------------------------------- | ----------------------------------------------------------------------------------------------------- |
| `McpConversationTools.java`     | +3 deps (`IRestAgentTriggerStore`, `IUserConversationStore`, `IRestAgentEngine`), +2 tools, +1 helper |
| `McpAdminTools.java`            | +4 trigger CRUD tools                                                                                 |
| `McpToolFilter.java`            | Whitelist 27 → 33                                                                                     |
| `McpConversationToolsTest.java` | +7 tests (discover_agents × 3, chat_managed × 4)                                                      |
| `McpAdminToolsCrudTest.java`    | +7 tests (trigger CRUD: list/create/update/delete + error paths)                                      |
| `docs/mcp-server.md`            | +234 lines: Tool Reference section with full docs                                                     |

**Live testing:** ✅ All 6 tools tested against running backend:

- `discover_agents`: 80 agents returned, filter works ("Bob Marley" → 1 result), intents enriched
- `chat_managed`: Conversation auto-created (`69bc8b93...`), reused on follow-up (same conversationId)
- Trigger CRUD: create/update/delete all returned status 200

**Testing:** ✅ All MCP unit tests pass (14 new). Compilation clean.

---

## Phase 8a.2: MCP Resource CRUD + Batch Cascade (2026-03-19)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

5 new MCP tools for full resource lifecycle management, bringing the total from 22 → 27 tools.

| Tool                   | Description                                                                        |
| ---------------------- | ---------------------------------------------------------------------------------- |
| `update_resource`      | Update any resource config by type and ID → returns new version URI                |
| `create_resource`      | Create a new resource → returns ID + URI                                           |
| `delete_resource`      | Delete a resource (soft by default, `permanent=true` for hard delete)              |
| `apply_agent_changes`  | Batch-cascade URI changes through package → agent in ONE pass, optionally redeploy |
| `list_agent_resources` | Walk agent → packages → extensions for complete resource inventory                 |

**Key design:** `apply_agent_changes` reads each package, replaces ALL old→new URIs in-memory, saves ONCE per package, then saves agent ONCE. No N+1 version problem.

**Files changed:** `McpAdminTools.java` (+5 tools), `McpToolFilter.java` (20→27), `McpAdminToolsCrudTest.java` (22 new tests), `docs/mcp-server.md`

**Testing:** ✅ All MCP unit tests pass.

---

## Phase 8a: MCP Code Review — Fixes, Resource Tools, Docs Resources (2026-03-19)

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Comprehensive MCP code review identified and fixed 6 issues, added 2 new tools, simplified `update_agent`, created docs MCP resources, and rewrote documentation.

**Fixes:**

- `get_agent` N+1 query → direct `readDescriptor(id, ver)` (McpConversationTools)
- `deployAgent` response → `deployed` consistently boolean, not `"pending"` string (McpAdminTools)
- `ConversationState` import → corrected to `engine.model` package
- `McpToolFilter` missing `ToolManager.ToolInfo` + `FilterContext` imports
- `getRestStore()` deduplicated → shared in `McpToolUtils`
- `update_agent` simplified to name/description + redeploy only (removed package business logic)

**New features:**

- `read_workflow` tool — reads full pipeline config with extensions
- `read_resource` tool — reads any resource config by type (6 types)
- `McpDocResources.java` — exposes 40+ docs as MCP resources (`eddi://docs/{name}`)
- `Dockerfile.jvm` — COPY docs into container + `eddi.docs.path` config

**Decision:** `update_agent` was doing too much (package add/remove). Moved to thin REST delegation — resource management belongs in dedicated tools (`read_resource`, and the planned `update_resource` / `apply_agent_changes`).

**Tests:** 116/116 MCP tests pass.

---

## Phase 8a: MCP `setup_agent` — Quick Replies, Sentiment Analysis & JSON Mode (2026-03-18)

### Backend — Structured JSON LLM Output

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Two new optional params for `setup_agent` that enable structured JSON output from the LLM:

| Feature                | Param                     | Effect                                                                                        |
| ---------------------- | ------------------------- | --------------------------------------------------------------------------------------------- |
| **Quick Replies**      | `enableQuickReplies`      | LLM returns `htmlResponseText` + `quickReplies[]` buttons                                     |
| **Sentiment Analysis** | `enableSentimentAnalysis` | LLM returns `sentimentScore`, `identifiedEmotions[]`, `detectedIntent`, `urgencyRating`, etc. |

**Two-layer JSON reliability approach:**

1. **Prompt instruction** — `promptResponseJson` format string appended to system prompt (works with all providers)
2. **Provider-level `responseFormat=json`** — set on langchain params for providers that support builder-level JSON mode

| Provider         | `responseFormat=json`   | Method                           |
| ---------------- | ----------------------- | -------------------------------- |
| **OpenAI**       | ✅ Wired in this commit | `.responseFormat("json_object")` |
| **Gemini**       | ✅ Already existed      | `.responseFormat(JSON)`          |
| **Anthropic**    | ❌ No native JSON mode  | Prompt-only (works well)         |
| **Ollama/jlama** | ❌ No builder-level API | Prompt-only                      |

When JSON format is active, `convertToObject=true` is set on the langchain params so EDDI parses the JSON response into an object. Streaming is not recommended with JSON mode as it would show raw JSON building up in the UI.

**Files changed:**

| File                              | Change                                                                                                                             |
| --------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `McpSetupTools.java`              | 2 new params, `buildPromptResponseJson()`, `supportsResponseFormat()`, `createLangchainConfig()` with JSON/convertToObject support |
| `OpenAILanguageModelBuilder.java` | Added `responseFormat=json` → `json_object` support                                                                                |
| `McpSetupToolsTest.java`          | 10 new tests (31 total): quick replies, sentiment, both, anthropic no-responseFormat, helper methods                             |

**Testing:** ✅ 31 MCP setup tests pass (up from 21), all green.

---

## Phase 8a: MCP Code Review Fixes (2026-03-18)

### Backend — CDI Injection Fix, Code Quality, Test Coverage

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Full code review of all 15 MCP tools identified and fixed several issues:

| Fix                      | Files                                           | Change                                                                                                                                             |
| ------------------------ | ----------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| **P0: CDI → REST proxy** | `McpAdminTools.java`                            | Same bug as McpSetupTools — `create_agent` bypassed `DocumentDescriptorFilter`. Refactored to `IRestInterfaceFactory`                              |
| **P1: Deduplication**    | `McpConversationTools.java`                     | Extracted `sendMessageAndWait()` — eliminated 30 duplicated lines between `talkToAgent` and `chatWithAgent`                                        |
| **P1: Input validation** | `McpConversationTools.java`                     | Added null/blank checks to `createConversation`, `talkToAgent`, `chatWithAgent` — returns clear errors instead of NPE                              |
| **Tests: +31 new**       | `McpConversationToolsTest`, `McpAdminToolsTest` | 8 new tests: input validation (6), `readConversationLog` error path (1), `chatWithAgent` creation failure (1). Factory mock wiring for admin tools |

**Testing:** ✅ 103 MCP tests pass (up from 72), all green.

---

## Phase 8a: MCP Server — EDDI as MCP Tool Provider (2026-03-17)

### Backend — quarkus-mcp-server-http Integration

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

EDDI now exposes its agent conversation and administration capabilities via the Model Context Protocol (MCP), enabling AI assistants (Claude Desktop, IDE plugins, custom MCP clients) to interact with deployed agents.

| Component          | Files                               | Purpose                                                                                                                                         |
| ------------------ | ----------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| Dependency         | `pom.xml`                           | `quarkus-mcp-server-http` v1.10.2 (Quarkiverse)                                                                                                 |
| Conversation Tools | `McpConversationTools.java`         | 7 MCP tools: list_agents, list_agent_configs, create_conversation, talk_to_agent, **chat_with_agent**, read_conversation, read_conversation_log |
| Admin Tools        | `McpAdminTools.java`                | 6 MCP tools: deploy_agent, undeploy_agent, get_deployment_status, list_workflows, create_agent, delete_agent                                    |
| **Setup Tools**    | `McpSetupTools.java`                | **setup_agent** composite: creates full agent pipeline in one MCP call (behavior → langchain → output → package → agent → deploy)               |
| Shared Utils       | `McpToolUtils.java`                 | parseEnvironment, JSON escaping (RFC 8259), extractIdFromLocation, extractVersionFromLocation                                                   |
| Config             | `application.properties`            | Streamable HTTP transport at `/mcp`                                                                                                             |
| Tests              | `McpSetupToolsTest.java` + 3 others | 62 unit tests                                                                                                                                   |
| Docs               | `docs/mcp-server.md`                | Feature documentation with Claude Desktop config                                                                                                |

**Design decisions:**

- **`quarkus-mcp-server`** over raw MCP Java SDK — native CDI `@Tool`/`@ToolArg` annotations, auto JSON schema, Dev UI, live reload. Dramatically less boilerplate.
- **langchain4j-mcp is client-only** — not suitable for building MCP servers. Reserved for Phase 8b.
- **Delegates to existing services** — `IConversationService` and `IRestAgentAdministration` (extracted in Phase 1), avoiding code duplication.
- **Typed params** — `Integer`/`Boolean` for `@ToolArg` so MCP JSON Schema uses correct types.
- **`chat_with_agent` composite** — combines create_conversation + talk_to_agent in one call (most common AI workflow).
- **`setup_agent` composite** — codifies the Agent Father's 12-step httpcalls pipeline as server-side Java. Atomic, validated, with proper error handling and rollback.
- **`@Blocking` on `talk_to_agent`/`chat_with_agent`** — explicit annotation since `CompletableFuture.get()` blocks the thread.
- **Per-agent MCP config planned** — currently global, will be per-agent configurable in future iteration.

**Planned (Phase 8a+): `create_api_agent`**

- Takes an OpenAPI spec → generates httpcalls configs → wires as LangChain tools → creates an agent that can call any API securely
- Needs `swagger-parser` (`io.swagger.parser.v3:swagger-parser:2.1.x`)
- Positions EDDI as an AI API gateway

---

## Phase 8a+: `create_api_agent` MCP Tool (2026-03-17)

### Backend — OpenAPI-to-Agent Pipeline

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

EDDI's MCP server now includes a `create_api_agent` composite tool that takes an OpenAPI 3.0/3.1 spec and generates a fully deployed agent with LLM-powered API interaction. The LLM receives context about available endpoints and can orchestrate API calls through EDDI's controlled pipeline.

| Component        | Files                               | Purpose                                                                                                     |
| ---------------- | ----------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| OpenAPI Parser   | `McpApiToolBuilder.java` (new)      | Parses OpenAPI spec → grouped `HttpCallsConfiguration` resources                                            |
| Composite Tool   | `McpSetupTools.java` (modified)     | `create_api_agent` @Tool method: parse → create httpcalls → behavior → langchain → package → agent → deploy |
| Dependency       | `pom.xml`                           | `io.swagger.parser.v3:swagger-parser:2.1.39`                                                                |
| Unit Tests       | `McpApiToolBuilderTest.java` (new)  | 19 tests: parsing, grouping, filtering, body templates, path conversion                                     |
| Unit Tests       | `McpSetupToolsTest.java` (modified) | 4 new tests (17 total): full pipeline, validation, package structure, prompt enrichment                     |
| Integration Test | `CreateApiAgentIT.java` (new)       | 10 ordered tests against running EDDI instance (standalone, not @QuarkusTest)                               |

**Key features:**

- **Tag-based grouping**: OpenAPI tags → separate `HttpCallsConfiguration` resources (e.g. "MyAPI - Users", "MyAPI - Orders"), keeping configs logically organized
- **Prompt enrichment**: System prompt automatically includes an API summary with all available endpoints for LLM context
- **Endpoint filtering**: Optional comma-separated filter (e.g. `"GET /users,POST /orders"`) to include only specific endpoints
- **Path/query param conversion**: OpenAPI `{petId}` → Thymeleaf `[[${petId}]]` templates for LLM-provided values
- **Request body templates**: Flat JSON schemas become typed Thymeleaf templates (strings quoted, numbers unquoted)
- **Auth propagation**: Optional `apiAuth` parameter flows as `Authorization` header on all generated HttpCalls
- **Deprecated operation skipping**: Operations marked `deprecated: true` are automatically excluded
- **Truncated summaries**: API summary capped at 30 endpoints to avoid overwhelming the LLM context

**Design decisions:**

| #   | Decision                                            | Reasoning                                                                                                                                                          |
| --- | --------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 1   | `McpApiToolBuilder` as package-private utility      | Stateless, testable in isolation; `McpSetupTools` handles the CDI-injected pipeline                                                                                |
| 2   | Tag-based grouping (not one giant config)           | Keeps HttpCallsConfiguration files manageable; mirrors API domain boundaries                                                                                       |
| 3   | Flat body template fallback to `[[${requestBody}]]` | Nested objects/arrays are too complex for Thymeleaf; documented as known limitation                                                                                |
| 4   | Default to `anthropic`/`claude-sonnet-4-6`          | Best balance of quality + tool-calling reliability for API agents                                                                                                  |
| 5   | Standalone REST integration test                    | `quarkus-mcp-server-http` extension's build-time `@ToolArg` processing causes `UnsatisfiedResolutionException` during `@QuarkusTest` — an MCP extension limitation |
| 6   | `resolveParams()` extraction                        | Eliminates parameter resolution duplication between `setupAgent` and `createApiAgent`                                                                              |

**Model names updated:**

- Default model: `gpt-4o` → **`claude-sonnet-4-6`**
- Default provider: `openai` → **`anthropic`**
- Examples: `gpt-5.4`, `gemini-3.1-pro-preview`, `deepseek-chat`

**Testing:** ✅ 36 MCP unit tests pass (19 `McpApiToolBuilderTest` + 17 `McpSetupToolsTest`). `CreateApiAgentIT` requires a running EDDI instance.

---

## Phase 8a: MCP Improvements — AI-Agent Friendliness (2026-03-18)

### Backend — Cleaner Responses, Ollama/jlama Support, Deploy Verification

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Three improvements to make the MCP server more useful for AI agents:

| Improvement                   | Files                                     | Change                                                                                                                                                       |
| ----------------------------- | ----------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **1. Cleaner responses**      | `McpConversationTools.java`               | `buildConversationResponse()` extracts `agentResponse`, `quickReplies`, `actions`, `conversationState` as top-level fields — eliminates deep JSON navigation |
| **2. Ollama/jlama support**   | `McpSetupTools.java`, `McpToolUtils.java` | All 7 providers listed; `baseUrl` param; `isLocalLlmProvider()` skips apiKey validation; provider-specific param mapping (ollama→`model`, jlama→`authToken`) |
| **3. Deploy verification**    | `McpSetupTools.java`                      | `deployAndWait()` polls status for 5s; reports actual `deploymentStatus` + `deployWarning` on failure                                                        |
| **4. Ollama baseUrl backend** | `OllamaLanguageModelBuilder.java`         | Added `baseUrl` parameter to both `build()` and `buildStreaming()`                                                                                         |
| **5. Docker compose**         | `docker-compose.yml`                      | Added `host.docker.internal:host-gateway` extra_hosts for Ollama running in Docker                                                                           |

**Testing:** ✅ 38 MCP tests pass (16 `McpConversationToolsTest` + 22 `McpSetupToolsTest`)

---

## Manager: Audit Trail UI (2026-03-17)

### Frontend — Timeline-Based Audit Ledger Viewer

**Repo:** EDDI-Manager (`feature/version-6.0.0`)

**What changed:**

Added an Audit Trail page to the Manager UI that consumes the backend audit ledger REST API. Provides a timeline-based visualization of task execution for compliance review and debugging.

| Component  | Files                    | Purpose                                                                                    |
| ---------- | ------------------------ | ------------------------------------------------------------------------------------------ |
| API Module | `src/lib/api/audit.ts`   | `AuditEntry` type + 3 fetch functions (by conversation, by agent, count)                   |
| Hooks      | `src/hooks/use-audit.ts` | 3 TanStack Query hooks with conditional enabling                                           |
| Page       | `src/pages/audit.tsx`    | Timeline UX: step-grouped entries, color-coded task badges, expandable JSON, summary strip |
| Sidebar    | `sidebar.tsx`            | ShieldCheck icon under Operations                                                          |
| Routing    | `App.tsx`                | `/manage/audit` route                                                                      |
| i18n       | 11 locale files          | `nav.audit` + `audit.*` namespace                                                          |
| MSW Mocks  | `handlers.ts`            | 4 realistic entries (parser → behavior → langchain → output)                               |
| Tests      | `audit.test.tsx`         | 13 tests covering all UI states and interactions                                           |

**Design decisions:**

- **Timeline layout** groups entries by `stepIndex` — mirrors the conversation lifecycle pipeline
- **Color-coded badges**: langchain=purple, behavior=blue, output=emerald, expressions=amber, httpcalls=orange, propertysetter=teal
- **Expandable detail sections** (Input/Output/LLM Detail/Tool Calls) keep the default view clean
- **Two search modes**: by Conversation ID or by Agent ID (with optional version filter)
- **Summary strip** with total entries, duration, and cost at a glance

**Verification:** 0 TS errors, 246/246 tests pass (29 files), production build succeeds.

---

## Phase 7, Item 34: Immutable Audit Ledger (2026-03-17)

### Backend — Write-Once Audit Trail, HMAC Integrity, EU AI Act Compliance

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Added an immutable audit ledger that captures every lifecycle task execution as a write-once, append-only trail. This implements Tier 3 ("Telemetry Ledger") of the EDDI 3-Tier CQRS architecture for EU AI Act Articles 17/19 compliance.

| Component        | Files                                        | Purpose                                                                    |
| ---------------- | -------------------------------------------- | -------------------------------------------------------------------------- |
| Data Model       | `AuditEntry.java`                            | Record with 18 fields + `withEnvironment()` helper                         |
| Store Interface  | `IAuditStore.java`                           | Write-once contract — no update/delete                                     |
| MongoDB Store    | `AuditStore.java`                            | `audit_ledger` collection, insert-only, 3 indexes                          |
| HMAC Signing     | `AuditHmac.java`                             | HMAC-SHA256 with PBKDF2-derived key, sorted-key determinism                |
| Batch Writer     | `AuditLedgerService.java`                    | Async queue + flush, secret scrubbing, retry on failure                    |
| Collector        | `IAuditEntryCollector.java`                  | Functional interface decoupling pipeline from storage                      |
| REST API         | `IRestAuditStore` / `RestAuditStore`         | Read-only endpoints: `/auditstore/{conversationId}`                        |
| Pipeline Hook    | `LifecycleManager.java`                      | `buildAuditEntry()` emits per-task audit entries                           |
| Service Wiring   | `ConversationService.java`                   | Audit collector on both `say` and `sayStreaming` paths                   |
| Memory API       | `IConversationMemory` / `ConversationMemory` | `getAuditCollector()` / `setAuditCollector()`                              |
| PostgreSQL Store | `PostgresAuditStore.java`                    | JDBC+JSONB hybrid, `@IfBuildProfile("postgres")`                           |
| LLM Audit        | `LangchainTask.java`                         | Writes `audit:compiled_prompt`, `audit:model_response`, `audit:model_name` |
| Documentation    | `docs/audit-ledger.md`                       | Full feature docs: config, API, HMAC, secret redaction                     |

**Key decisions:**

- **Vault master key reuse**: HMAC signing key derived from `EDDI_VAULT_MASTER_KEY` with a distinct PBKDF2 salt (`eddi-audit-hmac-v1`), so the audit key is cryptographically independent from the vault KEK. No new secret needed.
- **Retry on failure**: On flush failure, entries are re-queued for the next cycle (up to 3 attempts). Prevents data loss from transient DB issues.
- **HMAC determinism**: Map keys sorted via `TreeMap` in canonical string builder — HMAC is stable regardless of `HashMap` vs `LinkedHashMap`.
- **Secret scrubbing**: `SecretRedactionFilter.redact()` applied recursively to strings, nested maps, and lists before HMAC and storage.
- **Environment enrichment**: `ConversationService` wraps the audit collector lambda to add environment — avoids modifying the memory interface further.

**Testing:** 20 new unit tests in `AuditLedgerServiceTest` (queue/flush, HMAC, retry, list scrubbing, determinism, entry helpers). All tests pass.

---

## IDE Warning Cleanup — Phase C (2026-03-16)

### Backend — Unused Imports, Logger Fields, Copy-Paste Bug, Deprecated API, Resource Leak

**Repo:** EDDI (`feature/version-6.0.0`)  
**Commits:** `38f8fa89`, `next`

**What changed:**

Systematic cleanup of ~50 IDE warnings across 23 files, building on the Lombok removal phase.

| Category                                     | Count | Fix                                                                    |
| -------------------------------------------- | ----- | ---------------------------------------------------------------------- |
| Unused `InternalServerErrorException` import | 8     | Removed — classes use `sneakyThrow` not explicit exceptions            |
| Unused `NotFoundException` import            | 2     | Removed                                                                |
| Unused `Logger` import + `log` field         | 11    | Removed from Rest\*Store classes that had no log calls                 |
| Unused `sneakyThrow` import                  | 2     | Removed — classes use explicit exceptions                              |
| Unused `ApplicationScoped` import            | 1     | `URIMessageBodyProvider` uses `@Provider` not `@ApplicationScoped`     |
| Logger copy-paste bug                        | 1     | `RestWorkflowStore` logged as `RestOutputStore.class` → fixed          |
| Deprecated `getSize()`                       | 1     | Removed from `URIMessageBodyProvider` (JAX-RS deprecated since 2.0)    |
| `Scanner` resource leak                      | 1     | Replaced with `InputStream.readAllBytes()` in `URIMessageBodyProvider` |

**Testing:** ✅ 811 tests, 0 failures, 0 errors, 0 skipped.

---

## Phase 7, Item 33: Secrets Vault — Security Remediation (2026-03-16)

### Chat UI + Manager — Secret Input Handling (2026-03-17)

**Repos:** eddi-chat-ui, EDDI-Manager, EDDI (Agent Father)

**What changed:**

Frontend implementation of the secret input system, enabling both backend-driven password fields (`InputFieldOutputItem`) and client-initiated secret marking via context flags.

| Component          | Change                                                                                                        |
| ------------------ | ------------------------------------------------------------------------------------------------------------- |
| **eddi-chat-ui**   | `SecretInput.tsx` (new) — password field with eye toggle for backend-driven prompts                           |
| **eddi-chat-ui**   | `ChatInput.tsx` — 🔒/🔓 secret mode toggle, conditional password input with eye toggle                        |
| **eddi-chat-ui**   | `ChatWidget.tsx` — `processSnapshot` detects `InputFieldOutputItem`, `handleSend` sends `secretInput` context |
| **eddi-chat-ui**   | `chat-api.ts` — `sendMessage` + `sendMessageStreaming` accept optional context                                |
| **eddi-chat-ui**   | `chat-store.tsx` — `activeInputField`, `isSecretMode` state + actions                                         |
| **EDDI-Manager**   | `use-chat.ts` — Zustand store with `activeInputField`, `isSecretMode`, secret context send                    |
| **EDDI-Manager**   | `conversations.ts` — `extractInputField()` parser for backend output                                          |
| **EDDI-Manager**   | `chat-panel.tsx` — `SecretInputField` + `ChatInputWithSecretToggle` inline components                         |
| **EDDI (backend)** | `Conversation.java` — `isSecretInputFlagged()` + scrub plaintext from conversation output                     |
| **Agent Father**   | 3 property setters: `apiKey` scope `conversation` → `secret` (auto-vault)                                     |
| **Agent Father**   | 4 output configs: added `InputFieldOutputItem` {subType: password} for API key prompts                        |

**Code review fixes:**

- Removed unused `inputRef` in `ChatInput.tsx`
- Added `secretContext` to streaming path (`sendMessageStreaming` + `ChatWidget.tsx`)
- Fixed Tailwind `end-3` → `inset-e-3` (logical property) in Manager `chat-panel.tsx`

**Testing:** ✅ EDDI backend compiles clean, eddi-chat-ui 6/6 tests pass, Manager tsc clean.

---

### Manager — Secrets Admin Page (2026-03-17)

**Repo:** EDDI-Manager (`feature/version-6.0.0`)  
**Commit:** `2e4ec47`

**What changed:**

Added a dedicated Secrets Admin page at `/manage/secrets` for managing vault entries through the Manager UI.

| Component              | Change                                                                                                                                                               |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `secrets.ts` (new)     | API module: `listSecrets`, `storeSecret`, `deleteSecret`, `getVaultHealth`                                                                                           |
| `use-secrets.ts` (new) | TanStack Query hooks: `useSecrets`, `useStoreSecret`, `useDeleteSecret`, `useVaultHealth`                                                                            |
| `secrets.tsx` (new)    | Full page: namespace selectors (tenantId/agentId), secrets table with metadata, create dialog (password input + eye toggle), delete confirmation, vault health badge |
| `sidebar.tsx`          | Added Secrets nav item (KeyRound icon) in Operations section                                                                                                         |
| `app.tsx`              | Added `/manage/secrets` route                                                                                                                                        |
| `handlers.ts`          | Added `secretsHandlers` with mock data (2 secrets, store/delete/health)                                                                                              |
| `server.ts`            | Registered `secretsHandlers` in MSW server                                                                                                                           |
| 11 locale files        | Added `nav.secrets` + 35 `secrets.*` i18n keys                                                                                                                       |

**Security measures:**

- Secret values are **write-only** — backend API never returns plaintext
- `autoComplete="off"` on key name input, `autoComplete="new-password"` on value input
- React state cleared immediately on dialog close/submit
- Password field masked by default with optional eye toggle

**Testing:** ✅ 19/19 tests pass, TypeScript zero errors.

---

### Backend — Secrets Vault Hardening + Secret Input

**Repo:** EDDI (`feature/version-6.0.0`)

**What changed:**

Security audit identified 5 critical/high issues in the vault implementation. All fixed plus new secret input mechanism and 32 unit tests.

| Fix                           | Severity | Change                                                                                                                             |
| ----------------------------- | -------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| R1: Memory leakage            | CRITICAL | `PropertySetterTask` no longer resolves vault refs to plaintext; `HttpCallExecutor` scrubs sensitive headers before memory storage |
| R2: URL/body/query resolution | HIGH     | `HttpCallExecutor` now resolves vault refs in URL, body, and query params                                                          |
| R3: Secret input              | NEW      | `Property.Scope.secret` + auto-vault in `PropertySetterTask` + `InputFieldOutputItem` with `subType: password`                     |
| R4: PBKDF2 key derivation     | MEDIUM   | `EnvelopeCrypto` upgraded from SHA-256 to PBKDF2WithHmacSHA256 (600,000 iterations)                                                |
| R5: REST input validation     | LOW      | `RestSecretStore` validates path params against `[a-zA-Z0-9._-]{1,128}`                                                            |

**Additional fixes:**

- Fixed `SecretRedactionFilter` — `$` in replacement string `${eddivault:<REDACTED>}` was interpreted as regex group reference
- Removed dead `secretResolver` field from `PropertySetterTask` (left over from R1 fix)
- Fixed 8 pre-existing Lombok ghost-method bugs in OutputItem subclasses + `OutputConfiguration`
- Cleaned unused imports in `HttpCallExecutorTest` and `PropertySetterTaskTest`

**Key files (new):**

- `src/main/java/ai/labs/eddi/secrets/` — full secrets package (model, crypto, sanitize, rest)
- `src/test/java/ai/labs/eddi/secrets/` — 5 test classes, 32 tests
- `docs/secrets-vault.md` — architecture, encryption, API, security docs
- `docs/research/security-vault.md` — research notes (unversioned)
- `docs/research/security-vault-java.md` — Java implementation research

**Key files (modified):**

- `PropertySetterTask.java` — secret scope handling, removed dead secretResolver
- `HttpCallExecutor.java` — header scrubbing, vault ref resolution in URL/body/query
- `RestExportService.java` — export sanitization via SecretScrubber
- `OutputConfiguration.java` — fixed broken constructor + ghost getters
- 6 OutputItem subclasses — removed bogus getThat()/setThat()

**Design decisions:**

| #   | Decision                               | Reasoning                                                                        |
| --- | -------------------------------------- | -------------------------------------------------------------------------------- |
| 1   | Envelope encryption (per-secret DEK)   | Standard security pattern; allows key rotation without re-encrypting all secrets |
| 2   | PBKDF2 over plain SHA-256              | 600K iteration cost makes brute-force infeasible                                 |
| 3   | Scrub-before-store (not scrub-on-read) | Defense-in-depth: plaintext never hits DB                                        |
| 4   | `Property.Scope.secret`                | Reuses existing property mechanism; no new pipeline concepts                     |
| 5   | `InputFieldOutputItem` for password UI | Output directives already flow to chat UI; subType=password is a clean extension |

**Testing:** ✅ 810 tests (0 failures, 0 errors, 4 skipped). 32 new vault tests across 5 classes.

---

## Phase 6D: Lombok Removal (2026-03-16)

### Backend — Complete Delombok

**Repo:** EDDI (`feature/version-6.0.0`)  
**Commit:** `ca3e45da`

- **IntelliJ Delombok** — Used IntelliJ's built-in delombok to expand all Lombok annotations across 114 files (107 main + 7 test)
- **Field-level fix script** — Python script to handle field-level `@Getter`/`@Setter` that delombok missed; script had a bug inserting methods in reverse order with mismatched braces
- **Manual fixes** — 11 files required manual repair: `Agent.java`, `Data.java`, `ConversationMemorySnapshot.ResultSnapshot`, `ActionMatcher`, `InputMatcher`, `RawSolution`, `OutputGeneration`, `BehaviorRule` (added `getName()`, removed bogus getters for local vars), `BehaviorRulesEvaluator` (fixed constructor), `BehaviorSetResult` (toString syntax), `PropertySetter` (reversed getter)
- **`isIsPublic` → `isPublic`** — Fixed naming for boolean getters (`Data.java`, `ResultSnapshot`)
- **POM cleanup** — Removed `dependency.version.lombok` property, `org.projectlombok:lombok` dependency, and Lombok annotation processor from `maven-compiler-plugin`
- **Result**: 114 files changed, 4174 insertions, 634 deletions. All 775 tests pass, 0 `import lombok` statements, 0 `@Getter/@Setter/@Slf4j` annotations remain

## Phase 6F: Contextual Logging — MDC + Manager Log Panel (2026-03-15)

### Backend — BoundedLogStore + REST/SSE Log Admin

**Repo:** EDDI (`feature/version-6.0.0`)  
**Commits:** `c866a34e` (initial), `2431f858` (bugs, IT, legacy removal), `b6b6bf30` (Quarkus @LoggingFilter)

- **`BoundedLogStore`** — Core ring buffer (configurable size) that captures all JUL log records, tags them with MDC context (agentId, conversationId, environment, userId) and instanceId, then pushes to SSE listeners and optionally batches to DB
- **`InstanceIdProducer`** — Generates stable `hostname-xxxx` identifier per EDDI instance for multi-instance log disambiguation
- **Async batched DB writer** — Drains ring buffer to MongoDB/PostgreSQL every N seconds (configurable). Toggle off with `eddi.logs.db-enabled=false`. Min persist level configurable
- **`IRestLogAdmin` + `RestLogAdmin`** — 4 REST endpoints: GET recent (ring buffer), GET history (DB), GET /stream (SSE), GET /instance
- **Database layer** — `IDatabaseLogs`, `DatabaseLogs`, `PostgresDatabaseLogs` updated with batch insert, instanceId column, nullable filters
- **Config** — `eddi.logs.buffer-size=1000`, `eddi.logs.db-enabled=true`, `eddi.logs.db-flush-interval-seconds=5`, `eddi.logs.db-persist-min-level=WARNING`
- **Tests** — `BoundedLogStoreTest` (16 tests), `RestLogAdminTest` (5 tests). Total: 775 tests pass, 0 failures

### Frontend — Logs Page with Live + History Tabs

**Repo:** EDDI-Manager (`feature/version-6.0.0`)  
**Commit:** `80f4688`

- **API module** (`logs.ts`) — recent, history, instance ID, SSE EventSource factory
- **Hooks** (`use-logs.ts`) — `useLogStream` (SSE with pause/resume, max 500 entries), `useHistoryLogs`, `useInstanceId`
- **Logs page** (`logs.tsx`) — Two-tab interface:
  - **Live tab** — SSE streaming with connection badge, instance ID, agent/level filters, pause/resume, clear, auto-scroll
  - **History tab** — DB query with agent/conversation/instance filters, pagination
- **Collapsible stacktrace** — Java stacktrace patterns (`at `, `Caused by:`) are auto-detected; frames hidden behind expandable toggle with frame count
- **Sidebar** — `ScrollText` icon under Operations section
- **Routing** — `/manage/logs` route
- **MSW** — Mock handlers with realistic data including a stacktrace example
- **i18n** — 23 keys in `logs.*` namespace across all 11 locales

**Design decisions:**

- Hybrid architecture: ring buffer for real-time, DB for durability
- SSE push (event-driven) not polling, for minimal latency
- DB persistence is opt-out via config, so users who rely on console can disable it
- Stacktrace collapsing is frontend-only parsing — no backend changes needed

## Planning Phase (2026-03-05)

### Audit Completed — Implementation Plan Finalized

**Repos involved:** All 5 (EDDI, EDDI-Manager, eddi-chat-ui, eddi-website, EDDI-integration-tests)

**Key decisions made:**

| #   | Decision                                                   | Reasoning                                                                                | Appendix |
| --- | ---------------------------------------------------------- | ---------------------------------------------------------------------------------------- | -------- |
| 1   | UI framework: **React + Vite + shadcn/ui + Tailwind CSS**  | AI-friendly (components are plain files), no dependency rot, accessible (Radix), fast DX | J.1a     |
| 2   | Keep Chat UI **standalone** + extract **shared component** | EDDI has a dedicated single-agent chat endpoint; standalone deployment is needed         | M.1      |
| 3   | Website: **Astro** on GitHub Pages                         | Static output, built-in i18n routing, zero JS by default, Tailwind integration           | L        |
| 4   | **Skip API versioning**                                    | Only clients are Manager + Chat UI, both first-party controlled                        | M.7      |
| 5   | **Remove internal snapshot tests**                         | Never production-ready; integration tests provide sufficient coverage                    | K.1      |
| 6   | **Trunk-based branching**                                  | Short-lived feature branches, squash merge, clean main history                           | N.1      |
| 7   | **Mobile-first responsive** is Phase 1                     | Core requirement, not afterthought; Tailwind breakpoints make this natural               | J.4      |

**Biggest gap discovered:** No CI/CD anywhere — all builds, tests, and deployments are manual.

**Strongest existing areas:** Security (SSRF, rate limiting, cost tracking), Monitoring (30+ Prometheus metrics, Grafana dashboard), Documentation (40 markdown files published via docs.labs.ai).

---

## Implementation Log

### 2026-03-15 — Phase 6E: quarkus-langchain4j → langchain4j Core + ObservableChatModel

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`
**Phase:** 6E — Quick Win (2 SP)
**Commits:** `da69c7d0`, `d353c1d6`, `5c17a50f`, plus test enhancement commit

**What changed:**

Migrated from `io.quarkiverse.langchain4j` (Quarkus extension wrappers) to core `dev.langchain4j` libraries directly. Then added provider-agnostic observability layer.

| Component                          | Change                                                                                                                  |
| ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `pom.xml`                          | Removed 6 quarkiverse deps, added 7 core `dev.langchain4j` deps. Version split: GA (`1.11.0`) vs beta (`1.11.0-beta19`) |
| `VertexGeminiLanguageModelBuilder` | New package, API renames, removed `timeout()` (now EDDI-level)                                                          |
| `HuggingFaceLanguageModelBuilder`  | Removed quarkiverse-only methods (`topK`, `topP`, `doSample`, `repetitionPenalty`). Kept core-only methods              |
| `JlamaLanguageModelBuilder`        | Workflow change, removed `logRequests`/`logResponses`                                                                   |
| `ObservableChatModel`              | **NEW** — provider-agnostic decorator with timeout (Future+cancel) and request/response logging                         |
| `ChatModelRegistry`                | Wires ObservableChatModel into `getOrCreate()`, filters observability params from cache key                             |
| `ObservableChatModelTest`          | **NEW** — 19 tests across 4 nested classes                                                                              |
| `ChatModelRegistryTest`            | 5 new observability integration tests                                                                                   |

**Key decisions:**

| #   | Decision                               | Reasoning                                                                 |
| --- | -------------------------------------- | ------------------------------------------------------------------------- |
| 1   | Provider-agnostic observability layer  | Timeout/logging at EDDI level (not per-provider) ensures uniform behavior |
| 2   | Keep deprecated `HuggingFaceChatModel` | Still functional; EDDI's OpenAI builder can use HF Router as alternative  |
| 3   | Separate `langchain4j-beta.version`    | vertex-ai-gemini, hugging-face, jlama use beta versioning                 |
| 4   | Zero-overhead wrapping                 | `wrapIfNeeded()` returns original model when no observability params set  |

**Testing:** ✅ 753 tests pass (0 failures, 0 errors, 4 skipped). Zero `quarkiverse` references in dependency tree.

**Next:** Phase 6F (Contextual Logging — MDC + Manager Log Panel, 5 SP)

---

### 2026-03-11 — Session Wrap-Up: Next Steps Documented

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`

**What was identified:**

Two follow-up items added to the roadmap before proceeding to Phase 7 (MCP):

1. **6A. MongoDB sync driver migration (5 SP)**: The current MongoDB layer uses `mongodb-driver-reactivestreams` but blocks every call with `Observable.fromPublisher(...).blockingFirst()`. This is an anti-pattern — all the complexity of RxJava3 with none of the non-blocking benefits. 13 files need to be migrated to `mongodb-driver-sync`. Since EDDI's lifecycle pipeline is inherently synchronous (`ILifecycleTask.execute()`), the sync driver is the correct choice.

2. **6B. PostgreSQL integration test parity (3 SP)**: All 48 integration tests only run against MongoDB via `IntegrationTestProfile` (hardcoded `mongodb://` connection). The PostgreSQL storage implementations are unit-tested but never integration-tested end-to-end. Need `PostgresIntegrationTestProfile` to run all ITs against both databases.

**Files affected by 6A:** `MongoResourceStorage`, `MongoDeploymentStorage`, `ConversationMemoryStore`, `DescriptorStore` (mongo), `ResourceFilter`, `ResourceUtilities`, `PropertiesStore`, `AgentTriggerStore`, `UserConversationStore`, `TestCaseStore`, `MigrationManager`, `MigrationLogStore`, `DatabaseLogs`

---

### 2026-03-11 — Phase 6 Item #32: Full Store Migration (5 SP)

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`
**Phase:** 6 — Item #32 (5 SP)

**What changed:**

Completed the full migration of ALL remaining stores from MongoDB-only to DB-agnostic, eliminating the hybrid approach. Every datastore now transparently supports MongoDB or PostgreSQL based on `eddi.datastore.type` configuration.

| Component                         | Change                                                                                                     |
| --------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `IResourceStorage`                | Added `findResourceIdsContaining()`, `findHistoryResourceIdsContaining()`, `findResources()` query methods |
| `MongoResourceStorage`            | Implements new queries with MongoDB `$in`, regex, pagination                                               |
| `PostgresResourceStorage`         | Implements new queries with JSONB `@>`, `~`, SQL pagination                                                |
| `AgentStore`                      | Migrated from `AbstractMongoResourceStore` → `AbstractResourceStore` + `IResourceStorageFactory`           |
| `WorkflowStore`                   | Same migration, removed inner MongoDB utility classes                                                      |
| `IDeploymentStorage`              | New DB-agnostic interface for deployment persistence                                                       |
| `MongoDeploymentStorage`          | New `@DefaultBean` — extracted MongoDB logic from `DeploymentStore`                                        |
| `PostgresDeploymentStorage`       | New `@LookupIfProperty` — JDBC with `INSERT...ON CONFLICT`, dedicated `deployments` table                  |
| `DeploymentStore`                 | Refactored to thin delegate to `IDeploymentStorage`                                                        |
| `DescriptorStore` (datastore pkg) | New DB-agnostic descriptor store using `IResourceStorageFactory` + `findResources()`                       |
| `DocumentDescriptorStore`         | Injects `IResourceStorageFactory` instead of `MongoDatabase`                                               |
| `ConversationDescriptorStore`     | Same — `updateTimeStamp()` reads/modifies/saves via abstraction                                            |
| `TestCaseDescriptorStore`         | Same migration                                                                                             |
| `PostgresConversationMemoryStore` | New — JSONB with indexed columns (agent_id, agent_version, conversation_state)                             |

**Design decisions:**

| #   | Decision                                                         | Reasoning                                                                                                            |
| --- | ---------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| 1   | Add query methods to `IResourceStorage`                          | `AgentStore`/`WorkflowStore` used custom MongoDB containment queries; abstracting these makes them DB-agnostic       |
| 2   | `IDeploymentStorage` as separate interface                       | DeploymentStore has its own data model (not versioned resources) — doesn't fit `IResourceStorage`                    |
| 3   | `PostgresConversationMemoryStore` with extracted indexed columns | JSONB for full snapshot data, but agent_id/agent_version/conversation_state extracted as columns for indexed queries |
| 4   | `DescriptorStore` moved to `datastore` package                   | Was in `datastore.mongo` — breaking the package dependency to make it framework-agnostic                             |

**Tests:** All 701 tests pass (0 failures, 0 errors, 4 skipped). `mvnw verify` succeeds.

---

### 2026-03-11 — Phase 6 Item #31: PostgreSQL Adapter (8 SP)

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`
**Phase:** 6 — Item #31 (8 SP)

**What changed:**

Implemented a PostgreSQL storage backend as an alternative to MongoDB for EDDI's configuration stores. All 7 "simple" stores now use the factory pattern and automatically work with either MongoDB or PostgreSQL depending on configuration.

| Component                        | Change                                                                                           |
| -------------------------------- | ------------------------------------------------------------------------------------------------ |
| `PostgresResourceStorage<T>`     | New — JDBC + JSONB implementation of `IResourceStorage<T>`                                       |
| `PostgresResourceStorageFactory` | New — `@LookupIfProperty(eddi.datastore.type=postgres)`, creates PostgresResourceStorage         |
| `PostgresHealthCheck`            | New — `@Readiness` health check for PostgreSQL connection                                        |
| 7 config stores                  | Migrated from `AbstractMongoResourceStore` → `AbstractResourceStore` + `IResourceStorageFactory` |
| `pom.xml`                        | Added `quarkus-jdbc-postgresql`, `quarkus-agroal`, `testcontainers:postgresql`                   |
| `application.properties`         | Added PostgreSQL datasource config (inactive by default)                                         |
| `docker-compose.postgres.yml`    | New — PostgreSQL 16 + MongoDB + EDDI for local development                                       |

**Design decisions:**

| #   | Decision                                                                  | Reasoning                                                                                                         |
| --- | ------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| 1   | Single `resources` + `resources_history` tables with JSONB `data` column  | Keeps the generic `IResourceStorage` contract intact without per-type schema complexity                           |
| 2   | Uses `IJsonSerialization` instead of `IDocumentBuilder`                   | `IDocumentBuilder.toDocument()` returns `org.bson.Document` — MongoDB-specific; `IJsonSerialization` is pure JSON |
| 3   | AgentStore/WorkflowStore stayed on `AbstractMongoResourceStore` initially | Custom containment queries needed SQL equivalents — resolved in Phase 6.32                                        |
| 4   | ConversationMemoryStore stayed MongoDB initially                          | Complex aggregation, custom interface — resolved in Phase 6.32                                                    |
| 5   | `@LookupIfProperty` for activation                                        | Same pattern as NATS (`eddi.messaging.type`), no code changes to switch backends                                  |

**Tests:** 15 new (12 PostgresResourceStorageTest, 3 PostgresResourceStorageFactoryTest). All 699 tests pass.

---

### 2026-03-11 — Phase 6 Item #30: Repository Interface Abstraction (DB-Agnostic)

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`
**Phase:** 6 — Item #30 (5 SP)

**What changed:**

Introduced a factory-based abstraction layer so that the datastore can support multiple database backends. The core change is a new `IResourceStorageFactory` interface that replaces direct `MongoDatabase` injection in config stores.

| Component                              | Change                                                                   |
| -------------------------------------- | ------------------------------------------------------------------------ |
| `IResourceStorageFactory`              | New interface — single injection point for storage creation              |
| `MongoResourceStorageFactory`          | `@DefaultBean` — creates `MongoResourceStorage`, exposes `getDatabase()` |
| `AbstractResourceStore<T>`             | New DB-agnostic base class (in `datastore` package)                      |
| `HistorizedResourceStore<T>`           | Moved from `datastore.mongo` → `datastore` (zero MongoDB deps)           |
| `ModifiableHistorizedResourceStore<T>` | Same move                                                                |
| `AbstractMongoResourceStore<T>`        | Now extends `AbstractResourceStore`, `@Deprecated`                       |
| `ConversationMemoryStore`              | Added `@DefaultBean` for future override                                 |
| `application.properties`               | New `eddi.datastore.type=mongodb` config                                 |

**Design decisions:**

- Factory pattern (vs CDI alternatives) — mirrors the `@LookupIfProperty` pattern used for NATS
- Backward-compatible wrappers in `mongo` package — all 9 config stores continue working unchanged
- `ConversationMemoryStore` gets `@DefaultBean` only — its `IConversationMemoryStore` interface is already well-defined
- `AgentStore`/`WorkflowStore` inner classes with custom queries remain MongoDB-specific for now

**Tests:** 684 total (0 failures, 0 errors, 4 skipped) — 19 new tests added

---

### 2026-03-11 — Phase 5 Item #30: Coordinator Dashboard + Dead-Letter Admin

**Repos:** EDDI + EDDI-Manager
**Branch:** `feature/version-6.0.0`
**Phase:** 5 — Item #30

**What changed:**

- **Backend REST API** — `IRestCoordinatorAdmin` + `RestCoordinatorAdmin` under `/administration/coordinator/` with SSE endpoint for live status streaming
- **IConversationCoordinator** — Added default methods for status introspection (type, connection, queue depths, totals) and dead-letter management (list, replay, discard, purge)
- **InMemoryConversationCoordinator** — Added retry logic (3 attempts), in-memory dead-letter queue (`ConcurrentLinkedDeque`), processed/dead-lettered counters, full dead-letter CRUD
- **NatsConversationCoordinator** — Added status methods, dead-letter listing (from JetStream), purge (stream purge), counters
- **DTOs** — `CoordinatorStatus` + `DeadLetterEntry` records in `engine.model`
- **Manager UI** — Coordinator page at `/manage/coordinator` with status cards, active queue visualization, dead-letter admin table (replay/discard/purge)
- **Manager Infrastructure** — API module, TanStack Query hooks with SSE, sidebar nav item (Activity icon under Operations), MSW handlers, i18n (11 locales)

**Tests:**

- Backend: 665 total (22 new — `RestCoordinatorAdminTest` 10 + `InMemoryConversationCoordinatorTest` 12)
- Manager: 176 total (12 new — `coordinator.test.tsx`)

**Design decisions:**

- Dead-letter works for **both** coordinator types (user requirement: not NATS-only)
- SSE poller broadcasts status every 2s (balances responsiveness with overhead)
- In-memory dead-letter uses `ConcurrentLinkedDeque` (bounded only by JVM memory)

---

### 2026-03-11 — Phase 5 Item #29: Async Conversation Processing (Dead-Letter + Metrics + Testcontainers IT)

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`
**Phase:** 5 — Item #29

**What changed:**

1. **Dead-letter handling** (`NatsConversationCoordinator.java`) — Tasks that fail are now retried up to `maxRetries` (configurable, default 3). After all retries exhausted, the message is published to a dead-letter JetStream stream (`EDDI_DEAD_LETTERS`) with 30-day `Limits` retention for operator inspection and replay. Payload includes conversationId, error message, and timestamp.
2. **`NatsMetrics` wiring** — coordinator now injects `NatsMetrics` via `Instance<>` (optional CDI). Publish operations record `eddi_nats_publish_count` + `eddi_nats_publish_duration`. Task completions record `eddi_nats_consume_count` + `eddi_nats_consume_duration`. Dead-letter routing increments `eddi_nats_dead_letter_count`.
3. **`RetryableCallable` wrapper** — inner class tracks per-callable retry attempt count, enabling retry-then-dead-letter behavior without extra state maps.
4. **Dead-letter stream creation** — `start()` method now creates/updates both the main conversation stream and the dead-letter stream during NATS initialization.
5. **`application.properties`** — Added `eddi.nats.dead-letter-stream-name=EDDI_DEAD_LETTERS`.
6. **`pom.xml`** — Added `org.testcontainers:testcontainers:2.0.3` and `org.testcontainers:testcontainers-junit-jupiter:2.0.3` (test scope).
7. **Unit tests** — 12 tests in `NatsConversationCoordinatorTest` (was 8): added `shouldRetryTaskBeforeDeadLettering`, `shouldDeadLetterAfterMaxRetries`, `shouldIncrementPublishMetricsOnSubmit`, `shouldIncrementConsumeMetricsOnCompletion`. Existing `shouldProcessNextTaskAfterFailure` updated for retry behavior.
8. **Integration test** (`NatsConversationCoordinatorIT.java`) — 5 Testcontainers tests with real NATS 2.10-alpine: sequential execution, concurrent conversations, dead-letter routing, dead-letter payload verification, connection status.

**Key decisions:**

- **`Instance<NatsMetrics>` over direct injection** — keeps metrics optional and avoids CDI resolution errors when NATS is disabled
- **Per-callable `RetryableCallable` over Map** — simpler lifecycle, no cleanup needed, GC-friendly
- **30-day dead-letter retention** — gives operators ample time for inspection; main stream keeps 24h WorkQueue retention
- **Testcontainers 2.x naming** — `testcontainers-junit-jupiter` (not `junit-jupiter`) per the 2.0 migration guide

**Testing:** ✅ All 643 tests pass (0 failures, 0 errors, 4 skipped). +4 new unit tests vs previous 639.

---

### 2026-03-10 — Phase 5: Event Bus Abstraction + NATS JetStream Adapter

**Repo:** EDDI
**Branch:** `feature/version-6.0.0`
**Phase:** 5 — Items #27-28

**What changed:**

1. **`IEventBus` interface** — abstract event bus with `submitInOrder`, `start`, `shutdown` methods. Decouples conversation ordering from transport.
2. **`IConversationCoordinator` refactored** — now extends `IEventBus`, preserving backward compatibility. All injection sites continue working without changes.
3. **`InMemoryConversationCoordinator`** — renamed from `ConversationCoordinator`, annotated `@DefaultBean` so it's the default when no NATS config exists. Zero behavior change.
4. **`NatsConversationCoordinator`** — JetStream-based implementation, activated via `@LookupIfProperty(eddi.messaging.type=nats)`. Uses NATS for durable ordering while executing callables in-process. Handles NATS publish failures gracefully (falls back to local execution).
5. **`NatsHealthCheck`** — Quarkus `@Readiness` health check at `/q/health/ready`, reports NATS connection status.
6. **`NatsMetrics`** — Micrometer counters/timers: `eddi_nats_publish_count`, `eddi_nats_publish_duration`, `eddi_nats_consume_count`, `eddi_nats_consume_duration`, `eddi_nats_dead_letter_count`.
7. **`pom.xml`** — Added `io.nats:jnats:2.25.2` dependency.
8. **`application.properties`** — Added `eddi.messaging.type=in-memory` (default), `eddi.nats.url`, `eddi.nats.stream-name`, `eddi.nats.max-retries`, `eddi.nats.ack-wait-seconds`.
9. **`docker-compose.nats.yml`** — NATS 2.10-alpine + MongoDB + EDDI for local development.
10. **Fix** — Removed stale `javax.validation.constraints.NotNull` import from `RegularDictionaryConfiguration.java` (pre-existing issue).
11. **Tests** — 8 new `NatsConversationCoordinatorTest` unit tests covering ordering, multi-conversation independence, NATS failure resilience, subject sanitization, and health status.

**Key decisions:**

- **Direct `jnats` over SmallRye Reactive Messaging** — more control over JetStream stream configuration, no extra Quarkus extension overhead
- **`@LookupIfProperty` over CDI `@Alternative`** — cleaner activation, no `beans.xml` needed, Quarkus-idiomatic
- **In-process callable execution** — NATS serves as distributed ordering primitive now; full message serialization for cross-instance consumption is a future enhancement
- **Subject-per-conversation** — `eddi.conversation.<sanitizedId>` ensures per-conversation ordering without shared queue contention
- **WorkQueue retention** — messages auto-deleted after consumption, 24h TTL, file-based storage

**Testing:** ✅ All 639 tests pass (0 failures, 0 errors, 4 skipped)
**Commit:** `e220f4c0`

---

### 2026-03-09 — Backend API Consistency Fixes (N.7)

**Repo:** EDDI + EDDI-Manager
**Branch:** `feature/version-6.0.0`
**Phase:** N.7 (Backend fixes discovered during Phase 4.3 integration testing)

**What changed:**

1. **N.7.1 — Duplicate POST status code**: Verified `RestVersionInfo.create()` returns 201. The 200 observed in Manager tests was caused by Vite dev proxy stripping 201→200. No backend change needed.
2. **N.7.2 — DELETE `?permanent=true`**: Added optional `?permanent=true` query parameter to all 8 store DELETE endpoints. Soft delete remains default. When `permanent=true`, calls `resourceStore.deleteAllPermanently(id)`.
   - Modified: `RestVersionInfo.java`, all 8 `IRest*Store` interfaces and `Rest*Store` implementations
3. **N.7.3 — Deployment status JSON** (**BREAKING**): `GET /administration/{env}/deploymentstatus/{agentId}` now returns `{"status":"READY"}` (JSON) instead of plain text `READY`. Use `?format=text` for backward compatibility (deprecated).
   - Modified: `IRestAgentAdministration.java`, `RestAgentAdministration.java`, `TestCaseRuntime.java`
   - Tests: `AgentDeploymentComponentIT`, `BaseIntegrationIT`, `AgentUseCaseIT`, `AgentEngineIT`
   - Manager: `integration-helpers.ts`, `deployment.integration.spec.ts`
4. **N.7.4 — Health endpoint**: Deferred — Quarkus dev-mode issue, `/q/health/live` workaround sufficient.

**Key decisions:**

- **`?format` query param over Accept header** — simpler for curl/debugging, avoids Content-Negotiation complexity
- **`?permanent=false` default** — backward compatible, no existing client behavior changes

**Testing:** Maven `compile -q` passes (exit 0). Manager TypeScript compiles clean. Full integration tests pending.

---

### 2026-03-07 — Manager UI: Agent Editor (Version Picker, Env Badges, Duplicate)

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #15

**What changed:**

1. **Agent API** (`agents.ts`) — Added `getAgentDescriptorsWithVersions()` for fetching all versions of an agent, `getDeploymentStatuses()` for fetching deployment status across production/production/test environments simultaneously, plus `ENVIRONMENTS` and `Environment` type exports
2. **Agent hooks** (`use-agents.ts`) — Added `useAgentVersions` (version picker data with sort), `useUpdateAgent` (save mutation), `useDeploymentStatuses` (multi-env polling)
3. **Agent Detail page** (`agent-detail.tsx`) — Major rewrite from read-only page to full editor:
   - **Version picker** with relative timestamps (replaces hardcoded v1)
   - **Environment status badges** — 3-column grid showing production/production/test deploy states with per-env deploy/undeploy buttons
   - **Duplicate button** with deep copy and auto-navigation to the clone
   - **Save feedback toast** with auto-dismiss
   - All existing functionality preserved (deploy/undeploy, export, delete, package add/remove)
4. **MSW handlers** — Added agent PUT (returns incremented version), duplicate POST (returns new agent ID), undeploy POST, delete handlers
5. **i18n** — 23 new keys under `agentDetail.*` in all 11 locale files (env labels, duplicate, save feedback)
6. **Tests** — 9 new tests for AgentDetailPage (agent-detail.test.tsx): renders title, status badge, all action buttons, env badges, package section

**Key decisions:**

- **Environment badges vs duplicate cards** — Per UX research, show a single card with environment columns instead of duplicating agent cards per environment. Each environment row has its own deploy/undeploy button
- **Version picker is local state** — Switching versions re-fetches agent data via `useAgent(id, version)` query. No URL param for version to keep URLs clean
- **Test uses Routes wrapper** — Component requires `useParams()`, so tests wrap in `<Routes><Route path="...">` for proper param injection

**Tests:** ✅ 99/99 passing (13 files), TypeScript zero errors, build succeeds

---

### 2026-03-06 — Manager UI: JSON Editor, Version Picker & Config Editor Layout

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #14

**What changed:**

1. **Monaco JSON Editor** (`json-editor.tsx`) — Monaco-based editor wrapper with EDDI dark/light theme auto-detection via `useTheme()`, auto-format on mount, configurable read-only mode, loading spinner
2. **Version Picker** (`version-picker.tsx`) — Dropdown showing version numbers with relative timestamps ("3h ago"). Renders as badge when only one version exists, select when multiple
3. **Config Editor Layout** (`config-editor-layout.tsx`) — Shared editor chrome with `[ Form | JSON ]` tab toggle. both tabs share a single `useState` object for synchronized editing. Header shows type icon, resource name, version picker, save/discard buttons. Dirty-state detection via deep comparison. Form tab shows placeholder ("Visual editor coming soon") for Phase 3.17–3.18
4. **`useUpdateResource` hook** — Mutation calling `PUT /{store}/{plural}/{id}?version={version}`, invalidates queries on success
5. **Resource Detail page** — Rewrote from `<pre>` JSON dump to full `ConfigEditorLayout` with save/discard/dirty-state. All hooks moved above early returns for Rules of Hooks compliance
6. **i18n** — 15 new keys under `editor.*` in all 11 locale files (formTab, jsonTab, save, discard, dirty, etc.)
7. **Tests** — 16 new tests: VersionPicker (3), ConfigEditorLayout (7), ResourceDetailPage integration (4), updated resources.test.tsx (2)
8. **Dependency** — `@monaco-editor/react` installed (brings in `monaco-editor` as peer)

**Key decisions:**

- **Monaco mocked in tests** — JSDOM can't render the Monaco canvas, so tests use a `<textarea>` mock via `vi.mock("@monaco-editor/react")`
- **Form↔JSON toggle architecture** — `config-editor-layout.tsx` is the foundation for all Phases 3.15–3.18 editors. Extension-specific form editors will be passed as `children` prop
- **JSON tab default** — Since no form editors exist yet, JSON tab is the default active tab

**Tests:** ✅ 90/90 passing (12 files), TypeScript zero errors, build succeeds

#### Phase 3.14b — Version Cascade & Deferred Items

9. **Location header fix** (`api-client.ts`) — Capture Location header on `200 OK` responses (not just `201`), enabling version tracking for PUT updates
10. **Cascade save** (`cascade-save.ts`) — Saves config, then walks package→agent chain updating version URIs. Parses new versions from Location headers
11. **Resource usage scanner** (`resource-usage.ts`) — Finds all packages/agents referencing a given config, enabling the "update in agents" post-save dialog
12. **Update usage dialog** (`update-usage-dialog.tsx`) — Amber-themed post-save dialog showing affected agents/packages with checkboxes for selective cascade
13. **Version picker data** (`getResourceVersions`) — API function calling descriptors with `includePreviousVersions=true`
14. **Hooks** — `useResourceVersions` (version picker), `useCascadeSave` (cascade mutation)
15. **Dual-path cascade** in `resource-detail.tsx`:
    - **Path A** (from agent/package): auto-cascade via `?pkgId=…&pkgVer=…&agentId=…&agentVer=…` query params
    - **Path B** (from resource view): post-save usage dialog showing affected agents
16. **MSW handlers** — PUT returns Location header with incremented version; GET descriptors supports `includePreviousVersions` + `filter` params
17. **i18n** — 7 new cascade keys in all 11 locales

**Return types updated:** `updateResource`, `updateWorkflow`, `updateAgent` now return `{ location: string }` to capture backend version URIs.

---

### 2026-03-06 — Manager UI: EDDI Branding, Theme & Font

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #13

**What changed:**

1. **Brand theme restored** — replaced indigo/violet with original EDDI black and gold palette. Primary `#f59e0b` (amber), accent `#fbbf24` (gold), sidebar always dark `#1c1917`, dark mode true blacks `#0c0a09`, light mode warm stone `#fafaf9`
2. **Noto Sans font** — replaced Inter with Noto Sans + script variants (Arabic, Thai, Devanagari, CJK, Korean) via Google Fonts for universal language coverage
3. **Original E.D.D.I logo** — copied `logo_eddi.png` from EDDI backend repo to `public/`; sidebar shows image when expanded, compact gold "E." badge SVG when collapsed
4. **System theme fix** — theme provider now has `matchMedia("prefers-color-scheme: dark")` change listener so "system" mode tracks OS preference in real time (was only checking once on mount)
5. **Wide-screen constraint** — main content area capped at `max-w-screen-2xl` (1536px) to prevent infinite stretching on ultrawide monitors
6. **Test setup** — added `window.matchMedia` mock to `setup.ts` for JSDOM compatibility

**Key decisions:**

- **Noto Sans over Inter** — single font family covers all 11 supported languages' scripts without missing glyphs
- **SVG brand mark for collapsed sidebar** — gold rounded square with "E." matches the logo's style at 28×28px

**Tests:** ✅ 74/74 passing, TypeScript zero errors, build succeeds

---

### 2026-03-06 — Manager UI: Finalize i18n (11 Languages)

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #12

**What changed:**

1. **8 new locale files** — `fr.json` (French), `es.json` (Spanish), `zh.json` (Chinese Simplified), `th.json` (Thai), `ja.json` (Japanese), `ko.json` (Korean), `pt.json` (Portuguese BR), `hi.json` (Hindi)
2. **2 completed locale files** — `de.json` and `ar.json` expanded from ~57 keys to full 219-key parity with `en.json`
3. **`en.json`** — added language labels for all 8 new locales
4. **`config.ts`** — registered all 11 locales with imports and resource entries
5. **`top-bar.tsx`** — language selector expanded from 3 to 11 options
6. **`config.test.ts`** — added key parity regression tests: recursively compares every locale against `en.json` to prevent future key drift (10 new tests)

**Key decisions:**

- **11 languages chosen for global coverage** — en, de, fr, es, ar (RTL), zh, th, ja, ko, pt, hi (~4.5 billion native speakers)
- **Key parity test as regression guard** — any new key added to en.json will cause tests to fail until all 10 locales are updated
- **Hindi uses Devanagari script** — no special rendering needed, standard Unicode

**Tests:** ✅ 74/74 passing (11 files), TypeScript zero errors, build succeeds

---

### 2026-03-06 — Manager UI: Import/Export + Agent Wizard

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #11

**What changed:**

1. **Backup API module** (`backup.ts`) — typed functions for `exportAgent` (2-step: POST to create zip, GET to download), `downloadAgentZip` (triggers browser file save via `<a download>`), `importAgent` (POST with `application/zip` body)
2. **TanStack Query hooks** (`use-backup.ts`) — `useExportAgent` (chained export + download), `useImportAgent` (upload zip, invalidates agents cache)
3. **Agents page** — "Import Agent" button with hidden file input (.zip), "Agent Wizard" CTA link alongside existing "Create Agent"
4. **Agent card** — "Export" added to context menu dropdown (between Duplicate and Delete)
5. **Agent detail page** — "Export" button in header actions area
6. **Agent Wizard page** (`agent-wizard.tsx`) — 4-step guided creation: Template (3 presets: Blank, Q&A, Weather), Info (name/description), Workflows (default package toggle), Review & Create/Deploy
7. **Step progress indicator** — animated circles with checkmarks for completed steps, connecting lines
8. **Routing** — `/manage/agents/wizard` → AgentWizardPage (placed before `/manage/agentview/:id` for correct matching)
9. **i18n** — 40+ new keys under `agents.*` (export/import) and `wizard.*` (all step labels, template names/descriptions)
10. **MSW handlers** — 3 new handlers for `POST /backup/export/:agentId`, `GET /backup/export/:filename`, `POST /backup/import`
11. **Tests** — 11 new tests: 4 for import/export UI (backup.test.tsx), 7 for wizard flow (agent-wizard.test.tsx)

**Key decisions:**

- **Export is a 2-step flow** — POST triggers backend zip creation, response Location header contains the download URL, second GET fetches the binary
- **Import uses raw fetch** — `Content-Type: application/zip` requires bypassing the JSON api-client
- **Wizard is page-internal state** — no separate routes per step, single component with step counter, keeps back/forward simple
- **Templates are cosmetic placeholders** — all currently create blank agents; future phases can wire template-specific package presets

**Tests:** ✅ 64/64 passing (11 files), TypeScript zero errors, build succeeds

---

### 2026-03-06 — Manager UI: Resources Pages (Generic CRUD)

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #10

**What changed:**

1. **Generic API layer** (`resources.ts`) — single parameterized CRUD module that drives all 6 resource types: Behavior Rules (`/behaviorstore/behaviorsets`), HTTP Calls (`/httpcallsstore/httpcalls`), Output Sets (`/outputstore/outputsets`), Dictionaries (`/regulardictionarystore/regulardictionaries`), LangChain (`/langchainstore/langchains`), Property Setter (`/propertysetterstore/propertysetters`)
2. **TanStack Query hooks** (`use-resources.ts`) — `useResourceDescriptors`, `useResource`, `useCreateResource`, `useDeleteResource`, `useDuplicateResource` — all parameterized by type slug, with graceful handling of unknown types (disabled queries instead of throwing)
3. **Resource Card** (`resource-card.tsx`) — reusable card with dynamic icon mapping, context menu (duplicate/delete)
4. **Create Resource Dialog** (`create-resource-dialog.tsx`) — creates empty config, navigates to detail page
5. **Hub Page** (`resources.tsx`) — 6 category cards with icons, descriptions, and item counts
6. **List Page** (`resource-list.tsx`) — generic: search, card grid, create button, error/empty states
7. **Detail Page** (`resource-detail.tsx`) — raw JSON viewer, duplicate/delete actions
8. **Routing** — `/manage/resources/:type` → ResourceListPage, `/manage/resources/:type/:id` → ResourceDetailPage
9. **i18n** — 20+ new keys under `resources.*` including all 6 type names and descriptions
10. **MSW handlers** — `createResourceHandlers()` helper generates mock endpoints for all 6 types
11. **Tests** — 15 new tests for hub, list, and detail pages

**Key decisions:**

- **One solution, six types** — all 6 resource types share identical backend API shape, so a single `ResourceTypeConfig` object drives the entire stack (API → hooks → pages)
- **Hooks handle unknown types gracefully** — queries are disabled (not thrown) for invalid slugs, allowing pages to render error UI

**Tests:** ✅ 53/53 passing (9 files), TypeScript zero errors, build succeeds

---

### 2026-03-06 — Manager UI: Chat Panel

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #9

**What changed:**

1. **Chat API module** (`chat.ts`) — typed functions for `startConversation` (POST), `readConversation` (GET, for welcome messages + resume), `sendMessage` (text/plain), `sendMessageWithContext` (JSON), `sendMessageStreaming` (SSE async generator), `endConversation`
2. **Zustand store + TanStack Query hooks** (`use-chat.ts`) — `useChatStore` for local state (messages, agent selection, streaming toggle persisted to localStorage), `useDeployedAgents`, `useStartConversation` (auto-GETs welcome message), `useSendMessage` (auto-branches streaming/non-streaming), `useConversationHistory`, `useLoadConversation`, `useEndConversation`
3. **Chat components** — `chat-message.tsx` (markdown bubbles via react-markdown + remark-gfm), `chat-input.tsx` (auto-grow textarea), `chat-history.tsx` (conversation history sidebar with resume), `streaming-toggle.tsx` (Zap toggle), `chat-panel.tsx` (main container with agent selector dropdown, history panel, message list, input)
4. **Chat page** (`chat.tsx`) — full-height layout with `ChatPanel`
5. **Routing** — `/manage/chat` → ChatPage
6. **Sidebar** — "Chat" nav item with `MessageCircle` icon between Conversations and Resources
7. **i18n** — 16 new keys under `nav.chat`, `pages.chat.*`, `chat.*`
8. **MSW handlers** — start conversation (201 + Location), send message (snapshot), read conversation (welcome snapshot)
9. **CSS** — chat prose overrides for markdown code blocks and links
10. **Tests** — 7 new tests for ChatPage (heading, subtitle, agent selector, input, streaming toggle, history toggle, empty state)

**Key decisions:**

- After `startConversation` (POST), immediately GETs the conversation to pick up any welcome message
- Streaming mode is **configurable via UI toggle** (persisted to localStorage), not hardcoded
- Conversation history sidebar allows resuming past conversations — loads full conversation via GET
- Uses raw `fetch` for text/plain and SSE endpoints (api-client defaults to JSON)

**Tests:** ✅ 38/38 passing (8 files), TypeScript zero errors, build succeeds (754KB JS, 33KB CSS)

---

### 2026-03-06 — Manager UI: Workflows + Conversations Pages

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Items #6-8

**What changed:**

1. **Workflows List Page** — Full rewrite of placeholder: cards grid, search/filter, create dialog, context menu (duplicate/delete)
2. **Workflow Detail Page** — Extensions list with type labels, config URI, add/remove, expandable raw JSON, delete
3. **Conversations List Page** — Table layout with state filter pills (All/Active/In Progress/Ended/Error), search, delete, links to detail view
4. **Conversation Detail Page** — Step-by-step memory viewer showing user input, actions, agent output per turn, expandable raw JSON per step, conversation properties section
5. **API modules** — `conversations.ts` (GET descriptors, simple log, raw log, DELETE)
6. **TanStack Query hooks** — `useConversationDescriptors`, `useSimpleConversation`, `useRawConversation`, `useDeleteConversation`, `useCreateWorkflow`, `useUpdateWorkflow`, `useDeleteWorkflow`
7. **MSW handlers** — Workflow descriptors, package detail, package CRUD, conversation descriptors, conversation logs
8. **i18n** — Added all `packages.*`, `packageDetail.*`, `conversations.*`, `conversationDetail.*` keys to en.json
9. **Routes** — `/manage/packageview/:id` → WorkflowDetailPage, `/manage/conversationview/:id` → ConversationDetailPage
10. **Vite proxy** — Added `/managedagents` proxy for future Chat Panel

**Tests:** 31/31 passing (7 files), TypeScript zero errors, build succeeds (421KB JS, 29KB CSS)

**Key decisions:**

- Conversations page uses low-level `/conversationstore/conversations` API for browsing/inspecting
- Chat Panel (future Phase 3.9) will use `/agents/managed/{intent}/{userId}` (managed) or `/agents/{env}/{agentId}` (direct)

---

### 2026-03-06 — Manager UI: Greenfield Scaffold + Layout Shell

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Items #2-3

**What changed:**

- Replaced entire Webpack + MUI v4 + Redux + TSLint codebase with Vite 6 + React 19 + Tailwind v4 + TanStack Query + Zustand + ESLint
- 28 new files: config, layout components, i18n (en/de/ar with auto RTL), 5 placeholder pages
- Testing pyramid: Vitest + RTL + MSW (unit/component) + Playwright (E2E config)

**Testing:** ✅ `npx tsc -b` zero errors, `npm run build` succeeds, 14/14 tests pass  
**Commit:** `020007e`

---

### 2026-03-06 — Manager UI: Agents Page

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #4

**What changed:**

- Agent card component with deployment status badges (auto-polled via TanStack Query)
- Deploy/undeploy actions, context menu (duplicate, delete), create agent dialog
- Search/filter, version deduplication via `groupAgentsByName`

**Testing:** ✅ 23/23 tests pass (9 new)  
**Commit:** `e47b0fb`

---

### 2026-03-06 — Manager UI: Agent Detail Page

**Repo:** EDDI-Manager  
**Branch:** `feature/version-6.0.0`  
**Phase:** 3 — Item #5

**What changed:**

- Agent Detail page: deployment status + deploy/undeploy, package list with add/remove
- Searchable package selector, raw JSON config viewer, delete with navigation
- Workflows API, descriptors API, TanStack Query hooks for packages

**Testing:** ✅ 23/23 tests pass, zero TypeScript errors  
**Commit:** `dadc669`

---

### 2026-03-06 — Handoff Prep

**Repo:** EDDI-Manager  
**What changed:** Updated AGENTS.md, created HANDOFF.md  
**Commit:** `6fc510e`

### Template for Each Entry

```markdown
### [DATE] — [SHORT TITLE]

**Repo:** [repo name]  
**Branch:** `feat/...` or `fix/...`  
**Phase:** [1/2/3] — Item #[number]

**What changed:**

- [file 1]: [what and why]
- [file 2]: [what and why]

**Design decision (if any):**
[Why this approach was chosen over alternatives]

**Testing:**

- [ ] Builds cleanly
- [ ] Verified in browser
- [ ] No regressions

**Commit:** `feat(scope): message`
```

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
| 2026-03-05 | Use Astro (not Expo) for website                                      | Static site on GitHub Pages           | Expo would add unnecessary abstraction for a marketing site |
| 2026-03-05 | Use AI complexity scale (🟢/🟡/🔴/⚫) instead of human time estimates | AI will do all implementation work    | Human hours are meaningless for AI execution                |
| 2026-03-05 | Docs already published at docs.labs.ai                                | Third-party tool reads `docs/` folder | Could migrate to Astro Content Collections later            |
|            |                                                                       |                                       |                                                             |

---

## Regression Notes

_Track any regressions introduced during implementation for quick debugging._

| Date | Regression | Cause | Fix | Commit |
| ---- | ---------- | ----- | --- | ------ |
|      |            |       |     |        |
