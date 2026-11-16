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
 * `src/boundaries.test.ts` in `@eddi/core` checks the same rules and the
 * absence of import cycles across every package.
 *
 * A React package (`react: true`, as `@eddi/ui` is) lints `.tsx` with the rules
 * of hooks. With `noApi: true` (also `@eddi/ui`) it may not call the backend:
 * no `@eddi/core` import, no `fetch`/`XMLHttpRequest`/`EventSource`/`WebSocket`,
 * and no `window.__EDDI_*__` — what the server says reaches it as props or
 * options from the app.
 *
 * With `routerFree: true` (`@eddi/conversation`) it imports no router — hosts
 * pass ids and callbacks — and reads no `window.__EDDI_*__` either; it may call
 * the backend through `@eddi/core`.
 *
 * @param {{ frameworkFree: boolean, react?: boolean, noApi?: boolean, routerFree?: boolean }} options
 */
export function sharedPackage({ frameworkFree, react = false, noApi = false, routerFree = false }) {
  const appImports = [
    {
      group: ["@/*", "**/manager/**", "**/chat/**", "**/workforce/**"],
      message: "A shared package never imports an app. Move the code into the package, or pass it in from the app.",
    },
  ];
  const apiImports = noApi
    ? [
        {
          group: ["@eddi/core", "@eddi/core/*", "@eddi/conversation", "@eddi/conversation/*"],
          message: "@eddi/ui makes no API calls and sits below @eddi/conversation. Take the data as a prop or option from the app.",
        },
      ]
    : [];
  const apiGlobals = noApi
    ? ["fetch", "XMLHttpRequest", "EventSource", "WebSocket"].map((name) => ({
        name,
        message: `@eddi/ui makes no API calls ('${name}'). The app fetches and passes the result in.`,
      }))
    : [];
  const routerImports = routerFree
    ? [
        {
          group: ["react-router", "react-router/*", "react-router-dom", "react-router-dom/*"],
          message: "@eddi/conversation is router-free: the host passes ids and callbacks (onNavigate, …) instead.",
        },
      ]
    : [];
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
      files: react ? ["**/*.{ts,tsx}"] : ["**/*.ts"],
      languageOptions: {
        ecmaVersion: 2022,
        globals: globals.browser,
      },
      plugins: react ? { "react-hooks": reactHooks } : {},
      rules: {
        "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_" }],
        "no-restricted-imports": ["error", { patterns: [...appImports, ...frameworkImports, ...apiImports, ...routerImports] }],
        "no-restricted-globals": ["error", ...domGlobals, ...apiGlobals],
        ...(noApi || routerFree ? { "no-restricted-syntax": ["error", NO_EDDI_GLOBAL, NO_ALWAYS_TRUE_LENGTH] } : {}),
        ...(react
          ? {
              "react-hooks/rules-of-hooks": "error",
              "react-hooks/exhaustive-deps": "warn",
            }
          : {}),
      },
    },
  ];
}

/**
 * `window.__EDDI_BRAND__`, `window.__EDDI_CHAT__` and the rest are what the
 * server injects into an app's page. A shared package never reads them: the app
 * does, and passes the value in. A `no-restricted-syntax` entry (see
 * NO_ALWAYS_TRUE_LENGTH on why every block setting that rule lists all of its).
 */
export const NO_EDDI_GLOBAL = {
  selector: "MemberExpression[property.name=/^__EDDI_/]",
  message: "A shared package never reads window.__EDDI_*__. The app reads it and passes the value in.",
};

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
