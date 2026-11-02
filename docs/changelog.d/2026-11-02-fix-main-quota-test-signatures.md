## 🐛 fix(test): `toolQuotas` tests follow `executeToolCall` and `truncateIfNeeded` (2026-11-02)

**Repo:** EDDI (`fix/review-infra`, folded into #1185 so it lands with a reviewed PR)

### What changed and why

#1210 (`toolQuotas`) and #1192 (spend recorded on every model-phase exit) each passed CI on their own, but
together `main` no longer compiled its tests: #1192 added a `DoubleSupplier runSpend` parameter to
`ToolLoopRunner.executeToolCall` and a seventh parameter to `ToolResponseTruncator.truncateIfNeeded`, and
`ToolLoopRunnerQuotaTest` still called the old shapes. The two direct calls now pass
`ToolLoopRunner.NO_RUN_SPEND`, and the truncator stub matches the seven-argument overload the runner uses (the
old stub was never hit, so the mock returned `null` and five tests failed with an NPE on `governed()`).
`ToolQuotaHitlTest` had the same two stale stubs: `checkCostBudget(any())` (now `checkCostBudget(any(), anyDouble())`, the
two-argument form the runner calls) and the six-argument `truncateIfNeeded`; its three resume tests failed with an NPE
in CI. Test-only change.
