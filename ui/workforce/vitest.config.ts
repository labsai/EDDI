import { defineConfig, mergeConfig } from "vitest/config";
import { fileURLToPath, URL } from "node:url";
import viteConfig from "./vite.config.ts";

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      globals: true,
      environment: "jsdom",
      // The shared setup (mocked Keycloak and Monaco, the MSW server over the
      // mock API, @eddi/app-kit) first, then Workforce's own (its i18n).
      setupFiles: [
        fileURLToPath(new URL("../packages/app-kit/src/test/setup-common.ts", import.meta.url)),
        "./src/test/setup.ts",
      ],
      testTimeout: 30_000,
      css: true,
      // Other checkouts and sandboxes under this directory (worktrees, Stryker)
      // would otherwise be collected and run against this branch's mocks — the
      // Manager's vitest.config.ts has the measured history of each.
      exclude: ["e2e/**", "node_modules/**", ".claude/**", ".stryker-tmp/**", ".worktrees/**"],
      server: {
        deps: {
          // Tests mock @monaco-editor/react, so the ~40 MB package must never load.
          external: ["monaco-editor"],
        },
      },
      coverage: {
        provider: "v8",
        reporter: ["text", "json", "html", "lcov"],
        include: ["src/**/*.{ts,tsx}"],
        exclude: ["src/test/**", "src/**/*.d.ts", "src/main.tsx", "src/app.tsx"],
      },
    },
  }),
);
