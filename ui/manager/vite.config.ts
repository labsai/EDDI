import { defineConfig, type ProxyOptions } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { fileURLToPath, URL } from "node:url";
import { readFileSync } from "node:fs";
import type { ServerResponse } from "node:http";

const pkg = JSON.parse(readFileSync("./package.json", "utf-8"));

const BACKEND = "http://localhost:7070";

/**
 * Wrap a proxy entry with an error handler so the dev server doesn't crash
 * when the backend is unavailable. Returns a 502 JSON response instead.
 */
function withErrorHandler(opts: ProxyOptions): ProxyOptions {
  const original = opts.configure;
  return {
    ...opts,
    configure: (proxy, options) => {
      original?.(proxy, options);
      proxy.on("error", (err, _req, res) => {
        // `res` can be a Socket (for WebSocket upgrades) — only respond if
        // it's an actual ServerResponse that hasn't already sent headers.
        if (res && "writeHead" in res && !(res as ServerResponse).headersSent) {
          const sres = res as ServerResponse;
          sres.writeHead(502, { "Content-Type": "application/json" });
          sres.end(
            JSON.stringify({
              error: "Backend unavailable",
              message: err.message,
            }),
          );
        }
      });
    },
  };
}

/** Shorthand: simple reverse-proxy to the backend with error handling. */
function p(target = BACKEND): ProxyOptions {
  return withErrorHandler({ target });
}

/** SSE-aware proxy that tears down the upstream socket on client abort. */
function pSSE(target = BACKEND): ProxyOptions {
  return withErrorHandler({
    target,
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
  });
}

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
      "/agentstore": p(),
      "/workflowstore": p(),
      "/rulestore": p(),
      "/dictionarystore": p(),
      "/outputstore": p(),
      "/apicallstore": p(),
      "/llmstore": p(),
      "/propertysetterstore": p(),
      "/mcpcallsstore": p(),
      "/ragstore": p(),
      "/groupstore": p(),
      // The /groups path includes /groups/{id}/conversations/stream SSE endpoint.
      // Disable http-proxy buffering so the stream closes cleanly on abort.
      "/groups": pSSE(),
      "/logs": p(),
      "/parserstore": p(),
      "/extensionstore": p(),
      "/conversationstore": p(),
      "/conversations": p(),
      "/userconversationstore": p(),
      "/descriptorstore": p(),
      "/secretstore": p(),
      "/auditstore": p(),
      "/schedulestore": p(),
      "/deploymentstore": p(),
      "/propertiesstore": p(),
      "/AgentTriggerStore": p(),
      // SSE stream at /administration/logs/stream needs unbuffered proxy
      "/administration": pSSE(),
      "/snippetstore": p(),
      "/admin": p(),
      "/capabilities": p(),
      // The /agents path includes the /agents/{id}/stream SSE endpoint.
      // We must disable http-proxy buffering so the stream closes cleanly.
      "/agents": pSSE(),
      "/llm/tools": p(),
      "/backup": p(),
      "/managerresource": p(),
      "/managedagents": p(),
      "/mcp": p(),
      "/openapi": p(),
    },
  },
});
