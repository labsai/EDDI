import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { resolve } from "path";

export default defineConfig({
  plugins: [react()],
  base: "/",
  resolve: {
    alias: {
      "@": resolve(__dirname, "src"),
    },
  },
  build: {
    outDir: resolve(__dirname, "../EDDI/src/main/resources/META-INF/resources"),
    emptyOutDir: false, // Keep existing files (index.html, manage.html, dashboard, etc.)
    rollupOptions: {
      input: resolve(__dirname, "chat.html"),
      output: {
        // Put JS/CSS into scripts/ to match existing EDDI structure
        entryFileNames: "scripts/js/chat-ui.[hash].js",
        chunkFileNames: "scripts/js/chat-ui-[name].[hash].js",
        assetFileNames: "scripts/css/chat-ui.[hash][extname]",
      },
    },
  },
  server: {
    port: 5174,
    proxy: {
      "/agents": {
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
