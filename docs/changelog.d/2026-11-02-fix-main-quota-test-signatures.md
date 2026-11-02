## 🐛 fix(test): `ToolLoopRunnerQuotaTest` follows `executeToolCall` and `truncateIfNeeded` (2026-11-02)

**Repo:** EDDI (`fix/main-quota-test-run-spend`)

### What changed and why

#1210 (`toolQuotas`) and #1192 (spend recorded on every model-phase exit) each passed CI on their own, but
together `main` no longer compiled its tests: #1192 added a `DoubleSupplier runSpend` parameter to
`ToolLoopRunner.executeToolCall` and a seventh parameter to `ToolResponseTruncator.truncateIfNeeded`, and
`ToolLoopRunnerQuotaTest` still called the old shapes. The two direct calls now pass
`ToolLoopRunner.NO_RUN_SPEND`, and the truncator stub matches the seven-argument overload the runner uses (the
old stub was never hit, so the mock returned `null` and five tests failed with an NPE on `governed()`).
Test-only change.
