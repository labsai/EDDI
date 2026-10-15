## 🔒 fix(security): bound the expression parser, dictionary regexes and template string ops; refuse `saveResponse` without a name (2026-10-15)

**Repo:** EDDI (`fix/parser-bounds`) — findings M3, L8 and L2 of the 2026-10-15 security review

### What changed and why

**M3 — the expression parser was unbounded and reachable by any chatting user.** `ExpressionProvider` recursed once per nesting level with no cap and copied the remainder of the string at every level. A `context` entry of type `expressions` reaches it through `PropertySetterTask` and every `contextmatcher` rule; verified live, 20,000 nested parentheses threw a `StackOverflowError` that escaped `LifecycleManager`'s task-failure accounting, and a 3 MB payload cost ~10 s of CPU per request.

- New [`ExpressionLimits`](../../src/main/java/ai/labs/eddi/modules/nlp/expressions/utilities/ExpressionLimits.java) bean: `eddi.expressions.max-length` (default 32,768 characters) and `eddi.expressions.max-depth` (default 32), checked in one linear scan before parsing; `0` disables a bound. Exceeding one throws `ExpressionLimitExceededException` (an `IllegalArgumentException`) with the measured value and the bound.
- [`ExpressionProvider`](../../src/main/java/ai/labs/eddi/modules/nlp/expressions/utilities/ExpressionProvider.java) now parses on index ranges of the one input string — no per-level `substring` — and re-checks the depth where it descends, so no entry point bypasses the scan. The grammar is unchanged, quirks included (`ExpressionProviderBoundsTest` pins them). The limit exception is explicitly not swallowed by the parser's own error logging.
- REST boundary: `ConversationService.requireInputWithinLimit` — the gate every conversationId entry point, A2A and the OpenAI adapter already run — now also checks `context` entries of type `expressions` and refuses them with the existing `413 input_too_large` (`InputTooLargeException` gained a message constructor), so every adapter answers it without new plumbing. Inside a turn the exception is a task failure.
- [`LifecycleManager`](../../src/main/java/ai/labs/eddi/engine/lifecycle/internal/LifecycleManager.java) catches `StackOverflowError` alongside `RuntimeException`, wraps it in an `IllegalStateException` and runs the unchanged failure accounting: error counter (`error.type=stack_overflow`), SSE `task_failed`, audit entry, strict-write rollback, `taskErrors` to the caller.

**L8 — editor-authored CPU cost.**

- Dictionary regexes: [`RegEx.match`](../../src/main/java/ai/labs/eddi/modules/nlp/model/RegEx.java) runs through a deadline-checking `CharSequence` (new [`RegexDeadline`](../../src/main/java/ai/labs/eddi/utils/RegexDeadline.java), the pattern `ArtifactValidators` already used) with a 200 ms budget; a timed-out match is a miss, logged at WARN. [`RestDictionaryStore`](../../src/main/java/ai/labs/eddi/configs/dictionary/rest/RestDictionaryStore.java) compiles every `regEx` on create, update, patch and duplicate and refuses an invalid one with `400` naming the syntax error, instead of failing the agent's deployment later.
- Template string ops: `str.replace` and `str.concat` in [`StringTemplateExtensions`](../../src/main/java/ai/labs/eddi/modules/templating/impl/extensions/StringTemplateExtensions.java) compute the size they would produce and refuse it against `eddi.templating.max-output-chars` *before* allocating (new [`TemplateOutputLimit`](../../src/main/java/ai/labs/eddi/modules/templating/impl/extensions/TemplateOutputLimit.java), a process-wide value the production `TemplatingEngine` publishes, since Qute invokes extensions statically). `TemplatingEngine.processTemplate` catches `OutOfMemoryError` with the exceptions and reports it — and a `TemplateLimitExceededException` anywhere in the cause chain — as a `TemplateEngineException` naming the bound.
- [`PathNavigator.evaluateExpression`](../../src/main/java/ai/labs/eddi/utils/PathNavigator.java) caps an expression at 64 `+`/`-` operands (`MAX_OPERANDS`); beyond that it resolves to nothing. The longest-prefix search is quadratic in the operand count.

**L2 — `saveResponse: true` without `responseObjectName` was accepted and then failed every turn** with "Null key for a Map not allowed in JSON", leaving the conversation in ERROR. New [`ApiCallsConfigurationValidator`](../../src/main/java/ai/labs/eddi/configs/apicalls/ApiCallsConfigurationValidator.java) refuses it at save time (`400`, naming the call); it is wired with one call from `RestApiCallsStore.validateBatchLimits`, which create, update, patch and duplicate all run. `ApiCallExecutor` derives one `saveResponse` flag per call that is `false` when the name is missing (logged once per call at WARN), so a config stored before the check skips the save instead of failing the turn.

### Design decisions

- **413, not 400, for an over-long or over-deep `expressions` context.** It is the same class of refusal as a too-long input and the same exception, so the seven entry points that already map `InputTooLargeException` answer it with no new plumbing. The message names the bound and the property.
- **The expression bounds are deployment properties, not agent config**: they are a safety floor against a client, not agent behaviour.
- **The regex budget and the operand cap are constants**, documented in [security.md](../security.md#parser-and-matcher-bounds): nothing a dictionary legitimately matches takes 200 ms, and nobody writes 64 operands by hand.
- **The template pre-check is process-wide.** Qute calls extension methods statically, so the bound cannot travel with the engine instance; the production constructor publishes its configured value, tests get the shipped default. `BoundedResolvers.check` still enforces the per-engine value afterwards.

### Tests

`ExpressionProviderBoundsTest` (live payloads, both bounds at their boundary, every entry point, grammar parity), `LifecycleManagerStackOverflowTest`, `ConversationServiceTest` (expressions context), `RegExTest`, `RestDictionaryStoreRegExValidationTest`, `StringTemplateExtensionsOutputLimitTest`, `TemplatingEngineOutOfMemoryTest`, `PathNavigatorOperandCapTest`, `ApiCallsConfigurationValidatorTest`, `RestApiCallsStoreSaveResponseTest`, `ApiCallExecutorSaveResponseGuardTest`. Each bound was mutation-checked: with the fix reverted, its test fails. The one exception is the `OutOfMemoryError` catch clause itself: Qute hands the Error over wrapped in an exception, so the clause cannot be exercised from a unit test; the reporting it feeds is.

**Docs:** [configuration-reference.md](../configuration-reference.md) (two new properties), [security.md](../security.md#parser-and-matcher-bounds), [rest-api-reference.md](../rest-api-reference.md), [httpcalls.md](../httpcalls.md).

```regression-note
| 2026-10-15 | `ExpressionProvider` recursed without bound (StackOverflowError at depth 20,000, ~10 s CPU for 3 MB); the Error escaped task-failure accounting; `saveResponse` without `responseObjectName` failed every turn | No length/depth cap and per-level `substring`; `LifecycleManager` caught only `RuntimeException`; no save-time validation | `ExpressionLimits` + index-based parser, `StackOverflowError` handled as a task failure, `ApiCallsConfigurationValidator` | fix/parser-bounds |
```
