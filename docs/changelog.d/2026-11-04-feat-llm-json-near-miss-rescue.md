## ✨ feat(llm): rescue near-miss JSON replies without a second model call (2026-11-04)

**Repo:** EDDI (`feat/llm-json-near-miss-rescue`)

### What changed and why

A tool-using agent on Gemini, Anthropic or Bedrock gets no native JSON mode, and in production about 2-6% of the turns of such agents were replaced by the fallback answer although the model had written a good one: plain HTML/text with no JSON wrapper (most often non-Latin scripts), double-escaped JSON, a misspelled field name, or `{}`. `onInvalidJson: "retry"` helps but costs a second model call. Three opt-in local repairs now cover the first three shapes, in the shared parse path (`ModelOutputParser`), so the live turn, the HITL resume, the format-retry runner's detection, the circuit breaker's view and every cascade step see the same reply:

- **`responseValidation.onInvalidJson: "wrap"`** with required `wrapField`, optional `wrapDefaults` and `wrapMaxChars` (default 20,000): a non-JSON, non-blank reply becomes `{ "<wrapField>": "<text>" }` (plus the defaults) and is validated like any parsed reply. Text starting with `{` or `[` is broken JSON and is never wrapped (`fallbackAction` applies). New parse outcome `wrapped` (`llm:output:outcome:*`, `eddi.llm.output{outcome=wrapped}`), `eddi.llm.recovery{action=wrap}` and a `LlmRecoveryLog` line.
- **Double-escape repair** (`{\"a\": \"b\"}`): one-level unescape, on whenever `onInvalidJson` is `wrap` or `retry`; accepted only as a JSON object and never tried on a reply that already parsed.
- **Task `fieldAliases`** (`{"htmlResponseStr": "htmlResponseText"}`): renames a top-level key when the target is absent (the target wins when both exist).
- `{}` stays covered by `nonBlankFields` plus `onSchemaMismatch: "retry"`; documented as the recommended pair.

Save-time validation in `LlmStore`: `wrap` without `wrapField` is a 400 naming what to set; `wrapField`, alias names and `wrapDefaults` keys must be plain top-level names. A config without the new fields behaves exactly as before.

### Design decisions

- The options are resolved once into `RescueOptions.of(task)` and passed through the existing `parse` / `Policy.from` / `classifyJsonReply` / `convertResponseToObject` overloads (the old signatures delegate with `RescueOptions.NONE`), so a later caller that routes a plain-text answer through the invalid-JSON handling (the `answerMode: "tool"` work) gets the rescue for free.
- Wrapping and the double-escape repair follow `responseValidation.enabled` like every other policy; `fieldAliases` is independent of it, like `nonBlankFields`.
- Plan deviation: `wrap` and `retry` are alternatives for the single `onInvalidJson` action. A reply `wrap` cannot rescue (broken JSON, over the cap) is handled by `fallbackAction` (as after spent retries), not by a second policy.
- Platform Operator: no change needed (it does not teach per-task response-validation fields).

**Files:** [`ModelOutputParser.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/ModelOutputParser.java), [`RescueOptions.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/RescueOptions.java), [`FormatRetryRunner.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/FormatRetryRunner.java), [`LlmTask.java`](../../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java), [`LlmConfiguration.java`](../../src/main/java/ai/labs/eddi/modules/llm/model/LlmConfiguration.java), [`LlmStore.java`](../../src/main/java/ai/labs/eddi/configs/llm/mongo/LlmStore.java), [`langchain.md`](../langchain.md), [`llm-resilience.md`](../llm-resilience.md)
