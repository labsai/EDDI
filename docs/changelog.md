# EDDI Ecosystem — Working Changelog

> **Purpose:** Living document tracking all changes, decisions, and reasoning during implementation. Updated as work progresses for easy reference and review.

---

## How to Read This Document

Each entry follows this format:

- **Date** — What changed and why
- **Repo** — Which repository was modified
- **Decision** — Key design decisions and their reasoning
- **Files** — Links to modified files

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
| **Documentation**               | ~40   | `mcp-server.md`, `changelog.md`, `v6_renaming_recommendation.md`                               |
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
| `McpSetupToolsTest.java`          | 10 new tests (31 total): quick replies, sentiment, agenth, anthropic no-responseFormat, helper methods                             |

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

- Takes an OpenAPI spec → generates httpcalls configs → wires as LangChain tools → creates a agent that can call any API securely
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
| **4. Ollama baseUrl backend** | `OllamaLanguageModelBuilder.java`         | Added `baseUrl` parameter to agenth `build()` and `buildStreaming()`                                                                                         |
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
| Service Wiring   | `ConversationService.java`                   | Audit collector on agenth `say` and `sayStreaming` paths                   |
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

Frontend implementation of the secret input system, enabling agenth backend-driven password fields (`InputFieldOutputItem`) and client-initiated secret marking via context flags.

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
| 4   | **Skip API versioning**                                    | Only clients are Manager + Chat UI, agenth first-party controlled                        | M.7      |
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

2. **6B. PostgreSQL integration test parity (3 SP)**: All 48 integration tests only run against MongoDB via `IntegrationTestProfile` (hardcoded `mongodb://` connection). The PostgreSQL storage implementations are unit-tested but never integration-tested end-to-end. Need `PostgresIntegrationTestProfile` to run all ITs against agenth databases.

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

- Dead-letter works for **agenth** coordinator types (user requirement: not NATS-only)
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
4. **Dead-letter stream creation** — `start()` method now creates/updates agenth the main conversation stream and the dead-letter stream during NATS initialization.
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

1. **Agent API** (`agents.ts`) — Added `getAgentDescriptorsWithVersions()` for fetching all versions of a agent, `getDeploymentStatuses()` for fetching deployment status across production/production/test environments simultaneously, plus `ENVIRONMENTS` and `Environment` type exports
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
3. **Config Editor Layout** (`config-editor-layout.tsx`) — Shared editor chrome with `[ Form | JSON ]` tab toggle. Agenth tabs share a single `useState` object for synchronized editing. Header shows type icon, resource name, version picker, save/discard buttons. Dirty-state detection via deep comparison. Form tab shows placeholder ("Visual editor coming soon") for Phase 3.17–3.18
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
- Chat Panel (future Phase 3.9) will use `/managedagents/{intent}/{userId}` (managed) or `/agents/{env}/{agentId}` (direct)

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
