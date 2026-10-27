import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { resolve } from "path";
import { brandDefaults } from "./scripts/vite-plugin-brand.mjs";

export default defineConfig({
  plugins: [
    react(),
    // The EDDI brand for `npm run dev`: fills chat.html's %BRAND_*% placeholders
    // and serves /branding/*. Dev server only — EDDI renders both itself in
    // production (scripts/vite-plugin-brand.mjs).
    brandDefaults({
      assets: {
        logo: resolve(import.meta.dirname, "public/img/logo_eddi.png"),
        "logo-dark": resolve(import.meta.dirname, "public/img/logo_eddi.png"),
        "og-image": resolve(import.meta.dirname, "public/img/logo_eddi.png"),
        icon: resolve(import.meta.dirname, "public/img/favicon.ico"),
        "favicon.ico": resolve(import.meta.dirname, "public/img/favicon.ico"),
      },
    }),
  ],
  base: "/",
  resolve: {
    alias: {
      "@": resolve(import.meta.dirname, "src"),
    },
  },
  build: {
    // dist/ is copied into the Quarkus jar by maven-resources-plugin (pom.xml,
    // execution copy-ui-bundles). It used to point straight at a sibling backend
    // checkout, which is why emptyOutDir had to be off.
    outDir: "dist",
    emptyOutDir: true,
    rolldownOptions: {
      input: resolve(import.meta.dirname, "chat.html"),
      output: {
        // Put JS/CSS into scripts/ to match existing EDDI structure
        entryFileNames: "scripts/js/chat-ui.[hash].js",
        chunkFileNames: "scripts/js/chat-ui-[name].[hash].js",
        // Stylesheets beside the scripts; anything else a stylesheet pulls in
        // (the KaTeX fonts) under fonts/, which the Maven build already
        // copies, serves and prunes between builds.
        assetFileNames: (asset) =>
          (asset.names ?? []).some((name) => name.endsWith(".css"))
            ? "scripts/css/chat-ui.[hash][extname]"
            : "fonts/chat-ui-[name].[hash][extname]",
      },
    },
  },
  server: {
    port: 5174,
    // Every path prefix the API layer (src/api/*.ts) calls must be listed, or
    // `npm run dev` answers it with the SPA's index.html. vite-proxy.test.ts
    // checks this list against the code.
    proxy: {
      // The operator settings chat.html loads before the widget (chat-config.ts).
      // Unproxied, Vite answers it with the SPA shell and the widget runs on
      // the safe defaults — with a backend up, it gets the real values.
      "/chat/__config__.js": {
        target: "http://localhost:7070",
        changeOrigin: true,
      },
      "/agents": {
        target: "http://localhost:7070",
        changeOrigin: true,
      },
      // Attachment upload and delete (attachments-api.ts).
      "/conversations": {
        target: "http://localhost:7070",
        changeOrigin: true,
      },
      "/managedagents": {
        target: "http://localhost:7070",
        changeOrigin: true,
      },
      "/conversationstore": {
        target: "http://localhost:7070",
        changeOrigin: true,
      },
    },
  },
});
