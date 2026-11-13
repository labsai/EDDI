# EDDI Ecosystem — Working Changelog

> **Purpose:** Living document tracking all changes, decisions, and reasoning during
> implementation. Updated as work progresses, newest first.

## How to Read This Document

Each entry records:

- **Date** — what changed and why
- **Repo** — which repository and branch was modified
- **Decision** — key design decisions and their reasoning
- **Files** — the files touched

## Where to Add an Entry

**Not here.** Write your entry as a new file in
[`changelog.d/`](changelog.d/README.md) — `YYYY-MM-DD-<slug>.md`, with the slug
unique to your branch — and leave this file alone. The same goes for the two
running registers at the bottom: their rows ride along in the fragment, in a
fenced `decision-log` or `regression-note` block.

Entries used to be inserted at the top of this file, and the registers appended
to at the bottom. Both are a fixed point in a shared file, which git cannot
merge: with several PRs open, every one of them conflicted with every other over
a document that had nothing to do with the code under review. A fragment is a new
file under a name no other branch picks, so the same two PRs merge without
touching each other.

`.github/workflows/changelog-collate.yml` runs nightly, merges the fragments in
here **by date** — a PR that stayed open for weeks lands among its
contemporaries rather than on top — trims this file back under its rotation
target, and opens a PR. Until that PR merges, `changelog.d/` holds the newest
history, so read it alongside the top of this file. To do it by hand:

```bash
python scripts/collate-changelog.py   # fragments -> this file
python scripts/rotate-changelog.py    # this file -> docs/changelog/<YYYY-MM>.md
```

This file holds only recent work and is capped at **250 KB** —
`ChangelogRotationTest` fails the build if it grows past that. Rotation runs at a
lower threshold than the cap, trimming back to **200 KB** whenever the file is
over that, so the session whose entry tips it over is not the one made to rotate
it. Rotation moves the oldest entries into `docs/changelog/<YYYY-MM>.md` by date,
adds one `../` to the relative links it moves (an archive sits a directory deeper
than this file) without touching the ones inside code spans, and regenerates both
the Archive table below and the changelog list in [`SUMMARY.md`](SUMMARY.md) from
what is on disk. Do not raise the cap.

The single file this replaced had reached 1.9 MB — roughly half a million tokens —
which neither a reader nor an agent's context window could usefully hold.

## Archive

| Period | Entries | Size |
|---|---|---|
| [November 2026](changelog/2026-11.md) | 103 | 428 KB |
| [October 2026](changelog/2026-10.md) | 267 | 1235 KB |
| [September 2026](changelog/2026-09.md) | 223 | 1216 KB |
| [August 2026](changelog/2026-08.md) | 213 | 845 KB |
| [July 2026](changelog/2026-07.md) | 147 | 648 KB |
| [June 2026](changelog/2026-06.md) | 26 | 67 KB |
| [May 2026](changelog/2026-05.md) | 34 | 76 KB |
| [April 2026](changelog/2026-04.md) | 104 | 220 KB |
| [March 2026](changelog/2026-03.md) | 59 | 183 KB |

The two running registers — **Decision Log** and **Regression Notes** — live at the
bottom of this file and are never archived.

---

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
again.

The `currentversion` client is shared: callers never close the clients the factory hands out, so building one per
call would leave one more behind each time. The loopback factory method (`get(Class)`) builds it once per store
address and trust level (with or without the caller credential) and reuses it; this is safe because the filter
reads the caller per request, not when the client is built. The two-argument method (`get(Class, serverUrl)`)
does not cache, since a cache keyed by arbitrary `serverUrl`s would be unbounded. `redirectToLatestVersion` is not
routed (it answers a 303 that a client would follow, and no in-process caller uses it). The
wrapper is a `java.lang.reflect.Proxy`, which a future native image build has to register.

**Files:** [`CurrentVersionRouting.java`](../src/main/java/ai/labs/eddi/engine/runtime/client/factory/CurrentVersionRouting.java),
[`RestInterfaceFactory.java`](../src/main/java/ai/labs/eddi/engine/runtime/client/factory/RestInterfaceFactory.java),
[`CurrentVersionRoutingTest.java`](../src/test/java/ai/labs/eddi/engine/runtime/client/factory/CurrentVersionRoutingTest.java),
[`LoopbackCurrentVersionIT.java`](../src/test/java/ai/labs/eddi/integration/LoopbackCurrentVersionIT.java)

### Verification

The unit test reproduces the refusal on a client-like store and checks the routing. `LoopbackCurrentVersionIT`
(integration phase, CI only) makes the real call: the injected factory resolves the latest version of a stored
resource against the running application, and a JDK `HttpServer` on an ephemeral loopback port shows the request
is `GET /{id}/currentversion`, carries the caller's bearer token on the loopback client and none on a named remote
instance. It was not run locally. After the release, check `setup-api/sync` with `dryRun: true` and `read_workflow`
without a version on staging.

---

## 🐛 test(cluster): NatsTlsHandshakeTest race that failed unrelated PRs, mutual-TLS test re-enabled (2026-11-10)

**Repo:** EDDI (`fix/nats-tls-test-race`)

### What changed and why

`NatsTlsHandshakeTest` failed `Build & Test` intermittently on the Linux CI
runner with `expected: <CONNECTED> but was: <CLOSED>`, on branches that touch
neither NATS nor TLS (`acceptsACertificateWhoseOnlySanIsTheConfiguredHostName`
most often, `presentsTheJvmDefaultClientCertificateWhenNoEddiKeyStoreIsConfigured`
once). `presentsTheClientCertificateToABrokerThatRequiresMutualTls` had been
quarantined with `@Disabled` for 6.7.0 for the same symptom.

The cause was the test's own `connect(...)` helper. It opened the connection
with `Nats.connect(manager.buildOptions())` and shut the manager down in a
`finally`. `buildOptions()` installs the manager's connection listener, and
jnats delivers CONNECTED to it on its own executor thread. Whenever that event
arrived before `shutdown()` ran, the listener adopted the connection
(`connection = conn`, `status = CONNECTED`), so `shutdown()` no longer took its
"never started" early return and drained and closed the very connection the
test was about to assert on. Faster machines usually ran `shutdown()` first,
which is why it passed locally.

- The helper keeps each manager alive and `@AfterEach` shuts the managers down
  after the test has asserted on and closed its connection, then stops the fake
  servers.
- The helper now waits until the manager reports `isConnected()` before it
  returns, so the listener has always adopted the connection by the time a test
  asserts. The race outcome that used to fail now and then is the one every run
  takes: putting the old `manager.shutdown()` back into the helper fails all
  three CONNECTED tests every time (checked locally), instead of rarely. If the
  wait fails, the helper closes the connection itself, since the manager never
  stored it and `@AfterEach` could not reach it.
- `presentsTheClientCertificateToABrokerThatRequiresMutualTls` is re-enabled.

`NatsConnectionManager` is unchanged: in production its listener is installed
only through `start()`, and closing the connection that listener adopted is
exactly what `shutdown()` must do. Only a test that builds the options without
`start()` could be surprised by it.

**Files:** [`NatsTlsHandshakeTest.java`](../src/test/java/ai/labs/eddi/engine/cluster/NatsTlsHandshakeTest.java)

---

## 🐛 fix(httpcalls): a literal target the SSRF check refuses fails at deploy, not on the first turn (2026-11-09)

**Repo:** EDDI (`fix/httpcall-target-check-at-deploy`)

### What changed and why

External security review, finding B1 (real, low). With
`eddi.security.ssrf-protection.enabled=true`, an httpcall whose literal
`targetServerUrl` the SSRF check refuses (`http://127.0.0.1:7070/...`) saved
with 201, deployed with 200, and only failed when the first conversation
started: `ApiCallExecutor.buildRequest` threw and `POST /agents/{id}/start`
answered a bare 500. At deploy, `ApiCallsTask.configure` only refused templated
hosts.

- **One decision, two callers.** The SSRF block of `buildRequest` is extracted
  unchanged into `ApiCallExecutor.checkOutboundTarget(URI, HostResolver)`;
  `buildRequest` calls it with the JDK resolver, so runtime behaviour is the
  same. The new `IApiCallExecutor.rejectRefusedLiteralTarget` runs it at deploy
  on the scheme and authority of `targetServerUrl` and of every absolute
  `request.path` (`ApiCallsTask.rejectRefusedTargets`, formerly
  `rejectTemplatedHosts`). A refusal is a `WorkflowConfigurationException`
  naming the host, the `host:port` entry to add to
  `eddi.security.ssrf-protection.allowed-hosts`, and the alternative of a
  public address. A cloud-metadata target fails to deploy whatever the flag,
  with the metadata message (the allow-list cannot admit it, so the advice
  would be wrong).
- **Skipped at deploy:** a templated host (still handled by
  `rejectTemplatedAuthority`) and a `${vars:…}`/`${vault:…}` host — both are
  only known per call, where the same check runs.
- **DNS:** a host that does not resolve at deploy is logged, not refused. The
  execution path still refuses it with protection on; but a failed lookup at
  deploy is as likely a transient resolver problem (agents auto-deployed at
  startup) as a wrong name, and an unresolvable name cannot be reached anyway.
  A blocked name (`localhost`, `*.internal`, `*.local`) is refused without a
  lookup, as at execution.
- **DNS lookups are bounded.** The check resolves the host (deploy and, as
  before, every call), and `InetAddress.getAllByName` has no timeout: a
  black-holed name server would hold the deploying or turn thread for the OS
  default. Both now go through `BoundedHostResolver` (small bounded daemon pool,
  deadline `eddi.security.ssrf-protection.dns-timeout`, default `5s`). A timeout
  or a full pool is an `UnknownHostException`, i.e. "does not resolve": refused
  at execution with protection on, logged at deploy, never allowed. No default
  of any security flag changes. New property documented in
  `configuration-reference.md`; `BoundedHostResolverTest` covers fast, slow,
  failing and saturated.
- **Upgrade note** added to `upgrading-from-6.5.md` (Changes after 6.7.0).
- **No self exception for httpcalls — documented, not changed.** The
  `application.properties` comment on `eddi.self.base-url` claimed loopback was
  always refused with protection on; it now says that `OutboundTargetPolicy`
  targets accept the self URL while httpcalls need the host on the allow-list.
  `OutboundTargetPolicy`'s Javadoc no longer says it applies the flag "the way
  ApiCallExecutor" does without naming that difference, and the LLM httpcall
  tool's refusal message (`HttpCallToolsProvider.describeToolFailure`) now
  names the allow-list as well as `eddi.self.base-url`.

The Platform Operator needed no change: its prompt and allow-list say nothing
about SSRF, and a refused deploy surfaces through the deployment status it
already reads.

**Files:** [`ApiCallExecutor.java`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallExecutor.java),
[`IApiCallExecutor.java`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/IApiCallExecutor.java),
[`BoundedHostResolver.java`](../src/main/java/ai/labs/eddi/engine/net/BoundedHostResolver.java),
[`ApiCallsTask.java`](../src/main/java/ai/labs/eddi/modules/apicalls/impl/ApiCallsTask.java),
[`OutboundTargetPolicy.java`](../src/main/java/ai/labs/eddi/engine/httpclient/OutboundTargetPolicy.java),
[`HttpCallToolsProvider.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/HttpCallToolsProvider.java),
[`application.properties`](../src/main/resources/application.properties),
[`security.md`](security.md),
[`httpcalls.md`](httpcalls.md),
[`configuration-reference.md`](configuration-reference.md),
[`ApiCallTargetDeployCheckTest.java`](../src/test/java/ai/labs/eddi/modules/apicalls/impl/ApiCallTargetDeployCheckTest.java),
[`ApiCallsTaskTest.java`](../src/test/java/ai/labs/eddi/modules/apicalls/impl/ApiCallsTaskTest.java)

---

## 🐛 fix(spaces): a space id that matches none of the caller's spaces no longer says "not a member" (2026-11-09)

**Repo:** EDDI (`fix/space-errors-and-descriptor-patch`)

### What changed and why

External security review, findings B9 and B10 — both message-only. The checks
themselves are unchanged: still exact, still fail-closed.

- **B9 — `?space=` on `/spacestore/**`.** Space ids are opaque and may contain
  `%` (group `a|b` is `team:a%7Cb`). Sent raw, the id is decoded once by the
  container and arrives as `team:a|b`, which matches none of the caller's
  spaces — and the 403 told a member "You are not a member of that space." It
  now quotes the id as received (sanitised, capped at 200 characters) and says
  to send the id exactly as `GET /workspaces` lists it, URL-encoded with
  `encodeURIComponent` — what the Manager already does.
- **B10 — `X-EDDI-Space`.** The header is compared exactly and
  case-sensitively (Keycloak group names are case-sensitive), so
  `TEAM:engineering` or `team:engineering/` is refused — correctly — with
  "You are not a member of the space …". The refusal now says the value
  matches none of the caller's spaces, that the comparison is exact and
  case-sensitive with only surrounding whitespace ignored, and points at
  `GET /workspaces`.

Both texts live in one new class, `SpaceRefusals`. Neither lists the caller's
spaces: `GET /workspaces` already does, and an error body ends up in client
logs. The OpenAPI 403 descriptions on `IRestSpaceResources` say the same.
`docs/workspaces.md` documents the exact header match and the encoding of
`space`.

The Platform Operator quotes neither message (checked
`ui/manager/src/lib/operator/`), so its prompt and allow-list are unchanged.

**Files:**
[`SpaceRefusals.java`](../src/main/java/ai/labs/eddi/engine/security/spaces/SpaceRefusals.java),
[`SpaceHeaderFilter.java`](../src/main/java/ai/labs/eddi/engine/security/spaces/SpaceHeaderFilter.java),
[`RestSpaceResources.java`](../src/main/java/ai/labs/eddi/engine/security/spaces/rest/RestSpaceResources.java),
[`IRestSpaceResources.java`](../src/main/java/ai/labs/eddi/engine/security/spaces/rest/IRestSpaceResources.java),
[`SpaceHeaderTest.java`](../src/test/java/ai/labs/eddi/engine/security/spaces/SpaceHeaderTest.java),
[`SpaceResourcesTest.java`](../src/test/java/ai/labs/eddi/engine/security/spaces/SpaceResourcesTest.java),
[`workspaces.md`](workspaces.md)

---

## 🐛 fix(descriptors): PATCH refuses a document that sets anything but name and description (2026-11-09)

**Repo:** EDDI (`fix/space-errors-and-descriptor-patch`)

### What changed and why

External security review, PATCH side note. `PATCH /descriptorstore/descriptors/{id}`
applies `name` and `description` only, and answered 204 to a document that also
set `visibility`, `spaceId`, `grants` or anything else — so a client making an
agent private was told it had, while its visibility stayed as it was. It now
answers **400** and writes nothing. The message names every unsupported field
and where it is changed instead: visibility, space, owner and grants under
`/descriptorstore/descriptors/{id}/shares/…`; `deleted` by a DELETE on the
resource's own store; `resource`, the timestamps, `originId`, `syncedVersion`
and `accessIndex` are maintained by EDDI. A DELETE patch is held to the same
rule, since it would not clear those fields either.

**How "set" is detected.** A declared field counts when it is non-null —
`deleted`, a primitive, when it is `true`; none of the others has a
deserialisation default, and every in-process caller builds its patch from an
empty `DocumentDescriptor`. A key the model does not declare at all (the
review's `resourceVisibility`) was dropped by the REST mapper's
`FAIL_ON_UNKNOWN_PROPERTIES=false`, so `DocumentDescriptor` gained a
`@JsonAnySetter` that records such keys — names only, capped at 100, never
serialised or stored, values still discarded — exposed as `unmodeledFields()`.
Stored descriptors with legacy fields load exactly as before. Undeclared keys
are echoed quoted, sanitised, shortened to 64 characters and at most ten
(`N more` for the rest). `callerLevel` and `ownerName` are read-only, and Jackson
hands such a property to the any-setter rather than dropping it; they are
skipped there, so a client echoing them is not refused for it.

**Caller audit.** Every PATCH of the descriptor store sends name and/or
description only: the Manager's `updateDescriptor` (nine call sites, the operator marker among them), MCP
`create_agent` / `update_agent`, `AgentSetupService`, and
`src/main/docker/seed-demo-agent.sh`. The Chat UI sends none, and the Platform
Operator's prompt says nothing about this body — its write canary renames the
operator, a name-only patch — so neither the prompt nor the allow-list changes.
`updateDescriptor`'s parameter type also allowed a `resources` key no caller
used and the server never had; it is gone. Unknown keys elsewhere in EDDI are
unaffected: the recording is on this model only, and only this endpoint reads it.

**Files:**
[`DocumentDescriptor.java`](../src/main/java/ai/labs/eddi/configs/descriptors/model/DocumentDescriptor.java),
[`RestDocumentDescriptorStore.java`](../src/main/java/ai/labs/eddi/configs/descriptors/rest/RestDocumentDescriptorStore.java),
[`IRestDocumentDescriptorStore.java`](../src/main/java/ai/labs/eddi/configs/descriptors/IRestDocumentDescriptorStore.java),
[`RestDocumentDescriptorStoreTest.java`](../src/test/java/ai/labs/eddi/configs/descriptors/rest/RestDocumentDescriptorStoreTest.java),
[`descriptors.ts`](../ui/manager/src/lib/api/descriptors.ts),
[`developer-quickstart.md`](developer-quickstart.md)

---

## 🐛 fix(openai-compat): the /v1 shared key works with OIDC on (2026-11-09)

**Repo:** EDDI (`fix/v1-shared-key-under-oidc`)

### What changed and why

Security review finding B7. With `quarkus.oidc.tenant-enabled=true` (for example `docker-compose.auth.yml`) and the default `eddi.openai-compat.http-policy=permit`, every `/v1` request carrying the shared key (`Authorization: Bearer sk-…`) got `401` with a `WWW-Authenticate` challenge from the OIDC layer, and `OpenAiAuthFilter` never ran. Requests without an `Authorization` header did reach the filter. Open WebUI cannot mint per-user OIDC tokens, so Keycloak + Open WebUI, the main production setup, could not work, although [the docs](open-webui-integration.md) said it did.

**Cause.** `/v1/*` has its own `permit` permission so the key can reach the filter, but Quarkus authenticates proactively. Read from the Quarkus 3.40.1.1 sources (the version `pom.xml` pins): `HttpSecurityRecorder.AuthenticationHandler` runs `HttpAuthenticator.attemptAuthentication` before any permission policy is consulted, and an `AuthenticationFailedException` from it goes to `DefaultAuthFailureHandler`, which sends the challenge and a `401` whatever the policy says. `OidcAuthenticationMechanism` hands the bearer value to its bearer mechanism, which cannot verify `sk-…` as a token and throws. Without a header it returns no identity instead, which is why only that case got through.

**Fix.** A new `OpenAiPermitModeAuthMechanism` (an `HttpAuthenticationMechanism` bean). `HttpSecurityConfiguration.getMechanisms` sorts mechanisms by descending priority, and `HttpAuthenticator.createSecurityIdentity` stops at the first one that returns an identity when inclusive authentication is off. The new mechanism ranks one above `quarkus.oidc.priority` (default 1001) and, for a `/v1` request in permit mode, returns the anonymous identity. That is the identity `HttpSecurityRecorder` assigns to a request that carried no credential at all. OIDC is never asked about the key, the `permit` policy admits the request, and `OpenAiAuthFilter` checks the key: the right key is served, a wrong one or a JWT gets the adapter's `401 invalid_api_key`. The mechanism verifies nothing and grants nothing; anyone can be anonymous by leaving the header out.

It fails closed. It returns no identity, and OIDC runs as before, unless all of these hold: the adapter is enabled; `eddi.openai-compat.http-policy` **and** the effective `quarkus.http.auth.permission.openai-compat.policy` are both `permit`; `quarkus.http.auth.inclusive` is off; the method is one the permission lists; and both path forms Quarkus matches permissions against (the router's `normalizedPath()` and `HttpSecurityUtils.normalizePath` of it) are `/v1` or below it. With `http-policy=authenticated` nothing changes: OIDC validates the JWT and the filter checks the role. No other path's authentication changes, and proactive authentication stays on.

`OpenAiStartupGuard` now warns at startup when OIDC is on, the adapter is in key mode, and the mechanism cannot be active (inclusive authentication, an overridden `/v1` permission, or an unknown `http-policy`). In those cases every shared-key request would still get the OIDC `401`.

### Design decisions

- **Not an OIDC tenant.** A `TenantConfigResolver` returning a disabled tenant for `/v1`, or a named disabled tenant chosen by a `TenantResolver`, would also stop the verification (`OidcAuthenticationMechanism.authenticate` returns no identity for a disabled tenant). But in quarkus-oidc either one changes how the *default* tenant starts. `TenantContextFactory.createTenantContext` silently disables a default tenant with no `auth-server-url` once a `TenantConfigResolver` bean or a named tenant exists, where that is a startup failure today. A named static tenant is also selected by `DefaultStaticTenantResolver` for any request path with a segment equal to its id.
- **Not a per-path `auth-mechanism`.** It would need a config value that switches between this mechanism and `bearer` with `http-policy`, and a mechanism that names a credential transport.
- **The key is not checked in the mechanism.** The filter already checks it, in constant time, and answers in the OpenAI error envelope. A second check would mean two places to keep in step.

### Tests

- `OpenAiPermitModeAuthMechanismTest`: activation (each fail-closed condition on its own), path and method matching including disagreeing normalizations, the anonymous identity, no challenge, priority above OIDC, a chain that reproduces `HttpAuthenticator`'s ordering and shows the OIDC mechanism is never called for a `/v1` key request, and a pin on the shipped `openai-compat` permission in `application.properties`.
- `OpenAiStartupGuardTest`: the new warning condition.
- The Keycloak Auth E2E tier (`ui/manager/docker-compose.integration-keycloak.yml`) now enables the adapter with a shared key. [`e2e/auth/openai-compat.spec.ts`](../ui/manager/e2e/auth/openai-compat.spec.ts) covers: the key is served on `/v1/models`; a full chat completion runs end to end; a wrong key, no key and a Keycloak JWT each get the adapter's `401 invalid_api_key` with no challenge; the key on a non-`/v1` path still gets OIDC's `401`. These are the regression tests against a real OIDC provider.

**Files:** [`OpenAiPermitModeAuthMechanism.java`](../src/main/java/ai/labs/eddi/integrations/openai/OpenAiPermitModeAuthMechanism.java), [`OpenAiStartupGuard.java`](../src/main/java/ai/labs/eddi/integrations/openai/OpenAiStartupGuard.java), [`OpenAiAuthFilter.java`](../src/main/java/ai/labs/eddi/integrations/openai/OpenAiAuthFilter.java) (Javadoc), [`application.properties`](../src/main/resources/application.properties) (comments), [`open-webui-integration.md`](open-webui-integration.md), the two test classes, the E2E spec and compose file.

The Platform Operator needs no change: neither its prompt nor its allow-list covers the `/v1` adapter, which is deployment configuration.

---

## ♻️ refactor(llm): one AbandonableStreamCall for both streaming skeletons (2026-11-08)

**Repo:** EDDI (`refactor/abandonable-stream-call`)

### What changed and why

The legacy streaming path (`StreamingLegacyChatExecutor`) and the tool loop's streaming bridge (`ToolLoopStreamingChatModel`) each carried their own copy of the same concurrency skeleton: a latch per call, an `abandoned` gate, a lock that makes recording, forwarding and abandoning mutually exclusive, and the await with a timeout. The legacy copy was marked "KEEP IN SYNC" with the other, which is the only thing that kept a concurrency fix in one from missing the other.

- **New `AbandonableStreamCall`** — the one implementation. A fresh instance per call is the provider's `StreamingChatResponseHandler`; `await(seconds)` abandons the call before it returns on a timeout or an interrupt, `abandon()` covers a retried error, `forwardedText()` reads under the lock, and `runUnlessAbandoned` covers the legacy path's single-chunk fallback emit. Both callers keep their own policy: what an interrupt means (legacy salvages or throws, the tool loop throws), the timeout metric, the separator between tool-loop rounds, and the log wording.
- **The below-backstop `timeout` warning is no longer static state.** Its warn-once key set moved into an instance helper owned by the executor (one executor per `LlmTask`, shared with the cascade, so still once per process in production), and the two test-only static hooks (`shortTimeoutWarningCount`, `resetShortTimeoutWarnings`) are gone — the tests count the WARN lines instead.
- **`resolveTimeoutSeconds` got its Javadoc back.** The precedence rules for `timeout` against `streamingTimeoutSeconds` sat above a second Javadoc block and documented nothing. The static method is now the pure resolution (the cascade's step margin uses it and logs nothing); requests go through the instance method `backstopSeconds`, which also warns. `LlmTask`'s tool-loop bridge uses `backstopSeconds`, so that path still warns as before.

Behaviour is unchanged: the same text reaches the sink and memory, the same exceptions, metadata, metrics and log lines.

**Files:** [`AbandonableStreamCall.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/AbandonableStreamCall.java), [`StreamingLegacyChatExecutor.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/StreamingLegacyChatExecutor.java), [`ToolLoopStreamingChatModel.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/ToolLoopStreamingChatModel.java), [`LlmTask.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/LlmTask.java), [`AbandonableStreamCallTest.java`](../src/test/java/ai/labs/eddi/modules/llm/impl/AbandonableStreamCallTest.java), [`StreamingLegacyChatExecutorTimeoutTest.java`](../src/test/java/ai/labs/eddi/modules/llm/impl/StreamingLegacyChatExecutorTimeoutTest.java)

### Design decisions

- **A handler object, not a template method.** The two callers differ in everything after the await (retry and salvage versus a thrown exception the tool loop's retry classifies), so the shared part is the call's state and its gate, and each caller keeps its control flow.
- **`abandoned` is a plain field under the lock.** Both copies only ever read and wrote the `AtomicBoolean` inside the lock, so the atomic added nothing.
- **Not unified here:** the timeout counter is still incremented from the executor's injected `MeterRegistry` on one path and from `Metrics.globalRegistry` on the other. Moving the tool loop onto the injected registry changes which registry a deployment sees the `tool_loop` series in, which is a behaviour change for a separate PR.

---

## 🧪 refactor(llm): chat/streaming parity test for every provider builder (2026-11-08)

**Repo:** EDDI (`refactor/builder-option-appliers`)

Every provider builder configures two langchain4j clients, a synchronous one and a streaming one, and the two builder types
share no supertype, so each builder applied its parameters twice, in two method bodies that differed only in the retry
setting. Nothing failed when one of them was edited and the other was not: only Anthropic had a test comparing the two, and
the whole builder package was excluded from the coverage gate.

### What changed and why

- **`ProviderChatStreamingParityTest`** builds both clients of every provider (OpenAI, the eight OpenAI-compatible
  presets, Anthropic, Mistral, Ollama, Gemini, Azure OpenAI and Bedrock) from parameter maps that together set every key the
  builder recognises, and asserts that the two report equal `defaultRequestParameters()` and send the same first request:
  URL, headers, JSON body without the streaming switch, and client timeouts. A key added to `recognisedParameters()`
  without a value in the test fails it, so the check cannot silently fall behind. Bedrock's AWS client cannot be intercepted
  offline and is compared on its request parameters only.
- **Streaming test seams** for OpenAI, Mistral and Ollama (`buildStreaming(…, HttpClientBuilder)`), matching the ones
  Anthropic and Gemini already had, and an `HttpClientProvider` seam for both Azure clients. Production passes the same
  clients as before.
- **`RecordingHttpClient`** records the connect and read timeouts the provider client configures, so the timeout is compared
  too.
- **JaCoCo** now excludes only the two builders whose clients cannot be built offline (Vertex: GCP credentials; OCI:
  `~/.oci/config`), instead of the whole package on the stale ground that it had no logic and needed live keys. The package
  holds the parameter catalog, the value parsing and the bounded HTTP client, all unit-tested.
- The OpenAI and Azure Javadoc no longer claims the two clients "cannot drift"; the test is what checks it.

**Files:** [`ProviderChatStreamingParityTest.java`](../src/test/java/ai/labs/eddi/modules/llm/impl/builder/ProviderChatStreamingParityTest.java),
[`RecordingHttpClient.java`](../src/test/java/ai/labs/eddi/modules/llm/impl/builder/RecordingHttpClient.java),
[`OpenAILanguageModelBuilder.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/builder/OpenAILanguageModelBuilder.java),
[`MistralAiLanguageModelBuilder.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/builder/MistralAiLanguageModelBuilder.java),
[`OllamaLanguageModelBuilder.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/builder/OllamaLanguageModelBuilder.java),
[`AzureOpenAiLanguageModelBuilder.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/builder/AzureOpenAiLanguageModelBuilder.java),
[`pom.xml`](../pom.xml)

---

## ♻️ refactor(llm): provider request parameters built once for both clients (2026-11-08)

**Repo:** EDDI (`refactor/builder-option-appliers`)

With the parity test in place, the request-level settings of each provider builder are now built once, into langchain4j's
own request-parameter type for that provider, and handed to the synchronous and the streaming client through
`defaultRequestParameters(...)`. langchain4j starts every request from those, so the two clients cannot send different
sampling, token, stop, seed, caching or thinking settings any more; what each client builder still receives on its own is
the short list of options that are not part of a request (endpoint, credentials, timeout, logging, headers) or that the
library only accepts there.

### What changed and why

- **OpenAI** (and through it the OpenAI-compatible presets): `OpenAiChatRequestParameters` carries model, sampling,
  token limits, stop, seed, logit bias, reasoning effort, service tier, parallel tool calls, store, metadata, user, prompt
  cache key and the custom body fields. `strictTools`, `strictJsonSchema` and the `json_object` response format stay on
  the client builders.
- **Anthropic**: `AnthropicChatRequestParameters` carries model, `maxTokens`, sampling, stop, thinking type and budget,
  the thinking switches, the cache flags and TTL, `disableParallelToolUse` and `userId`. `thinkingDisplay`, `strictTools`,
  version, beta and the custom body fields stay on the client builders, which are the only place langchain4j takes them.
- **Mistral**: `MistralAiChatRequestParameters` carries everything except `strictJsonSchema` and the connection options.
- **Ollama**: the two hand-kept `applyTo` twins are gone; the parameters are read once into a `Settings` record whose
  `OllamaChatRequestParameters` carries the sampling and context options, `think` and `truncate`.
- **Gemini**: the two `applyExtras` twins are replaced by one `read` into a `Settings` record, so the thinking-config and
  media-resolution parsing (and their warnings) is written once instead of twice. Model, sampling, token limit,
  stop and the cached-content name travel as `GoogleAiGeminiChatRequestParameters`; the rest (seed, thinking config,
  tools, safety settings, media resolution) are options only the client builders take.
- **Azure OpenAI**: Azure has no request-parameter type of its own, so the common sampling settings travel as
  `DefaultChatRequestParameters` and the Azure-specific ones stay on the client builders.
- **Bedrock** is unchanged: it already built one `BedrockChatRequestParameters` for both clients.

No configuration key, default or wire field changes. The requests both clients send, and the models' default request
parameters, were compared field by field before and after the change for every provider, with a full and a minimal
parameter map, and are identical.

**Files:** [`OpenAILanguageModelBuilder.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/builder/OpenAILanguageModelBuilder.java),
[`AnthropicLanguageModelBuilder.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/builder/AnthropicLanguageModelBuilder.java),
[`MistralAiLanguageModelBuilder.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/builder/MistralAiLanguageModelBuilder.java),
[`OllamaLanguageModelBuilder.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/builder/OllamaLanguageModelBuilder.java),
[`GeminiLanguageModelBuilder.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/builder/GeminiLanguageModelBuilder.java),
[`AzureOpenAiLanguageModelBuilder.java`](../src/main/java/ai/labs/eddi/modules/llm/impl/builder/AzureOpenAiLanguageModelBuilder.java)

---

## 🐛 fix(configs): one endpoint-parameter rule; brand SVG refuses CSS escapes (2026-11-08)

**Repo:** EDDI (`fix/endpoint-params-brand-svg`)

### What changed and why

**One answer to "does this parameter name an endpoint?"** Five places asked it, each with its own list, and the lists had drifted. The LLM registry knew Vertex's `apiEndpoint`; the embedding check knew `apiBase`, `api_base` and `apiUrl`; the export merge (`ScrubbedSecrets`) knew neither `apiEndpoint` nor `apiBase`. The merge's list matters most: it decides whether a target's hidden literal credential is put back into a synced config, and it withholds the credential when the config's destination changed. A synced config that changed only `apiEndpoint` or `apiBase` therefore had the target's literal key restored next to an endpoint the editor chose. No shipped builder reads either key together with an `apiKey` today (Vertex uses ADC), so this was latent, but the next builder that does would have opened it.

[`EndpointParameters.isEndpointKey`](../src/main/java/ai/labs/eddi/utils/EndpointParameters.java) is now the one rule: case-insensitive, any key ending in `url`, plus `uri`, `endpoint`, `apiEndpoint`, `apiBase`, `api_base`, `host` and `hostname`. The rule is the union of the old lists. `ScrubbedSecrets`, `ChatModelRegistry` (the `allowedHosts` check), `EmbeddingParameters` (refusing an endpoint the provider ignores) and `EmbeddingModelFactory` (its `allowedHosts` check) and `LlmTask` (which parameters a conversation summary on a different provider may not inherit) all use it.

Behaviour changes, all in the refusing direction:
- **Agent sync / upgrade:** a change to any of these keys now keeps the `${vault:REDACTED}` placeholder instead of restoring the target's literal credential.
- **LLM `allowedHosts`:** every endpoint parameter that is set is checked against a referenced secret's list. That covers `apiBase`, `hostname`, `uri` and any `…Url`, not only the six keys listed before.
- **Embedding parameters:** an endpoint parameter the provider does not honour is refused under more spellings (`apiEndpoint`, `hostname`, `uri`, `proxyUrl`, …), and with a restricted secret it is reported as an `allowedHosts` refusal first, as the factory's comment already said.
- **Conversation summary on another provider:** every endpoint parameter of the task is withheld from the summarizer, not only `baseUrl`, `endpoint` and `apiEndpoint`, so a summary provider that reads, say, `apiBase` never sends its own key to the task provider's host.

**Brand SVGs refuse backslashes.** [`BrandAssetValidator`](../src/main/java/ai/labs/eddi/configs/branding/BrandAssetValidator.java) checked `url(` and `@import` as literal text. CSS decodes escapes, so `u\72l(http://…)` and `@\69mport "…"` load external resources while matching neither check. Any backslash now refuses the SVG. The check covers the whole document, not only `<style>` and `style="…"`, because every presentation attribute (`fill`, `filter`, `mask`, …) is parsed as CSS too. Finding where an element really ends would also need a parser that agrees with the browser's (CDATA, end tags). `image-set()` is refused as well, since it loads an image from a plain string without `url()`. An editor that writes a Windows path into its metadata (Inkscape's `inkscape:export-filename`) now trips the check, and the refusal message says to export a plain or optimized SVG.

**Upgrade note:** the images in `eddi.branding.assets-dir` go through the same checks at startup, and a refused file fails startup. A seeded SVG containing a backslash (or `image-set(`) that started before this change now stops the deployment from starting, with an error naming the file; re-export it as a plain or optimized SVG before upgrading. An SVG already uploaded through the API is not re-checked.

**Docs:** [White-labeling](white-labeling.md), [Agent sync](agent-sync-guide.md) and [Secrets vault](secrets-vault.md) list the new rules.

### Tests

`EndpointParametersTest`; `ScrubbedSecretsDestinationTest` (every endpoint spelling is a destination; an ordinary parameter is not); `ChatModelRegistryAllowedHostsTest` (`apiBase`, `hostname` and `proxyUrl` are checked); `EmbeddingParametersBranchTest` (`apiEndpoint` on Vertex, `hostname` on Bedrock); `LlmTaskSummaryCredentialIsolationTest` (every endpoint spelling stays with the task's provider); `VaultAllowedHostsCoverageTest` (an unsupported endpoint key is judged by `allowedHosts`); `BrandAssetValidatorTest` (escaped `url(`/`@import` in `<style>`, CDATA, `style` and presentation attributes; `image-set()`; editor metadata refused with the way out).

---

## 🐛 fix(manager): saves and previews do not overwrite edits made while in flight (2026-11-08)

**Repo:** EDDI (`fix/manager-form-state`)

### What changed and why

Four Manager forms let the admin keep working while a request was running, then
applied the reply (or, for the transfer dialog, a Cancel) to whatever the form had
become by then.

- **Quotas** — a successful save replaced the form with the server's copy and
  cleared the dirty flag, so a limit typed during the PUT vanished and the
  unsaved-changes guard stopped protecting it. The page now counts edits; the
  server copy is taken only when nothing was edited since sending, otherwise the
  form keeps the edit and stays dirty.
- **Connection detail** — the same on success, plus a second loss: following the
  new version reset the form and re-seeded it from the cache. The save now moves
  the baseline to what it stored, keeps the draft when the form (or uncommitted
  chip text) changed since sending, and tells the route-change reset to leave that
  draft alone for exactly the version it created. The edit stays unsaved and the
  next save goes against the new version. Text typed into a parameter row that has no
  name yet counts as a change too, though such a row is not part of the document.
- **Agent sync** — a batch preview landed on rows whose target or "Create new"
  choice had changed during the request (a diff for one target shown under
  another, with Sync armed to send the new one), or that a reconnect had relisted
  at a newer source version, and a row ticked mid-flight was marked "Preview
  failed". A preview now applies only to a row still checked and still naming the
  source version and target it was sent with.
- **Team budget transfer** — Cancel always started a fresh `Idempotency-Key` and
  an empty form, so after a 5xx or network error (which may have moved the money)
  re-entering the same transfer could move it twice. After such an error, or when
  the dialog is closed while the transfer is still running, closing keeps the
  values, the error and the key; a success or a 4xx starts a new transfer. The table's dead `initialFromId` prop is gone (its only opener passed
  `""`), and its open state is a boolean.

### Design decisions

- Snapshot-and-compare rather than locking the fields while saving: the forms
  stay usable, and nothing typed is thrown away. Quotas uses an edit counter
  because a half-typed number changes the dirty flag without changing the form.
- No new component or hook; each fix stays in the file that owns the state.

**Files:** [`quotas.tsx`](../ui/manager/src/pages/quotas.tsx),
[`connection-detail.tsx`](../ui/manager/src/pages/connection-detail.tsx),
[`sync-page.tsx`](../ui/manager/src/pages/sync-page.tsx),
[`team-budget-dialogs.tsx`](../ui/manager/src/components/quotas/team-budget-dialogs.tsx),
[`team-budgets-table.tsx`](../ui/manager/src/components/quotas/team-budgets-table.tsx),
[`team-budgets.md`](team-budgets.md)

---

## 🐛 fix(rag): storage collision check refuses on unreadable neighbours; cluster-shared ingestion status (2026-11-08)

**Repo:** EDDI (`fix/rag-store-guards`)

### What changed and why

**The explicit-location collision check now fails closed when another knowledge base cannot be read.**
`KnowledgeBaseStorageGuard` refuses an explicit `table` / `collectionName` / `indexName` that another
knowledge base already uses unless the caller may edit that one too. It already failed closed when the
scan ran past its page budget, but a neighbour whose document could not be read (a `ResourceStoreException`
or any runtime error while loading it) was logged at debug level and skipped. The neighbour that cannot be
read may be the very one that owns the location, so a transient read error let an editor point their
knowledge base at another knowledge base's table. Such a save now fails with the same `IllegalStateException`
the descriptor-listing failure already raised (a 500), naming the knowledge base that could not be read. A
neighbour that is gone (`ResourceNotFoundException`, deleted since it was listed) still owns no location and
is still skipped.

**Ingestion status is shared across a cluster.** `RagIngestionService` kept the status and warning of each
`POST /ragstore/rags/{id}/ingest` in two private Caffeine caches, so with `eddi.messaging.type=nats` a poll of
`GET …/ingestion/{ingestionId}/status` that a load balancer sent to another node answered 404 "no ingestion
with this id is known" for an ingestion that was running or done. Both now live in one cache from
`ICacheFactory`, `rag-ingestion-status` (one-hour TTL, sized 20,000: a status and at most one warning per
ingestion), which `CacheFactory.SHARED_BUCKETS` maps to the new `RAG_INGESTION` KV bucket. A single node
behaves exactly as before.

**A value written while NATS was down no longer reads back as the older shared value, and never overwrites a newer one.** `SharedKvCache.get()` only consulted the node-local copy when the bucket answered empty, so a key written `processing` with NATS up and `completed` during an outage kept reading `processing` after recovery, even on the writing node. A degraded write now remembers the bucket revision this node last saw for the key (or that the key was absent, or that it is unknown), and the next read of the key publishes the local value **by compare-and-set** against that revision (`update`, or `create` for an absent key). If another node wrote or deleted the key meanwhile, the bucket's state wins, the marker and the stale local copy are dropped and nothing is overwritten or resurrected; an unknown revision yields to a present bucket value and creates an absent one. A value the bucket refuses as too large (`ClusterValueRefusedException`, a subclass of `ClusterUnavailableException` thrown by `NatsSharedKv` for a payload or message size refusal) is tracked apart and never retried, so the read path costs no failed network write: it behaves as before, answering from the local copy only while the bucket holds nothing. Republishing starts a new bucket TTL (the KV API cannot preserve the original expiry), so a status written during an outage can stay readable for up to about twice its TTL after the last update; the writing node's own copy expires after one TTL, so nothing older is ever republished. Applies to all shared caches.

### Design decisions

- **One cache and one bucket, two key kinds.** The status is kept under `status:` plus the ingestion id
  and the warning under `warning:` plus the id, so neither can overwrite the other in the bucket, and an id
  polled as `warning:<id>` finds no status. Two caches in two buckets would add a JetStream stream for a
  handful of strings per ingestion.
- **A status is bound to its knowledge base.** The status endpoint checks access on the RAG configuration
  in its path, but an ingestion id is global, so an editor of knowledge base B holding an id of A could poll
  it through B and read A's document name or failure message (on any node, once the cache is shared). Both
  keys now carry the RAG configuration id (`status:<ragConfigId>:<id>`), so an id polled through another
  knowledge base is unknown (404). No extra NATS writes.
- **No retry timer after a NATS outage.** If NATS is unreachable while the terminal status is written, that write stays on the originating node, like every other non-fail-closed shared cache. It is published when that node next reads the key (compare-and-set, see above); no background retry is added here.
- **500, not 400, for an unreadable neighbour.** The request is not wrong; the server could not check it.
  It matches the listing-failure path right beside it, and a retry succeeds once the store answers.
- **Not in this PR:** serialising the check and the write (two editors saving the same new location at the
  same moment both pass). A per-JVM lock does not serialise a cluster, the import and upgrade paths write
  outside `RestRagStore`, and in the per-id layout, which every new knowledge base gets, chunks in a shared
  table are still filtered by knowledge-base id (a knowledge base still in the 6.5.0 name layout is not
  filtered, so for one of those the race is a real read). Injecting the
  guard into `RestRagStore` instead of constructing it is only a step toward that lock, so it waits for it.
  The replace locks in `RagIngestionService` stay per instance, as their Javadoc says.

**Files:** [`KnowledgeBaseStorageGuard.java`](../src/main/java/ai/labs/eddi/configs/rag/rest/KnowledgeBaseStorageGuard.java),
[`RagIngestionService.java`](../src/main/java/ai/labs/eddi/modules/rag/RagIngestionService.java),
[`CacheFactory.java`](../src/main/java/ai/labs/eddi/engine/caching/CacheFactory.java),
[`SharedKvCache.java`](../src/main/java/ai/labs/eddi/engine/caching/SharedKvCache.java),
[`ClusterValueRefusedException.java`](../src/main/java/ai/labs/eddi/engine/cluster/ClusterValueRefusedException.java),
[`rag.md`](rag.md), [`clustering.md`](clustering.md); tests in `KnowledgeBaseStorageGuardTest`,
`RagIngestionServiceTest`, `SharedKvCacheTest` and `CacheFactoryTest`.

---

## fix(datastore): Mongo id queries fetch only id and version; deletes lock per id instead of per store (2026-11-08)

**Repo:** EDDI (`fix/resource-storage-small`)

### What changed and why

- **MongoDB id queries project `_id` and `_version` only.**
  `MongoResourceStorage.findResources` returns `(id, version)` pairs. The
  listings read the documents again by id (descriptor listings through
  `readMany`, the group conversation and shared artifact listings through
  `read`), and the GDPR erasure loops only need the ids. Without a projection,
  every match crossed the wire and was decoded for nothing, whole transcripts
  and artifact bodies included, and a listed one was decoded twice. `findResources`,
  `findResourceIdsContaining` and `getCurrentVersion` now project
  `{_version: 1}` (`_id` comes back unless excluded), and
  `findHistoryResourceIdsContaining` projects `{_id: 1}`, the composite id that
  carries the version. `getCurrentVersion` is behind
  `HistorizedResourceStore.getCurrentResourceId`, which has about 150 callers.
  Filtering and sorting still run on the server over the full documents. The
  PostgreSQL twin already selected only these columns.
- **`readHistoryLatest` makes one round trip instead of two.** It ran
  `countDocuments` and then the same filter as a sorted `limit(1)` find. The
  find returns null on its own when there is no history row, so the count is
  gone. The history document is still read in full, because callers of
  `IHistoryResource` can call `getData()`.
- **`HistorizedResourceStore` locks per resource id instead of per store.**
  `delete` and `deleteAllPermanently` were `synchronized` on the store, and
  there is one store instance per resource type, so on one node every delete of
  that type (for example the GDPR erasure loop over conversation descriptors)
  waited for the one in progress, which held the monitor across several
  majority-acknowledged round trips. The monitor was not useless, though: on
  MongoDB without a transaction it was the only thing on a node that kept a
  `delete` from reading a resource, losing the CPU while `deleteAllPermanently`
  erased it, and then upserting its tombstone (the erased data) into the emptied
  history after the erasure had reported success. It did not cover the same race
  through `update`, which archives the predecessor before its version-checked
  replace and was never locked. Now `update`, `delete` and
  `deleteAllPermanently` take one of 64 `ReentrantLock` stripes, chosen by
  `Math.floorMod(id.hashCode(), 64)`, for their whole read-then-write sequence.
  Same-id sequences on a node run one after the other, different ids contend
  only when they share a stripe, and each call holds one stripe and calls only
  the storage under it, so stripes are never nested. **What it does not
  guarantee:** the lock is per JVM, so a delete or update on one node and an
  erasure of the same id on another can still meet on MongoDB, exactly as they
  could under `synchronized`; `ModifiableHistorizedResourceStore.set` is not
  locked (its writes are version-checked replaces with no upsert, so they cannot
  write into an erased id); and stores that call `IResourceStorage.removeAllPermanently`
  directly (group conversations, shared artifacts, workspaces, agent test runs)
  bypass it. Two deletes of the same id, and update against delete, are decided
  by the storage's version checks either way.

### Design decisions

- `readHistoryLatest` keeps reading the full document (no projection), since
  the history resource it returns is a data accessor.
- A striped lock rather than no lock or a map of per-id locks: removing the
  monitor outright reopened the MongoDB erasure race on a single node, and a
  `ConcurrentHashMap` of locks grows with every id ever touched. 64 stripes are
  a fixed 64 locks per store.
- **PostgreSQL `storeHistoryAndRemove` now deletes first and writes the tombstone
  only if the delete removed the row.** The old order (tombstone, then delete)
  let a `deleteAllPermanently` that finished between a delete's read and its
  write be undone: the tombstone was upserted over the erased id, and the
  "no live row, a concurrent delete won" branch committed it, leaving the erased
  resource's data in `resources_history`. The monitor used to prevent that on
  one node, and the per-id lock still does; ordering the statements prevents it
  on every node, because the
  delete takes the row lock (an erasure waits for it and then also deletes the
  tombstone, or it has run first and the delete writes nothing). The same
  reordering means a refused delete (an update moved the resource on) never
  writes a tombstone to roll back.
- **MongoDB keeps tombstone-then-delete and has a known residual window.**
  Without a transaction the tombstone write and the check that the row still
  exists cannot be one step, and "row gone" cannot tell a concurrent delete from
  an erasure. The `MongoResourceStorage.storeHistoryAndRemove` Javadoc states
  the limit; on one node the per-id lock closes it, across nodes it stays open,
  and a repeated erasure removes the orphan row.

**Files:** [`MongoResourceStorage.java`](../src/main/java/ai/labs/eddi/datastore/mongo/MongoResourceStorage.java),
[`PostgresResourceStorage.java`](../src/main/java/ai/labs/eddi/datastore/postgres/PostgresResourceStorage.java),
[`HistorizedResourceStore.java`](../src/main/java/ai/labs/eddi/datastore/HistorizedResourceStore.java),
[`PostgresResourceStorageTest.java`](../src/test/java/ai/labs/eddi/datastore/postgres/PostgresResourceStorageTest.java),
[`MongoResourceStorageTest.java`](../src/test/java/ai/labs/eddi/datastore/mongo/MongoResourceStorageTest.java),
[`MongoResourceStorageBranchTest.java`](../src/test/java/ai/labs/eddi/datastore/mongo/MongoResourceStorageBranchTest.java),
[`HistorizedResourceStoreTest.java`](../src/test/java/ai/labs/eddi/datastore/HistorizedResourceStoreTest.java)

---

## refactor(hitl): one GuardText for the self-ungating heuristics (2026-11-08)

**Repo:** EDDI (`refactor/self-ungating-guard-text`)

### What changed and why

The two server-side guards around an approved tool call decided "does this request name an id?" with two different decodings. `SelfUngatingGuard` percent-decoded the raw arguments and also walked them as parsed JSON, a few levels into JSON-in-a-string. `ApprovedCallScope.targetsConversation`, which backs the self-conversation rule, percent-decoded the text and ran a hand-written JSON unescape over it. Given the same arguments, the two disagreed, and each one missed spellings the other caught:

- **Self-conversation rule missed:** an id inside a document carried as a JSON string (escaped once per level, so one unescape pass leaves the escape in place), and a JSON escape that was itself percent-encoded (`%5Cu0034`).
- **Self-ungating rule missed:** an escaped id in arguments that only a lenient parser accepts (single quotes, for example, or a leading comment). Its strict parse failed, or never started because the text did not begin with a bracket or a quote, so only the raw-text test ran.

Both now go through one package-private `GuardText` in `engine/hitl/tools`. It has three parts. `decodeLower` percent-decodes and lower-cases, and keeps the raw text when an escape is malformed. `containsId` does a trimmed, case-insensitive containment test. `haystack` builds the decoded raw text plus every key and string value from a JSON walk, and follows nested JSON strings up to four levels. The walk parses with every `JsonReadFeature` enabled: a parse can only add text to the haystack, so the most lenient parse is the safe one. It parses any text that starts with a character such a document can start with, comments (`/*`, `//`, `#`) and single quotes included, not only `{`, `[` and `"`. `ApprovedCallScope.jsonUnescaped` and `urlDecoded` are deleted. The lenient parse covers what `jsonUnescaped` covered for non-strict JSON. The only thing it no longer catches is unparseable text such as truncated JSON, and no receiver accepts that either.

Also fixed: `SelfUngatingGuard.collectNonEmptyKeyPaths` carried two stacked Javadoc blocks. The first one described `collectKeyPaths`, which had none, so it now sits on that method.

### Behaviour

Behaviour only gets stricter, in two places. The self-conversation rule (on resume and in the live tool loop through `ToolLoopResumer.uriTargetsConversation`) now refuses the two spellings listed above. The raw-argument self-ungating test now refuses escaped ids and keys in lenient JSON, including JSON that starts with a comment. No config, endpoint, MCP tool or default changed.

### Tests

`GuardTextTest` (new). Decoding: malformed percent escapes, a JSON-escaped key, the nested-string depth bound, non-JSON text and `containsId`. Agreement: `SelfUngatingGuard.refusalFromArguments` and `ApprovedCallScope.targetsConversation` must both see an id in seven escaped or encoded spellings, and both must miss another id. Before the change, 5 of the 7 spellings failed, in both directions. The existing `SelfUngatingGuard*Test`, `ApprovedCallScopeTest` and `ToolLoopResumerSelf*Test` pass unchanged.

**Files:** [`GuardText.java`](../src/main/java/ai/labs/eddi/engine/hitl/tools/GuardText.java), [`SelfUngatingGuard.java`](../src/main/java/ai/labs/eddi/engine/hitl/tools/SelfUngatingGuard.java), [`ApprovedCallScope.java`](../src/main/java/ai/labs/eddi/engine/hitl/tools/ApprovedCallScope.java), [`GuardTextTest.java`](../src/test/java/ai/labs/eddi/engine/hitl/tools/GuardTextTest.java)

### Next

`ToolLoopResumer.argumentsTargetConversation` still runs its own JSON walk (`appendStrings`) after `uriTargetsConversation`. `uriTargetsConversation` already searches the full haystack, so that second pass is now redundant and can be removed. It was left in place because this PR is scoped to the two guard classes. Splitting the JSON-pointer and patch helpers out of `SelfUngatingGuard` into a `PatchGate` remains an optional follow-up.

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
| 2026-11-09 | httpcalls keep no self-URL exception under SSRF protection; a literal target the check refuses fails at deploy, an unresolvable one only logs | Review B1: refused targets deployed fine and failed the first turn with a 500 | Adding the OutboundTargetPolicy self exception to httpcalls (widens what a config editor can reach); refusing deploy on a DNS failure (a transient resolver outage would take agents down) |
| 2026-11-09 | Keep OIDC off /v1 in permit mode with a higher-priority HttpAuthenticationMechanism that returns the anonymous identity | Review B7: proactive auth handed the shared key to OIDC, which answered 401 before the permit policy or OpenAiAuthFilter ran | A disabled OIDC tenant for /v1 via TenantConfigResolver/TenantResolver (changes default-tenant startup and path-segment tenant selection); a per-path auth-mechanism; disabling proactive auth globally |
| 2026-11-07 | Every `email.*` audit entry names a mail address as `GdprPseudonymizer`'s token (`senderPseudonym` / `recipientPseudonym`) beside its domain, superseding the SHA-256 + domain of 2026-10-24 for inbound refusals and replies | An unkeyed hash next to the domain is confirmed by hashing the candidate addresses at that domain, and the write-once ledger keeps it past erasure | Keep the SHA-256 (reversible by enumeration); drop the recipient (unauditable); the suppression list's hash (a lookup key, not audit output) |
| 2026-11-07 | Unread `dream.batchSize`/`maxUsersPerRun` removed from the model behind `@JsonIgnoreProperties`; unread schedule `tenantId`/`allowSelfScheduling` deprecated and kept | Docs review: config fields nothing reads | Wiring them up: Dream has no multi-user sweep to batch, and schedules have no tenant model or self-scheduling gate to plug into; removing the schedule fields: they are DB columns with PUT carry-over semantics |
| 2026-11-07 | A migrated channel's descriptor copies the default-target agent's owner and space, but narrows `internal`/`published` to `space` and copies no grants | Channel migration wrote no descriptors; a startup job has no caller to stamp with | Leaving it unowned (legacy: visible to everyone under the default legacy policy, with the bot's credentials); copying the agent's visibility verbatim (publishing a credentials-bearing config) |
| 2026-11-07 | Schedule leases are written and judged on the database's clock; the poller's claim re-checks nextFire <= now, manual fires use tryClaimAnyTime | Two paths to a duplicate fire: a row re-armed between a poll's read and its claim, and claimedAt compared across instances' clocks | A poller-only due-checked claim beside the old one (leaves the unsafe call as the default); clock_timestamp() (changes during the statement) |
| 2026-11-07 | SecretScrubber.scrubJson throws on a document it cannot parse instead of returning it unscrubbed | It returned the input, credentials included, on any exception; serializer output can exceed Jackson's read limits | Returning a redacted placeholder document (an export that silently loses a config is worse than one that fails) |
| 2026-11-06 | Presence age is the time since this node saw the record's revision change, on its monotonic clock; a record found already written has no age until rewritten | A writer's `updatedAt` or the server's write time compared with this node's wall clock misjudged every skewed node: members vanished, drains were refused, live nodes reported stale | Keep wall-clock ages with a skew allowance (only moves the threshold); date a first-seen record as fresh (a dead node looks live to the drain guard for up to one TTL after a reconnect) |
| 2026-11-06 | Conversation listing takes `mine=true` (exact owner, any caller incl. admins); the Manager's chat resume, chat history and operator restore/History use it | An admin's chat panel resumed an end user's newest READY conversation and sent the next message into it | Filtering in the Manager (it does not know the owner principal); a separate endpoint (the listing already has every other filter) |
| 2026-11-06 | A non-200 merge update fails the import with the store's own response; a refused snippet update costs the snippet only | A dictionary or httpcalls update the store refused was created instead, unvalidated, as a duplicate with the same origin id | Validating in the direct create path and keeping the fallback (a merge target that exists must never be duplicated); failing the whole import on a snippet refusal (snippets are best-effort by design) |
| 2026-11-06 | A queued turn of a vanished conversation is reported as ENDED through the skip contract | RT-1: it ran on its stale snapshot and wrote erased properties back | A new 404 channel on the skip contract, which carries only a snapshot |
| 2026-11-06 | The late outcome of a watchdog-abandoned turn is inert (cache refresh only) | RT-5: it settled the next turn's state and dead-lettered it in cluster mode | Keying the failure maps by turn token: larger, and still needs the abandoned turn to stop writing state |
| 2026-11-06 | Stream backstop: end a started turn's stream after agentTimeout + one keep-alive interval of silence, code turn_timeout | RT-2: a hung abandoned body held its stream open indefinitely | Notifying the stream from the watchdog: the skip contract would report a turn that may still produce output as finished |
| 2026-11-06 | Per-turn LLM tool budgets are counted on the conversation step (`TurnCounter`), keyed by budget and, for delegations, by task id | A tool object is rebuilt per tool assembly, and a turn assembles once per cascade step and again on a HITL resume, so a field counter multiplied the budget | A shared counter in a CDI bean keyed by conversation id (state outside memory, lost on a resume on another node); counting in `AgentOrchestrator` and passing the count into each tool (spreads one budget over two classes) |
| 2026-11-05 | Google Workspace recipe ships without a send tool and neutralises header CR/LF in the template | Read + send turns mail-borne prompt injection into exfiltration; the argument schema ignores `pattern`, so a CR/LF ban there would be a no-op | A send tool behind `toolApprovals` by default (still one approval away from mailing attacker-chosen content); a schema `pattern` (silently ignored) |
| 2026-11-05 | HITL decisions bind to a canonical SHA-256 plan hash; strict mode opt-in per agent/group or per deployment | Approvals were bound to a pause, not to the exact arguments/actions that ran | Reusing JacksonCanonicalizer (number formatting not canonical); a deployment-HMAC instead of a per-pause nonce (key rotation and cluster distribution for no gain); storing group plan hashes |
| 2026-11-05 | Discovery streams work items to `DiscoveryContext.accept`; F1 processes each one synchronously | The crawler's budget stop, ownership fence and conditional requests interleave with per-page work, so a returned list would buffer the whole site and change the order of side effects | Returning `List<WorkItem>` from `discover` |
| 2026-11-05 | `WorkItem.Action` gains `UNCHANGED` alongside UPSERT/DELETE | An HTTP 304 against the stored validators is a provider-confirmed "unchanged" with no content and no comparable version token | Encoding 304 as UPSERT with a fake version token |
| 2026-11-05 | `Coverage.FULL_TRAVERSAL` is separate from `FULL_LISTING` | A crawl needs a "learned something definitive" guard before absence counts as deletion, but an authoritative listing (uploads) must believe an empty result | One FULL_LISTING value plus a flag; applying the crawl guard to every source, which would never delete the last uploaded file |
| 2026-11-05 | Text extraction stays in the framework, selected by `Content` kind (HtmlPage / FileBytes / Text) | Keeps the limits and the blank-page-skipped and blank-file-failed semantics in one place for every source | Connectors extracting their own text |
| 2026-11-05 | `SourceError.isDefinitive()` (NotFound, Permanent) decides "gone" vs "could not look"; a failure with no document id is never evidence | It is a lossless mapping of the crawler's `contentUnknown`, and it generalises to provider errors | Exposing a raw contentUnknown boolean in the contract |
| 2026-11-05 | A request the provider refuses as made is `SourceError.Rejected`, not definitive; `Permanent` is kept for answers about the document | "Malformed request" was listed under `Permanent`, which counts as absence, so a connector bug would delete the documents it touched. The web connector's content-known 4xx stays `Permanent`: that is the server's answer for the page, as the crawler always treated it | One `Permanent` kind for both; making `Permanent` non-definitive, which would keep unexportable files' stale chunks for ever |
| 2026-11-05 | `SourceMetadataKeys.CITATION_URL` is `"url"`, the key chunks already carry | The vocabulary describes stored data rather than renaming it | A new `citationUrl` key on chunks |
| 2026-11-04 | Budgets are the phase-0 result plus ~2–6 % headroom (630 KB Manager, 170 KB widget), in KB of 1000 bytes of gzip -9 | Plan §2.6/§7 phase 0 asks for budgets from measured numbers | Budgets at the plan's §1.2 numbers (too loose to catch regressions after the wins); KiB (the plan's table mixes units) |
| 2026-11-04 | The widget's feature-matrix column is a Vitest spec, not Playwright | `ui/chat` has no Playwright tier; adding one is a lockfile change on Windows | Driving the widget from the Manager's Playwright config (couples the two apps' installs) |
| 2026-11-03 | NATS auth/TLS failures and "never connected since boot" fail readiness by default | Review C2: such nodes served every turn unfenced while green | Capping unfenced time in the coordinator (owned by another workstream); keeping readiness UP |
| 2026-11-03 | Cluster scheduler jobs run on virtual threads behind a timer-only pool | Review D6: two blocked jobs starved presence past its TTL | A dedicated presence thread only (presence is owned by another workstream) |
| 2026-11-03 | Transient (autoDeploy=false) deployments are recorded in a NATS KV bucket with tombstones, not in the deployment collection | B1: they existed only as events | A database status + epoch: survives a full cluster restart (against autoDeploy=false semantics) and an unknown status value breaks older nodes mid-upgrade |
| 2026-11-03 | Cluster messages are signed with an HMAC key kept outside NATS; unsigned accepted unless eddi.cluster.signing.required=true | C4: one NATS credential controlled every node | Per-message asymmetric signatures (key distribution, cost); refusing unsigned by default (breaks rolling upgrades and existing installs) |
| 2026-11-03 | No margin when an audit position counter is re-seeded | B5 recommended one | A margin is a chain gap that verification grades BROKEN; re-seeds are counted instead |
| 2026-11-02 | Analytics read a projection (`approval_decisions`) fed by the decision event and rebuildable from the ledger | GDPR redaction empties ledger output; cancellation rows lack agents; no index on `output.*`; overflow drops | Aggregating the ledger directly; maintained `$inc` counters (not idempotent under retry/backfill) |
| 2026-11-02 | Summaries aggregate in Java over a bounded, indexed query (50k rows, `truncated` flag) | One implementation for MongoDB 6 and PostgreSQL, percentiles from fixed buckets | `$bucket` / `GROUP BY` twins per backend |
| 2026-11-02 | Re-gating is a runtime overlay with synthetic WAIT rules; promotion is a human-applied config change | Nothing ungates without a human; a suspension must never auto-execute and must not rewrite configuration | Rewriting `exempt` on demotion; an AUTO_APPROVE demotion inside ConversationHitlService |
| 2026-11-02 | No MCP write tools and no bulk sample verdict | An MCP client is often a model; bulk OK is the rubber-stamping the queue prevents | Mirroring every REST action as an MCP tool |
| 2026-11-02 | Humans execute task-force tasks after the agents' waves, one task per pause | A person inside a wave future would hold every agent's wave budget hostage; one pause per task keeps a single pendingHumanInput and one content-to-task mapping | Humans inside waves; batching several tasks into one pause |
| 2026-11-02 | targetEachPeer phases and nested groups containing humans stay refused | The resume bookmark has no (speaker, target) coordinate, and nested pauses would need parent-follows-child pause propagation across two documents | Extending ResumePoint for peer targets in this PR |
| 2026-11-02 | VERIFY rework skips HUMAN-assigned tasks | A rework would pause from inside VERIFY, and resuming VERIFY replays the whole verification pass | Reworking human tasks with a VERIFY-phase pause |
| 2026-11-02 | A HUMAN_DECIDES tie-break pauses through the I6 AWAITING_HUMAN_INPUT path, not the approval path | Approval endpoints are binary and carry no content, and any eddi-approver could then set the outcome of a vote; the I6 path already has the decider-or-admin guard, timeouts, crash recovery, CAS and the inbox | A PHASE approval with the choice in the note; letting approvers break ties |
| 2026-11-02 | GroupConversation schema v5 for PendingHumanInput.detail and RESUME_KIND_TIE_BREAK | Both are resume-consumed: an older pod resolving a tie-break would re-run the vote's last repeat | Riding v4 because the fields are additive |
| 2026-11-02 | A tie-break audit entry carries no decisionOutcome | It is a human turn, not an approval; the approval-analytics contract skips null outcomes, and new outcome values would widen that contract | DECIDED / TIMED_OUT outcome values |
| 2026-11-02 | A team's monthly spend is charged from the root discussion's cost-ledger delta; the root group pays and a nested child charges nothing | The ledger already holds member, nested and group-system spend and records by replacement, so a delta cannot double-count; the per-call spend feed has no root and cannot express the in-run ceiling | Per-call `ISpendSink` events (attribute nested children to the child group, tenant-accepted charges only); reading the tenant counter (stays 0 with quotas disabled) |
| 2026-11-02 | Gates fail closed on an unreadable team budget, metering never fails work | A budget nobody can read is not a budget with room; failing open would drop every cap during an outage | Fail-open at the gate |
| 2026-11-02 | The ceiling a running discussion obeys is expressed in its own `totalCost` and excludes its boundary | Lets the existing ceiling checks enforce it unchanged; equality exhausts, like the tenant | A parallel check in every phase engine |
| 2026-11-02 | Tool-call arguments are screened before the approval gate, and again block-only after approval; `disclosure` is a guardrail type, not a transform; result rules deferred | A call certain to be refused must not reach a human, and an approver must see what will run; approval binds to a request, so nothing may be rewritten after it | Screening after the gate (approvers see unredacted text, approvals spent on refused calls); rewriting approved calls (sends text nobody approved); a new `GuardrailAction` for labels (unknown actions block) |
| 2026-11-04 | Tool-argument schema check runs before the approval gate via a `ToolArgumentValidator` executor marker | Malformed generated-tool calls reached human approval, then failed or silently no-op'd at the API | A new map threaded through `ToolContribution`/`ToolSetup` (more plumbing for the same result); a hand-written validator (networknt already ships) |
| 2026-11-04 | setup-api sync identifies generated apicalls by an `openApiOrigin` marker set at setup; unmarked agents are refused with how to add it | Sync must not regenerate hand-written httpcalls | Heuristic adoption by targetServerUrl match (could overwrite hand-written configs) |
| 2026-11-04 | `dryRun` on setup-api is hidden from the OpenAPI schema | No addition may become callable by the Platform Operator, whose setup-api tool is generated from that schema | A sibling endpoint (the brief asked for the body field) |
| 2026-11-04 | Retry jitter only shortens the backoff (`[base × (1 − r), base]`), never lengthens it | Adding ±20% jitter as OpenClaw does | Symmetric jitter, which could push a sleep past `maxBackoffDelayMs`, the total backoff budget or the turn deadline |
| 2026-11-04 | Peer-content caps (12,000 per entry, 96,000 per list) are engine constants, not group config | Bounding peer text in group prompts | A per-group setting: it would have to be threaded through every `buildPhaseInput` caller for a safety bound nobody has needed to tune; the I9 context window is the tuning knob |
| 2026-11-04 | `headTail` became the default only for an *unset* strategy; explicit `truncate` and the fallbacks keep start-only | Making head-and-tail truncation the default | Changing `truncate` itself, which would have silently changed configs that asked for it by name |
| 2026-11-03 | Vault references in a SERVICE judge's parameters need a secret granted to all agents ("*") and are re-checked at run time | A group has no agent identity, so the deploy-time VaultGrantChecker cannot apply; an editor could otherwise aim any vault key at an endpoint of their choosing | A group-level grant model; restricting parameters to ${vars:...} |
| 2026-11-03 | `approvalContext` is rendered once at pause commit and persisted as `hitlApprovalContext`; every surface serves that text, re-redacted | The Slack card is posted once, so the Manager, REST, MCP and a later audit must show the same text the approver saw; a read-time render would drift (snippets, artifacts, republished config) and would need the whole multi-MB conversation memory on every poll | Rendering at read time on each surface; rendering only for Slack |
| 2026-11-03 | Showing the context in a Slack channel needs the integration's own opt-in `hitlApprovalContext=true`, on top of the designer's `approvalContext` | The channel audience (everyone who can read it) is wider than the approver role the Manager, REST and MCP surfaces are gated to | Designer opt-in only; always on |
| 2026-11-03 | Slack values are `plain_text` sections; a card that carries context is posted with link and media unfurling off | The text is conversation data a model may have written: plain text cannot form a mention, a disguised link or bold spoofing, and a model-chosen URL must not make Slack fetch it | Escaping mrkdwn (`SlackMrkdwn.escape` leaves `@here` and bold untouched) |
| 2026-11-03 | A render failure becomes an item with an `error`; it never throws into the pause commit | A broken template must not strand a pause or hide the approval from the person who has to decide | Failing the pause; dropping the block silently |
| 2026-11-03 | A lease is trusted for ttl − 2×heartbeat after the last acknowledged renewal, counted from its send time; past that isLost() is true and callbacks fire | A holder cut off from NATS ran on past the TTL (A1) | Keeping the lease until a conflict is seen (the old behaviour); counting from the reply's arrival (the server's TTL starts earlier) |
| 2026-11-03 | Lease values carry a per-process lease id; conflicts are re-read and adopted when the id matches | Applied-but-timed-out writes read as takeovers (A2) | Matching on node+boot only — a second lease of the same process under the same key could be mistaken for this one |
| 2026-11-03 | Release notifications are addressed to the nodes that said they wait; no broadcast for plain releases | Every node received every release (D7) | Keeping the broadcast during the upgrade — old waiters poll instead, a hint is never needed for exclusion |
| 2026-11-02 | A deployment price catalog completes config prices; EDDI ships reference prices that are never active until an admin adopts them | Cache, thinking and unpriced spend were invisible to budgets; `TokenPricing` said "deliberately no built-in provider price table" | Active shipped defaults (stale silently, upgrades change charges); per-tenant catalogs (EDDI meters one tenant) |
| 2026-11-02 | Unknown cache prices are charged at the input price | Anthropic/Bedrock cache tokens were $0 | Charging them $0 until priced (under-counts a hard budget) |
| 2026-11-02 | The SERVICE judge calls SummarizationService.summarizeWithUsage, not GuardrailModelClient | GuardrailModelClient is package-private and bound to an LLM task's GuardrailContext/PaidCallGate, which a group does not have; the group summarizers already price and book through SummarizationService + TokenPricing + GroupCostLedger | Reusing the guardrail client; a new model client |
| 2026-11-02 | The judge's parameters accept vault/vars references only for credential keys, and refuse ${connection:...} | A group config must not become a plaintext key store, and ChatModelRegistry rejects connection references for model parameters at call time | Accepting literals with a warning; resolving connections for the judge |
| 2026-11-02 | One `hitl.approval` entry per decision event with a `units[]` list (answer, tool call, phase, task) and `schema: 2`; legacy fields kept | Analytics needs per-unit outcomes, categories and edit sizes; existing readers need the old fields | One entry per unit (breaks the one-entry-per-decision shape, multiplies ledger sequence numbers); a separate store written alongside the ledger |
| 2026-11-02 | An approved edit of a rule-pause answer replaces only the draft's own output item and data keys; nothing re-runs; a guardrail-refused edit re-pauses on the unchanged draft | Re-running later tasks would re-trigger the pause rule; a refused edit must not consume the human's pause | Truncating and re-running the rest of the turn (as REVISE does); screening before the CAS (model calls on the request thread and inside Slack's 3-second window) |
| 2026-11-02 | A group SYNTHESIS edit is appended as an unsigned reviewer entry (`hitl:<decidedBy>`, `errorReason: reviewer-edit`), reusing the SYNTHESIS type | Signed transcript entries must never be rewritten; the existing "last synthesis" extraction then needs no change and older pods deserialize it | A new transcript entry type; rewriting the signed entry in place |
| 2026-11-02 | Slack edits of tool arguments are merged server-side against the raw arguments; redacted values cannot be edited from Slack | Slack only ever sees redacted arguments; a value left as shown must keep its raw secret, and a marker must never become an argument | Sending raw arguments to Slack; refusing every edit of a call that has any redacted value |
| 2026-11-02 | `HitlDecisionRecorded` is fired with `fireAsync` through `Arc.container()` from the plain audit writer | Nothing a consumer does may run on the decision's critical path; the HITL collaborators are plain classes built in their facades' constructors | A synchronous event; new CDI constructor parameters on the facades |
| 2026-11-02 | Cross-team handoff requires two-sided consent (source `targets` and target `accept.from`), enforced in `TeamHandoffService`, not by prompt | A one-sided design lets an author with EDIT on any agent write into any team's backlog | Source-only allow-list; wildcard accept rules (explicit ids keep consent auditable) |
| 2026-11-02 | Handoff provenance is a side map on the workspace (`handoffs.origins`), not a `TaskItem` component | The cadence writeback rebuilds tasks with the shorter constructors and already drops `createdByAgentId`; a component would also touch ~30 call sites | Adding `origin` to the `TaskItem` record |
| 2026-11-02 | The default accept mode is REVIEW (an intake inbox no cadence can pull) | The handed-over text crosses a trust boundary and will be read by the receiving team's models | AUTO by default |
| 2026-11-02 | The approval source for the tool is a new `team`, not `builtin` | `exempt: ["builtin:*"]` would silently un-gate cross-team writes | Reusing `builtin` |
| 2026-11-02 | `toolQuotas` reserve inside `ToolExecutionService.executeToolWrapped` (after cache, before execution), release on failure, keep on timeout | A quota that counts cache hits or refused calls, or that cannot give a failed call back, charges users for nothing; a timed-out call may still have completed its side effect | Reserving in `ToolLoopRunner` around the supplier (cannot see the cache; the refusal would be cached and charged); counting at result time (overshoots under concurrency) |
| 2026-11-01 | Late agent-test charges adjust the stored terminal record (compare-and-swap on `heartbeatAt`) | A timed-out run would otherwise wait up to the turn timeout plus the write-back wait before it could report | Holding finalisation for abandoned turns |
| 2026-11-01 | Dead-letter forwarding is fenced by a shared erasure tombstone, checked before and after publishing | The snapshot outlives the shared purge, so "does the conversation exist" cannot see an erasure under way, and one check still races the purge | Relying on the existence check alone |
| 2026-10-27 | docs.labs.ai redirects to docs.eddi.technology with an explicit 301 (not Vercel's default 308), on every path, kept forever | Domain decision for the GitBook replacement; released images print docs.labs.ai links indefinitely | `permanent: true` (308) — equally permanent, but not the decided code |
| 2026-10-27 | The prompt and response are removed from the stored step once the ledger accepted the turn's entries; tool calls, usage, cost, model name and guardrail records stay | The ledger is the system of record and holds the full text; the copy was a fifth of the document. Agent tests, cost metering and history replay read the small keys back from the stored step | A config switch (no consumer needs the text in memory); a hash/reference in place of the text (the ledger is addressable by conversation id and step index already); stripping `audit:tool_calls` too (agent tests read it from the stored step) |
| 2026-10-27 | Current-step and approval-status reads load a server-side projection (last step/output + computed totals) instead of the whole conversation document | Whole-document reads ran at ~100 KB/s on a bandwidth-limited database link; 0.5–3 MB documents made these reads take 11–29 s | Caching the snapshot (stale across nodes, does not help the first read); a `$slice` find projection without totals (undoAvailable, output/step pairing and conversationCost need the full lengths and every step's spend) |
| 2026-10-26 | Due resubmission rows of a deleted integration are left to ledger retention, not refused by the sweeper | Once the query is scoped to one integration they block nobody; a sweeper pass over all RECEIVED rows would again read one global batch | Refusing them in the inbound sweep; deleting an integration's ledger rows with it (the rows are GDPR-reachable history) |
| 2026-10-25 | A conversation-mode reply's recipient is decided in one function, from the ledger row cross-checked with the mail's step in conversation memory and the conversation's identity, on every attempt | Authentication is a fact of the inbound mail; the processor forces it on, and the reply path checks it again from two independent records, so a reply to an unauthenticated or anonymous sender cannot be produced even for a turn that should not have run | An authenticated flag on the ledger row (a new column on both stores for a fact the step already holds); trusting the processor's check alone |
| 2026-10-25 | Replies are rebuilt from the conversation on retry; group replies, whose discussion keeps no copy of the mail, are retried only by the node that holds them | The ledger stores no body or subject (plan §12); a group conversation has no field for the answered mail's subject | Storing the subject in the ledger; an extra copy of the mail in the group conversation |
| 2026-10-25 | A mail whose conversation was started but whose turn never ran is found again in the mailbox by Message-ID and run once | The raw mail is not kept, and the mailbox still has it (flagged or moved); the hand-back is a conditional transition and the turn carries the mail's ledger key | Keeping the rendered input (a body in storage); marking it lost (a mail silently not processed) |
| 2026-10-25 | The email suppression list stores a SHA-256 of the address per integration, and GDPR erasure deletes the entry | Enough to answer "suppressed?" and to clear it for someone who knows the address; conversation mode only answers mail the person sends, so an erased person is written to again only if they write first | Plain addresses (a list of correspondents); keeping a hash after erasure (plan §23 Q4 — keeps the identifier the request asked to remove) |
| 2026-10-25 | Suppressions are cleared through two channel REST endpoints, not an MCP tool or an operator grant | An administrator needs a way to undo a bounce without database access; the operator holds no channel endpoints by design | Store-only clearing (database access); an MCP tool (one more admin surface to secure) |
| 2026-10-25 | Email trigger mode never runs a mail twice once a side effect may have happened; a claim that did nothing is given back and the mail, still unmarked in the mailbox, runs again | The CLAIMED state separates "nothing left EDDI" from the point of no return; only a crash while starting the conversation is ambiguous, and that one is REFUSED with an owner notice | Refusing every stranded claim (lost mail that had run nothing); re-running submitted turns (double side effects); a sweep that opens its own IMAP session (two nodes on one mailbox) |
| 2026-10-25 | The thread mapping key of a correlated email conversation includes the sender's address | Message-IDs travel in every forward; under identityMode integration all senders share a user id, so a thread-only key lets anyone join any thread | An explicit sender field checked after lookup (one more stored fact and a check that can be forgotten); correlating by user id only (unsafe in integration mode) |
| 2026-10-25 | Unauthenticated email senders get a one-off email-anon id with no user-memory store and no memory tool, enforced in ConversationService and ContextualToolsProvider | Their From is a claim anyone can make; memory written under it would be read by the next forger | A shared anonymous id (one sender's injection persists for all); relying on the random id never recurring (still writes orphaned memories) |
| 2026-10-25 | Mail-server outages back off inside the poller and are COMPLETED fires with the error in the fire log | The schedule retry ladder dead-letters after a few minutes, which would silently stop a mailbox after a short outage | FAILED fires (dead-letter); SKIPPED fires (no error recorded) |
| 2026-10-25 | Polling state lives in a new IEmailMailboxStateStore (Mongo + Postgres), not the ledger or the cluster KV | The cursor is one value per mailbox that must outlive every ledger row's 30-day retention; the KV buckets are node-local and volatile in single-node mode | A cursor row in the ledger (expires, mixes per-message and per-mailbox facts); NATS KV (lost on restart) |
| 2026-10-25 | Save-time warnings travel as stable codes in an X-Eddi-Warnings response header | The channel save responses carry no body to extend, and codes let the Manager translate them in 11 locales | A response body (changes the create/update contract); log-only warnings (invisible to the author) |
| 2026-10-25 | Mail in the folder before an integration's first poll is never processed | Connecting an integration to a full mailbox must not make an agent work through years of mail | Processing everything unread (cost and side effects nobody expected); a startFrom key (more surface, deferred until asked for) |
| 2026-10-25 | A validly signed message with a second From, or a duplicated Subject/Date/Message-ID/In-Reply-To/References, is FAIL, not ALIGNED_PASS | Bottom-up selection hashes only one instance; the other is unsigned attacker text that clients may display | Trusting the signature alone; downgrading to PASS_UNALIGNED (would hide that the message is malformed) |
| 2026-10-25 | DKIM l= is refused as FAIL rather than honoured | It lets anyone append unsigned text that EDDI would read as the sender's | Honouring l= and ignoring the extra body (EDDI's parser would still read it) |
| 2026-10-25 | Signatures under a t=y key make a message NONE, not FAIL | RFC 6376 §3.6.1: testing-mode mail must be treated as unsigned | FAIL (would misreport a sender who is merely testing) |
| 2026-10-25 | A temporary DMARC lookup failure assumes strict alignment and flags the result temporary | Strict still authenticates an exact-domain signature, and never over-authenticates a subdomain one | Relaxed (could authenticate what the domain's policy forbids); FAIL (punishes the common exact-domain case for a DNS blip) |
| 2026-10-25 | dnsjava, not JNDI's DNS provider | JNDI gives no TTLs, no rcode, no control of the server per lookup and no TCP fallback control; dnsjava is BSD-3-Clause with one runtime dependency | JNDI (com.sun.jndi.dns); a hand-written DNS client |
| 2026-10-24 | Self-continuation is delivered as a one-shot schedule on the cluster-aware schedule machinery, not through the conversation coordinator's queue | E8 needs a continuation that survives a node crash, runs once under the conversation lease, and lands in dead letters when lost | Coordinator queue (in memory, lost on a crash); a custom scheduler (AGENTS.md forbids it) |
| 2026-10-24 | `maxContinuations` counts per conversation (steps carrying the engine-written `continuation:number`), not per chain | A counter the author or a property setter can reset is no bound; a job that needs more starts a new conversation | A conversation property as counter (writable by config); per-chain counts reset by user input |
| 2026-10-26 | Docs move off GitBook to an Astro + Starlight build of `docs/` on Vercel at docs.eddi.technology; docs.labs.ai redirects permanently; flat URLs from file names | GitBook URLs carry the SUMMARY section and break when a page moves; the backend's flat docs links 404 there; the marketing site already moved to eddi.technology on Vercel | Keeping docs.labs.ai canonical (docs stay on the old brand domain); folding the docs into the website repo (plain Astro with 11 locales, and docs PRs could not be validated before merge); GitHub Pages (no real redirects) |
| 2026-10-26 | DNS rebinding is closed per client: Vert.x checks the connected peer before sending, OutboundHttp re-checks after the exchange, MCP re-validates per request | Found by a repository security scan; previously an accepted risk | A JVM-wide InetAddressResolverProvider (affects every lookup in the process, loaded before app classes in Quarkus); IP-literal URLs with a Host header (breaks SNI and certificate checks) |
| 2026-10-25 | HITL notification recipients are bounded by an operator domain allowlist, defaulting to the sender's own domain | Agent authors choose recipients but the mail goes out under the operator's sender and relay, with author- and user-influenced text; an unbounded list makes the relay an open mailer | Unbounded recipients (open mailer); a per-agent allowlist (set by the same author it is meant to bound) |
| 2026-10-24 | Proposed (not approved): make → judge → repair extends the shipped output guardrails with a `judge` type (closed-vocabulary issue codes) and `onExhausted: deliver-best`, rather than a new loop (planning/evaluated-generation-plan.md) | Analysis of hosting a multi-stage video-generation pipeline found seven existing loops; #1064/#1112 made output guardrails the natural home | Checks in responseValidation, a classifier issues mode, free-text judge feedback, loops in LifecycleManager, a refine ILifecycleTask, groups for the per-item loop, a workflow engine |
| 2026-10-24 | HITL email notifications go to configured recipients only, not to "every approver" | EDDI's approvers are role holders in the identity provider, which EDDI cannot enumerate; a list built from past sign-ins would keep mailing a revoked approver indefinitely | Recording roles in the user directory at sign-in (stale and unrevokable); mailing the conversation owner (usually the end user, not an approver) |
| 2026-10-24 | The audit entry of a notification records the recipient as SHA-256 + domain, not the address | The audit ledger is write-once and erasure only pseudonymises its userId, so a plain address would outlive an erasure; the hash still answers "was this address mailed?" for someone who has it | Plain address (unerasable personal data); no recipient at all (unauditable) |
| 2026-10-24 | Operator-configured relays get their own socket policy instead of a bypass | An on-premises relay on a private address or port 25 is a normal deployment, but the pinning, metadata refusal and TLS verification are still worth having; a bypass would lose them | Allowing private addresses through eddi.email.allowed-hosts (would open them to channel authors too); opening a raw socket outside SafeSocketConnector |
| 2026-10-24 | Hidden-content pass in two modes: conservative for pages (comments, 1px, transparent, explicitly same-coloured text), strict for mail | Pages hide accordions, tabs and "read more" content with display:none/hidden/opacity until script reveals it — content RAG wants; mail runs no script, so anything hidden there is only for a machine | One strict rule set for every caller (dropped FAQ answers and tab panels from crawled pages); opt-in only for mail (leaves RAG without the white-on-white and zero-size defences) |
| 2026-10-24 | Style-block hiding rules over-approximate instead of being capped | A rule cap is a bypass: a display:none rule placed after the cap would leave the hidden text in | Truncating the style text or the rule count; exact selector matching per rule (one document search per rule, quadratic) |
| 2026-10-24 | A forward (Fwd:/FW:/WG:/TR: subject, or a forward separator) is never quote-stripped | Outlook writes the same From/Sent block for forwards as for replies, and a forwarded invoice is the content of a trigger-mode mail | Stripping by header block alone (deleted the forwarded mail) |
| 2026-10-24 | httpcall `forEach` is a new field, not `batchRequests` made to collect | Old configs with `batchRequests` on a waiting call send one request; reusing the field would silently send N | Extending `batchRequests`; dropping the fire-and-forget restriction |
| 2026-10-24 | `forEach.maxConcurrency` defaults to 1 | Sequential sends the same load as the calls written one after another; parallel is opt-in for APIs that allow it | A small default such as 4 |
| 2026-10-24 | A `forEach` item that threw (timeout) is never retried | The request may have been processed and billed | Retrying timeouts with the response retry policy |
| 2026-10-24 | Single-agent revise is `hitlConfig.onRejection: REVISE`, RULE pauses only, the frozen request kept as step data | Plan decision 9.5; a rejected tool call already reaches the model with the note | A new verdict value on the approval API; rebuilding the request from memory at resume (loses retrieved context and tool results) |
| 2026-10-24 | `maxReworks` runs the rework inside the VERIFY phase | The phase list stays linear and resume bookmarks untouched | Jumping the phase loop back to EXECUTE |
| 2026-10-24 | A guardrail-refused message is withheld from every later model-facing history by a step flag written at block time (`llm:input_withheld:<taskId>`), not by re-screening | The classifier does not re-screen history, so a message it blocked reached the model on the next turn | Making the classifier screen history (cost multiplies by the window); storing the redacted text in memory (size, and a classifier redaction is the block message anyway) |
| 2026-10-24 | `postResponse` runs after an input block, like every other fallback | An input-blocked `convertToObject` + `addToOutput: false` task answered nothing | Correcting the docs instead — that left the silent empty answer |
| 2026-10-24 | Webhook dedup refuses (503) per integration (own budget, fair share from half capacity, hard stop at 90%) rather than evicting; shared via the DEDUP bucket in a cluster | The dedup cache is the only replay protection of the github / generic-hmac schemes, and it sat on the 1,000-entry default shared by all integrations | A configurable size property (could be set below the traffic); fail-closed on any eviction (Caffeine cannot report one) |
| 2026-10-26 | A rejection note from a person asks the model to revise; automated `system:` decisions keep the "declined" text | The note is the user's way to say "not like this"; the model should come back with a new proposal to approve. Automated notes are explanations, and the group auto-reject would otherwise be retried in a loop. | Matching on the note text; a new verdict type that means "revise" |
| 2026-10-19 | The REST turn timeout counts from the request's arrival (first route of the router), and a turn still queued when its caller gave up is dropped unrun | Under a burst the worker-pool wait before the timeout was armed reached 79 s, and turns nobody waited for spent the model and the database | A default `turnDeadlineMs` for every agent; arming the timeout on a Vert.x timer at arrival, which races the resource method's own response |
| 2026-10-19 | `say`/`rerun` run on virtual threads, conversation starts stay on the worker pool | Starts on virtual threads collapsed a 2-CPU node (event loops starved); the pool is their only concurrency bound | All agent endpoints on virtual threads; a new start-concurrency limit (follow-up) |
| 2026-10-19 | `/q/metrics` is served by an EDDI route on two dedicated threads, ahead of Quarkus' blocking handler | The blocking route shares the worker pool and went blind for two minutes in every overloaded run | The Quarkus management interface, whose blocking routes use the same pool |
| 2026-10-15 | Webhook channel starts the conversation and admits the turn on the request thread | 202 must carry the conversation id and quota refusals must be a 429 | A separate executor like Slack's (429 unreportable), a non-consuming quota pre-check (none exists; racy) |
| 2026-10-15 | Self-hosted OpenAI-compatible servers get their own type, `openai-compatible`, with author-declared capability flags (jsonMode, jsonModeWithTools, vision; all off by default) instead of catalog rows | `type: openai` + `baseUrl` inherited OpenAI's JSON-with-tools and vision rules; the catalog validator requires a fixed https URL and model | Adding self-hosted rows to `openai-compatible-providers.json`; a new `extraBody` key next to `customParameters` |
| 2026-10-17 | GDPR database-log pseudonym is keyed (ledger key, else a persisted per-deployment salt); old unsalted pseudonyms are not migrated, and ledger step 7b keeps the unsalted token | L9: unsalted SHA-256 of an e-mail id is dictionary-reversible | Re-keying old rows (raw id is gone); keying step 7b too (breaks v3/v4 signatures) |
| 2026-10-17 | A secret create under grant-enforcement=enforce requires allowedHosts; ["*"] is the explicit opt-out, [] is refused | allowedAgents limits which agent references a secret, not where it is sent; every editor can choose the destination (security review 2026-10-17, H1) | A [SECURITY] warning only, which keeps scripts working but leaves the default open; requiring hosts in every mode, which overrides the warn/off opt-out |
| 2026-10-15 | LLM input/output guardrails use an EDDI-native `ContentGuardrail` SPI, not `dev.langchain4j.guardrail` | WS4/R0 implementation against langchain4j 1.22.0 | lc4j `InputGuardrail`/`OutputGuardrail` + `EddiChatExecutor` (mandatory executor = unbudgeted model-call path; sealed results; mandatory builder fields) |
| 2026-10-15 | Tenant cost is metered per settled step from the completion callback, with an `audit:metered_cost` marker; LLM tasks get a soft pre-call gate (fallback answer) | `recordCost` had no caller; the tool-call gate alone never stopped a tool-less agent | Metering per LLM task (double counts — `audit:cost` is already a per-step total); metering in `GroupCostLedger` (member turns already settle through `say`); failing the turn on refusal (the tool gate is soft; a deterministic reply is friendlier) |
| 2026-10-15 | A listing above the page ceiling is refused with 400, not clamped | A clamp returns a short page a client mistakes for the whole; a refusal tells it to page. The ceiling (1000) is the Manager's largest page — it resolves agent names and dashboard counts from one page of 1000, and 200 broke both | Silent clamping, as the conversation listing did at 100; a 200 ceiling with the Manager paging |
| 2026-10-15 | An agent with any `scope: secret` instruction never has its turn input dead-lettered | The capture runs before the pipeline, so the scrub cannot help, and a client cannot be relied on to flag every secret turn; losing replayability for those agents' failed turns is the lesser cost | Per-turn detection of secret-shaped input (the redaction filter still runs on everything captured) |
| 2026-10-15 | An approver-only caller may cancel only an `AWAITING_HUMAN` conversation | The role exists to decide pauses; a cancel of an executing turn is the owner's or an admin's to make | A separate cancel permission |
| 2026-10-15 | The Chat UI's `?token=` is an operator opt-in, `eddi.chat.url-token.enabled`, default off; served to the widget as a script global before the bundle | Security review M6: a token in the URL is login CSRF and is logged by the first request | Keeping it on and documenting the risk; a fetched JSON config, which would race the `?token=` read |
| 2026-10-15 | All three CSP filters use negative Vert.x orders so they run before the `/q` sub-router mount | Security review L11: at order 20 the Swagger filter never ran, and Quarkus passes the order to Vert.x unchanged, ascending | Moving Swagger UI off the sub-router, or a Java `Filters` observer — both heavier than three numbers |
| 2026-10-15 | `quarkus.oidc.resource-metadata.force-https-scheme` defaults to `false`; the advertised MCP resource scheme follows the request | Security review L3: a plain-http instance with auth on advertised an https identifier nothing served | Keeping `true` (wrong on every plain-http instance); deriving it from `quarkus.http.proxy.*` (no config signal separates plain http from a TLS proxy without forwarded headers) |
| 2026-10-15 | `k8s/quickstart.yaml` keeps all three `*_ALLOW_UNAUTHENTICATED` flags `true` behind a loud banner | Review L4 asked for the MCP/secret-store opt-outs to be `false` like Helm | Setting them `false` with OIDC off: `HighValueSurfaceGuard` refuses to boot, so the quickstart would crash-loop; Helm's `false` is paired with a render-time refusal, which a static manifest cannot express |
| 2026-10-15 | `docker-compose.yml` enforces "no non-loopback bind while unauthenticated" with a one-shot `bind-guard` service on the `mongo` image | Review L4; the contract was only a comment | A busybox/alpine init image (extra pull, breaks offline); an entrypoint wrapper on `eddi` (intrusive); installer-only check (does not cover `docker compose up` by hand) |
| 2026-10-15 | `mongodb.existingSecret` requires `eddi.existingSecret` | The chart builds `mongodb.connectionString` from `mongodb.rootPassword` | Reading the password back out of the operator's Secret (`lookup` is empty under `helm template` and dry runs, so the render would differ from the install) |
| 2026-10-15 | Tool cost ceilings enforced by default (`eddi.tools.budget.enforce-by-default=true`); `enforceBudget: false` is the named opt-out | Security review M8: every stored ceiling was advisory because the opt-in key was undeclared, and the $0.00 built-in pricing that justified the opt-in was fixed releases ago | Keep the opt-in and only document it |
| 2026-10-15 | Remote MCP/A2A tool parameter schemas are governed (text redaction, per-tool and per-server byte caps; descriptions stripped before a tool is dropped) | Security review M7: schemas are prompt content authored by the remote party, and a partial schema would make the model call the tool wrongly | Govern descriptions only; truncate the serialised schema mid-document |
| 2026-10-15 | `eddi.self.base-url` is the one exemption from `eddi.security.ssrf-protection.enabled` for configured endpoints (LLM `baseUrl`, embedding endpoints, spec locations) | It is deployment configuration set by the operator, not agent configuration, and an agent that talks to EDDI's own `/v1` API is a supported arrangement; without it, turning the switch on would break that on every deployment, since the self address is loopback or private by construction. Bare database hosts get no exemption: EDDI's listener is never a database. |
| 2026-10-15 | Templated httpCall hosts are refused while eddi.security.ssrf-protection.enabled=false; allowed-hosts list added | A conversation-controlled host reached loopback and private APIs | Defaulting protection to true (breaks the localhost reference agent and every configured internal API) |
| 2026-10-15 | Only eddi.caller-identity.author-roles (default eddi-admin) may store a config containing ${caller:token} | An editor-authored call spent the chatting admin's token on EDDI's admin API | Disabling self-release by default (breaks the Platform Operator); HITL-gating every caller-token call (a prompt per tool call) |
| 2026-10-13 | Model-version rules live in a data catalog consulted when a model is built, rejected parameters are dropped with a WARN (switchable) | Exposing 30 more parameters per provider without knowing which model accepts them would make agents easier to break; dropping a certain-4xx parameter keeps the agent answering | Fail the build of the model; only document the rules |
| 2026-10-13 | No catalog rule without an official source; unknown models fail open | A wrong rule silently removes a working setting, a missing rule only costs a visible vendor error | Infer rules from model-name prefixes |
| 2026-10-12 | A2A streaming uses the agent-qualified `sayStreaming`, like A2A's non-streaming `say()` | #1008 fixed the #949/#953 compile break by routing A2A streaming through the conversation-id overload and its ownership check | Keeping #1008's routing (A2A streaming and non-streaming would apply different access rules) |
| 2026-10-12 | In-memory vector stores live in their own never-evicting registry keyed by knowledge base; an ingestion run re-embeds everything when the store's epoch differs from the one recorded with the source's state | In-memory KBs emptied after 30 idle minutes or any secret rotation, and unchanged content hashes stopped scheduled runs from refilling them | Keeping them in the client cache with a longer TTL; a per-document epoch column (wider schema change for the same effect); evicting the largest KB when a memory cap is hit |
| 2026-10-07 | Bind `POST /agentstore/agents/{id}/cascade` for the operator while `PUT /agentstore/agents/{id}` stays excluded | Multi-step edits cost one approval per step (five for a knowledge-base binding); dependent steps cannot be pre-approved because approval binds to the resolved request | Plan-level / template approval (explicitly out of scope); a looser same-id-only rule on mappings (would forbid swapping to a different config of the same type); admin-only on the REST endpoint (stricter than every write it composes, and would 403 an editor-operator that holds every other write) |
| 2026-10-07 | Operator edits LLM, apicall and mcpcall configs with RFC 6902 PATCH; gate-guard reads patch operations and refuses copy/move | The operator's llmstore PUT could never work for a config holding a ${vault:…} reference: ConfigReferenceGuard refuses the echo, and an allowed echo would have stored the plaintext key | Relaxing ConfigReferenceGuard with an "unchanged from the stored document" exemption (the guard does not know the target, and the read-back is conversation data); a server-side toolApprovals restriction (cannot distinguish the operator from an admin) |
| 2026-10-07 | Operations whose required input the generator cannot send are skipped and reported, not generated half-working | An approved `updateResourceUri` call could only ever fail, and the model could not say why | Generating them and warning (the tool still exists for the model to call); failing the whole setup (one unusable endpoint would block every other tool) |
| 2026-10-07 | A gated call whose request the credential-reference guard refuses is refused before the pause, not approved unpinned | An admin approved a setupAgent call that was refused the instant they approved it | Adding an "allowed if unchanged from the stored document" exemption to the guard; weakening the guard; an approval-card warning flag (dead on every call the operator can make) |
| 2026-10-06 | A deploy refused by the vault-grant gate returns 200 + `failure`, not 409 | Handoff preferred 409 so callers notice a grant refusal | 409: every other failed waited deploy is a 200 with the outcome in the body; one outcome would get two shapes across 4 in-process callers, the Manager and quarkus-eddi |
| 2026-10-06 | One-agent grant append is a compare-and-set loop over the existing guarded write, not a new persistence method | Two admins granting two new agents at once lost one grant with the replacing PUT | A new atomic `$addToSet` per store (Mongo + Postgres twins) for the same guarantee |
| 2026-10-04 | Cluster console: read-only insights for eddi-viewer, content and actions for eddi-admin | Ids, counts and timings help a read-only on-call role; a dead letter's input is what a user typed, and every action changes the live cluster |
| 2026-10-04 | No purge-all, no stream/bucket delete, no lease hand-over in the console | Each could lose data or bypass fencing; per-item discard and the scripted DELETE endpoint remain |
| 2026-10-04 | Activity timeline in its own JetStream stream rather than node-local rings | Behind a round-robin load balancer a node-local timeline changes on every refresh |
| 2026-10-04 | Operator gets cluster reads but no recovery action | Actions act on the live cluster, not on a reviewable document; not in operator-write-scope-plan's write set |
| 2026-10-13 | The Kubernetes copy of the Full Metrics Reference ships as two linked dashboards | Keeps plain client-side `kubectl apply -k` working; the source dashboard is unchanged | Requiring `kubectl apply --server-side` (changes every operator's apply command); raising the test's budget (removes the headroom that caught this) |
| 2026-10-06 | Agent detail edits a draft saved by an explicit Save, with Save & leave on the app's one unsaved-changes prompt | Every inline edit created an agent version and could not be undone | Keeping auto-save with an undo stack; a second navigation guard beside the router's single blocker |
| 2026-10-06 | One `LlmRecoveryLog` INFO line per recovery action, emitted by the call site that performs it | Six ad-hoc log lines had no shared shape and no conversation id; "one recovery, one line" needs a single place that formats it | Keeping each component's own log message and documenting them (not greppable as one family, no ids) |
| 2026-10-06 | A HITL resume applies onError, responseValidation and the final-answer re-ask, but not the breaker, cascade, truncation/context re-asks or a deadline | The resume pins the paused model and must not replay tools; the breaker and cascade decisions were made before the pause | Re-running the cascade on resume (would re-enter the tool loop and replay approved tools) |
| 2026-10-03 | HTTP latency panels chart mean and peak; http.server.requests publishes no histogram | The P50/P95/P99 panels queried _bucket series nobody publishes | A percentile histogram on every uri-tagged series (cardinality x ~70), and SLO buckets (still x ~10) |
| 2026-10-03 | eddi_vault_errors_count_total no longer counts "no such key" | Agent signing probes a missing key every turn, so the counter could not be alerted on | Tagging the counter by kind (a second tag shape on an existing meter is dropped by the registry) |
| 2026-10-06 | R8 circuit breaker is a new per-model class with half-open single-probe, not a generalisation of the MCP/A2A breakers | Those are private consecutive-failure counters with no half-open state or per-class window; an LLM model needs N-of-M by class and one concurrent probe | Rewriting both existing breakers onto a shared utility in the same change (behaviour-visible for MCP/A2A) |
| 2026-10-06 | R5 recovery order: repair, same-model re-ask, then escalate | A format slip is usually fixed by the model that made it; escalating first pays a pricier model per hiccup | Escalating to the next cascade step first (pays a different, pricier model for every one-off format slip) |
| 2026-10-04 | Manager imports send `X-EDDI-Space` | 6.6 scopes merge-import matching to the importing space; the raw-fetch import calls never sent the selected space | Leaving placement to the default space (merge would silently target a different copy than the one on screen) |
| 2026-10-04 | Operator allow-list not extended for `/auditstore/admin-actions` in the UI PR | `findMissingEndpoints` refuses activation when the spec lacks an allow-listed endpoint | Duplicating #952's allow-list line (would break activation on main until #952 merged) |
| 2026-10-04 | Manager uses a data router with one catch-all route wrapping `<App />` | `useBlocker` needs a data router; converting every route would churn the table and every `MemoryRouter` test | Route objects per page |
| 2026-10-03 | A2A pinned to 1.0 (spec v1.0.1), with 0.3 and pre-0.2 tasks/send still served on the same endpoint; dialect chosen by method name | The pre-0.2 dialect was unreadable to every current SDK, and 1.0 renamed every method and enum | Serving only 1.0 (breaks 0.3 peers and every older EDDI client); a separate endpoint per version (a card can name one URL per interface, and the method names never collide) |
| 2026-10-03 | Server-issued A2A task ids embed the conversation id; turns carry the task id in the input context | tasks/get had to survive a restart without redesigning the task store, which the NATS cluster work owns | Storing tasks in a new collection (a parallel store P11 would have to replace); trusting the node-local cache (loses every task on restart) |
| 2026-10-03 | /chat img-src appends eddi.chat.img-sources, default https: | Designer image output items on other hosts were blocked by the widget's own CSP | Default empty (feature broken out of the box); Manager-wide img-src widening |
| 2026-10-03 | Chat token hand-off: ready/token-request/token protocol with a 401 retry hook; allow-list stays ?tokenOrigin= (exact origins) under the operator's frame-ancestors | The widget's first request beat the host's token, was refused and never retried | A backend-served origin list (new endpoint for a value frame-ancestors already enforces); delaying start on a fixed timer |
| 2026-10-03 | GDPR erasure redacts audit content and re-signs only rows that verified; rows and sequences are kept | Erasure left verbatim PII in audit input/output/llmDetail/toolCalls and reported complete:true | A v6 canonical form over content digests (redactable without the key); deleting rows (breaks the chain); pseudonymising only (the old behaviour, kept as eddi.audit.erasure-mode=pseudonymize) |
| 2026-10-03 | Administrative REST actions are audited by excluding the data plane, not by listing admin paths | "Every API call is audited" was false; no actor was on record for config, deploy, vault, backup or GDPR actions | An allow-list of admin paths, which silently misses the next endpoint; auditing reads (volume, and the proxy access log already has them) |
| 2026-10-03 | Dream deletes/overwrites are compare-and-set on updatedAt; on a miss the user's newer write wins | Dream deleted or overwrote facts written during its LLM call | A version counter field (schema change on both backends for the same guarantee) |
| 2026-10-03 | A group member may hold one seat; repeats are refused at save time and dropped on read for stored configs | Duplicate members shared one member conversation (review top issue #12) | One conversation per seat (needs a seat id through every agentId-keyed group structure) |
| 2026-10-03 | Generic sealed data is AAD-bound to a caller context with an unbound-read fallback; a DEK rotation re-seals bound | `seal()` bound nothing, so a grant token copied between rows opened | Forced re-encryption migration; binding without fallback (would strand every existing grant) |
| 2026-10-03 | /v1 requests without a chat id are keyed by a hash of their first system + first user message (`chat-key-fallback=history`), with `shared` as the opt-back | One `:default` conversation per user/agent shared context across unrelated chats and grew without bound | Always-new conversation per request (breaks history-less clients and memory); inferring restarts from message counts; honouring a caller-supplied EDDI conversation id (needs an ownership check, deferred) |
| 2026-10-02 | EDDI's Deployment upgrades with `Recreate`; Helm `eddi.updateStrategy` can opt back into `RollingUpdate` | `maxSurge: 1` ran two EDDI JVMs against one database during every rollout, and the engine's turn lock, nonces and caches are node-local | Keep `RollingUpdate` and only document the overlap (rejected: the default would stay unsafe) |
| 2026-10-02 | Installers fetch the compose files for `latest` from the newest release tag (GitHub releases API), falling back to `main` with a warning | The `latest` image is the newest release while `main` can be ahead of it | Pin the image to the resolved tag as well (rejected: changes what `eddi update` pulls) |
| 2026-10-02 | The metrics job keeps aggregate counts only; the stargazer roster is no longer collected | A per-person record kept forever, for a question the star count answers | Keep the roster and document a retention rule (rejected: data minimisation) |
| 2026-10-02 | The two "first agent" tutorials are folded into one page (creating-your-first-agent/README.md) and the sub-pages are deleted | Both repeated the same steps with different mistakes; one tested page is cheaper to keep correct than two. The anchors other pages link to are directory links and still resolve | Correcting both pages in place (two copies of the same steps to keep in sync) |
| 2026-10-02 | Doc examples keep `labsai/eddi:6.4.0` / `EDDI_VERSION=6.4.0` although 6.5.0 is tagged | ReleaseVersionSourceTest ties every pointer to helm/eddi/Chart.yaml's appVersion, still 6.4.0 until the post-release PR lands; bump-version.py moves them all together. Superseded on 2026-10-12: the post-release PR moved the chart to 6.5.0, so the examples were bumped with it when main was merged | Writing 6.5.0 now (fails the build until the chart moves) |
| 2026-10-02 | Conversations with no owner anywhere are admin-only; the snapshot owner is the fallback | F1: a stripped descriptor opened a conversation to every token | Keeping "unowned = open" for legacy data — the pre-v5.1.6 legacy case records its owner in the snapshot, so the open rule protected nothing real |
| 2026-10-02 | The generic descriptor/sharing API answers for configuration types derived from the IRestVersionInfo stores | F1 | A deny-list of non-config types (breaks silently on the next new type); moving conversation descriptors to their own collection (a data migration on both backends for the same protection) |
| 2026-10-02 | Triggers need EDIT on their targets for create, update and delete | F2 | USE (any chat recipient could re-point); OWN (excludes team editors who legitimately maintain the routing) |
| 2026-10-02 | Metrics default to a role (eddi-admin, eddi-metrics) rather than any token | F7 | Keeping `authenticated` (a role-less token read the exposition); shipping the eddi-metrics role in the realm files (needs three realm copies and a chart bump for an opt-in scrape account) |
| 2026-10-02 | Parameter-conversion 404s become 400 with a JSON body; other 404s unchanged | JAX-RS §3.2 maps a bad query/path parameter to 404, indistinguishable from a missing resource | A global ParamConverter (does not cover built-in types) |
| 2026-10-02 | A timed-out turn stays in inFlightConversations until its body returns | the registry was the only handle /cancel and GDPR stop had on it | Interrupt-only abandonment (swallowable) |
| 2026-10-02 | HITL timeouts whose decision cannot be applied yet are re-armed at a fixed interval and never dead-lettered | A COMPLETED-and-disabled one-shot silently turned a finite policy into wait-forever | Retrying on the normal backoff ladder (dead-letters after ~21 min) |
| 2026-10-02 | Schedule fires re-check USE against a creator access snapshot (teams + admin flag) taken at creation | principalMayUse(id, principal) refuses team shares; server has no team membership without a token | Re-checking the bare principal; skipping the check |
| 2026-10-02 | Scheduler lease is raised at startup to outlast fire-timeout + grace instead of failing startup | A misconfigured scheduler should not take the platform down | Failing startup; heartbeat-renewed leases (more store surface) |
| 2026-10-02 | Conversation-bound tools are excluded from the tool cache entirely, not narrowed to the conversation scope | Their state also changes within a conversation (new uploads, peer artifacts) and several have side effects; the classification is derived from the tool class (non-CDI-bean = per-conversation) | Forcing the conversation scope (still stale within a conversation); extending the hand-kept name list |
| 2026-10-02 | EDDI's RetryConfiguration is the single LLM retry layer; provider SDK retries are set to 0 | 3 × 3 = 9 upstream calls per failure; EDDI's layer is configurable, budgeted and skips non-retryable 4xx | Disabling EDDI's layer and keeping the SDK retries (not configurable per task, no shared backoff budget) |
| 2026-10-02 | Manager deletes default to soft; hard delete is an explicit unticked option in every dialog | Review #7: "Delete Group Only" sent permanent=true and also deleted the workspace | Keeping permanent defaults with stronger wording; removing hard delete from the UI entirely |
| 2026-10-02 | Data router with one catch-all route around the existing <Routes> to get useBlocker | No in-app unsaved-changes guard was possible under <BrowserRouter> | Rewriting the route table as route objects; intercepting link clicks by hand |
| 2026-10-02 | Role-aware UI fails open when the token carries no EDDI role | A deployment may map roles from another claim; hiding admin screens from a real admin is worse than noise | Hiding everything not positively allowed |
| 2026-10-02 | MCP role tiers mirror REST `@RolesAllowed` per tool; `eddi-viewer` is kept as an MCP-only read-and-converse role; audit trail and conversation logs stay admin + viewer (REST: admin); schedules and memory writes stay admin-only over MCP | Review §2 #17: MCP setup open to editors, deploy closed to them, config reads open to viewers, and no hierarchy for reads | A real role hierarchy in `requireRole` (would also widen every admin-only check implicitly); dropping `eddi-viewer` from MCP entirely (breaks every viewer-based MCP client) |
| 2026-10-02 | MCP tool failures become `isError: true` through an interceptor that rethrows `errorJson` results as `ToolCallException` | Every tool returns a `String`, and the unit tests compare those strings | Changing every tool's return type to `ToolResponse` |
| 2026-10-02 | Store Long as valueLong and Double/BigDecimal as valueDouble (new Property fields); a Long that fits an int is stored as valueInt | PropertySetterTask/PrePostUtils dropped or blanked them | Widening valueInt/valueFloat (breaks stored documents); storing numbers as strings (loses type in templates and conditions) |
| 2026-10-02 | A postResponse fromObjectPath that resolves to nothing writes nothing | The two property engines disagreed (postResponse stored "", property.json skipped) | Keeping "" (overwrote a good value with an empty one whenever an API omitted a field) |
| 2026-10-02 | Failed preRequest/postResponse property instructions are recorded under step key propertyInstructions:errors and do not fail the turn (scope secret excepted) | The failure was a log line only | Failing the turn (the response has already been received; one bad instruction would discard the others) |
| 2026-10-02 | The parser builds next-turn quick-reply dictionaries from the previous step's quickReplies:* data, which client-context quick replies never get; postResponse-built ones are marked server-generated on their Context | N3: client context quick replies minted expressions/actions | Stripping only the expressions (a value still generates an expression); a new persisted provenance field on IData |
| 2026-10-02 | A parser step's config.uri document is merged with the inline step: step config keys win, step extensions are appended after the document's | N1: the uri was ignored | Document-only or inline-only (either breaks existing workflows that carry both) |
| 2026-10-02 | Setup-created agents no longer get expressionsAsActions=true | Not needed by their inputmatcher-* rule; amplified N3 | Keeping it (documented risk) |
| 2026-10-02 | Unknown, foreign and malformed ids are "not found" (404) on both datastores, checked before any statement is sent | PostgreSQL answered 500, MongoDB 400 on a GET and 500 on a POST, for the same request; archives and URLs from one backend carry the other's id format | 400 for a malformed id on both (MongoDB had it on some paths) — rejected: a UUID is not malformed, only foreign, and most callers cannot tell the backends apart |
| 2026-10-02 | PostgreSQL sorts `DESC NULLS LAST`, and its field indexes are rebuilt in that order (`idx_resources_fdesc_*`, replacing `idx_resources_field_*`) | MongoDB sorts a missing field last, PostgreSQL first | `NULLS LAST` on the old ascending index — rejected: the planner can no longer read a sorted page off it and sorts the whole collection per listing page |
| 2026-10-02 | On an upgraded database the `idx_resources_fdesc_*` replacements are built `CONCURRENTLY` in the background, under a per-index advisory lock, and the old index is dropped only once the new one is valid | A blocking `CREATE INDEX` at boot locks the shared `resources` table (a descriptor per conversation) against writes cluster-wide for the whole build | `CREATE INDEX CONCURRENTLY` at boot — rejected: it waits for every open transaction, so boot could stall, and two booting instances race on one name; keeping the ascending index and adding the new one beside it — rejected: two indexes per field cost every write |
| 2026-10-02 | The EDDI 5 migrations are inert when `eddi.datastore.type=postgres` | `holdsRetention()` dereferenced the lazy `MongoDatabase` proxy on PostgreSQL and held retention for ever | Injecting `Instance<MongoDatabase>` — rejected: the proxy is already lazy; the defect was calling it, and EDDI 5 never ran on PostgreSQL |
| 2026-10-02 | RAG stores are addressed by the config id; a `storeNamespace` field keeps 6.5.0 KBs on their name-derived store until renamed or switched | Cross-KB poisoning via `?kbId=` and same-name KBs sharing a store | A startup migration moving vectors (EDDI does not own external stores); filtering legacy KBs (would hide all 6.5.0 chunks) |
| 2026-09-30 | An idle limit below 1 disables idle-ending; old versions with no active conversation are still undeployed | `-1` ended every conversation, while the retention settings read `-1` as "never" | Treating `-1` literally; also stopping undeploys (a separate decision needing its own setting) |
| 2026-09-30 | A NEGOTIATION group's prompt-less Arbitration phase is repaired in the backend at run time and on save, not rejected | Only the Manager repaired it, so behaviour depended on which client saved the group | A 400 at save (the Manager saved this shape itself, so its groups would become unsaveable) |
| 2026-09-30 | Schedule `enabled` changes only through `/enable`/`/disable`; a configuration PUT keeps the stored value inside the same atomic write on both stores | CodeRabbit on labsai/EDDI#854: the Manager editor's save re-enabled a schedule another operator had disabled after the dialog read it | Optimistic concurrency on `updatedAt` with 409 (needs a client protocol change, and the other lifecycle fields already follow the keep-stored rule); simply omitting `enabled` from the PUT (the model defaults it to `true`) |
| 2026-09-29 | Merge with main: a captured conversation property is kept at turn end; a secret input shorter than 4 characters is removed as a whole token, in values only | The wizard pattern hands a captured key to a later turn; a short PIN echoed into a reply must not persist, but digits inside other numbers and field names must | Main's property scrub; no search below 4 characters; renaming map keys in token mode |
| 2026-09-29 | One wall-clock deadline, max(3 × connect timeout, request timeout), covers every SafeHttpClient send path including buffered body reads; redirect bodies go to a 64 KiB discarding subscriber | H16/R8 after #862: the per-request timeout stops at the headers, and 3xx bodies reached the caller's handler (an unclosed stream per hop on the bounded paths) | Leaving buffered paths to per-caller bounds; discarding redirect bodies without a cap (downloads a huge courtesy page in full) |
| 2026-09-29 | Reconciled fix/outbound-http-hardening with #862 by keeping main's implementation wherever both fixed the same finding | Two parallel fixes; main's shipped first and is what other code builds on | Keeping the PR's handler-level BoundedBodyHandlers alongside main's sendBounded (two mechanisms for one job) |
| 2026-09-29 | Block the NAT64 local-use prefix 64:ff9b:1::/48 whole instead of unwrapping its trailing 32 bits | RFC 8215 allows any RFC 6052 prefix length inside it, so the IPv4 address need not be at the end | Unwrapping per prefix length (the length is a per-network choice EDDI cannot see) |
| 2026-09-29 | PR #831 keeps #859's `ClientContextGuard` and #860's converse ownership check, drops its own `ReservedContextKeys` and started-conversation set, and applies the delegation USE check to continuations | Two implementations of one boundary drift apart; the USE check on continuations closes the one case the started set covered that ownership does not | Keeping the started set alongside the ownership check (two continuation rules for one tool); stripping in `ConversationService` as well (a second boundary for the same keys) |
| 2026-09-29 | Reconciled with main: main's per-write auto-vault slots (AutoVaultedSecrets) replace this branch's per-conversation key; only the deltas main lacked are kept | Both sides fixed C2; one implementation, main's | Keeping both; keeping the per-conversation key and pre-allocated conversation id |
| 2026-09-29 | Where this branch and #861 fixed the same Slack finding, keep #861's implementation (approval records + card ids, owner-bound signatures, mandatory namespaced ids) | Two parallel bindings of one decision are harder to reason about than one | Keeping both bindings; keeping this branch's opt-in user-id namespacing |
| 2026-09-29 | Every Slack route the event handler takes must belong to an app holding the secret that verified the event | Thread locks and group follow-ups are found by a sender-chosen timestamp, outside the webhook's channel-owner check | Checking the channel only (a DM is owned by nobody) |
| 2026-09-29 | KEK rotation keeps main's rollback and adds re-runnability only for what a rollback cannot undo | Both branches fixed "a half-way rotation strands DEKs"; one mechanism, main's, with the pending salt and either-KEK verify for the unrecoverable cases | Forward-only re-run (this branch's original); rollback without the pending salt (loses DEKs when the salt write fails) |
| 2026-09-29 | Libraries that come from Quarkus or integrate with it take the version the Quarkus platform ships, never a newer one | Quarkus validates its platform as a set; running ahead of it invites classpath and behaviour mismatches nobody tested | Taking the newest stable release of every library independently |
| 2026-09-29 | langchain4j stays at 1.20.0 (1.20.2 reverted) | The Quarkus 3.39.5 platform ships langchain4j 1.19.3 via quarkus-langchain4j-bom | 1.20.2 with langchain4j-community-oci-genai pinned at 1.20.0-beta30 |
| 2026-09-29 | Global-key ownership for the rememberFact tool is decided by an owner-conditional write in the store (ON CONFLICT … WHERE on PostgreSQL; an owner-filtered upsert against a new unique partial index on MongoDB) | Read-then-upsert let two agents both see a key as free and the later value win (CodeRabbit on #860, CWE-367) | Serialising writes per user in the tool (single-node only); a transaction around read and write (not available on standalone Mongo) |
| 2026-09-29 | OpenAI-compatible vendors are catalog entries, not Java classes | One JSON file feeds the backend registry and (through a parity test) the Manager, so a new vendor is a data change. | A Java builder class per vendor; a REST catalog endpoint. |
| 2026-09-28 | json-schema-validator 3.x, victools jsonschema-generator 5.x and bson4jackson 3.x not taken | All three require Jackson 3 (tools.jackson.*) in their public API; the ban-jackson3 enforcer rule forbids it and the Quarkus BOM ships Jackson 2 | Lifting the Jackson 3 ban for three libraries as part of a dependency bump |
| 2026-09-28 | UI majors: take every stable major whose peers allow it; keep TypeScript 5.9 in the Manager, hold jsdom at 30.0.x, and pin react-hooks to its two classic rules | typescript-eslint 8.71 caps TypeScript below 6.1; Vitest 5.0.2 cannot unwrap jsdom 30.1 Blobs; react-hooks 7 `recommended` adds 119 React Compiler findings | Forcing TS 7 with overrides / --legacy-peer-deps; jsdom 30.1 with a Blob shim in test setup; fixing 119 compiler findings inside a dependency bump |
| 2026-09-28 | A vault reference in synced content yields to the target's own value at the same place; it travels only where the target has none | Which vault entry an environment uses belongs to that environment; a promotion that repointed production at staging's entry broke every call it made | Transferring references as-is (the defect); stripping them entirely (a first promotion would lose the only hint of which entry to create) |
| 2026-09-28 | Local edits are detected by a `syncedVersion` recorded on the descriptor at every sync or import write, and a resource changed on both sides is written only when named | The descriptor already travels forward with every version through `DocumentDescriptorFilter`, so a version past the recorded one is a local edit without any new store; overwriting a hotfix by default is the one outcome a promotion must not produce | Content hashes in a separate collection (a second store to keep in step); warning but writing anyway (the hotfix is still lost) |
| 2026-09-28 | A resource for a step the source added is created only when the workflow adoption that places it is decided to succeed, before anything is written | The previous refusal made adding a step impossible to promote; creating unconditionally left an orphan whenever the adoption was refused afterwards | Refusing (unusable); creating then deleting on refusal (a second failure path mid-sync) |
| 2026-09-28 | Without `targetAgentId`, a sync targets the agent promoted from that source (by `originId`), with `createNew` to force a copy and 409 when ambiguous | The guide already said the originId "is what lets the next sync match it", but nothing matched on it, so every call without a target made another full copy | Keeping "no target means create" (not idempotent for scripts and CI); picking the most recent of several (a guess) |
| 2026-09-28 | A parser document's dictionaries are matched as resources keyed under the document, and the parser repointed by source id — never paired by position | Pairing a document's references by position with the target's copy made a dictionary *replaced* by another at the same place compare equal, so the preview said SKIP and nothing was synced; keying them lets the ordinary machinery diff, create and update them | Positional pairing against the target's copy (the first version of this change — silent on a replacement); pairing by the target descriptor's originId (only covers resources an import created, and still leaves new dictionaries uncreated) |
| 2026-09-28 | An archive without a parser document imports its parser step unchanged, instead of pruning the step as a `create` does for any other missing config | Every archive written before parser documents travelled lacks the file; pruning would leave the agent unable to parse input, and a `merge` would fail with no local copy to answer the reference. The pipeline never loads the document, so the old behaviour was harmless | Treating parser like every other type (breaks every existing archive); failing with a message (the operator cannot act on it — the product wrote the archive) |
| 2026-09-28 | A sync recreates a resource the target's step names but the target no longer has — only when the store confirms it is gone | Every agent promoted before parser documents travelled names a parser its instance never had; refusing that CREATE would fail every later sync of it | Reporting it as a failure (permanent 207 for every previously promoted agent); recreating on any read failure (a timeout would orphan a live resource) |
| 2026-09-28 | Shares resolve names and emails through a user directory; the principal stays the storage key | Sharing with an email stored `user:<email>`, which matched no principal and reached nobody while answering 200 | Keying grants on email (mutable; unverified emails let anyone collect shares), or requiring raw principals from users |
| 2026-09-28 | Space secrets and variables use hashed (64-bit) per-space tenants, authorized by membership and checked again at deploy; a reference assembled from variable pieces is refused at resolution | Editors could not store their own keys, and any editor could overwrite a global variable other teams relied on | Per-secret ACLs; letting EDIT on an agent imply use of the owner's team secrets |
| 2026-09-28 | Conversation review is opt-in per agent version and read-only, with a notice shown before the first message | Owners of shared agents could not see how the agent was used, and silent access to other people's chats is not acceptable | Owners always reading conversations; an opt-in that applies retroactively to versions that did not announce it |
| 2026-09-27 | A client-flagged secret input is searched for from 4 characters, not 8 and not 1 | A PIN or short password is what a password field carries; below 4 the replace-everywhere search shreds the turn's reply and map keys for a guessable value. Same floor in the turn-end scrub, read-time masking and audit buffer | The 8-character context-value floor; every nonempty form |
| 2026-09-26 | Chat UI: KaTeX and highlight.js are loaded on first use, not bundled | Wiring the advertised features statically tripled the widget bundle (645 → 1086 kB) | Static imports; dropping the features from the README |
| 2026-09-26 | A client-flagged secret input is scrubbed when its turn ends, and the turn output's `input` is the masked display copy on the wire | The parser overwrote the placeholder with the normalized plaintext, so exposing `input` alone would have leaked a copy | Exposing `input` without the engine fix; scrubbing properties the designer captured the input into (breaks the wizard pattern) |
| 2026-09-26 | Older stored secret turns are masked on read, not migrated | Every secret turn carries `context.secretInput`; masking on read closes the exposure for history at no migration cost | A data migration; leaving history exposed |
| 2026-09-26 | Chat UI math is `$$…$$` only | Single-dollar math turned dollar amounts into formulas | remark-math defaults |
| 2026-09-26 | A queued turn whose snapshot was superseded is rebuilt over a reloaded memory (revision probe first) | H13a/E1: queued turns ran on request-time memory | Always reloading (doubles every turn's load); refreshing the memory in place (the Conversation's longTerm baseline would stay stale) |
| 2026-09-26 | Persistent schedules may roll over (opt-in, idle only, conversation properties carried) at a step limit instead of trimming | 16 MB document limit | Trimming steps (destroys history, violates "full data is never deleted by optimization"); on by default (silently resets a stateful agent's history) |
| 2026-09-26 | The in-chart and Kustomize MongoDB authenticate by default, and Helm refuses to render without a password | C5c: any pod could read and rewrite all EDDI data | NetworkPolicy only (inert without an enforcing CNI); auth opt-in (leaves the default open) |
| 2026-09-26 | The /chat framing allow-list is an operator property (default 'none'), in its own CSP filter | M-F3: the global DENY blocked the documented embed | Relax frame-ancestors for every path; XFO ALLOW-FROM (unsupported) |
| 2026-09-26 | The wizard's spec-by-URL needs eddi.csp.extra-connect-sources; connect-src stays strict by default | M-F4: the browser blocked the fetch | Allow https: in connect-src by default (opens exfiltration for any injected script) |
| 2026-09-26 | A failed merge import writes each updated resource's pre-import content back as a new version (and restores its descriptor) | M-P4: rollback only deleted created resources, leaving the target half-promoted | Deleting the imported version / restoring history in place (history is append-only everywhere else) |
| 2026-09-26 | `IResourceStorage.storeHistoryAndRemove` takes the expected version and has no default implementation | M-P5: a delete racing an update erased the committed version and lost its tombstone | Keeping an unconditional default for non-transactional backends |
| 2026-09-26 | Tool-loop retry wraps one model request, not the loop | H11a: whole-loop retry re-executed tools | Keeping whole-loop retry with a dedupe journal on the live path (more state, same result) |
| 2026-09-26 | Cascade escalation carries the executed tool exchange to the next step (default on, `carryToolResultsOnEscalation`) | H11b: each escalation re-ran side-effecting tools | Refusing to escalate after any tool ran (defeats agent-mode cascades) |
| 2026-09-26 | The carried-exchange fallback retries only a rejected FIRST request of a step, and only for a 400/422-class rejection | A failure after tools ran would re-run them; auth/model errors cannot be fixed by dropping the exchange | Retrying on any non-retryable failure (replays side effects, masks auth errors) |
| 2026-09-26 | HTTP/MCP/A2A tool results are cached only when named in `toolCacheScopes` | A cache hit skips a write | A new `cacheableToolSources` list (extra field, same effect) |
| 2026-09-26 | Manager approval decisions carry the shown pause (pauseId when reported, else a fresh re-read compared on kind/start/call ids) | A queue row up to 10 s old approved whatever the conversation was paused on, including an unreviewed tool-call batch | Sending toolDecisions only (does not stop a rule-row approval landing on a tool pause); waiting for the backend pauseId alone (leaves current main unprotected) |
| 2026-09-26 | Audit screen verdict comes from GET /auditstore/verify, green only when verified | hmac presence was shown as a verified shield | Verifying client-side (no key in the browser); keeping the count banner with softer wording |
| 2026-09-26 | Fixed-time crons (no `*` in minute or hour) fire once per local time across DST: skipped time at the transition, repeated time at its first occurrence; wildcard crons keep real-time cadence. Accepted Vixie consequences: `0 2,3` collapses into one fire on spring-forward, fixed hour ranges do not repeat the doubled hour | M-Q1: `30 2 * * *` double-fired / was skipped in DST zones | Shifting a skipped time by the gap length (ZonedDateTime.of): `30 2,3` would collapse into one fire |
| 2026-09-26 | An oversized fire-and-forget batch is refused whole, not truncated; `maxBatchSize` default 100, ceiling 1000 (both operator-configurable) | M-Q3: uncapped fan-out from upstream/LLM data | Silent truncation (reports success while dropping requests) |
| 2026-09-26 | A schedule PUT keeps `metadata`/`tenantId`/`allowSelfScheduling` only when the body omits them; naming the field (even `null` or `{}`) sets it | UI High 5: edited system schedules became chat schedules | Treating metadata as never editable over REST: an operator could not deliberately clear a marker |
| 2026-09-26 | Dismissing a dead letter is a store operation conditional on DEAD_LETTERED, 409 otherwise | Unfenced `markCompleted` reset live claims (double fire) | Fencing on fireId: a dead-lettered row holds no claim to fence on |
| 2026-09-26 | A schedule PUT is judged on the STORED row for managed schedules: RAG ingestion refused outright (409), team cadence requires EDIT on the group | Review MAJOR 1: carrying metadata over let USE+VIEW re-cron a knowledge base's crawl | Requiring KB EDIT for ingestion edits (as fireNow does): the knowledge-base save re-creates the row from its source cron, so an edit here would be silently undone anyway |
| 2026-09-26 | Batch cap default and ceiling are operator properties (`eddi.httpcalls.batch.*`); a `maxBatchSize` above the ceiling is refused at save time | Review MINOR 2: hard-coded cap was a regression with no escape hatch | Silent runtime clamping only |
| 2026-09-26 | `RestAgentStore.updateAgent` keeps the stored `dynamicOrigin`; duplicate and new-agent ZIP import drop it; create still accepts it | The marker authorizes permanent deletion, so only the engine may write it; setup writes it through the loopback REST create | Stripping on create (breaks `AgentSetupService`); a separate engine-only store field (larger change, same outcome) |
| 2026-09-26 | Reserved context keys are read with exact-key lookups (`getData`, new `getExactDataPerStep`) **and** `ClientContextGuard` also drops keys that start with a reserved name | `getLatestData`/`getAllLatestData` match by prefix, so `groupIdSuffix` and friends passed the exact-name guard and were read as the reserved key (PR #831, CWE-863) | Only the exact lookups (the next prefix read reopens it); only the prefix drop (leaves every reader relying on a distant boundary); changing `getLatestData` to exact matching (many callers rely on the prefix, e.g. `httpCalls:*`, `output:*`) |
| 2026-09-26 | `teardown_agent` and end-of-discussion cleanup require a `dynamicOrigin` marker on the target agent in addition to the created-ids list | The list alone decided and could be forged; deletion is permanent | Trusting the list once the context channel is closed (one leak away from deleting a person's agent) |
| 2026-09-26 | Short secret context values (4–7 chars) are exact-match scrubbed, top-level string entries only | S4: a PIN copied into a property survived; object leaves would blank unrelated data | Substring replacement; matching object leaves |
| 2026-09-26 | Success bodies: text redaction for secrets ≥ 8 chars, JSON scrubbed as a tree | Echo redaction must not corrupt the data a call fetches | Redacting every length by substring; redacting error bodies only |
| 2026-09-26 | HITL decisions may carry a pauseId (epoch-ms of the pause start), checked before and after the resume CAS | A stale REST/MCP decision could approve a later, different pause | A stored random pause UUID (needs a schema field and migration for RULE pauses) |
| 2026-09-26 | A non-owner approver's identity is bound only around the tool calls they approved; the rest of the resumed turn has no caller | ${caller:token} calls after an approval ran with the approver's token, including un-previewed calls after a RULE pause | Keeping the whole-turn binding (privilege escalation); dropping it entirely (breaks approved caller-bound calls) |
| 2026-09-26 | Audit HMAC key pinned in the vault (sealed under a reserved system tenant's DEK) and keyed by id in v5 signatures | KEK rotation silently changed the audit key and invalidated the whole ledger | Requiring operators to configure a separate key (kept as an option, eddi.audit.hmac-key); a KEK-wrapped meta value (would need its own re-wrap step in rotateKek) |
| 2026-09-26 | Lost master key is resolved by an explicit admin call (adopt-master-key), not automatically | A stale replica and a lost key look identical from a node; guessing either way loses data | Auto-adopting on mismatch (lets a stale replica strand tenants); manual DB edit (undocumented) |
| 2026-09-26 | UNKNOWN_KEY only for key ids recorded as sealed system values | The key id in a row is attacker-writable text | Rewording docs only |
| 2026-09-26 | Unreadable vault salt fails startup | Legacy-salt fallback derives the wrong KEK on random-salt deployments | Falling back and warning; marking the vault unavailable |
| 2026-09-26 | Schedules that run as a real user are that user's for every operation, workspaces on or off | H2a: editors could list/disable/delete other users' dream schedules | Workspace-only scoping (left the per-user leak open with workspaces off) |
| 2026-09-21 | Default the per-tool timeout to 120000ms and leave it ON, rather than shipping it disabled | A bound nobody enables does not fix a hang; and the field is null on every agent already stored, so the engine-side fallback is what actually decides the default | Shipping `-1` by default (inert for everyone), and a 30s default (would cut into legitimate nested-agent and delegation calls, and duplicate the MCP/A2A transport timeouts) |
| 2026-09-21 | Return an error string on expiry instead of throwing | `RetryConfiguration.isRetryableError` treats `TimeoutException` as retryable, so a thrown timeout becomes a retry loop over a hang; and the rate-limit branch already established "tell the model, keep the turn" | Throwing a `LifecycleException` (fails the turn), throwing `TimeoutException` (retried), a custom checked exception (every caller would have to translate it back into a string anyway) |
| 2026-09-21 | Run the bounded call on a shared virtual-thread executor, inline when unbounded | A timeout needs a second thread by construction; virtual threads make the abandoned worker of a hung tool nearly free, and running inline when disabled keeps the untouched path byte-identical | A fixed platform-thread pool (an abandoned worker costs a whole thread and stack, and a pool of N hung tools deadlocks the N+1st), a pool per call (leak by construction) |
| 2026-09-21 | Add `int timeoutMs` as a tenth parameter rather than a field on `ToolInvocation` | The brief asked for the `rateLimit` shape end to end, and `rateLimit` is a parameter; it also breaks stale Mockito stubs at COMPILE time instead of silently mismatching at runtime | Putting it on the `ToolInvocation` record (would have kept every `any(ToolInvocation.class)` stub compiling while no longer describing the call), a separate 10-arg overload (same silent-mismatch problem) |
| 2026-09-21 | Count abandoned workers on a gauge, but put no admission bound on the timeout executor | A leak an operator cannot see is the dangerous half of the trade-off, and counting is unambiguously right; refusing healthy tool calls because unrelated ones are stuck trades a per-tool failure for a conversation-wide one, which is a product call rather than a review fix | A bounded-queue or semaphore executor with a model-visible "too many tools running" error (Copilot's suggestion, left open for a human), a per-tool `Semaphore` (same blast radius, more bookkeeping), doing nothing and leaving the accumulation undetectable |
| 2026-09-21 | Leave the `String` overload unbounded | Its only caller is `McpCallsTask`, which has no `LlmConfiguration.Task` and already bounds its tools with `McpCallsConfiguration.timeoutMs` | Giving it the same default (would apply an LLM-task setting to a workflow step that cannot configure it) |
| 2026-09-21 | Walk `docs/` rather than list it in `ComposeStackTest`, and assert the sweep's shape | A guard that reads only the top level would have failed over a nested page that *does* document a stack; a false failure is how a guard gets deleted rather than fixed | Listing the top level only (the reviewed version), naming the nested directories explicitly (a third place to forget when one is added) |
| 2026-09-29 | Each Slack approval card carries a random card id in its buttons, recorded with the card; a click must present the id recorded for the current pause | Subject + pause matching let an old card of the same subject approve a newer pause | Storing the posted message `ts` on the record after `chat.postMessage` (a post-then-update window, and a failed update leaves a live card unusable) |
| 2026-09-28 | The Slack workspace comes only from a declared `platformConfig.teamId`, for owned channels too; without one the identity is team-less | A signing secret authenticates the integration, not the workspace in the payload | Trusting an owned channel's payload team_id; scoping team-less ids by integration name (names are mutable) |
| 2026-09-28 | Adoption of raw-id `/v1` chat mappings is opt-in (`adopt-legacy-header-mappings`, default false) | A raw mapping may have been written for an OIDC principal under `http-policy=authenticated`; its origin is unrecorded | Adopting by default (reopens the cross-namespace reach); heuristics on the id's shape |
| 2026-09-28 | `/v1` refuses an OIDC principal carrying the reserved `openwebui:` prefix | Such a principal equals a header-derived identity | Namespacing OIDC principals too |
| 2026-09-27 | Removed the standalone bare-id memory MOVE for both OpenAI-compat and Slack; kept adopt/rekey only | The move read the shared bare-id namespace on an attacker-chosen id and could relocate+erase an OIDC user's memories (review Finding A/B) | A mapping-gated move (still leaks on a bare-id string collision with an OIDC principal) |
| 2026-09-27 | HITL owner binding refuses an ambiguous integration name and enforces global per-type name uniqueness | Integration display names were not unique, so a copied name could bind a decision to the attacker's config (review Finding C) | Keying everything on a unique resource id (larger blast radius on a security branch) |
| 2026-09-27 | Slack webhook rejects an event whose claimed workspace disagrees with the signing integration's declared teamId; a teamId-less unowned (DM) event is bound team-less, not to the payload team | Payload team_id was attacker-controllable in a validly-signed DM event → cross-tenant memory read (review residual #1) | Trusting the payload team when the integration declares none |
| 2026-09-26 | Conversation guard resolves the owner from the archived descriptor; no descriptor at all = 404 for non-admins | A soft delete left the snapshot readable and drivable by every authenticated caller | Admin-only `/active` + `/end` (editors undeploy and already end them all); deleting audit entries with the conversation (append-only ledger) |
| 2026-09-26 | GDPR erasure stops in-flight work through a CDI SPI (`UserErasureParticipant`) called before the cascade, not a direct call | The services that own turns and discussions depend on GdprComplianceService, so the dependency cannot point the other way; same pattern as SealedDataRotationParticipant | Injecting ConversationService/GroupConversationService directly (dependency cycle); a CDI event (no failure reporting into failedSteps) |
| 2026-09-26 | `_gdpr_` memory keys are refused at the store (`upsert`/`mergeProperties`) and written only via `upsertReserved` | A caller-side check misses the next write path; a category check is forgeable because REST/MCP callers choose the category | Validating in each caller only; honouring the key when category == gdpr |
| 2026-09-26 | Manager treats a deployment at an older version as "live" (flagged `deployedVersion`) instead of "Not deployed" | Every save bumps the version and the per-version status endpoint answers only for the exact version | A backend "any version" query parameter (needs a contract change); asking the listing for every card (N requests) |
| 2026-09-26 | Workforce Stop cancels on the server before closing the stream; a 409 closes it without claiming CANCELLED | Stop only aborted the SSE connection, leaving the run spending | Aborting first and cancelling after (a failed cancel would then leave a running discussion with nothing following it) |
| 2026-09-26 | A stream that ends without a terminal event is "interrupted", and the UI follows the stored conversation | A dropped connection froze the board or showed FAILED for a still-running discussion | Closing placeholders as SKIPPED on interruption (those members may still be answering) |
| 2026-09-26 | returnDetailed hardened by redaction + denylist rather than an owner gate | The detailed conversion is a central chokepoint reached by several endpoints and the owner is not cheaply available at that layer; raw full-fidelity data stays on the owner/admin-gated raw endpoint | Restricting returnDetailed to admin/editor/owner at every call site |
| 2026-09-26 | UserMemoryTool default allowedVisibilities = [self] | Secure-by-default: a prompt-injected model must not broadcast group/global memories unless an operator opts in; the configured defaultVisibility is always unioned in so it is never self-blocking | Leaving visibility fully model-chosen |
| 2026-09-26 | Slack HITL decisions require a persisted record of the card the owning integration posted, matched to the subject's current pause | Signature and approver list bound the integration, not the subject | In-memory marker (lost on restart); trusting the button value |
| 2026-09-26 | Slack users are `slack:<team_id>:<user_id>` in EDDI; raw-id mappings are re-keyed only | Raw Slack ids shared the OIDC principal namespace | Keeping raw ids for existing users (leaves the collision open); a one-shot bulk migration |
| 2026-09-26 | OpenAI-compat header users are `openwebui:<id>` in EDDI; raw-id mappings are re-keyed only (opt-in since 2026-09-28) | `X-OpenWebUI-User-Id` shared the OIDC principal namespace, so a shared-key holder reached OIDC users | Namespacing OIDC principals too (they are already canonical); a one-shot bulk migration |
| 2026-09-26 | Mask secret input by scrubbing input:initial post-pipeline in Conversation's finally, not at store time | The plaintext must reach parser/property tasks (vaulting) during the turn but never persist; the finally runs on error/pause paths too and precedes the audit redaction | Storing a placeholder up front (would break scope=secret vaulting and normalization) |
| 2026-09-26 | Chat markdown images render as links (keep img in sanitize schema + component override) rather than dropping img from the schema | The "renders as a link" behaviour needs the node to reach the renderer; the override prevents any fetch, preserving the URL as a click-through | Dropping img from the sanitize schema (loses the URL entirely and cannot render a link) |
| 2026-09-26 | Deep links preselect the agent only; starting a conversation stays an explicit user action | Auto-starting as the admin from a URL param is a CSRF-style side effect | Keeping the auto-start behind a confirmation step |
| 2026-09-26 | Bind EDDI to 127.0.0.1 by default in compose, opt-in `EDDI_BIND` to expose | An unauthenticated default must not be network-reachable out of the box; MongoDB was already loopback-bound, EDDI was not |
| 2026-09-26 | Helm: require explicit opt-in for /mcp and /secretstore when OIDC is off | Deriving the opt-outs from oidc.enabled silently opened the two highest-value surfaces, defeating HighValueSurfaceGuard |
| 2026-09-26 | CI: sign the image digest and require the release tag to be an ancestor of main | A tag on any commit could publish and sign :latest, bypassing review; a digest signature cannot be moved by a later tag push |
| 2026-09-26 | Bound outbound response reads with a shared BoundedBodyReader (cap + deadline) rather than per-tool ad-hoc checks | One implementation across attachment/web/pdf/A2A paths, mirroring the crawler's tested readBounded; size is enforced while streaming, not after buffering | Keep per-tool post-buffer size checks (the status quo that let unbounded bodies into memory first) |
| 2026-09-26 | Drop the shared WebClientSession cookie jar for httpcalls in favour of a plain WebClient | The single app-scoped session replayed one user's Set-Cookie on another user's call to the same host; no httpcalls feature needs cookies | Scope a WebClientSession per conversation/principal (more machinery for a capability nothing uses) |
| 2026-09-26 | Strip `SafeHttpClient.SENSITIVE_HEADERS` on every cross-origin redirect hop in the shared httpcalls client (`HttpClientModule`), and additionally disable redirect-following for a request whose header carries a resolved (reference) credential | Vert.x's redirect handler strips none of these headers cross-origin, so a literal credential written in the config leaked too; the hop-level strip covers every credential source, and the per-request disable is the stronger measure for resolved secrets | Disable redirects only for reference credentials (the first cut: missed literal credentials); strip only the three RFC headers (Vert.x does not even do that) |
| 2026-09-26 | Under enforced workspaces a render gets only snippets from the agent's space, its owner's own snippets (personal-space agents only) and legacy snippets; granted/published snippets from other spaces are never auto-injected | C4c + pre-push review: snippets are injected by name, so a grant or publish is a push into other tenants' prompts (incl. the counterweight preset names) | Ranking grants/published below closer tiers (they remain the only candidates for unclaimed names such as the presets); scoping by the chatting user |
| 2026-09-26 | Caller-supplied and already-rendered output is marked verbatim per data entry, persisted in ResultSnapshot, and never templated again | C4a + review: context output was rendered as Qute; a HITL resume or a second templating pass re-rendered data | Key-prefix convention (collides with an action named "context", suffix is client-chosen); a transient flag (lost on tool-call resume) |
| 2026-09-26 | Strip engine-reserved context keys at the client entry points (REST, streaming, MCP trigger context) with an opt-in config list, rather than verifying them downstream | The engine trusted client-supplied values for group memory scope, dynamic-agent policy, created-agent tracking and delegation depth | Marking trusted entries with a flag on `Context` (does not survive the store round-trip, so reloaded steps would lose the policy); stripping inside `ConversationService` (internal group/delegation callers use the same methods) |
| 2026-09-26 | Runtime templates render with an EDDI-built Qute engine assembled from the Quarkus engine's resolvers through allow-lists | The injected engine exposes config:, inject:/cdi:, str:eval and unrestricted reflection to agent-authored templates | Keeping the Quarkus engine and scrubbing template text (unbounded syntax surface); hand-writing all extension resolvers (duplicates Quarkus's generated ones) |
| 2026-09-26 | Values read via fromObjectPath are stored verbatim, with no opt-out | They are user/upstream data; no legitimate configuration needs them evaluated, and valueString covers composing text | A config flag to restore rendering (would re-open the path per agent) |
| 2026-09-22 | `StanceSummaryConfig` carries no `enabled` flag | Stances always exist (extraction needs no config), so the flag could only mean "may this spend?" — already said by naming a provider/model. A flag could contradict them. | EDDI |
| 2026-09-22 | `cost_updated` carries cumulative cost, never a delta | The ledger records by replacement, so duplicates are idempotent; a delta frame replayed after a reconnect would double-count, and PARALLEL turns interleave. | EDDI |
| 2026-09-22 | `memberStances` rides schema v4 without a bump | It is a display projection — reproducible from the transcript, read by no resume path. The bump rule is scoped to resume-consumed fields. | EDDI |
| 2026-09-22 | Per-style difference is band ORDERING, not a renderer per style | Every discussion reduces to phases × members × entries; a style table means an unknown style still renders, and adding one is a row rather than a component. | EDDI Manager |
| 2026-09-22 | One `useDiscussionDigest` adapter for all three transcript surfaces | Two data shapes × three renderers is exactly how a DISSENT once rendered as an opinion on two of them; one adapter makes the drift structurally impossible. | EDDI Manager |
| 2026-09-22 | `split` restacks instead of being width-gated | A gated mode would discard the half the user picked and silently rewrite their stored preference. | EDDI Manager |
| 2026-09-22 | Round boundaries are recovered from QUESTION entries but validated against the stored index | Only the current round's start is persisted. Trusting the QUESTION invariant blindly split a discussion on a stray marker; validating and falling back narrows the switcher instead of slicing the view wrongly. | EDDI Manager |
| 2026-09-22 | The interaction band is a list, not a graph | A force-directed diagram of five nodes is decoration and of twenty is unreadable; a list answers "who did this member take on" and "who went unchallenged" at any size with no layout engine. | EDDI Manager |
| 2026-09-22 | DELPHI gets no interaction band | Naming who answered whom would undo the anonymity the method rests on — the reason its later rounds run ANONYMOUS. | EDDI Manager |
| 2026-09-22 | A first-time live sync fetches the source's export archive and imports it, rather than creating resources itself | `executeUpgrade` has no create path, and writing one meant a second implementation of everything `RestImportService` already does for a ZIP — schedules, connections, capability registration, rollback | Writing native creates in `UpgradeExecutor` (would quietly do less than a ZIP import and drift from it); materialising the archive locally from `IResourceSource` (duplicates the export's layout rules) |
| 2026-09-22 | The sync source policy is three independent settings, all defaulting to the strict behaviour, with an exact-origin allow-list as the preferred one | A compiled-in refusal of every private address made the feature unusable for self-hosted deployments, but relaxing it globally by default would let any caller of the endpoint probe hosts behind the deployment | Reusing `eddi.security.ssrf-protection.enabled` (it governs agent-config-driven calls, a different trust question, and defaults the other way); a single "allow internal" switch (cannot express "this one staging host") |
| 2026-09-22 | `includeFirstAgentMessage` drops a message only when it is the agent's, and the flag is deprecated | The unconditional `removeFirst()` emptied the history of any agent with no `ai.labs.output` step, and Anthropic rejected the call; the Anthropic rule the flag exists for no longer applies | REMOVING it — agent behaviour lives in stored JSON, and silently ignoring a deliberate setting would start sending a greeting the author chose to withhold, with no diagnostic |
| 2026-09-22 | A HITL rejection gets its own `REJECTED` state rather than reusing `FAILED` | The Manager rendered a recorded human decision as a red "Failed" badge | Re-labelling `FAILED` in the UI only — the backend distinction is what audit and API consumers need |
| 2026-09-22 | Pre-`REJECTED` documents keep `FAILED`; no migration | Nothing stored distinguishes a rejection from a failure, so a migration could only guess | Backfilling from the audit ledger — it is not guaranteed enabled |
| 2026-09-22 | The debate-verdict note is INFO, not a save-time rejection | For a real DEBATE group the verdict path is the intended behaviour; rejecting would break every existing debate config | A hard error, and a WARN (which would cry wolf on every correct debate) |
| 2026-09-22 | The expanded group question is height-bounded and scrolls itself | Unbounded expansion pushed its own "Show less" out of an overflow-hidden pane, so it could not be undone without a reload | Making the whole header scroll — it would move the state badge and cost pane height a transcript needs |
| 2026-09-22 | "New Discussion" is guarded by an explicit-clear ref, not a one-shot restore | Preserves today's auto-select-after-delete behaviour, which a one-shot ref would drop | The Workforce board's one-shot `restoredRef`, which is right for its "restore an ongoing discussion" semantics but not for this page's "select the newest" one |
| 2026-09-22 | The session log stream is lazy and refcounted rather than removed | The Logs page genuinely needs a live tail; what was wrong was holding it on every page | Keeping the boot connection and raising the tab budget — the six-per-origin cap is Chrome's, not ours |
| 2026-09-22 | A plain Save toasts "not yet live" with a Deploy action rather than deploying itself | Deploying on every Save would make an ordinary edit a production change; the gap was that nothing said the change was inert | Auto-deploying, and leaving it to documentation |
| 2026-09-21 | Document the three-sided KB binding instead of making the workflow step optional | Retrieval discovers knowledge bases from the workflow document, which is what makes a KB an agent-level capability rather than a per-task one; inferring a binding from `knowledgeBases[].name` alone would let any task reach any KB in the deployment. The requirement is correct — it was undocumented. |
| 2026-09-21 | Fix CWE-117 in `NatsConversationCoordinator` at the log call, and also widen `sanitizeSubject` to strip CR, LF and tab | The log call and the subject token answer different questions, so both are fixed. Widening was first rejected as moving the subject namespace; that was wrong — NATS refuses a subject containing those characters outright, so nothing was published under one, and the rejection is an unchecked `IllegalArgumentException` that escapes the publish's `IOException \| JetStreamApiException` handler | Leave CR/LF in `sanitizeSubject` and rely on the log call alone — keeps a latent unchecked-exception path for no gain |
| 2026-09-21 | Arm an ingestion schedule in its creator, not in `createSchedule` | A cron ingestion source was stored enabled with a null `nextFire`, which no `findDueSchedules` can ever match, so it never ran | Computing `nextFire` inside the stores: `MongoScheduleStore` does not cover `PostgresScheduleStore` (no shared base), and it would make a second copy of the arming policy that already lives once in `RestScheduleStore.computeRearmNextFire` |
| 2026-09-21 | Repair already-stored unarmed ingestion schedules with a repeatable startup sweep, rather than a migration script or leaving it to the next save | Rows written before the fix are dead for ever and nothing tells the operator to re-save the knowledge base | A one-off migration (needs running, and is skipped on upgrades); re-syncing every knowledge base at startup (delete-then-create races between nodes); a generic sweep over all schedules (wider blast radius than the defect) |
| 2026-09-21 | Arm a legacy row in the zone the poller will use, rather than in UTC or by widening the store's re-arm to carry one | `armIfUnarmed` writes only `nextFire`, so a legacy row's `timeZone` stays null and the poller re-arms it in the deployment default; arming the first fire in UTC regardless would make exactly one interval the wrong length | Adding a zone to the store's re-arm on both backends (CodeRabbit's suggestion — it would also normalise legacy rows to UTC, at the cost of a third field in a predicate that exists to do one thing), leaving the fixed-UTC arm and the drift with it |
| 2026-09-21 | Close the two-node repair race with a conditional `armIfUnarmed` on both stores rather than a re-read | A re-read narrows the window to one store round-trip and still reads a snapshot; the predicate is the only place two nodes meet, and ~20 lines per backend is a small price for a write that cannot skip a fire | A re-read before writing (narrows, does not close), a distributed lock for a startup sweep, leaving the inaccurate idempotency claim in place |
| 2026-09-21 | Changelog entries are per-branch fragment files, collated nightly on main | Every PR inserted at the same point in one file, so every open PR conflicted with every other over a document unrelated to its code | Keep one file and resolve by hand (the conflict returns at the next merge); collate on every push to main (a bot commit per merge, and races between them); let the merge tool own it (no merge driver makes two insertions at one point orderable) |
| 2026-09-21 | The nightly job opens a PR, and skips entirely while one is open | main requires a PR; and a second PR proposing the already-claimed fragments would conflict with the first — the very failure being fixed | Push to main directly (a hole in the PR requirement); force-push the bot branch (banned by §2 rule 4); a new branch per night (two PRs carrying the same entries) |
| 2026-09-21 | Fragments live in docs/changelog.d/, one directory below the live file | Puts all changelog material in one place, and makes a fragment exactly as deep as an archive, so the two link transforms are inverses | A repo-root newsfragments/ (avoids the SUMMARY.md carve-out, splits changelog material across two trees); a subdirectory of docs/changelog/ (collides with the archive naming rule) |
| 2026-09-21 | CI fails a PR that ADDS an entry heading or a dated register row to docs/changelog.md | AGENTS.md prose is what a session follows, but it is not a guard, and 29 PRs were open under the old rule | Detect any change to the file (blocks legitimate header edits and typo fixes in past entries); rely on review to catch it (it is one line at the top of a file nobody reads in a diff) |
| 2026-09-21 | The collation PR prefers a CHANGELOG_BOT_TOKEN, and says so in the PR body when it has none | GitHub does not fire pull_request workflows for GITHUB_TOKEN events, so the PR is unmergeable against required checks until a human reopens it | Use GITHUB_TOKEN and say nothing (a nightly PR that silently cannot merge); require the secret (the job would not run at all until someone provisions it) |
| 2026-09-18 | Keep the v5→v6 conversation rewrite a client-side pass; no skip-if-clean pre-check | Staging: 20 of 24 startup minutes in `migrateEnvironments` over 80 MB | A pre-check was built and removed: a nested legacy URI has no filter form, so proving a collection clean costs the same read. Server-side `updateMany` is the real fix, left as follow-up |
| 2026-09-18 | Default Gemini's `returnThinking`/`sendThinking` to true rather than requiring agent designers to set them | Without both, no Gemini 3.x model can use tools at all — a 400 with no config workaround, not a preference | Leave them opt-in and document it (every Gemini 3.x agent breaks until its author reads the docs); pin them on with no override (removes configurability for no gain) |
| 2026-09-18 | Leave Anthropic and Bedrock `returnThinking` alone; enable it when extended thinking becomes configurable | Both have the identical signed-thinking echo-back requirement and langchain4j models it, but no config key turns the mode on, so the provider returns no signed blocks and the flag is untestable | Set it now anyway — ships a line no test can reach and implies the mode works |
| 2026-09-18 | Warn instead of fixing `gemini-vertex` for Gemini 3.x | Neither `langchain4j-vertex-ai-gemini:1.20.0-beta30` nor the `Part` protobuf (`proto-google-cloud-vertexai-v1:1.27.0`) has a `thought_signature` field — an upstream change plus a dependency bump, not an EDDI fix | Hard-fail the model build (breaks Gemini 3.x agents that use no tools and work today); say nothing (operators meet a bare 400 from inside the provider) |
| 2026-03-05 | Use Astro (not Expo) for website                                      | Static site on GitHub Pages           | Expo would add unnecessary abstraction for a marketing site |
| 2026-03-05 | Use AI complexity scale (🟢/🟡/🔴/⚫) instead of human time estimates | AI will do all implementation work    | Human hours are meaningless for AI execution                |
| 2026-03-05 | Docs already published at docs.labs.ai                                | Third-party tool reads `docs/` folder | Could migrate to Astro Content Collections later            |
| 2026-09-17 | Keep `deny-licenses` in dependency-review, broadened to GPL-2.0, LGPL-2.0/2.1/3.0, SSPL-1.0, BUSL-1.1 and Elastic-2.0 | An allow-list would fail today on the `LicenseRef-bad-non-standard` values GitHub reports for jsoup and classgraph, and would gate nothing extra — unknown licences are informational in both modes | Migrate to `allow-licenses` (needs two permanent per-package exclusions to work around GitHub's normalisation); leave the list at GPL-3.0/AGPL-3.0 (misses the source-available relicensing hazard that actually threatens a project depending on MongoDB and Elasticsearch clients) |
| 2026-09-17 | Mark secret context on the value (`"secret": true`), scrub every copy when the turn ends | A per-user credential sent as context was stored, echoed and copied into properties; `scope: secret` holds one vault slot per agent | A list of secret keys in the agent configuration — couples every agent to one client's field names |
| 2026-09-14 | Connection deployment settings are runtime-writable; a set property pins its value (409 on change) | Properties-only meant a restart per change and protected nothing from `eddi-admin`, who already writes the vault and can send any `${vault:}` value anywhere via an httpcall | Keep properties only (restart, no real protection); store without pinning (removes the operator/admin split for deployments that have one); seed the store from properties (a removed property would be silently replaced by its copy) |
| 2026-09-13 | Block the cloud metadata service on every outbound path, even with `eddi.security.ssrf-protection.enabled=false` | E2E: a config-authored httpcall reached `169.254.169.254` | Flip SSRF protection on by default — breaks every configured internal API |
| 2026-09-13 | Buffer a turn's audit entries and flush them after the pipeline, redacting a vaulted input | E2E: parser/rules entries carried a `scope: secret` plaintext into the append-only ledger | Redact after submission — impossible, entries are signed and immutable |
| 2026-09-13 | Exclude stateful tools from the tool cache by reflecting over their `@Tool` classes | E2E: group members share a user, so `listArtifacts()` was served stale | Make caching opt-in per tool — changes every existing cached tool |
| 2026-09-13 | New group save-time checks (member agentId, negative limits, preset roles, nesting cycles) are hard errors | E2E: all saved fine and failed at run time | Warn only — the invalid configs cannot run as written, and shipped templates pass |
| 2026-09-20 | Escape record boundaries in the throwable's MESSAGE before the trace is rendered, not in the rendered `%s%e` output | `%e` prints `toString()` as the trace's first line, so a CR/LF in an exception message forged a record past every call-site `sanitize(...)` | Scan the rendered trace and keep the breaks that begin `\tat ` / `Caused by:` / `\t... N more` — an attacker can write all three into a message, so the scan has to guess; or drop the throwable at the ~415 call sites — the stack trace is often the only diagnostic left |

---

## Regression Notes

_Track any regressions introduced during implementation for quick debugging._

| Date | Regression | Cause | Fix | Commit |
| ---- | ---------- | ----- | --- | ------ |
| 2026-11-11 | `setup-api/sync` HTTP 500 and MCP "latest version" reads refused with "does not define how its version info is authorized" | REST client proxies run `IRestVersionInfo.getCurrentVersion` (a default method) locally, where the fail-closed `requireCurrentVersionAccess` default refuses | `RestInterfaceFactory` routes `getCurrentVersion` to the `/{id}/currentversion` endpoint over HTTP | `fix/setup-api-sync-current-version` |
| 2026-11-10 | NatsTlsHandshakeTest intermittently CLOSED instead of CONNECTED on CI, failing unrelated PRs; mutual-TLS test quarantined | Test helper shut the manager down in a finally; when jnats' CONNECTED event reached the manager's listener first, shutdown() closed the adopted connection | Managers shut down in @AfterEach; helper waits for the listener to adopt the connection, making the old bug deterministic; mutual-TLS test re-enabled | fix/nats-tls-test-race |
| 2026-11-09 | Every /v1 request with the shared key got 401 + WWW-Authenticate once OIDC was on | The /v1/* permit permission does not stop proactive authentication: the OIDC bearer mechanism verified the opaque key and failed before the policy was consulted | OpenAiPermitModeAuthMechanism ranks above OIDC and returns the anonymous identity for /v1 in permit mode | fix/v1-shared-key-under-oidc |
| 2026-11-06 | A drain could fail with `NODE_FAILED` ("the drain of this node was not recorded") | The target node's scheduled drain-key sync deleted the key the deciding node had just written under the drain gate | Sync reads the key's revision, skips while the gate is held or once the node drains, and deletes only at that revision | fix/cluster-presence-clock |
| 2026-11-06 | An admin opening an agent's chat resumed another user's conversation and sent messages into it as that user | The chat panel picked the newest resumable conversation from a listing that, for an admin, holds every user's | `mine=true` on the listing, used by the chat panel and the operator | fix/conversations-mine-flag |
| 2026-11-04 | `leaseIsHandedToTheNextQueuedTurn` flaky: a queued turn acquired a second lease | `releaseOrHandOver`'s cleanup matched the lease, which every turn of a handoff chain parks again, so a finished turn removed and released the next turn's parking | Park a per-call `Handoff` token compared by identity | fix/lease-handoff-test-flake |
| 2026-11-03 | Hostname-verified NATS TLS refused every certificate issued for a host name | jnats dials and hands TLS the resolved IP, so endpoint identification compared the certificate with an IP | `ServerNameTrustManager` checks the certificate against the configured host names; `NatsConnectionSecurityIT` covers it | fix/cluster-ops-security |
| 2026-11-02 | Anthropic/Bedrock cached prompt tokens and Gemini/Vertex thinking tokens were not priced; `audit:token_usage.inputTokens` now includes cache tokens, so costs and counts rise for cached or thinking workloads | Provider usage conventions differ and only input/output were read | Normalization in the chat-model decorator; escape hatch `eddi.llm.pricing.provider-normalization=false` | feat/cost-accounting |
| 2026-10-20 | Every HITL resume that re-supplied secret context answered 400, so an approved call acting as the user could never run | Both conversation stores rebuilt context entries without the `secret` flag when loading a conversation; the resume check and the injection read it from that memory | Both loaders keep the flag; store round-trip tests for MongoDB and PostgreSQL | (this branch) |
| 2026-10-15 | An embedding that relied on `?token=` stops authenticating | The parameter is now ignored unless `eddi.chat.url-token.enabled=true` (M6); the widget says so in the browser console | Set the property, or move to the `postMessage` hand-off | a136e09 |
| 2026-10-15 | A deployment behind a TLS-terminating proxy with neither `quarkus.http.proxy.proxy-address-forwarding` nor an absolute `quarkus.oidc.resource-metadata.resource` advertises an `http` MCP resource identifier | `force-https-scheme` now defaults to `false` (L3) | Set one of those two, or `force-https-scheme=true` | a136e09 |
| 2026-10-15 | `ExpressionProvider` recursed without bound (StackOverflowError at depth 20,000, ~10 s CPU for 3 MB); the Error escaped task-failure accounting; `saveResponse` without `responseObjectName` failed every turn | No length/depth cap and per-level `substring`; `LifecycleManager` caught only `RuntimeException`; no save-time validation | `ExpressionLimits` + index-based parser, `StackOverflowError` handled as a task failure, `ApiCallsConfigurationValidator` | fix/parser-bounds |
| 2026-10-03 | Dashboards queried series that do not exist (`eddi_nats_dead_letter_count`, `http_server_requests_seconds_bucket`, a `skill` label, `jvm_info`) and read "No data" forever | The coverage test only substring-matched the Full dashboard against registered meters; nothing checked queries against what a scrape contains | Every dashboard parsed; every query's series checked against registered meters plus a verified built-in list | (this branch) |
| 2026-10-03 | GDPR erasure kept SSNs in 6/6 audit rows and reported complete:true; fast erasures reported auditEntriesPseudonymized: 0 | Erasure only rewrote userId, store-side, while entries were still in the write queue | AuditLedgerService.eraseUser flushes, redacts and re-seals; IAuditStore.redactEntry | fix/gdpr-audit-memory |
| 2026-10-03 | `maxToolCallsPerTurn` restarted from zero after a HITL resume | The resumed loop counted only its own trace; the pre-pause trace was merged in afterwards | `ToolLoopResumer` seeds the loop trace with `batch.getTraceSoFar()`; `AgentOrchestratorResumeToolLoopTest.perTurnCapCountsCallsMadeBeforeThePause` | `fix/llm-tool-execution` |
| 2026-09-30 | `maximumLifeTimeOfIdleConversationsInDays=-1` ended every conversation five minutes after boot | `DAYS.between(date, today) >= -1` is always true | A limit below 1 disables idle-ending | fix/650-idle-negotiation |
| 2026-09-29 | EDDI failed to start on this branch (caught by CI E2E before merge) | `LlmModule.configure()` runs twice under CDI and the provider collision guard checked the map it had just filled | Guard against a fixed built-in type set; a test calls `configure()` twice | feat/openai-compatible-providers |
| 2026-09-28 | Manager RAG upload tests failed under Node 24 | undici 7 brand-checks File/Blob; jsdom's global File loses its bytes in FormData and breaks request.formData() | Assert on the multipart part's name and content type, parsed from the raw body | d09d1b120a |
| 2026-09-28 | A live sync after the first one showed the source's plaintext credentials in the preview and wrote them, and the source's vault references, over the target's | `RemoteApiResourceSource` read store documents unscrubbed, and `ScrubbedSecrets` restored only placeholders | Scrub every document the remote source reads; treat vault references as target-bound in the restore | `fix/agent-sync-hardening` |
| 2026-09-28 | Syncing an agent after a workflow was added on the source answered 201 and left an agent version that could not be deployed | `createNewWorkflow` stored the source's workflow document unchanged — its steps named source-only ids — and created none of its resources | Create the workflow's resources first and repoint its steps at them | `fix/agent-sync-hardening` |
| 2026-09-26 | A delete racing an update erased the new version; `set()` on a history version silently did nothing and on the current version could roll back a newer write | Delete removed by id alone; `set()` used insert-if-absent for history and an id-only replace for the current row | Version predicate on delete (tombstone upsert + revert on conflict), CAS for current `set()`, `replaceHistory` for history rows | fix/import-persistence |
| 2026-09-28 | Weekly Slack digest posted (0) for every metric | Actions cache eviction reseeded the week baseline to "now" 8 min before the delayed Monday run | Digests computed row-to-row from the `metrics` branch, sent by any run once due, deduplicated by a claimed marker | fix/weekly-digest-durable-baseline |
| 2026-09-27 | Updating either of two pre-existing duplicate-named channel integrations of the same type now fails validation until one is renamed | New global per-type name uniqueness check on create/update (Finding C) — benign migration: rename one integration | Rename one of the clashing integrations, then update | fix/security-channel-identity |
| 2026-09-26 | Any caller could read, drive and delete a soft-deleted conversation, and read the audit trail of a deleted one | `ConversationAccessGuard` treated a missing live descriptor as "allowed" | Resolve the owner via the archived descriptor, 404 for non-admins without one, soft delete ends the conversation | fix/conversation-access-guard |
| 2026-09-26 | Saving a second Studio stage 409'd; group/workforce edits "reverted"; a quick second agent-section edit vanished | Pages kept the version they were opened with instead of the one in the save's `Location` | Pages move onto the saved version; cascade checks references before writing and reports partial progress for retry | (this branch) |
| 2026-09-26 | Secret 🔒 input persisted as public input:initial and echoed on reload | Only conversationOutput["input"] was masked; input:initial kept the raw text and is always included in the simple snapshot | Scrub input:initial/normalized to the placeholder before persist in Conversation.executeConversationStep's finally | fix/security-frontend |
| 2026-09-26 | Secret input still persisted verbatim inside parser expressions (unknown(<token>)) | scrubSecretUserInput scrubbed only input:initial/normalized; the parser's expressions:parsed/matches/intents keep the normalized secret and a free-text secret matches no dictionary | Also drop the derived parsed forms in scrubSecretUserInput (dropParsedSecretForms), mirroring PropertySetterTask.dropParsedForms | fix/security-frontend |
| 2026-09-26 | Chat ?apiServer= deny-list bypassed by a leading control char, leaking the bearer token off-origin | %09/space prefix escaped the scheme/// /\ tests but the URL parser strips it, resolving ${base}${path} to the attacker origin | Replace with an allow-list requiring a clean single-leading-/ path | fix/security-frontend |
| 2026-09-26 | scope:secret checksum was a brute-forceable unsalted SHA-256 exposed over REST | plain digest of low-entropy user secrets | keyed tenant-bound HMAC checksum, never returned over REST | fix/security-secrets-crypto |
| 2026-09-24 | A task with an empty `knowledgeBases` and no `enableWorkflowRag: true` also retrieves nothing and says nothing — `RagContextProvider.retrieveContext` returns before workflow discovery even runs, so unlike the missing-step cause there is no `DEBUG` line either. Check the task's own RAG settings before checking the workflow binding. |
| 2026-09-22 | Matrix cells: a phase not yet reached must read `pending`, not `absent` | `absent` means "the selector excluded them"; using it for "not yet" told readers a debate's PRO side had gone quiet during a CON-only phase. Guarded by `use-discussion-digest.test.ts` "distinguishes a member excluded from a phase from one still expected". | EDDI Manager |
| 2026-09-22 | Stance coverage must count a member's OWN contributions, not transcript length | Keyed to the transcript, any member speaking invalidated every member's stance: a six-member discussion re-summarised all six at every boundary. Guarded by `StanceSummaryEngineTest` "a member is NOT re-summarized because somebody else spoke". | EDDI |
| 2026-09-22 | A continuation round restarts phaseIndex at 0 — slice at roundStartTranscriptIndex | Without the slice, round 1's turns render in round 2's cells and a round-1 failure marks a round-2 cell failed. Guarded by `use-discussion-digest.test.ts` "does not merge a previous round's turns into this round's phases". | EDDI Manager |
| 2026-09-22 | The live cost map overlays the persisted one, never replaces it | A stream carries only the keys it announced this session; swapping dropped earlier rounds and unspoken members, so Continue on a $4.10 discussion showed $0.02. Guarded by "keeps persisted keys the live stream has not re-announced". | EDDI Manager |
| 2026-09-22 | A LIVE continuation keeps every round in one transcript — slice at the stream's own roundStartIndex | `continueStream` preserves `s.transcript` and `group_start` appends; assuming the live transcript was already round-scoped re-created the cross-round contamination the persisted slice prevents. Guarded by "slices a LIVE continuation at the stream's own boundary". | EDDI Manager |
| 2026-09-22 | The I1 ceiling must be re-checked per member, not once per boundary | Each stance call adds to the ledger, so one decision up front let every member after the first spend past an exhausted budget. | EDDI |
| 2026-09-22 | Overview mode unmounts the transcript, so anything rendered only inside it is GONE | The task board and the synthesised answer were both invisible in Overview until moved into the `extras`/`outcome` bands. Anything added to a transcript renderer in future needs the same question asked. | EDDI Manager |
| 2026-09-22 | A repeating phase must render its repeat count | ROUND_TABLE and DELPHI are built on `repeats`; rendering the phase once made a four-pass deliberation indistinguishable from a single one, in the two styles most groups use. | EDDI Manager |
| 2026-09-22 | A live sync worked once per target agent, then failed permanently with "the store did not accept the update"; the version it did write could not be deployed | `readDescriptor(id, null)` always throws on a historized store, so version resolution fell back to 1 — and nothing moved the `DocumentDescriptor` onto the version each write produced | Resolve through `readCurrentDescriptor`, and bump the descriptor after every write, reporting a resource whose descriptor could not be moved as a failure | `fix/agent-sync-promotion` |
| 2026-09-22 | `invalid_request_error: messages: Field required` on any Anthropic agent with no `ai.labs.output` step | `includeFirstAgentMessage: false` removed message zero whatever its role, emptying a one-turn history | Removal is role-aware; only an `assistant` first message is dropped | (this branch) |
| 2026-09-22 | Every `@RolesAllowed` endpoint 403'd, with an empty body and no log line, for users in a Keycloak group | `quarkus.oidc.roles.role-claim-path` absent from the 6.4.0 image; quarkus-oidc fell back to the `groups` claim | Startup ERROR naming `QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH`, plus a test pinning the property in the source | (this branch) |
| 2026-09-22 | `GET /manage/` answered 200 with an empty body | The empty path normalized to the resource base, whose directory entry is a non-null empty stream | A path that normalizes to nothing serves the SPA shell | (this branch) |
| 2026-09-22 | "Show more" on a long group question could not be undone without reloading | The expanded text was unbounded inside a shrink-0 header, pushing its own toggle out of an overflow-hidden pane | Bound the expanded question and let it scroll in place | (this branch) |
| 2026-09-22 | A group that had held any discussion could never accept an uploaded file again | "New Discussion" cleared the selection and the auto-select effect restored it within a tick, so the attachment control never rendered | An explicit-clear ref the auto-select effect honours | (this branch) |
| 2026-09-22 | Manager pages hung on skeleton loaders while the backend was healthy | A boot-time SSE log stream per tab saturated Chrome's six-connections-per-origin cap | The stream is opened lazily by its consumers and refcounted | (this branch) |
| 2026-09-22 | A saved config edit silently did not take effect | A plain Save cascades resource → workflow → agent but never deploys, and reported plain success | The toast says "not yet live" and offers a Deploy action | (this branch) |
| 2026-09-21 | A RAG setup with a correct KB config and a correct `knowledgeBases` reference but no `eddi://ai.labs.rag` workflow step retrieves nothing, and says nothing: no context, no `rag:trace:*`, no error, and the only log is `DEBUG` "No RAG steps found in workflow". Check the workflow step first, and `GET /extensionstore/extensions` before that on older builds. |
| 2026-09-21 | `POST /ragstore/rags/{id}/ingest` accepts a `kbId` that overrides the embedding-store key, but retrieval always keys on the KB's `name` and cannot be redirected. A `kbId` that is not exactly the `name` ingests into a store nothing reads, reporting `202` then `completed` the whole way. Leave `kbId` unset. Ingestion sources are unaffected. |
| 2026-09-21 | A RAG ingestion source with a cron was stored looking enabled and never fired; a ZIP could store a cron the REST API refuses; run reports named a null source; `docs/rag.md` overstated defaults; a Manager test asserted nothing | All five were raised in review on PR #790 and the PR was **merged with those threads unresolved** — the review caught them, the merge did not wait for them | `nextFire` computed in `buildSchedule` plus a repeatable startup repair for existing rows; cron validation shared between the REST and import paths; `effectiveId()` at all six report sites; docs corrected against the code; the Manager test rewritten to serve a saved-disabled source so no dirty-state guard can mask it | (this branch) |
