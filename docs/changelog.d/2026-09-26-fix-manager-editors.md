## 🐛 fix(manager): editors no longer change or drop config data silently (2026-09-26)

**Repo:** EDDI (`fix/manager-editors`)

Fixes the "Editors: data silently changed or dropped" section of the 2026-09-25 UI review, plus
known findings U1 and U2. Manager-only; no backend, REST or stored-config shape changes.

### What changed and why

- **HTTP code filter** (`apicalls-editor.tsx`). "Add HTTP Code Filter" wrote `runOnHttpCode: []`.
  `PrePostUtils` substitutes the defaults only for a *missing* list, and `[]` contains no status
  code, so the guarded instruction never ran again. It now writes `{}`; an emptied field is
  written as absent, never `[]`; the placeholders show the engine defaults; and a stored `[]` is
  flagged with a "Use defaults" repair.
- **Parameter and argument names** (`llm-editor.tsx`, `mcpcalls-editor.tsx`, new
  `renamable-key-input.tsx`). The key inputs were read-only, so an added LLM model parameter was
  stuck as `param<n>` and an MCP argument as `arg<n>`. Names are now editable, committed on blur
  or Enter, and refused (marked invalid, reverted) when empty, taken, or — for model
  parameters — a key another section owns (`systemMessage`). New keys use the first free name
  (`nextFreeKey`) instead of a count that could collide and overwrite a value.
- **MCP argument types** (`mcpcalls-editor.tsx`). Values were edited as `String(v)` and written
  back as strings: `5` became `"5"`, an object `"[object Object]"`. `McpCallsTask` templates
  strings and passes every other JSON value through, so a row now has a Text / JSON kind; JSON
  rows keep their type and store only valid JSON. The placeholder is `{memory.current.input}`
  (was `{{memory.input}}` — wrong syntax and wrong path), and "Save Response" shows the
  backend's default of `true`.
- **Controls for fields the backend removed or never reads**: "Parallel Tool Execution" and its
  timeout (removed from `LlmConfiguration.Task`), the RAG "Injection" selects (removed from
  `KnowledgeBaseReference` / `ragDefaults`), "Paragraph" / "Sentence" chunking (rewritten to
  `recursive` on save — a stored legacy value is still shown, disabled, as "saved as Recursive"),
  and "Batch Calls" (`ApiCall.isBatchCalls` / `iterationObjectName` are read by nothing; batching
  is `preRequest.batchRequests`). Stored values are left untouched in the JSON.
- **sizematcher preset** (`rules-editor.tsx`) wrote `min:""`, `max:""`; `SizeMatcher.setConfigs`
  parses each with `Integer.parseInt`, so the save failed with a 400. It now presets
  `{ valuePath: "", min: "1" }`.
- **Manual document ingestion with unsaved edits** (`rag-editor.tsx`). The ingest endpoint uses
  the *saved* knowledge base, so documents went into the old store with the old embedding model.
  The panel now refuses (drop zone, file picker and "Ingest Text" disabled, with a notice) while
  the editor is dirty, as the sources panel already did.
- **Crash guards**. A `null` HTTP call `request` (`apicalls-editor.tsx`), an output set without
  `outputs` or a group without `valueAlternatives` (`output-editor.tsx`), and a property setter
  without `actions` / `setProperties` (`propertysetter-editor.tsx`) threw during render and took
  the whole page — and its unsaved edits — down through the root error boundary.
- **Secret scope** (UI side of C2b). HTTP/MCP property instructions are stored by `PrePostUtils`
  under their scope as-is, so `secret` there kept the value in plain text. It is no longer offered
  on those rows; a stored `secret` stays visible (disabled option) with a warning. The property
  setter warns when `secret` is combined with a "From path", which `autoVaultSecret` does not
  cover.
- **Hints**. The snippet usage hint showed `{{snippets.name}}`, which Qute renders literally; it is
  now `{snippets.name}` (and the "template resolution" hint says `{ }`, in all 11 locales).
- **Number inputs** (new `number-input.tsx`). `value={x ?? d}` with `parseInt(v) || d` wrote the
  fallback back into a cleared field, so the next keystroke was appended ("-1" → "-18"). The
  editors' number fields keep the typed text locally and store `undefined` (the backend default,
  shown as the placeholder) or an explicit empty value.
- **Monaco schema scope** (`json-editor.tsx`). One schema was registered with `fileMatch: ["*"]`,
  so it validated every JSON model on the page, and the next editor to mount replaced it. Each
  editor now has its own model path, its schema is matched to that path only, and it is removed
  on unmount.
- **Version diff race** (`version-diff-dialog.tsx`). Fetches started during render and every
  response was applied, so a slow earlier response could overwrite the pair asked for last. The
  fetch runs in an effect and only the latest request writes; a fresh `fetchVersion` arrow from
  the parent no longer refetches.
- **U1** (`ingestion-sources-panel.tsx`). Switching a *saved* Files source to Website (click or
  arrow key) now asks first: on save `discardRemovedSources` deletes its files and vectors, the
  same loss removing it already confirmed.
- **U2** (`use-ingestion-sources.ts`, `ingestion-files-panel.tsx`). The file list is invalidated
  when a run starts, refetched when a run finishes, invalidated after a purge, and refetched after
  a retried upload; a failed list load is shown with a retry instead of "No files yet"; and the
  "Run now" in the files banner is not offered on a disabled source.

### Not done here

- "Deselecting the last built-in tool enables all tools" (same Editors section) belongs to
  `fix/manager-chat` (wizard + LLM editor tools whitelist) and is left to that branch.
- `DebouncedNumberInput` in `agent-config-sections.tsx` has the same append-after-clear pattern
  once its 600 ms commit fires; that file is owned by `fix/manager-version-after-save`, so it is
  left for that branch (the new `NumberInput` is the drop-in fix).
- A per-editor error boundary (so an *unforeseen* shape costs one editor, not the page) is the
  shell work in `fix/manager-shell`.

**Files:** [`apicalls-editor.tsx`](../../ui/manager/src/components/editors/apicalls-editor.tsx),
[`mcpcalls-editor.tsx`](../../ui/manager/src/components/editors/mcpcalls-editor.tsx),
[`llm-editor.tsx`](../../ui/manager/src/components/editors/llm-editor.tsx),
[`rag-editor.tsx`](../../ui/manager/src/components/editors/rag-editor.tsx),
[`json-editor.tsx`](../../ui/manager/src/components/editors/json-editor.tsx),
[`version-diff-dialog.tsx`](../../ui/manager/src/components/editors/version-diff-dialog.tsx),
[`ingestion-sources-panel.tsx`](../../ui/manager/src/components/editors/ingestion-sources-panel.tsx),
[`ingestion-files-panel.tsx`](../../ui/manager/src/components/editors/ingestion-files-panel.tsx),
[`number-input.tsx`](../../ui/manager/src/components/editors/number-input.tsx),
[`renamable-key-input.tsx`](../../ui/manager/src/components/editors/renamable-key-input.tsx),
[`editor-value-utils.ts`](../../ui/manager/src/components/editors/editor-value-utils.ts).
