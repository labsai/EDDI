import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Toaster } from "sonner";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { AuthProvider } from "@/components/auth/auth-provider";
import { App } from "@/app";
import "@/i18n/config";
import "@/index.css";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
});

async function startApp() {
  // In development, start MSW browser worker if the backend is unreachable
  if (import.meta.env.DEV) {
    try {
      const res = await fetch("/agentstore/agents/descriptors?limit=1", {
        signal: AbortSignal.timeout(1500),
      });
      if (!res.ok) throw new Error("Backend not OK");
      console.log("[EDDI] Backend detected — using real API");
    } catch {
      console.log("[EDDI] Backend not reachable — starting mock API (MSW)");
      const { worker } = await import("@/test/mocks/browser");
      await worker.start({ onUnhandledRequest: "bypass" });
      // Signal to UI components that mock data is active
      (window as unknown as Record<string, unknown>).__EDDI_MOCK_ACTIVE__ = true;
    }
  }

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

