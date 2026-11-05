import { setupWorker } from "msw/browser";
import { browserHandlers } from "./browser-handlers";
import { UNHANDLED_API_REQUESTS_KEY } from "./unhandled-api";
import { recordUnhandledApiRequest } from "./unhandled-api-recorder";

export const worker = setupWorker(...browserHandlers);

/**
 * Re-exported so `main.tsx` has a single import for the worker and its
 * unhandled-request callback. Both live in their own modules because
 * `setupWorker()` above runs at module scope and throws outside a browser —
 * anything defined here is unreachable from a unit test, and a guard nothing
 * can test is the shape of bug this work exists to remove.
 *
 * @see ./unhandled-api-recorder — the callback, and why it filters as it does
 * @see ./unhandled-api — the storage key, shared with `e2e/fixtures.ts`
 */
export { UNHANDLED_API_REQUESTS_KEY, recordUnhandledApiRequest };
