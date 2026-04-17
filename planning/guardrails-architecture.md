# EDDI Guardrails Architecture — Enterprise Analysis

## Executive Summary

This document analyzes what a guardrails system for EDDI should look like, why it matters for enterprise adoption, and recommends the architecture that best fits EDDI's existing pipeline. The analysis is based on deep review of the codebase and the current state of LangChain4j's guardrail APIs.

---

## What Enterprises Actually Need From Guardrails

Enterprise buyers evaluate AI platforms against compliance checklists. Guardrails address **four** non-negotiable categories:

### 1. Safety & Content Control
- Block toxic, violent, or sexually explicit content
- Prevent the bot from generating legally risky statements (medical/financial/legal advice without disclaimers)
- Enforce topic boundaries ("only answer about our products")

### 2. Security
- **Prompt injection detection** — prevent adversarial inputs that hijack agent behavior
- **Data leakage prevention** — block system prompt extraction attempts
- **PII redaction** — strip SSNs, emails, credit card numbers before they reach the LLM or appear in responses

### 3. Compliance & Governance
- **Regulatory disclaimers** — auto-append required disclosures
- **Audit trail of every block/pass decision** — proving compliance in regulated industries
- **Per-tenant guardrail policies** — different clients get different safety profiles (critical for SaaS/white-label)

### 4. Quality & Reliability
- **Response format validation** — ensure JSON responses conform to schema
- **Hallucination reduction** — cross-check claims against RAG sources
- **Reprompt on failure** — automatically retry when output violates rules (LangChain4j's killer feature)

---

## EDDI's Existing Patterns (What We Can Build On)

EDDI already has **partial guardrail infrastructure** via several mechanisms:

| Existing Pattern | Acts As | Limitation |
|---|---|---|
| `preRequest` in `LlmTask` | Input guardrail (property manipulation) | Template-based only; can't block/reject |
| `postResponse` in `LlmTask` | Output guardrail (formatting/extraction) | No reject/reprompt capability |
| `STOP_CONVERSATION` action | Hard stop mechanism | Too coarse — stops entire conversation, not just one LLM call |
| Audit trail in `LifecycleManager` | Observability | Already records per-task execution; perfect for guardrail verdicts |
| SSE `onTaskStart`/`onTaskComplete` events | Real-time UI feedback | Can emit guardrail block/pass events to frontend |
| Budget controls (`maxBudgetPerConversation`) | Cost guardrail | Already exists and works |

> **Key gap:** EDDI has no mechanism to **reject** a user input before it reaches the LLM, or to **reject and retry** an LLM response that violates rules.

---

## Design Decision: Three Possible Approaches

### Option A: Adopt LangChain4j's `@InputGuardrail` / `@OutputGuardrail` Interfaces

**How it works:** LangChain4j's guardrails are designed for `@AiService` annotated interfaces. They're annotation-driven, tightly coupled to LangChain4j's AI Service layer.

**Problem for EDDI:** EDDI doesn't use `@AiService`. The entire LLM interaction is programmatic via `ChatModel.chat()` and the `AgentOrchestrator`. Adopting LangChain4j's guardrail annotations would require a **fundamental architectural change** to how LLM calls are made.

> **Not recommended.** The impedance mismatch is too large. EDDI's config-driven, multi-tenant architecture doesn't fit annotation-driven guardrails.

---

### Option B: Build a Config-Driven Guardrail Engine Inside `LlmTask` ✅ RECOMMENDED

**How it works:** Add `inputGuardrails` and `outputGuardrails` arrays to the existing `LlmConfiguration.Task` model. Guardrails execute as part of the `LlmTask.executeTask()` method — before the LLM call (input) and after (output). Each guardrail is a config object with a `type` and parameters.

**Fits EDDI because:**
- Stays within the existing config-driven pattern (JSON configs, not annotations)
- Multi-tenant: each agent/task gets its own guardrail profile
- Audit integration is natural — guardrail verdicts go into the same audit trail
- SSE integration is natural — emit `onGuardrailBlock` / `onGuardrailPass` events
- No architectural changes to `LifecycleManager` or the plugin system

```json
{
  "tasks": [{
    "id": "customer-support",
    "type": "openai",
    "inputGuardrails": [
      { "type": "prompt-injection", "action": "block" },
      { "type": "pii-redaction", "action": "redact", "patterns": ["email", "ssn", "creditcard"] },
      { "type": "max-tokens", "limit": 4096 }
    ],
    "outputGuardrails": [
      { "type": "pii-redaction", "action": "redact" },
      { "type": "topic-scope", "action": "reprompt", "maxRetries": 2,
        "allowedTopics": ["product", "billing", "support"] },
      { "type": "toxicity", "threshold": 0.7, "action": "block" }
    ],
    "parameters": { "..." : "..." }
  }]
}
```

---

### Option C: Guardrails as a Separate `ILifecycleTask`

**How it works:** Create a new `GuardrailTask` that sits in the workflow pipeline before and/or after `LlmTask`.

**Problem:** Input guardrails need access to the user message *and* need to be able to prevent the LLM call from executing. But in the lifecycle pipeline, tasks are independent — `GuardrailTask` can't prevent `LlmTask` from running. You'd need to use `STOP_CONVERSATION` which is too coarse, or invent a new "skip next task" mechanism.

**Additionally:** Output guardrails need the LLM response, which only exists inside `LlmTask`. A separate task running after `LlmTask` would need fragile memory-key conventions to find and evaluate the response.

> **Not recommended.** Lifecycle tasks are too decoupled for tight input/output guardrail semantics.

---

## Recommended Architecture (Option B — Detailed)

### New Configuration Model

Add to existing `LlmConfiguration.Task`:

```java
// New fields on Task class
private List<GuardrailConfig> inputGuardrails;
private List<GuardrailConfig> outputGuardrails;
```

```java
public static class GuardrailConfig {
    private String type;          // "pii-redaction", "prompt-injection", "toxicity", "topic-scope", "max-tokens", "regex-filter", "custom-llm"
    private String action;        // "block", "redact", "reprompt", "warn"
    private Integer maxRetries;   // For "reprompt" action (default: 2)
    private Map<String, String> parameters;  // Type-specific params
}
```

### Guardrail Interface & Registry

```java
public interface IGuardrail {
    GuardrailResult evaluate(String content, Map<String, String> parameters);
}

public record GuardrailResult(
    Verdict verdict,           // PASS, BLOCK, REDACT, REPROMPT
    String message,            // Human-readable reason
    String redactedContent     // Only for REDACT verdict
) {
    public enum Verdict { PASS, BLOCK, REDACT, REPROMPT }
}
```

Built-in implementations registered in a `GuardrailRegistry`:

| Type | Implementation | Notes |
|---|---|---|
| `pii-redaction` | Regex-based (SSN, email, CC, phone) | No external dependency |
| `prompt-injection` | Heuristic pattern matching + optional LLM judge | Catches common injection patterns |
| `toxicity` | Delegates to moderation API (OpenAI) or local classifier | Configurable threshold |
| `topic-scope` | LLM-as-judge with allowed topics list | Uses cheap model (mini) |
| `max-tokens` | Token count estimation | Simple, no LLM needed |
| `regex-filter` | Custom regex patterns | For domain-specific rules |
| `custom-llm` | User-defined LLM prompt as guardrail | Maximum flexibility |

### Execution Flow in `LlmTask.executeTask()`

```
1. Build messages from conversation history
2. Run INPUT GUARDRAILS on user message
   → If BLOCK: store audit entry, emit SSE event, return canned error
   → If REDACT: replace content in messages, continue
3. Call LLM (existing code, unchanged)
4. Run OUTPUT GUARDRAILS on LLM response
   → If BLOCK: store audit entry, emit SSE event, return canned error
   → If REPROMPT: add corrective instruction, re-call LLM (up to maxRetries)
   → If REDACT: sanitize response before storing/outputting
5. Store response (existing code, unchanged)
```

### Audit Integration

Each guardrail evaluation writes to the existing audit trail:

```java
// New memory keys for guardrail audit
"audit:guardrail:input:<type>"   → { "verdict": "PASS|BLOCK|REDACT", "reason": "..." }
"audit:guardrail:output:<type>"  → { "verdict": "PASS|BLOCK|REDACT|REPROMPT", "reason": "...", "retryCount": 1 }
```

These get picked up by existing `LifecycleManager.buildAuditEntry()` automatically.

### SSE Events

```
event: guardrail_triggered
data: { "type": "prompt-injection", "phase": "input", "verdict": "BLOCK", "taskId": "customer-support" }
```

---

## Phased Implementation Plan

### Phase 1 — Foundation (~3-4 days)
- `GuardrailConfig` model class + JSON deserialization
- `IGuardrail` interface + `GuardrailRegistry`
- Input/output guardrail execution loop in `LlmTask.executeTask()`
- Two built-in guardrails: `pii-redaction` (regex) + `max-tokens`
- Audit trail integration
- Unit tests for each component

### Phase 2 — Security Guardrails (~2 days)
- `prompt-injection` guardrail (heuristic pattern matching)
- `regex-filter` guardrail (custom patterns)
- Reprompt loop for output guardrails
- SSE event emission

### Phase 3 — LLM-Powered Guardrails (~2 days)
- `toxicity` guardrail (uses OpenAI moderation API or LLM-as-judge)
- `topic-scope` guardrail (LLM-as-judge with cheap model)
- `custom-llm` guardrail (user-defined prompt as validator)

### Phase 4 — Manager UI (~2 days)
- Guardrail configuration editor in the Manager UI
- Guardrail execution log viewer in conversation audit trail

---

## What Competitors Offer (For Positioning)

| Platform | Guardrails? | How? |
|---|---|---|
| **Dify** | Basic | System prompt restrictions only |
| **Flowise** | None | No guardrail system |
| **LangFlow** | Partial | Custom Python code components |
| **Amazon Bedrock Guardrails** | Full | Config-driven, but AWS-only |
| **Azure AI Content Safety** | Full | API-based, Azure-only |
| **EDDI (proposed)** | Full | Config-driven, provider-agnostic, multi-tenant, with audit trail |

> **EDDI's differentiator:** provider-agnostic, config-driven guardrails with per-tenant policies and immutable audit trails. No competitor in the open-source space offers all three.

---

## Files That Would Change

| File | Change |
|---|---|
| `LlmConfiguration.java` | Add `inputGuardrails` / `outputGuardrails` fields + `GuardrailConfig` class |
| `LlmTask.java` | Add guardrail execution before/after LLM call in `executeTask()` |
| **[NEW]** `IGuardrail.java` | Guardrail interface |
| **[NEW]** `GuardrailResult.java` | Result record |
| **[NEW]** `GuardrailRegistry.java` | Registry + execution engine |
| **[NEW]** `PiiRedactionGuardrail.java` | Built-in PII redaction |
| **[NEW]** `PromptInjectionGuardrail.java` | Built-in prompt injection detection |
| **[NEW]** `MaxTokensGuardrail.java` | Built-in token limit |
| **[NEW]** `ToxicityGuardrail.java` | LLM-powered toxicity check |
| **[NEW]** `TopicScopeGuardrail.java` | LLM-powered topic enforcement |
| **[NEW]** `RegexFilterGuardrail.java` | Custom regex patterns |
| **[NEW]** `CustomLlmGuardrail.java` | User-defined LLM prompt as guardrail |
| `ConversationEventSink.java` | Add `onGuardrailTriggered()` event |
