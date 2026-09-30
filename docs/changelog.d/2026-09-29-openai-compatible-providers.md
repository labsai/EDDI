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
  because they reject a tool-loop follow-up that omits the reasoning of the previous call. GLM and
  MiniMax also set both: Z.ai asks for the historical `reasoning_content` back during tool use,
  and MiniMax (which the preset sends `reasoning_split: true`) puts thinking into
  `reasoning_content` and wants the full assistant message appended to the history. Qwen sets
  neither. `returnThinking` only matters together with `sendThinking` today, since EDDI does not
  read a response's thinking for any other purpose. A blank parameter counts as not set, so the
  preset default still applies.
- **Capabilities follow the catalog.** `JsonResponseFormatPolicy` and `ModelCapabilityService`
  read each provider's JSON-mode and vision-model tokens from it (DeepSeek's vision is
  `deepseek-flash`/`deepseek-v4-flash`; a unit test checks that no vision token matches a known
  text-only model such as `qwen3.7-max` or `glm-5.3`). `AgentSetupService.resolveParams` no longer defaults these providers to
  `claude-sonnet-4-6`; it uses the provider's own default model. The `setup_agent` and
  `create_api_agent` MCP tools name the eight ids, and their model example is now `deepseek-flash`.
- **Manager.** A grouped provider picker (model labs, OpenAI-compatible providers, cloud platforms,
  local) replaces the flat lists in the agent wizard, the operator activation form and the LLM
  editor (task type, cascade steps and judge, summary provider). Providers with more than one
  endpoint get a region select in the wizard and operator form: a non-default region fills the
  base URL, the default region clears it, and the backend `region` parameter stays for
  hand-written configs. A type the Manager does not know renders as "<type> (custom)" instead
  of being rewritten. `llm-provider-catalog.ts` mirrors the backend JSON, and
  `llm-provider-catalog.test.ts` reads that JSON and fails on any drift. Twelve i18n keys in all 11 locales.
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
- HITL: a paused tool call round-trips the assistant message's `thinking` (a new
  `ChatTranscriptCodecTest` case is a characterisation test that pins this existing behaviour),
  so DeepSeek and Kimi tool turns resume correctly. Only a
  gating message above its 64 KB cap loses its thinking on the degraded resume, which those two
  providers can reject with a 400. Documented in [`langchain.md`](../langchain.md).
- Local baseline: `hitl-config-negotiation.test.ts` fails on a Windows checkout because the Java
  text block it compares against has CRLF line endings; it is unrelated to this change.

### Review follow-up (backend)

- **Catalog corrections.** xAI gains an `intl`/`us` region pair (the US host serves `grok-4.7` and
  `grok-4.6` only, at about a 10% premium) and `grok-4.6`. DeepSeek's default is now
  `deepseek-flash` (V4.1 Flash, 2026-09-10, multimodal). Moonshot's key URL is the console page and
  `kimi-k2.7-code` is vision-capable. Qwen drops the legacy `qwen3.7-max` / superseded
  `qwen3.6-flash` suggestions, its vision tokens no longer match the text-only `qwen3.7-max`, and
  it sets no thinking defaults. Z.ai GLM sets both thinking flags and lists `glm-4.6v` and
  `glm-5.3-flash` for vision (`glm-5v` is unconfirmed). MiniMax's `cn` region is
  `api.minimax.cn` (the old host 302-redirects, which can drop the POST body/Authorization) and
  it sets both thinking flags. OpenRouter's `openrouter/auto` is vision-capable. Groq moves to
  `qwen/qwen3.8-27b` (`qwen3.6-27b` was shut down 2026-09-14). Sources: the vendor docs linked from
  [`langchain.md`](../langchain.md).
- **Default model outside the builder.** `AgentSetupService.defaultModelFor` is the one rule (preset
  default, else `claude-sonnet-4-6`); `resolveParams` and `CreateSubAgentTool` (including its
  `allowedModels` guard) use it. `LlmTask.resolveModelName(params, type)` and the cascade executor
  fall back to the preset default too, so vision forwarding and the audit/cost records see the
  real model instead of null or the type string. `McpSetupTools` no longer calls
  `claude-sonnet-4-6` the default for every provider.
- **Blank values** in the task parameters no longer block a preset default (`"sendThinking": ""`).
- **Catalog records** ignore unknown JSON properties, and a null key or value in
  `parameterDefaults`/`customParameters` fails with a message naming the provider and field.
  `OpenAiCompatibleProviders.isCompatibleProvider` (test-only) was removed.
- **Tests.** The builder test now asserts custom parameters and thinking flags reach the langchain4j
  model, a new `LlmModuleTest` covers registration and the id-collision guard, and a unit test
  checks vision tokens against a list of known text-only model ids.

### Review follow-up (Manager)

- **The catalog mirror** follows the backend corrections above (xAI regions, DeepSeek default,
  MiniMax `cn` host, refreshed suggestions and Moonshot key URL).
- **No key crosses vendors.** The agent wizard clears the API key on any provider change, as the
  operator form already did.
- **The operator remembers its endpoint.** `OperatorConfig` gained an optional `llmBaseUrl`, so
  reconfiguring seeds the region select from it instead of silently falling back to the default
  region; the review step lists the endpoint when one is set. Any provider switch in the operator
  form now clears the base URL (ollama's `http://localhost:11434` used to stay in a hidden field
  and be submitted with openai), and switching back to the stored provider restores it.
- **Consistent provider pickers.** The group wizard (member and moderator), the workforce team
  builder and the group advanced editor's summarizer now use `ProviderSelect`, which gained
  `leadingOptions` (for their "None" / "Workforce default" choices), `ariaLabel` and a
  "Select a provider" placeholder for an empty value. The wizard label is bound to its select.
  `ProviderRegionSelect` takes a `className` and `hideLabel`, so the operator form renders it
  inside its own field chrome with matching `h-10 rounded-md` styling; its custom option reads
  "Custom URL (set below)". `LLM_PROVIDERS` is type-checked against `ProviderGroup` with
  `satisfies`.
- **LLM editor.** The endpoint hint resolves in the backend's order (`baseUrl`, then the `region`
  URL, then the default); a comment records that changing the type deliberately keeps the
  parameters, which stay visible in the grid.
- Four new i18n keys (`llmProviders.select`, `llmEditor.compatibleEndpointOverridden`,
  `llmEditor.modelType`, `operator.activation.llmEndpoint`) and one reworded
  (`llmProviders.region.custom`) in all 11 locales.

### CI follow-up: startup crash

The first CI run's E2E jobs could not boot the image: `LlmModule.configure()` is both
`@PostConstruct` and `@Inject`, so CDI runs it twice, and the catalog's collision guard
(`builders.containsKey(id)`) rejected the providers it had registered itself on the first pass —
`IllegalStateException: OpenAI-compatible provider id 'xai' collides with a built-in LLM type`.
Unit tests construct the module by hand and call `configure()` once, so they could not see it.
The guard now checks a fixed `BUILT_IN_TYPES` set plus duplicates within the catalog, which makes
registration idempotent. `LlmModuleTest.configureIsIdempotent` calls `configure()` twice and fails
with the original exception when the old guard is restored; the packaged jar was also booted
locally against MongoDB and reached `/q/health/ready`.

### Review follow-up: group wizard key reset

CodeRabbit (PR 904) found the group wizard's member and moderator provider selects kept the API
key across a provider change, so a key typed for one vendor was submitted with another's
configuration — the same gap already closed in the agent wizard. Both handlers now clear
`apiKey`; a test switches each slot's provider and asserts the key is empty, and fails with either
reset removed. The gap predates this branch; the added providers made it easier to hit.

### Merge of main (2026-09-30)

Main moved by ~270 commits while this was open. Conflicts resolved as follows:
- The agent wizard and the operator form now pass `include={isProvisionableBySetup}` to
  `ProviderSelect` (new prop), keeping main's rule that setup cannot provision `gemini-vertex`.
- The wizard keeps clearing the API key on *any* provider switch — stricter than main's "carry it
  between two keyed providers" rule, and it covers main's Jlama-token case too.
- The operator form seeds its stored endpoint only when it keeps the stored provider, since main now
  falls back from a provider the setup flow no longer offers.
- `DynamicAgentToolsTest`'s new cases verify main's two-argument `setupAgent(request, origin)`; the
  one-argument form would have made the `never()` check vacuous.
- `operator-activation.test.tsx`'s fallback case (added on main) expected `claude-sonnet-5`
  while #896 moved the default to `claude-sonnet-5-5` — main's own suite is red on it. It now
  derives the expected model from `LLM_PROVIDERS[0]`.

### Design decisions

```decision-log
| 2026-09-29 | OpenAI-compatible vendors are catalog entries, not Java classes | One JSON file feeds the backend registry and (through a parity test) the Manager, so a new vendor is a data change. | A Java builder class per vendor; a REST catalog endpoint. |
```

```regression-note
| 2026-09-29 | EDDI failed to start on this branch (caught by CI E2E before merge) | `LlmModule.configure()` runs twice under CDI and the provider collision guard checked the map it had just filled | Guard against a fixed built-in type set; a test calls `configure()` twice | feat/openai-compatible-providers |
```
