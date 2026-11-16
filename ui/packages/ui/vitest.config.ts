import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // The theme provider and the i18n setup write to the document (`class`,
    // `data-theme`, `dir`, `lang`), so the tests need a page.
    environment: "jsdom",
    include: ["src/**/*.test.{ts,tsx}"],
    setupFiles: ["./src/test-setup.ts"],
  },
});
