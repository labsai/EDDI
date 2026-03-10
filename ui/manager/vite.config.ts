import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath, URL } from "node:url";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          "react-vendor": ["react", "react-dom", "react-router-dom"],
          "ui-vendor": [
            "lucide-react",
            "sonner",
            "clsx",
            "tailwind-merge",
            "class-variance-authority",
          ],
          "editor-vendor": ["monaco-editor"],
          "query-vendor": ["@tanstack/react-query"],
          "i18n-vendor": ["i18next", "react-i18next"],
        },
      },
    },
  },
  server: {
    port: 3000,
    proxy: {
      "/botstore": "http://localhost:7070",
      "/packagestore": "http://localhost:7070",
      "/behaviorstore": "http://localhost:7070",
      "/regulardictionarystore": "http://localhost:7070",
      "/outputstore": "http://localhost:7070",
      "/httpcallsstore": "http://localhost:7070",
      "/langchainstore": "http://localhost:7070",
      "/propertysetterstore": "http://localhost:7070",
      "/parserstore": "http://localhost:7070",
      "/extensionstore": "http://localhost:7070",
      "/conversationstore": "http://localhost:7070",
      "/descriptorstore": "http://localhost:7070",
      "/administration": "http://localhost:7070",
      "/bots": "http://localhost:7070",
      "/backup": "http://localhost:7070",
      "/managerresource": "http://localhost:7070",
      "/managedbots": "http://localhost:7070",
    },
  },
});
