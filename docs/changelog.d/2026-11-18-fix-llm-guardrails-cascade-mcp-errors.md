## fix(llm,mcp): guardrail types, cascade credentials, MCP errors, audit and bounds (2026-11-18)

**Repo:** EDDI (`fix/llm-guardrails-cascade-mcp-errors`)

Findings O1, O3, O4, O7 (LLM providers, cascade, guardrails) and N1, N2, N3, N5 (MCP server) of the November 2026 code review.

### Upgrade notes (each one is a behaviour change)

**The policy for the save-time checks added here:** an *interactive* save (REST, MCP, the Manager) refuses; an *import*,
instance-to-instance *sync*, *upgrade* or *duplicate* of an LLM config only logs one WARN per problem (it is a config that
existed and ran before), via `LenientConfigWrite`; the hard failure for an unknown guardrail type is at **deploy**, and
that stays. The setup wizard writes configs it generates itself, so it needs no lenient path.

- **An unknown or mistyped input/output guardrail type fails the deploy** (`pii-redacton`): the message names the config
  (its URI, so id and version), the task, the list, the index, the type and the known types. A stored config written by an
  older version that carries one now stops deploying, and cannot be saved interactively. Importing or duplicating it still
  works (with a WARN). Intended fail-closed behaviour: until now the entry was skipped with one WARN and the protection did not exist.
- **A cascade step of another provider type no longer inherits the task's credentials.** Not inherited across types:
  `apiKey`, `accessToken`, `authToken`, `nonAzureApiKey`, `signingSecret`, `appPassword`, `botToken`; `baseUrl` and every
  other endpoint parameter; `region`, `location`, `projectId`, `compartmentId`, `configProfile`, `organizationId`,
  `serviceVersion`; `customHeaders`, `customQueryParams`, `customParameters`; Bedrock `guardrailId`, `guardrailVersion`,
  `guardrailStreamMode`; Gemini `cachedContentName`. Temperature, timeouts, model keys and the like still inherit. A stored
  config whose cross-provider step relied on the task's `apiKey` fails to deploy and to save interactively ("give the step
  its own apiKey"); keyless types (`ollama`, `jlama`, `openai-compatible`, `bedrock`, `gemini-vertex`, `oracle-genai`) are exempt.
- **`customParameters`, `serviceVersion`, `region` and `location` are no longer inherited across provider types** either,
  although they are not credentials: they are vendor-specific, and a step of another vendor must set its own.
- **A `judge_model` cascade with no usable verdict now escalates.** A missing or unbuildable judge, a judge call that throws
  or returns no usable confidence, and a structured-output wrapper whose confidence is unusable all score 0.0 (on the last
  step the answer is still returned) instead of the heuristic's accepting 0.8. `heuristic.fallbackWhenJudgeUnavailable: true`
  restores the heuristic. **Expect more escalations** (and so more cost) wherever a judge was flaky or a model answered with
  a confidence outside the range.
- **Confidences are read one way** (judge, structured-output wrapper, task-force bids): `0..1` as is, `1` is 1.0, a whole
  number 2..100 or `"85%"` is a percentage, anything else (`1.5`, a 1-5 scale, a negative, above 100, text) is unusable.
  A bid with an unusable confidence now bids 0.0 instead of a clamped, winning 1.0.
- **`read_conversation_log` returns the newest 50 steps by default** (at most 500); it returned the whole conversation.
- **MCP tool errors no longer carry exception text** (an `INTERNAL` error with a reference id; the log has the exception).
  Messages EDDI authors for the caller (the setup wizard's validation, a cascade request that cannot be applied, spend-control
  refusals, review validation, the conversation service's own refusals) are still described.
- **MCP results are bounded** (100,000 characters; an over-long result is valid JSON of the same shape with the newest list
  entries and `truncated`, `total…`, `returned…`, `hint`); `list_user_memories` pages in the store; page limits are clamped.
- **New switch `eddi.llm.pricing.catalog-floor`** (default `false`, no change): the price catalog as a floor under configured
  prices (base, cache and long-context; never on local providers). An explicit 0 (full or partial) on a hosted provider is
  warned about at save and deploy and counted in `eddi.llm.pricing.explicit_zero_calls{provider}` either way.
- New admin-action ledger entries for in-process MCP mutators, with the target ids.

### O1 — credentials never cross provider types in a cascade

`CascadeCredentialPolicy` decides which keys are provider-bound and which steps need their own key;
`CascadingModelExecutor.mergeParams(base, step, taskType, stepType)` applies it at run time (global variables resolved,
so a templated type is covered), `CascadeConfigValidator` refuses at deploy (was a WARN), `LlmStore` refuses an interactive
save. The judge never inherited task parameters. The deploy message is prefixed with the config URI.

**Files:** [`CascadeCredentialPolicy.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/CascadeCredentialPolicy.java),
[`CascadingModelExecutor.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java),
[`CascadeConfigValidator.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/CascadeConfigValidator.java),
[`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java), [`model-cascade.md`](../model-cascade.md)

### O4 — unknown guardrail type is a deploy error

`GuardrailService.unknownTypeProblems(listName, configs)`; `LlmTask.validateGuardrails` (deploy) and `LlmStore`
(interactive save, via the new `GuardrailService` constructor argument; the two-argument constructor skips the check).
Import, sync, upgrade and duplicate (`RestImportService`, `UpgradeExecutor`, `RestLlmStore.duplicateLlm`) run inside
`LenientConfigWrite`, where `LlmStore` only logs.

**Files:** [`GuardrailService.java`](../../src/main/java/ai/labs/eddi/modules/llm/guardrails/GuardrailService.java),
[`LenientConfigWrite.java`](../../src/main/java/ai/labs/eddi/configs/LenientConfigWrite.java),
[`LlmStore.java`](../../src/main/java/ai/labs/eddi/configs/llm/mongo/LlmStore.java),
[`RestImportService.java`](../../src/main/java/ai/labs/eddi/backup/impl/RestImportService.java),
[`UpgradeExecutor.java`](../../src/main/java/ai/labs/eddi/backup/impl/UpgradeExecutor.java),
[`langchain.md`](../langchain.md), [`security.md`](../security.md), [`tool-call-guardrails.md`](../tool-call-guardrails.md)

### O7 — confidence values; a missing judge escalates

`ReportedConfidence` is the one reading (see above); `ConfidenceEvaluator` and `TaskBidEngine` use it. The documented range
is 0.0 to 1.0, so a percentage reading was chosen only for whole numbers and `%` values.

### O3 — an explicit zero price is visible, and can be forbidden

`ExplicitPrices` warns (save: log; deploy: `PricingLint`), counts (`provider` tag from the known provider types, else
`other`) and `eddi.llm.pricing.catalog-floor` raises configured prices to the catalog's. The Full metrics dashboard
charts the counter. There is no warnings channel in the store's REST response, so the save-time warning is a log line.
Not done: an admin-only lock on price fields.

### N1 — no internal exception text to MCP callers

The two-argument `errorJson(prefix, exception)` is gone; tools use `toolFailure(LOGGER, tool, what, e)` or
`safeMessage(e, fallback)` (the message only of a type `isCallerFacing` lists). `McpToolErrorLeakScanTest` flags
`getMessage()`, `getLocalizedMessage()`, `toString()`, `String.valueOf(` and `describe(` inside `errorJson(` and
`put("…error…", …)` arguments in the `Mcp*Tools` classes, and the two-argument form. **It is a tripwire for those forms,
not a guarantee:** exception text that reaches a result through a variable or a helper is not seen.

### N3 — mutating MCP tools write an admin-action entry

`@McpAudited(subject, target)` marks the tool and the class-level `@McpAuditBinding` binds `McpAuditInterceptor`
(the same mechanism as `@McpErrorResults`); the entry carries actor, tool, target ids, pseudonymised subject and outcome.
`apply_agent_changes` and `update_agent` (in-process cascade / redeploy) and the five HITL decision tools are audited.
A refusal of an unauthenticated caller is not recorded. `McpToolAuditCoverageTest` enumerates every `@Tool`.

### N5 — bounded MCP results

See the upgrade notes; [`mcp-server.md`](../mcp-server.md). `IUserMemoryStore.getEntriesPage` (Mongo and PostgreSQL) pages
`list_user_memories` newest update first.

### N2 — remote MCP tool names

Not changed here: open PRs #1401 and #1403 rewrite `McpToolProviderManager`, and #1403 drops invalid tool names.
