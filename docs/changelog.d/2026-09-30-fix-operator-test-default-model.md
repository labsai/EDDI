## 🧪 test(manager): the operator fallback test expects the new default Anthropic model (2026-09-30)

**Repo:** EDDI (`fix/operator-test-default-model`)

### Why

`main` has failed `UI Manager Checks` since #903 merged. #896 made `claude-sonnet-5-5` the first
Anthropic model suggestion, and the operator setup falls back to a provider's first suggestion
when its stored provider is no longer offered. `operator-activation.test.tsx` still expected
`claude-sonnet-5`. #896's own CI ran before that test existed on its base, so neither PR saw the
combination.

### What changed

- `ui/manager/src/components/operator/__tests__/operator-activation.test.tsx`: the fallback case
  expects `claude-sonnet-5-5`. No production code changes.
