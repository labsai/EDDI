## 🐛 fix(manager): operator-activation test reads the provider's default model instead of a stale literal (2026-09-30)

**Repo:** EDDI (`fix/manager-operator-default-model-test`)

### What changed and why

`main` went red in `UI Manager Checks` after #896 moved the Manager's
Anthropic default model from `claude-sonnet-5` to `claude-sonnet-5-5`. One test
still hard-coded the old id: "OperatorActivation — stored provider the setup
flow no longer offers › falls back to an offered provider with its default
model and no carried key" expected `claude-sonnet-5` and received
`claude-sonnet-5-5`. Every PR opened against `main` since then inherited the
red check.

The test now asserts `getProviderConfig("anthropic").defaultModel`, the value
the component actually falls back to (`operator-activation.tsx` reads the same
function). What the test is about is *that* the fallback uses the offered
provider's default, not which model that currently is, so the next default
bump no longer breaks it. The expected value is asserted non-empty first:
`toHaveValue(undefined)` accepts any value at all, so a missing provider entry
would otherwise turn the assertion vacuous.

No product code changed. The other `claude-sonnet-5` strings in Manager tests
are fixture data, not assertions about the default, and were left alone.

**Files:** [`operator-activation.test.tsx`](../../ui/manager/src/components/operator/__tests__/operator-activation.test.tsx)
