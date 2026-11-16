import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath, URL } from "node:url";
import { readFileSync } from "node:fs";
import { brandDefaults } from "../scripts/vite-plugin-brand.mjs";
import { backendProxy } from "../scripts/vite-backend-proxy.mjs";

/**
 * The EDDI version this Manager ships with, read from the repository's pom.xml —
 * the single source of truth (root AGENTS.md §1). package.json deliberately
 * carries no version of its own: it used to, and every release had to remember
 * to move it.
 *
 * The Maven build passes EDDI_VERSION=${project.version} (pom.xml, execution
 * manager-build), which wins. This fallback covers `npm run dev` and the CI
 * checks that build the Manager outside Maven. It takes the first <version>
 * element, which is the rule ci.yml uses (`grep -m1 '<version>' pom.xml`), and
 * answers "dev" when the Manager is built without the rest of the repository.
 */
function readPomVersion(): string {
  try {
    const pom = readFileSync(fileURLToPath(new URL("../../pom.xml", import.meta.url)), "utf-8");
    return /<version>([^<]+)<\/version>/.exec(pom)?.[1].trim() ?? "dev";
  } catch {
    return "dev";
  }
}

/**
 * The EDDI backend the dev server proxies to. `EDDI_BACKEND_URL` points it at
 * another instance (a second checkout on another port, a container) without
 * editing this file. The proxy table is shared with Workforce
 * (ui/scripts/vite-backend-proxy.mjs): both apps call the same API.
 */
const BACKEND = process.env.EDDI_BACKEND_URL || "http://localhost:7070";

/** The EDDI logo (shared with Workforce, @eddi/app-kit) as the dev-server brand. */
const LOGO = fileURLToPath(new URL("../packages/app-kit/src/assets/logo_eddi.png", import.meta.url));

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    // The EDDI brand for `npm run dev` and MSW mock mode: fills the shells'
    // %BRAND_*% placeholders and serves /branding/*. Dev server only — EDDI
    // renders both itself in production (ui/scripts/vite-plugin-brand.mjs).
    brandDefaults({
      assets: {
        logo: LOGO,
        "logo-dark": LOGO,
        "og-image": LOGO,
        icon: fileURLToPath(new URL("./public/eddi-icon.svg", import.meta.url)),
        "favicon.ico": fileURLToPath(new URL("./public/eddi-icon.ico", import.meta.url)),
      },
    }),
  ],
  define: {
    // The sidebar shows the version of the jar it ships in — see readPomVersion.
    __APP_VERSION__: JSON.stringify(process.env.EDDI_VERSION || readPomVersion()),
    // Which app this bundle is, for the code it shares with Workforce
    // (@eddi/app-kit, lib/app-paths.ts): a link to Workforce is a page load.
    "import.meta.env.VITE_EDDI_APP": JSON.stringify("manager"),
  },
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  optimizeDeps: {
    include: ["monaco-editor"],
  },
  build: {
    /**
     * Never inline a font, whatever its size.
     *
     * Vite inlines any asset under 4 KB as a `data:` URI, and six of the Noto
     * subsets land under that. EDDI serves the Manager with `font-src 'self'`,
     * which refuses a `data:` URI — so exactly those six faces were blocked in
     * production and silently fell back to a system font, while the other 467
     * (shipped as files) loaded fine. As files they are same-origin, which
     * `'self'` allows.
     */
    assetsInlineLimit: (filePath) => (/\.woff2?$/i.test(filePath) ? false : undefined),
    /**
     * The two production shells, one shared bundle.
     *
     * EDDI serves `manage.html` and `welcome.html` from the jar by exact name
     * (RestManagerResource, RestWelcomeResource), and `dist/` is copied into the
     * jar by the Maven build (pom.xml, execution copy-ui-bundles). `index.html`
     * is deliberately NOT an input: it is the dev-server entry only, and the
     * backend keeps its own hand-written `index.html` redirect shell.
     * `workforce.html` belongs to Workforce, which ui/workforce builds into a
     * `dist/` of its own.
     */
    rolldownOptions: {
      input: {
        manage: fileURLToPath(new URL("./manage.html", import.meta.url)),
        welcome: fileURLToPath(new URL("./welcome.html", import.meta.url)),
      },
    },
  },
  worker: {
    format: "es",
  },
  server: {
    // Honour PORT so two worktrees can run dev servers side by side; 3000 stays
    // the default, which is what the proxy notes and docs assume.
    port: Number(process.env.PORT) || 3000,
    proxy: backendProxy(BACKEND),
  },
});
