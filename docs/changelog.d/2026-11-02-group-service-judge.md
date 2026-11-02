## ✨ feat(groups): `convergence.judge: "SERVICE"` makes one direct model call (2026-11-02)

**Repo:** EDDI (`feat/group-service-judge`)

### What changed and why

`judge: "SERVICE"` on a phase's `convergence` block used to be accepted, documented and then quietly run as the
moderator agent: the group config carried no model coordinates or credentials for a direct call. A phase can
now name the model in a new `convergence.service` block, and the judge asks it directly — one short call
instead of a full moderator-agent turn, and it works in a group that has no moderator at all.

```json
"convergence": {
  "enabled": true, "minRepeats": 2, "threshold": 0.8, "judge": "SERVICE",
  "service": { "llmProvider": "openai", "llmModel": "gpt-4o-mini",
               "parameters": { "apiKey": "${vault:openai-key}" },
               "inputPricePer1M": 0.15, "outputPricePer1M": 0.60, "timeoutMs": 20000 }
}
```

- **Config** — [`ServiceJudgeConfig`](../../src/main/java/ai/labs/eddi/configs/groups/model/ServiceJudgeConfig.java),
  a sixth component on `ConvergenceConfig` (the five-argument constructor is kept as an overload). Normalized like
  `ContextWindowConfig`/`StanceSummaryConfig`: blank identifiers absent, negative prices unpriced, timeout default
  20 s and ceiling 60 s. The stale Javadoc that said the config "does not carry" provider coordinates is fixed.
- **Engine** — [`ServiceConvergenceJudge`](../../src/main/java/ai/labs/eddi/engine/internal/groups/ServiceConvergenceJudge.java)
  calls `SummarizationService.summarizeWithUsage` with a fixed system instruction and the existing judge prompt
  as the user message (data, never template source), parses it with the same `ConvergenceDetector` parsers, prices
  it through the one-method seam [`JudgeCallPricing`](../../src/main/java/ai/labs/eddi/engine/internal/groups/JudgeCallPricing.java)
  (today `TokenPricing.cost`), books it as `system:judge:<phase>:<repeat>` and announces it with `cost_updated`.
  `PhaseExecutionEngine.runJudge` asks it **before** the no-moderator early return; it is wired by a setter from
  `GroupConversationService`, so no construction site changed. A failure, timeout or unreadable reply is "not
  converged"; the provider's error text never reaches the transcript.
- **Save time** — [`ServiceJudgeValidation`](../../src/main/java/ai/labs/eddi/configs/groups/validation/ServiceJudgeValidation.java),
  one line in `AgentGroupStore.memberAndLimitProblems`: a plaintext credential in `service.parameters`, a
  `${connection:...}` reference (the model registry refuses those) and a half-named provider/model are 400s.
  `SERVICE` without a model and an unknown `judge` value are warnings only, so stored configs keep saving.
- **Metrics** — timer `eddi.group.convergence.judge.duration{judge=service, mode, outcome}`.
- **Manager** — the phase editor shows provider, model, API key reference, prices and timeout when Judge is
  Service (`service-judge-fields.tsx`); `normalizeConvergence` carries the block through. New i18n namespace
  `groupServiceJudge` in all 11 locales; the "not yet wired" note is gone.
- **Docs** — new page [Group Convergence Judge: SERVICE](../group-service-judge.md); the convergence paragraph in
  [Group Conversations](../group-conversations.md) now links to it.

### Behaviour of existing configs

Unchanged. `judge: SERVICE` without a `service` model still runs the moderator agent (now counted as
`outcome=fallback_moderator`), and no stored config can fail the new save-time checks because they only read the
new block.

### What's next

- `JudgeCallPricing` is the seam the cost-accounting work re-points at its central price resolver.
- MCP/Operator: no new tool; the Platform Operator follow-up is in
  [`planning/operator-followups/group-service-judge.md`](../../planning/operator-followups/group-service-judge.md).

```decision-log
| 2026-11-02 | The SERVICE judge calls SummarizationService.summarizeWithUsage, not GuardrailModelClient | GuardrailModelClient is package-private and bound to an LLM task's GuardrailContext/PaidCallGate, which a group does not have; the group summarizers already price and book through SummarizationService + TokenPricing + GroupCostLedger | Reusing the guardrail client; a new model client |
| 2026-11-02 | The judge's parameters accept vault/vars references only for credential keys, and refuse ${connection:...} | A group config must not become a plaintext key store, and ChatModelRegistry rejects connection references for model parameters at call time | Accepting literals with a warning; resolving connections for the judge |
```
