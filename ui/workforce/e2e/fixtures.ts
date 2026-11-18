// The `test` every UI-tier spec imports, with the auto fixture that fails a test
// on an API call MSW had no handler for. Shared with the Manager's E2E suite
// (@eddi/app-kit, src/test/e2e/fixtures.ts, which explains the guard).
export { test, expect } from "@eddi/app-kit/test/e2e/fixtures";
