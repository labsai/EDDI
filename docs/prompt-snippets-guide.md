# Prompt Snippets — Usage Guide

> Prompt Snippets are reusable system prompt building blocks stored as versioned configuration documents. They replace the deleted `CounterweightService`, `IdentityMaskingService`, and `DeploymentContextService` with a flexible, user-extensible, config-driven approach.

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
You are a helpful customer service agent for {{properties.company_name.valueString}}.

{{snippets.cautious_mode}}

Always respond in {{properties.preferred_language.valueString}}.
```

That's it. The snippet content is automatically injected at template resolution time.

---

## How It Works

### Auto-Loading

All snippets are loaded from MongoDB at LLM task execution time and injected into the template data map under the `snippets` namespace. This happens **before** the Jinja2 template engine processes the system prompt, so `{{snippets.xxx}}` resolves like any other template variable.

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
- After creating/updating/deleting a snippet, changes appear within 5 minutes
- For immediate effect, restart the server or call `invalidateCache()` programmatically
- Cache hit/miss metrics are exposed at `/q/metrics` as `eddi.snippets.cache.hits` and `eddi.snippets.cache.misses`

### Name Validation

Snippet names **must** match the pattern `[a-z0-9_]+`:

| Valid ✅ | Invalid ❌ |
|---|---|
| `cautious_mode` | `CautiousMode` (uppercase) |
| `safety_rules` | `with-dash` (hyphen) |
| `tone_formal` | `with.dot` (dot) |
| `rule_42` | `with space` (space) |

This ensures safe Jinja2 dot-notation access (`{{snippets.name}}`).

---

## Template Control

### `templateEnabled` (default: `true`)

Controls whether the Jinja2 template engine resolves template markers inside the snippet content.

**When `true` (default):** Template variables in the snippet are resolved against the full template data map. This allows snippets to be dynamic:

```json
{
  "name": "personalized_greeting",
  "content": "Address the user as {{properties.preferred_name.valueString}} and respond in {{properties.preferred_language.valueString}}.",
  "templateEnabled": true
}
```

**When `false`:** Template markers (`{{`, `}}`) are treated as literal text. The content is wrapped in Jinja2 `{% raw %}...{% endraw %}` blocks automatically. This is useful for code examples or documentation snippets:

```json
{
  "name": "code_example_instructions",
  "content": "When showing code examples, use the format: {{variable_name}} for placeholders.",
  "templateEnabled": false
}
```

### Inline Override

Even when `templateEnabled` is `true`, you can protect specific sections using Jinja2 raw blocks directly in the content:

```json
{
  "name": "mixed_content",
  "content": "Hello {{properties.name.valueString}}! {% raw %}Use {{placeholder}} in templates.{% endraw %}",
  "templateEnabled": true
}
```

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

Usage: `{{snippets.cautious_mode}}`

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
  "content": "DATA PRIVACY: You are operating under GDPR regulations. Never store or repeat personal data beyond the current conversation unless the user explicitly consents. If asked about data handling, refer to our privacy policy at {{properties.privacy_policy_url.valueString}}.",
  "tags": ["compliance", "gdpr", "eu"],
  "templateEnabled": true
}
```

### Dynamic — Context-Aware Routing

```json
{
  "name": "routing_context",
  "category": "custom",
  "description": "Injects department-specific instructions from properties",
  "content": "You are handling inquiries for the {{properties.department.valueString}} department. Follow these department-specific guidelines:\n{{properties.department_guidelines.valueString}}",
  "tags": ["routing", "dynamic"],
  "templateEnabled": true
}
```

---

## Composing a Full System Prompt

Snippets give you full control over prompt composition order:

```
{{snippets.tone_formal}}

You are a customer service agent for Acme Corp.

{{snippets.cautious_mode}}

{{snippets.gdpr_notice}}

Your specialization is {{properties.specialization.valueString}}.

Important context:
{{snippets.routing_context}}
```

The designer controls exactly where each snippet appears, enabling precise prompt engineering.

---

## Migration from Legacy Services

| Legacy Service | Snippet Replacement |
|---|---|
| `CounterweightService` | Create a `cautious_mode` snippet with your safety instructions |
| `IdentityMaskingService` | Create a `persona_instructions` snippet with masking rules |
| `DeploymentContextService` | Create environment-specific snippets (`prod_rules`, `staging_rules`) |

The key advantage: snippets are **user-configurable** without code changes, versionable, and composable.
