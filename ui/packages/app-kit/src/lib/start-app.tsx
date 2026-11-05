import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider, type createBrowserRouter } from "react-router-dom";
import type { i18n as I18n } from "i18next";
// Types only: msw itself is loaded by the app's `loadMocks`, in dev mode alone.
import type { SetupWorker, StartOptions } from "msw/browser";
import { installLocaleFonts } from "@eddi/ui/fonts";

/** How long the first paint waits for a locale's font CSS before going ahead. */
const LOCALE_FONT_WAIT_MS = 3000;

/**
 * Force the mock API on regardless of whether a backend answers.
 *
 * Set by the Playwright `ui` project through `storageState`, which lands in
 * localStorage before any page script runs. That tier is documented as "browser
 * + MSW mocks, no backend", but the probe below decides that at runtime — so on
 * any machine with EDDI running (a normal state for this repo: the compose files
 * and the full-stack tier both want one) the "no backend" tier silently drove the
 * real API instead. Every assertion written against a fixture value then failed
 * for a reason that has nothing to do with the branch under test, and any that
 * passed were validating real data while claiming to validate mocks.
 */
function mocksForced(): boolean {
  try {
    return localStorage.getItem("eddi-force-mocks") === "true";
  } catch {
    // Storage can be unavailable (private mode, blocked cookies) — that is not
    // a reason to fail startup, and the probe below still decides correctly.
    return false;
  }
}

/** The MSW browser worker an app starts in dev mode when no backend answers. */
export interface MockWorker {
  worker: SetupWorker;
  recordUnhandledApiRequest: NonNullable<StartOptions["onUnhandledRequest"]>;
}

export interface StartAppOptions {
  /** The app's data router (`createBrowserRouter`), built by the caller. */
  router: ReturnType<typeof createBrowserRouter>;
  /** The app's i18next instance, for the locale's script font. */
  i18n: I18n;
  /** Resolves once the detected language's bundle is in memory. */
  i18nReady: Promise<unknown>;
  /**
   * Dev mode only: the app's MSW worker, imported lazily. The caller passes it
   * as `import.meta.env.DEV ? () => import(…) : undefined`, so that in a
   * production build the branch is dead and the bundler drops the dynamic
   * import — and with it a ~600 KB chunk of mock data that would otherwise ship
   * in the jar, unloaded but present.
   */
  loadMocks?: () => Promise<MockWorker>;
}

/**
 * Boot one of the signed-in apps (the Manager or Workforce): the dev-mode mock
 * probe, the locale bundle and font, then the router.
 *
 * Both apps boot the same way, so this lives here once; each `main.tsx` only
 * says which router, which i18next instance and which mock worker.
 */
export async function startApp({ router, i18n, i18nReady, loadMocks }: StartAppOptions): Promise<void> {
  // In development, start MSW browser worker if the backend is unreachable
  if (import.meta.env.DEV && loadMocks) {
    const forced = mocksForced();
    try {
      if (forced) throw new Error("Mocks forced");
      const res = await fetch("/agentstore/agents/descriptors?limit=1", {
        signal: AbortSignal.timeout(1500),
      });
      // 401/403 is an answer from EDDI with authentication on (the probe runs
      // before sign-in): the backend is there. Reading it as "unreachable" put
      // every authenticated dev session onto the mock API, where an editor saw
      // the mocks' admin data instead of the server's refusals.
      if (!res.ok && res.status !== 401 && res.status !== 403) throw new Error("Backend not OK");
      console.log("[EDDI] Backend detected — using real API");
    } catch {
      // Say which of the two reasons it was. Logging "backend not reachable"
      // when the backend is fine and mocks were merely pinned sends whoever is
      // debugging a UI failure chasing a phantom connectivity problem.
      console.log(
        forced
          ? "[EDDI] eddi-force-mocks is set — starting mock API (MSW), backend not probed"
          : "[EDDI] Backend not reachable — starting mock API (MSW)",
      );
      const { worker, recordUnhandledApiRequest } = await loadMocks();
      await worker.start({
        // NOT "bypass". An API call with no handler used to fall through to the
        // dev server silently, so a page could fail to load all of its data and
        // the E2E tier would not notice — which is where the "MSW browser worker
        // too slow" skips came from. Unhandled API calls are now recorded on
        // `window` so a Playwright fixture can fail the test that caused them,
        // and still bypassed so dev-server behaviour is unchanged.
        onUnhandledRequest: recordUnhandledApiRequest,
      });
      // Signal to UI components that mock data is active
      (window as unknown as Record<string, unknown>).__EDDI_MOCK_ACTIVE__ = true;
    }
  }

  // Wait for the detected language's bundle before the first paint. Only English
  // is in the entry chunk (see the app's `i18n/config.ts`), so rendering
  // immediately would show English and then swap — a visible flash of the wrong
  // language on every cold load in a non-English locale. Never rejects; a failed
  // chunk falls back to English inside i18next.
  await i18nReady;
  // The locale's script font, so a Japanese or Arabic first paint is already in
  // Noto rather than swapping from the system font. Bounded: a slow chunk costs
  // a font swap, never a blank page.
  await Promise.race([
    installLocaleFonts(i18n),
    new Promise((resolve) => setTimeout(resolve, LOCALE_FONT_WAIT_MS)),
  ]);

  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      {/* useTransitions={false}: a data router wraps navigation in a React
          transition by default, which keeps the OLD page on screen until a
          lazy route's chunk has loaded — no skeleton, no sign the click did
          anything. The app's Suspense boundary at the outlet is the loading
          state, so navigation must render synchronously and let it show. */}
      <RouterProvider router={router} useTransitions={false} />
    </StrictMode>,
  );
}
