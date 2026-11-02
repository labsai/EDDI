/**
 * The EDDI brand for the Vite dev server (and MSW mock mode), where no backend
 * renders it.
 *
 * In production EDDI serves the HTML shells itself and replaces the
 * `%BRAND_PRODUCT_NAME%`, `%BRAND_TAGLINE%` and `%BRAND_THEME_COLOR%`
 * placeholders with the deployment's brand (BrandedShellRenderer), and answers
 * `/branding/*` (RestBranding). Vite only rewrites `%VITE_*%`-style names, so the
 * placeholders pass through `vite build` untouched — this plugin is
 * `apply: "serve"` and never runs in a build, which `src/vite-plugin-brand.test.ts`
 * pins.
 *
 * Under `npm run dev` it fills in what an unbranded EDDI would, and serves
 * `/branding/__brand__.js`, an empty `/branding/brand.css` and the bundled EDDI
 * images, so the shells work with no backend. The dev server therefore always
 * shows the EDDI brand, even when a backend with a brand is running.
 *
 * Plain JS with a `.d.mts` (as the Manager keeps it): vite.config.ts and the test both
 * import it. Keep it in step with ui/manager/scripts/vite-plugin-brand.mjs.
 */
import { readFileSync } from "node:fs";
import { basename, extname } from "node:path";

/**
 * Exactly what EDDI's `/branding/__brand__.js` answers when nothing is branded
 * (BrandDelivery.script). BrandDeliveryTest reads this line to keep the two equal.
 */
export const DEFAULT_BRAND_SCRIPT =
  'window.__EDDI_BRAND__={branded:false,revision:0,productName:"EDDI",shortName:"EDDI",tagline:null,poweredBy:true,showUpdateCheck:false,chat:{accent:null}};';

/** Per shell, what an unbranded EDDI substitutes (BrandedShell). */
const SHELL_DEFAULTS = {
  "welcome.html": { tagline: "Open Source Conversational AI Platform", themeColor: "#111113" },
  "chat.html": { tagline: "Open Source Conversational AI", themeColor: "#1a1a1a" },
};
const FALLBACK_DEFAULTS = { tagline: "", themeColor: "#111113" };

const CONTENT_TYPES = {
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".webp": "image/webp",
};

/**
 * @param {string} html
 * @param {string} fileName the shell's file name, e.g. "manage.html"
 */
export function substituteBrandDefaults(html, fileName) {
  const defaults = SHELL_DEFAULTS[fileName] ?? FALLBACK_DEFAULTS;
  return html
    .replaceAll("%BRAND_PRODUCT_NAME%", "EDDI")
    .replaceAll("%BRAND_TAGLINE%", defaults.tagline)
    .replaceAll("%BRAND_THEME_COLOR%", defaults.themeColor);
}

/**
 * @param {{ assets: Record<string, string> }} options file to serve for each
 *   `/branding/<name>` image, e.g. `{ icon: "public/eddi-icon.svg" }`
 * @returns {import("vite").Plugin}
 */
export function brandDefaults({ assets }) {
  return {
    name: "eddi-brand-defaults",
    apply: "serve",
    transformIndexHtml: {
      order: "pre",
      handler(html, ctx) {
        return substituteBrandDefaults(html, basename(ctx.filename));
      },
    },
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const path = (req.url ?? "").split("?")[0];
        if (!path.startsWith("/branding/")) {
          next();
          return;
        }
        const name = path.slice("/branding/".length);
        if (name === "__brand__.js") {
          res.setHeader("Content-Type", "application/javascript");
          res.setHeader("Cache-Control", "no-cache");
          res.end(DEFAULT_BRAND_SCRIPT);
          return;
        }
        if (name === "brand.css") {
          res.setHeader("Content-Type", "text/css");
          res.setHeader("Cache-Control", "no-cache");
          res.end("");
          return;
        }
        const file = assets[name];
        if (!file) {
          res.statusCode = 404;
          res.end();
          return;
        }
        res.setHeader("Content-Type", CONTENT_TYPES[extname(file)] ?? "application/octet-stream");
        res.end(readFileSync(file));
      });
    },
  };
}
