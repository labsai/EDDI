import { createBrowserRouter } from "react-router-dom";
import { QueryClientProvider, type QueryClient } from "@tanstack/react-query";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { ThemedToaster } from "@/components/layout/themed-toaster";
import { AuthProvider } from "@/components/auth/auth-provider";
import { App } from "@/app";

/**
 * The browser router for the app.
 *
 * It is a DATA router (`createBrowserRouter`, rendered with `RouterProvider`)
 * because only that kind supports `useBlocker`, which `useUnsavedChangesGuard`
 * needs to stop in-app navigation from silently discarding edits. `<App />` still
 * declares the real route table with `<Routes>`, so every route, lazy page and
 * layout is unchanged — this wrapper is one catch-all route that matches
 * everything, renders the providers, and hands the path over to `<App />`.
 */
export function createAppRouter(queryClient: QueryClient) {
  return createBrowserRouter([
    {
      path: "*",
      element: (
        <AuthProvider>
          <QueryClientProvider client={queryClient}>
            <ThemeProvider defaultTheme="system" storageKey="eddi-theme">
              <App />
              <ThemedToaster />
            </ThemeProvider>
          </QueryClientProvider>
        </AuthProvider>
      ),
    },
  ]);
}
