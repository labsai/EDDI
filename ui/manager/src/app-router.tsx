import { createBrowserRouter } from "react-router-dom";
import type { QueryClient } from "@tanstack/react-query";
import { AppProviders } from "@eddi/app-kit/components/layout/app-providers";
import { App } from "@/app";

/**
 * The browser router for the app.
 *
 * It is a DATA router (`createBrowserRouter`, rendered with `RouterProvider`)
 * because only that kind supports `useBlocker`, which `useUnsavedChangesGuard`
 * needs to stop in-app navigation from silently discarding edits. `<App />` still
 * declares the real route table with `<Routes>`, so every route, lazy page and
 * layout is unchanged — this wrapper is one catch-all route that matches
 * everything, renders the providers (shared with Workforce, @eddi/app-kit), and
 * hands the path over to `<App />`.
 */
export function createAppRouter(queryClient: QueryClient) {
  return createBrowserRouter([
    {
      path: "*",
      element: (
        <AppProviders queryClient={queryClient}>
          <App />
        </AppProviders>
      ),
    },
  ]);
}
