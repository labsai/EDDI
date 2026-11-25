import globals from "globals";
import tseslint from "typescript-eslint";
import { NO_ALWAYS_TRUE_LENGTH, NO_EDDI_GLOBAL, sharedPackage } from "../../eslint.config.base.js";

// @eddi/ui may use React, but never imports an app, never calls the backend
// (no @eddi/core, no fetch) and never reads window.__EDDI_*__ (see
// sharedPackage in ui/eslint.config.base.js).
export default tseslint.config(
  ...sharedPackage({ frameworkFree: false, react: true, noApi: true }),
  {
    // Tests may read files (Node) to pin a stylesheet against a table.
    files: ["**/*.test.{ts,tsx}", "src/test-setup.ts"],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
    },
    rules: {
      "no-restricted-syntax": ["error", NO_EDDI_GLOBAL, NO_ALWAYS_TRUE_LENGTH],
    },
  },
);
