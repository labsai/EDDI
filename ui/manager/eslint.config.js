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
      selector:
        "CallExpression[callee.object.name='test'][callee.property.name='skip']",
      message:
        "test.skip() in E2E hides the failure it guards against. Create missing fixtures in beforeAll and let real failures fail. If a skip is genuinely correct, disable this rule on the line with a reason.",
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
    ];
  })()
);
