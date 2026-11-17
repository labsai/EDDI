import { queryClient } from "@eddi/app-kit/lib/query-client";
import { startApp } from "@eddi/app-kit/lib/start-app";
import { createAppRouter } from "@/app-router";
import i18n, { i18nReady } from "@/i18n/config";
import "@/index.css";

// Self-hosted fonts, no CDN. Only the default family is eager; the Arabic, Thai,
// Devanagari and CJK families load with their locale (`installLocaleFonts`,
// called by `startApp`).
import "@fontsource-variable/noto-sans";

// The mock probe, the locale bundle and font, then the router: the Manager's
// boot too (@eddi/app-kit). In dev with no backend, the shared mock API answers;
// the conditional keeps the mock data out of the production bundle altogether.
void startApp({
  router: createAppRouter(queryClient),
  i18n,
  i18nReady,
  loadMocks: import.meta.env.DEV ? () => import("@eddi/app-kit/test/mocks/browser") : undefined,
});
