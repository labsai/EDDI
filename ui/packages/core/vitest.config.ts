import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // The source uses no DOM beyond fetch; jsdom is here for the tests that
    // need a page URL (`isSafeUri` resolves same-origin paths against it) and
    // for FormData/File in the attachment tests.
    environment: "jsdom",
    include: ["src/**/*.test.ts"],
  },
});
