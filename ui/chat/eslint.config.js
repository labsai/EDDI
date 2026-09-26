import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import tseslint from "typescript-eslint";

// The same base as ui/manager/eslint.config.js, so one rule means the same thing
// in both UIs. The Manager's E2E-only blocks (conditional skips, the MSW fixture
// import) are left out: this app has no Playwright suite.
export default tseslint.config(
  { ignores: ["dist"] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ["**/*.{ts,tsx}"],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    plugins: {
      "react-hooks": reactHooks,
      "react-refresh": reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      "react-refresh/only-export-components": [
        "warn",
        {
          allowConstantExport: true,
          // chat-store.tsx keeps the provider next to its reducer and hooks.
          allowExportNames: ["initialState", "chatReducer", "useChatState", "useChatDispatch"],
        },
      ],
      // A leading underscore marks a parameter a mock's signature needs but its
      // body does not read (useHitlPolling.test.ts types its callbacks that way).
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_" }],
    },
  },
  {
    // The entry point renders the app; it exports nothing and is never hot-swapped.
    files: ["src/main.tsx"],
    rules: { "react-refresh/only-export-components": "off" },
  },
  {
    // Tests must be able to fail — see the matching block in the Manager's config.
    files: ["**/*.test.{ts,tsx}"],
    rules: {
      "no-restricted-syntax": [
        "error",
        {
          selector:
            "CallExpression[callee.property.name='toBeGreaterThanOrEqual'][arguments.0.type='Literal'][arguments.0.value=0]",
          message:
            "toBeGreaterThanOrEqual(0) is always true for a length or count. Assert the value you actually expect.",
        },
        {
          selector:
            "CallExpression[callee.property.name='toBeGreaterThan'][arguments.0.type='UnaryExpression'][arguments.0.operator='-'][arguments.0.argument.value=1]",
          message: "toBeGreaterThan(-1) is always true for a length. Assert the value you actually expect.",
        },
      ],
    },
  },
);
