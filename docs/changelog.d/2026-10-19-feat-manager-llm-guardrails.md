## 🛡️ feat(manager): LLM input/output guardrails editor and `guardrail_triggered` chat notice (2026-10-19)

**Repo:** EDDI (`feat/manager-llm-guardrails`, based on `feat/llm-input-output-guardrails`, PR #1064, because that PR had not merged when this was written)

The backend shipped input/output guardrails on LLM tasks with only a TypeScript type mirror in the Manager, so designers had to edit JSON. This adds the editor and surfaces the new SSE event.

### What changed

- **Guardrails section on the LLM task editor** ([`task-guardrails-section.tsx`](../../ui/manager/src/components/editors/llm/task-guardrails-section.tsx)): two ordered lists, `inputGuardrails` and `outputGuardrails`. Each row shows type and action, and can be reordered (order is run order), edited or removed. Each guardrail is edited in a side sheet ([`guardrail-sheet.tsx`](../../ui/manager/src/components/editors/llm/guardrail-sheet.tsx)) so the task editor itself stays one flat list.
- **The sheet** edits type, action (with a one-line explanation per action and phase; `reprompt` is not offered on the input side, where the engine treats it as `block`), `maxRetries` (only while the action is `reprompt`), `blockMessage`, `failMode`, `repromptMessage`, and the type's own parameters: `prompt-injection` (extra patterns, mode), `pii-redaction` (kinds, replacement), `regex-filter` (patterns, replacement), `max-length` (`maxChars`), `json-object` (none), and `classifier` (provider picker reusing `ProviderSelect`, base URL, model under the key the chosen provider reads, API key through `SecretKeyPicker` so it is a `${vault:…}` reference, risk, prompt template, unsafe/safe label, label pattern, timeout, prices, retrieved-context toggle on the output side). Warnings for: classifier without a provider, classifier `unsafeLabel` other than `yes` without a `safeLabel` (the deploy is refused), `redact` on a type with nothing to rewrite with, `failMode: open` on a blocking guardrail, a regex filter with no pattern, a type on the wrong side.
- **Streaming caveat**: an output guardrail whose action is `block`, `redact`, `reprompt` (or absent, which is `block`) makes the task buffer its answer; the section says so in amber while that is the case, and plainly otherwise.
- **Chat**: `guardrail_triggered` is handled in `use-chat` and attached to the agent bubble of the turn; [`ChatMessage`](../../ui/manager/src/components/chat/chat-message.tsx) shows one quiet line per fired guardrail (phase, type, action). The payload carries no screened text. The standalone Chat UI (`ui/chat`) was left alone: it is an end-user widget whose block message already is the answer, and it surfaces no other internal pipeline events as notices.
- i18n: 109 keys (`llmEditor.gr*`, `chat.guardrail*`) in all 11 locales.

### Design decisions

- **Write only what the author sets.** A new guardrail is `{ "type": … }` with nothing else; blanking a field removes it, an emptied `parameters` map is dropped, an emptied list removes `inputGuardrails`/`outputGuardrails` rather than writing `[]`, and moving off `reprompt` drops `maxRetries`. An older server's strict parser refuses unknown fields (same rule as `task-tool-call-limits.tsx`).
- All `parameters` stay plain strings, as the backend reads them; an unknown `type` and any unknown parameter are preserved untouched.
- The classifier model key follows `modelParamSpec(provider)`, and switching provider moves the model and credential to the new provider's keys.
- Edits apply live (no draft/apply step) because the list behind the sheet reflects them and the editor already has its own save/dirty handling.

### Not done / next

- The `classifier` type exists on `feat/llm-classifier-guardrail`, not yet in a PR. Until that backend lands the editor does not offer it (it is left out of `GUARDRAIL_TYPES`); a stored `classifier` entry still shows as a custom type and keeps its settings panel. The classifier PR adds it back.
- The operator system prompt is unchanged: its guardrail text was already added with the backend PRs.
- Live-turn notices only: a conversation reloaded from history does not show them (the audit ledger holds the record).
