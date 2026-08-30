# Prompt Snippets — Usage Guide

> Prompt Snippets are reusable system prompt building blocks stored as versioned configuration documents. They replace the deleted `DeploymentContextService` with a flexible, user-extensible, config-driven approach, and supply the customisable preset text for the counterweight and identity-masking features described in [langchain.md → Behavioral Safety](langchain.md#behavioral-safety-counterweight--identity-masking).

## Quick Start

### 1. Create a Snippet via REST API

```bash
POST /snippetstore/snippets
Content-Type: application/json

{
  "name": "cautious_mode",
  "category": "governance",
  "description": "Instructs the agent to verify facts before responding",
  "content": "IMPORTANT: You must always verify facts before responding. If you are unsure about something, say so explicitly rather than guessing. Never fabricate information.",
  "tags": ["safety", "production"],
  "templateEnabled": true
}
```

### 2. Use in a System Prompt

Reference the snippet in your LLM task's system prompt template:

```
You are a helpful customer service agent for {properties.company_name}.

{snippets.cautious_mode}

Always respond in {properties.preferred_language}.
```

That's it. The snippet content is automatically injected at template resolution time.

---

## How It Works

### Auto-Loading

All snippets are loaded from MongoDB at LLM task execution time and injected into the template data map under the `snippets` namespace. This happens **before** the Qute template engine processes the system prompt, so `{snippets.xxx}` resolves like any other template variable.

```
Template Data Map:
├── context       → input context variables
├── properties    → conversation properties  
├── memory        → conversation step data
├── snippets      → ← ALL snippets auto-injected here
│   ├── cautious_mode       → "IMPORTANT: You must..."
│   ├── persona_formal      → "Use formal language..."
│   └── compliance_gdpr     → "You must comply with..."
├── userInfo      → authenticated user
└── conversationLog → formatted history
```

### Caching

Snippets are cached in a Caffeine cache with a **5-minute TTL**. This means:

- Snippets load once from MongoDB, then serve from cache
- `POST`/`PUT`/`DELETE` on `/snippetstore/snippets` invalidate the cache synchronously, so a snippet edited through the REST API takes effect on the next LLM turn
- The 5-minute TTL is the fallback for changes made outside the REST API (a direct database write, or another node)
- Cache hit/miss metrics are exposed at `/q/metrics` as `eddi.snippets.cache.hits` and `eddi.snippets.cache.misses`

### Name Validation

Snippet names **must** match the pattern `[a-z0-9_]+`:

| Valid ✅ | Invalid ❌ |
|---|---|
| `cautious_mode` | `CautiousMode` (uppercase) |
| `safety_rules` | `with-dash` (hyphen) |
| `tone_formal` | `with.dot` (dot) |
| `rule_42` | `with space` (space) |

This ensures safe Qute dot-notation access (`{snippets.name}`).

---

## Template Control

### `templateEnabled` (default: `true`)

Intended to control whether the Qute template engine resolves template markers inside the snippet content. **It is currently inert.**

Snippet content is injected verbatim and is never template-resolved, whatever `templateEnabled` says. A snippet reaches a prompt as a template *data value* — `{snippets.name}` resolves to it — and Qute does not re-parse what an expression resolved to. Any `{...}` inside the content therefore reaches the model as literal text:

```json
{
  "name": "code_example_instructions",
  "content": "When showing code examples, use the format: {variable_name} for placeholders.",
  "templateEnabled": false
}
```

That is exactly the `templateEnabled: false` guarantee, and every snippet gets it for free — no unparsed-block wrapping is applied or needed. The corollary is that `templateEnabled: true` does **not** make `{properties.x}` inside a snippet resolve either; it too reaches the model literally. Snippets cannot be made dynamic this way — put the dynamic parts in the system prompt template itself, around the `{snippets.name}` reference.

The field is kept because stored configs carry it and because honouring it remains a live option; see the `PromptSnippetService` class javadoc for what enabling it would cost.

---

## REST API Reference

All endpoints require `eddi-admin` or `eddi-editor` role.

| Method | Path | Description |
|---|---|---|
| `GET` | `/snippetstore/snippets/descriptors` | List snippet descriptors (paginated) |
| `GET` | `/snippetstore/snippets/{id}?version=1` | Read a snippet |
| `POST` | `/snippetstore/snippets` | Create a snippet |
| `PUT` | `/snippetstore/snippets/{id}?version=1` | Update a snippet |
| `DELETE` | `/snippetstore/snippets/{id}?version=1` | Delete a snippet |

### Query Parameters for Descriptors

| Param | Default | Description |
|---|---|---|
| `filter` | `""` | Filter by name (substring match) |
| `index` | `0` | Pagination offset |
| `limit` | `20` | Max results |

---

## Model Reference

```json
{
  "name": "string (required, [a-z0-9_]+)",
  "category": "string (optional: governance, persona, compliance, custom)",
  "description": "string (optional, for UI gallery)",
  "content": "string (required, the prompt text)",
  "tags": ["string array (optional, for filtering)"],
  "templateEnabled": "boolean (default: true)"
}
```

---

## Example Snippets

### Governance — Cautious Mode

```json
{
  "name": "cautious_mode",
  "category": "governance",
  "description": "Instructs the agent to verify facts and avoid fabrication",
  "content": "CRITICAL SAFETY INSTRUCTION: You must always verify facts before responding. If uncertain, explicitly state your uncertainty. Never fabricate citations, statistics, or technical details. If a question is outside your knowledge, redirect the user to appropriate resources.",
  "tags": ["safety", "production", "enterprise"],
  "templateEnabled": true
}
```

Usage: `{snippets.cautious_mode}`

### Persona — Formal Tone

```json
{
  "name": "tone_formal",
  "category": "persona",
  "description": "Enforces formal business communication style",
  "content": "COMMUNICATION STYLE: Use formal, professional language at all times. Address users respectfully. Avoid slang, contractions, and casual expressions. Structure responses with clear headings when appropriate.",
  "tags": ["persona", "enterprise"],
  "templateEnabled": true
}
```

### Compliance — GDPR Notice

```json
{
  "name": "gdpr_notice",
  "category": "compliance",
  "description": "GDPR-compliant data handling instructions",
  "content": "DATA PRIVACY: You are operating under GDPR regulations. Never store or repeat personal data beyond the current conversation unless the user explicitly consents. If asked about data handling, refer the user to our published privacy policy.",
  "tags": ["compliance", "gdpr", "eu"],
  "templateEnabled": true
}
```

### Routing — Escalation Rules

```json
{
  "name": "routing_context",
  "category": "custom",
  "description": "Department handover and escalation rules",
  "content": "ROUTING: Handle only inquiries that belong to your assigned department. If a request belongs elsewhere, say so and hand it over rather than guessing. Escalate to a human agent whenever the user asks for one.",
  "tags": ["routing"],
  "templateEnabled": true
}
```

---

## Composing a Full System Prompt

Snippets give you full control over prompt composition order:

```
{snippets.tone_formal}

You are a customer service agent for Acme Corp.

{snippets.cautious_mode}

{snippets.gdpr_notice}

Your specialization is {properties.specialization}.

Important context:
{snippets.routing_context}
```

The designer controls exactly where each snippet appears, enabling precise prompt engineering.

---

## Migration from Legacy Services

| Legacy Service | Snippet Replacement |
|---|---|
| `DeploymentContextService` | Create environment-specific snippets (`prod_rules`, `staging_rules`) |

`CounterweightService` and `IdentityMaskingService` were **not** removed — they are still applied to every system prompt and are configured per LLM task via the `counterweight` and `identityMasking` blocks in [langchain.md → Behavioral Safety](langchain.md#behavioral-safety-counterweight--identity-masking). Counterweight preset text is itself customised by creating `counterweight-cautious` / `counterweight-strict` snippets.

The key advantage: snippets are **user-configurable** without code changes, versionable, and composable.
