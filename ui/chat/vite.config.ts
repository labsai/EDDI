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
    // dist/ is copied into the Quarkus jar by maven-resources-plugin (pom.xml,
    // execution copy-ui-bundles). It used to point straight at a sibling backend
    // checkout, which is why emptyOutDir had to be off.
    outDir: "dist",
    emptyOutDir: true,
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
