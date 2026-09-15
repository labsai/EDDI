import { describe, expect, it } from "vitest";
import {
  browserHandlers,
  devAssetPassthrough,
} from "@/test/mocks/browser-handlers";

const ORIGIN = "http://localhost:3000";

/** The first handler that claims `url`, or undefined if none does. */
async function firstMatch(url: string) {
  const request = new Request(url);
  for (const handler of browserHandlers) {
    if (await handler.test({ request })) return handler;
  }
  return undefined;
}

/**
 * Route-level code splitting means Vite's ES module requests are issued
 * *after* the MSW worker is active, so the worker sees them. Broad API masks
 * (a leading wildcard plus `/agents/:conversationId`, say) match module URLs
 * like `/src/components/agents/agent-card.tsx`; answering those with JSON
 * breaks the module graph and the route's `import()` rejects with
 * "Failed to fetch dynamically imported module".
 */
describe("browser MSW handlers — dev asset passthrough", () => {
  const devAssetUrls = [
    // The URL that actually broke: a broad `/agents/:param` mask claimed it.
    `${ORIGIN}/src/components/agents/agent-card.tsx`,
    `${ORIGIN}/src/pages/agents.tsx`,
    `${ORIGIN}/src/pages/workforce/workforce-chat.tsx`,
    // Lazily-imported locale JSON.
    `${ORIGIN}/src/i18n/locales/de.json`,
    // Vite internals.
    `${ORIGIN}/@vite/client`,
    `${ORIGIN}/@id/__x00__virtual:module`,
    `${ORIGIN}/@fs/C:/repo/node_modules/foo/index.js`,
    `${ORIGIN}/@react-refresh`,
    `${ORIGIN}/node_modules/.vite/deps/react.js`,
  ];

  it.each(devAssetUrls)("passes %s through to the network", async (url) => {
    const handler = await firstMatch(url);
    expect(handler).toBeDefined();
    expect(devAssetPassthrough).toContain(handler);
  });

  it("keeps the passthroughs ahead of every API handler", () => {
    const firstApiIndex = browserHandlers.findIndex(
      (h) => !devAssetPassthrough.includes(h),
    );
    const lastPassthroughIndex = browserHandlers.reduce(
      (last, h, i) => (devAssetPassthrough.includes(h) ? i : last),
      -1,
    );
    expect(lastPassthroughIndex).toBeLessThan(firstApiIndex);
  });

  it("still lets API handlers claim real backend routes", async () => {
    for (const url of [
      `${ORIGIN}/agentstore/agents/descriptors`,
      `${ORIGIN}/agents/pending-approvals`,
    ]) {
      const handler = await firstMatch(url);
      expect(handler, url).toBeDefined();
      expect(devAssetPassthrough, url).not.toContain(handler);
    }
  });
});
