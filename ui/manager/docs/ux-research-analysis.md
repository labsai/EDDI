# UX Research Analysis — Impact on Phases 3.14–3.19

> **Date:** 2026-03-06  
> **Source:** Deep research on AI middleware UX/UI across Voiceflow, n8n, Langflow, Dify, Agentpress, LangSmith, Make.com  
> **Purpose:** Validate and refine the editing layer plan before implementation

---

## Executive Summary

The research **validates and strengthens** the existing Phase 3.14–3.19 plan rather than overturning it. EDDI's backend architecture (sequential pipeline, `IConversationMemory` snapshots, config-as-code) is already well-positioned. The research gives us concrete UI patterns to implement _within_ the existing phase structure.

---

## ✅ Already Aligned — No Changes Needed

| Research Finding                                          | Our Existing Plan                                           | Verdict               |
| --------------------------------------------------------- | ----------------------------------------------------------- | --------------------- |
| **Monaco Editor** for JSON editing with schema validation | Phase 3.14 `json-editor.tsx` with `@monaco-editor/react`    | ✅ Perfect match      |
| **Version control** with visual diffing                   | Phase 3.14 `version-picker.tsx`                             | ✅ Already planned    |
| **Drag-and-drop pipeline builder**                        | Phase 3.16 `pipeline-builder.tsx` with `@dnd-kit/core`      | ✅ Already planned    |
| **Extension-specific form editors**                       | Phases 3.17–3.18 (Behavior, HTTP Calls, LangChain, etc.)    | ✅ Already planned    |
| **NOT building a free-floating node graph**               | EDDI's sequential pipeline is a vertical list, not a canvas | ✅ Strongly validated |

---

## 🔄 Refinements — Same Phases, Better Patterns

### 1. Synchronized Dual-Interface (Phase 3.14)

**Research says:** Every config panel needs a seamless `[ Visual Form | { } JSON ]` toggle. Editing JSON must update the form and vice versa.

**Recommended change:** Add the synchronized toggle to `config-editor-layout.tsx` (Phase 3.14) as first-class. The layout manages a single state object and renders either the form or Monaco.

```
config-editor-layout.tsx
├── Header (name, version picker, save/cancel, dirty indicator)
├── Tab bar: [ Form | JSON ]
├── Form view → extension-specific editor component (3.17–3.18)
└── JSON view → Monaco editor with schema validation (3.14)
    both read/write the SAME reactive state object
```

### 2. Side-Sheet Inspector Instead of Navigation (Phase 3.16)

**Research says:** Never use overlapping modals. Use sliding side sheets so the parent canvas stays visible.

**Current plan says:** "Click extension → navigates to the extension-specific editor." This is full page navigation, losing pipeline context.

**Recommended change:** When clicking an extension in the pipeline, open its editor in a **`shadcn/sheet`** sliding panel from the right, keeping the pipeline visible underneath.

> **Key decision:** Build each editor as a self-contained React component that accepts `resourceId` and `version` props. Then:
>
> - In the **pipeline builder** (Phase 3.16), render it inside a `shadcn/sheet`
> - Via **direct URL** (`/manage/resources/:type/:id`), render it as a full page

### 3. Variable Autocomplete with `cmdk` (Phase 3.17–3.18)

**Research says:** Typing fragile string paths like `[[${memory.current.input}]]` is brittle. Use a command palette.

**Recommended change:** In template fields (HTTP Call URLs, body templates, property setter values), typing `[[` triggers a `shadcn/ui` Command palette showing available memory keys. Nice-to-have for initial 3.17–3.18; can polish in 3.19.

---

## 🆕 New Items for Existing Phases

### Agent Environment Status Badges (Phase 3.15)

**Research says:** Don't show duplicate agent cards per environment. Show one card per agent with environment columns/badges.

**Recommended change:** In Phase 3.15, enhance agent cards to show Dev/Test/Prod deployment status as badges within a single card rather than duplicating cards.

---

## ❌ Deferred to Phase 4+

| Feature                                                  | Why Defer                                                                                                          |
| -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| **"Time-Traveling IDE" debugger** with context snapshots | Requires new backend endpoints (compiled prompt snapshots, token probabilities)                                    |
| **Interactive HITL step-through** (Pause/Edit/Resume)    | `rerunLastConversationStep` exists but full pause/resume requires orchestration changes                            |
| **Semantic Visual Diffing** on the canvas                | Build the editing layer first, add diffing later                                                                   |
| **Linear/Block Hybrid Canvas** with React Flow           | Phase 3.16 pipeline builder is a vertical list. Full React Flow canvas for multi-package orchestration is Phase 5+ |
| **AI-assisted debugging** (NL trace queries)             | Requires significant backend infrastructure                                                                        |
| **Actionable cost telemetry** dashboard                  | `ToolCostTracker` exists but no REST endpoint for aggregated data yet                                              |

---

## Revised Phase Descriptions

| Phase    | Refined Description                                                                                |
| -------- | -------------------------------------------------------------------------------------------------- |
| **3.14** | JSON Editor + Version Picker + **`config-editor-layout.tsx` with Form↔JSON toggle architecture**   |
| **3.15** | Agent Editor + **Environment Status Badges** (not duplicate cards)                                 |
| **3.16** | Workflow Editor + **Side-sheet extension inspector** (instead of page navigation)                  |
| **3.17** | Behavior + HTTP Calls Editors as **sheet-embeddable components** + optional `cmdk` variable picker |
| **3.18** | LangChain + Output + PropSetter + Dict as **sheet-embeddable components**                          |
| **3.19** | Polish, i18n, Tests + `cmdk` autocomplete polish                                                   |

Phase ordering unchanged: 3.14 → 3.15 → 3.16 → 3.17/3.18 → 3.19
