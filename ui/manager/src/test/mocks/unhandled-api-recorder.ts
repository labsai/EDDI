import type { UnhandledFrameHandle } from "msw";
import { HttpNetworkFrame } from "msw/experimental";
import { DEV_ASSET_PATHS } from "./browser-handlers";
import { UNHANDLED_API_REQUESTS_KEY } from "./unhandled-api";

/**
 * Records API calls MSW had no handler for, so the E2E tier can fail the test
 * that caused them.
 *
 * Its own module, separate from `browser.ts`, for the same reason the key is:
 * `browser.ts` calls `setupWorker()` at module scope, which throws outside a
 * browser — so anything living there is unreachable from a unit test. This is a
 * guard, and a guard nothing can test is the exact shape of bug the surrounding
 * work exists to remove. See `__tests__/unhandled-api-recorder.test.ts`.
 */

/**
 * Paths the dev server owns, derived from the passthrough handlers rather than
 * restated — a hand-synced second copy is the failure mode `unhandled-api.ts`
 * exists to avoid, and restating it here would have been that same mistake one
 * file over. `browser-handlers.ts` claims these URLs first with `passthrough()`,
 * so they should never reach this function; the list is belt-and-braces for a
 * Vite internal that appears later and is not yet passed through.
 */
const DEV_ASSET_PREFIXES = DEV_ASSET_PATHS.map((p) =>
  p.replace(/^\*/, "").replace(/\*$/, ""),
);

/**
 * Static assets the app serves from `public/` or imports as URLs.
 *
 * MSW normally filters these itself with `isCommonAssetRequest` — but only when
 * `onUnhandledFrame` is one of the string strategies. Passing a **function**
 * makes `the unhandled-frame handler` invoke the callback and `return` before that
 * filter is ever reached, so every asset lands here. Without this, the single
 * `<link rel="icon" href="/eddi-icon.svg">` in `index.html` fails every test
 * that reloads the page.
 */
const ASSET_EXTENSIONS =
  /\.(svg|png|jpe?g|gif|ico|webp|avif|woff2?|ttf|eot|css|m?js|map|txt|webmanifest)$/i;

/**
 * Record an API call that no MSW handler answered, then let it through.
 *
 * Only same-origin requests count, and only ones that look like API calls.
 *
 * There is deliberately **no** `request.destination` check. An earlier version
 * had one, reasoning that `fetch`/XHR report `""` while browser-initiated
 * subresources name a type. That is true of a real Request — but MSW's worker
 * serialises the request and the client rebuilds it with
 * `new Request(url, {...serialized})`, and `destination` is not a `RequestInit`
 * member, so the reconstructed Request always reports `""`. The check could
 * never fire, which made it worse than nothing: it read as a filter while
 * filtering nothing. Extension matching is what actually works here.
 *
 * Bypassing rather than erroring is deliberate: erroring would break the dev
 * server for a human running `npm run dev`, and the point is to make the gap
 * visible *to the test suite*, not to change how the app behaves.
 */
export function recordUnhandledApiRequest(
  request: Request,
  print: { warning: () => void },
) {
  const url = new URL(request.url);
  if (url.origin !== window.location.origin) return;
  if (DEV_ASSET_PREFIXES.some((prefix) => url.pathname.startsWith(prefix))) return;
  if (ASSET_EXTENSIONS.test(url.pathname)) return;
  // A page load or reload is not an API call. MSW 2's worker script skipped
  // navigations itself; MSW 3's hands them to the handlers, so they arrive here.
  // Browsers ask for documents with `Accept: text/html`; the app's `fetch` calls
  // ask for JSON, an event stream or `*/*`.
  if (request.method === "GET" && request.headers.get("accept")?.includes("text/html")) return;

  const record = `${request.method} ${url.pathname}${url.search}`;

  try {
    const raw = sessionStorage.getItem(UNHANDLED_API_REQUESTS_KEY);
    const seen: string[] = raw ? JSON.parse(raw) : [];
    // Deduplicate: a polled endpoint with no handler (the coordinator refetches
    // every 5s) would otherwise repeat until it crowds out the failure message
    // and, in a long test, approaches the storage quota — at which point the
    // catch below silently downgrades to the per-document fallback.
    if (!seen.includes(record)) {
      seen.push(record);
      sessionStorage.setItem(UNHANDLED_API_REQUESTS_KEY, JSON.stringify(seen));
    }
  } catch {
    // Storage unavailable or full. Fall back to the current document so the
    // record is not simply lost — the fixture reads both and concatenates, and
    // this array stays empty on the normal path.
    const store = window as unknown as Record<string, string[] | undefined>;
    const list = (store[UNHANDLED_API_REQUESTS_KEY] ??= []);
    if (!list.includes(record)) list.push(record);
  }

  print.warning();
}

/**
 * The `onUnhandledFrame` callback for `worker.start()`.
 *
 * MSW 3 replaced `onUnhandledRequest(request, print)` with
 * `onUnhandledFrame({ frame, defaults })`: the callback now sees a network
 * frame of any protocol, and `defaults.warn()` is the old `print.warning()`.
 * Only HTTP frames carry a `Request`; WebSocket frames are not API calls and
 * are left alone. The filtering and recording live in
 * {@link recordUnhandledApiRequest}, which stays protocol-agnostic and testable
 * with a plain `Request`.
 */
export const recordUnhandledApiFrame: Exclude<UnhandledFrameHandle, string> = ({
  frame,
  defaults,
}) => {
  if (!(frame instanceof HttpNetworkFrame)) return;
  recordUnhandledApiRequest(frame.data.request, { warning: defaults.warn });
};
