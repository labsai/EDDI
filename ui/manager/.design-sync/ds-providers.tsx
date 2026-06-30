// Preview provider chain for /design-sync. Wraps every preview card so
// components that read router / query / i18n / theme context render instead of
// throwing. Exported from ds-entry so it's a bundle export usable as cfg.provider.
import * as React from "react";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@radix-ui/react-tooltip";
import { ThemeProvider } from "@/components/layout/theme-provider";
import i18n from "i18next";
// Side-effect: initializes the global i18next instance so useTranslation works.
import "@/i18n/config";

// Force English so previews are deterministic — the app's LanguageDetector
// otherwise renders labels in the host machine's locale (e.g. German).
i18n.changeLanguage("en");

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
});

export function DesignSyncProvider({ children }: { children: React.ReactNode }) {
  return (
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme">
          <TooltipProvider delayDuration={0}>{children}</TooltipProvider>
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}
