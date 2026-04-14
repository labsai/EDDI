# EDDI v6.0.0 — Agentic Improvements Plan

> **Scope**: Multi-agent orchestration, A2A evolution, cryptographic agent identity, multimodal context attachments, and behavioral governance.
>
> **Governing Principles**: All changes **must** conform to the [Nine Pillars](../project-philosophy.md). Java is the engine, configuration is logic, security is architecture.

> [!IMPORTANT]
> **Implementation Status (2026-04-07):**
> - **Improvements 1–5**: ✅ Implemented on `feature/agentic-improvements` branch — `CounterweightService`, `DeploymentContextService`, `IdentityMaskingService`, `ToolResponseTruncator`, `CapabilityRegistryService`, `CapabilityMatchCondition`, `ContentTypeMatcher`, `Attachment`, `AgentSigningService` all exist and are tested
> - **Bug fixes applied**: Condition creation factory-first ordering, `CapabilityRegistryService` startup wiring, counterweight opt-in enforcement
> - **Improvement 6 (Session Forking & State Snapshotting)**: ❌ Not yet implemented
> - See `docs/changelog.md` entries: "Agentic Improvements — Phases 1–5" and "Critical Bug Fixes" (2026-04-07)

---

## 1. Problem Statement

EDDI v6 has already achieved significant milestones: A2A protocol support, group conversations, MCP server/client integration, and multi-model cascading. However, the current agentic architecture is fundamentally **manager-driven** — a human admin explicitly wires agents together via behavior rules and group configurations.

The next evolution moves EDDI from a **configured pipeline** to a **governed agent ecosystem** where agents can:

1. **Discover** each other's capabilities at runtime, not just at configuration time
2. **Prove** their identity cryptographically in multi-agent interactions
3. **Carry** multimodal context (images, audio, documents) through the lifecycle pipeline without losing fidelity
4. **Self-regulate** behavior intensity based on deployment context and risk level
5. **Negotiate** task delegation within policy boundaries, not just follow fixed routes

These improvements do **not** replace the deterministic governance model — they extend it. The engine remains strict so the AI can be creative.

---

## 2. Improvement 1: A2A Capability Registry & Soft Routing

### 2.1 Current State

The A2A protocol foundation is **already implemented**:

| Component | Status | Location |
|---|---|---|
| Agent Cards (`.well-known/agent.json`) | ✅ Implemented | `RestA2AEndpoint.java` |
| Agent Card Service | ✅ Implemented | `AgentCardService.java` |
| A2A Data Models | ✅ Implemented | `A2AModels.java` |
| A2A Tool Provider for LLM | ✅ Implemented | `A2AToolProviderManager.java` |
| Group Conversations (debate orchestration) | ✅ Implemented | `GroupConversationService.java` |

**What's missing**: Agents can talk to each other, but they can't *find* each other dynamically. Group composition is entirely static — the admin pre-configures which agents participate.

### 2.2 Proposed Changes

#### 2.2.1 Capability Registry

Introduce a `CapabilityRegistryService` that allows agents to advertise structured skills:

```json
// Part of AgentConfiguration — new field
"capabilities": [
  {
    "skill": "language-translation",
    "languages": ["en", "de", "fr", "es"],
    "confidence": "high"
  },
  {
    "skill": "financial-analysis",
    "domains": ["IFRS", "GAAP"],
    "confidence": "medium"
  }
]
```

**Architectural mapping:**
- The `capabilities` field is a new JSON config extension on `AgentConfiguration` — pure config, no Java logic (Pillar 1)
- `CapabilityRegistryService` is a new `@ApplicationScoped` bean that indexes capabilities at agent deployment and exposes a query API
- The registry is backed by the existing `AbstractMongoResourceStore<T>` — no new persistence layer

#### 2.2.2 Soft Routing via Behavior Rules

Extend `BehaviorRulesEvaluationTask` with a new condition type: `capabilityMatch`.

```json
// behavior.json — new condition type
{
  "name": "delegate-to-best-translator",
  "actions": ["delegate_translation"],
  "conditions": [
    {
      "type": "capabilityMatch",
      "config": {
        "skill": "language-translation",
        "requiredLanguage": "{{properties.targetLanguage.valueString}}",
        "selectionStrategy": "highest_confidence"
      }
    }
  ]
}
```

**Key design decision**: Soft routing defines *boundaries* (which skills are acceptable, what selection strategy to use), not exact routes. The engine evaluates the capability registry at runtime and selects the best-match agent. This preserves determinism — the selection strategy is a deterministic algorithm, not an LLM guess.

**Selection strategies** (config-driven, not hardcoded):
- `highest_confidence` — pick the agent with the highest declared confidence
- `round_robin` — distribute across all matching agents
- `lowest_load` — pick the agent with the fewest active conversations (leverages existing `ConversationMetricsService`)

#### 2.2.3 Implementation Components

| Component | Type | Description |
|---|---|---|
| `capabilities` field | Config POJO | New field on `AgentConfiguration` (Java record) |
| `CapabilityRegistryService` | `@ApplicationScoped` bean | Indexes, queries, and resolves capability matches |
| `capabilityMatch` condition | Extension to `BehaviorRulesEvaluationTask` | New condition type for soft routing |
| REST endpoint | JAX-RS resource | `GET /v6/capabilities?skill=X` for external/MCP discovery |
| Micrometer metrics | Counters/timers | `capability.query.count`, `capability.match.latency` |

### 2.3 Effort & Risk

- **Effort**: Medium — the A2A foundation exists, this is additive
- **Risk**: Low — no changes to existing pipeline semantics, fully backwards-compatible
- **Priority**: 🟡 P2

---

## 3. Improvement 2: Cryptographic Agent Identity

### 3.1 Current State

- ✅ OAuth 2.0 / Keycloak for **human** authentication
- ✅ HMAC-secured audit ledger for forensic tracing
- ✅ SecretsVault with PBKDF2 + AES envelope encryption
- ✅ SSRF protections, URL validation, `SafeMathParser`
- ❌ **No cryptographic agent-to-agent identity** — agents trust each other implicitly within the platform boundary

### 3.2 Threat Model (Scoped for EDDI)

> [!IMPORTANT]
> EDDI is a **self-contained platform**, not a library consumed by third-party code. The primary threat is not network-level MITM, but **prompt injection → identity spoofing**: a compromised LLM response that claims to be "Agent-Finance" making decisions for "Agent-Compliance".

The cryptographic identity layer protects against:

1. **Identity spoofing via prompt injection** — attacker manipulates an agent's LLM to impersonate another agent
2. **Tampered inter-agent context** — messages between agents in a group conversation are modified in flight
3. **Audit repudiation** — ability to prove which agent *actually* generated a particular output

### 3.3 Proposed Architecture

#### 3.3.1 Agent Signing Keys

Add an Ed25519 signing keypair to `AgentConfiguration`:

```json
// AgentConfiguration — new field (auto-generated on creation)
"identity": {
  "agentDid": "did:eddi:agent:abc-123",
  "publicKey": "base64-encoded-ed25519-public-key"
  // privateKey stored in SecretsVault, never in config JSON
}
```

**Key lifecycle:**
1. On agent creation → `AgentFactory` auto-generates an Ed25519 keypair
2. Public key stored in `AgentConfiguration.identity` (versioned, auditable)
3. Private key stored via `SecretsVault` with scope `secret` (auto-vaulted, scrubbed from exports)
4. On agent deletion → keychain cleanup

#### 3.3.2 Signed Inter-Agent Messages

In `GroupConversationService.discuss()`, before delivering an agent's response to peers:

```java
// Pseudocode — inside GroupConversationService
String signature = Ed25519Signer.sign(agentPrivateKey, messagePayload);
groupContext.put("senderSignature", signature);
groupContext.put("senderDid", agentDid);
```

Receiving agents verify the signature before trusting the context:

```java
boolean verified = Ed25519Verifier.verify(senderPublicKey, messagePayload, signature);
if (!verified) {
    auditLedger.logSecurityEvent("SIGNATURE_VERIFICATION_FAILED", senderDid, conversationId);
    // Route to error action — out-of-band error handling (Pillar 2)
}
```

#### 3.3.3 Signed MCP Invocations

Extend `ToolExecutionService.executeToolWrapped()` to carry the calling agent's signature:

```
Tool Call → Rate Limiter → Cache Check → Sign(agentKey) → Execute → Cost Tracker → Verify Response → Result
```

This ensures that when EDDI acts as an MCP server and an external system sends a `talk_to_agent` call, the audit ledger records the cryptographic identity of the initiating agent — not just its `agentId`.

#### 3.3.4 Trust Scoring (Future Phase)

A dynamic trust metric based on agent behavior history:

- Leverages existing Micrometer `MeterRegistry` infrastructure
- Tracks: successful tool invocations, error rates, budget adherence, HITL approval outcomes
- Exposed as `agentTrustScore` in the capability registry and Agent Cards
- The capability registry's `capabilityMatch` can factor trust score into selection strategy

### 3.4 Configuration-Driven Opt-In

Per Pillar 1, cryptographic identity is **enabled via configuration**, not hardcoded:

```json
// AgentConfiguration
"security": {
  "signInterAgentMessages": true,
  "signMcpInvocations": true,
  "requirePeerVerification": false  // strict mode — reject unverified messages
}
```

Defaults are `false` for backwards compatibility. Admins can enable signing for high-risk deployments (financial services, healthcare) without affecting simple chatbot agents.

### 3.5 Effort & Risk

- **Effort**: High — net-new cryptographic infrastructure
- **Risk**: Medium — new crypto surface area requires careful implementation and testing
- **Priority**: 🟡 P2 (critical for regulated industries)

---

## 4. Improvement 3: Multimodal Context Attachments

### 4.1 Current State

- ✅ Multimodal LLM support via LangChain4j (Gemini vision, GPT-4o multimodal, etc.)
- ✅ Text-based input/output pipeline carries multimodal LLM responses as text
- ❌ No native binary routing in behavior rules
- ❌ No native image/audio/document storage in conversation memory
- ❌ No streaming binary data through the lifecycle pipeline

### 4.2 Design Philosophy

> [!IMPORTANT]
> **EDDI remains text-centric orchestration middleware.** The goal is not to become a multimodal processing engine — it's to allow multimodal *context* to flow through the pipeline so LLMs and external tools can process it. EDDI routes based on **metadata** (MIME type, size, classification results), not on raw binary content.

This approach aligns with Pillar 3 (The Engine, Not the Application): heavy processing (image classification, transcription, OCR) happens via MCP tools or httpCalls. EDDI orchestrates the routing.

### 4.3 Proposed Architecture

#### 4.3.1 Conversation Memory Attachments

Add an `attachments` map to `ConversationStep`:

```java
// Extension to ConversationStep
public class Attachment {
    private String id;           // UUID
    private String mimeType;     // "image/png", "audio/wav", "application/pdf"
    private String storageRef;   // GridFS reference or S3 URI — not inline base64
    private long sizeBytes;
    private Map<String, String> metadata; // e.g., {"width": "1024", "height": "768"}
}

// In ConversationStep:
private Map<String, Attachment> attachments; // key = logical name
```

**Critical design decisions:**
1. **No inline base64** — attachments are stored via GridFS (MongoDB) or external object storage. The `ConversationStep` carries a lightweight reference, not the binary payload. This prevents MongoDB document size limits from being hit.
2. **Metadata, not content** — behavior rules match on `mimeType`, `sizeBytes`, and `metadata` fields, never on raw bytes.
3. **TTL-governed** — attachments inherit the conversation's TTL. Expired conversations clean up their attachments automatically.

#### 4.3.2 LLM Multimodal Forwarding

In `LlmTask` (the refactored `LangchainTask`), detect attachments and include them in the LLM request:

```java
// Inside LlmTask.execute() — when building the ChatMessage
List<Attachment> currentAttachments = memory.getCurrentStep().getAttachments();
if (!currentAttachments.isEmpty()) {
    for (Attachment att : currentAttachments) {
        if (att.getMimeType().startsWith("image/")) {
            // LangChain4j already supports ImageContent
            chatMessage.addContent(ImageContent.from(loadFromStorage(att.getStorageRef())));
        }
        // Audio, document, etc. — provider-dependent
    }
}
```

This is transparent to the agent configuration — if the LLM supports multimodal input, EDDI forwards attachments automatically. No new JSON config needed (progressive disclosure — Pillar 7).

#### 4.3.3 Behavior Rule Content Type Matcher

New condition type for behavior rules:

```json
{
  "type": "contentTypeMatcher",
  "config": {
    "mimeType": "image/*",
    "action": "process_image"
  }
}
```

This enables routing like:
- User sends an image → behavior rule matches `image/*` → triggers `process_image` action → httpCall sends image to a classification API → result drives the next behavior rule

#### 4.3.4 REST API Extension

```
POST /v6/conversations/{id}/messages
Content-Type: multipart/form-data

  input: "What's in this image?"
  attachment_0: <binary file>
```

The REST layer:
1. Stores the binary in GridFS/object storage
2. Creates an `Attachment` reference on the new `ConversationStep`
3. Forwards the conversation to the lifecycle pipeline as normal

SSE events include attachment metadata (not binary) so the chat UI can render thumbnails.

### 4.4 Effort & Risk

- **Effort**: Medium for the pragmatic approach (references + metadata + LLM forwarding)
- **Risk**: Low — additive, no changes to text-based pipeline semantics
- **Priority**: 🟠 P3

---

## 5. Improvement 4: Assertiveness Counterweights & Deployment Governance

### 5.1 Motivation

Production-grade agentic systems (including lessons from Claude Code's internal architecture) have demonstrated that advanced LLM models can exhibit:
- **Overconfident refactoring** — aggressively rewriting logic without verification
- **False claims** — stating facts with high confidence when uncertain
- **Identity leakage** — revealing internal system details to end users

EDDI's BehaviorRules module is uniquely positioned to implement **dynamic assertiveness governance** as configuration, not code.

### 5.2 Proposed Changes

#### 5.2.1 Assertiveness Counterweight (Config-Driven)

A new optional field in `LangchainConfiguration`:

```json
// langchain.json — per-task assertiveness control
{
  "systemPromptSuffix": {
    "counterweight": {
      "enabled": true,
      "level": "cautious",  // "normal", "cautious", "strict"
      "instructions": [
        "State your planned actions clearly before executing them.",
        "Err heavily on the side of caution for state-changing operations.",
        "If uncertain, ask for clarification rather than guessing."
      ]
    }
  }
}
```

**How it works:**
1. When `LlmTask` builds the system prompt, it checks for `counterweight` configuration
2. If enabled, the counterweight instructions are **appended** to the compiled system prompt
3. The counterweight is invisible to the end user — it's an **engine-level safety injection** (Pillar 2)
4. The telemetry UI flags conversations operating under counterweight rules with a visual indicator

**Predefined levels:**
- `normal` — no modification (default)
- `cautious` — adds intent-declaration requirements and verification prompts
- `strict` — adds explicit prohibitions on sweeping changes and requires step-by-step confirmation

#### 5.2.2 Deployment Context Rules

A new condition type in behavior rules that checks deployment/environment context:

```json
{
  "type": "deploymentContext",
  "config": {
    "when": "production",
    "autoApply": {
      "counterweightLevel": "cautious",
      "requireHitlForStateChanging": true
    }
  }
}
```

**Sources for deployment context:**
- Environment variables (`EDDI_DEPLOYMENT_ENV=production`)
- Agent configuration tags (`"tags": ["high-risk", "financial"]`)
- Connected MCP server classifications (`READ_ONLY` vs `STATE_CHANGING`)

#### 5.2.3 Identity Masking Mode

For agents interacting with public-facing channels:

```json
// AgentConfiguration
"identityMasking": {
  "enabled": true,
  "rules": [
    "Never identify yourself as an AI or automated system.",
    "Do not mention internal model names, versions, or infrastructure.",
    "Do not include automated attribution in any output."
  ]
}
```

When enabled, the masking rules are injected into the system prompt, and the audit ledger records that identity masking was active for the conversation.

### 5.3 Effort & Risk

- **Effort**: Low — this is primarily prompt engineering wrapped in configuration infrastructure
- **Risk**: Low — additive, fully opt-in, no changes to pipeline semantics
- **Priority**: 🟡 P2 (increasingly critical as agents interact with production systems)

---

## 6. Improvement 5: MCP Governance & Token-Efficient Tool Loading

### 6.1 Current State

EDDI already has robust MCP integration:
- ✅ MCP Server (33 tools) — external systems can manage EDDI agents
- ✅ MCP Client — agents can discover and invoke external tools
- ✅ `ToolExecutionService.executeToolWrapped()` — rate limiting, caching, cost tracking
- ✅ Tenant quota enforcement — `QuotaExceededException` with HTTP 429

### 6.2 Proposed Improvements

#### 6.2.1 Token-Efficient Tool Loading

**Problem**: Loading all available MCP tool definitions into the agent's context window causes massive token consumption and latency.

**Solution**: Lazy tool loading with capability matching:

```json
// langchain.json — tool loading strategy
{
  "toolLoadingStrategy": {
    "type": "lazy",           // "eager" (current default), "lazy", "dynamic"
    "maxToolsInContext": 10,  // Hard cap on simultaneous tool definitions
    "discoveryTool": true     // Provides a meta-tool: "list_available_tools(category)"
  }
}
```

**How `lazy` works:**
1. Instead of loading all tool schemas into the initial prompt, EDDI provides a single `discover_tools` meta-tool
2. The LLM calls `discover_tools(category="financial")` to get relevant tool schemas
3. Only matched tools are injected into subsequent turns
4. This reduces standing context window size dramatically

#### 6.2.2 Response Truncation

**Problem**: Unbounded MCP tool responses can blow out context limits.

**Solution**: Configurable response truncation:

```json
// langchain.json — response handling
{
  "toolResponseLimits": {
    "maxTokens": 25000,        // Hard cap on tool response size
    "truncationStrategy": "summarize",  // "truncate", "summarize", "paginate"
    "paginationPageSize": 5000
  }
}
```

- `truncate` — cut at token limit with a `[TRUNCATED — use pagination to see more]` marker
- `summarize` — route oversize responses through a fast model (Gemini Flash) for compression
- `paginate` — split response into pages, provide a `next_page` tool

#### 6.2.3 MCP Budget Integration

Wire MCP tool execution into the tenant quota system:

```
MCP Tool Call → Rate Limiter → Quota Check → Cache Check → Execute → Cost Tracker → Result
                                    ↓ (if exceeded)
                              QuotaExceededException → HTTP 429
```

Each external MCP tool invocation is tracked against the tenant's API call budget. Configurable per-tool cost weights allow admins to express that "calling the database MCP tool costs 10x more than calling the search tool".

### 6.3 Effort & Risk

- **Effort**: Medium — extends existing infrastructure
- **Risk**: Low — additive, backwards-compatible (default strategy remains `eager`)
- **Priority**: 🟡 P2 (significant cost/performance impact)

---

## 7. Improvement 6: Session Forking & State Snapshotting

### 7.1 Current State

- ✅ `ConversationMemorySnapshot` data with undo/redo endpoints
- ✅ Centralized state in MongoDB (not instance-pinned)
- ✅ SSE streaming for real-time observation
- ✅ `ConversationService` decoupled from REST layer
- ✅ `READ_ONLY` vs `STATE_CHANGING` MCP tool classification
- ❌ **No automatic checkpointing** before destructive operations
- ❌ **No conversation forking** — no parallel exploration branches

### 7.2 Motivation

Research on advanced agentic architectures (including Claude Code internals) reveals two critical execution safety features that are **orchestration concerns**, not memory concerns:

1. **State snapshotting before destructive operations** — automatic checkpoint of conversation state before `STATE_CHANGING` tool calls, enabling rollback on failure
2. **Session forking** — creating parallel exploration branches so users or agents can test alternative strategies without corrupting the primary conversation

These capabilities directly support EDDI's Pillar 2 (Deterministic Governance) and Pillar 6 (Transparent Observability — "Time-Traveling IDE").

### 7.3 Proposed Changes

#### 7.3.1 Automatic State Snapshotting

Before the `McpToolProvider` or `HttpCallsTask` executes a `STATE_CHANGING` operation, EDDI automatically creates a lightweight checkpoint:

```java
// Pseudocode — inside ToolExecutionService or LifecycleManager
if (toolClassification == STATE_CHANGING) {
    String checkpointId = memorySnapshotService.createCheckpoint(conversationId);
    try {
        result = executeToolWrapped(tool, args);
    } catch (Exception e) {
        memorySnapshotService.rollbackToCheckpoint(conversationId, checkpointId);
        auditLedger.logRollback(conversationId, checkpointId, e);
        // Route to error action — pipeline continues with clean state
    }
}
```

**Key design decisions:**

1. **Lightweight snapshots** — only the current `ConversationStep` and `conversationProperties` are checkpointed, not the full conversation history. This keeps snapshot overhead minimal.
2. **Automatic, not manual** — snapshotting triggers on `STATE_CHANGING` tool classification, which is already configured per MCP tool. No admin intervention needed.
3. **Rollback is atomic** — uses `findOneAndUpdate` with the checkpoint version to prevent race conditions.

#### 7.3.2 Session Forking

Expose a new core endpoint for conversation forking:

```
POST /v6/conversations/{id}/fork
Response: { "forkedConversationId": "new-uuid", "parentId": "original-id" }
```

**Implementation:**

1. The endpoint instructs `IConversationMemoryStore` to create a deep copy of the existing memory document
2. The copy receives a new UUID and a `parentId` field pointing to the original conversation
3. The forked conversation has its own independent lifecycle — changes to the fork do not affect the original
4. The Manager UI can display forked conversations as a tree, enabling visual comparison

**Configuration:**

```json
// AgentConfiguration
"sessionManagement": {
  "autoSnapshot": {
    "enabled": true,
    "triggerOn": "STATE_CHANGING"  // snapshot before state-changing tools
  },
  "forkingEnabled": true,
  "maxForksPerConversation": 5  // prevent unbounded forking
}
```

#### 7.3.3 Omnichannel Session Continuity

Because EDDI centralizes state in MongoDB and streams events via SSE, session continuity across channels is largely "free." A user can:
1. Start a conversation via Slack adapter
2. Open the Manager UI, connect to the same conversation ID
3. Immediately see the full SSE stream of the agent's reasoning

No additional implementation is required for basic teleportation. The forking API enhances this by allowing channel-specific branches:
- Slack session forks a read-only branch for the Manager UI
- Manager UI session forks an experimental branch for prompt testing

### 7.4 Effort & Risk

- **Effort**: Medium — snapshot infrastructure is partially built (undo/redo exists); forking is a new persistence operation
- **Risk**: Low for snapshots (additive, fully opt-in). Medium for forking (new persistence patterns, potential storage growth)
- **Priority**: 🟡 P2 (valuable for regulated industries and multi-agent deployments)

---

## 8. Implementation Sequence

The improvements should be implemented in this order, maximizing incremental value:

```
Phase 1: Behavioral Governance (Low effort, immediate safety value)
  └── Assertiveness Counterweights
  └── Deployment Context Rules
  └── Identity Masking Mode

Phase 2: MCP Governance (Medium effort, immediate cost savings)
  └── Token-Efficient Tool Loading
  └── Response Truncation
  └── MCP Budget Integration

Phase 3: A2A Evolution (Medium effort, enables larger deployments)
  └── Capability Registry
  └── Soft Routing Conditions
  └── REST API for capability discovery

Phase 4: Session Safety (Medium effort, regulated industry value)
  └── Auto-snapshotting before STATE_CHANGING tools
  └── Session forking API
  └── Omnichannel continuity validation

Phase 5: Multimodal Attachments (Medium effort, market positioning)
  └── Conversation Memory Attachments
  └── LLM Multimodal Forwarding
  └── Content Type Matcher conditions
  └── REST API multipart support

Phase 6: Cryptographic Identity (High effort, regulated industry requirement)
  └── Agent signing keypairs + SecretsVault integration
  └── Signed inter-agent messages
  └── Signed MCP invocations
  └── Trust scoring foundation
```

---

## 9. Cross-Cutting Concerns

### 9.1 Metrics (All Improvements)

Every new feature **must** add Micrometer metrics:

| Feature | Metrics |
|---|---|
| Capability Registry | `capability.query.count`, `capability.match.latency`, `capability.miss.count` |
| Cryptographic Identity | `agent.signature.verify.success`, `agent.signature.verify.failure` |
| Multimodal Attachments | `attachment.store.count`, `attachment.store.bytes`, `attachment.load.latency` |
| Assertiveness | `counterweight.activation.count` by level |
| MCP Governance | `mcp.tool.discovery.count`, `mcp.response.truncation.count` |
| Session Safety | `session.snapshot.count`, `session.rollback.count`, `session.fork.count` |

### 9.2 Audit Ledger

All new operations are logged to the immutable HMAC-secured audit ledger:
- Capability match decisions (which agent was selected and why)
- Signature verification outcomes (success/failure)
- Counterweight activations (which rules were injected)
- MCP budget threshold events
- State snapshot creation and rollback events
- Session fork events (parent → child relationship)

### 9.3 Manager UI

Each improvement should be surfaced in the Manager UI with progressive disclosure (Pillar 7):
- **Simple view**: Toggle switches for counterweights, signing, multimodal support, auto-snapshotting
- **Advanced view**: Full JSON editing with Monaco for fine-grained configuration
- **Forked conversations**: Tree view showing parent-child conversation relationships

### 9.4 Testing

- Unit tests with Mockito for all new services
- Integration tests using Testcontainers (MongoDB + NATS) for end-to-end flows
- API contract tests for new REST endpoints
- Security tests for cryptographic operations (signing, verification, key rotation)
- Snapshot + rollback round-trip tests for session safety

---

## 10. Out of Scope

The following items are explicitly **not** covered by this plan:

- **Full DAG execution model** — covered in Phase 9 of the main roadmap
- **HITL framework** — covered in Phase 9b
- **Memory management (WISC, Strict Write Discipline, Property Consolidation)** — covered in a dedicated [Memory Architecture Plan](./memory-architecture-plan.md)
- **Multi-channel adapters (WhatsApp, Slack, Telegram)** — covered in Phase 11b
- **Visual pipeline builder** — covered in Phase 13
- **Native multimodal processing** (image classification, transcription in-engine) — EDDI delegates this to external tools/MCP

---

*This document should be revisited after each phase is completed to re-evaluate priorities based on customer feedback and market dynamics.*
