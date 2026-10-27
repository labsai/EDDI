## ✨ feat(llm): `expression` guardrail — a deterministic check over a JSON answer (2026-10-24)

**Repo:** EDDI (`feat/llm-expression-guardrail`, from `main` at `5028af0a3`) — item E1c of the evaluated-generation plan. Independent of the `judge` guardrail (#1121, `feat/llm-judge-guardrail`): it builds only on what `main` has (`ContentGuardrail`, `GuardrailVerdict.violation(reason, hint)`, `GuardrailUndecidedException`, `failMode`).

### What changed and why

A well-formed answer can still be wrong: an invoice whose `total` is not the sum of its lines passes `responseSchema` and `json-object`. `expression` (output only) parses the answer as JSON and evaluates an author-written boolean expression over it, and reprompts with author-written feedback — the deterministic sibling of the `judge`.

```json
{ "type": "expression", "action": "reprompt", "maxRetries": 1,
  "parameters": { "expr": "sum(lines[*].amount) == total", "issue": "LINE_SUM",
                  "fix": "Make total equal the sum of the line amounts, to the cent." } }
```

- **`SafeJsonExpression`** (`ai.labs.eddi.utils`, new) — a small recursive-descent language in the style of `CalculatorTool.SafeMathParser`: number/string/`true`/`false`/`null` literals; paths (`invoice.total`, `lines[0].amount`, `invoice['due-date']`, `$` for the answer itself) with a `[*]` wildcard that yields a list; `+ - * / %` on `BigDecimal` (34 significant digits, `0.1 + 0.2 == 0.3` is true); `== != < <= > >=` (not chainable); `&& || !` with short-circuit; parentheses; nine functions — `sum`, `count`, `min`, `max`, `len`, `abs`, `round(x, n)` (half-up), `exists(path)`, `approx(a, b, tolerance)`. No script engine, no expression library, no reflection, no Qute; an unknown function is a compile-time error. JDK-only and Java 17 syntax so ClusterFuzzLite can compile a vendored copy.
- **Null semantics:** a missing path and a JSON `null` are both `null`; `== null` / `!= null` test it; any other comparison with `null` is `false`; arithmetic, logic or a function on `null`, a type mismatch, division by zero, an empty `min`/`max` and an out-of-range number are evaluation errors — *could not decide*, never `false`. `exists(path)` tells a present key holding `null` from a missing one.
- **Bounds:** expression ≤ 2000 characters, nesting ≤ 32, ≤ 500 parts, ≤ 32 segments per path (all refused at compile time with a position, never a `StackOverflowError`); ≤ 10,000 values per path, ≤ 200,000 steps per evaluation; numbers beyond 1000 digits or a ±1000 scale refused rather than materialised.
- **`ExpressionGuardrail`** (`modules/llm/guardrails/impl`, new bean, picked up by `GuardrailService`'s type index) — parameters `expr`, `issue` (`[A-Z][A-Z0-9_]{0,39}`), `fix` (≤ 1000 characters), all required and checked in `validate()` at deploy (`parameter 'expr' is not valid at position 21: '=' is not an operator; compare with '=='`). Compiled expressions sit in a bounded Caffeine cache (512, refusals cached too). The answer is read with `ModelOutputParser` (fence and prose tolerant) over its own exact mapper: decimals as `BigDecimal`, integers as `BigInteger`, duplicate keys refused. Verdicts: `true` → allow; `false` → `VIOLATION` `expression_failed:<ISSUE>` with a hint that is EDDI's fixed frame (the judge guardrail's wording) around the author's `fix` only; not JSON / > 262,144 characters / duplicate key → `GuardrailUndecidedException` `expression_unparseable`; evaluation error or a non-boolean result → `expression_undecided:<cause>` (the evaluator's own codes). `failMode` decides as for every type.
- **Fuzzing:** `SafeJsonExpressionFuzzTest` (Jazzer `@FuzzTest` over expressions and over JSON data, plus seeded random sweeps that run in every unit-test run) and a ClusterFuzzLite target `SafeJsonExpressionFuzzer`; `.clusterfuzzlite/SafeJsonExpression.java` is the vendored copy, and the `Vendored Fuzz Sources In Sync` guard knows the fourth file.
- **Docs:** [langchain.md](../langchain.md#the-expression-type-a-deterministic-check-over-a-json-answer) (grammar, semantics, bounds, the invoice example and the exact corrective message), [security.md](../security.md) (sandboxed evaluator, closed vocabulary, fuzz targets), [security-review.md](../security-review.md).

- **End-to-end proof:** `ExpressionGuardrailIT` runs a real agent against a WireMock fake OpenAI endpoint: pass (also from a code fence), fail then repair (the second request carries the fixed frame around the author's `fix` and no computed value), exhausted reprompt and `block`/`warn` actions, non-JSON, duplicate-key and null-operand answers under both `failMode`s, exact decimal arithmetic (`0.1 + 0.2 == 0.3`, a one-cent difference caught), and deploy refusal for a syntax error, an over-long or over-deep expression, sandbox escapes and a bad issue code or fix.

### Design decisions

- **A new evaluator, not `ExpressionProvider`** (plan §9.1): that is the NLP expression grammar, with no arithmetic or paths into JSON. `PathNavigator` has only `+`/`-` on path values, no comparisons, logic or aggregates.
- **Could not decide is the safe default for anything ambiguous.** A missing amount inside `sum(...)` is not skipped, comparing a number with a string is not `false`: both are undecided, so a `reprompt`/`block` guardrail withholds the answer (fail closed) unless the author set `failMode: open`.
- **Closed vocabulary:** nothing read or computed from the answer — not the sum, not the total — reaches the corrective message. Only the issue code (author-declared, pattern-checked) reaches the audit reason.
- **Duplicate keys are unparseable**, so the check and a downstream reader of `convertToObject` cannot see two different totals.
- **No new meter**; `eddi.guardrail.triggered` counts the action as for every type.

### Manager and operator

- The guardrail editor offers `expression` on the output side (`guardrails.ts`: type list, phases, no-rewrite set, list-row summary `ISSUE · expr`, type text). Its sheet has fields for `expr`, `issue` and `fix`, and `expressionProblems()` mirrors `validate()`'s refusals (required, length caps, the issue-code pattern) as warnings; the expression's syntax is left to the server, which names the position at deploy. 16 new `llmEditor.grExpr*`/`grTypeExpression*` keys in all 11 locales. Tests: `guardrails-expression.test.ts` and an `expression` block in `task-guardrails-section.test.tsx`.
- The Platform Operator's prompt explains the type (parameters, the language, null rules, the reasons, closed-vocabulary feedback, deploy refusals with a position); `operator-revision.json` 36 → 37. #1121 also takes 37, so whichever merges second re-bumps.

### Next

- When #1121 lands, `expression` can use `onExhausted: deliver-best` like any reprompting type, and its `HINT_HEADER`/`HINT_FOOTER` can be shared with the judge's (same wording today).
