# Model Pricing and Cost Accounting

EDDI turns every model call's token usage into dollars, and those dollars feed the
conversation's tracked cost (`audit:cost`), the agent's `maxCostPerConversationUsd`, a
cascade's `maxCostPerRun`, a group's `maxCostPerDiscussion`, the tenant's
[monthly cost budget](tenant-quotas.md#the-monthly-cost-budget), the audit ledger and the
`/v1` usage block. This page explains how a call is priced, where the prices come from,
how to see spend that has no price, and how to refuse it.

## In short

- **Token usage is normalized** in the decorator around every chat model, so every
  provider's counts mean the same thing before they are priced (see
  [What the providers report](#what-the-providers-report)).
- **One price resolution** prices every call: the config's own prices first, then its
  optional `pricing` object, then the deployment's **price catalog**, then fallbacks.
- **The catalog** is one document per deployment, edited in the Manager under
  *Model Prices* (or `PUT /administration/model-prices`). It starts **empty**: an
  empty catalog prices exactly as EDDI did before it existed.
- **Unpriced calls are visible** — deployment warnings, a per-step `audit:pricing`
  record, the `eddi.llm.calls.unpriced` metric and an *Unpriced models seen* panel.
- **Strict mode is opt-in**: `unpricedPolicy` `REFUSE_WHEN_BUDGETED` or `REFUSE`
  refuses an unpriced call before it is made.

## Price resolution

For one call, each price — input, output, cache read, cache write, and the
long-context tier's — is chosen in this order:

1. The config's explicit `inputPricePer1M` / `outputPricePer1M`: the LLM task's; a
   cascade step's, else the cascade's defaults (a step never inherits the task's);
   the cascade judge's; a guardrail's, a group window or stance summary's, Dream's.
2. The config's optional `pricing` object (LLM tasks and cascade steps):
   `cacheReadPricePer1M`, `cacheWritePricePer1M`, `longContext`.
3. The catalog entry matched by the model that answered (see [Matching](#matching)).
4. Fallbacks:
   - a cache price that is still unknown is the **input price** — conservative: an
     over-count is safe under a hard budget, an under-count is not;
   - a long-context price that is unknown is the base price of the same kind;
   - **no input and no output price anywhere is _unpriced_**: the call costs $0 and
     is counted. An explicit `0` is a real price (self-hosted models) and is never
     counted as unpriced.

When a config sets no price of its own, each call is priced at the catalog entry it
matched **when it returned** — a catalog edit in the middle of a turn cannot tear one
call's price.

The cost of a call is

```text
cost = max(0, input − cacheRead − cacheWrite) × inputPrice
     + cacheRead  × cacheReadPrice
     + cacheWrite × cacheWritePrice
     + output     × outputPrice
```

where `input` is **all** prompt tokens (cached ones included) and `output` includes
thinking. Tokens of a call whose own prompt is longer than the matched entry's
`longContext.thresholdTokens` (strictly greater, as providers phrase it: "over 200K")
are priced at the tier's prices. Each call is classified on its own prompt, so the
summed rounds of a tool loop never move a short call into the tier.

### The `pricing` object on a task or cascade step

```json
{
  "type": "anthropic",
  "parameters": { "modelName": "claude-sonnet-4-6" },
  "inputPricePer1M": 3.0,
  "outputPricePer1M": 15.0,
  "pricing": {
    "cacheReadPricePer1M": 0.30,
    "cacheWritePricePer1M": 3.75,
    "longContext": { "inputPricePer1M": 6.0, "outputPricePer1M": 22.5 }
  }
}
```

Every field is optional; absent means the catalog (then the fallbacks) decides. The
long-context **threshold** is a property of the model and comes from the catalog
entry, so `longContext.thresholdTokens` is ignored here. Prices must be finite and
between 0 and 10 000; anything else fails the deployment.

### What gets priced where

| Call | Priced at |
|---|---|
| LLM task, tool loop, HITL resume, format re-asks, reprompts | the task's prices, completed by the catalog |
| Cascade step | the step's prices, else the cascade's defaults — never the task's |
| Cascade confidence judge | the judge's prices |
| Rolling conversation summary, tool-response `summarize` | the task's prices **only when it runs on the task's own model**; another model is priced from the catalog (it used to be charged at the task's rates) |
| Guardrail classifier and judge | the guardrail's `inputPricePer1M`/`outputPricePer1M` parameters |
| Group window and stance summaries | their config's prices — and now **charged to the tenant** (see [Spend attribution](#for-other-features-spend-attribution)) |
| Dream consolidation | the Dream config's prices, else the catalog |
| Embeddings (RAG, ingestion) | not priced (next gap) |

## The deployment price catalog

```json
{
  "revision": 7,
  "unpricedPolicy": "REFUSE_WHEN_BUDGETED",
  "entries": [
    { "provider": "anthropic", "model": "claude-sonnet-4-6*",
      "inputPricePer1M": 3.0, "outputPricePer1M": 15.0,
      "cacheReadPricePer1M": 0.30, "cacheWritePricePer1M": 3.75,
      "asOf": "2026-11-02", "source": "https://platform.claude.com/docs/en/about-claude/pricing" },
    { "provider": "anthropic", "model": "claude-haiku-5-5*",
      "inputPricePer1M": 0.10, "outputPricePer1M": 0.50,
      "longContext": { "thresholdTokens": 100000, "inputPricePer1M": 0.50, "outputPricePer1M": 2.50 } },
    { "provider": "ollama", "model": "*", "inputPricePer1M": 0, "outputPricePer1M": 0 },
    { "provider": "openrouter", "model": "openrouter/auto", "inputPricePer1M": 15, "outputPricePer1M": 75,
      "note": "cap price: the routed model is not known before the call" }
  ]
}
```

The prices above illustrate the shape; take real prices from the provider.

- **One document per deployment**, stored in MongoDB (`modelpricecatalog`) or
  PostgreSQL (`model_price_catalog`). It is operational settings, not a versioned
  resource, and it is **not** part of an agent ZIP: prices belong to the deployment.
  Move it between environments with the Manager's JSON export and import.
- **Writes are compare-and-set** on `revision`: send the revision you read (as
  `If-Match` or in the body); a stale one answers `409` with `currentRevision`.
- **Only `eddi-admin` writes.** The catalog is a budget lever — lowering a price to $0
  is raising a budget — so there is no MCP write tool and no Platform Operator write
  grant. Editors can read it and resolve a model.
- **Limits**: at most 500 entries; `model` is 1–128 characters of `A–Z a–z 0–9 . _ : / @ + -`
  with `*` only as a leading and/or trailing wildcard; `provider` is a model type of
  this deployment or `*`; prices 0–10 000; `thresholdTokens` 1 000–10 000 000; `note`
  and `source` ≤ 512 characters, `source` must be an http(s) link (display only, never
  fetched); no duplicate provider/model pairs; ≤ 256 KB in all.
- **Multi-node**: a write applies on its node immediately; every node re-reads the
  catalog every 30 seconds.
- **Store outage**: at boot the catalog is empty (prices from configs only); later
  the last good catalog is kept. `eddi.llm.price.catalog.load.failures` counts it.

### Matching

`provider` is the EDDI model type (`anthropic`, `openai`, `gemini`, `bedrock`,
`xai`, `openai-compatible`, …) or `*`. `model` compares case-insensitively:

1. an entry for the provider whose model is the exact name;
2. an entry for the provider whose wildcard has the **longest literal part** —
   `gpt-4o-mini*` beats `gpt-4o*`;
3. the same over entries for provider `*`.

The name the provider **reported** is tried first (OpenAI's dated snapshots, the
model behind an Azure deployment, the model OpenRouter routed to), then the name
that was requested. So an exact `gpt-4o` entry also prices a response from
`gpt-4o-2024-08-06` requested as `gpt-4o`.

### Reference prices

EDDI ships **no active prices**. It bundles a read-only reference list
(`GET /administration/model-prices/reference`) of list prices for Anthropic, OpenAI
and Gemini (Google AI) models, each entry with its `asOf` date and the `source` page
it was read from. The Manager's *Load reference prices* shows a diff and merges it
into the catalog only when an admin saves — from then on the admin owns every
number. Upgrading EDDI never changes what a tenant is charged.

Prices go stale. The Manager flags entries whose `asOf` is older than 120 days, and
`eddi.llm.price.catalog.oldest_asof_days` exposes the age of the oldest one.

## What the providers report

Normalization happens once, in the decorator `ChatModelRegistry` wraps around every
chat model, against langchain4j's per-provider usage classes:

| Provider | Input as reported | What EDDI does |
|---|---|---|
| Anthropic, Bedrock | excludes cache reads and writes | adds cache reads and writes to input, keeps both as `cacheReadTokens` / `cacheWriteTokens` |
| OpenAI and every OpenAI-client type (xAI, DeepSeek, Moonshot, Qwen, Zhipu, MiniMax, OpenRouter, Groq, `openai-compatible`) | `prompt_tokens`, cached included | keeps input; takes the cached subset from `prompt_tokens_details` |
| Gemini (Google AI) | cached content included; **output excludes thinking** | adds `thoughtsTokenCount` to output |
| Gemini on Vertex | thinking only visible as `total − input − output` | adds that residual to output |
| Azure OpenAI, Mistral, Ollama, Hugging Face, Jlama, Oracle GenAI | no cache detail | passes through |

Two consequences, both deliberate:

- **Anthropic and Bedrock cache tokens are now charged.** They were $0 before. Without
  a cache price they are charged at the input price until you set one.
  `audit:token_usage.inputTokens` grows because it now includes them.
- **Gemini thinking tokens are now output** — they are billed as output.

OpenAI-family numbers do not change unless a `cacheReadPricePer1M` is configured, in
which case they go down. Presets whose cache fields langchain4j does not map
(DeepSeek reports `prompt_cache_hit_tokens`) are charged at the input price — an
over-count. Anthropic prices 5-minute and 1-hour cache writes differently but reports
one write total: set `cacheWritePricePer1M` to the rate of the TTL you use.

The escape hatch `eddi.llm.pricing.provider-normalization=false` (environment
variable `EDDI_LLM_PRICING_PROVIDER_NORMALIZATION=false`) restores the old counts; the
catalog is still used.

## Seeing unpriced spend

- **Deployment warnings** — `[unpriced-model] llm task '…': the model 'type/name' has no
  price …` in the deployment status and the log, for the task's model, each cascade
  step, the cascade judge, a summary on another model and the tool summarizer. A model
  name that is a template is reported as checked per call.
- **Per step** — `audit:pricing` = `{calls, unpricedCalls, sources, cacheReadTokens,
  cacheWriteTokens}`, copied into the audit entry's `llmDetail.pricing`.
- **Metrics** — `eddi.llm.calls.unpriced{provider, model, site}`,
  `eddi.llm.cost.usd{provider, model, source}`, `eddi.llm.tokens{type=cache_read|cache_write|reasoning}`
  and `eddi.llm.pricing.refused{site, reason}`; see [Metrics](metrics.md).
- **Manager** — *Model Prices → Unpriced models seen*: provider, model, site, count
  and last seen on the node that served the page since it started (at most 200
  rows), with an *Add price* shortcut.

## Strict mode

`unpricedPolicy` decides what happens to an unpriced call:

| Policy | Effect |
|---|---|
| `ALLOW` (default) | the call runs, costs $0 and is counted |
| `REFUSE_WHEN_BUDGETED` | refused while a dollar ceiling applies: the tenant's monthly budget, the agent's `maxCostPerConversationUsd`, a cascade's or Dream's `maxCostPerRun` |
| `REFUSE` | every unpriced call is refused |

The policy in force is the **stricter** of the catalog's and the operator's floor,
`eddi.llm.pricing.unpriced-policy-floor` (environment variable
`EDDI_LLM_PRICING_UNPRICED_POLICY_FLOOR`, default `ALLOW`). An admin's catalog edit
cannot loosen the floor, and neither can a store outage. A value that cannot be
parsed reads as `REFUSE`.

The check runs **before** the call against the requested model; the model that
answers is not known yet. A router such as `openrouter/auto` therefore needs a
catalog entry under strict mode — a cap price, as in the example above.

What a refusal does is the caller's existing failure path:

| Caller | On refusal |
|---|---|
| LLM task | serves the fallback; `llm:spend_refused:<taskId>` with reason `unpriced_model` |
| Cascade step | counts as a failed step and escalates; all steps refused fails like an all-failed cascade |
| Model guardrail | "could not decide" — its `failMode` applies |
| Group window / stance summary | plain truncation / an extracted stance |
| Dream | the user's consolidation stops for this run |

Not refused in this version (still priced and counted): the rolling conversation
summary, the tool-response summarizer and the cascade's confidence judge.

A refusing policy never fails a deployment: the deployment warning says the calls
will be refused.

## For other features: spend attribution

Three stable, public entry points for features that call models or keep their own
spend view.

**Pricing a call** — `ModelPriceResolver.price(PriceOverride override, Map<?,?> usage)`
returns `PricedSpend(usd, unpricedCalls, source, cacheReadTokens, cacheWriteTokens)`.
Pass the response's `ToolContextBudget.tokenUsageMap(response.tokenUsage())` (it
carries the metered fields) and `PriceOverride.of(inputPer1M, outputPer1M)` from your
config, or `PriceOverride.NONE`. `ModelPriceResolver.resolve(provider, model)` returns
the catalog entry for a model; `TokenPricing.cost(in, out, usage)` is the same
resolution for callers that hold two prices.

**Refusing unpriced calls** — `UnpricedCallPolicy.check(override, provider, model,
memory, callerHasCeiling, site)` returns a refusal or null; `require(…)` throws.

**Group spend** — a call made for a group outside a member turn asks
`SystemSpendMeter.admit()` first and hands the usage to
`SystemSpendMeter.charge(groupConversation, key, override, usage, detail)` after: it
prices, records in the group's cost ledger by key (a re-run is idempotent), charges the
tenant the increase and notifies the sinks.

**Receiving spend** — implement `ISpendSink` as an `@ApplicationScoped` bean. Its
`onSpend(SpendEvent)` is called synchronously after every charge the tenant counter
accepted: settled turns (`TURN`, from `TurnCostMeter`, exactly once per step even
across a pause and resume), Dream runs (`DREAM`) and group machinery (`GROUP_SYSTEM`).
A `SpendEvent` carries `tenantId, conversationId, agentId, agentVersion, userId,
groupId, groupConversationId, kind, detail, usd` (the delta just charged),
`unpricedCalls, inputTokens, outputTokens` (a turn's are the step's totals) and `at`.
A member turn's `groupId` is the group the orchestrator put on the turn. A sink's
exception is logged and swallowed.

## REST

| Method | Path | Role | |
|---|---|---|---|
| GET | `/administration/model-prices` | admin, editor | catalog, effective and floor policy, known model types |
| PUT | `/administration/model-prices` | admin | replace (`If-Match: <revision>`); 400 with `violations`, 409 with `currentRevision` |
| GET | `/administration/model-prices/resolve?provider=&model=` | admin, editor | the entry that prices a model |
| GET | `/administration/model-prices/reference` | admin | the bundled reference prices |
| GET | `/administration/model-prices/unpriced` | admin, editor | unpriced sightings on this node |
