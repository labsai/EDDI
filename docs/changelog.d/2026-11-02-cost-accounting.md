## ✨ feat(pricing): correct cost accounting — usage normalization, one price resolution, deployment price catalog, unpriced visibility, strict mode, spend SPI (2026-11-02)

**Repo:** EDDI (`feat/cost-accounting`)

### What changed and why

EDDI's dollar figures — `audit:cost`, every cost ceiling, the tenant's monthly budget, the audit ledger, `/v1`
usage — were only as good as the token counts and prices behind them, and both were off. Anthropic and Bedrock
report prompt-cache reads and writes outside `input_tokens`, so cached prompts cost $0; Gemini reports thinking
outside its output count; the only per-call sum point (`ToolContextBudget.sumTokens`) threw away every provider
field beyond the three base counts; a rolling summary on a cheap model was charged at the task's rates; group
window and stance summaries never reached the tenant budget; and a model with no price on its config was simply
free, with nothing telling anyone. See [Model Pricing](../model-pricing.md).

- **Usage normalization** — the decorators around every chat model
  ([`UsageMetering`](../../src/main/java/ai/labs/eddi/modules/llm/pricing/UsageMetering.java),
  [`UsageNormalizer`](../../src/main/java/ai/labs/eddi/modules/llm/pricing/UsageNormalizer.java)) replace each
  response's usage with a [`MeteredTokenUsage`](../../src/main/java/ai/labs/eddi/modules/llm/pricing/MeteredTokenUsage.java):
  input = all prompt tokens, cache reads/writes kept as subsets, output includes thinking, per-call long-context
  buckets, the pricing model, and the call's price at the catalog entry it matched when it returned. Sums and
  usage-map merges keep every field. `audit:token_usage` gains `cacheReadTokens`/`cacheWriteTokens`.
- **One price resolution** — [`ModelPriceResolver`](../../src/main/java/ai/labs/eddi/modules/llm/pricing/ModelPriceResolver.java):
  config prices, then the task's / cascade step's new optional `pricing` object (cache and long-context prices),
  then the catalog, then fallbacks (cache at the input price, tier at the base price; no price at all is unpriced,
  an explicit `0` is priced). `ModelPrices` and `TokenPricing` delegate to it, so every existing call site gains it.
- **Deployment price catalog** — one CAS-revisioned document (MongoDB `modelpricecatalog`, PostgreSQL
  `model_price_catalog`), `/administration/model-prices` (admin writes; admin/editor read, resolve and unpriced
  sightings), published as a static snapshot every pricing site reads, refreshed every 30 s. Bundled, never-active
  **reference prices** (Anthropic, OpenAI, Gemini list prices as of 2026-11-02, each with its source) for one-click
  adoption in the Manager.
- **Visibility** — `[unpriced-model]` deployment warnings, `audit:pricing` per step (→ `llmDetail.pricing`),
  `eddi.llm.cost.usd`, `eddi.llm.calls.unpriced`, `eddi.llm.pricing.refused`, cache/reasoning token types on
  `eddi.llm.tokens`, catalog gauges, and node-local unpriced sightings.
- **Strict mode** — `unpricedPolicy` `ALLOW` (default) / `REFUSE_WHEN_BUDGETED` / `REFUSE`, never looser than the
  `eddi.llm.pricing.unpriced-policy-floor` property; refused before the call through each caller's existing
  failure path (task fallback with reason `unpriced_model`, failed cascade step, undecided guardrail, truncation
  for group summaries, Dream stops).
- **Spend attribution SPI** — [`ISpendSink`](../../src/main/java/ai/labs/eddi/engine/pricing/ISpendSink.java) /
  [`SpendEvent`](../../src/main/java/ai/labs/eddi/engine/pricing/SpendEvent.java), fired synchronously after each
  accepted tenant charge by `TurnCostMeter` (exactly once per step), `DreamService` and the new
  [`SystemSpendMeter`](../../src/main/java/ai/labs/eddi/engine/pricing/SystemSpendMeter.java), which also charges
  group window and stance summaries to the tenant and admits them against the budget first.
- **Call sites** — summaries and the tool-response summarizer priced at their own model; guardrail, streaming and
  summary usage maps are the metered ones; Dream falls back to the catalog.
- **Manager** — a *Model Prices* page (admin) and a catalog hint plus cache/long-context fields in the LLM task's
  pricing section.

### Design decisions

- No active shipped prices: silently active, code-shipped prices go stale invisibly and an EDDI upgrade would change
  what a tenant is charged. An empty catalog prices exactly as before.
- Static snapshot holder (`ModelPriceCatalogs`) instead of injecting the catalog into the tool loop, cascade executor
  and resumer — every pricing method also has a snapshot-taking overload for tests.
- A provider response metadata subclass (`OpenAiChatResponseMetadata`, …) narrows `tokenUsage()` to its own type, so
  the decorator replaces it with a plain `ChatResponseMetadata` (id, model, finish reason). EDDI reads none of the
  provider-only extras.
- Strict mode never fails a deployment; the warning says the calls will be refused, and the per-call check enforces
  it. Failing deploy would race the catalog load at boot.

### Not done / next

- The MCP tool `read_model_prices` and the Platform Operator grants are deferred to the combined follow-up
  (`planning/operator-followups/cost-accounting.md`).
- Strict mode does not refuse the rolling conversation summary, the tool-response summarizer or the cascade's
  confidence judge yet (they are priced and counted).
- Embeddings are not priced (next gap; the catalog could take `kind: embedding`).
- Summarization retries that failed after consuming tokens stay unpriced.

```decision-log
| 2026-11-02 | A deployment price catalog completes config prices; EDDI ships reference prices that are never active until an admin adopts them | Cache, thinking and unpriced spend were invisible to budgets; `TokenPricing` said "deliberately no built-in provider price table" | Active shipped defaults (stale silently, upgrades change charges); per-tenant catalogs (EDDI meters one tenant) |
| 2026-11-02 | Unknown cache prices are charged at the input price | Anthropic/Bedrock cache tokens were $0 | Charging them $0 until priced (under-counts a hard budget) |
```

```regression-note
| 2026-11-02 | Anthropic/Bedrock cached prompt tokens and Gemini/Vertex thinking tokens were not priced; `audit:token_usage.inputTokens` now includes cache tokens, so costs and counts rise for cached or thinking workloads | Provider usage conventions differ and only input/output were read | Normalization in the chat-model decorator; escape hatch `eddi.llm.pricing.provider-normalization=false` | feat/cost-accounting |
```
