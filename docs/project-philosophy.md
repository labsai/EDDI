# EDDI Project Philosophy

> **The Overarching Directive for All Development in EDDI**
>
> This document defines the foundational principles that govern every architectural decision, feature implementation, and design trade-off across the EDDI ecosystem. Every contributor — human or AI — must internalize these principles before writing code.

---

## Identity Statement

**EDDI is the "Grown-Up" Enterprise AI Orchestrator.**

While competitors (n8n, LangGraph, CrewAI, Flowise) were built for rapid prototyping and are now frantically reverse-engineering enterprise qualities into architectures that resist them, EDDI approaches from the opposite direction: **a deterministic engine built to safely govern non-deterministic AI.**

EDDI's competitive moat is structural, not feature-based. It emerges from the combination of:

- **Java 21 / Quarkus** — true concurrency without GIL or single-threaded event loops
- **Configuration-driven logic** — agent behavior is JSON, not compiled code
- **Strict pipeline architecture** — deterministic execution of probabilistic components
- **Enterprise security by default** — no eval(), no sandbox escapes, no plaintext secrets

---

## The Seven Pillars

### Pillar 1: Configuration Is Logic, Java Is the Engine

> _"Agent behavior belongs in JSON configurations. Java code builds the components that read and execute those configurations."_

**The Principle:** EDDI is a **config-driven engine**, not a monolithic application. The intelligence of a agent — its routing rules, API calls, LLM prompts, output templates — is defined entirely in versioned JSON documents. Java code provides the **infrastructure components** (`ILifecycleTask`, `IResourceStore`, tools) that the engine uses to interpret and execute those configurations at runtime. No agent-specific logic may ever be hardcoded in Java.

**Why This Matters:** Competitors that embed logic in code (Python scripts, JavaScript eval blocks) suffer from:

- **Deployment friction** — every logic change requires recompilation and redeployment
- **Security vulnerabilities** — dynamic code execution is the #1 source of CVSS 10.0 RCE exploits (n8n CVE-2025-68613, Flowise sandbox escapes)
- **Operational opacity** — code-embedded logic can't be versioned, diffed, or rolled back independently

**Anti-patterns to avoid:**

- Adding `if/else` branches in Java to handle specific agent use cases
- Introducing dynamic scripting engines (GraalJS, Nashorn) for "flexibility"
- Hardcoding model names, API endpoints, or prompt templates in Java source

**EDDI's Answer to "I need custom code":** _"Spin up an external MCP server in an isolated container. EDDI will call it as a tool."_ This pushes execution risk outside the EDDI perimeter while providing infinite extensibility.

---

### Pillar 2: Deterministic Governance of Non-Deterministic AI

> _"The engine is strict so the AI can be creative."_

**The Principle:** LLMs are inherently probabilistic — they hallucinate, loop infinitely, and burn through token budgets unpredictably. EDDI's role is to provide **deterministic guardrails** around this non-determinism: circuit breakers, budget caps, execution hashes, HITL pause points, and immutable audit trails.

**Why This Matters:** Competitors built "AI-first" without governance layers and now face:

- **Infinite loops** — CrewAI agents burning $100 in 10 minutes repeating the same hallucinated tool call
- **State corruption** — AutoGen's parallel agents silently overwriting each other's shared memory
- **API contract violations** — Semantic Kernel injecting fake "user" messages that crash provider APIs

**Concrete Mandates:**

1. **Execution Hash Circuit Breaker** — if an agent calls the same tool with identical arguments N times, halt the DAG branch
2. **Budget-Aware Execution** — every tool call flows through `ToolRateLimiter` → `ToolCacheService` → `ToolCostTracker`
3. **Out-of-Band Error Handling** — never inject framework exceptions into the LLM conversation history; map failures to action strings in the pipeline
4. **Pessimistic Reducers for Parallelism** — when parallel agents complete, a deterministic Java reducer merges outputs atomically; no concurrent writes to `IConversationMemory`

---

### Pillar 3: The Engine, Not the Application

> _"EDDI provides the components. The admin configures the intelligence."_

**The Principle:** EDDI is middleware — it sits between the user-facing channels and the AI providers. It does not contain business logic; it contains the **machinery** to execute business logic defined as configuration. Adding a new capability means adding a new `ILifecycleTask` component, not modifying existing ones.

**Why This Matters:** This architecture enables:

- **Multi-tenancy** — the same engine runs completely different agents for different customers
- **Instant iteration** — changing agent behavior requires editing a JSON document, not a Java rebuild
- **Clean extensibility** — new task types are discovered via CDI, no registration code needed

**The Component Lifecycle:**

```
New Feature Idea -> Configuration POJO -> IResourceStore -> REST API
                                       -> ILifecycleTask.execute()
                                       -> ExtensionDescriptor (for Manager UI)
                                       -> Unit Tests
```

---

### Pillar 4: Security as Architecture, Not Afterthought

> _"If a security measure can be bypassed by changing a configuration, it is not a security measure."_

**The Principle:** Security is enforced at the **architectural level**: the type system, the classpath, the network topology. It is never delegated to the LLM, the prompt, or the admin's good judgment.

**Concrete Mandates:**

| Domain                    | Mandate                                                                                                            |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| **Expression Evaluation** | `SafeMathParser` (recursive descent) only. Never `ScriptEngine`, never `eval()`                                    |
| **URL Validation**        | All tools MUST call `UrlValidationUtils.validateUrl()` — blocks private IPs, internal hostnames, non-HTTP schemes  |
| **Secret Storage**        | No plaintext API keys in MongoDB. Use Vault references (`${vault:key}`) resolved at runtime                        |
| **Deserialization**       | No `@JsonTypeInfo(use=Id.CLASS)`, no `enableDefaultTyping()` for untrusted payloads                                |
| **Memory Safety**         | `PathNavigator` replaces OGNL — no `.getClass()`, no reflection, no runtime class instantiation                    |
| **MCP**                   | Opt-in per agent. External MCP tools flagged as READ_ONLY or STATE_CHANGING. STATE_CHANGING requires HITL approval |
| **Tenant Isolation**      | Database queries enforce `tenantId` via Java code / SQL WHERE / RLS — never via LLM prompt filtering               |
| **Export Sanitization**   | Agent ZIP exports scrub API keys, tokens, and high-entropy strings before packaging                                |

**Anti-patterns to avoid:**

- Letting admins execute arbitrary code through the UI
- Trusting LLM output to filter data access ("only search Tenant A's documents")
- Storing unbounded session maps without TTL (the OpenClaw OOM vulnerability)

---

### Pillar 5: Enterprise-Grade Concurrency

> _"Java's concurrency model is our unfair structural advantage."_

**The Principle:** Python's GIL and Node.js's single-threaded event loop are fundamental barriers to scaling multi-agent AI workloads. EDDI leverages Java 21 Virtual Threads and Quarkus's reactive stack to achieve true parallelism without blocking, without heartbeat starvation, and without the serialization panics that plague competitors.

**Concrete Architecture:**

| Tier                  | Purpose                                   | Technology                         | Lifecycle                  |
| --------------------- | ----------------------------------------- | ---------------------------------- | -------------------------- |
| **Execution Token**   | Routing identifiers on the message broker | NATS JetStream payload (kilobytes) | Milliseconds               |
| **Transient Context** | Active connections, processing buffers    | Virtual Thread local memory        | Flushed on step completion |
| **Telemetry Ledger**  | Full context snapshot + audit trail       | Write-once append to audit store   | Permanent                  |

**Mandates:**

1. `ILifecycleTask` implementations are **stateless singletons** — all conversational state lives in `IConversationMemory`
2. Virtual Threads handle all NATS consumer endpoints — the messaging heartbeat thread is never blocked
3. No raw infrastructure objects (DB connections, HTTP clients) in `IConversationMemory` — use `@RequestScoped` CDI for transient resources
4. No unbounded `ConcurrentHashMap` — use Caffeine Cache or Redis with strict TTL

---

### Pillar 6: Transparent Observability

> _"If you can't see why the AI made a decision, you can't fix it, audit it, or trust it."_

**The Principle:** Every conversation turn must produce a **complete, immutable trace** of exactly what happened: the compiled prompt (with all template variables resolved), the RAG context ingested, the LLM reasoning tokens, the tool calls, the memory state, and the cost. This is not debugging infrastructure — it is the product.

**Why This Matters:**

- The EU AI Act requires immutable audit logs of _why_ an AI made a decision
- Developers suffer from "reasoning blindness" — standard logs show _what_ tool was called, not _why_ it was chosen
- EDDI already captures `ConversationMemorySnapshot` data and has undo/redo endpoints — this is a foundation to build on

**Vision:** The Manager UI debugger queries the telemetry ledger (not the operational memory) to provide a "Time-Traveling IDE" experience: step-through replay, exact compiled prompts, memory state at every millisecond, with pause/edit/resume controls for human-in-the-loop debugging.

---

### Pillar 7: Progressive Disclosure in UX

> _"Easy things should be easy. Hard things should be possible."_

**The Principle:** The Manager UI must serve two audiences simultaneously:

- **Business users** who want to configure a agent via visual forms, drag-and-drop pipelines, and guided wizards
- **Power users** who want raw JSON editing with Monaco, schema validation, and autocomplete

Agenth views are **identical state representations**. Editing the form updates the JSON; editing the JSON updates the form. Neither view is "advanced" — they are complementary perspectives on the same configuration.

**UX Mandates:**

1. **No spaghetti node graphs** — use a Linear/Block Hybrid with vertical stacks inside containers and wires only for macro-routing between containers
2. **No modals** — use side-sheet inspectors so the main pipeline view remains visible
3. **Visual taint tracking** — trusted data (system properties) gets a green shield; untrusted data (user input, external MCP) gets a yellow warning
4. **Actionable telemetry** — surface "True Resolution Rate" and LLM cost per agent on the dashboard, not vanity metrics like "Deflection Rate"

---

## Strategic Positioning

EDDI occupies a unique vacuum in the market:

```
                    +-------------------------------------+
                    |        Visual + Config-Driven        |
                    |                                     |
         n8n       |            EDDI v6.0                 |   Flowise
        Langflow   |         (Only JVM entry)             |   Agentpress
                    |                                     |
                    +-------------------------------------+
                    |        Library / Framework           |
                    |                                     |
      LangChain4j  |                                     |   Spring AI
      Semantic     |        (DIY Orchestration)          |   Quarkus
       Kernel      |                                     |    LangChain4j
                    +-------------------------------------+
                         Python/Node                  Java/JVM
```

**Three strategic pitches:**

1. **Escape the Prototype Trap** — transition fragile Python/Node prototypes to robust JVM production
2. **Agility Through Configuration** — update AI logic in seconds without recompilation
3. **Cloud-Native Scale** — Quarkus + Virtual Threads + GraalVM Native Image = minimal footprint, maximum throughput

---

## Document Governance

This document is the **supreme directive** for EDDI development. When a technical decision conflicts with these principles, the principles win. When a new feature doesn't fit within these pillars, either the feature must be redesigned or a new pillar must be proposed and approved.

**Versioning:** This document evolves with the project. Changes require explicit approval from project leadership and must be documented in the changelog.
