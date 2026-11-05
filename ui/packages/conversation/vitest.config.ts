import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // The controller tests need no page; the components do.
    environment: "jsdom",
    include: ["src/**/*.test.{ts,tsx}"],
    setupFiles: ["./src/test-setup.ts"],
  },
});
