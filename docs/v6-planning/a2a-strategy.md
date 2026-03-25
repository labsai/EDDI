# A2A Protocol + quarkus-langchain4j Re-adoption Strategy

> **Status:** Approved  
> **Date:** 2026-03-25  
> **Prerequisites:** Phase 8b (MCP Client) ✅

---

## 1. Overview

This document defines EDDI's strategy for:
1. **Re-adopting quarkus-langchain4j provider extensions** for GraalVM native image readiness
2. **Implementing the A2A protocol** for cross-instance agent communication

### Core Principle: Layered Adoption

EDDI re-adopts quarkus-langchain4j as **infrastructure** (native image support, Dev Services) while keeping the **config-driven agent model** (`ChatModelRegistry`, `ILanguageModelBuilder`) untouched. `@RegisterAiService` is not used for the core pipeline.

---

## 2. Background

### Why Re-adopt quarkus-langchain4j?

Phase 6E removed quarkus-langchain4j for control over model lifecycle. However, GraalVM native image compilation requires reflection registration for all LLM provider classes. The provider extensions handle this automatically:

| Concern | Raw langchain4j (current) | With provider extensions |
|---|---|---|
| GraalVM reflection | Manual `reflect-config.json` per provider | Automatic via `ReflectiveClassBuildItem` |
| Class initialization | Manual `--initialize-at-run-time` flags | Extension handles it |
| Proxy registration | Manual `proxy-config.json` | Extension handles it |
| Dev Services | N/A | Auto-start Ollama in Docker for local dev |

### What is A2A?

Google's Agent2Agent protocol (April 2025, Linux Foundation) is an open standard for peer-to-peer agent communication. It complements MCP:

| | MCP | A2A |
|---|---|---|
| Direction | Vertical (agent → tools/data) | Horizontal (agent ↔ agent) |
| EDDI today | ✅ Server + Client (Phase 8) | ❌ Not implemented |

A2A uses: Agent Cards (discovery), JSON-RPC 2.0 (transport), Tasks (lifecycle), Messages (payload), Artifacts (outputs), SSE (streaming).

---

## 3. What We Use vs. Skip

| Layer | Use? | Notes |
|---|---|---|
| **Provider Extensions** (`quarkus-langchain4j-{ollama,openai,anthropic,gemini}`) | ✅ | Native image reflection, Dev Services |
| **Core Extension** (`quarkus-langchain4j-core`) | ✅ | Build-time optimization |
| **Declarative AI Services** (`@RegisterAiService`) | ❌ | EDDI uses `ChatModelRegistry` + `ILanguageModelBuilder` |
| **A2A Server** | ✅ | Via A2A Java SDK (`a2a-java-sdk-server-quarkus`) |
| **A2A Client** | ✅ | Via SDK client or quarkus-langchain4j `@A2AClientAgent` |
| **Config-driven model creation** | ✅ Keep | `ChatModelRegistry` + `ObservableChatModel` unchanged |

### Disabling Auto-Created CDI Beans

Provider extensions create CDI `ChatModel` beans from config properties by default. These must be disabled to avoid conflicting with EDDI's `ChatModelRegistry`:

```properties
# application.properties — keep extension infra, disable auto-created models
quarkus.langchain4j.ollama.chat-model.enabled=false
quarkus.langchain4j.openai.chat-model.enabled=false
quarkus.langchain4j.anthropic.chat-model.enabled=false
quarkus.langchain4j.google-ai-gemini.chat-model.enabled=false
```

### What Stays Unchanged

```
LlmTask → AgentOrchestrator → tool-calling loop
ChatModelRegistry → ILanguageModelBuilder → programmatic ChatModel.builder()
ObservableChatModel (timeout + logging decorator)
ToolExecutionService (rate-limiting, caching, cost tracking)
McpToolProviderManager (MCP client)
All 1045 tests
JSON config → runtime model selection
```

---

## 4. A2A Architecture

### A2A Server (exposing EDDI agents)

Using `io.github.a2asdk:a2a-java-sdk-server-quarkus`:

```
External Agent → /.well-known/agent-card.json → discover EDDI agents
               → POST /a2a/{agentId} (JSON-RPC) → A2ATaskHandler
                                                 → ConversationService.say()
                                                 → response as A2A Task result
```

- **Agent Card**: auto-generated from `AgentConfiguration.name/description` + `LlmConfiguration` capabilities
- **Task lifecycle**: maps to conversation states (`READY` → `IN_PROGRESS` → `ENDED`)
- **SSE streaming**: wraps existing `ConversationEventSink` events into A2A format
- **Opt-in per agent**: `AgentConfiguration.a2aEnabled: true` → card generated, endpoint active

### A2A Client (consuming remote A2A agents)

Remote A2A agents appear as tools in `AgentOrchestrator`, following the same pattern as MCP:

```
LlmConfiguration.a2aAgents[] → A2AToolProviderManager
                              → discover Agent Card
                              → create ToolSpecification + ToolExecutor
                              → wire into AgentOrchestrator alongside MCP tools
```

Config-driven via `LlmConfiguration`:
```json
{
  "a2aAgents": [
    {
      "url": "http://remote-agent:8080/a2a",
      "name": "analyst",
      "skillsFilter": ["data-analysis"]
    }
  ]
}
```

---

## 5. Native Image Considerations

Some features may only work on JVM and need to be disablable for native builds:

| Feature | Native-ready? | Notes |
|---|---|---|
| Core pipeline (LlmTask, tools, memory) | ✅ with provider extensions | Reflection handled by extensions |
| MCP Server (`quarkus-mcp-server-http`) | ✅ | Quarkiverse extension, native-aware |
| MCP Client (`langchain4j-mcp`) | ⚠️ Needs verification | StreamableHttpMcpTransport uses reflection |
| A2A Server (SDK) | ⚠️ Needs verification | SDK may need `@RegisterForReflection` supplements |
| Jlama (local inference) | ❌ Likely JVM-only | Heavy reflection + JNI |
| HuggingFace | ⚠️ Needs verification | HTTP-based, should work |

Strategy: features that don't work in native mode should be **disablable** via config (`eddi.features.jlama.enabled=false`) and excluded from the native binary via Quarkus build-time conditions.

---

## 6. Implementation Roadmap

### Phase 8d: quarkus-langchain4j Provider Re-adoption (5 SP)

| Step | Work | SP |
|---|---|---|
| **8d.1** | Re-add provider extension deps to POM. Disable auto-created CDI beans. Verify 1045 tests pass. | 2 |
| **8d.2** | Build native binary (`./mvnw package -Dnative`). Run test suite on native. Fix remaining reflection issues. Add feature flags for JVM-only features. | 3 |

### Phase 10.5: A2A Integration (10 SP)

| Step | Work | SP |
|---|---|---|
| **10.5.1** | A2A Server — `a2a-java-sdk-server-quarkus` dep, `EddiA2ATaskHandler`, Agent Card generation from config, JSON-RPC endpoint. | 5 |
| **10.5.2** | A2A Client — `A2AToolProviderManager` (parallel to `McpToolProviderManager`), config-driven via `LlmConfiguration.a2aAgents[]`, wired into `AgentOrchestrator`. | 3 |
| **10.5.3** | A2A ↔ Group Conversations bridge — remote agents as group members, groups callable via A2A. | 2 |

### Roadmap Position

```
Phase 8b   ✅  MCP Client
Phase 8c      RAG Foundation
Phase 8d      quarkus-langchain4j Provider Re-adoption + Native Image     ← NEW
Phase 9       DAG Pipeline + Governance
Phase 10      Group Conversations
Phase 10.5    A2A Integration                                              ← NEW
Phase 11a     Persistent Memory
```

---

## 7. Why Not `@RegisterAiService`?

EDDI's core pipeline uses runtime model selection from JSON config. `@RegisterAiService` binds to config properties at deploy time.

| Concern | Impact |
|---|---|
| Runtime model selection | EDDI picks models per-agent, per-task from JSON. `@RegisterAiService` uses static config. |
| Multi-tenant model isolation | Different tenants need different API keys/models. Can't do with static annotation. |
| `ObservableChatModel` | EDDI wraps all models with timeout + logging. Extension-managed models bypass this. |
| Audit integration | `LlmTask` writes `audit:*` memory keys. Extension-managed calls skip this. |
| `ChatModelRegistry` caching | EDDI caches by (type, params) tuple. Extension uses CDI scope. |

**Exception:** `@RegisterAiService` *may* be used for future **internal utility agents** (e.g., group conversation synthesis agent, Workspace AI Operator from Phase 9b) where the model is fixed at deploy time.

---

## 8. POM Changes (Phase 8d)

### Dependencies to Add

```xml
<!-- quarkus-langchain4j provider extensions (native image support) -->
<dependency>
    <groupId>io.quarkiverse.langchain4j</groupId>
    <artifactId>quarkus-langchain4j-ollama</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkiverse.langchain4j</groupId>
    <artifactId>quarkus-langchain4j-openai</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkiverse.langchain4j</groupId>
    <artifactId>quarkus-langchain4j-anthropic</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkiverse.langchain4j</groupId>
    <artifactId>quarkus-langchain4j-vertex-ai-gemini</artifactId>
</dependency>

<!-- A2A Java SDK (Phase 10.5) -->
<dependency>
    <groupId>io.github.a2asdk</groupId>
    <artifactId>a2a-java-sdk-server-quarkus</artifactId>
</dependency>
```

### Dependencies to Keep (unchanged)

All existing raw `dev.langchain4j` deps stay — the provider extensions depend on them transitively but EDDI's builders use them directly.

---

## 9. Files Affected

### Phase 8d (Provider Re-adoption)

| File | Change |
|---|---|
| `pom.xml` | Add quarkus-langchain4j provider deps, manage version |
| `application.properties` | Add `quarkus.langchain4j.*.chat-model.enabled=false` for all providers |
| `src/main/resources/META-INF/native-image/` | May need supplemental reflection config for edge cases |

### Phase 10.5 (A2A)

| File | Change |
|---|---|
| `engine/a2a/EddiA2ATaskHandler.java` | **NEW** — bridges A2A tasks → `ConversationService` |
| `engine/a2a/AgentCardService.java` | **NEW** — generates Agent Cards from `AgentConfiguration` |
| `engine/a2a/A2AToolProviderManager.java` | **NEW** — discovers + wraps remote A2A agents as tools |
| `configs/llm/model/LlmConfiguration.java` | Add `a2aAgents` config field |
| `configs/agents/model/AgentConfiguration.java` | Add `a2aEnabled` field |
| `modules/llm/impl/AgentOrchestrator.java` | Merge A2A tools alongside MCP + built-in tools |
| `application.properties` | A2A server config |
