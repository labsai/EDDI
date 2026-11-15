# Operator / MCP follow-up — `feat/group-service-judge` (F8a)

Deferred to the combined F9 integration PR (program convention: no edits to `ui/manager/src/lib/operator/**`
and no new MCP tools in feature PRs).

## Platform Operator — what the system prompt should learn

- `convergence.judge: "SERVICE"` with a `convergence.service` block
  (`llmProvider`, `llmModel`, `parameters`, `inputPricePer1M`, `outputPricePer1M`, `timeoutMs`) makes one cheap
  direct model call per convergence check instead of a moderator-agent turn.
  - It works **without a moderator**.
  - Its spend appears in the discussion's `memberCosts` as `system:judge:<phaseIndex>:<repeat>` and counts toward
    `maxCostPerDiscussion`; each check also counts against `maxTurns`.
  - Without a `service` model (or with only `judge: "SERVICE"`), it falls back to the moderator agent with a
    warning — exactly the pre-existing behaviour.
  - `timeoutMs` defaults to 20000 and is capped at 60000.
- Credentials: any credential-named parameter (`apiKey`, ...) must be `${vault:...}` or `${vars:...}`; a literal
  or a `${connection:...}` reference is a 400 at save time. The operator must suggest a vault reference, never
  paste a key.
- A failed, timed-out or unreadable judge reply never ends a phase ("not converged").
- Metric for diagnosis: `eddi.group.convergence.judge.duration{judge=service,mode,outcome}`, where
  `outcome=fallback_moderator` means the block is missing its model.

No allow-list (`tool-scopes.ts`) change is needed: the operator already edits group configs through the existing
group store endpoints, and this feature adds no endpoint. A system-prompt edit means an `operator-revision.json`
bump in the F9 PR.

## MCP tools

None. The feature adds no REST endpoint and no service method an MCP tool would need; `update_group` already
carries the whole config, including the new block.

## Cost accounting (F1)

`JudgeCallPricing.cost(provider, model, inputPricePer1M, outputPricePer1M, inputTokens, outputTokens)` is the
one-method seam. Its body is `TokenPricing.cost(...)` today; once F1's central price resolver is merged, only that
body should change (catalog by provider/model, explicit per-1M overrides winning).
