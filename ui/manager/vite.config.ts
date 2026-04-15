import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath, URL } from "node:url";
import { readFileSync } from "node:fs";

const pkg = JSON.parse(readFileSync("./package.json", "utf-8"));

export default defineConfig({
  plugins: [react(), tailwindcss()],
  define: {
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    port: 3000,
    proxy: {
      "/agentstore": "http://localhost:7070",
      "/workflowstore": "http://localhost:7070",
      "/rulestore": "http://localhost:7070",
      "/dictionarystore": "http://localhost:7070",
      "/outputstore": "http://localhost:7070",
      "/apicallstore": "http://localhost:7070",
      "/llmstore": "http://localhost:7070",
      "/propertysetterstore": "http://localhost:7070",
      "/mcpcallsstore": "http://localhost:7070",
      "/ragstore": "http://localhost:7070",
      "/groupstore": "http://localhost:7070",
      "/groups": "http://localhost:7070",
      "/logs": "http://localhost:7070",
      "/parserstore": "http://localhost:7070",
      "/extensionstore": "http://localhost:7070",
      "/conversationstore": "http://localhost:7070",
      "/conversations": "http://localhost:7070",
      "/userconversationstore": "http://localhost:7070",
      "/descriptorstore": "http://localhost:7070",
      "/secretstore": "http://localhost:7070",
      "/auditstore": "http://localhost:7070",
      "/schedulestore": "http://localhost:7070",
      "/deploymentstore": "http://localhost:7070",
      "/propertiesstore": "http://localhost:7070",
      // SSE stream at /administration/logs/stream needs unbuffered proxy
      "/administration": {
        target: "http://localhost:7070",
        configure: (proxy) => {
          proxy.on("proxyReq", (_proxyReq, req) => {
            if (req.socket) {
              req.socket.on("close", () => {
                if (_proxyReq.socket && !_proxyReq.socket.destroyed) {
                  _proxyReq.socket.destroy();
                }
              });
            }
          });
        },
      },
      "/snippetstore": "http://localhost:7070",
      "/admin": "http://localhost:7070",
      "/capabilities": "http://localhost:7070",
      // The /agents path includes the /agents/{id}/stream SSE endpoint.
      // We must disable http-proxy buffering so the stream closes cleanly.
      "/agents": {
        target: "http://localhost:7070",
        configure: (proxy) => {
          proxy.on("proxyReq", (_proxyReq, req) => {
            // When the client aborts (AbortController), destroy the upstream
            // socket so the backend sees the disconnect immediately.
            if (req.socket) {
              req.socket.on("close", () => {
                if (_proxyReq.socket && !_proxyReq.socket.destroyed) {
                  _proxyReq.socket.destroy();
                }
              });
            }
          });
        },
      },
      "/llm/tools": "http://localhost:7070",
      "/backup": "http://localhost:7070",
      "/managerresource": "http://localhost:7070",
      "/managedagents": "http://localhost:7070",
      "/mcp": "http://localhost:7070",
      "/openapi": "http://localhost:7070",
    },
  },
});
