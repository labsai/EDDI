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
import reactRefresh from "eslint-plugin-react-refresh";
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
      group: ["@/*", "eddi-manager", "eddi-manager/*", "eddi-chat-ui", "eddi-chat-ui/*", "eddi-workforce", "eddi-workforce/*"],
      message: "A shared package never imports an app. Move the code into the package, or pass it in from the app.",
    },
    {
      // A relative path out of the package into an app directory: from a file
      // under src/ that takes at least three `../` (src, the package, packages/).
      // Not `**/chat/**`: @eddi/app-kit has a components/chat/ and a
      // components/workforce/ of its own, and those are not apps.
      // core/src/boundaries.test.ts resolves every path exactly.
      regex: "^(\\.\\./){3,}(manager|chat|workforce)(/|$)",
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

/**
 * The boundary between the two signed-in apps (frontend unification plan, phase
 * 5): the Manager never imports Workforce code and Workforce never imports the
 * Manager's. What both need lives in a package (`@eddi/app-kit` for the shared
 * app layer, or one of the three below it). `no-restricted-imports` patterns
 * for an app's eslint config: `apps` names the OTHER app directories.
 *
 * The relative form catches `../../workforce/src/…` (any number of `../` then
 * the sibling app's directory); the package form catches the npm name. An app's
 * own `@/…` imports and `@eddi/app-kit/components/workforce/…` (a directory of
 * the package, not the app) are untouched. `src/__tests__/app-boundary.test.ts`
 * in each app checks the same rule over the resolved import graph.
 *
 * @param {Array<"manager" | "workforce">} apps the apps this one must not import
 */
export function appBoundary(apps) {
  const names = { manager: "eddi-manager", workforce: "eddi-workforce" };
  return apps.flatMap((app) => [
    {
      regex: `^(\\.\\./)+${app}(/|$)`,
      message: `Never import ${app} code from here: the two signed-in apps share code only through a package (@eddi/app-kit). Move it there.`,
    },
    {
      group: [names[app], `${names[app]}/*`],
      message: `Never import ${app} code from here: the two signed-in apps share code only through a package (@eddi/app-kit). Move it there.`,
    },
  ]);
}

/**
 * The lint every signed-in app (the Manager, Workforce) shares on top of
 * `reactApp()`: the react-refresh rule, the app boundary, and the rules that
 * make tests able to fail.
 *
 * @param {{ forbiddenApps: Array<"manager" | "workforce">, refreshExports?: string[] }} options
 */
export function signedInApp({ forbiddenApps, refreshExports = [] }) {
  const boundary = appBoundary(forbiddenApps);

  // ── Tests must be able to fail ────────────────────────────────────────
  //
  // These rules exist because the repo shipped tests that could not go red. A
  // review found two E2E tests whose entire body was a `test.skip()` guard,
  // nine more that skipped on exactly the failure they existed to detect, and
  // two assertions that were true of every array ever allocated. Every one of
  // them reported green.
  //
  // `no-restricted-syntax` is REPLACED, not merged, when two config objects
  // both set it — so the E2E block below has to repeat the tautology selectors
  // rather than rely on the block above. Getting that wrong is how
  // `rtl.spec.ts` sat outside the tautology rule on the first attempt: it
  // matches `**/*.spec.ts` AND `e2e/**`, and the later block silently won.
  // `no-restricted-imports` is replaced the same way, which is why the UI-spec
  // block repeats the app boundary next to its own path rule.
  const NO_TAUTOLOGY = [
    // toBeGreaterThanOrEqual(0), shared with ui/chat.
    NO_ALWAYS_TRUE_LENGTH,
    {
      // Same shape, one operator over.
      selector:
        "CallExpression[callee.property.name='toBeGreaterThan'][arguments.0.type='UnaryExpression'][arguments.0.operator='-'][arguments.0.argument.value=1]",
      message: "toBeGreaterThan(-1) is always true for a length. Assert the value you actually expect.",
    },
  ];

  const NO_CONDITIONAL_SKIP = {
    // A conditional `test.skip()` turns the failure into a pass. If a fixture
    // might not exist, create it in `beforeAll` so its absence fails the suite
    // loudly; if an editor might crash, that crash is the bug. Genuinely
    // unrunnable cases can disable this rule with a comment saying why — the
    // friction is the point.
    //
    // Covers `test.skip`, `test.fixme`, `test.slow` and `test.fail` — all four
    // turn a failure into a pass — at every callee depth the API offers:
    //
    //   test.skip                     object.name
    //   test.describe.skip            object.object.name
    //   test.describe.serial.skip     object.object.object.name
    //
    // The first version of this rule stopped at the second, which left
    // `test.describe.serial.skip` and `test.describe.parallel.fixme` silently
    // disabling whole suites with a clean lint. Enumerating depths is ugly;
    // esquery has no ancestor-axis operator, and the alternative — matching
    // any member expression rooted at `test` — is not expressible here.
    selector: [
      ":matches(",
      "CallExpression[callee.object.name='test'],",
      "CallExpression[callee.object.object.name='test'],",
      "CallExpression[callee.object.object.object.name='test']",
      ")",
      ":matches(",
      "[callee.property.name='skip'],",
      "[callee.property.name='fixme'],",
      "[callee.property.name='slow'],",
      "[callee.property.name='fail']",
      ")",
    ].join(""),
    message:
      "test.skip()/fixme()/slow()/fail() in E2E hides the failure it guards against. Create missing fixtures in beforeAll and let real failures fail. If a skip is genuinely correct, disable this rule on the line with a reason.",
  };

  const IMPORT_THE_FIXTURE = {
    // A ui spec that imports `test` from `@playwright/test` silently opts out
    // of the auto fixture that fails a test on an unhandled API call — the
    // guard would simply not be there, and nothing would say so. The
    // integration and fullstack tiers run without MSW and are excluded below.
    name: "@playwright/test",
    importNames: ["test"],
    message:
      "UI specs must import `test` from ./fixtures so the unhandled-API-call guard applies. Type-only imports and the integration/fullstack tiers are exempt.",
  };

  return [
    {
      files: ["**/*.{ts,tsx}"],
      plugins: { "react-refresh": reactRefresh },
      rules: {
        "react-refresh/only-export-components": [
          "warn",
          {
            allowConstantExport: true,
            allowExportNames: ["badgeVariants", "buttonVariants", "useTheme", ...refreshExports],
          },
        ],
        "no-restricted-imports": ["error", { patterns: boundary }],
      },
    },
    {
      files: ["**/*.test.{ts,tsx}", "**/*.spec.{ts,tsx}"],
      rules: { "no-restricted-syntax": ["error", ...NO_TAUTOLOGY] },
    },
    {
      files: ["e2e/**/*.{ts,tsx}"],
      rules: { "no-restricted-syntax": ["error", ...NO_TAUTOLOGY, NO_CONDITIONAL_SKIP] },
    },
    {
      // UI tier only — the tiers that run against a real backend have no MSW
      // and so nothing for the fixture to check.
      //
      // Matched recursively, and excluded at ANY depth — playwright.config.ts
      // uses `**/integration/**`, so anchoring these at the root would have
      // flagged a nested `e2e/foo/integration/bar.spec.ts` that Playwright
      // excludes. The original `e2e/*.spec.ts` was the top level only, while
      // the ui project is `testDir: "./e2e"` with `testIgnore` — so a spec in
      // any new subdirectory ran in the ui tier without the fixture, silently
      // losing the unhandled-API-call assertion that is the point of it.
      files: ["e2e/**/*.spec.{ts,tsx}"],
      // The three real-backend tiers are exempt: the fixture's guard asserts that
      // nothing reached an unmocked API, which is exactly what they are for.
      ignores: ["e2e/**/integration/**", "e2e/**/fullstack/**", "e2e/**/auth/**"],
      rules: {
        "no-restricted-imports": ["error", { paths: [IMPORT_THE_FIXTURE], patterns: boundary }],
      },
    },
  ];
}
