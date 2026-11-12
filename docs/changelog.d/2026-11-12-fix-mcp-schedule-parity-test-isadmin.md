## Fix main's test compile: McpScheduleParityTest stubs isAdmin() (2026-11-12)

**Repo:** EDDI · **Branch:** `fix/mcp-schedule-parity-test-isadmin`

**What changed:** `src/test/java/ai/labs/eddi/engine/schedule/rest/McpScheduleParityTest.java` stubbed `ResourceAccessGuard.callerIsAdmin()`, which no longer exists. #1352 renamed it to `isAdmin()`, and #1345 (which added this test) was written against the old name. Each PR was green on its own, but once both were squash-merged into main, `testCompile` failed there. The stub now targets `isAdmin()`, the method `RestScheduleStore` actually calls.

**Design decisions:** test-only change; no production code touched.
