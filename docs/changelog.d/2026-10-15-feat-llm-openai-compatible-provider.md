## feat(llm): generic `openai-compatible` provider type for self-hosted servers (2026-10-15)

**Repo:** EDDI (`feat/llm-openai-compatible-provider`; backend, Manager, docs, Platform Operator)

### What changed and why

Production on-prem inference runs on vLLM, llama-server, TGI, KServe or LiteLLM. EDDI could
reach them only as `type: openai` + `baseUrl`, and so judged them as OpenAI: JSON mode was sent
alongside tools (a 400 on servers that do not support the combination), vision was decided by
OpenAI model names (a self-hosted Qwen-VL was text-only), and telemetry reported the traffic as
`OPEN_AI`. The named-provider catalog cannot describe these servers by design: it requires a
fixed `https://` endpoint and a fixed default model.

- **New LLM type `openai-compatible`** (`OpenAiCompatibleEndpoint`,
  `SelfHostedOpenAiLanguageModelBuilder`, registered in `LlmModule` and reserved in
  `BUILT_IN_TYPES` so no catalog row can take the id). It runs on the OpenAI builder.
  `baseUrl` and `modelName` are required; `http://` is accepted and the cloud-metadata block
  stays. `apiKey` is optional: verified against langchain4j 1.22.0's `DefaultOpenAiClient`, a
  null key adds no `Authorization` header, and a blank key is now removed before it could
  become `Bearer `.
- **Per-endpoint capability flags**, in the catalog's own vocabulary
  (`OpenAiCompatibleProvider.Capabilities`): `jsonMode`, `jsonModeWithTools`, `vision`, all
  off by default. `JsonResponseFormatPolicy` gained an `endpoint` component, filled by a new
  `of(..., parameters)` overload at all three call sites: `LlmTask` live, `LlmTask` HITL resume,
  and `CascadingModelExecutor`, which passes the **step's** merged parameters.
  `ModelCapabilityService.visionOverride` places the endpoint's `vision=true` below the per-task
  `multimodal.vision` override and above the deployment override; the built-in default for the
  type is off.
- **Strict literal flags, lenient templated ones.** A literal flag that is neither `true` nor
  `false` fails the deploy (`OpenAiCompatibleConfigValidator`, run from `LlmTask.configure` for
  the task, each cascade step and the judge) with a message naming the parameter; so does a
  missing `baseUrl`/`modelName`. LLM parameters are rendered by Qute before the request is built,
  but `${vars:...}` is otherwise only resolved inside `ChatModelRegistry`, so the call sites
  resolve global variables in the three flags first (`OpenAiCompatibleEndpoint.withResolvedFlags`).
  A templated flag that still is not a boolean counts as `false` with one WARN per distinct value
  (bounded): it never fails the build or the turn. `customParameters` must parse to a JSON object for this type (the OpenAI builder
  only warns and drops it).
- **Telemetry.** `serverName` (default `openai-compatible`) is reported as
  `gen_ai.provider.name` and the `provider` meter tag in place of langchain4j's `OPEN_AI`,
  through a provider-name override on `LlmTelemetryListener.forModelType` and the observable
  model wrappers. It is bounded: lower-cased, 1-32 characters of `[a-z0-9._-]`, else the
  default.
- **Model cache.** The three capability flags are left out of `ChatModelRegistry`'s cache key
  for this type (they shape requests, not the model instance); `serverName` stays in, because
  the telemetry wrapper bakes it in.
- **Parameter catalog.** `model-parameters.json` has an `openai-compatible` row that inherits
  OpenAI's parameters without their model rules and adds `serverName` and the three flags;
  the Manager mirror and [`docs/llm-parameters.md`](../llm-parameters.md) are regenerated.
- **Setup.** `AgentSetupService` treats the type as keyless and requires `model` and
  `baseUrl`/`llmBaseUrl` up front instead of injecting the SaaS default model; the MCP
  `setup_agent` / `create_api_agent` descriptions name it.
- **Manager.** "Self-hosted (OpenAI-compatible)" in the local group of the provider picker
  (agent wizard, operator activation, LLM editor, cascade steps, summary provider): required
  model and base URL with examples, an optional API key, and a note that the capability flags
  are switched on under Model settings, where they render from the catalog with a one-line
  explanation each. Pickers with no base URL field do not offer it: the workforce team builder,
  the group wizard's members and moderator, the cascade judge and a group's summarizer. The
  rolling-summary provider offers it only when the task itself is `openai-compatible`, because
  the summarizer then inherits the task's `baseUrl` and key.
  7 i18n keys in all 11 locales.
- **Docs and Operator.** A "Self-hosted OpenAI-compatible servers" section with a migration
  note in [`docs/langchain.md`](../langchain.md), the provider matrix row, and notes in
  [`docs/metrics.md`](../metrics.md) and [`docs/model-cascade.md`](../model-cascade.md). The
  Platform Operator prompt describes the type; `operator-revision.json` is bumped to 18.

### Design decisions

- **`customParameters` is the extra-body mechanism**, not a new `extraBody` key: it already is
  a JSON object merged into the request body with the user winning, the Manager already renders
  it, and a second key with the same meaning would need a precedence rule. It is made strict
  for this type only, so `type: openai` behaviour does not change.
- **No `jsonSchema` flag yet.** Native `json_schema` support differs between servers and
  versions, and the plan ties it to the structured-output work (D5); schemaless JSON is sent
  when `jsonMode` allows it.
- **`vision=false` defers rather than vetoes**: absent or `false` both fall through to the
  deployment override and then to the off default, so an operator's
  `eddi.multimodal.openai-compatible.vision=on` still applies to endpoints that did not set it.
- **`type: openai` is untouched**, pinned by a regression test of its JSON-policy rows, its
  native schema, its vision decisions and an old `type: openai` + `baseUrl` config.

### Not done

- Model discovery via `GET /v1/models` and the deployment-level provider allow-list (C3) are out
  of scope.
- `CreateSubAgentTool` has no base URL parameter, so it refuses this type up front with a
  message telling the model to pick another provider or have an administrator create the agent.

```decision-log
| 2026-10-15 | Self-hosted OpenAI-compatible servers get their own type, `openai-compatible`, with author-declared capability flags (jsonMode, jsonModeWithTools, vision; all off by default) instead of catalog rows | `type: openai` + `baseUrl` inherited OpenAI's JSON-with-tools and vision rules; the catalog validator requires a fixed https URL and model | Adding self-hosted rows to `openai-compatible-providers.json`; a new `extraBody` key next to `customParameters` |
```
