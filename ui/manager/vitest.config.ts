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
      exclude: ["e2e/**", "node_modules/**"],
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
