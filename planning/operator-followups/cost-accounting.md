# Operator and MCP follow-up — cost accounting (`feat/cost-accounting`)

For the combined "F9 integration" PR. Nothing here was built on the feature branch.

## Platform Operator

### Allow-list (`ui/manager/src/lib/operator/tool-scopes.ts`)

Grant, read-only:

- `GET /administration/model-prices` — the catalog, the effective and floor policy, the known model types.
- `GET /administration/model-prices/resolve?provider=&model=` — which catalog entry prices a model.
- `GET /administration/model-prices/unpriced` — unpriced calls seen on this node.
- `GET /administration/quotas/{tenantId}/usage` if it is not already granted.

**Do not grant** `PUT /administration/model-prices` or `GET /administration/model-prices/reference` (admin-only, and
only useful for a write). Prices are a budget lever — lowering a price to $0 is raising a budget — so price edits stay
human-only, in the Manager's *Model Prices* page.

### System prompt (`ui/manager/src/lib/operator/system-prompt.ts`)

Teach the operator:

- `audit:cost` is token spend at the resolved prices plus tracked tool cost. Price resolution: the config's
  `inputPricePer1M`/`outputPricePer1M` (task, cascade step — never the task's for a step —, judge, guardrail, group
  summary, Dream), then the task's/step's optional `pricing` object (cache and long-context prices), then the
  deployment price catalog, then fallbacks (cache at the input price, long-context at the base price). No price at
  all is **unpriced**: $0, counted. An explicit `0` is priced.
- Token semantics: input includes cached tokens for every provider (Anthropic/Bedrock cache tokens are added in);
  output includes thinking (Gemini). `audit:token_usage` carries `cacheReadTokens`/`cacheWriteTokens`.
- The deployment warning `[unpriced-model] … has no price` means the model's spend counts $0 against every budget;
  the fix is a catalog entry (Manager → Model Prices) or prices on the config.
- `llm:spend_refused:<taskId>` with reason `unpriced_model` means strict mode refused the call
  (`unpricedPolicy` `REFUSE` or `REFUSE_WHEN_BUDGETED` while a budget applies; the policy is never looser than the
  `eddi.llm.pricing.unpriced-policy-floor` property). Under strict mode an `openrouter/auto`-style router needs a
  catalog cap entry, because the check runs before the routed model is known.
- Where cost shows up: `llmDetail.pricing` in the audit trail (`calls`, `unpricedCalls`, `sources`, cache tokens),
  metrics `eddi.llm.cost.usd`, `eddi.llm.calls.unpriced`, `eddi.llm.pricing.refused`.
- Group window and stance summaries are now charged to the tenant's monthly budget.
- Reference prices are bundled but never active until an admin adopts them; entries older than 120 days are stale.

### Revision

Bump `ui/manager/src/lib/operator/operator-revision.json` with these changes.

## MCP tools (deferred)

| Tool | Args | Role tier | Calls |
|---|---|---|---|
| `read_model_prices` | `provider` (optional), `model` (optional) — when both are given, also resolve that model | viewer-tier read (admin + editor on REST) | `ModelPriceCatalogService.read()`, `ModelPriceCatalogService.snapshot().effectivePolicy()`, `ModelPriceCatalogService.floor()`, `ModelPriceCatalogService.resolve(provider, model)`, `UnpricedSightings.list()` — the same data as `GET /administration/model-prices`, `/resolve` and `/unpriced` |

Put it in a new class `engine/mcp/McpPricingTools` (do not grow `McpAdminTools`), add the name to `McpToolFilter`
next to `get_eddi_version`, bump the `McpToolContractTest` count and `docs/mcp-server.md`. No write tool.

Also check whether `integrations/claude-plugin/` skills should mention `read_model_prices`.
