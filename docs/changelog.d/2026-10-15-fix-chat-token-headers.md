## 🔒 fix(security): `?token=` opt-in for the Chat UI, Swagger UI gets its CSP, MCP discovery advertises the request's scheme (2026-10-15)

**Repo:** EDDI (`fix/chat-token-headers`)

Three findings of the 2026-10-15 security review of `main` at `d49e855`, verified
live against the packaged 6.6.0 jar: M6, L11 and L3.

### What changed and why

**M6 — the Chat UI took a bearer token from `?token=` unconditionally.** A crafted
link signs the widget in as whoever minted the token (login CSRF), and the first
request writes the token into the access log before the widget has stripped it
from the address bar. It is now an operator opt-in: `eddi.chat.url-token.enabled`
(default `false`) reaches the widget as `window.__EDDI_CHAT__` from a new
`GET /chat/__config__.js` on `RestHtmlChatResource`, loaded by `chat.html`
before the widget — the way the Manager reads `/manage/__auth_config__.js`.
Off, `readUrlToken` (new `ui/chat/src/api/chat-config.ts`) ignores the parameter
and logs a console warning naming the property; the parameter is stripped from
the address bar either way, and only a literal `true` switches it on. The
`postMessage` hand-off (`?tokenOrigin=`) and `ChatConfig.authToken` are
unchanged. `parseAllowedTokenOrigins` also warns about a plain-`http`
`tokenOrigin` that is not loopback: the token the host posts is only as private
as that page's transport. The Vite dev proxy forwards the config script so
`npm run dev` sees the real value with a backend up.

**L11 — `/q/swagger-ui/` was served with no CSP and no X-Frame-Options.** The
`csp-swagger` filter was configured but never ran. Quarkus hands a filter's
`order` to Vert.x `Route.order()` unchanged, and Vert.x runs routes in
*ascending* order (the Quarkus doc's "higher priority takes precedence" reads
the other way); the `/q` sub-router — health, metrics, Swagger UI — is mounted
on the same router as a route whose order is its creation sequence number.
Live, that number sat between 10 and 20: `/q/health/ready` got `csp-default`
(order 10) and `/q/swagger-ui/` got nothing from `csp-swagger` (order 20),
because the sub-router had already answered. All three CSP filters now use
negative orders (`-30`/`-25`/`-20`), which run before every sequence-ordered
route; verified live both ways in dev mode. `CspPolicyTest` pins the orders
negative, adds the Swagger paths and the other `/q` routes, and feeds the path
tricks through Vert.x's own `HttpUtils.normalizePath` (what the regexes are
matched against): `/chat/../manage`, `/%2Fchat`, `/CHAT`, `/chat;x=1/…` keep
the strict default policy; `//chat` (folded to `/chat`) and `/chat/..;/manage`
(not a dot segment; the chat catch-all serves `chat.html`) get the chat policy
and nothing else, so the browser never enforces an intersection.

**L3 — with OIDC on, a plain-http instance advertised
`https://localhost:7070/mcp`.** `quarkus.oidc.resource-metadata.force-https-scheme`
was pinned `true` for the TLS-terminating-proxy case, so every plain-http
deployment with authentication on had to remember to override it, and one that
did not handed MCP clients an identifier nothing served. It is now `false`: the
scheme follows the request — `http` on a plain-http instance, `https` over TLS,
and behind a proxy that forwards it (`quarkus.http.proxy.proxy-address-forwarding`)
the public one. A proxy that terminates TLS without forwarding the scheme gets
every other absolute URL EDDI builds wrong too; the fix there is the proxy
headers or the absolute `quarkus.oidc.resource-metadata.resource`, both already
documented. `McpOAuthDiscoveryConfigTest` replaces the pinned-`true` assertion
with one that drives quarkus-oidc's own `ResourceMetadataHandler` with the
values from `application.properties` and proves an http request yields an http
identifier and an https request an https one. The compose auth stacks, the k8s
auth overlay and the Manager's Keycloak E2E compose keep their explicit `false`
(now the default); only their comments changed.

**Files:** [`application.properties`](../../src/main/resources/application.properties),
`src/main/java/ai/labs/eddi/ui/{IRestHtmlChatResource,RestHtmlChatResource}.java`,
`ui/chat/chat.html`, `ui/chat/vite.config.ts`, `ui/chat/src/api/chat-config.ts` (new),
`ui/chat/src/api/embed-auth.ts`, `ui/chat/src/components/ChatWidget.tsx`,
`CspPolicyTest`, `McpOAuthDiscoveryConfigTest`, `RestHtmlChatResourceTest`,
the Chat UI tests, [`chat-ui-embedding.md`](../chat-ui-embedding.md),
[`security.md`](../security.md), [`mcp-server.md`](../mcp-server.md),
[`configuration-reference.md`](../configuration-reference.md),
`docker-compose.auth.yml`, `k8s/overlays/auth/kustomization.yaml`,
`ui/manager/docker-compose.integration-keycloak.yml`.

### Design decisions

- **A script global, not a fetch.** The widget needs the flag before its first
  request, synchronously, at mount — a fetched JSON would race the `?token=`
  read. The Manager already solves this with `/manage/__auth_config__.js`.
- **Strictly `true`.** A missing, malformed or string-valued global reads as
  off: a bundle served without the backend, or a stale cached script, must not
  turn the vector back on.
- **Scheme from the request rather than a smarter default.** There is no config
  signal that distinguishes "plain http" from "TLS terminated upstream without
  forwarded headers"; the request's scheme is right in every configured
  deployment and wrong only in the misconfigured one, where it is already wrong
  everywhere else.

```decision-log
| 2026-10-15 | The Chat UI's `?token=` is an operator opt-in (`eddi.chat.url-token.enabled`, default off); CSP filters use negative Vert.x orders so they run before the `/q` sub-router; `force-https-scheme` defaults to `false` and the advertised MCP resource scheme follows the request | Security review M6, L11, L3 |
```

```regression-note
| 2026-10-15 | An embedding that relied on `?token=` stops authenticating until the operator sets `eddi.chat.url-token.enabled=true`; the widget says so in the browser console. A deployment behind a TLS-terminating proxy that set neither `quarkus.http.proxy.proxy-address-forwarding` nor an absolute `quarkus.oidc.resource-metadata.resource` now advertises an `http` MCP resource identifier — set one of them, or `force-https-scheme=true` |
```
