# EDDI Manager UI — Agentic Improvements Handoff

> **Purpose:** Complete specification of all backend config fields added in the `feature/agentic-improvements` branch that need UI support in the EDDI Manager (React 19 + Vite + Tailwind).
>
> All features are **off by default** (backward-compatible). The Manager should expose them as opt-in configuration panels.

---

## Overview

Changes are organized by **where they live** in the JSON config. The Manager edits two primary config types:

| Config Type | REST Endpoint | Manager Page |
|-------------|---------------|--------------|
| **LLM Task** (`langchain.json`) | `GET/PUT /langchainstore/langchains/{id}?version=N` | Workflow Extension → LLM Task editor |
| **Agent** (`AgentConfiguration`) | `GET/PUT /agentstore/agents/{id}?version=N` | Agent editor |
| **Behavior Rules** (`behavior.json`) | `GET/PUT /behaviorstore/behaviorsets/{id}?version=N` | Behavior Rule editor |

---

## Part 1: LLM Task Config (`langchain.json` → per task)

These fields live on each **task** object inside the `tasks` array.

### 1.1 Behavioral Counterweights

**JSON path:** `tasks[n].counterweight`

```json
{
  "counterweight": {
    "enabled": false,
    "level": "normal",
    "placement": "suffix",
    "customInstructions": []
  }
}
```

| Field | Type | Default | Values | UI Widget |
|-------|------|---------|--------|-----------|
| `enabled` | `boolean` | `false` | — | Toggle switch |
| `level` | `string` | `"normal"` | `normal`, `cautious`, `strict` | Dropdown / segmented control |
| `placement` | `string` | `"suffix"` | `prefix`, `suffix` | Dropdown |
| `customInstructions` | `string[]` | `[]` | Free-text list | Chip list / textarea with line-per-item |

**UX Notes:**
- When `enabled` is `false`, collapse the panel (gray out sub-fields)
- When `customInstructions` has entries, show a note: "Custom instructions override the preset level text"
- Consider tooltip: "`strict` is auto-downgraded to `cautious` in scheduled/batch runs"

---

### 1.2 Identity Masking

**JSON path:** `tasks[n].identityMasking`

```json
{
  "identityMasking": {
    "enabled": false,
    "rules": []
  }
}
```

| Field | Type | Default | Values | UI Widget |
|-------|------|---------|--------|-----------|
| `enabled` | `boolean` | `false` | — | Toggle switch |
| `rules` | `string[]` | `[]` | Free-text list | Chip list / textarea |

**UX Notes:**
- Both `enabled: true` **and** at least one rule are required. Show validation warning if `enabled` but `rules` is empty
- Example placeholder: *"Never reveal you are an AI language model"*

---

### 1.3 Tool Response Limits

**JSON path:** `tasks[n].toolResponseLimits`

```json
{
  "toolResponseLimits": {
    "defaultMaxChars": 50000,
    "truncationStrategy": "truncate",
    "summarizerModel": null,
    "perToolLimits": {}
  }
}
```

| Field | Type | Default | Values | UI Widget |
|-------|------|---------|--------|-----------|
| `defaultMaxChars` | `int` | `50000` | 0 = disabled | Number input (with helper: "≈12k tokens") |
| `truncationStrategy` | `string` | `"truncate"` | `truncate`, `paginate`, `summarize` | Dropdown |
| `summarizerModel` | `string` | `null` | Model name | Text input (shown only when strategy = `summarize`) |
| `perToolLimits` | `Map<String, Integer>` | `{}` | tool name → max chars | Key-value table |

**UX Notes:**
- `summarizerModel` field should only be visible when `truncationStrategy` is `"summarize"`
- Show info banner when summarize is selected: *"The summarizer uses the same provider and API key as this task. Only the model name is swapped to a cheaper variant (e.g., gpt-4o-mini)."*
- `perToolLimits` is an editable key-value table: tool name (string) → max characters (int)
- Strategy descriptions for the dropdown:
  - `truncate` — "Hard cut at character limit with note"
  - `paginate` — "Split into pages, LLM fetches more via tool call"
  - `summarize` — "Route through cheap model, fallback to truncate"

---

## Part 2: Agent Config (`AgentConfiguration`)

These fields live on the **agent** object itself.

### 2.1 Capabilities (A2A Discovery)

**JSON path:** `capabilities[]`

```json
{
  "capabilities": [
    {
      "skill": "language-translation",
      "attributes": { "languages": "en,de,fr", "domain": "legal" },
      "confidence": "high"
    }
  ]
}
```

| Field | Type | Default | Values | UI Widget |
|-------|------|---------|--------|-----------|
| `skill` | `string` | — | Free-text (slugified) | Text input |
| `attributes` | `Map<String, String>` | `{}` | Key-value pairs | Key-value table |
| `confidence` | `string` | `"medium"` | `low`, `medium`, `high` | Dropdown |

**UX Notes:**
- Render as a list of cards, each with skill + attributes + confidence
- Add/remove capability buttons
- `skill` is the primary identifier — should be required and non-empty

---

### 2.2 Cryptographic Identity

**JSON path:** `identity`

```json
{
  "identity": {
    "agentDid": "did:eddi:agent-1",
    "publicKey": "MCowBQYDK2Vw...",
    "keys": [
      { "version": 1, "publicKeyB64": "MCowBQYDK2Vw...", "validFrom": null, "validTo": null }
    ]
  }
}
```

| Field | Type | Default | UI Widget |
|-------|------|---------|-----------|
| `agentDid` | `string` | `null` | Text input (read-only after generation) |
| `publicKey` | `string` | `null` | Read-only monospace display |
| `keys` | `AgentPublicKey[]` | `[]` | Read-only table (version, key, valid dates) |

**UX Notes:**
- **Read-only display** — keys are generated via backend API (`AgentSigningService.generateKeyPair()`), not entered manually
- Show a "Generate Keypair" button that calls the backend to create a new key
- Private key is never displayed (stored in SecretsVault)
- If `keys` is empty and `publicKey` is set, show as "Legacy key (v0)"

---

### 2.3 Security Config

**JSON path:** `security`

```json
{
  "security": {
    "signInterAgentMessages": false,
    "requirePeerVerification": false
  }
}
```

| Field | Type | Default | UI Widget |
|-------|------|---------|-----------|
| `signInterAgentMessages` | `boolean` | `false` | Toggle |
| `requirePeerVerification` | `boolean` | `false` | Toggle |

> [!IMPORTANT]
> Both flags are **fully wired** and operational as of v6.0.2. Enabling either requires a valid Ed25519 keypair on the agent's identity block (the backend validates on save). The Manager should show a validation error if the toggle is enabled but no keypair exists.

---

### 2.4 Memory Policy (Commit Flags)

**JSON path:** `memoryPolicy`

```json
{
  "memoryPolicy": {
    "strictWriteDiscipline": {
      "enabled": false,
      "onFailure": "digest"
    }
  }
}
```

| Field | Type | Default | Values | UI Widget |
|-------|------|---------|--------|-----------|
| `strictWriteDiscipline.enabled` | `boolean` | `false` | — | Toggle switch |
| `strictWriteDiscipline.onFailure` | `string` | `"digest"` | `digest`, `exclude_all`, `keep_all` | Dropdown |

**UX Notes:**
- When `enabled` is `false`, collapse or gray out `onFailure`
- Strategy descriptions for dropdown:
  - `digest` — "Hide raw data, inject concise error summary"
  - `exclude_all` — "Hide raw data, no error info"
  - `keep_all` — "Everything visible (backwards-compatible)"

---

### 2.5 Session Management (Checkpoints)

**JSON path:** `sessionManagement`

```json
{
  "sessionManagement": {
    "autoSnapshot": {
      "enabled": false,
      "triggerOn": ["before_tool"]
    },
    "maxCheckpointsPerConversation": 10
  }
}
```

| Field | Type | Default | Values | UI Widget |
|-------|------|---------|--------|-----------|
| `autoSnapshot.enabled` | `boolean` | `false` | — | Toggle switch |
| `autoSnapshot.triggerOn` | `string[]` | `[]` | `before_tool`, `before_action` | Multi-select checkboxes |
| `maxCheckpointsPerConversation` | `int` | `10` | 1–100 | Number input |

**UX Notes:**
- Session forking (`forkingEnabled`, `maxForksPerConversation`) has been **removed** from the config — it will be re-added when the forking service is implemented
- When `autoSnapshot.enabled` is `false`, collapse sub-fields

---

## Part 3: Behavior Rules (`behavior.json`)

Two new condition types are available in the behavior rule editor.

### 3.1 Deployment Context Condition

**Condition type:** `"deploymentContext"`

```json
{
  "type": "deploymentContext",
  "configs": {
    "when": "production",
    "tagMatches": "high-risk"
  }
}
```

| Config Key | Type | Required | Description |
|------------|------|----------|-------------|
| `when` | `string` | Yes | Environment to match (`development`, `staging`, `production`) |
| `tagMatches` | `string` | No | Agent tag to match (from context) |

**UX Notes:**
- Add to the condition type dropdown in the behavior rule editor
- `when` — text input or dropdown with common values
- `tagMatches` — optional text input

---

### 3.2 Capability Match Condition

**Condition type:** `"capabilityMatch"`

```json
{
  "type": "capabilityMatch",
  "configs": {
    "skill": "language-translation",
    "strategy": "highest_confidence",
    "attributes": "domain=legal"
  }
}
```

| Config Key | Type | Required | Description |
|------------|------|----------|-------------|
| `skill` | `string` | Yes | Skill name to match |
| `strategy` | `string` | No (default: `highest_confidence`) | `highest_confidence`, `round_robin`, `all`, `random` |
| `attributes` | `string` | No | Attribute filter (key=value format) |

**UX Notes:**
- Add to the condition type dropdown
- `strategy` — dropdown with the 4 values
- `attributes` — text input with placeholder *"key=value"*

---

## Suggested Manager UI Layout

### LLM Task Editor — New Sections

```
┌─────────────────────────────────────────────────────┐
│ LLM Task: "send_message"                            │
│                                                     │
│ ┌─ Model Configuration ──────────────────────────┐  │
│ │ Type: [openai ▾]  Model: [gpt-4o]             │  │
│ │ API Key: [••••••]  Temperature: [0.7]          │  │
│ └────────────────────────────────────────────────┘  │
│                                                     │
│ ┌─ 🛡️ Behavioral Counterweight ──── [OFF] ──────┐  │
│ │ Level: [cautious ▾]  Placement: [suffix ▾]     │  │
│ │ Custom Instructions: [+ Add instruction]       │  │
│ └────────────────────────────────────────────────┘  │
│                                                     │
│ ┌─ 🎭 Identity Masking ─────────── [OFF] ──────┐  │
│ │ Rules: [+ Add rule]                            │  │
│ └────────────────────────────────────────────────┘  │
│                                                     │
│ ┌─ 📏 Tool Response Limits ──────────────────────┐  │
│ │ Default Max Chars: [50000] (≈12k tokens)       │  │
│ │ Strategy: [truncate ▾]                         │  │
│ │ Summarizer Model: [gpt-4o-mini] ← only if     │  │
│ │                                    summarize   │  │
│ │ Per-Tool Overrides: [+ Add override]           │  │
│ │   webscraper → 2000                            │  │
│ └────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

### Agent Editor — New Tabs/Sections

```
┌─────────────────────────────────────────────────────┐
│ Agent: "Customer Support v2"                        │
│                                                     │
│ [General] [Workflows] [Capabilities] [Security]     │
│          [Memory] [Sessions]                        │
│                                                     │
│ ── Capabilities Tab ────────────────────────────────│
│ ┌ Capability ──────────────────────────────────────┐│
│ │ Skill: [language-translation]                    ││
│ │ Confidence: [high ▾]                             ││
│ │ Attributes: languages=en,de,fr | domain=legal   ││
│ └──────────────────────────────────────────────────┘│
│ [+ Add Capability]                                  │
│                                                     │
│ ── Security Tab ────────────────────────────────────│
│ Identity: did:eddi:agent-1  [Generate Keypair]      │
│ Public Key: MCowBQYDK2Vw... (read-only)             │
│                                                     │
│ ┌ Signing Flags ─────────────────────────────────┐  │
│ │ ✓ Sign inter-agent messages    [ON/OFF]        │  │
│ │ ✓ Require peer verification    [ON/OFF]        │  │
│ │ ⚠ Requires keypair to enable                   │  │
│ └────────────────────────────────────────────────┘  │
│                                                     │
│ ── Memory Tab ──────────────────────────────────────│
│ Strict Write Discipline: [OFF]                      │
│   On Failure: [digest ▾]                            │
│                                                     │
│ ── Sessions Tab ────────────────────────────────────│
│ Auto Snapshot: [OFF]                                │
│   Trigger On: ☑ before_tool  ☐ before_action       │
│ Max Checkpoints: [10]                               │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

## Complete JSON Schema Reference

### LLM Task — new fields on `tasks[n]`

```typescript
interface Task {
  // ... existing fields (type, parameters, actions, etc.)

  counterweight?: {
    enabled: boolean;           // default: false
    level: 'normal' | 'cautious' | 'strict';  // default: 'normal'
    placement: 'prefix' | 'suffix';            // default: 'suffix'
    customInstructions?: string[];
  };

  identityMasking?: {
    enabled: boolean;           // default: false
    rules: string[];
  };

  toolResponseLimits?: {
    defaultMaxChars: number;    // default: 50000
    truncationStrategy: 'truncate' | 'paginate' | 'summarize';  // default: 'truncate'
    summarizerModel?: string;   // required when strategy = 'summarize'
    perToolLimits?: Record<string, number>;  // tool name → max chars
  };
}
```

### Agent Configuration — new fields

```typescript
interface AgentConfiguration {
  // ... existing fields (name, workflows, etc.)

  capabilities?: Array<{
    skill: string;
    attributes?: Record<string, string>;
    confidence?: 'low' | 'medium' | 'high';  // default: 'medium'
  }>;

  identity?: {
    agentDid?: string;
    publicKey?: string;         // read-only, generated by backend
    keys?: Array<{
      version: number;
      publicKeyB64: string;
      validFrom?: number;       // epoch ms
      validTo?: number;         // epoch ms
    }>;
  };

  security?: {
    signInterAgentMessages: boolean;      // default: false, requires keypair
    requirePeerVerification: boolean;     // default: false, requires keypair
  };

  memoryPolicy?: {
    strictWriteDiscipline: {
      enabled: boolean;                   // default: false
      onFailure: 'digest' | 'exclude_all' | 'keep_all';  // default: 'digest'
    };
  };

  sessionManagement?: {
    autoSnapshot?: {
      enabled: boolean;                   // default: false
      triggerOn: string[];                // 'before_tool', 'before_action'
    };
    maxCheckpointsPerConversation: number; // default: 10
  };
}
```

### Behavior Rules — new condition types

```typescript
// Add to condition type dropdown:
type ConditionType =
  | 'inputmatcher'
  | 'actionmatcher'
  | 'contextmatcher'
  | 'connector'
  | 'negation'
  | 'occurrence'
  | 'deploymentContext'    // NEW
  | 'capabilityMatch';     // NEW

// deploymentContext configs:
interface DeploymentContextConfigs {
  when: string;            // environment name (e.g., 'production')
  tagMatches?: string;     // optional agent tag filter
}

// capabilityMatch configs:
interface CapabilityMatchConfigs {
  skill: string;
  strategy?: 'highest_confidence' | 'round_robin' | 'all' | 'random';
  attributes?: string;     // "key=value" format
}
```

---

## Priority Order for Implementation

| Priority | Feature | Effort | Reason |
|----------|---------|--------|--------|
| 🔴 P0 | Tool Response Limits | Small | Most impactful — prevents context blowout, directly visible in task config |
| 🔴 P0 | Counterweights | Small | Simple toggle + dropdown, high value for safety-conscious deployments |
| 🟡 P1 | Identity Masking | Small | Simple toggle + text list, quick win |
| 🟡 P1 | Memory Policy | Small | Toggle + dropdown, very simple |
| 🟡 P1 | Capabilities | Medium | List of cards with attributes, more UI work |
| 🟢 P2 | Session Management | Small | Toggle + checkboxes, but forking is deferred |
| 🟢 P2 | Behavior Conditions | Medium | Extends existing condition editor with 2 new types |
| ⚪ P3 | Cryptographic Identity | Small | Mostly read-only display + one button |
| ⚪ P3 | Security Flags | Small | 2 toggles (active), require keypair validation |
