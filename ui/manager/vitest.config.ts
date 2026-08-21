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
      exclude: ["e2e/**", "node_modules/**", ".claude/**", ".stryker-tmp/**"],
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
        thresholds: {
          lines: 85,
          branches: 75,
          functions: 70,
          statements: 85,
        },
      },
    },
  })
);
