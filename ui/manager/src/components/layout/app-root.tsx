import { QueryClientProvider, type QueryClient } from "@tanstack/react-query";
import { AuthProvider } from "@/components/auth/auth-provider";
import { ThemeProvider } from "./theme-provider";
import { ThemedToaster } from "./themed-toaster";
import { UnsavedChangesNavigationGuard } from "./unsaved-changes-navigation-guard";
import { App } from "@/app";

/**
 * Everything under the router: the providers, the app, and the two global
 * pieces that must sit beside it rather than inside a page.
 *
 * The single element of the data router `main.tsx` creates. Kept out of
 * `main.tsx` so that file exports nothing a fast refresh has to reason about.
 */
export function AppRoot({ queryClient }: { queryClient: QueryClient }) {
  return (
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="system" storageKey="eddi-theme">
          <App />
          <UnsavedChangesNavigationGuard />
          <ThemedToaster />
        </ThemeProvider>
      </QueryClientProvider>
    </AuthProvider>
  );
}
