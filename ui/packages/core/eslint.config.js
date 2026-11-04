import globals from "globals";
import tseslint from "typescript-eslint";
import { NO_ALWAYS_TRUE_LENGTH, sharedPackage } from "../../eslint.config.base.js";

// @eddi/core is framework-free: no React, no app import, no DOM global beyond
// fetch (see sharedPackage in ui/eslint.config.base.js).
export default tseslint.config(
  ...sharedPackage({ frameworkFree: true }),
  {
    // Tests may use the page (jsdom) and Node (reading the backend's Java
    // source to pin a contract); the source may not.
    files: ["**/*.test.ts"],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
    },
    rules: {
      "no-restricted-globals": "off",
      "no-restricted-syntax": ["error", NO_ALWAYS_TRUE_LENGTH],
    },
  },
);
