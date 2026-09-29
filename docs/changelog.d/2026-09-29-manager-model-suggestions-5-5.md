## ✨ feat(manager): Claude 5.5 and GPT-6 Sol/Luna in the model autocompletion (2026-09-29)

**Repo:** EDDI (`feat/manager-model-suggestions-5-5`)

### Why

The model field in the agent wizard and the Platform Operator activation form offers suggestions
from [`model-suggestions.ts`](../../ui/manager/src/lib/model-suggestions.ts). It stopped at
Claude Opus 5 / Sonnet 5 and the `gpt-5.6-*` OpenAI ids, so the newest releases had to be typed by hand.

### What changed

- `anthropic`, `gemini-vertex`: added `claude-opus-5-5` and `claude-sonnet-5-5`, listed first.
- `bedrock`: added `anthropic.claude-opus-5-5` and `anthropic.claude-sonnet-5-5`.
- `openai`: added `gpt-6-sol` and `gpt-6-luna` after `gpt-6-astra`.

### Decisions

- Gemini already led with `gemini-3.8-flash` (stable GA 2026-09-02); no Gemini 3.8 Pro exists, so nothing added.
- No Haiku 5.5: announced 2026-09-22 but no model id published yet.
- `azure-openai` untouched: it lists Azure deployment names, and GPT-6 availability there is unconfirmed.
- No xAI provider in the Manager, so Grok 4.7 is not offered; DeepSeek-V4.1-Flash not added (id unconfirmed).
- Suggestions are hints only; any custom model id can still be typed.
