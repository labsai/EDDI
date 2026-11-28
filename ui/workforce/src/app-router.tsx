import { createBrowserRouter } from "react-router-dom";
import type { QueryClient } from "@tanstack/react-query";
import { AppProviders } from "@eddi/app-kit/components/layout/app-providers";
import { App } from "@/app";

/**
 * Workforce's browser router: a DATA router, because only that kind supports
 * `useBlocker`, which `useUnsavedChangesGuard` needs (the wizard and the team
 * settings hold edits). One catch-all route renders the providers — the same
 * stack as the Manager's (@eddi/app-kit) — and hands the path to `<App />`.
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
