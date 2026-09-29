## feat(llm): first-class OpenAI-compatible providers (2026-09-29)

**Repo:** EDDI (`feat/openai-compatible-providers`)

### Why

Reaching xAI, DeepSeek, Kimi, Qwen, GLM, MiniMax, OpenRouter or Groq meant knowing to use
`type: openai` with a hand-typed `baseUrl`, and knowing each vendor's reasoning quirks. None of
that was discoverable in the Manager or the setup tools.

### What changed

- **One catalog, one builder.** [`llm/openai-compatible-providers.json`](../../src/main/resources/llm/openai-compatible-providers.json)
  declares the eight providers (`xai`, `deepseek`, `moonshot`, `qwen`, `zhipu`, `minimax`,
  `openrouter`, `groq`): endpoint, regions, default model, suggestions, parameter defaults,
  extra body fields and capabilities. `OpenAiCompatibleProviders` loads and validates it
  (duplicate id, blank URL/model, non-https URL fail startup); `LlmModule` registers one
  `OpenAiCompatibleLanguageModelBuilder` per entry, each delegating to `OpenAILanguageModelBuilder`.
- **Precedence.** Explicit `baseUrl`, then the `region` parameter, then the provider default.
  `modelName` falls back to the provider default. A missing `apiKey` fails with a message naming
  the provider and a `${vault:<id>-key}` example.
- **`OpenAILanguageModelBuilder`** now reads `returnThinking` / `sendThinking` (default false, so
  `type: openai` is unchanged) and has public `build`/`buildStreaming` overloads that take extra
  request-body fields. They are public because the delegate is reached through a CDI client proxy.
- **Provider defaults.** DeepSeek and Kimi run with `returnThinking` and `sendThinking` true,
  because they reject a tool-loop follow-up that omits the reasoning of the previous call.
  Qwen and GLM return thinking without echoing it. MiniMax sends `reasoning_split: true`.
- **Capabilities follow the catalog.** `JsonResponseFormatPolicy` and `ModelCapabilityService`
  read each provider's JSON-mode and vision-model tokens from it (DeepSeek's vision is
  `deepseek-flash` only). `AgentSetupService.resolveParams` no longer defaults these providers to
  `claude-sonnet-4-6`; it uses the provider's own default model. The `setup_agent` and
  `create_api_agent` MCP tools name the eight ids, and their model example is now `deepseek-v4-pro`.
- **Manager.** A grouped provider picker (model labs, OpenAI-compatible providers, cloud platforms,
  local) replaces the flat lists in the agent wizard, the operator activation form and the LLM
  editor (task type, cascade steps and judge, summary provider). Providers with more than one
  endpoint get a region select in the wizard and operator form: a non-default region fills the
  base URL, the default region clears it, and the backend `region` parameter stays for
  hand-written configs. A type the Manager does not know renders as "<type> (custom)" instead
  of being rewritten. `llm-provider-catalog.ts` mirrors the backend JSON, and
  `llm-provider-catalog.test.ts` reads that JSON and fails on any drift. The group wizard and team
  builder keep their flat selects, which now simply include the new providers. Twelve i18n keys in
  all 11 locales.
- No new dependencies and no migration: existing `type: openai` + `baseUrl` configs behave as before.

### Verification

Endpoints and model ids were checked against vendor documentation on 2026-09-29. Notes:
Qwen's `us` region exists (`dashscope-us.aliyuncs.com`); Alibaba's docs now steer the Singapore
and Beijing regions towards workspace-specific hosts, while the shared `dashscope-intl` and
`dashscope` hosts remain the defaults here, so a workspace URL goes in `baseUrl`. Groq
announced the retirement of `llama-3.3-70b-versatile` (2026-08-16), so it is not suggested.

### Known limitations

- Vendors retire models quickly; defaults were verified on 2026-09-29 and `modelName` should be set
  explicitly in production. Alibaba's docs now favour workspace-specific hosts for Singapore and
  Beijing; the shared `dashscope-intl` / `dashscope` hosts are kept as region defaults.
- HITL: a paused tool call round-trips the assistant message's `thinking` (covered by a new
  `ChatTranscriptCodecTest` case), so DeepSeek and Kimi tool turns resume correctly. Only a
  gating message above its 64 KB cap loses its thinking on the degraded resume, which those two
  providers can reject with a 400. Documented in [`langchain.md`](../langchain.md).
- The group wizard and workforce team builder keep flat provider selects (they include the new
  providers, ungrouped).
- Local baseline: `hitl-config-negotiation.test.ts` fails on a Windows checkout because the Java
  text block it compares against has CRLF line endings; it is unrelated to this change.

### Design decisions

```decision-log
| 2026-09-29 | OpenAI-compatible vendors are catalog entries, not Java classes | One JSON file feeds the backend registry and (through a parity test) the Manager, so a new vendor is a data change. | A Java builder class per vendor; a REST catalog endpoint. |
```
