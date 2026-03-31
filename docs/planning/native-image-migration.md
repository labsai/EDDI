# Native Image Migration Plan

> **Goal:** Compile EDDI into a GraalVM native executable for fast startup and low memory footprint.
> **Status:** Planning — not yet started
> **Estimated effort:** ~5-7 days
> **Strategy:** B2 — Re-adopt `quarkus-langchain4j` extensions, disable managed beans, keep EDDI's custom builder architecture

---

## Current State — What's Already Native-Ready ✅

The v6 refactoring eliminated most classic native blockers:

- **Thymeleaf → Qute** — Eliminated OGNL reflection + ServiceLoader
- **Infinispan → Caffeine** — Eliminated JGroups/Infinispan native issues
- **RxJava3 removed** — Simplified to sync MongoDB driver
- **Lombok removed** — No annotation processor magic at runtime
- **Clean codebase** — No `Class.forName`, `getDeclaredField`, `newInstance`, dynamic proxies, or `ServiceLoader` in EDDI's own code
- **Build infra exists** — Native Maven profile (`-Dnative`), two native Dockerfiles, `native-image-agent` goal configured

### Key Facts

- **Java 25** — GraalVM 25 is GA (released Sept 2025), supports Java 25
- **Quarkus 3.34.1** — Current EDDI version, fully supports native
- **jlama IS native-compatible** — Uses Panama Vector API which GraalVM 25 supports
- **classgraph** — Only transitive, never imported in EDDI source code; non-issue

---

## Strategy: B2 — quarkus-langchain4j with Disabled Integrations

### Why quarkus-langchain4j?

EDDI previously used `quarkus-langchain4j` but migrated to plain `dev.langchain4j` in Phase 6E for more control. For native image, quarkus-langchain4j provides critical build-time processing:

| Feature | Benefit |
|---|---|
| `ReflectiveClassBuildItem` | Auto-registers all provider model classes for GraalVM reflection at build time |
| Native image configs | Handles resource bundles, serialization, proxy configs for each provider |
| Dev UI | Visual model introspection in dev mode |
| Single BOM | One version property instead of managing plain + beta langchain4j versions |

### The Key Tension: DB-Stored Config vs Static Config

**EDDI stores LLM configs in the database** per-agent. Users configure providers and API keys dynamically at runtime through REST API / Manager UI / MCP tools.

**quarkus-langchain4j expects config in `application.properties`** — its default model is: one app = one static set of LLM configs.

### The Solution: `enable-integration=false`

Setting `quarkus.langchain4j.<provider>.enable-integration=false` for all providers:
1. **Bypasses** the `api-key is required` build-time validation
2. **Disables** default CDI bean creation (beans exist but throw `ModelDisabledException`)
3. **Retains** build-time reflection registration (runs regardless, triggered by JAR on classpath)
4. **EDDI's custom `ILanguageModelBuilder` pattern stays fully in control**

Config to add to `application.properties`:
```properties
# Native Image: disable quarkus-langchain4j managed beans — EDDI uses its own ILanguageModelBuilder pattern
# The quarkiverse extensions provide build-time native reflection registration only
quarkus.langchain4j.openai.enable-integration=false
quarkus.langchain4j.anthropic.enable-integration=false
quarkus.langchain4j.ollama.enable-integration=false
quarkus.langchain4j.google-ai-gemini.enable-integration=false
quarkus.langchain4j.vertex-ai-gemini.enable-integration=false
quarkus.langchain4j.hugging-face.enable-integration=false
quarkus.langchain4j.jlama.enable-integration=false
```

### What Does NOT Change

- `ILanguageModelBuilder` interface and all 7 builder implementations — unchanged
- `ChatModelRegistry` model caching — unchanged
- `ObservableChatModel` timeout/logging decorator — unchanged
- `AgentOrchestrator` tool-calling loop — unchanged
- `LlmConfiguration` DB-stored per-agent config model — unchanged
- All business logic — unchanged

---

## POM Dependency Changes

### Remove (7 direct langchain4j deps)

```xml
<!-- REMOVE these — replaced by quarkiverse extensions which include them transitively -->
<dependency>groupId: dev.langchain4j / langchain4j-open-ai / ${langchain4j-libs.version}</dependency>
<dependency>groupId: dev.langchain4j / langchain4j-anthropic / ${langchain4j-libs.version}</dependency>
<dependency>groupId: dev.langchain4j / langchain4j-ollama / ${langchain4j-libs.version}</dependency>
<dependency>groupId: dev.langchain4j / langchain4j-google-ai-gemini / ${langchain4j-libs.version}</dependency>
<dependency>groupId: dev.langchain4j / langchain4j-vertex-ai-gemini / ${langchain4j-beta.version}</dependency>
<dependency>groupId: dev.langchain4j / langchain4j-hugging-face / ${langchain4j-beta.version}</dependency>
<dependency>groupId: dev.langchain4j / langchain4j-jlama / ${langchain4j-beta.version}</dependency>
```

### Add (quarkiverse BOM + provider extensions)

```xml
<!-- BOM for version management -->
<dependency>
    <groupId>io.quarkiverse.langchain4j</groupId>
    <artifactId>quarkus-langchain4j-bom</artifactId>
    <version>${quarkus-langchain4j.version}</version> <!-- 1.8.2 as of Mar 2026 -->
    <type>pom</type>
    <scope>import</scope>
</dependency>

<!-- Provider extensions (each transitively includes dev.langchain4j provider jar) -->
<dependency>groupId: io.quarkiverse.langchain4j / quarkus-langchain4j-openai</dependency>
<dependency>groupId: io.quarkiverse.langchain4j / quarkus-langchain4j-anthropic</dependency>
<dependency>groupId: io.quarkiverse.langchain4j / quarkus-langchain4j-ollama</dependency>
<dependency>groupId: io.quarkiverse.langchain4j / quarkus-langchain4j-google-ai-gemini</dependency>
<dependency>groupId: io.quarkiverse.langchain4j / quarkus-langchain4j-vertex-ai-gemini</dependency>
<dependency>groupId: io.quarkiverse.langchain4j / quarkus-langchain4j-hugging-face</dependency>
<dependency>groupId: io.quarkiverse.langchain4j / quarkus-langchain4j-jlama</dependency>
```

### Keep (these are NOT covered by quarkus-langchain4j)

```xml
<dependency>groupId: dev.langchain4j / langchain4j / ${langchain4j.version}</dependency>           <!-- core API -->
<dependency>groupId: dev.langchain4j / langchain4j-http-client-jdk / ${langchain4j-libs.version}</dependency>  <!-- JDK HTTP client -->
<dependency>groupId: dev.langchain4j / langchain4j-mcp / ${langchain4j-beta.version}</dependency>  <!-- MCP client -->
<dependency>groupId: dev.langchain4j / langchain4j-agentic-a2a / ${langchain4j-beta.version}</dependency> <!-- A2A client -->
```

> **Note:** Verify that the quarkiverse BOM's transitive langchain4j version aligns with the `langchain4j.version` property (currently 1.12.2). If there's a mismatch, use `<dependencyManagement>` to enforce the core version.

---

## EDDI Model Registration — `@RegisterForReflection`

EDDI has **29 `model/` packages** with POJOs serialized by Jackson. None have `@RegisterForReflection`. These ALL need it for native image.

### Packages Requiring Annotation

| Package | Key Classes |
|---|---|
| `configs.agents.model` | `AgentConfiguration` |
| `configs.apicalls.model` | `HttpCallsConfiguration`, `Request`, `Response` |
| `configs.deployment.model` | `DeploymentInfo` |
| `configs.descriptors.model` | `DocumentDescriptor` |
| `configs.dictionary.model` | `DictionaryConfiguration` |
| `configs.groups.model` | `AgentGroupConfiguration`, `GroupConversation`, `DiscussionPhase` |
| `configs.migration.model` | Migration DTOs |
| `configs.output.model` | `OutputConfiguration`, `OutputConfigurationSet` |
| `configs.parser.model` | `ParserConfiguration` |
| `configs.properties.model` | `Property` |
| `configs.propertysetter.model` | `PropertySetterConfiguration` |
| `configs.rules.model` | `RuleSetConfiguration` |
| `configs.workflows.model` | `WorkflowConfiguration` |
| `datastore.model` | `ResourceFilter` |
| `engine.audit.model` | `AuditEntry` |
| `engine.memory.descriptor.model` | `ConversationDescriptor` |
| `engine.memory.model` | `ConversationMemorySnapshot`, `ConversationOutput` |
| `engine.model` | `ConversationState`, `Context`, `Deployment` |
| `engine.runtime.model` | Various DTOs |
| `engine.schedule.model` | `ScheduleConfiguration` |
| `engine.tenancy.model` | Tenant DTOs |
| `engine.triggermanagement.model` | `AgentTriggerConfiguration` |
| `modules.llm.model` | `LlmConfiguration` |
| `modules.nlp.model` | NLP model classes |
| `modules.output.model` | `OutputItem` (**uses `@JsonTypeInfo` + `@JsonSubTypes`** — all subtypes must be registered) |
| `modules.properties.model` | Property model classes |
| `secrets.model` | `SecretEntry` |
| `backup.model` | Export/import DTOs |
| `configs.admin.model` | `OrphanInfo`, `OrphanReport` |

### Jackson Polymorphic Types — Special Attention

`OutputItem.java` uses `@JsonTypeInfo` + `@JsonSubTypes` for polymorphic deserialization. All listed subtypes **must** also be registered for reflection, or deserialization fails at runtime.

---

## Third-Party Libraries — Remaining Native Work

These deps are NOT covered by quarkus-langchain4j and need manual reflection configuration:

| Dependency | Used In | Usage | Action |
|---|---|---|---|
| **swagger-parser** 2.1.39 | `McpApiToolBuilder.java` | OpenAPI spec parsing for `create_api_agent` | Run tracing agent; create `reflect-config.json` for swagger model classes |
| **jsonschema-generator** 4.38.0 | `JsonSchemaCreator.java` + 8 REST stores | Runtime JSON Schema generation via reflection | Consider pre-generating at build time, or add reflect-config |
| **json-path** 2.10.0 | `ContextMatcher.java` (1 file, 1 callsite) | JsonPath expression evaluation in behavior rules | Small reflect-config for `JsonProvider` |
| **bson4jackson** 2.15.1 | `IdSerializer.java`, `IdDeserializer.java`, `PersistenceModule.java` | MongoDB ObjectId ↔ Jackson BSON codec | Small reflect-config for BSON types |
| **pdfbox** 2.0.30 | `PdfReaderTool.java` (1 file) | PDF text extraction for LLM built-in tool | Uses `ServiceLoader` for fonts; consider: upgrade to 3.x, gate behind feature flag, or add native config |
| **quarkus-mongodb-client** | Core stores | ✅ Quarkus extension handles native | No action needed |
| **quarkus-jdbc-postgresql** | PG stores | ✅ Quarkus extension handles native | No action needed |
| **jnats** 2.25.2 | NATS coordinator | 🟢 Pure Java TCP client | Likely works; verify with tracing agent |
| **quarkus-mcp-server-http** 1.11.0 | MCP server | ✅ Quarkus extension handles native | No action needed |

---

## Execution Plan

### Phase 1: Dependency Swap (~0.5 day)
1. Add `quarkus-langchain4j-bom` to `<dependencyManagement>`
2. Replace 7 direct `dev.langchain4j` provider deps with 7 `io.quarkiverse.langchain4j` extension deps
3. Add `enable-integration=false` for all 7 providers in `application.properties`
4. Verify `mvnw compile` succeeds — no import changes should be needed (same langchain4j classes)
5. Run existing test suite — should pass unchanged

### Phase 2: Reflection Registration (~1 day)
6. Add `@RegisterForReflection` to all model classes in 29 packages
7. Special handling for `OutputItem` polymorphic type hierarchy
8. Verify JVM-mode tests still pass

### Phase 3: First Native Build (~1-2 days)
9. Run `./mvnw package -Dnative` — collect errors
10. Use GraalVM tracing agent to generate `reflect-config.json` for third-party libs:
    ```bash
    ./mvnw package -Dquarkus.package.jar.type=uber-jar
    java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image \
         -jar target/eddi-6.0.0-runner.jar
    # Exercise all API paths, then Ctrl+C
    ```
11. Iterate: fix build errors, add missing configs

### Phase 4: Third-Party Lib Configs (~1-2 days)
12. swagger-parser reflect-config (hit `create_api_agent` via tracing agent)
13. jsonschema-generator reflect-config (hit `/jsonSchema` endpoints via tracing agent)
14. json-path reflect-config (exercise behavior rules via tracing agent)
15. bson4jackson reflect-config (any MongoDB operation exercises this)
16. pdfbox decision: gate behind feature flag OR add native config

### Phase 5: Validation + CI (~1 day)
17. Run full native binary against MongoDB + test suite
18. Test all 7 LLM providers in native mode (at least verify startup + config parsing)
19. Add native build to CI pipeline (note: native builds take ~5-10 min)
20. Optionally add `@NativeImageTest` integration tests

---

## Build Commands Reference

```bash
# Standard native build
./mvnw package -Dnative

# Native build in Docker container (for Linux binary on any OS)
./mvnw package -Dnative -Dquarkus.native.container-build=true

# With tracing agent (generates reflect-config.json)
./mvnw package -Dquarkus.package.jar.type=uber-jar
java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image \
     -jar target/eddi-6.0.0-runner.jar

# Docker image from native binary
docker build -f src/main/docker/Dockerfile.native -t labsai/eddi:6-native .
docker build -f src/main/docker/Dockerfile.native-micro -t labsai/eddi:6-native-micro .
```

---

## Key Files Reference

### EDDI Model Builder Architecture (unchanged by this migration)
- `src/main/java/ai/labs/eddi/modules/llm/impl/builder/ILanguageModelBuilder.java` — builder interface
- `src/main/java/ai/labs/eddi/modules/llm/impl/builder/OpenAILanguageModelBuilder.java` — one of 7 builders
- `src/main/java/ai/labs/eddi/modules/llm/impl/ChatModelRegistry.java` — model caching (~147 lines)
- `src/main/java/ai/labs/eddi/modules/llm/impl/ObservableChatModel.java` — timeout/logging decorator
- `src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java` — DB-stored per-agent config

### Build Infrastructure (already exists)
- `pom.xml` — native profile at lines 475-488
- `src/main/docker/Dockerfile.native` — UBI9 minimal base
- `src/main/docker/Dockerfile.native-micro` — Quarkus micro base

### Third-Party Reflection Hotspots
- `src/main/java/ai/labs/eddi/engine/mcp/McpApiToolBuilder.java` — swagger-parser
- `src/main/java/ai/labs/eddi/configs/schema/JsonSchemaCreator.java` — jsonschema-generator
- `src/main/java/ai/labs/eddi/modules/rules/impl/conditions/ContextMatcher.java` — json-path
- `src/main/java/ai/labs/eddi/datastore/serialization/IdSerializer.java` — bson4jackson
- `src/main/java/ai/labs/eddi/datastore/serialization/IdDeserializer.java` — bson4jackson
- `src/main/java/ai/labs/eddi/datastore/bootstrap/PersistenceModule.java` — bson4jackson
- `src/main/java/ai/labs/eddi/modules/llm/tools/impl/PdfReaderTool.java` — pdfbox
