import globals from "globals";
import tseslint from "typescript-eslint";
import { NO_ALWAYS_TRUE_LENGTH, NO_EDDI_GLOBAL, sharedPackage } from "../../eslint.config.base.js";

// @eddi/conversation may use React and @eddi/core, but never imports an app or
// a router (hosts pass ids and callbacks) and never reads window.__EDDI_*__
// (see sharedPackage in ui/eslint.config.base.js).
export default tseslint.config(
  ...sharedPackage({ frameworkFree: false, react: true, routerFree: true }),
  {
    // Tests may read files (Node) to pin locale files against each other.
    files: ["**/*.test.{ts,tsx}", "src/test-setup.ts"],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
    },
    rules: {
      "no-restricted-syntax": ["error", NO_EDDI_GLOBAL, NO_ALWAYS_TRUE_LENGTH],
    },
  },
);
