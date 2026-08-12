import { http, passthrough } from "msw";
import type { RequestHandler } from "msw";
import { handlers, coordinatorHandlers, orphanHandlers, logAdminHandlers, secretsHandlers, variablesHandlers, auditHandlers, quotaHandlers, scheduleHandlers, gdprHandlers, capabilityHandlers, userMemoryHandlers, propertiesHandlers, triggerHandlers, backupSyncHandlers } from "./handlers";

/**
 * URL masks the dev server owns. Anything matching these must reach the
 * network untouched.
 *
 * MSW's service worker sees *all* page requests, including the ES module
 * requests Vite serves during development. Several API handlers use broad
 * masks — a leading wildcard followed by `/agents/:conversationId`, for
 * instance — and those happily match module URLs such as
 * `/src/components/agents/agent-card.tsx`. MSW would then answer a
 * JavaScript module request with JSON, the module graph fails to load, and
 * the route's `import()` rejects with "Failed to fetch dynamically imported
 * module".
 *
 * This was latent while every page was statically imported: those module
 * requests all went out during the initial document load, before
 * `worker.start()` had activated the service worker. Route-level code
 * splitting moved them *after* activation, so the worker began intercepting
 * them.
 */
export const DEV_ASSET_PATHS = [
  // Application sources (also the lazily-imported locale JSON).
  "*/src/*",
  // Vite internals: client, HMR, resolved ids, filesystem escapes.
  "*/@vite/*",
  "*/@id/*",
  "*/@fs/*",
  "*/@react-refresh",
  // Pre-bundled dependencies (`/node_modules/.vite/deps/*`).
  "*/node_modules/*",
];

export const devAssetPassthrough: RequestHandler[] = DEV_ASSET_PATHS.map(
  (path) => http.all(path, () => passthrough()),
);

/**
 * Handlers for the in-browser worker, in resolution order. The dev-asset
 * passthroughs must stay first — MSW resolves handlers in order and
 * `passthrough()` ends the search.
 */
export const browserHandlers: RequestHandler[] = [
  ...devAssetPassthrough,
  ...handlers,
  ...coordinatorHandlers,
  ...orphanHandlers,
  ...logAdminHandlers,
  ...secretsHandlers,
  ...variablesHandlers,
  ...auditHandlers,
  ...quotaHandlers,
  ...scheduleHandlers,
  ...gdprHandlers,
  ...capabilityHandlers,
  ...userMemoryHandlers,
  ...propertiesHandlers,
  ...triggerHandlers,
  ...backupSyncHandlers,
];
