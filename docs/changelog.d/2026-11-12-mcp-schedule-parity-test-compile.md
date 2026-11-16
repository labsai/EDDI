## fix(test): McpScheduleParityTest compiles again on main (2026-11-12)

**Repo:** EDDI (`fix/mcp-schedule-parity-test-compile`)

Two merges crossed: #1352 removed `ResourceAccessGuard.callerIsAdmin()`, a duplicate of `isAdmin()` (both return `ownershipValidator.isAdmin(identity)`), and #1345 added `McpScheduleParityTest`, which stubs `callerIsAdmin()`. Each was green on its own branch, but together the test sources on `main` no longer compiled, so `Build & Test` failed on `main` and on every pull request merged against it. The test now stubs `isAdmin()`. No production code changed.
