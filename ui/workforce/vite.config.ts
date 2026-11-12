import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath, URL } from "node:url";
import { brandDefaults } from "../scripts/vite-plugin-brand.mjs";
import { backendProxy } from "../scripts/vite-backend-proxy.mjs";

/**
 * The EDDI backend the dev server proxies to (`EDDI_BACKEND_URL` to point it
 * elsewhere). The table is the Manager's too (ui/scripts/vite-backend-proxy.mjs).
 */
const BACKEND = process.env.EDDI_BACKEND_URL || "http://localhost:7070";

/** The EDDI logo (@eddi/app-kit) for every brand image under `npm run dev`. */
const LOGO = fileURLToPath(new URL("../packages/app-kit/src/assets/logo_eddi.png", import.meta.url));

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    // The EDDI brand for `npm run dev` and MSW mock mode: fills the shell's
    // %BRAND_*% placeholders and serves /branding/*. Dev server only — EDDI
    // renders both itself in production (ui/scripts/vite-plugin-brand.mjs).
    brandDefaults({
      assets: { logo: LOGO, "logo-dark": LOGO, "og-image": LOGO, icon: LOGO },
    }),
    // The shells link the manifest EDDI renders in the brand
    // (RestWorkforceResource#manifest); the dev server answers with the default
    // brand's, so `npm run dev` installs and audits like the jar does.
    {
      name: "workforce-dev-manifest",
      configureServer(server) {
        server.middlewares.use("/workforce/manifest.webmanifest", (_req, res) => {
          res.setHeader("Content-Type", "application/manifest+json");
          res.end(
            JSON.stringify({
              id: "/workforce",
              name: "EDDI Workforce",
              short_name: "EDDI",
              start_url: "/workforce",
              scope: "/workforce",
              display: "standalone",
              background_color: "#111113",
              theme_color: "#111113",
              icons: [{ src: "/branding/icon", type: "image/png", sizes: "any", purpose: "any" }],
            }),
          );
        });
      },
    },
  ],
  define: {
    // Which app this bundle is, for the code it shares with the Manager
    // (@eddi/app-kit, lib/app-paths.ts): a link to the Manager is a page load.
    "import.meta.env.VITE_EDDI_APP": JSON.stringify("workforce"),
  },
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  build: {
    /**
     * Never inline a font, whatever its size: EDDI serves its pages with
     * `font-src 'self'`, which refuses a `data:` URI (the Manager's vite.config.ts
     * has the measured story).
     */
    assetsInlineLimit: (filePath) => (/\.woff2?$/i.test(filePath) ? false : undefined),
    /**
     * One production shell. EDDI serves `workforce.html` from the jar for every
     * `/workforce/**` path (RestWorkforceResource, branded by BrandedShellRenderer),
     * so a deep link or a reload lands here and the router takes it from there.
     * The Maven build copies `dist/` into the jar beside the Manager's and the Chat
     * UI's (pom.xml, execution copy-ui-bundles): the shell at the root, the hashed
     * chunks under `assets/`. `index.html` is the dev-server entry only.
     */
    rolldownOptions: {
      input: {
        workforce: fileURLToPath(new URL("./workforce.html", import.meta.url)),
      },
    },
  },
  server: {
    // 3002, beside the Manager's 3000 and the Chat UI's 5174. PORT overrides it,
    // so two worktrees can run side by side.
    port: Number(process.env.PORT) || 3002,
    proxy: backendProxy(BACKEND),
  },
});
