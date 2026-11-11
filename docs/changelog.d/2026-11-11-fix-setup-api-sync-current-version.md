## 🐛 fix(engine): `setup-api/sync` and "latest version" MCP reads failed with "does not define how its version info is authorized" (2026-11-11)

**Repo:** EDDI (`fix/setup-api-sync-current-version`)

### What broke

`POST /administration/agents/{agentId}/setup-api/sync` answered HTTP 500 on 6.7.0 before it wrote anything,
dry run included: `API agent sync failed: Access denied: this store does not define how its version info is
authorized`. The same refusal came back from every MCP admin tool that resolves "the latest version" when no
version is given (`read_workflow` without `version`, reproduced on a live 6.7.0; also the other resource reads
in `McpAdminTools`), and the same lookup in `AgentSetupService` and `AgentCascadeService`. Callers that inject a
store bean directly (`McpConversationTools`, `McpGroupTools`) were never affected.

### Cause

Those callers reach the stores through `IRestInterfaceFactory`, a MicroProfile REST client over loopback.
`getCurrentVersion` is a `default` method of the `IRestVersionInfo` mixin, and a REST client runs a default
method as plain Java in the caller, never as a request. The store beans override
`requireCurrentVersionAccess` with their VIEW check, but the client proxy does not, so the mixin's
fail-closed default (#1161) refused everyone. Before #1161 the next default, `getCurrentResourceId`, threw
`IllegalStateException` instead, so this path never worked through a client; sync (#1270) was the first caller
that could not do without it.

### Fix

`RestInterfaceFactory` wraps every store client it builds (`CurrentVersionRouting`): `getCurrentVersion` is sent
to the store's own `GET /{id}/currentversion` endpoint over HTTP, with the same loopback caller filter, and every
other method goes to the client unchanged. The endpoint keeps its role check and the store's VIEW check, so
nothing is authorized that was not before; one place fixes every caller, and a new caller cannot fall into it
again. The `currentversion` client is built once per store address and shared, since callers never close the clients the factory hands out (the filter reads the caller per request, so sharing is safe). `redirectToLatestVersion` is not routed (a 303 a client would follow; no in-process caller uses it). The
wrapper is a `java.lang.reflect.Proxy`, which a future native image build has to register.

**Files:** [`CurrentVersionRouting.java`](../../src/main/java/ai/labs/eddi/engine/runtime/client/factory/CurrentVersionRouting.java),
[`RestInterfaceFactory.java`](../../src/main/java/ai/labs/eddi/engine/runtime/client/factory/RestInterfaceFactory.java),
[`CurrentVersionRoutingTest.java`](../../src/test/java/ai/labs/eddi/engine/runtime/client/factory/CurrentVersionRoutingTest.java)

### Not verified here

The unit test reproduces the refusal on a client-like store and checks the routing. The loopback HTTP call itself
needs a running EDDI: check `setup-api/sync` with `dryRun: true` and `read_workflow` without a version on staging
after the release.

```regression-note
| 2026-11-11 | `setup-api/sync` HTTP 500 and MCP "latest version" reads refused with "does not define how its version info is authorized" | REST client proxies run `IRestVersionInfo.getCurrentVersion` (a default method) locally, where the fail-closed `requireCurrentVersionAccess` default refuses | `RestInterfaceFactory` routes `getCurrentVersion` to the `/{id}/currentversion` endpoint over HTTP | `fix/setup-api-sync-current-version` |
```
