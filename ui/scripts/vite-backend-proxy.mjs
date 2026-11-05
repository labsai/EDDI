// The dev server's reverse proxy to an EDDI backend, shared by the two signed-in
// apps (ui/manager and ui/workforce): both call the same REST API from the same
// origin as their page, so both need the same table. Plain JS for the same reason
// as vite-plugin-brand.mjs; the types are in vite-backend-proxy.d.mts.

/**
 * Wrap a proxy entry with an error handler so the dev server doesn't crash
 * when the backend is unavailable. Returns a 502 JSON response instead.
 */
function withErrorHandler(opts) {
  const original = opts.configure;
  return {
    ...opts,
    configure: (proxy, options) => {
      original?.(proxy, options);
      proxy.on("error", (err, _req, res) => {
        // `res` can be a Socket (for WebSocket upgrades) — only respond if
        // it's an actual ServerResponse that hasn't already sent headers.
        if (res && "writeHead" in res && !res.headersSent) {
          res.writeHead(502, { "Content-Type": "application/json" });
          res.end(
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

/** SSE-aware proxy that tears down the upstream socket on client abort. */
function sse(target) {
  return withErrorHandler({
    target,
    configure: (proxy) => {
      proxy.on("proxyReq", (proxyReq, req) => {
        if (req.socket) {
          req.socket.on("close", () => {
            if (proxyReq.socket && !proxyReq.socket.destroyed) {
              proxyReq.socket.destroy();
            }
          });
        }
      });
    },
  });
}

/**
 * Every backend path the apps call, proxied to `target`.
 *
 * @param {string} target the EDDI backend, e.g. "http://localhost:7070"
 */
export function backendProxy(target) {
  const p = () => withErrorHandler({ target });
  return {
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
    "/groups": sse(target),
    "/logs": p(),
    "/parserstore": p(),
    "/extensionstore": p(),
    "/conversationstore": p(),
    "/conversations": p(),
    "/userconversationstore": p(),
    "/descriptorstore": p(),
    "/secretstore": p(),
    "/variablestore": p(),
    "/auditstore": p(),
    "/schedulestore": p(),
    "/deploymentstore": p(),
    "/propertiesstore": p(),
    // Tool cost / history reads (the debugger's cost dashboard).
    "/llm": p(),
    // Called by the User Memory tab and the Workspaces screens; missing here, the
    // dev server answered them with index.html (200, not JSON) and both pages
    // showed only "Something went wrong" in dev mode.
    "/usermemorystore": p(),
    "/workspaces": p(),
    "/spacestore": p(),
    "/AgentTriggerStore": p(),
    // SSE stream at /administration/logs/stream needs unbuffered proxy
    "/administration": sse(target),
    "/snippetstore": p(),
    "/admin": p(),
    "/capabilities": p(),
    // The end-user catalog Workforce lists for an eddi-user.
    "/catalog": p(),
    "/.well-known": p(),
    // The /agents path includes the /agents/{id}/stream SSE endpoint.
    // We must disable http-proxy buffering so the stream closes cleanly.
    "/agents": sse(target),
    "/llm/tools": p(),
    "/backup": p(),
    "/managerresource": p(),
    "/managedagents": p(),
    "/channelstore": p(),
    "/connectionstore": p(),
    // The per-user grant routes. Same-origin matters here beyond convenience:
    // `POST /connections/{name}/authorize` is answered with a `Set-Cookie`
    // nonce that the provider's callback has to carry back, and the proxy is
    // what makes the dev server the same origin as the backend.
    "/connections": p(),
    "/integrations": p(),
    "/mcp": p(),
    "/openapi": p(),
  };
}
