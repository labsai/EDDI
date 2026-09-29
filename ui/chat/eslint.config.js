import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import tseslint from "typescript-eslint";

// The same base as ui/manager: ESLint and typescript-eslint recommended, plus
// the rules of hooks. The Chat UI had no lint configuration at all, so hook
// dependency mistakes and unused code reached review unflagged.
export default tseslint.config(
  { ignores: ["dist", "node_modules"] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ["**/*.{ts,tsx}"],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: {
      "react-hooks": reactHooks,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
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
      "no-restricted-syntax": [
        "error",
        {
          selector:
            "CallExpression[callee.property.name='toBeGreaterThanOrEqual'][arguments.0.type='Literal'][arguments.0.value=0]",
          message:
            "toBeGreaterThanOrEqual(0) is always true for a length or count. Assert the value you actually expect.",
        },
      ],
    },
  },
);
