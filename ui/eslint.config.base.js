// The lint base both apps under ui/ share: ESLint and typescript-eslint
// recommended over TypeScript, browser globals, and the two classic hook rules.
// Each app's eslint.config.js spreads `reactApp()` first and adds what is its
// own after it (the Manager's react-refresh rule and E2E guards, the Chat UI's
// unused-argument pattern), so a later block still wins where both set a rule.
//
// The imports resolve from ui/node_modules, the npm workspaces root.
import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import tseslint from "typescript-eslint";

/**
 * The shared base, as flat-config objects for `tseslint.config(...)`.
 *
 * @param {{ ecmaVersion: number }} options `ecmaVersion` stays per app: the
 *   Manager parses as 2020 and the Chat UI as 2022, and unifying them is a
 *   lint change of its own.
 */
export function reactApp({ ecmaVersion }) {
  return [
    { ignores: ["dist"] },
    {
      extends: [js.configs.recommended, ...tseslint.configs.recommended],
      files: ["**/*.{ts,tsx}"],
      languageOptions: {
        ecmaVersion,
        globals: globals.browser,
      },
      plugins: {
        "react-hooks": reactHooks,
      },
      rules: {
        // The two classic hook rules, pinned by name. eslint-plugin-react-hooks 7
        // folded the React Compiler rules (set-state-in-effect, refs, purity,
        // immutability, static-components, preserve-manual-memoization) into its
        // `recommended` preset; spreading that preset here would switch on 119
        // findings across the Manager in a dependency upgrade. Adopting them is a
        // change of its own, rule by rule.
        "react-hooks/rules-of-hooks": "error",
        "react-hooks/exhaustive-deps": "warn",
      },
    },
  ];
}

/**
 * The base for a shared package under ui/packages/: the same recommended rules
 * without React, plus the boundaries the frontend unification plan (§3.1) sets.
 * Apps may import packages; a package never imports an app, and a package that
 * declares itself framework-free (`frameworkFree: true`, as `@eddi/core` does)
 * imports neither React nor `@eddi/ui` and touches no DOM global beyond fetch.
 * `src/__tests__/boundaries.test.ts` in `@eddi/core` checks the same rules and
 * the absence of import cycles across every package.
 *
 * @param {{ frameworkFree: boolean }} options
 */
export function sharedPackage({ frameworkFree }) {
  const appImports = [
    {
      group: ["@/*", "**/manager/**", "**/chat/**", "**/workforce/**"],
      message: "A shared package never imports an app. Move the code into the package, or pass it in from the app.",
    },
  ];
  const frameworkImports = frameworkFree
    ? [
        {
          group: ["react", "react/*", "react-dom", "react-dom/*", "@eddi/ui", "@eddi/ui/*", "@eddi/conversation", "@eddi/conversation/*"],
          message: "@eddi/core is framework-free: no React and no UI package. Put React code in @eddi/conversation or the app.",
        },
      ]
    : [];
  const domGlobals = frameworkFree
    ? ["window", "document", "localStorage", "sessionStorage", "navigator", "history"].map((name) => ({
        name,
        message: `@eddi/core uses no DOM beyond fetch. Take what you need from '${name}' as a parameter from the app.`,
      }))
    : [];
  return [
    { ignores: ["dist"] },
    {
      extends: [js.configs.recommended, ...tseslint.configs.recommended],
      files: ["**/*.ts"],
      languageOptions: {
        ecmaVersion: 2022,
        globals: globals.browser,
      },
      rules: {
        "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_" }],
        "no-restricted-imports": ["error", { patterns: [...appImports, ...frameworkImports] }],
        "no-restricted-globals": ["error", ...domGlobals],
      },
    },
  ];
}

/**
 * `expect(xs.length).toBeGreaterThanOrEqual(0)` asserts nothing: it holds for
 * every array. It reads like a check, which is worse than no check at all. For
 * "indexOf found it", use `toContain`; for "this loaded something", assert what
 * it loaded. A geometry coordinate that could legitimately be negative is the one
 * real exception — disable the rule on that line and say why.
 *
 * A `no-restricted-syntax` entry. That rule is REPLACED, not merged, when two
 * config objects set it, so every block that sets it must list this again.
 */
export const NO_ALWAYS_TRUE_LENGTH = {
  selector:
    "CallExpression[callee.property.name='toBeGreaterThanOrEqual'][arguments.0.type='Literal'][arguments.0.value=0]",
  message:
    "toBeGreaterThanOrEqual(0) is always true for a length or count. Assert the value you actually expect — toContain() for a found substring, or the specific content for a loaded list. If the receiver can genuinely be negative (a coordinate), disable this line with a reason.",
};
