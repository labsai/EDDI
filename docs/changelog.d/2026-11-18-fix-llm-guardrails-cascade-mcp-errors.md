## fix(llm,mcp): guardrail types, cascade credentials, MCP errors, audit and bounds (2026-11-18)

**Repo:** EDDI (`fix/llm-guardrails-cascade-mcp-errors`)

Findings O1, O3, O4, O7 (LLM providers, cascade, guardrails) and N1, N2, N3, N5 (MCP server) of the November 2026 code review.

### Upgrade notes

- **An unknown or mistyped input/output guardrail type now stops a deploy.** A stored LLM config written by an older version
  that carries such a type (for example `pii-redacton`) fails to deploy with a message naming the task, the list, the index,
  the type and the known types, and can no longer be saved. This is the intended fail-closed behaviour: until now the entry
  was skipped with one WARN and the protection silently did not exist.

- **A cascade step of another provider type no longer inherits the task's credentials.** `apiKey`/tokens, `baseUrl` and
  other endpoints, region/account coordinates and `customHeaders`/`customQueryParams`/`customParameters` are inherited only
  when the step's provider type equals the task's. A stored config whose cross-provider step relied on the task's `apiKey`
  now fails to save and to deploy ("give the step its own apiKey"). Keyless provider types (`ollama`, `jlama`,
  `openai-compatible`, `bedrock`, `gemini-vertex`, `oracle-genai`) are exempt; temperature, timeouts and the like still inherit.

- **A `judge_model` cascade with no usable judge now escalates.** A missing or unbuildable judge scores the answer 0.0
  instead of the heuristic's accepting 0.8; `heuristic.fallbackWhenJudgeUnavailable: true` opts back in. A judge or
  structured-output confidence in (1, 100] is read as a percentage (`85` is 0.85, not certain); a negative or above-100 value
  is no confidence and takes the heuristic fallback.
- **MCP tools no longer echo exception text** (an `INTERNAL` error with a reference id instead), `read_conversation_log`
  returns the newest 50 steps by default (it returned everything), and the memory/search tools page. See the MCP sections below.
- **New switch `eddi.llm.pricing.catalog-floor`** (default `false`, no change): the price catalog as a floor under configured
  prices. A zero on a hosted provider is warned about and counted either way.

### O1 — credentials never cross provider types in a cascade

`CascadeCredentialPolicy` (new) decides which keys are provider-bound and which steps need their own key;
`CascadingModelExecutor.mergeParams(base, step, taskType, stepType)` applies it at run time (global variables resolved),
`CascadeConfigValidator` refuses at deploy (was a WARN), `LlmStore` refuses at save. The judge never inherited task parameters.

**Files:** [`CascadeCredentialPolicy.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/CascadeCredentialPolicy.java),
[`CascadingModelExecutor.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java),
[`CascadeConfigValidator.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/CascadeConfigValidator.java),
[`model-cascade.md`](../model-cascade.md)

### O7 — confidence values; a missing judge escalates

`ConfidenceEvaluator.usableConfidence` replaces the clamp for structured-output and judge confidences; `evaluateWithJudge`
without a judge returns 0.0 unless `HeuristicConfig.fallbackWhenJudgeUnavailable` is true. A judge call that fails or returns
nothing usable still falls back to the heuristic (unchanged; only the build failure was in scope). The documented range is
0.0 to 1.0, so a percentage reading was chosen over rejecting: `85` from a model almost certainly means 85%.

**Files:** [`ConfidenceEvaluator.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ConfidenceEvaluator.java),
[`LlmConfiguration.java`](../../src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java),
[`model-cascade.md`](../model-cascade.md)

### O3 — an explicit zero price is visible, and can be forbidden

The small safe-by-default variant; a free model stays declarable. `ExplicitPrices` (new) warns about an explicit 0 on a
provider other than `ollama`/`jlama`/`openai-compatible` at save (log) and at deploy (`PricingLint`, deployment warnings),
counts such calls in `eddi.llm.pricing.explicit_zero_calls{provider}`, and `eddi.llm.pricing.catalog-floor=true` makes
`ModelPriceResolver` raise configured prices to the catalog's. There is no warnings channel in the store's REST response, so
the save-time warning is a log line. Not done: an admin-only lock on price fields.

**Files:** [`ExplicitPrices.java`](../../src/main/java/ai/labs/eddi/modules/llm/pricing/ExplicitPrices.java),
[`ModelPriceResolver.java`](../../src/main/java/ai/labs/eddi/modules/llm/pricing/ModelPriceResolver.java),
[`PricingLint.java`](../../src/main/java/ai/labs/eddi/modules/llm/pricing/PricingLint.java),
[`model-pricing.md`](../model-pricing.md), [`configuration-reference.md`](../configuration-reference.md),
[`metrics.md`](../metrics.md)

### N1 — no internal exception text to MCP callers

The two-argument `McpToolUtils.errorJson(prefix, exception)` is gone; its 26 call sites use `toolFailure(LOGGER, tool, what, e)`
(a stable message plus a reference id that is in the server log with the full exception). `McpToolErrorLeakScanTest` scans
the tool classes so a new call site or a re-added overload fails the build.

### N3 — mutating MCP tools write an admin-action entry

`@McpAudited` plus `McpAuditInterceptor` record actor, tool, subject (pseudonymised) and outcome for the in-process mutators
(deploy/undeploy, upgrade, schedules, groups, team tasks, user memories, setup, team budgets/handoffs/cadences, rating review,
alert acknowledgement, agent-test runs). `McpToolAuditCoverageTest` enumerates every `@Tool` and fails for one that is neither
annotated nor classified (read-only, REST-backed, data plane, self-recording). The `AdminActionAuditFilter` Javadoc and
`audit-ledger.md` no longer claim the in-process tools reach the filter.

### N5 — bounded MCP results

`read_conversation`, `read_group_conversation` and `discuss_with_group` return at most 100,000 characters, else an explicit
`{"truncated":true,…,"hint":…,"partial":…}`; `read_conversation_log` returns the newest 50 steps (max 500) with a
`[truncated: true …]` line; `export_user_data` over 250,000 characters keeps counts and flags and says `complete:false,
truncated:true`; `list_user_memories` gained `offset` and a clamped `limit`, `search_user_memories` a `limit`. Page limits are
clamped. Documented in [`mcp-server.md`](../mcp-server.md).

### N2 — remote MCP tool names

Not changed here: open PRs #1401 and #1403 rewrite `McpToolProviderManager`, and #1403 drops invalid tool names.

### O4 — unknown guardrail type is a deploy error

`GuardrailService.unknownTypeProblems(listName, configs)` lists the entries of an input/output list whose type is missing or
not registered. `LlmTask.validateGuardrails` (deploy) and `LlmStore.create/update` (save, via the new `GuardrailService`
constructor argument; the two-argument constructor keeps working and skips the check) refuse them. The run-time WARN-and-skip
stays as a last line. Tool-call guardrails already rejected unknown types.

**Files:** [`GuardrailService.java`](../../src/main/java/ai/labs/eddi/modules/llm/guardrails/GuardrailService.java),
[`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java),
[`LlmStore.java`](../../src/main/java/ai/labs/eddi/configs/llm/mongo/LlmStore.java),
[`langchain.md`](../langchain.md), [`security.md`](../security.md), [`tool-call-guardrails.md`](../tool-call-guardrails.md)
