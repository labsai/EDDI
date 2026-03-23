# EDDI v6 Renaming Specification

> Complete, self-contained specification for the EDDI v6 naming overhaul.
> All decisions are final. This document contains everything needed to implement the changes.

---

## Context

EDDI is an AI orchestration platform. Its terminology dates from the chatagent era (2017–2018). For v6, the surface-level naming is modernized to align with the 2026 AI/LLM industry while keeping full backwards compatibility with existing MongoDB databases and ZIP config exports/imports.

**Repository**: `c:\dev\git\EDDI` (Java 21, Quarkus, Maven)
**Base package**: `ai.labs.eddi`

---

## All Decisions — Locked In

| #   | Rename              | From → To                                                                 | Status       |
| --- | ------------------- | ------------------------------------------------------------------------- | ------------ |
| 1   | Core concept        | `Agent` → `Agent`                                                         | ✅ Confirmed |
| 2   | Core concept        | `Workflow` → `Workflow`                                                   | ✅ Confirmed |
| 3   | Core concept        | `LangChain` → `LLM`                                                       | ✅ Confirmed |
| 4   | Config type         | `Behavior` → `Rules`                                                      | ✅ Confirmed |
| 5   | Config type         | `RegularDictionary` → `Dictionary`                                        | ✅ Confirmed |
| 6   | Config type         | `HttpCalls` → `ApiCalls`                                                  | ✅ Confirmed |
| 7   | Config type         | `PropertySetter` → keep as-is                                             | ❌ Skip (v7) |
| 8   | Config type         | `Output` → keep as-is                                                     | ❌ No change |
| 9   | Config type         | `Parser` → keep as-is                                                     | ❌ No change |
| 10  | Environments        | `unrestricted`/`restricted`/`test` → `production`/`test`                  | ✅ Confirmed |
| 11  | URI structure       | Keep 3-segment (`XYZstore/XYZs`)                                          | ✅ Confirmed |
| 12  | REST path migration | `ContainerRequestFilter` rewrite                                          | ✅ Confirmed |
| 13  | DB migration        | Auto on startup, disablable via config, install script prompts on upgrade | ✅ Confirmed |

---

## 1. `Agent` → `Agent`

### Reasoning

The LLM industry has standardized on "agent" for an autonomous entity that reasons, uses tools, and converses. EDDI already uses `AgentOrchestrator` and `AgentExecutionHelper` internally (in `modules/langchain/impl/`). The MCP ecosystem universally uses "agent." "Agent" feels dated.

### Source Files to Rename (35 files)

#### Config Layer (`configs/agents/`)

| Current File                                   | New File                                       |
| ---------------------------------------------- | ---------------------------------------------- |
| `configs/agents/IAgentStore.java`              | `configs/agents/IAgentStore.java`              |
| `configs/agents/IRestAgentStore.java`          | `configs/agents/IRestAgentStore.java`          |
| `configs/agents/model/AgentConfiguration.java` | `configs/agents/model/AgentConfiguration.java` |
| `configs/agents/mongo/AgentStore.java`         | `configs/agents/mongo/AgentStore.java`         |
| `configs/agents/rest/RestAgentStore.java`      | `configs/agents/rest/RestAgentStore.java`      |

#### Engine API Layer (`engine/api/`)

| Current File                                | New File                                    |
| ------------------------------------------- | ------------------------------------------- |
| `engine/api/IRestAgentAdministration.java`  | `engine/api/IRestAgentAdministration.java`  |
| `engine/api/IRestAgentEngine.java`          | `engine/api/IRestAgentEngine.java`          |
| `engine/api/IRestAgentEngineStreaming.java` | `engine/api/IRestAgentEngineStreaming.java` |
| `engine/api/IRestAgentManagement.java`      | `engine/api/IRestAgentManagement.java`      |

#### Engine Implementation (`engine/internal/`)

| Current File                                    | New File                                        |
| ----------------------------------------------- | ----------------------------------------------- |
| `engine/internal/RestAgentAdministration.java`  | `engine/internal/RestAgentAdministration.java`  |
| `engine/internal/RestAgentEngine.java`          | `engine/internal/RestAgentEngine.java`          |
| `engine/internal/RestAgentEngineStreaming.java` | `engine/internal/RestAgentEngineStreaming.java` |
| `engine/internal/RestAgentManagement.java`      | `engine/internal/RestAgentManagement.java`      |

#### Agent Management (`engine/agentmanagement/`) → rename package to `engine/agentmanagement/`

| Current File                                                  | New File                                                      |
| ------------------------------------------------------------- | ------------------------------------------------------------- |
| `engine/agentmanagement/IAgentTriggerStore.java`              | `engine/agentmanagement/IAgentTriggerStore.java`              |
| `engine/agentmanagement/IRestAgentTriggerStore.java`          | `engine/agentmanagement/IRestAgentTriggerStore.java`          |
| `engine/agentmanagement/model/AgentTriggerConfiguration.java` | `engine/agentmanagement/model/AgentTriggerConfiguration.java` |
| `engine/agentmanagement/mongo/AgentTriggerStore.java`         | `engine/agentmanagement/mongo/AgentTriggerStore.java`         |
| `engine/agentmanagement/rest/RestAgentTriggerStore.java`      | `engine/agentmanagement/rest/RestAgentTriggerStore.java`      |

> [!NOTE]
> `engine/agentmanagement/model/UserConversation.java` — keep the class name but update internal field `agentId` references. Also `engine/agentmanagement/mongo/UserConversationStore.java` — review for `agent` field references.

#### Engine Model (`engine/model/`)

| Current File                              | New File                                  |
| ----------------------------------------- | ----------------------------------------- |
| `engine/model/AgentDeployment.java`       | `engine/model/AgentDeployment.java`       |
| `engine/model/AgentDeploymentStatus.java` | `engine/model/AgentDeploymentStatus.java` |

#### Runtime Layer (`engine/runtime/`)

| Current File                                                        | New File                                                            |
| ------------------------------------------------------------------- | ------------------------------------------------------------------- |
| `engine/runtime/IAgent.java`                                        | `engine/runtime/IAgent.java`                                        |
| `engine/runtime/IAgentFactory.java`                                 | `engine/runtime/IAgentFactory.java`                                 |
| `engine/runtime/IAgentDeploymentManagement.java`                    | `engine/runtime/IAgentDeploymentManagement.java`                    |
| `engine/runtime/internal/Agent.java`                                | `engine/runtime/internal/Agent.java`                                |
| `engine/runtime/internal/AgentFactory.java`                         | `engine/runtime/internal/AgentFactory.java`                         |
| `engine/runtime/internal/AgentDeploymentManagement.java`            | `engine/runtime/internal/AgentDeploymentManagement.java`            |
| `engine/runtime/internal/readiness/AgentsReadiness.java`            | `engine/runtime/internal/readiness/AgentsReadiness.java`            |
| `engine/runtime/internal/readiness/AgentsReadinessHealthCheck.java` | `engine/runtime/internal/readiness/AgentsReadinessHealthCheck.java` |
| `engine/runtime/internal/readiness/IAgentsReadiness.java`           | `engine/runtime/internal/readiness/IAgentsReadiness.java`           |
| `engine/runtime/client/agents/AgentStoreClientLibrary.java`         | `engine/runtime/client/agents/AgentStoreClientLibrary.java`         |
| `engine/runtime/client/agents/IAgentStoreClientLibrary.java`        | `engine/runtime/client/agents/IAgentStoreClientLibrary.java`        |
| `engine/runtime/service/AgentStoreService.java`                     | `engine/runtime/service/AgentStoreService.java`                     |
| `engine/runtime/service/IAgentStoreService.java`                    | `engine/runtime/service/IAgentStoreService.java`                    |

#### Postgres (`datastore/postgres/`)

| Current File                                        | New File                                            |
| --------------------------------------------------- | --------------------------------------------------- |
| `datastore/postgres/PostgresAgentTriggerStore.java` | `datastore/postgres/PostgresAgentTriggerStore.java` |

#### Output model — do NOT rename

- `modules/output/model/types/AgentFaceOutputItem.java` — this is a UI output type name, not the "agent" concept. Leave as-is.

### Test Files to Rename (10 files)

| Current Test File                                              | New Test File                                                  |
| -------------------------------------------------------------- | -------------------------------------------------------------- |
| `configs/agents/rest/RestAgentStoreTest.java`                  | `configs/agents/rest/RestAgentStoreTest.java`                  |
| `engine/internal/RestAgentEngineTest.java`                     | `engine/internal/RestAgentEngineTest.java`                     |
| `engine/runtime/internal/AgentFactoryTest.java`                | `engine/runtime/internal/AgentFactoryTest.java`                |
| `integration/AgentDeploymentComponentIT.java`                  | `integration/AgentDeploymentComponentIT.java`                  |
| `integration/AgentEngineIT.java`                               | `integration/AgentEngineIT.java`                               |
| `integration/AgentUseCaseIT.java`                              | `integration/AgentUseCaseIT.java`                              |
| `integration/CreateApiAgentIT.java`                            | `integration/CreateApiAgentIT.java`                            |
| `integration/postgres/PostgresAgentDeploymentComponentIT.java` | `integration/postgres/PostgresAgentDeploymentComponentIT.java` |
| `integration/postgres/PostgresAgentEngineIT.java`              | `integration/postgres/PostgresAgentEngineIT.java`              |
| `integration/postgres/PostgresAgentUseCaseIT.java`             | `integration/postgres/PostgresAgentUseCaseIT.java`             |

---

## 2. `Workflow` → `Workflow`

### Reasoning

A Workflow defines _what should happen under what conditions_ — behavior rules evaluate expressions, produce actions, and those actions control which API calls fire, which output renders, and what properties get set. This is conditional logic driving execution — a workflow.

While the current `LifecycleManager` executes tasks sequentially (a `for` loop), this is an implementation detail:

- Behavior rules form a **decision tree** (IF conditions → THEN actions)
- Downstream tasks **selectively execute** based on those actions (HttpCallsTask filters by `httpCallActions.contains(action)`)
- The `AgentOrchestrator` adds iterative LLM↔tool loops (up to 10 iterations)
- **DAG execution is on the roadmap**, making Workflow the forward-looking choice
- Versioning could lead to branching/forking — Workflow encompasses this future

"Pipeline" was rejected as too narrow — it implies dumb data flow without decisions. "Flow" is overloaded (LangFlow, Node-RED). "Playbook" was considered but feels too casual for enterprise. **Workflow** is universally understood, used by competitors (n8n, Make, AWS Step Functions), and accurate at the conceptual level.

### Source Files to Rename (15 files)

| Current File                                                      | New File                                                           |
| ----------------------------------------------------------------- | ------------------------------------------------------------------ |
| `configs/packages/IWorkflowStore.java`                            | `configs/workflows/IWorkflowStore.java`                            |
| `configs/packages/IRestWorkflowStore.java`                        | `configs/workflows/IRestWorkflowStore.java`                        |
| `configs/packages/IRestWorkflowExtensionStore.java`               | `configs/workflows/IRestWorkflowStepStore.java`                    |
| `configs/packages/model/WorkflowConfiguration.java`               | `configs/workflows/model/WorkflowConfiguration.java`               |
| `configs/packages/mongo/WorkflowStore.java`                       | `configs/workflows/mongo/WorkflowStore.java`                       |
| `configs/packages/rest/RestWorkflowStore.java`                    | `configs/workflows/rest/RestWorkflowStore.java`                    |
| `configs/packages/rest/RestWorkflowExtensionStore.java`           | `configs/workflows/rest/RestWorkflowStepStore.java`                |
| `engine/runtime/IExecutableWorkflow.java`                         | `engine/runtime/IExecutableWorkflow.java`                          |
| `engine/runtime/IWorkflowFactory.java`                            | `engine/runtime/IWorkflowFactory.java`                             |
| `engine/runtime/internal/WorkflowFactory.java`                    | `engine/runtime/internal/WorkflowFactory.java`                     |
| `engine/runtime/client/packages/IWorkflowStoreClientLibrary.java` | `engine/runtime/client/workflows/IWorkflowStoreClientLibrary.java` |
| `engine/runtime/client/packages/WorkflowStoreClientLibrary.java`  | `engine/runtime/client/workflows/WorkflowStoreClientLibrary.java`  |
| `engine/runtime/service/IWorkflowStoreService.java`               | `engine/runtime/service/IWorkflowStoreService.java`                |
| `engine/runtime/service/WorkflowStoreService.java`                | `engine/runtime/service/WorkflowStoreService.java`                 |
| `engine/lifecycle/exceptions/WorkflowConfigurationException.java` | `engine/lifecycle/exceptions/WorkflowConfigurationException.java`  |

### Inner Class Rename

- `WorkflowConfiguration.WorkflowExtension` → `WorkflowConfiguration.WorkflowStep`

### JSON Field

- `AgentConfiguration` (formerly `AgentConfiguration`) field `packages` → `workflows`
- Use `@JsonAlias("packages")` on the `workflows` field for backwards compatibility

---

## 3. `LangChain` → `LLM`

### Reasoning

"LangChain" is a third-party library name (langchain4j), not a concept. EDDI's config governs _any_ LLM provider (OpenAI, Anthropic, Gemini, Ollama, HuggingFace, jlama). Calling this "LangChain" confuses users into thinking they need the LangChain library. The accurate term is simply "LLM" (Large Language Model configuration).

### Source Files to Rename (7 files)

| Current File                                          | New File                                        |
| ----------------------------------------------------- | ----------------------------------------------- |
| `configs/langchain/ILangChainStore.java`              | `configs/llm/ILlmStore.java`                    |
| `configs/langchain/IRestLangChainStore.java`          | `configs/llm/IRestLlmStore.java`                |
| `configs/langchain/mongo/LangChainStore.java`         | `configs/llm/mongo/LlmStore.java`               |
| `configs/langchain/rest/RestLangChainStore.java`      | `configs/llm/rest/RestLlmStore.java`            |
| `modules/langchain/model/LangChainConfiguration.java` | `modules/langchain/model/LlmConfiguration.java` |
| `modules/langchain/bootstrap/LangChainModule.java`    | `modules/langchain/bootstrap/LlmModule.java`    |
| `modules/langchain/impl/LangchainTask.java`           | `modules/langchain/impl/LlmTask.java`           |

> [!NOTE]
> The `modules/langchain/` package directory itself may optionally be renamed to `modules/llm/`, but this is a large drag on the refactor. The config-layer package (`configs/langchain/`) **must** be renamed to `configs/llm/`. The module package can be deferred.

---

## 4. `Behavior` → `Rules`

### Reasoning

"Behavior" alone is ambiguous. The feature is a rule engine (IF conditions THEN actions). "Rules" or "RuleSet" is universally understood. The existing OpenAPI tag already says "Behavior Rules" — just drop the "Behavior" prefix.

### Source Files to Rename (19 files)

#### Config Layer (`configs/behavior/`) → `configs/rules/`

| Current File                                                     | New File                                              |
| ---------------------------------------------------------------- | ----------------------------------------------------- |
| `configs/behavior/IBehaviorStore.java`                           | `configs/rules/IRuleSetStore.java`                    |
| `configs/behavior/IRestBehaviorStore.java`                       | `configs/rules/IRestRuleSetStore.java`                |
| `configs/behavior/model/BehaviorConfiguration.java`              | `configs/rules/model/RuleSetConfiguration.java`       |
| `configs/behavior/model/BehaviorGroupConfiguration.java`         | `configs/rules/model/RuleGroupConfiguration.java`     |
| `configs/behavior/model/BehaviorRuleConfiguration.java`          | `configs/rules/model/RuleConfiguration.java`          |
| `configs/behavior/model/BehaviorRuleConditionConfiguration.java` | `configs/rules/model/RuleConditionConfiguration.java` |
| `configs/behavior/mongo/BehaviorStore.java`                      | `configs/rules/mongo/RuleSetStore.java`               |
| `configs/behavior/rest/RestBehaviorStore.java`                   | `configs/rules/rest/RestRuleSetStore.java`            |

#### Module Layer (`modules/behavior/`) → `modules/rules/`

| Current File                                               | New File                                            |
| ---------------------------------------------------------- | --------------------------------------------------- |
| `modules/behavior/bootstrap/BehaviorConditions.java`       | `modules/rules/bootstrap/RuleConditions.java`       |
| `modules/behavior/bootstrap/BehaviorModule.java`           | `modules/rules/bootstrap/RulesModule.java`          |
| `modules/behavior/impl/BehaviorDeserialization.java`       | `modules/rules/impl/RuleDeserialization.java`       |
| `modules/behavior/impl/BehaviorGroup.java`                 | `modules/rules/impl/RuleGroup.java`                 |
| `modules/behavior/impl/BehaviorRule.java`                  | `modules/rules/impl/Rule.java`                      |
| `modules/behavior/impl/BehaviorRulesEvaluationTask.java`   | `modules/rules/impl/RulesEvaluationTask.java`       |
| `modules/behavior/impl/BehaviorRulesEvaluator.java`        | `modules/rules/impl/RulesEvaluator.java`            |
| `modules/behavior/impl/BehaviorSet.java`                   | `modules/rules/impl/RuleSet.java`                   |
| `modules/behavior/impl/BehaviorSetResult.java`             | `modules/rules/impl/RuleSetResult.java`             |
| `modules/behavior/impl/IBehaviorDeserialization.java`      | `modules/rules/impl/IRuleDeserialization.java`      |
| `modules/behavior/impl/conditions/IBehaviorCondition.java` | `modules/rules/impl/conditions/IRuleCondition.java` |

---

## 5. `RegularDictionary` → `Dictionary`

### Reasoning

The "Regular" qualifier is a legacy artifact. There are no "irregular" dictionaries. Drop the prefix for clarity.

### Source Files to Rename (5 config files only)

| Current File                                                          | New File                                                |
| --------------------------------------------------------------------- | ------------------------------------------------------- |
| `configs/regulardictionary/IRegularDictionaryStore.java`              | `configs/dictionary/IDictionaryStore.java`              |
| `configs/regulardictionary/IRestRegularDictionaryStore.java`          | `configs/dictionary/IRestDictionaryStore.java`          |
| `configs/regulardictionary/model/RegularDictionaryConfiguration.java` | `configs/dictionary/model/DictionaryConfiguration.java` |
| `configs/regulardictionary/mongo/RegularDictionaryStore.java`         | `configs/dictionary/mongo/DictionaryStore.java`         |
| `configs/regulardictionary/rest/RestRegularDictionaryStore.java`      | `configs/dictionary/rest/RestDictionaryStore.java`      |

> [!WARNING]
> **Do NOT rename** the NLP dictionary classes in `modules/nlp/extensions/dictionaries/` — those are a different concept entirely (parser dictionaries like IntegerDictionary, EmailDictionary, etc.). Only rename the `configs/regulardictionary/` package (the user-created configuration dictionaries).

---

## 6. `HttpCalls` → `ApiCalls`

### Reasoning

"HttpCalls" describes the transport protocol, not the purpose. "ApiCalls" is still honest (these are API calls), less ugly, and protocol-neutral (doesn't exclude future gRPC/WebSocket). "Integrations" was rejected as too broad.

### Source Files to Rename (11 files)

#### Config Layer (`configs/httpcalls/`) → `configs/apicalls/`

| Current File                                            | New File                                              |
| ------------------------------------------------------- | ----------------------------------------------------- |
| `configs/httpcalls/IHttpCallsStore.java`                | `configs/apicalls/IApiCallsStore.java`                |
| `configs/httpcalls/IRestHttpCallsStore.java`            | `configs/apicalls/IRestApiCallsStore.java`            |
| `configs/httpcalls/model/HttpCallsConfiguration.java`   | `configs/apicalls/model/ApiCallsConfiguration.java`   |
| `configs/httpcalls/model/HttpCall.java`                 | `configs/apicalls/model/ApiCall.java`                 |
| `configs/httpcalls/model/RetryHttpCallInstruction.java` | `configs/apicalls/model/RetryApiCallInstruction.java` |
| `configs/httpcalls/mongo/HttpCallsStore.java`           | `configs/apicalls/mongo/ApiCallsStore.java`           |
| `configs/httpcalls/rest/RestHttpCallsStore.java`        | `configs/apicalls/rest/RestApiCallsStore.java`        |

#### Module Layer (`modules/httpcalls/`) → `modules/apicalls/`

| Current File                                       | New File                                         |
| -------------------------------------------------- | ------------------------------------------------ |
| `modules/httpcalls/bootstrap/HttpCallsModule.java` | `modules/apicalls/bootstrap/ApiCallsModule.java` |
| `modules/httpcalls/impl/HttpCallsTask.java`        | `modules/apicalls/impl/ApiCallsTask.java`        |
| `modules/httpcalls/impl/HttpCallExecutor.java`     | `modules/apicalls/impl/ApiCallExecutor.java`     |
| `modules/httpcalls/impl/IHttpCallExecutor.java`    | `modules/apicalls/impl/IApiCallExecutor.java`    |

---

## 7. Deployment Environments

### Current State

```java
// engine/model/Deployment.java
public enum Environment { restricted, unrestricted, test }
```

- `unrestricted` — public, no auth required. Default everywhere.
- `restricted` — intended for authenticated users, but **no auth enforcement exists in code**. Simply uses a separate in-memory map.
- `test` — test/staging isolation. Equivalent to `unrestricted` in behavior.

### Decision: Reduce to `production` + `test`

```java
public enum Environment {
    production,  // was: unrestricted
    test         // same
}
```

`restricted` is dropped — it had no auth enforcement (just a separate `ConcurrentHashMap` in `AgentFactory.createEmptyEnvironments()`). Any existing `restricted` deployments become `production`.

### Files Affected

- `engine/model/Deployment.java` — the enum itself
- `engine/runtime/internal/AgentFactory.java` (`AgentFactory.java` after rename) — `createEmptyEnvironments()` method creates maps for each enum value
- Every REST interface that takes `@PathParam("environment") Deployment.Environment` (pervasive across `IRestAgentEngine`, `IRestAgentAdministration`, `IRestAgentManagement`, MCP tools)
- Mongo stores: `MongoDeploymentStorage.java`, `PostgresDeploymentStorage.java`

### Backwards Compatibility

| Surface              | Strategy                                                                                                                      |
| -------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| **Java enum**        | Custom Jackson deserializer or `@JsonCreator` on the enum: `"unrestricted"` → `production`, `"restricted"` → `production`     |
| **REST path params** | `LegacyPathRewriteFilter` (§12) rewrites `/{environment}/` segments                                                           |
| **MongoDB**          | `MigrationManager` v6 migration rewrites `"environment"` field values in `deployments` and `conversationmemories` collections |
| **PostgreSQL**       | No migration needed — Postgres is new, starts with new values                                                                 |
| **MCP tools**        | Accept `"unrestricted"` as alias for `"production"` in all environment params                                                 |
| **ZIP imports**      | Import service rewrites environment strings during import                                                                     |

---

## 8. URI Scheme

### Constraint

`RestUtilities.extractResourceId()` (line 67) has `split.length > 2` — requires **minimum 3 path segments** after the host. Two-segment URIs (like `agents/{id}`) return `null` for the ID.

**Decision: Keep 3-segment URI structure** (`XYZstore/XYZs/{id}`). Do not touch the parser.

### Complete URI Mapping

| Current URI                                                                                  | New URI (v6)                                                            |
| -------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| `eddi://ai.labs.agent/agentstore/agents/{id}?version=V`                                      | `eddi://ai.labs.agent/agentstore/agents/{id}?version=V`                 |
| `eddi://ai.labs.package/packagestore/packages/{id}?version=V`                                | `eddi://ai.labs.workflow/workflowstore/workflows/{id}?version=V`        |
| `eddi://ai.labs.llm/langchainstore/langchains/{id}?version=V`                                | `eddi://ai.labs.llm/llmstore/llmconfigs/{id}?version=V`                 |
| `eddi://ai.labs.behavior/behaviorstore/behaviorsets/{id}?version=V`                          | `eddi://ai.labs.rules/rulestore/rulesets/{id}?version=V`                |
| `eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/{id}?version=V`                           | `eddi://ai.labs.apicalls/apicallstore/apicalls/{id}?version=V`          |
| `eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/{id}?version=V` | `eddi://ai.labs.dictionary/dictionarystore/dictionaries/{id}?version=V` |
| `eddi://ai.labs.agenttrigger/agenttriggerstore/agenttriggers/{id}`                           | `eddi://ai.labs.trigger/triggerstore/triggers/{id}`                     |
| `eddi://ai.labs.output/outputstore/outputsets/{id}?version=V`                                | **no change**                                                           |
| `eddi://ai.labs.property/propertysetterstore/propertysetters/{id}?version=V`                 | **no change**                                                           |
| `eddi://ai.labs.parser/parserstore/parsers/{id}?version=V`                                   | **no change**                                                           |
| `eddi://ai.labs.conversation/conversationstore/conversations/{id}`                           | **no change**                                                           |
| `eddi://ai.labs.userconversation/userconversationstore/userconversations/{id}`               | **no change**                                                           |
| `eddi://ai.labs.descriptor/descriptorstore/descriptors/{id}`                                 | **no change**                                                           |
| `eddi://ai.labs.properties/propertiesstore/properties/{id}`                                  | **no change**                                                           |

---

## 9. REST Path Mapping

### Old → New REST Paths

| Old REST Path                                    | New REST Path                                    |
| ------------------------------------------------ | ------------------------------------------------ |
| `/agentstore/agents`                             | `/agentstore/agents`                             |
| `/packagestore/packages`                         | `/workflowstore/workflows`                       |
| `/langchainstore/langchains`                     | `/llmstore/llmconfigs`                           |
| `/behaviorstore/behaviorsets`                    | `/rulestore/rulesets`                            |
| `/httpcallsstore/httpcalls`                      | `/apicallstore/apicalls`                         |
| `/regulardictionarystore/regulardictionaries`    | `/dictionarystore/dictionaries`                  |
| `/administration/{environment}/deploy/{agentId}` | `/administration/{environment}/deploy/{agentId}` |

### Migration: `LegacyPathRewriteFilter`

**Decision: Use a `@PreMatching` `ContainerRequestFilter`** to rewrite old paths to new paths. This centralizes all legacy mappings in one class, keeps `@Path` annotations clean, and is removable in v7 as a single class deletion.

```java
@PreMatching
@Provider
public class LegacyPathRewriteFilter implements ContainerRequestFilter {
    private static final Map<String, String> REWRITES = Map.of(
        "/agentstore/agents", "/agentstore/agents",
        "/packagestore/packages", "/workflowstore/workflows",
        "/langchainstore/langchains", "/llmstore/llmconfigs",
        "/behaviorstore/behaviorsets", "/rulestore/rulesets",
        "/httpcallsstore/httpcalls", "/apicallstore/apicalls",
        "/regulardictionarystore/regulardictionaries", "/dictionarystore/dictionaries"
    );
    // Also rewrite environment path segments:
    // "unrestricted" → "production", "restricted" → "production"
}
```

---

## 10. MCP Tool Renames

All MCP tools are in `engine/mcp/` (3 files: `McpSetupTools.java`, `McpConversationTools.java`, `McpAdminTools.java`).

### Complete MCP Tool Mapping

| Current Tool Name       | New Tool Name          | File                        |
| ----------------------- | ---------------------- | --------------------------- |
| `setup_agent`           | `setup_agent`          | `McpSetupTools.java`        |
| `create_api_agent`      | `create_api_agent`     | `McpSetupTools.java`        |
| `list_agents`           | `list_agents`          | `McpConversationTools.java` |
| `list_agent_configs`    | `list_agent_configs`   | `McpConversationTools.java` |
| `talk_to_agent`         | `talk_to_agent`        | `McpConversationTools.java` |
| `chat_with_agent`       | `chat_with_agent`      | `McpConversationTools.java` |
| `get_agent`             | `get_agent`            | `McpConversationTools.java` |
| `read_agent_logs`       | `read_agent_logs`      | `McpConversationTools.java` |
| `discover_agents`       | `discover_agents`      | `McpConversationTools.java` |
| `list_conversations`    | **no change**          | `McpConversationTools.java` |
| `read_conversation`     | **no change**          | `McpConversationTools.java` |
| `read_conversation_log` | **no change**          | `McpConversationTools.java` |
| `read_audit_trail`      | **no change**          | `McpConversationTools.java` |
| `chat_managed`          | **no change**          | `McpConversationTools.java` |
| `create_conversation`   | **no change**          | `McpConversationTools.java` |
| `deploy_agent`          | `deploy_agent`         | `McpAdminTools.java`        |
| `undeploy_agent`        | `undeploy_agent`       | `McpAdminTools.java`        |
| `get_deployment_status` | **no change**          | `McpAdminTools.java`        |
| `create_agent`          | `create_agent`         | `McpAdminTools.java`        |
| `delete_agent`          | `delete_agent`         | `McpAdminTools.java`        |
| `update_agent`          | `update_agent`         | `McpAdminTools.java`        |
| `list_workflows`        | `list_workflows`       | `McpAdminTools.java`        |
| `read_workflow`         | `read_workflow`        | `McpAdminTools.java`        |
| `apply_agent_changes`   | `apply_agent_changes`  | `McpAdminTools.java`        |
| `list_agent_resources`  | `list_agent_resources` | `McpAdminTools.java`        |
| `list_agent_triggers`   | `list_agent_triggers`  | `McpAdminTools.java`        |
| `create_agent_trigger`  | `create_agent_trigger` | `McpAdminTools.java`        |
| `update_agent_trigger`  | `update_agent_trigger` | `McpAdminTools.java`        |
| `delete_agent_trigger`  | `delete_agent_trigger` | `McpAdminTools.java`        |
| `read_resource`         | **no change**          | `McpAdminTools.java`        |
| `update_resource`       | **no change**          | `McpAdminTools.java`        |
| `create_resource`       | **no change**          | `McpAdminTools.java`        |
| `delete_resource`       | **no change**          | `McpAdminTools.java`        |
| `create_schedule`       | **no change**          | `McpAdminTools.java`        |
| `list_schedules`        | **no change**          | `McpAdminTools.java`        |
| `read_schedule`         | **no change**          | `McpAdminTools.java`        |
| `delete_schedule`       | **no change**          | `McpAdminTools.java`        |
| `fire_schedule_now`     | **no change**          | `McpAdminTools.java`        |
| `retry_failed_schedule` | **no change**          | `McpAdminTools.java`        |

### MCP Tool Descriptions

All tool descriptions and parameter descriptions that reference "agent" or "package" must be updated to "agent" or "workflow" respectively. Also update the `resourceType` accepted values in `read_resource`/`update_resource`/`create_resource`/`delete_resource` — replace `"langchain"` with `"llm"`, `"behavior"` with `"rules"`, `"httpcalls"` with `"apicalls"`, `"dictionaries"` stays as `"dictionaries"`.

### MCP Doc Resources

- `McpDocResources.java` — update resource URIs and descriptions that reference agents/packages

---

## 11. MongoDB Migration (MongoDB Only)

### Scope

The v6 rename migration **only applies to MongoDB**. PostgreSQL is new — tables use new names from the start (clean schema).

This is already architecturally handled:

- `MigrationManager` (MongoDB) — `@DefaultBean`, runs migrations
- `PostgresMigrationManager` — `@IfBuildProfile("postgres")`, no-op that skips all migrations

### Collection Name Mapping

| Old Mongo Collection                                  | New Mongo Collection                    |
| ----------------------------------------------------- | --------------------------------------- |
| `agents` + `agents.history`                           | `agents` + `agents.history`             |
| `packages` + `packages.history`                       | `workflows` + `workflows.history`       |
| `langchain` + `langchain.history`                     | `llmconfigs` + `llmconfigs.history`     |
| `behaviorsets` + `behaviorsets.history`               | `rulesets` + `rulesets.history`         |
| `httpcalls` + `httpcalls.history`                     | `apicalls` + `apicalls.history`         |
| `regulardictionaries` + `regulardictionaries.history` | `dictionaries` + `dictionaries.history` |
| `agenttriggers`                                       | `agenttriggers`                         |
| `outputs`                                             | **no change**                           |
| `propertysetter`                                      | **no change**                           |
| `parsers`                                             | **no change**                           |
| `descriptors`                                         | **no change**                           |
| `deployments`                                         | **no change**                           |
| `conversationmemories`                                | **no change**                           |
| `userconversations`                                   | **no change**                           |
| `logs`                                                | **no change**                           |
| `audit_ledger`                                        | **no change**                           |
| `migrationlog`                                        | **no change**                           |
| `properties`                                          | **no change**                           |
| `eddi_schedules`                                      | **no change**                           |
| `eddi_schedule_fire_logs`                             | **no change**                           |

### Migration Implementation

Add to `MigrationManager.startMigrationIfFirstTimeRun()`:

```java
private void startV6RenameMigration() {
    Map<String, String> renames = Map.of(
        "agents", "agents",
        "packages", "workflows",
        "langchain", "llmconfigs",
        "behaviorsets", "rulesets",
        "httpcalls", "apicalls",
        "regulardictionaries", "dictionaries",
        "agenttriggers", "agenttriggers"
    );

    for (var entry : renames.entrySet()) {
        renameCollectionIfExists(entry.getKey(), entry.getValue());
        renameCollectionIfExists(entry.getKey() + ".history", entry.getValue() + ".history");
    }

    // Rewrite eddi:// URIs inside all documents
    rewriteUrisInAllCollections(URI_PREFIX_MAP);

    // Rewrite environment field values
    rewriteEnvironmentFields();
}
```

### URI Rewrite Map (for inside documents)

```java
Map<String, String> URI_PREFIX_MAP = Map.of(
    "eddi://ai.labs.agent/agentstore/agents/", "eddi://ai.labs.agent/agentstore/agents/",
    "eddi://ai.labs.package/packagestore/packages/", "eddi://ai.labs.workflow/workflowstore/workflows/",
    "eddi://ai.labs.llm/langchainstore/langchains/", "eddi://ai.labs.llm/llmstore/llmconfigs/",
    "eddi://ai.labs.behavior/behaviorstore/behaviorsets/", "eddi://ai.labs.rules/rulestore/rulesets/",
    "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/", "eddi://ai.labs.apicalls/apicallstore/apicalls/",
    "eddi://ai.labs.regulardictionary/regulardictionarystore/regulardictionaries/", "eddi://ai.labs.dictionary/dictionarystore/dictionaries/",
    "eddi://ai.labs.agenttrigger/agenttriggerstore/agenttriggers/", "eddi://ai.labs.trigger/triggerstore/triggers/"
);
```

### Migration Trigger Config

```properties
eddi.migration.v6-rename.enabled=true  # default true; set false to skip
```

- **Auto on v6 first startup** (default)
- **Install script** prompts on upgrade: detect existing DB, ask user to auto-migrate or skip
- **Manual fallback**: `/admin/migrate` REST endpoint for operators who disabled auto-migration

---

## 12. Backwards Compatibility Matrix

| Layer                        | Old Value                                  | New Value                        | Strategy                                                          |
| ---------------------------- | ------------------------------------------ | -------------------------------- | ----------------------------------------------------------------- |
| **JSON fields**              | `"packages"` in AgentConfig                | `"workflows"`                    | `@JsonAlias("packages")`                                          |
| **JSON enum**                | `"unrestricted"`, `"restricted"`           | `"production"`                   | Custom `@JsonCreator` on `Environment` enum                       |
| **REST paths**               | `/agentstore/agents`, etc.                 | `/agentstore/agents`, etc.       | `LegacyPathRewriteFilter` `ContainerRequestFilter`                |
| **REST env segment**         | `/unrestricted/`, `/restricted/`           | `/production/`                   | Same `LegacyPathRewriteFilter`                                    |
| **eddi:// URIs**             | `eddi://ai.labs.agent/...`                 | `eddi://ai.labs.agent/...`       | `MigrationManager` URI rewrite on startup                         |
| **Mongo collections**        | `agents`, `packages`, etc.                 | `agents`, `workflows`, etc.      | `MigrationManager` `renameCollection()`                           |
| **Mongo environment fields** | `"unrestricted"`, `"restricted"`           | `"production"`                   | `MigrationManager` field rewrite                                  |
| **MCP tool names**           | `setup_agent`, etc.                        | `setup_agent`, etc.              | Rename in code; no aliasing needed (MCP tools are server-defined) |
| **MCP resource types**       | `"langchain"`, `"behavior"`, `"httpcalls"` | `"llm"`, `"rules"`, `"apicalls"` | Accept old values via alias map in MCP tool handlers              |
| **ZIP imports**              | Old URIs and field names                   | New                              | Import service applies URI rewrite map during import              |
| **OpenAPI tags**             | `"07. Agents"`, etc.                       | `"07. Agents"`, etc.             | Update `@Tag` annotations                                         |
| **PostgreSQL**               | N/A (new)                                  | New names from start             | No migration needed                                               |

---

## 13. OpenAPI Tag Renames

| Current Tag                | New Tag                    |
| -------------------------- | -------------------------- |
| `02. Behavior Rules`       | `02. Rules`                |
| `03. LangChains`           | `03. LLM`                  |
| `04. HTTP Calls`           | `04. API Calls`            |
| `06. Workflows`            | `06. Workflows`            |
| `07. Agents`               | `07. Agents`               |
| `08. Agent Administration` | `08. Agent Administration` |
| `09. Agent Engine`         | `09. Agent Engine`         |

---

## 14. Documentation Updates

| File                         | Changes                                                                              |
| ---------------------------- | ------------------------------------------------------------------------------------ |
| `docs/mcp-server.md`         | All tool names, parameter names, and descriptions referencing agent/package/pipeline |
| `docs/changelog.md`          | Add v6 entry documenting all renames                                                 |
| `HANDOFF.md`                 | Update roadmap with v6 rename phase                                                  |
| `install.sh` / `install.ps1` | Add upgrade detection + migration prompt                                             |
| `README.md`                  | Update terminology                                                                   |

---

## 15. Implementation Order

1. **Backwards compat infrastructure first**: `LegacyPathRewriteFilter`, `@JsonAlias` annotations, `Environment` enum with `@JsonCreator`, URI rewrite utility
2. **MigrationManager v6 extension**: Add rename migration with config toggle
3. **Config layer renames**: `configs/agents/` → `configs/agents/`, etc. (6 packages)
4. **Module layer renames**: `modules/behavior/` → `modules/rules/`, etc. (3 packages)
5. **Engine/runtime renames**: interfaces, impls, services, clients (largest batch)
6. **MCP tool renames**: `@Tool(name=...)` annotations + descriptions
7. **Test file renames**: All corresponding test classes
8. **Documentation**: mcp-server.md, changelog, README
9. **Install scripts**: Add upgrade detection + migration prompt
10. **Verification**: Full build + all tests pass + manual MCP smoke test
