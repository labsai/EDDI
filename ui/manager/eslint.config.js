import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import tseslint from "typescript-eslint";

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
          allowExportNames: [
            "badgeVariants",
            "buttonVariants",
            "useTheme",
          ],
        },
      ],
    },
  },

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
  ...(() => {
    const NO_TAUTOLOGY = [
      {
        // `expect(xs.length).toBeGreaterThanOrEqual(0)` asserts nothing: it
        // holds for every array. It reads like a check, which is worse than no
        // check at all. For "indexOf found it", use `toContain`; for "this
        // loaded something", assert what it loaded. A geometry coordinate that
        // could legitimately be negative is the one real exception — disable
        // the rule on that line and say why.
        selector:
          "CallExpression[callee.property.name='toBeGreaterThanOrEqual'][arguments.0.type='Literal'][arguments.0.value=0]",
        message:
          "toBeGreaterThanOrEqual(0) is always true for a length or count. Assert the value you actually expect — toContain() for a found substring, or the specific content for a loaded list. If the receiver can genuinely be negative (a coordinate), disable this line with a reason.",
      },
      {
        // Same shape, one operator over.
        selector:
          "CallExpression[callee.property.name='toBeGreaterThan'][arguments.0.type='UnaryExpression'][arguments.0.operator='-'][arguments.0.argument.value=1]",
        message:
          "toBeGreaterThan(-1) is always true for a length. Assert the value you actually expect.",
      },
    ];

    const NO_CONDITIONAL_SKIP = {
      // A conditional `test.skip()` turns the failure into a pass. If a fixture
      // might not exist, create it in `beforeAll` so its absence fails the suite
      // loudly; if an editor might crash, that crash is the bug. Genuinely
      // unrunnable cases can disable this rule with a comment saying why — the
      // friction is the point.
      //
      // Covers `test.skip`, `test.fixme` and `test.slow` (all three silence a
      // failure) and the `test.describe.skip` / `test.describe.fixme` forms,
      // where the callee's object is itself a MemberExpression — a selector
      // matching only `[callee.object.name='test']` misses those entirely,
      // leaving three equally effective escape hatches open.
      selector: [
        ":matches(",
        "CallExpression[callee.object.name='test'],",
        "CallExpression[callee.object.object.name='test']",
        ")",
        ":matches(",
        "[callee.property.name='skip'],",
        "[callee.property.name='fixme'],",
        "[callee.property.name='slow']",
        ")",
      ].join(""),
      message:
        "test.skip()/fixme()/slow() in E2E hides the failure it guards against. Create missing fixtures in beforeAll and let real failures fail. If a skip is genuinely correct, disable this rule on the line with a reason.",
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
        files: ["**/*.test.{ts,tsx}", "**/*.spec.{ts,tsx}"],
        rules: { "no-restricted-syntax": ["error", ...NO_TAUTOLOGY] },
      },
      {
        files: ["e2e/**/*.{ts,tsx}"],
        rules: {
          "no-restricted-syntax": [
            "error",
            ...NO_TAUTOLOGY,
            NO_CONDITIONAL_SKIP,
          ],
        },
      },
      {
        // UI tier only — the tiers that run against a real backend have no MSW
        // and so nothing for the fixture to check.
        files: ["e2e/*.spec.ts"],
        rules: {
          "no-restricted-imports": ["error", { paths: [IMPORT_THE_FIXTURE] }],
        },
      },
    ];
  })()
);
