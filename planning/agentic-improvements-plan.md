# EDDI v6 — Agentic Improvements Plan

> **Scope.** Capability-based A2A routing, cryptographic agent identity, multimodal context attachments, behavioral counterweights, MCP governance, and session safety (snapshots + forking).
>
> **Governing Principles.** All work MUST conform to the Nine Pillars in [`docs/project-philosophy.md`](../project-philosophy.md) and the engine rules in [`AGENTS.md`](../../AGENTS.md). Java is the engine; configuration is logic; security is architecture.
>
> **Out of scope (tracked elsewhere).** Memory architecture (see [`memory-architecture-plan.md`](memory-architecture-plan.md)), DAG pipeline (Phase 9), HITL framework (Phase 9b), guardrails (`guardrails-architecture.md`), multi-channel adapters (Phase 11b), visual builder (Phase 13), native image (`native-image-migration.md`).

---

## 0. How to read this document

This plan is split into six **Waves** (delivery order) that map to six **Improvements** (topical grouping). The two numberings are kept deliberately distinct to avoid collision with the main roadmap's "Phase N" numbering in [`AGENTS.md` §3](../../AGENTS.md).

| Wave   | Improvement                               | Code status (verified 2026-04-17) | Est. effort |
| ------ | ----------------------------------------- | --------------------------------- | ----------- |
| Wave 1 | Improvement 4 — Behavioral Counterweights | Not implemented                   | Low         |
| Wave 2 | Improvement 5 — MCP Governance            | Partially implemented             | Medium      |
| Wave 3 | Improvement 1 — Capability Registry       | Implemented, gaps remain          | Low (gaps)  |
| Wave 4 | Improvement 6 — Session Safety            | Not implemented                   | Medium      |
| Wave 5 | Improvement 3 — Multimodal Attachments    | Model only, no pipeline/REST      | Medium      |
| Wave 6 | Improvement 2 — Cryptographic Identity    | Signing primitive only            | High        |

**Verification method.** Every "implemented" claim below has been checked against [src/main/java](../../src/main/java) on branch `fix/security-hardening-6.0.2`. Claims are annotated ✅ (present and wired), ⚠️ (present but not wired end-to-end), ❌ (not present).

---

## 1. Verified implementation status

### 1.1 Present and wired (✅ / ⚠️)

| Component                                                                                                                  | Location                                       | Status | Notes                                                                                          |
| -------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------- | ------ | ---------------------------------------------------------------------------------------------- |
| [`CapabilityRegistryService`](../../src/main/java/ai/labs/eddi/configs/agents/CapabilityRegistryService.java)              | `ai.labs.eddi.configs.agents`                  | ✅     | In-memory skill index. Rebuilds on agent CRUD.                                                 |
| [`CapabilityMatchCondition`](../../src/main/java/ai/labs/eddi/modules/rules/impl/conditions/CapabilityMatchCondition.java) | `ai.labs.eddi.modules.rules.impl.conditions`   | ✅     | Behavior-rule condition; wired in `RuleDeserialization`.                                       |
| [`ContentTypeMatcher`](../../src/main/java/ai/labs/eddi/modules/rules/impl/conditions/ContentTypeMatcher.java)             | `ai.labs.eddi.modules.rules.impl.conditions`   | ✅     | Matches on attachment MIME type. Wired.                                                        |
| [`Attachment`](../../src/main/java/ai/labs/eddi/engine/memory/model/Attachment.java)                                       | `ai.labs.eddi.engine.memory.model`             | ⚠️     | Metadata record exists. **Not wired to REST input; not fetched by `LlmTask` for forwarding.**  |
| [`AgentSigningService`](../../src/main/java/ai/labs/eddi/configs/agents/AgentSigningService.java)                          | `ai.labs.eddi.configs.agents`                  | ⚠️     | Ed25519 sign/verify with vault-backed key storage. **Primitive only — no call sites wire it.** |
| [`ToolResponseTruncator`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolResponseTruncator.java)                    | `ai.labs.eddi.modules.llm.impl`                | ✅     | Character-based truncation with size note.                                                     |
| `AgentConfiguration.identity`, `.security`, `.capabilities`                                                                | `configs/agents/model/AgentConfiguration.java` | ⚠️     | POJO fields exist; `security.*` flags have no consumer.                                        |

### 1.2 Not present (❌)

- `CounterweightService`, `DeploymentContextCondition`, `IdentityMaskingService` — entire Wave 1 block.
- Session forking endpoint (`POST /v6/conversations/{id}/fork`); `MemorySnapshotService.createCheckpoint` / `rollbackToCheckpoint`.
- Multipart REST upload for attachments; GridFS-backed attachment store; `LlmTask` multimodal forwarding of conversation-memory attachments.
- Token-efficient tool loading (`lazy`, `dynamic`; `discover_tools` meta-tool); `summarize` and `paginate` truncation strategies; MCP tenant-quota integration; per-tool cost weights.
- Signing envelope canonicalization, replay protection (nonce / `signedAt`), key rotation, call sites for `AgentSigningService`.
- Trust scoring / `agentTrustScore` integration.
- External A2A / capability discovery REST endpoint (only the internal admin REST exists at [`IRestCapabilityRegistry`](../../src/main/java/ai/labs/eddi/configs/agents/IRestCapabilityRegistry.java)).

### 1.3 Known bugs to fix while touching these areas

- `CapabilityRegistryService.round_robin` is implemented as a per-call `Collections.shuffle`; not true round-robin. Either rename to `random` or add per-skill atomic counters.
- `AgentConfiguration.security` exists with `signInterAgentMessages` / `signMcpInvocations` / `requirePeerVerification` flags but they are inert. Until Wave 6 wires them, PUT with any of them `true` MUST be rejected (see §5.2).

---

## 2. Cross-cutting rules (apply to every Wave)

Every task in every Wave MUST satisfy these rules. Reviewers should reject PRs that violate them.

### 2.1 Template syntax

EDDI v6 uses **Qute** (see [`AGENTS.md` §5.1](../../AGENTS.md)). In every config example in this plan and in test fixtures:

- Use **single braces**: `{properties.x}`, not `{{properties.x}}`.
- `properties.*` returns **raw values**. Never use `.valueString`, `.valueObject`, etc.
- Available top-level keys: `properties`, `memory`, `context`, `userInfo`, `conversationInfo`, `conversationLog`.

### 2.2 Naming

- Task class: `LlmTask`. Config file: `langchain.json`. Config POJO: `LlmConfiguration`. Do not use `LangchainTask` or `LangchainConfiguration` in new code.
- Behavior-rule condition types use the `ID` constant from the condition class (e.g. `CapabilityMatchCondition.ID`), not free-form strings.

### 2.3 Backward compatibility of stored configs

All new fields added to `AgentConfiguration`, `LlmConfiguration`, or any other JSON config MUST:

- Be optional (nullable, default-valued).
- Use `@JsonInclude(JsonInclude.Include.NON_NULL)` on getters (or class-level) so old configs round-trip byte-identical.
- Pass an import round-trip test for a pre-v6 ZIP (use one of the fixtures under [`docs/agent-configs`](../agent-configs/)).
- Be absent from the ZIP export if left at default.

### 2.4 Security of new config fields

If a field holds a secret, it MUST be declared with `scope: "secret"` semantics and stored via `ISecretProvider`. Plaintext MUST NOT appear in:

- Exported ZIPs.
- Micrometer tag values.
- Audit ledger payloads (store only a vault reference).
- Log messages.

### 2.5 Metrics

Every new feature MUST register at least one Micrometer counter or timer via the injected `MeterRegistry`. Metric names use the `eddi.` prefix and dot-separated lowercase. Existing conventions: `eddi.coordinator.*`, `eddi.mcp.*`, `eddi.agent.*`, `eddi.capability.*`.

### 2.6 Audit ledger

New security-relevant events MUST be written to the HMAC-secured audit ledger:

- Capability-match selections (`skill`, `strategy`, `candidateAgentIds`, `selectedAgentId`, `conversationId`).
- Signature verification outcomes (include `senderDid`, `conversationId`, failure reason).
- Counterweight activations (agent, level, rule source).
- Snapshot creation, rollback, and fork events (parent → child relationship).
- MCP budget threshold crossings (warning and hard-cap separately).

### 2.7 Thread safety

Every new `@ApplicationScoped` bean is a singleton. Do not store conversation data in instance fields. Use `ConcurrentHashMap` and `CopyOnWriteArrayList` the way `CapabilityRegistryService` already does.

### 2.8 Tests required per feature

Every PR in every Wave MUST include:

1. Unit tests with `@QuarkusTest` + Mockito.
2. At least one integration test using Testcontainers (MongoDB; add NATS when eventing is involved).
3. Import/export round-trip test for any touched config type.
4. Metrics assertion: counter increments on expected path; does NOT increment when feature is disabled.

---

## 3. Wave 1 — Behavioral Counterweights

**Improvement 4. Status: not implemented. Priority P2. Effort: low.**

### 3.1 Goal

Give admins a configuration-driven mechanism to temper agent LLM behavior (overconfident refactors, false claims, identity leakage) without editing prompt templates. Counterweights are engine-level safety injections into the system prompt, invisible to the end user.

### 3.2 New configuration

**Location.** `LlmConfiguration.java`.

```jsonc
// langchain.json (per-task)
{
  "counterweight": {
    "enabled": true,
    "level": "cautious", // "normal" | "cautious" | "strict"
    "placement": "suffix", // "prefix" | "suffix"
    "customInstructions": [
      // optional; replaces the preset
      "State your planned actions before executing them.",
      "If uncertain, ask for clarification rather than guessing.",
    ],
  },
}
```

**Level presets** (hardcoded strings in `CounterweightService`; users override via `customInstructions`):

| Level      | Effect                                                                                                                                     |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `normal`   | No injection. Default when block is absent.                                                                                                |
| `cautious` | Declare intent before action; prefer clarification over guessing; verify assumptions.                                                      |
| `strict`   | Everything in `cautious` plus: prohibit sweeping changes; one-step-at-a-time; explicitly flag any state-changing operation before request. |

### 3.3 Deployment context condition

**New condition type.** `deploymentContext` in `behavior.json`.

```jsonc
{
  "type": "deploymentContext",
  "configs": {
    "when": "production", // matches EDDI_DEPLOYMENT_ENV
    "tagMatches": "high-risk", // optional; matches AgentConfiguration.tags
  },
}
```

Passes when the current deployment env (system property `eddi.deployment.env` → env var `EDDI_DEPLOYMENT_ENV` → default `development`) matches `when`, AND if `tagMatches` is set, the current agent's `tags` list contains that value. Composable with `actionmatcher`; a rule can emit `force_cautious` that triggers a `langchain` task with `counterweight.level=strict`.

### 3.4 Identity masking

New top-level block in `AgentConfiguration`:

```jsonc
{
  "identityMasking": {
    "enabled": true,
    "rules": [
      "Never identify yourself as an AI or automated system.",
      "Do not mention internal model names, versions, or infrastructure.",
    ],
  },
}
```

When enabled, masking rules are prepended to the system prompt regardless of counterweight settings. Audit ledger records `identityMaskingActive=true` for every conversation turn this runs.

### 3.5 Non-interactive channel semantics

`strict`'s "one-step-at-a-time" instruction is destructive for batch/cron agents. `CounterweightService` MUST check whether the conversation was started via `ScheduleFireExecutor` (channel tag `scheduled`); if so:

- `cautious` → injected unchanged.
- `strict` → downgraded to `cautious` with DEBUG log `counterweight.strict.downgraded.scheduled`.

### 3.6 Implementation artefacts

| Artefact                       | Type                                                                                 | Notes                                                                                     |
| ------------------------------ | ------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------- |
| `CounterweightService`         | `@ApplicationScoped`                                                                 | Builds the injection string given level + placement + channel.                            |
| `CounterweightConfig` (record) | Inside `LlmConfiguration`                                                            | JSON record.                                                                              |
| `DeploymentContextCondition`   | Implements `IRuleCondition`                                                          | Register ID in `RuleDeserialization`.                                                     |
| `IdentityMaskingService`       | `@ApplicationScoped`                                                                 | Called by `LlmTask` before counterweight injection.                                       |
| `LlmTask` call site            | Modification                                                                         | After system-prompt compile, call `identityMasking.apply()` then `counterweight.apply()`. |
| Metrics                        | `eddi.counterweight.activation.count{level}`, `eddi.counterweight.strict.downgraded` |                                                                                           |
| Audit ledger events            | `COUNTERWEIGHT_APPLIED`, `IDENTITY_MASKING_APPLIED`                                  |                                                                                           |
| Template-preview REST          | Extension                                                                            | Resolved-prompt preview MUST show the injected counterweight for voice-collision checks.  |

### 3.7 Tests

- Unit: preset text injection per level; prefix vs suffix placement; `customInstructions` override.
- Unit: `strict` downgrades to `cautious` when channel tag is `scheduled`.
- Integration: full conversation turn with `cautious` → assert injection present via template preview.
- Import/export: agent with and without `counterweight` block round-trips.

---

## 4. Wave 2 — MCP Governance & Token-Efficient Tool Loading

**Improvement 5. Status: partial (`ToolResponseTruncator` ✅). Priority P2. Effort: medium.**

### 4.1 Finish response truncation

Add two strategies to `ToolResponseTruncator`:

1. **`paginate`.** Split oversized responses into numbered pages stored in conversation memory; inject only page 1; expose built-in tool `fetch_tool_response_page(responseId, pageNumber)`.
2. **`summarize`.** Route oversize responses through a cheap model (global `eddi.mcp.summarizer.model`, default Gemini Flash when available, else `gpt-4o-mini`).
   - Summarizer calls count against the tenant LLM budget via `CostTracker`.
   - If summarizer fails or would exceed per-turn cost ceiling, fall back to `truncate` and log `mcp.summarize.fallback.truncate`.
   - Summary header: `[SUMMARY — full response id=<X>, length=<N>]`.

Per-tool limits (`ToolResponseLimits.perToolLimits`) already work — add import/export coverage and surface via the extension descriptor.

### 4.2 Token-efficient tool loading

Add `toolLoadingStrategy` to `LlmConfiguration`:

```jsonc
{
  "toolLoadingStrategy": {
    "type": "eager", // "eager" (default) | "lazy" | "dynamic"
    "maxToolsInContext": 10,
    "discoveryToolEnabled": true,
  },
}
```

- **`eager`.** Current behavior — all tools loaded into the prompt.
- **`lazy`.** Only the meta-tool `discover_tools(category, keywords)` is advertised at turn start. When the LLM calls it, `LlmTask` injects matching tool schemas into the _next_ model request and re-runs. Bounded by `maxToolsInContext`.
- **`dynamic`.** Variant of `lazy` pre-filtered by current behavior-rule actions. The LLM sees only tools whose `actions` intersect the current step's action list.

#### Tool cache correctness under `lazy` / `dynamic`

`ToolExecutionService.executeToolWrapped()` caches by `(toolName, args)`. Lazy loading means the active tool set can change turn-to-turn, so a cached response for `tool=X args=Y` may be stale if `X`'s schema has drifted. Change the cache key to `(toolName, args, toolSchemaHash)` where `toolSchemaHash` is a stable hash of the tool's advertised JSON schema at call time. Document in the cache Javadoc: "cache entries are schema-versioned."

### 4.3 MCP tenant-quota integration

Wire existing `TenantQuotaService.acquireApiCallSlot()` into `ToolExecutionService.executeToolWrapped()` for MCP calls. Pipeline becomes:

```
MCP Tool Call → Rate Limiter → Quota Check → Schema-Versioned Cache → Execute → Cost Tracker → Truncate → Result
```

**Per-tool cost weights.** Optional `weight` field on tool definition, default `1`. On `acquireApiCallSlot()`, decrement by `weight`.

```jsonc
{ "name": "db.query",   "weight": 10, "classification": "STATE_CHANGING" }
{ "name": "search.web", "weight": 1,  "classification": "READ_ONLY" }
```

Weights are quota indicators, not dollars — dollar cost is already tracked by `CostTracker`.

### 4.4 Artefacts

| Artefact                                   | Notes                                                                                                                                 |
| ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------- |
| `ToolResponseTruncator` extensions         | `summarize`, `paginate`, pagination response store                                                                                    |
| `fetch_tool_response_page` built-in tool   | Returns page by id+number; `READ_ONLY`                                                                                                |
| `discover_tools` built-in meta-tool        | Used by `lazy`/`dynamic`                                                                                                              |
| `ToolLoadingStrategy` enum + config        | Inside `LlmConfiguration`                                                                                                             |
| `ToolExecutionService` cache key extension | Add `toolSchemaHash`                                                                                                                  |
| `ToolExecutionService` quota integration   | Call `acquireApiCallSlot()` with `weight`                                                                                             |
| Metrics                                    | `eddi.mcp.tool.discovery.count`, `eddi.mcp.summarize.fallback.truncate`, `eddi.mcp.budget.exceeded`, `eddi.mcp.cache.schema_mismatch` |

### 4.5 Tests

- Unit: truncation, summarize, paginate; summarizer cost-ceiling fallback.
- Unit: cache key differs for same `(tool, args)` when schema hash differs.
- Integration: `lazy` — first turn injects only `discover_tools`; second turn (after LLM calls it) injects matched schemas.
- Integration: MCP tool refused with HTTP 429 when tenant quota exhausted.

---

## 5. Wave 3 — A2A Capability Registry (close the gaps)

**Improvement 1. Status: core implemented ✅, gaps remain. Priority P2. Effort: low.**

### 5.1 Fix `round_robin`

Replace per-call shuffle with deterministic round-robin:

- Per-skill `AtomicInteger` counter keyed on skill name.
- `findBySkill(skill, "round_robin")` returns the list rotated by `counter.getAndIncrement() % size`.
- Counter reset on `register()` / `unregister()` to avoid drift.

If random selection is desired, add a separate `random` strategy. Do not alias.

### 5.2 Activate or reject the `security` flags

Until Wave 6 wires signing into messaging, REST PUT MUST reject `security.signInterAgentMessages=true` (and the other two flags) with HTTP 400 message: `"cryptographic-identity features not yet available in this version"`. Prevents silent-failure configs.

### 5.3 External discovery endpoint

Add a public A2A-facing endpoint alongside the existing admin REST:

- Path: `GET /.well-known/capabilities?skill=X&language=Y`
- Auth: same model as `/.well-known/agent.json`. Gated behind `eddi.a2a.capabilities.public` boolean, default `false`.
- Response: `[ { agentId, skill, confidence, attributes } ]`. No tenant IDs, no private agent cards.
- Rate-limit: shared unauthenticated pool via `eddi.a2a.public.rate-per-minute` (default 60).

### 5.4 Define `lowest_load` before implementing

The original plan listed `lowest_load`. Before writing code, pin the metric:

- Candidate: **active conversation count per agent** from existing `ConversationMetricsService`.
- Defer token-throughput tie-breaking to a future trust-score integration.
- Document in `applyStrategy` Javadoc. Do not ship `lowest_load` until the metric is wired.

### 5.5 Audit selection decisions

Every `CapabilityMatchCondition` routing decision emits `CAPABILITY_SELECTION` to the audit ledger with `skill`, `strategy`, `candidateAgentIds`, `selectedAgentId`, `conversationId`.

### 5.6 Metrics

- `eddi.capability.miss.count{skill}` — empty result.
- `eddi.capability.strategy.applied{strategy}` — which strategy was used.

### 5.7 Tests

- Unit: `round_robin` rotates deterministically across 100 calls with 3 agents.
- Unit: `security.*=true` on PUT rejected with 400 until Wave 6.
- Integration: public discovery endpoint sanitized; `eddi.a2a.capabilities.public=false` returns 404.

---

## 6. Wave 4 — Session Safety (Snapshot + Fork)

**Improvement 6. Status: not implemented. Priority P2. Effort: medium.**

### 6.1 Automatic pre-tool snapshot

Before any tool classified `STATE_CHANGING` runs, `ToolExecutionService` creates a lightweight snapshot of the current `ConversationStep` and `conversationProperties`.

```java
record MemoryCheckpoint(
    String checkpointId,        // UUID
    String conversationId,
    String parentConversationId, // null for non-fork
    int stepIndex,
    ConversationStep stepCopy,  // deep copy
    Map<String, Object> propertiesCopy,
    Instant createdAt,
    String triggeredBy,         // tool name
    String triggeredByClass     // "STATE_CHANGING"
) {}
```

Persisted in new MongoDB collection `conversation_checkpoints` via `IConversationCheckpointStore`.

### 6.2 Retention policy (no unbounded growth)

- Per conversation: keep last N checkpoints (`eddi.session.checkpoints.max-per-conversation`, default 10). Older pruned on each write.
- TTL inherits conversation TTL (MongoDB TTL index on `createdAt + conversation.ttl`).
- Per-tenant hard cap: `eddi.session.checkpoints.max-per-tenant`, default 100_000, enforced via `TenantQuotaService`.

### 6.3 Rollback semantics

On `STATE_CHANGING` tool failure:

- `ToolExecutionService.rollbackToCheckpoint(checkpointId)` atomically restores the snapshot via `findOneAndUpdate` keyed on document version (no lost-update).
- Writes `ROLLBACK` audit event with `reason`.
- Routes pipeline to the `on_error` action declared on the current behavior rule.

**Rollback is in-engine only.** External side-effects (HTTP POST already issued, DB INSERT already committed) are NOT reversed. Document in the tool-classification guide and Manager UI.

### 6.4 Session forking

New endpoint: `POST /v6/conversations/{id}/fork`.

- Deep-copies conversation document to a new UUID with `parentConversationId = id`.
- Forks inherit `readOnly=true` by default.
- A `STATE_CHANGING` invocation inside a `readOnly=true` fork is **refused** with structured error `fork.readonly.blocked` unless an admin explicitly sets `readOnly=false` via `PATCH /v6/conversations/{forkId}?readOnly=false`. Flipping is an audit event.
- Per-tenant quota: `eddi.session.forks.max-per-tenant`. Per-conversation (across all descendants transitively): `maxForksPerConversation` from `AgentConfiguration.sessionManagement`, default 5.
- Ancestry stored as `ancestryPath` (materialized-path string `root/parent/me`) for O(1) "total descendants" queries.

### 6.5 Configuration

```jsonc
// AgentConfiguration
{
  "sessionManagement": {
    "autoSnapshot": {
      "enabled": true,
      "triggerOn": "STATE_CHANGING",
    },
    "forkingEnabled": true,
    "maxForksPerConversation": 5,
  },
}
```

### 6.6 Artefacts

| Artefact                           | Notes                                                                                                                 |
| ---------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `MemoryCheckpoint` record          | Immutable                                                                                                             |
| `IConversationCheckpointStore`     | Interface (follow `IResourceStore<T>` pattern)                                                                        |
| `MongoConversationCheckpointStore` | MongoDB impl; TTL index on `createdAt`                                                                                |
| `MemorySnapshotService`            | `createCheckpoint`, `rollbackToCheckpoint`, `prune`                                                                   |
| `ToolExecutionService` changes     | Wrap STATE_CHANGING with checkpoint/rollback                                                                          |
| `ConversationForkService`          | Deep-copy + ancestry                                                                                                  |
| `RestConversations.fork` endpoint  | Returns `{forkedConversationId, parentId, readOnly}`                                                                  |
| Manager UI tree view               | Later — not this Wave                                                                                                 |
| Metrics                            | `eddi.session.snapshot.count`, `eddi.session.rollback.count`, `eddi.session.fork.count`, `eddi.fork.readonly.blocked` |

### 6.7 Tests

- Unit: checkpoint create + restore; `findOneAndUpdate` prevents concurrent rollback races.
- Unit: pruning keeps exactly N newest.
- Integration: STATE_CHANGING tool throws → pipeline rolls back → `on_error` fires.
- Integration: fork inherits memory; writing to parent doesn't affect fork; `readOnly=true` refuses STATE_CHANGING.
- Integration: per-tenant fork cap enforced.

---

## 7. Wave 5 — Multimodal Attachments

**Improvement 3. Status: model only. Priority P3. Effort: medium.**

### 7.1 Design reaffirmation

EDDI remains **text-centric orchestration middleware**. This Wave lets multimodal _context_ flow through the pipeline so LLMs and external tools can process it. Attachments are routed by metadata; EDDI does not classify, transcribe, or OCR content itself.

### 7.2 Storage backend

Use MongoDB **GridFS** as default. Add abstraction `IAttachmentStore` for future S3.

**Security rules for `IAttachmentStore.store(bytes, declaredMime, declaredFilename, conversationId, tenantId)`:**

1. Magic-byte sniff via Apache Tika. Compare sniffed MIME against declared. Mismatch → reject with `attachment.mime_mismatch`.
2. Enforce `maxBytes` tenant quota (`eddi.attachments.max-size-bytes` default 20 MB, per-tenant overridable).
3. Return `storageRef` encoding `tenantId/conversationId/attachmentId` so retrieval can authorize.

**`load(storageRef, requestingConversationId)`** MUST reject if requestor is not the owner or a permitted peer in a group conversation.

### 7.3 REST input

```
POST /v6/conversations/{id}/messages
Content-Type: multipart/form-data
  input: "What's in this image?"
  attachment_0: <binary>
  attachment_1: <binary>
```

Layer:

1. Validate each binary against tenant quotas.
2. Store to GridFS → `Attachment` record.
3. Attach to new `ConversationStep`.
4. Emit SSE events with metadata only (never bytes).

Legacy JSON endpoint unchanged.

### 7.4 `LlmTask` forwarding

When building the chat message, iterate `memory.getCurrentStep().getAttachments()`:

- `image/*` + provider supports vision (`LlmProvider.supportsVision()`): attach via langchain4j `ImageContent`.
- `audio/*` + provider supports audio: attach via provider-specific content type.
- Otherwise: skip forwarding but inject text marker into user message: `[Attached: <filename> (<mimeType>, <size>)]` so the LLM can request a tool-based processor.

### 7.5 Behavior rule matching

`ContentTypeMatcher` already matches attachments on `currentStep`. Document in [`docs/behavior-rules.md`](../behavior-rules.md) with example routing `image/*` to an external OCR MCP tool.

### 7.6 Cost accounting

Multimodal input consumes materially more tokens. Changes:

- `CostTracker` extension: when `LlmTask` forwards `ImageContent`, estimate tokens using provider formula (e.g., OpenAI `(width*height)/750`). Reconcile against provider usage on response.
- Audit ledger logs `attachmentsForwarded` and `estimatedAttachmentTokens`.

### 7.7 Cleanup

- GridFS TTL index matches conversation TTL.
- Conversation deletion (including GDPR erasure) cascades to attachments.
- Nightly attachment reaper via `ScheduleFireExecutor` cleans orphans.

### 7.8 Malware scanning (explicit scope decision)

Out of scope for in-engine implementation. Provide MCP hook: tenants configure a `scan_attachment(storageRef)` MCP tool; if present, `IAttachmentStore.store()` calls it synchronously before returning. Non-OK result rejects upload. If absent, log WARN once at startup `attachments.malware_scanner.not_configured` and proceed.

### 7.9 Artefacts

| Artefact                          | Notes                                                                                                                                  |
| --------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `IAttachmentStore`                | Interface                                                                                                                              |
| `GridFsAttachmentStore`           | Default impl                                                                                                                           |
| Tika dependency                   | Add to `pom.xml` if not already transitive                                                                                             |
| REST multipart handler            | In `RestConversationStore`                                                                                                             |
| `LlmTask` attachment forwarding   | Vision + audio + text fallback                                                                                                         |
| `CostTracker` multimodal estimate | Per-provider formula                                                                                                                   |
| Attachment reaper                 | `ScheduleFireExecutor` job                                                                                                             |
| Metrics                           | `eddi.attachment.store.count`, `eddi.attachment.store.bytes`, `eddi.attachment.rejected{reason}`, `eddi.attachment.load.authz_failure` |

### 7.10 Tests

- Unit: sniffed vs declared MIME mismatch → rejection.
- Unit: oversize → tenant-cap rejection.
- Unit: cross-conversation `load()` → authz failure.
- Integration: multipart upload → SSE metadata event → `LlmTask` forwards image → mock provider observes `ImageContent`.
- Integration: GDPR erasure cascades GridFS delete.

---

## 8. Wave 6 — Cryptographic Agent Identity

**Improvement 2. Status: primitive only. Priority P3 (P1 for regulated tenants). Effort: high.**

### 8.1 Threat-model scope (reaffirmed)

Does NOT replace TLS or prevent network MITM (EDDI typically sits behind enterprise ingress). Defends against:

1. Prompt-injection identity spoofing between agents in a group.
2. Tampered inter-agent context within an EDDI deployment.
3. Audit repudiation (proving _which_ agent generated an output).
4. Untrusted external MCP invocations claiming to be a specific agent.

### 8.2 Canonicalization (prerequisite)

Picking a canonicalization scheme is mandatory; signatures break across serializers without it. **Decision: JCS (RFC 8785).**

- Add dependency `io.github.erdtman:java-json-canonicalization`.
- Every message is a `SignedEnvelope`:

```java
record SignedEnvelope(
    String senderDid,
    String recipientDid,     // null for broadcast
    String conversationId,
    long signedAtEpochMs,
    String nonce,            // 128-bit random, UUID v4
    int keyVersion,          // see §8.4
    JsonNode payload
) {}
```

Sign/verify operate over `JCS(envelopeWithoutSignatureField)`.

### 8.3 Replay protection

Every verifier MUST:

1. Reject envelopes with `signedAtEpochMs` older than `eddi.a2a.signature.freshness-seconds` (default 300) or more than 60 s in the future (clock-skew tolerance).
2. Maintain per-recipient LRU cache of seen `nonce` values sized at `eddi.a2a.signature.nonce-cache-size` (default 10_000). Duplicate nonce within freshness window → reject.

Use Caffeine (already a dependency).

### 8.4 Key rotation

- `AgentIdentity` gains `List<AgentPublicKey>`; each: `{keyVersion, publicKey, notBefore, notAfter}`.
- Endpoint `POST /v6/agents/{id}/identity/rotate`:
  - Generates new keypair.
  - Stores new private key in vault under `agent-signing-key:{agentId}:v{n}`.
  - Adds new public key with `notBefore=now`.
  - Sets `notAfter=now + gracePeriod` on the previous key (default 7 days).
- Verifiers accept any key whose `[notBefore, notAfter]` window contains `envelope.signedAtEpochMs`.
- Audit events: `AGENT_KEY_GENERATED`, `AGENT_KEY_ROTATED`, `AGENT_KEY_EXPIRED`.

### 8.5 Call-site wiring

Activate `AgentSigningService` at these call sites (gated on `AgentConfiguration.security.*`):

1. **`GroupConversationService.discuss()`** — sign envelope before delivering an agent's response to peers. Receivers verify before accepting. Verification failure emits `SIGNATURE_VERIFICATION_FAILED` and routes pipeline to `on_security_error` action (default: drop, log WARN).
2. **EDDI's MCP server.** `talk_to_agent` / other agent-scoped tools accept optional `signedEnvelope` param; when present, verify and bind to `senderDid`. When absent and `requirePeerVerification=true` on target agent, reject.
3. **A2A outbound.** When EDDI calls a remote peer's `/messages/send`, wrap outbound payload as `SignedEnvelope`.

### 8.6 Identity masking vs. signing

No conflict: masking is a **prompt-level** adjustment for end-user output; signing is a **transport-level** proof of origin for agent-to-agent and MCP traffic. Masking does not strip signatures; signing does not include masking instructions in signed payloads.

### 8.7 Trust scoring foundation (deferred)

Only the metric surface ships now:

- Micrometer counters tagged by `agentDid`: `signature.verify.success`, `signature.verify.failure`, `tool.execution.success`, `tool.execution.failure`, `quota.exceeded`, `hitl.rejection`.
- `CapabilityMatch` gains optional `trustScore`, always `null` in this Wave.

Actual score computation deferred until real data exists to calibrate thresholds.

### 8.8 Artefacts

| Artefact                                          | Notes                                                                                                                                                                                                                               |
| ------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `java-json-canonicalization` dep                  | Add to `pom.xml`                                                                                                                                                                                                                    |
| `SignedEnvelope` record                           | New                                                                                                                                                                                                                                 |
| `AgentPublicKey` record + list in `AgentIdentity` | Back-compat single-key adapter                                                                                                                                                                                                      |
| `NonceCacheService` (Caffeine)                    | Per-process; document that clusters need sticky routing OR shared store (Redis) for strict replay protection; in-process default acceptable for single-tenant                                                                       |
| `GroupConversationService` integration            | Sign + verify                                                                                                                                                                                                                       |
| `RestA2AEndpoint` integration                     | Verify inbound when `requirePeerVerification`                                                                                                                                                                                       |
| MCP server changes                                | Accept and verify `signedEnvelope`                                                                                                                                                                                                  |
| Key-rotation endpoint                             | `POST /v6/agents/{id}/identity/rotate`                                                                                                                                                                                              |
| Metrics                                           | `eddi.agent.identity.sign.count`, `eddi.agent.identity.verify.success`, `eddi.agent.identity.verify.failure`, `eddi.agent.identity.rotation.count`, `eddi.agent.identity.replay.rejected`, `eddi.agent.identity.freshness.rejected` |

### 8.9 Tests

- Unit: canonicalization stable across field-order permutations (JCS contract).
- Unit: verification rejects modified payloads.
- Unit: replay within freshness window rejected; outside window rejected with distinct reason.
- Unit: key rotation — old signatures within grace window still verify; beyond `notAfter` they fail.
- Integration: group conversation with two agents — tamper with one peer's payload → recipient rejects → audit event present.
- Integration: MCP `talk_to_agent` with missing signature on an agent requiring it → 401.

---

## 9. Cross-cutting work

### 9.1 Manager UI

Each Wave gets a surface with **progressive disclosure** (Pillar 7):

- Wave 1: Toggles for `counterweight.level` and `identityMasking.enabled`; advanced JSON editor for custom instructions.
- Wave 2: Dropdown for `toolLoadingStrategy.type`; per-tool weights table.
- Wave 3: Capability editor with skill autocomplete; read-only registry view.
- Wave 4: Fork tree view; per-fork writable toggle with confirmation.
- Wave 5: File-drop zone in chat panel; attachment list in memory inspector.
- Wave 6: Key-rotation panel; signature-status badges on group messages.

UI tasks live in **EDDI-Manager**, not this repo. Track in `EDDI-Manager/AGENTS.md` as each Wave lands.

### 9.2 Documentation updates (per Wave)

- [`docs/architecture.md`](../architecture.md) — new components and flows.
- [`docs/behavior-rules.md`](../behavior-rules.md) — new condition types with examples.
- [`docs/langchain.md`](../langchain.md) — counterweight, tool-loading strategy, response limits.
- [`docs/security.md`](../security.md) — signing model, replay protection, key rotation.
- [`docs/compliance-data-flow.md`](../compliance-data-flow.md) — attachments in flow diagrams.
- [`docs/changelog.md`](../changelog.md) — per-PR entries per [`AGENTS.md` §2](../../AGENTS.md).

### 9.3 MCP server surface additions

Expose new capabilities through EDDI's MCP server so external agents can use them:

- Wave 3: `find_agent_by_capability(skill, strategy, attributes)`.
- Wave 4: `fork_conversation(conversationId)`, `list_checkpoints(conversationId)`, `rollback_to_checkpoint(conversationId, checkpointId)`.
- Wave 6: `verify_agent_signature(agentDid, envelopeJson)`.

Each MCP tool MUST declare classification (`READ_ONLY` vs `STATE_CHANGING`) so Wave 4's auto-snapshot applies correctly.

### 9.4 Import/export round-trip

Every Wave's new config fields MUST pass a ZIP round-trip test. A parameterized integration test `AgentImportExportRoundTripTest` covers all new field shapes.

### 9.5 Startup checks

Extend `ComplianceStartupChecks` to fail-fast when:

- `AgentConfiguration.security.signInterAgentMessages=true` but no `identity.keys` (Wave 6).
- `sessionManagement.autoSnapshot.enabled=true` but `IConversationCheckpointStore` unavailable (e.g., DB adapter missing impl).
- `toolLoadingStrategy.type=dynamic` but no `CapabilityRegistryService` populated at startup.

---

## 10. Delivery sequence and acceptance

### 10.1 Recommended order

```
Wave 1: Behavioral Counterweights
  └── immediate safety value, low effort, no persistence changes

Wave 2: MCP Governance
  └── immediate cost savings, extends existing ToolExecutionService

Wave 3: Capability Registry gaps
  └── finishes an almost-done feature, unlocks Wave 6 trust scoring

Wave 4: Session Safety
  └── regulated-industry value; prerequisite for forking demos

Wave 5: Multimodal Attachments
  └── market positioning; larger surface area; benefits from Wave 4 cleanup semantics

Wave 6: Cryptographic Identity
  └── highest effort and risk; depends on Waves 3 (capability metadata) and 4 (audit continuity)
```

### 10.2 Acceptance checklist per Wave

A Wave is "done" only when:

- [ ] All artefacts exist and pass build.
- [ ] Unit + integration tests as listed pass in CI.
- [ ] Import/export round-trip test passes.
- [ ] Manager UI surface is designed (implementation may lag).
- [ ] Documentation updates merged.
- [ ] `docs/changelog.md` entry added.
- [ ] At least one end-to-end demo agent config in [`docs/agent-configs/`](../agent-configs/) exercises the feature.
- [ ] Metrics visible on `docs/monitoring/eddi-grafana-dashboard.json`.

### 10.3 Rollback strategy

Each Wave is deployable independently. Feature flags:

- `eddi.counterweight.enabled` (Wave 1, default true — per-agent config still required for activation).
- `eddi.mcp.tool-loading.enabled` (Wave 2, default true; `eager` remains default strategy).
- `eddi.a2a.capabilities.public` (Wave 3, default false).
- `eddi.session.checkpoints.enabled`, `eddi.session.forks.enabled` (Wave 4, default true).
- `eddi.attachments.enabled` (Wave 5, default true; disabling rejects multipart uploads with 501).
- `eddi.a2a.signing.enabled` (Wave 6, default false; opt-in per environment).

---

## 11. Session handoff notes

For any agent picking this up mid-stream:

1. Read [`docs/project-philosophy.md`](../project-philosophy.md) first — non-negotiable.
2. Read [`AGENTS.md`](../../AGENTS.md), especially §4 (Java guidelines) and §5 (agent config authoring).
3. Run `git status`, `git branch --show-current`, `git log -5 --oneline`.
4. Check [`docs/changelog.md`](../changelog.md) for recent entries on any of the six Waves.
5. Do NOT trust historical "implemented" callouts in older plan revisions without re-verifying via `file_search` / `grep_search`.
6. Do NOT commit to `main`. Create a `feature/agentic-<wave-name>` branch.
7. Every commit MUST build (`./mvnw compile`) and new tests MUST pass (`./mvnw test -Dtest=<your-test>`).
8. When finishing or switching context, append an entry to [`docs/changelog.md`](../changelog.md) describing what shipped, what's in progress, and what's next.

---

_End of plan. This document is the authoritative source for the six Waves. Supersedes the previous "Agentic Improvements" revision. Revisit after each Wave ships to rescope remaining Waves based on real-world feedback._

# EDDI v6.0.0 — Agentic Improvements Plan

> **Scope**: Multi-agent orchestration, A2A evolution, cryptographic agent identity, multimodal context attachments, and behavioral governance.
>
> **Governing Principles**: All changes **must** conform to the [Nine Pillars](../project-philosophy.md). Java is the engine, configuration is logic, security is architecture.

> [!IMPORTANT]
> **Implementation Status (2026-04-07):**
>
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

| Component                                  | Status         | Location                        |
| ------------------------------------------ | -------------- | ------------------------------- |
| Agent Cards (`.well-known/agent.json`)     | ✅ Implemented | `RestA2AEndpoint.java`          |
| Agent Card Service                         | ✅ Implemented | `AgentCardService.java`         |
| A2A Data Models                            | ✅ Implemented | `A2AModels.java`                |
| A2A Tool Provider for LLM                  | ✅ Implemented | `A2AToolProviderManager.java`   |
| Group Conversations (debate orchestration) | ✅ Implemented | `GroupConversationService.java` |

**What's missing**: Agents can talk to each other, but they can't _find_ each other dynamically. Group composition is entirely static — the admin pre-configures which agents participate.

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

**Key design decision**: Soft routing defines _boundaries_ (which skills are acceptable, what selection strategy to use), not exact routes. The engine evaluates the capability registry at runtime and selects the best-match agent. This preserves determinism — the selection strategy is a deterministic algorithm, not an LLM guess.

**Selection strategies** (config-driven, not hardcoded):

- `highest_confidence` — pick the agent with the highest declared confidence
- `round_robin` — distribute across all matching agents
- `lowest_load` — pick the agent with the fewest active conversations (leverages existing `ConversationMetricsService`)

#### 2.2.3 Implementation Components

| Component                   | Type                                       | Description                                               |
| --------------------------- | ------------------------------------------ | --------------------------------------------------------- |
| `capabilities` field        | Config POJO                                | New field on `AgentConfiguration` (Java record)           |
| `CapabilityRegistryService` | `@ApplicationScoped` bean                  | Indexes, queries, and resolves capability matches         |
| `capabilityMatch` condition | Extension to `BehaviorRulesEvaluationTask` | New condition type for soft routing                       |
| REST endpoint               | JAX-RS resource                            | `GET /v6/capabilities?skill=X` for external/MCP discovery |
| Micrometer metrics          | Counters/timers                            | `capability.query.count`, `capability.match.latency`      |

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
3. **Audit repudiation** — ability to prove which agent _actually_ generated a particular output

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
> **EDDI remains text-centric orchestration middleware.** The goal is not to become a multimodal processing engine — it's to allow multimodal _context_ to flow through the pipeline so LLMs and external tools can process it. EDDI routes based on **metadata** (MIME type, size, classification results), not on raw binary content.

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
      "level": "cautious", // "normal", "cautious", "strict"
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
    "type": "lazy", // "eager" (current default), "lazy", "dynamic"
    "maxToolsInContext": 10, // Hard cap on simultaneous tool definitions
    "discoveryTool": true // Provides a meta-tool: "list_available_tools(category)"
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
    "maxTokens": 25000, // Hard cap on tool response size
    "truncationStrategy": "summarize", // "truncate", "summarize", "paginate"
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

| Feature                | Metrics                                                                       |
| ---------------------- | ----------------------------------------------------------------------------- |
| Capability Registry    | `capability.query.count`, `capability.match.latency`, `capability.miss.count` |
| Cryptographic Identity | `agent.signature.verify.success`, `agent.signature.verify.failure`            |
| Multimodal Attachments | `attachment.store.count`, `attachment.store.bytes`, `attachment.load.latency` |
| Assertiveness          | `counterweight.activation.count` by level                                     |
| MCP Governance         | `mcp.tool.discovery.count`, `mcp.response.truncation.count`                   |
| Session Safety         | `session.snapshot.count`, `session.rollback.count`, `session.fork.count`      |

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

_This document should be revisited after each phase is completed to re-evaluate priorities based on customer feedback and market dynamics._
