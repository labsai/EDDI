// Preview provider chain for /design-sync. Wraps every preview card so
// components that read router / query / i18n / theme context render instead of
// throwing. Exported from ds-entry so it's a bundle export usable as cfg.provider.
import * as React from "react";
import { MemoryRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@radix-ui/react-tooltip";
import { I18nextProvider } from "react-i18next";
import i18n from "i18next";
import { ThemeProvider } from "@/components/layout/theme-provider";
// Side-effect: initializes the global i18next instance + loads resources.
import "@/i18n/config";

// Isolated i18n instance, forced to English so previews are deterministic —
// WITHOUT mutating the app's shared instance or persisting a locale into the
// user's localStorage (the app's LanguageDetector otherwise renders the host
// machine's locale, e.g. German). `detection.caches: []` disables cache writes.
const previewI18n = i18n.cloneInstance({ lng: "en", detection: { caches: [] } });

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
});

export function DesignSyncProvider({ children }: { children: React.ReactNode }) {
  return (
    <MemoryRouter>
      <QueryClientProvider client={queryClient}>
        <I18nextProvider i18n={previewI18n}>
          {/* Dedicated storage key so the preview theme never reads or writes
              the app's "eddi-theme" — keeps previews deterministically light. */}
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-design-sync">
            <TooltipProvider delayDuration={0}>{children}</TooltipProvider>
          </ThemeProvider>
        </I18nextProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}
