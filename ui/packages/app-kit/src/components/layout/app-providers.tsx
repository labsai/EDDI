import type { ReactNode } from "react";
import { QueryClientProvider, type QueryClient } from "@tanstack/react-query";
import { ThemeProvider } from "@eddi/ui";
import { AppToaster } from "./app-toaster";
import { AuthProvider } from "../auth/auth-provider";
import { GrantRequiredDialogHost } from "../secrets/grant-required-dialog";

/**
 * The provider stack both signed-in apps render their routes inside: the
 * Keycloak session, the query cache, the theme, the toaster and the one
 * vault-grant dialog every deploy flow asks through.
 *
 * The theme key is the same in both apps (`eddi-theme`), so a theme chosen in
 * the Manager is the theme Workforce opens with: moving between the two is a
 * full page navigation on one origin, and localStorage is what carries it over.
 */
export function AppProviders({ queryClient, children }: { queryClient: QueryClient; children: ReactNode }) {
  return (
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="system" storageKey="eddi-theme">
          {children}
          <AppToaster />
          <GrantRequiredDialogHost />
        </ThemeProvider>
      </QueryClientProvider>
    </AuthProvider>
  );
}
