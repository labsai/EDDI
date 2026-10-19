## feat(manager): model picker that always shows the whole catalog, and a refreshed model list (2026-10-17)

**Repo:** EDDI (`feat/manager-model-picker`)

### What changed and why

**The model field no longer hides the catalog.** The agent wizard, the LLM
editor and the operator activation form used a native `<datalist>`, which
filters its options by whatever the input holds. The field is pre-filled with
the provider's default, so clicking it showed one option, the default, and read
as "this provider has one model". The new `ModelCombobox`
(`ui/manager/src/components/shared/model-combobox.tsx`) replaces all three:

- A click, the chevron or ArrowDown opens the whole list with the current value
  ticked and scrolled into view. The provider default carries a `default` badge.
- Typing puts matches under a "Matching …" heading and keeps every other
  suggestion below a divider under "Other models". Matching ignores case and
  separators, so `opus 5.5` finds `claude-opus-5-5`.
- When nothing matches, a note says the typed id is used as typed. A footer
  repeats that any id the provider supports can be typed. The suggestions are
  help, not a whitelist.
- It follows the ARIA combobox pattern (`aria-activedescendant`, grouped
  listbox). Escape closes only the list, not the dialog around it. A read-only
  field gets no list.

The grouping lives in `groupModelSuggestions` in `lib/model-suggestions.ts`.
The 7 new `modelCombobox.*` strings are translated into all 11 locales.

**Catalog refresh.** Every id was checked against the provider's model and
deprecation pages on 2026-10-17.

- **Added:**
  - `claude-haiku-5-5` (Anthropic, Vertex, Bedrock)
  - `gemini-4-argon` (Gemini, Vertex, OpenRouter)
  - The GPT-6 family on Azure and Bedrock
  - `mistral-large-4`
  - `grok-build-0.1`
  - Grok, Gemini and Llama 4 Scout on OCI
  - Gemma 4, Qwen 3.5/3.6, Ministral 3 and gpt-oss on Ollama
  - Popular OpenRouter slugs
- **Removed:**
  - OpenAI: `o3-mini` (shut down 2026-10-23), `gpt-5.4-nano`, the `gpt-5`
    family, and `gpt-5.4-thinking`, which was never an API id
  - Gemini: `gemini-3.7-flash` and `gemini-3.5-flash`, both deprecated on
    AI Studio
  - Vertex: `gemini-2.5-*`, retiring 2026-10-20
  - MiniMax: the M2.7 pair (retiring 2026-11-18). M3.1-Flash-Preview stays,
    because the parameter catalog has rules for it, though MiniMax currently
    offers it only on its subscription plans
  - Bedrock: models now Legacy or past end of life (Llama 3.x/4, MiniMax M2)
  - Azure: deprecated gpt-4o and gpt-4.1
- **Fixed ids that never existed:**
  - Hugging Face: `deepseek-ai/DeepSeek-V4`, `google/gemma-4-assistant` and
    `Qwen/Qwen3.5-7B`
  - Bedrock: `anthropic.claude-sonnet-4-6-v1:0`
  - OCI: the `openai/gpt-oss-*` ids, which take a dot after the vendor, not a
    slash
  - Vertex: `claude-haiku-4-5@20251001`
- **Bedrock** now suggests `global.` inference-profile ids. Most current models
  have no in-Region endpoint on `bedrock-runtime`.
- **Defaults:**
  - Gemini and Vertex: `gemini-3.5-flash` → `gemini-3.8-flash`
  - Hugging Face: `Qwen/Qwen3.5-7B`, which does not exist → `Qwen/Qwen3.8-27B`
- The named-compatible changes (xAI, MiniMax, OpenRouter) are made in both
  `openai-compatible-providers.json` and its Manager mirror.
- The operator's system prompt lists these suggestions, so the operator
  revision is bumped.

### Not done

`ModelCombobox` is not added to the design-sync mirror (`.design-sync/`).
