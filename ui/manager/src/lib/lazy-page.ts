import { lazy, type ComponentType, type LazyExoticComponent } from "react";

/**
 * Key for the one-shot reload guard. Session-scoped on purpose: a genuinely
 * broken deploy must not put the tab in a reload loop, but a new session (or a
 * later deploy) should get its own retry.
 */
const RELOAD_GUARD_KEY = "eddi-chunk-reload";

/**
 * Whether a failed dynamic import looks like a stale-deploy 404 rather than a
 * bug in the chunk itself.
 *
 * Browsers do not agree on the message, and none of them give a typed error:
 * Chrome says "Failed to fetch dynamically imported module", Firefox "error
 * loading dynamically imported module", Safari "Importing a module script
 * failed". Matching loosely is right here — the recovery is a single reload, so
 * a false positive costs one refresh, while a false negative leaves the user
 * staring at an error boundary on an app that a refresh would have fixed.
 */
function looksLikeStaleChunk(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return /dynamically imported module|importing a module script|Failed to fetch/i.test(message);
}

/**
 * `React.lazy` for a module that exports its page under a NAMED export.
 *
 * ## Why the helper
 *
 * Every page in this app is a named export (`export function DashboardPage`),
 * and `React.lazy` insists on a module whose `default` is the component. Written
 * inline that is `lazy(() => import("…").then((m) => ({ default: m.X })))` at
 * every one of ~45 call sites in `app.tsx` — enough boilerplate that a mistyped
 * export name hides in it.
 *
 * Keeping this in a `.ts` file rather than beside the routes is deliberate:
 * `app.tsx` exports a component, and mixing a utility export into it trips
 * `react-refresh/only-export-components`.
 *
 * ## Why it reloads on failure
 *
 * Chunks are content-hashed, and `deploy-to-local-eddi-repo.*` deletes hashes
 * the new build did not produce. So a user holding a tab open across a deploy
 * has an entry chunk naming `dashboard-OLDHASH.js`, which is now a 404. Before
 * code splitting that could not happen: one bundle either loaded at boot or did
 * not. Now the failure surfaces on navigation, which is exactly when the user is
 * doing something.
 *
 * A reload fetches the new `manage.html`, hence the new entry chunk and the new
 * hashes, and the navigation succeeds. The guard makes it strictly one attempt —
 * if the reload does not fix it the error propagates to the `ErrorBoundary`,
 * where a real bug belongs.
 *
 * @param loader the dynamic `import()` — must be a literal call at the use site,
 *               because bundlers resolve the chunk statically and cannot follow
 *               a variable
 * @param name   the named export to pull out
 */
export function lazyPage<K extends string, M extends Record<K, ComponentType>>(
  loader: () => Promise<M>,
  name: K,
): LazyExoticComponent<ComponentType> {
  return lazy(async () => {
    try {
      const module = await loader();
      // A successful load means the current chunk set is reachable; clear the
      // guard so a future deploy gets its own retry.
      try {
        sessionStorage.removeItem(RELOAD_GUARD_KEY);
      } catch {
        // Storage unavailable (private mode, disabled cookies) — the guard is a
        // nicety, not a correctness requirement.
      }
      return { default: module[name] };
    } catch (error) {
      if (looksLikeStaleChunk(error)) {
        let alreadyTried = false;
        try {
          alreadyTried = sessionStorage.getItem(RELOAD_GUARD_KEY) === "1";
          if (!alreadyTried) sessionStorage.setItem(RELOAD_GUARD_KEY, "1");
        } catch {
          // Without storage we cannot prove this is the first attempt. Treat it
          // as already tried rather than risk a reload loop.
          alreadyTried = true;
        }
        if (!alreadyTried) {
          window.location.reload();
          // Never resolves — the reload tears the page down. Returning here
          // instead would flash an error boundary on the way out.
          return new Promise<never>(() => {});
        }
      }
      throw error;
    }
  });
}
