import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Toaster } from "sonner";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { AuthProvider } from "@/components/auth/auth-provider";
import { App } from "@/app";
import { i18nReady } from "@/i18n/config";
import "@/index.css";
// Start collecting logs from session start (before user navigates to /manage/logs)
import "@/hooks/session-log-store";

// ── Self-hosted fonts (no external CDN requests) ────────────────────
import "@fontsource-variable/noto-sans";
import "@fontsource-variable/noto-sans-arabic";
import "@fontsource-variable/noto-sans-thai";
import "@fontsource-variable/noto-sans-devanagari";
import "@fontsource-variable/noto-sans-jp";
import "@fontsource-variable/noto-sans-kr";
import "@fontsource-variable/noto-sans-sc";
import "@fontsource-variable/noto-sans-tc";

// Monaco is deliberately NOT imported here. It is ~7 MB and only four editor
// components need it, so it lives in `@/lib/monaco-setup`, which those
// components import for its side effect — see that file for why the
// configuration cannot simply be deferred behind a function call.

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
});

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

async function startApp() {
  // In development, start MSW browser worker if the backend is unreachable
  if (import.meta.env.DEV) {
    const forced = mocksForced();
    try {
      if (forced) throw new Error("Mocks forced");
      const res = await fetch("/agentstore/agents/descriptors?limit=1", {
        signal: AbortSignal.timeout(1500),
      });
      if (!res.ok) throw new Error("Backend not OK");
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
      const { worker, recordUnhandledApiRequest } = await import("@/test/mocks/browser");
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
  // is in the entry chunk (see `i18n/config.ts`), so rendering immediately would
  // show English and then swap — a visible flash of the wrong language on every
  // cold load in a non-English locale. Never rejects; a failed chunk falls back
  // to English inside i18next.
  await i18nReady;

  createRoot(document.getElementById("root")!).render(
    <StrictMode>
      <BrowserRouter>
        <AuthProvider>
          <QueryClientProvider client={queryClient}>
            <ThemeProvider defaultTheme="system" storageKey="eddi-theme">
              <App />
              <Toaster position="bottom-right" richColors closeButton />
            </ThemeProvider>
          </QueryClientProvider>
        </AuthProvider>
      </BrowserRouter>
    </StrictMode>
  );
}

startApp();

