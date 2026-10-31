## 🐛 fix(llm): every model-phase exit records its spend; guardrail redactions reach the summarizer and recall tool (2026-10-31)

**Repo:** EDDI · **Branch:** `fix/review-llm-cost` · Review group H1 (LLM task core: cost accounting, spend gate, guardrails), from the code review report (§3 #2, #3, #4, #12, §5, §6, §7) and the verified CodeRabbit findings

### What changed and why

**Spend of a failed model phase was never recorded (#2).** A task wrote its cost from the result of its
model phase, so a phase that threw — a tool loop whose seventh round hit a provider error or the
context limit, a cascade step that timed out, a cascade whose every step failed — lost everything it
had spent: the tenant's monthly counter and `maxCostPerConversationUsd` never saw it, with or without
`onError: fallback`. Now:

- `UnrecordedSpend` carries a failed phase's spend on the failure (a suppressed throwable, so it
  survives wrapping; drained at most once). The tool loop attaches its answered rounds and tool cost
  on every failure exit; the cascade folds a failed step's rounds into its run totals, prices a
  timed-out step's completed rounds from the step's `ToolExchangeRecorder` (which now records each
  round's usage as it finishes), and attaches the run's totals to the failure it finally throws —
  including a spend refusal, an open circuit, or a run ceiling (`maxCostPerRun`, duration, turn
  deadline) reached before a later step when every earlier step had failed.
- `LlmTask.recordSpend` is the one place spend reaches the step; a returned phase, a pause
  (`recordPrePauseSpend`) and a failure (`recordFailedPhaseSpend`) all pass through it. The returned
  phase's spend is now recorded as soon as the phase is over, before JSON conversion and
  `postResponse` can fail the task, and a rejected answer (`responseValidation` `error`, a failed
  reprompt) records the answer's cost before it rethrows.

**One price decision instead of six (§6).** `ModelPrices` decides which prices a phase is charged at
(task, or cascade step → cascade default). The tool loop used the *task's* prices for its mid-loop
ceiling and its pause spend even inside a cascade step, so with prices set only on the cascade the
ceiling ignored token spend and a pause recorded $0. The cascade now hands the loop its step's prices
through the recorder, and a resumed cascade step is priced at that step again.

**Tenant check before a tool call (#12).** It compared the budget against the counter as it stood
before the turn. It now passes the step's unmetered `audit:cost` plus the running loop's spend, the
figure `LlmSpendGate.admit` uses for every other paid call.

**Redactions leaked through the rolling summary and the recall tool (#3).** Memory keeps the user's
text as sent; `pii-redaction`, `regex-filter` and `prompt-injection` re-screen only the history
window, so a turn they rewrote reached the summarizer (possibly another vendor), the summary placed
in every later system prompt, and `recallConversationDetail` verbatim. `GuardrailService.screenHistoricInput`
applies the history rule to one stored text; the summarizer screens the turns it sends and the recall
tool the turns it returns. The summary sits in the system prompt inside the retrieved-context
provenance envelope. The summary call and a tool result's `summarize` truncation are now admitted by
the spend checks and priced at the task's rates (the summary on `audit:cost`, the tool summary as a
priced `summarizeToolResponse` tool call). A cross-provider summary no longer inherits
`customHeaders`, `customQueryParams`, `customParameters` or provider account keys.

**Guardrails.**
- `pii-redaction` missed a card followed by its CVV or expiry: the greedy run failed Luhn as a whole
  and was skipped. The digit-group windows of the run are now checked (longest Luhn-valid window wins;
  an unseparated number is never split), and a run that holds no card is searched again from its next
  digit group, so groups in front of a card (`ref 1234 4111 1111 1111 1111`) do not hide it. Unspaced `+E.164` numbers count as phones. Every pattern
  repetition is bounded, so the e-mail scan is linear (it was quadratic on a long local-part run).
- A `warn` output guardrail with `failMode: closed` now forces buffering — it can withhold the answer.
- The `expression` guardrail reads the answer the tolerant way too and treats a disagreement as
  undecided: strict extraction could skip an earlier duplicate-key object and approve a later one
  while the task's conversion consumed the earlier.
- The HITL self-checks judge an MCP/A2A call by its arguments as well (`RemoteToolRequestResolvers.Envelope`):
  the resolved URI is the server, not the conversation or resource the call targets. The approved
  call's outgoing request is also refused when a `preRequest` instruction pointed it at the running
  conversation (`ApprovedCallScope` carries the conversation id).

**Tool cost tracking.** A non-finite `toolPricing` value fell through `Math.max` as NaN and poisoned
every total; it now falls back to the default price. A timed-out tool call is charged. An entry found
over budget is evicted only after every under-budget one. `ToolCostTracker.resetConversation` (unused)
is gone.

**Streaming.** A tool-loop round that failed after forwarding text was retried into the same sink
(partial + full answer); it is now raised as `PartiallyStreamedRoundException`, which the retry does
not repeat.

**Dead code / docs (§5).** `LlmSpendGate`'s misplaced Javadoc fixed; `AgentOrchestrator`'s unused
`DEFAULT_MAX_PAUSES_PER_TURN`, `PAUSE_REASON_MAX_CHARS`, `mergeExternalTools` and the test-only
delegators removed.

**Files:** [`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java),
[`ToolLoopRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopRunner.java),
[`CascadingModelExecutor.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/CascadingModelExecutor.java),
[`ModelPrices.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ModelPrices.java),
[`UnrecordedSpend.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/UnrecordedSpend.java),
[`ConversationSummarizer.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ConversationSummarizer.java),
[`GuardrailService.java`](../../src/main/java/ai/labs/eddi/modules/llm/guardrails/GuardrailService.java),
[`PiiRedactionGuardrail.java`](../../src/main/java/ai/labs/eddi/modules/llm/guardrails/impl/PiiRedactionGuardrail.java),
[`ToolCostTracker.java`](../../src/main/java/ai/labs/eddi/modules/llm/tools/ToolCostTracker.java);
docs: [`langchain.md`](../langchain.md), [`tenant-quotas.md`](../tenant-quotas.md).

### Design decisions

- **Carry spend on the failure, don't write it as you go.** Writing each round into `audit:cost`
  immediately would have changed the accounting model of every path; a carrier mirrors the pause's
  `addSpend` and leaves the success path untouched.
- **Summaries are priced at the task's rates.** No price is configured for a summarizer; the task's
  model is the same provider and normally the dearer model, so this errs on the side of the budget.
  A dedicated summarizer price is a possible follow-up.
- **Screen at the consumer, lazily.** The summarizer screens only the batch it sends and the recall
  tool only the turns it returns, rather than `modelFacingOutputs` screening the whole conversation
  every turn.

### Not done

- `SecretRedactionFilter` still has no `Cookie`/`Set-Cookie` rule (owned by the secrets area; a
  fuzzed filter — left for that group).
- The per-conversation tool budget stays in memory (lost on restart); documented, with
  `maxCostPerConversationUsd` as the durable bound.
