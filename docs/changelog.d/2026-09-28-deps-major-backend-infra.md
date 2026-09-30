## ⬆️ chore(deps): major upgrades — MCP server 2, Testcontainers 2; Vert.x 5 web client held back (2026-09-28)

**Repo:** EDDI (`chore/deps-major-backend-infra`, built on `chore/deps-minor-upgrades`)

### Aligned with what Quarkus ships

All three follow the rule the dependency PRs share. If a library comes from Quarkus or integrates
with it, EDDI takes the version the Quarkus platform ships, and nothing newer.

- **MCP server 2.0.1** is exactly what the Quarkus 3.39.5 platform's `quarkus-mcp-server-bom` ships.
- **Testcontainers 2.0.5** is exactly what the 3.39.5 `quarkus-bom` manages. That is why the
  explicit version pins are gone.
- **The vert.x web client** stays at the BOM's 4.5.34 until Quarkus moves to Vert.x 5.

### What changed

| Artifact | From → To | Note |
|---|---|---|
| `io.quarkiverse.mcp:quarkus-mcp-server-http` | 1.13.2 → **2.0.1** | Built against Quarkus 3.33.3.1, so it runs on the 3.39.5 platform. No code change needed |
| Testcontainers | 1.21.4 → **2.0.5** | Module artifacts renamed; versions now come from the Quarkus BOM |
| `io.vertx:vertx-web-client` | stays **4.5.34** (BOM) | 5.2.0 cannot run on the Vert.x 4 core Quarkus 3.39 ships — see below |

**quarkus-mcp-server 2.0.** Its [2.0 breaking changes](https://github.com/quarkiverse/quarkus-mcp-server/blob/main/docs/modules/ROOT/pages/release-notes.adoc)
were checked one by one against EDDI and none applies: we use no `quarkus.mcp.server.sse.*` property
(our root path is already `quarkus.mcp.server.http.root-path`), send no JSON-RPC batches, never call
`McpRequest.protocolVersion()` or override `OutputSchemaGenerator`, and never enabled the MCP metrics
that became runtime opt-in. The renumbered `RESOURCE_NOT_FOUND` error code applies only to clients that
negotiate the new stateless protocol. 2.0 also fixes an exact-match hole in the extension's
DNS-rebinding `Origin` check and makes `server/discover` run `McpToolFilter` with an active request
context.

**Testcontainers 2.** Module artifacts carry a `testcontainers-` prefix (`testcontainers-junit-jupiter`,
`testcontainers-mongodb`, `testcontainers-postgresql`), and `MongoDBContainer` / `PostgreSQLContainer`
moved to `org.testcontainers.mongodb` / `org.testcontainers.postgresql` — the new `PostgreSQLContainer`
is not generic. The explicit `1.21.4` pins are gone: the Quarkus BOM already manages the whole 2.0.5
family, and the pins had been overriding it, so the tests ran a different Testcontainers than Quarkus
Dev Services. No test used the removed JUnit 4 integration.

**Vert.x 5 web client — not taken.** `vertx-web-client` is managed by the Quarkus BOM at 4.5.34
alongside `vertx-core` 4.5.34. Pinning 5.2.0 leaves a mixed classpath (`dependency:tree`:
`vertx-web-client:5.2.0` over `vertx-web-common:4.5.34` and `vertx-core:4.5.34`), fails to compile
(`HttpClientWrapper`'s callback-style `send`/`sendBuffer` are gone in 5), and could not run even after
a Future-based rewrite: the 5.2.0 jar references `io.vertx.core.internal.*`
(`ContextInternal`, `VertxInternal`, `HttpClientInternal`, …), a package with zero classes in
`vertx-core` 4.5.34. It moves when Quarkus moves to Vert.x 5.

**Files:** [`pom.xml`](../../pom.xml),
[`MongoTestBase.java`](../../src/test/java/ai/labs/eddi/datastore/mongo/MongoTestBase.java),
[`MongoConversationMemoryStoreTest.java`](../../src/test/java/ai/labs/eddi/datastore/mongo/MongoConversationMemoryStoreTest.java),
[`PostgresTestBase.java`](../../src/test/java/ai/labs/eddi/datastore/postgres/PostgresTestBase.java),
[`ContainerBaseIT.java`](../../src/test/java/ai/labs/eddi/integration/ContainerBaseIT.java),
[`PostgresAgentUseCaseIT.java`](../../src/test/java/ai/labs/eddi/integration/postgres/PostgresAgentUseCaseIT.java)

### Verification

- `./mvnw compile` / `test-compile` green (Checkstyle and `formatter:validate` included).
- MCP unit tests (`Mcp*Test`): 1377 run, 0 failures.
- Every container-backed store test (the `MongoTestBase` / `PostgresTestBase` subclasses, the tenant-quota
  parity tests, `MongoConversationMemoryStoreTest`) against real Docker containers: 564 run, 0 failures.
- Container ITs (`verify -DskipITs=false`): `AgentUseCaseIT`, `A2aEndpointIT` (the `ContainerBaseIT`
  image, which boots the extension in real Quarkus), `PostgresAgentUseCaseIT` and
  `NatsConversationCoordinatorIT` all green. `McpEndpointIT` against that same image: 5 of 5.
  `McpCallsCrudIT` / `PostgresMcpCallsCrudIT` could not run on the Windows workstation — a native
  `mongod` already holds the fixed Dev Services port 27017, and in-process Quarkus boot hit the known
  "failed to create a child event loop" — so CI is their gate.
- Full unit suite: 23,332 run, 8 failures / 210 errors, every one the workstation's known environmental
  kind ("Unable to establish loopback connection", "Failure opening selector", Netty child event loop),
  in classes none of these upgrades touch.
