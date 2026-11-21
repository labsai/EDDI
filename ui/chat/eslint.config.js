import globals from "globals";
import tseslint from "typescript-eslint";
import { NO_ALWAYS_TRUE_LENGTH, reactApp } from "../eslint.config.base.js";

// The same base as ui/manager (ui/eslint.config.base.js): ESLint and
// typescript-eslint recommended, plus the rules of hooks. The Chat UI had no lint
// configuration at all, so hook dependency mistakes and unused code reached
// review unflagged.
export default tseslint.config(
  ...reactApp({ ecmaVersion: 2022 }),
  {
    files: ["**/*.{ts,tsx}"],
    rules: {
      // A leading underscore marks a parameter kept for its position.
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_" }],
    },
  },
  {
    // Tests must be able to fail: the same tautology guard as ui/manager.
    files: ["**/*.test.{ts,tsx}"],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
    },
    rules: {
      "no-restricted-syntax": ["error", NO_ALWAYS_TRUE_LENGTH],
    },
  },
);
