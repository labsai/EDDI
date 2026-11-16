import { queryClient } from "@eddi/app-kit/lib/query-client";
import { startApp } from "@eddi/app-kit/lib/start-app";
import { createAppRouter } from "@/app-router";
import i18n, { i18nReady } from "@/i18n/config";
import "@/index.css";
// NOT importing "@/hooks/session-log-store" here, deliberately. It used
// to be a bare side-effect import that opened an /administration/logs/stream SSE
// connection at app boot, on every page, for the lifetime of the tab. EDDI
// serves HTTP/1.1, where Chrome allows six concurrent connections per origin
// across the whole profile, so a couple of Manager tabs saturated the cap and
// pages hung on skeleton loaders while the server was fine. The stream is now
// opened by the consumers that need a live tail (see that module).

// ── Self-hosted fonts (no external CDN requests) ────────────────────
// Only the default family is eager. The Arabic, Thai, Devanagari and CJK
// families load with their locale (`installLocaleFonts(i18n)`, called by
// `startApp` in @eddi/app-kit): their @font-face rules were most of the main
// stylesheet, for every user in every language.
import "@fontsource-variable/noto-sans";

// Monaco is deliberately NOT imported here. It is ~7 MB and only four editor
// components need it, so it lives in `@eddi/app-kit/lib/monaco-setup`, which those
// components import for its side effect — see that file for why the
// configuration cannot simply be deferred behind a function call.

// The mock probe, the locale bundle and font, then the router: the same boot as
// Workforce (ui/workforce/src/main.tsx), so it lives in @eddi/app-kit.
void startApp({
  router: createAppRouter(queryClient),
  i18n,
  i18nReady,
  // Conditional, so a production build drops the mock API (~600 KB) entirely.
  loadMocks: import.meta.env.DEV ? () => import("@eddi/app-kit/test/mocks/browser") : undefined,
});
