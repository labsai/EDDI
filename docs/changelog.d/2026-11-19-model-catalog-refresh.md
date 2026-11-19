## Model catalog refresh: Fable 5.2, GPT-6.2, Gemini 4, Kimi K3.1 (2026-11-19)

**Repo/branch:** EDDI, `claude/model-api-support-audit-6cd65c` (rename before pushing).

Audited the model pickers and the parameter rules against each provider's own
docs (Anthropic models overview, OpenAI models + changelog, Gemini models +
changelog, Mistral models, Moonshot models page, xAI release notes).

- **Pickers** (`ui/packages/app-kit/src/lib/model-suggestions.ts`,
  `llm-provider-catalog.ts`, `openai-compatible-providers.json`): added
  `claude-fable-5-2` and the limited-access `claude-mythos-5-2`/`5-1`/`5` (Anthropic list; Fable 5.2 also Vertex, Bedrock `global.` profile),
  `gpt-6.2-astra`, `gpt-6.2-sol`, `gpt-6.1-terra` (OpenAI, Azure),
  `gemini-4-neon`, `gemini-4-hydrogen`, `gemini-4-carbon`, and
  `kimi-k3.1-pro` / `kimi-k3.1-pro-highspeed`. Dropped what the providers now
  deprecate or retire: `gemini-2.5-pro`/`gemini-2.5-flash` (route to 3.8 and
  3.5 Flash-Lite from 2026-12-01, with breaking changes), `mistral-large-2512`
  (retired 2026-10-31) and `mistral-medium-3-5` (deprecated 2026-10-31). Kimi
  K2.6 and K2.7-code stay listed, last, because the parameter rules still cover
  them and `ModelParameterCatalogTest.noDeadRegex` requires that.
- **Parameter rules** (`model-parameters.json`, regenerated
  `llm-parameter-catalog.data.ts`): Fable/Mythos 5.2 follow the 5.x rules
  (fixed temperature/topP, no topK, adaptive thinking only); `gpt-6.2*` rejects
  sampling parameters unconditionally and has no `none` effort; Azure
  `gpt-6.2*` rejects the penalty parameters; Gemini `thinkingLevel` accepts
  `xhigh`, which a rule restricts to Gemini 4 Neon/Hydrogen (other models reject it).
- **Reference prices:** `claude-fable-5-2*` at $8/$40 (it would otherwise match
  the $10/$50 `claude-fable-5*` fallback). GPT-6.2 and Gemini 4 prices are left
  unset (`unpricedPolicy: ALLOW`) until published figures are confirmed.

**Second pass (every picker id re-checked against provider docs):**
`MiniMax-M3.1-Flash-Preview` is `MiniMax-M3.1-Flash` in MiniMax's docs (rules match both);
`deepseek-ai/DeepSeek-V4-Pro` is not a Hugging Face repo, so the picker now lists
`DeepSeek-V4.1-Flash` and `moonshotai/Kimi-K3.1-Pro`; added `qwen4-max` (Alibaba),
`global.openai.gpt-6.2-astra`/`-sol` (Bedrock lists both), `gemini-4-hydrogen` (Vertex), and
OpenRouter's Fable 5.2, GPT-6.2 Sol and Gemini 4 Neon. Confirmed unchanged: all Anthropic,
xAI, Groq, DeepSeek, GLM, OCI chat ids, the Ollama tags and the Azure GPT list. Not
confirmable from docs: the `*-latest` Mistral aliases (the model pages hide an alias),
`qwen3.8-max`, `qwen3.7-flash`, the Vertex-specific ids for Gemma and Claude, and OCI's
`xai.grok-4.20-reasoning`/gpt-oss ids; they were left as they were.

**Not done / open:** `gpt-6.2-*` `reasoningEffort` and `verbosity` support was
not confirmed from the docs; Gemini 4 sampling-parameter behaviour is not
documented, so the existing "3.6 and newer ignore temperature" rule is
unchanged. The DeepSeek, Qwen, GLM and MiniMax ids were re-checked against the vendors' model lists
(see the second pass above); what remains unconfirmed is only the list under "Not confirmable from docs". Their
*parameter rules* were not re-verified against the APIs.
