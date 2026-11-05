// The `test` every UI-tier spec imports, with the auto fixture that fails a test
// on an API call MSW had no handler for. Shared with Workforce's E2E suite, so it
// lives in @eddi/app-kit (src/test/e2e/fixtures.ts, which explains the guard);
// specs keep importing it from here, which is what the lint rule checks.
export { test, expect } from "@eddi/app-kit/test/e2e/fixtures";
