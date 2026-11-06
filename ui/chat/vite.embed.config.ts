import { defineConfig } from "vite";
import { resolve } from "path";

/**
 * The embed launcher (T2.9): one framework-free script, built apart from the
 * widget into `dist/scripts/js/chat-ui-embed.js`. EDDI serves it at
 * `/chat/embed.js` (RestHtmlChatResource), and the Maven copy and clean
 * patterns for `scripts/js/chat-ui*.js` already cover the name. Its gzip size
 * has a budget of its own in ui/bundle-budget.json (`scripts`).
 *
 * Runs after the widget's build (`npm run build`), which empties dist/ — so
 * this one must not.
 */
export default defineConfig({
  publicDir: false,
  build: {
    outDir: "dist",
    emptyOutDir: false,
    target: "es2019",
    lib: {
      entry: resolve(import.meta.dirname, "src/embed/embed.ts"),
      formats: ["iife"],
      name: "EddiChatEmbed",
      fileName: () => "scripts/js/chat-ui-embed.js",
    },
  },
});
