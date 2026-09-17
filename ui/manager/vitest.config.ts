import { defineConfig, mergeConfig } from "vitest/config";
import viteConfig from "./vite.config";

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      globals: true,
      environment: "jsdom",
      setupFiles: ["./src/test/setup.ts"],
      testTimeout: 30_000,
      css: true,
      // `.claude/worktrees/**` holds checkouts of other branches. Their test
      // files resolve `@/` through this config, so without excluding them a
      // local run executes another branch's tests against this branch's mocks.
      //
      // `.stryker-tmp/**` is the same trap with a different source: Stryker
      // copies the whole repo into a sandbox per test runner, and only cleans
      // up after a run that finishes. Interrupt one — Ctrl-C, a timeout, a
      // failed threshold — and the next `npm run test` collects every sandbox
      // as well, which measured 2,320 files and 28,223 tests against a real
      // 358 and 5,543, with 261 files failing because a mutated copy of the
      // source was still in place.
      //
      // `.worktrees/**` is the trap a third time: `git worktree add
      // .worktrees/<branch>` inside the main checkout puts a whole second tree
      // under the repo root, and a stale one — a branch merged and forgotten —
      // is swept by every `vitest run` from then on, failing on whatever the
      // two branches disagree about.
      exclude: [
        "e2e/**",
        "node_modules/**",
        ".claude/**",
        ".stryker-tmp/**",
        ".worktrees/**",
      ],
      server: {
        deps: {
          // monaco-editor is ~40 MB; tests mock @monaco-editor/react so
          // the real package must never be loaded in the test environment.
          external: ["monaco-editor"],
        },
      },
      coverage: {
        provider: "v8",
        reporter: ["text", "json", "html", "lcov"],
        include: ["src/**/*.{ts,tsx}"],
        exclude: [
          "src/test/**",
          "src/**/*.d.ts",
          "src/main.tsx",
          "src/app.tsx",
          "src/lib/auth-config.ts",
        ],
        // Recalibrated for Vitest 4, not relaxed. Vitest 3's v8-to-istanbul
        // counted every source LINE as a statement — so JSX markup, which runs
        // on every render, padded both figures (main measured 90.25 / 90.25).
        // Vitest 4 remaps against the AST and counts real statements: the same
        // code reads 83.45% lines and 81.83% statements with no test or source
        // change. The uncovered code did not change either — view-toggle.tsx
        // was flagged at lines 23-33 before and after; only the denominator
        // moved. Branches (84.03 -> 76.44) and functions (74.29 -> 76.37)
        // still clear their floors, so those are left alone.
        thresholds: {
          lines: 83,
          branches: 75,
          functions: 70,
          statements: 81,
        },
      },
    },
  })
);
