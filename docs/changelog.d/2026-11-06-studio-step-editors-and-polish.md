## fix(manager): every workflow step opens in the Agent Studio, and a denser pipeline list (2026-11-06)

**Repo:** EDDI (`fix/studio-step-editors-and-polish`)

### Why

Selecting an `eddi://ai.labs.httpcalls` step in the Agent Studio showed "Editor not available
for this extension type". That is the API-call task's *canonical* step type
(`ApiCallsTask.ID = "ai.labs.httpcalls"`; `ai.labs.apicalls` is the alias), so an agent with
thirty API-call steps had thirty uneditable stages. The same happened to steps still typed
`ai.labs.langchain` (registered by `LlmModule` as an alias of `ai.labs.llm`).

**Root cause:** the Studio resolved step → editor through its own partial type table
(`EXTENSION_TO_SLUG` in `editor-registry.tsx`), while the workflow page resolved the same step
by the store segment of its config URI (`workflow-step-links.ts`, with a third copy of the
store table) and the add-extension dialog and orphans page used a fourth
(`EXTENSION_TO_RESOURCE_SLUG`). The Studio's copy lacked both aliases.

### What changed

- **One resolution path.** `getResourceSlugForStep` in
  [`ui/manager/src/lib/api/extensions.ts`](../../ui/manager/src/lib/api/extensions.ts) decides by
  the config URI's store segment (`apicallstore` → `apicalls`, through `RESOURCE_TYPES` and the new
  `getResourceTypeByStore`), and only falls back to the step type for a URI without that shape.
  The Studio uses it; `EXTENSION_TO_SLUG` is gone; `workflow-step-links.ts` reads the same store
  table instead of its own copy. `ai.labs.langchain` was added to the type tables.
- **Store-less steps get an informative state instead of a warning.** Templating explains what it
  does; a parser with an embedded config shows that config read-only; a step of a store-backed
  type with no URI says to attach a resource; an unknown type shows its stored config read-only.
  Each links to the workflow page (`/manage/workflowview/:id?version=N`), where steps are attached
  and inline configs are edited.
- **Pipeline list (`pipeline-railroad.tsx`).** Compact rows with the pipeline position, the
  resource's descriptor NAME (read per resource through the descriptor store, shared cache key
  with the parser editor), the id in the tooltip; consecutive steps of one type are grouped under
  one header with a count; the connector lines and per-card gaps are gone; the type label now
  comes from the shared `extensionTypes.*` labels, so it matches the workflow page.
- **Studio chrome.** Pipeline header with a step count and proper padding, larger workflow
  switcher, wider default pipeline column (288px), editor panel padded `px-6 py-5`, the shared
  `EmptyState` for "select a stage", the chat toggle as a `Button` with an `aria-label`.
- **i18n.** New `studio.inlineConfig` and `studio.stepInfo.*` keys in all 11 locales; the unused
  `studio.type.*`, `studio.noConfig` and `studio.unsupportedType` keys were removed.

### Step types and how the Studio handles them

| Step type (backend registration) | Handling |
| --- | --- |
| `ai.labs.httpcalls` (ApiCallsTask.ID), `ai.labs.apicalls` (alias) | API Calls editor (`apicallstore`) |
| `ai.labs.behavior` (RulesEvaluationTask.ID), `ai.labs.rules` (alias) | Rules editor |
| `ai.labs.llm`, `ai.labs.langchain` (alias) | LLM editor |
| `ai.labs.property` | Property Setter editor |
| `ai.labs.output` | Output editor |
| `ai.labs.mcpcalls` | MCP Calls editor |
| `ai.labs.rag` | RAG editor |
| `ai.labs.parser` with a URI / with an embedded config | Parser editor / read-only inline config + link to the workflow page |
| `ai.labs.templating` | Explanation (no config of its own) + link |
| anything else | Read-only stored config + link |

### Decisions

- The URI's store wins over the step type: it names the store the task really reads, whatever
  spelling the workflow carries. Not editing a parser's inline config in the Studio itself — that
  needs a workflow-document save, which the Studio's cascade does not do; the workflow page does.
- The "API Calls (legacy)" label for `ai.labs.httpcalls` is untouched (being decided separately);
  the pipeline list now shows it, as the workflow page already did.

### Verification

`npm run lint`, `npm run typecheck`, `npm run i18n:check` clean; Studio, editor-registry,
extensions, workflow, i18n and page suites pass. Mutation check: removing the store lookup from
`getResourceSlugForStep` fails the httpcalls/store tests. Checked in the browser against the MSW
mock with a 20-step pipeline.
