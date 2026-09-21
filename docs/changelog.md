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

**Add new entries directly below the `---` that closes this section**, above the
most recent existing entry. Never append to an archive file.

This file holds only recent work and is capped at **250 KB** —
`ChangelogRotationTest` fails the build if it grows past that. When it does, run:

```bash
python scripts/rotate-changelog.py
```

It moves the oldest entries into `docs/changelog/<YYYY-MM>.md` by the date each
entry carries, adds one `../` to the relative links it moves (an archive sits a
directory deeper than this file) without touching the ones inside code spans, and
regenerates the Archive table below from what is on disk. Add any newly created
archive file to [`SUMMARY.md`](SUMMARY.md). Do not raise the cap.

The single file this replaced had reached 1.9 MB — roughly half a million tokens —
which neither a reader nor an agent's context window could usefully hold.

## Archive

| Period | Entries | Size |
|---|---|---|
| [September 2026](changelog/2026-09.md) | 16 | 60 KB |
| [August 2026](changelog/2026-08.md) | 211 | 832 KB |
| [July 2026](changelog/2026-07.md) | 147 | 648 KB |
| [June 2026](changelog/2026-06.md) | 26 | 67 KB |
| [May 2026](changelog/2026-05.md) | 34 | 76 KB |
| [April 2026](changelog/2026-04.md) | 104 | 220 KB |
| [March 2026](changelog/2026-03.md) | 59 | 183 KB |

The two running registers — **Decision Log** and **Regression Notes** — live at the
bottom of this file and are never archived.

---

## 🛡️ fix(security): validate the token audience, and stop calling userinfo on every request (2026-09-20)

**Repo:** EDDI (`feat/oidc-audience-validation`, stacked on `feat/keycloak-mcp-client`)

Increment 2 of [`planning/mcp-oauth-protected-resource-plan.md`](../planning/mcp-oauth-protected-resource-plan.md),
kept separate because it changes which tokens are accepted — every token, not only MCP ones.

Quarkus verifies `aud` on an **access** token only when `quarkus.oidc.token.audience` is set
(`OidcIdentityProvider` passes `enforceAudienceVerification = idToken`, and with the property
unset `OidcProvider` calls `setSkipDefaultAudienceValidation()`). It was unset, so EDDI accepted
any token the realm issued for any client in it — and roles come from `realm_access/roles`,
which is client-independent, so such a token arrived carrying the user's full rights. The
`eddi-backend-audience` mapper that has been on `eddi-frontend` all along is evidence someone
intended this check and never switched it on. It matters more now that a second client exists.

### What changed

- **`application.properties`** — `quarkus.oidc.token.audience=eddi-backend`.
- **`application.properties`** — `quarkus.oidc.token-cache.{max-size=1000,time-to-live=3M,clean-up-timer-interval=5M}`.
  `user-info-required=true` means one Keycloak round trip per request, which an MCP client makes
  many of. Signature, expiry and audience are still checked per request, before the cache is
  consulted at all. What it defers is the userinfo call, which doubles as a session-revocation
  check (Keycloak refuses userinfo for a logged-out session) — so a killed session keeps working
  for up to the TTL. That, not token expiry, is why the TTL is short.
- **`DeploymentManifestsTest`** — every client that can mint a token (standard, direct-grant,
  implicit or service-account flow) must mint the audience the property requires, in all three realm copies, compared
  against the property rather than a spelling repeated in the test.
- **`ui/manager/e2e/auth/mcp-oauth.spec.ts`** (new) — the middle of the feature, which the
  discovery and 401 cases do not reach: authorization code + PKCE against `eddi-mcp`, the
  token exchange, then `/mcp` `initialize` and a real `list_agents` call. It asserts the
  token carries `aud=eddi-backend` and realm roles, so the two silent failures — a token the
  backend refuses, and one that authenticates and is then refused by every tool — surface as
  themselves rather than as a generic 401.
- **`docs/security.md`**, **`docs/open-webui-integration.md`** — both properties in the table, what
  a hand-built realm has to do, and the `/v1` adapter's 401-under-OIDC entry, which now also means
  "and carrying `aud=eddi-backend`".

### Compatibility

**This rejects tokens that were accepted before.** A deployment whose users authenticate through
a client *without* an audience mapper starts answering 401. The shipped realm is unaffected
(both login clients carry the mapper). The fix for a custom realm is to add the mapper; the
escape hatch is `QUARKUS_OIDC_TOKEN_AUDIENCE=any` — quarkus-oidc's own sentinel for skipping
audience validation (`OidcProvider.ANY_AUDIENCE`), which restores the old behaviour.

### Files

- `src/main/resources/application.properties`
- `src/test/java/ai/labs/eddi/deploy/DeploymentManifestsTest.java`
- `docs/security.md`, `docs/open-webui-integration.md`

---

## 🔑 feat(keycloak): ship an `eddi-mcp` client for MCP clients to log in through (2026-09-20)

**Repo:** EDDI (`feat/keycloak-mcp-client`, stacked on `feat/mcp-oauth-discovery`)

Increment 1 of [`planning/mcp-oauth-protected-resource-plan.md`](../planning/mcp-oauth-protected-resource-plan.md),
second half. The previous entry made EDDI tell a client *where* to authenticate; this gives
it something to authenticate as.

### What changed

- **All three realm copies** (`keycloak/`, `helm/eddi/files/`, `k8s/overlays/auth/`) gain
  `eddi-mcp`: public, authorization code + PKCE `S256` required, direct access grant / implicit
  / service accounts all off, redirect URIs `http://localhost:*` and `http://127.0.0.1:*`, no
  web origins, and the `realm-roles`, `eddi-backend-audience` and `groups` protocol mappers
  copied from `eddi-frontend`.
- **`DeploymentManifestsTest`** — a case per realm copy asserting the flow settings, the PKCE
  requirement, the mappers (by claim name and by *access* token, not just id token), that no
  redirect is `*` or a remote http URL, that `webOrigins` is empty, and that neither `name` nor
  `description` exceeds 255 characters: Keycloak stores them in `VARCHAR(255)` and an over-long
  value does not truncate — **the realm import fails and Keycloak exits 1**, which is how the
  first draft of this client took down every stack that imports the realm. Found by running the
  import, not by reading the file.
- **`helm/eddi/templates/NOTES.txt`**, **`k8s/overlays/auth/kustomization.yaml`**,
  **`docs/security.md`** — every place that told an operator to grant an account "those two
  roles" now names all three. Following the old instruction built an administrator that logs in
  and is refused every MCP read tool, which is the trap the realm change exists to close.
- **`.github/workflows/ci.yml`** — `keycloak/**` added to the `code` and `backend` path
  filters. `k8s/` and `helm/` were already there, so the compose realm was the one copy whose
  change ran no CI — including the audience mapper every accepted token depends on.
- **`docs/mcp-server.md`**, **`docs/security.md`** — the client, how to point a client at it,
  why dynamic registration is not an option here, and what to do on an **existing** realm:
  `--import-realm` never re-imports into a realm that already exists and both auth stacks keep
  Keycloak's database in a named volume, so an upgrade leaves the client absent and the flow
  ends in `invalid_client`. The manual steps are listed, `realm-roles` first.

- **All three realm copies** — the seeded `eddi` administrator gains `eddi-viewer` alongside
  `eddi-admin`/`eddi-editor`. There is no role hierarchy, so without it the account an operator
  points their first MCP client at completes the login and is then refused all 27 viewer-gated tools.
  A test pins it. `scripts/make-test-realm.mjs` guards that fixture set against the realm and
  fails the auth E2E run when the two drift, so `ROLE_FIXTURES` and `e2e/auth/auth-helpers.ts`
  move with it — which is how CI caught this change the first time it ran.

### Decisions

- **Pre-registered client, not dynamic registration.** Not a preference: this realm supplies its
  own `clientScopes` and defines no `roles` scope, so `realm_access.roles` comes only from a
  client's own protocol mapper. RFC 7591 registration carries no mappers, so a self-registered
  client would mint tokens that authenticate and then fail every tool with "requires role" —
  login succeeded, everything forbidden. Keycloak's default registration policies would also
  have to be loosened in at least three places to get there.
- **Loopback redirects only; `https://claude.ai/api/mcp/auth_callback` is not shipped.** Claude
  Desktop connectors redirect to that remote callback, so the authorization response for a
  self-hosted EDDI would pass through a third party. That is an operator's decision, documented
  in `docs/mcp-server.md`, rather than a default inherited from us.
- **No `webOrigins`, not even `+`.** These clients are native processes; `eddi-frontend` needs
  browser origins and this one never makes a browser request.
- **The redirect list is the one `[ext]` assumption in the plan.** Which loopback path each
  client uses is documented client behaviour rather than something verified here, so the entries
  are the broad `localhost` / `127.0.0.1` wildcards the realm already uses for the SPA, and the
  docs say to add anything else in the admin console.

### Files

- `keycloak/eddi-realm.json`, `helm/eddi/files/eddi-realm.json`, `k8s/overlays/auth/eddi-realm.json`
- `src/test/java/ai/labs/eddi/deploy/DeploymentManifestsTest.java`
- `docs/mcp-server.md`, `docs/security.md`

---

## 🔐 feat(mcp): advertise `/mcp` as an OAuth protected resource, so clients sign themselves in (2026-09-20)

**Repo:** EDDI (`feat/mcp-oauth-discovery`, stacked on `docs/mcp-oauth-plan`)

Increment 1 of [`planning/mcp-oauth-protected-resource-plan.md`](../planning/mcp-oauth-protected-resource-plan.md).
An MCP client now discovers where to authenticate and holds its own token, instead of an
operator pasting a bearer that expires in five minutes.

Quarkus OIDC 3.39.3 already serves the RFC 9728 document and appends `resource_metadata="…"`
to the 401 challenge, so there is no new EDDI code — five properties and one permit rule.

### What changed

- **`application.properties`** — `quarkus.oidc.resource-metadata.*`: `enabled` tracks
  `tenant-enabled` (an instance with auth off has no authorization server to name, and the
  handler is not installed for a disabled tenant), `resource=/mcp`, `force-https-scheme=true`,
  `scopes=openid`, and `authorization-server` preferring `token.issuer` over `auth-server-url`.
- **`application.properties`** — a `permit` rule for the two exact metadata paths (the bare
  form and the path-inserted document), `GET,HEAD` only. Exact rather than a `/*` under the
  prefix, which would anonymously expose any future handler beneath it.
- **`helm/eddi`** — `eddi.oidc.resourceMetadata.{forceHttpsScheme,authorizationServer}`, because
  neither is safely inferable: `publicUrl` describes Keycloak, not EDDI, so an https IdP in
  front of a plain-http port-forward would advertise a resource nothing serves. The scheme now
  follows EDDI's own `ingress.tls` unless set. Chart version bumped per Chart.yaml's rule.
- **`application.properties`** — the MCP security banner said 33 tools (there are 84) and
  described a two-role model (there are four, with no hierarchy).
- **`McpOAuthDiscoveryConfigTest`** (new, 8 cases) — the config *is* the feature, so it is what
  gets asserted: the enabled expression, the resource matching the MCP root path, the issuer
  preference, `openid` while `user-info-required` is on, the permit rule's policy/methods/paths,
  what those paths match and do not match, and `/mcp` still being `authenticated`.
- **`ui/manager/e2e/auth/auth.spec.ts`** — two cases in the Keycloak tier: the document is
  readable with no token and names the issuer a real token carries; an unauthenticated `/mcp`
  POST answers 401 with a challenge pointing at it.
- **Every shipped stack that serves plain http with authentication on** overrides
  `force-https-scheme`, because that is the one shape a forced https identifier is wrong for:
  `docker-compose.auth.yml`, the auth E2E tier, `k8s/overlays/auth` (its documented flow is
  `kubectl port-forward`), and the helm chart whenever the Keycloak URL it is given is itself
  plain http. Without it those deployments advertise `https://…/mcp` with nothing serving TLS,
  and discovery dies before it starts.
- **`ui/manager/docker-compose.integration-keycloak.yml`** — the tier now pins the
  browser-reachable issuer the way `docker-compose.auth.yml` does, so the discovery document it
  publishes is the one a real deployment publishes. The E2E case can therefore assert the
  advertised authorization server is reachable **from outside the compose network** — with the
  old in-cluster hostname that assertion could not have failed.
- **`docs/mcp-server.md`**, **`docs/security.md`** — the discovery path as the preferred way in,
  with the hand-pasted token demoted to a fallback; the new permit row, and why `@PermitAll`
  alone does not make a path public.

### Decisions

- **The permit rule is mandatory, not defence in depth.** quarkus-oidc registers its handler as
  `FilterBuildItem(handler, 50)` and `SecurityHandlerPriorities.AUTHORIZATION` is 100 — higher
  runs first, so authorization would answer 401 before the document could be read, and
  discovery could never start. Verified by `javap` on `OidcBuildStep`, and asserted over HTTP
  in the auth E2E tier because no properties file can prove an ordering.
- **Exact paths, never `/.well-known/*`.** A wildcard there would pre-permit whatever lands
  under that prefix later. A test asserts what the patterns match *and* what they must not.
- **`authorization-server` defaults to `token.issuer`.** `auth-server-url` is the
  cluster-internal Keycloak address in both shipped deployments, so the default would have
  advertised a host no client outside the cluster can resolve. RFC 8414 wants the advertised
  server to equal the issuer regardless, and the E2E assertion compares it against the `iss`
  claim of an accepted token rather than a hardcoded URL.
- **`openid` is advertised deliberately.** `user-info-required=true` makes EDDI call userinfo
  on every request, and Keycloak refuses userinfo for a token minted without that scope;
  clients copy `scopes_supported` into the authorize request. A test pins the pair together so
  removing one surfaces the other.
- **https is forced by default.** `quarkus.http.proxy.*` is unset, so behind a TLS-terminating
  ingress the identifier would be advertised as `http://`.

### Files

- `src/main/resources/application.properties`
- `src/test/java/ai/labs/eddi/configs/McpOAuthDiscoveryConfigTest.java` (new)
- `ui/manager/e2e/auth/auth.spec.ts`, `ui/manager/docker-compose.integration-keycloak.yml`
- `docs/mcp-server.md`, `docs/security.md`

---

## 📝 docs(mcp): how to reach an authenticated `/mcp`, and the plan to stop needing this (2026-09-20)

**Repo:** EDDI (`docs/mcp-oauth-plan`)

A local MCP client — Claude Desktop, Claude Code, Cursor, LM Studio — cannot practically
manage an EDDI instance that has OIDC enabled. `/mcp` carries an `authenticated` policy
(its own `quarkus.http.auth.permission.mcp` rule), EDDI is bearer-only (`application-type=service`), and it
advertises no OAuth metadata, so a client that would log in by itself gets a bare 401 with
nothing to discover. The only way in is a hand-pasted token that the shipped realm lets
expire after Keycloak's default five minutes, and there is no long-lived key for `/mcp`
(the only api-key surface is the `/v1` adapter).

The Quick Start in `docs/mcp-server.md` only ever showed the unauthenticated
`localhost:7070` case, so nothing said any of that.

### What changed

- **`docs/mcp-server.md`** — new *Connecting to an authenticated instance* section under
  Authentication & Authorization: get a token from the public `eddi-frontend` client, pass
  it either as a header on a Streamable-HTTP client or through `mcp-remote` (whose argument
  splitting means the value belongs in an env var), and four caveats in the order they
  bite — expiry, no api key, roles decide which tools work, and `/mcp` cannot be opened
  selectively. The Quick Start now points at it.
- **`docs/mcp-server.md`** — the Configuration block documented `quarkus.mcp-server.http.root-path`.
  That hyphenated form is not a key the extension knows; `application.properties`
  already says so. Corrected to `quarkus.mcp.server.http.root-path` with the warning kept.
- **`planning/mcp-oauth-protected-resource-plan.md`** (new) — the fix: advertise `/mcp` as an
  RFC 9728 protected resource so the client runs the OAuth flow and refreshes its own token,
  removing the shared long-lived credential rather than automating its rotation.

### Decisions

- **Rotation is the wrong problem to solve.** The instinct is to reuse **Connections**, which
  already does lazy OAuth refresh with a single-flight claim. It cannot apply: a connection
  resolves to a header on a request *EDDI originates*, and here EDDI is the callee. Connections
  exists because EDDI holds a credential it must refresh; inbound, the client holds it.
- **Serving the metadata is configuration, not code.** Quarkus OIDC 3.39.3 already ships
  `ResourceMetadataHandler` and appends `resource_metadata="…"` to the 401 challenge. The
  plan's Increment 1 is four properties, a permit rule and a Keycloak client.
- **A permit rule is mandatory, not a precaution.** That handler registers as
  `FilterBuildItem(handler, 50)`, and `SecurityHandlerPriorities.AUTHORIZATION` is 100 — it
  runs *after* authorization, so the catch-all at `/*` would 401 the discovery document and
  the flow could never start.
- **Pre-registered client over dynamic registration.** The realm defines no `roles` client
  scope; `eddi-frontend` gets `realm_access.roles` only from its own protocol mapper. A
  dynamically registered client cannot carry mappers, so its tokens authenticate and then
  fail every tool with "requires role" — the worst failure shape available.
- **The EDDI → client direction is deliberately out of scope** and recorded as such in the
  plan, so it is not re-derived: it needs Claude Code channels rather than MCP, and two
  design answers first — attribution (nothing reads the token's `azp`, so a model answering
  a HUMAN member's turn is recorded as the person) and keeping HITL decisions out of an
  AI client's reach.

### Files

- `docs/mcp-server.md`
- `planning/mcp-oauth-protected-resource-plan.md` (new)

---

## ⏱️ fix(schedule): close the review round and pin the guards by mutation (2026-09-04)

**Repo:** EDDI (`fix/review-schedules`)

Follow-up on the same branch, from three independent review rounds plus a diff-coverage pass.

**Two CI failures this branch caused are fixed.** `ImportStyleTest` was red because the branch
introduced two inline fully-qualified names — the exact convention that test enforces — in
`RestScheduleStoreTest` and `MongoScheduleStoreTest`. And the vendored fuzz sources drifted
because a Javadoc reformat of `PathNavigator` diverged from the copy `.clusterfuzzlite`
vendors; the cosmetic edit is reverted rather than re-syncing the vendored file, keeping the
diff to what the findings required.

**Tests that could not fail were replaced.** Five were proven vacuous by mutation, not by
inspection. Two `WordSplitter` cases never reached the bounds guard they claimed to pin — one
used an input whose index made the new `i > 0 &&` term unreachable. A `MongoScheduleStore` test
asserted `!rendered.contains("triggerType=CRON")` on a `Bson.toString()` where that string can
never appear, so it was unconditionally true; it now encodes through the real codec registry
and asserts BSON null for an absent trigger type and the value for a present one, catching both
an invented default and a hardcoded null.

Two further claims were **disputed with evidence and left alone**: their "changed" line was a
rename from an inline FQN to an import, mandated by AGENTS.md 4.7. No test can fail on the
revert of a rename, so the correct remedy is to drop the line from the coverage claim, not the
test from the suite — and both were shown to kill real mutants first.

**Diff coverage** of changed lines: 94.4% to 99.2% line, 89.3% to 98.2% branch.

---

## ⏰ fix(schedule): correct fire bookkeeping, persistence and manual-fire claiming (2026-09-04)

**Repo:** EDDI (`fix/review-schedules`)

From the whole-repository code review. Scheduled fires were reporting success they had
not earned, and losing state they had been given.

**PostgreSQL lost the payload entirely.** `eddi_schedules` had no column for `message` —
the text a CRON schedule sends to the agent, which `RestScheduleStore` makes mandatory on
save — nor for `time_zone`, `one_time_at`, `environment`, `agent_version`, `created_by` or
`persistent_conversation_id`. The value was written, silently dropped, read back null, and
the scheduled turn ran with **null input**. Scheduling is enabled by default and PostgreSQL
is a documented, supported backend. The columns are added with
`ADD COLUMN IF NOT EXISTS` statements so existing databases upgrade in place, and the
dropped `persistent_conversation_id` was separately re-opening the CAS claim on every
heartbeat fire, breaking the single-owner CAS claim that keeps a fire from running twice.
(The delivery contract is at-least-once, not exactly-once — `IScheduleStore`,
`docs/scheduling.md` and `docs/hitl.md` all say so. An earlier draft of this entry claimed
otherwise.)

**Failures were recorded as successes.** The executor read its outcome from a latch that
counts down on the failure branch too, so an error inside the pipeline looked like a green
fire: retry, backoff and dead-lettering never engaged, and `docs/scheduling.md` documents a
state machine that could not be reached.

**Persistent fires un-claimed themselves mid-flight.** The strategy wrote the pre-claim
schedule back with `replaceOne`, so the poller re-claimed and re-fired a schedule that was
still running, routing both turns into the *same* persistent conversation — two interleaved
turns, two cost charges, one memory.

**Heartbeats drifted.** The next fire re-anchored on the moment a turn *finished* rather
than when it was *due*, so a 40-second turn on a 60-second cadence actually fired every 100
seconds.

**A manual "fire now" took no cluster claim at all**, so it could run concurrently with the
poller's own fire of the same schedule.

Also: `PUT /schedulestore/schedules/{id}` silently erased `createdAt`, `createdBy`,
`lastFired` and the claim state on MongoDB (PostgreSQL preserved them — a parity gap in the
same feature), and `CronDescriber` rejected day-of-week `7`, which `CronParser.validate`
accepts, so a valid stored schedule 400'd on read.

### Regression coverage

Every behavioural change is pinned by a test proven to fail with its fix reverted. Four
tests that the auditor found could pass with the fix removed were rewritten to assert the
corrected value precisely rather than a property the buggy code also satisfied — one had
asserted only that the next fire time lies in the future, which the drifting formula did too.

Three of this repository's own guard tests were failing and are now satisfied properly
rather than relaxed: the three new `eddi.schedule.*` properties are documented in
`docs/configuration-reference.md`, and the new `eddi.schedule.firelog.pruned` counter is
both documented in `docs/metrics.md` and charted in the Grafana dashboard, because
`MetricsDashboardCoverageTest` requires both.

Recorded honestly as unverifiable locally: the `SafeHttpClient` redirect tests need a
loopback socket, and the new DDL and Mongo codec paths are only exercised against real
backends in CI.

---


## 🔒 fix(security): close the CWE-117 gap in the half of a log line no call site can reach (2026-09-20)

**Repo:** EDDI (`fix/log-injection-record-boundary-handler`)

`LogSanitizer.sanitize(...)` at a call site only ever covered the log **message**.
`quarkus.log.console.format` ends in `%s%e`, and `%e` renders a stack trace whose FIRST
line is the throwable's own `toString()` — `ClassName: message`. So an attacker-controlled
CR/LF inside an **exception message** reached the console verbatim and forged a record that
reads as a genuine, server-authored line, no matter how carefully the message half was
sanitized. 412 log calls in `src/main/java` pass a throwable (244 as a trailing argument,
168 as JBoss `*f(e, …)`), and none of them could fix this themselves.

Dropping the throwable at those call sites was never the trade: `RestAgentAdministration`'s
deploy-failed WARN tells the client only *"Deployment failed. Check server logs for
details."*, so the stack trace is the sole diagnostic a failed deployment leaves.

### What changed

- **`LogSanitizer.escapeRecordBoundaries(String)`** — a second, record-level rule beside the
  existing call-site `sanitize(...)`. It escapes rather than destroys: CR → `\r`, LF → `\n`,
  U+2028/U+2029 and every other ISO control character → `\uXXXX`, TAB kept verbatim. Returns
  the same instance when nothing needs escaping, and `null` for `null` (unlike `sanitize`,
  which renders `null` as the string `"null"` — doing that to a throwable's message would turn
  a printed `java.io.IOException` into `java.io.IOException: null`).
- **`LogRecordRedactor`** now applies both rules in one pass: `SecretRedactionFilter.redact`
  then `escapeRecordBoundaries`, to the record's formatted message and to every message in its
  throwable graph (causes and suppressed included). `RedactedThrowable.of` takes the message
  rewrite as a `UnaryOperator<String>` so one walk of the graph applies both rules instead of
  nesting one stand-in inside another.
- **`BoundedLogStore.capture`**'s own fallback path (used when the upstream pass threw) applies
  the same `LogRecordRedactor.rewrite`, so the ring buffer, the DB and the SSE live tail agree
  with the console.
- **Two log calls that this change would otherwise have made uglier**: the `\n` in
  `ConversationStepRunner`'s "Conversation not ready" ERROR became `": "` (the throwable is
  passed too, so `%e` prints the trace anyway), and `ApiCallExecutor`'s trailing `\n` on the
  execution-time INFO is gone (the pattern already ends in `%n`). They were the only two
  deliberately multi-line log messages in `src/main/java`.

### Design decision — escape the throwable's MESSAGE, not the rendered trace

The obvious reading of "sanitize the rendered `%s%e`" is to scan the finished stack trace and
escape the line breaks that do not begin a genuine continuation line (`\tat `, `Caused by:`,
`\t... N more`). **Rejected**: those three prefixes are also three strings an attacker can put
in an exception message, so such a scan has to decide which `Caused by:` is the JVM's and which
is the payload, and it has no way to know.

There is no need to guess. In a rendered trace the only text an attacker reaches is the
`toString()` of each throwable in the graph; every other line is generated by the JDK from the
`StackTraceElement` array. So EDDI escapes the messages *before* the trace is rendered, by
substituting a copy of the throwable, and lets the JDK produce the structure from clean input.
Nothing is parsed, nothing is guessed, and `LogRecordBoundaryForgeryTest` asserts the frames,
the `Caused by:` and the `... N more` elision come out identical to what the original threw.

Two further choices worth stating: **TAB is kept** (it cannot end a record, and it is what
indents `\tat …`), and **a backslash is not doubled** — the escaping is therefore not injective,
which is a cosmetic ambiguity rather than a forgery, and the alternative doubles every backslash
in the Windows paths and regexes exception messages are full of.

It is also a rewrite of the record rather than a new console formatter, matching the reasoning
already recorded in `LogRecordRedactor`: one definition of "what goes out" for every destination.
The filter is wired to the console handler alone via
`quarkus.log.console.filter=eddi-log-capture`; a file or syslog handler would need the same
filter, and the test below fails if that property or the `%s%e%n` format moves out from under
the claim.

### Tests

New `LogRecordBoundaryForgeryTest` (10 tests) asserts on **rendered** output — a real
`PatternFormatter` built from the pattern read out of `src/main/resources/application.properties`
— because `LogCaptureSupport.captureLogsOf` reads `getMessage()`/`getParameters()` but not
`getThrown()` and so cannot see this defect at all. Its shared invariant: after the first, every
line of a rendered record must be a continuation the JDK generated. Covers the exception message,
a cause, a suppressed exception, U+2028, the message half, a format parameter, plus "a clean
record renders byte-for-byte as before and keeps its throwable" and the config guard.
`LogSanitizerTest` gains 8 cases for the new method.

**Mutation-checked.** Removing the escaping entirely fails 7 of 10 (the 3 survivors are the
must-not-change tests). Escaping the message but not the throwable fails exactly the 5
throwable-half tests — so none of them pass on the strength of the message fix.

### And the message-level alerts, folded in

The handler above stops any of these forging a record at *runtime*, but CodeQL's
`java/log-injection` is a dataflow rule and keeps flagging the call site regardless — and if
the filter is ever detached from a handler, the call site is what is left. So the same branch
also applies the ordinary one-line `LogSanitizer.sanitize(...)` to **38 sinks across the eight
files** the alerts name:

| File | Sinks | The tainted arguments |
|---|---|---|
| `GroupHitlCoordinator` | 16 | `gc.getId()`, `gc.getGroupId()`, `groupConversationId`, `entry.getKey()`, `e.getMessage()` |
| `GroupConversationService` | 11 | `gc.getId()`, `gc.getGroupId()`, `phase.name()`, `outcome.reason()` |
| `MemberTurnExecutor` | 3 | `member.agentId()`, `gc.getId()`, `gc.getGroupId()`, `subGroupId` |
| `ConversationHitlService` | 3 | `conversationId` |
| `PhaseExecutionEngine` | 2 | `gc.getId()`, `phase.name()`, `decision.outcome()` |
| `AuditLedgerService` | 1 | `entry.agentId()`, `e.getMessage()` |
| `AgentGroupStore` | 1 | `groupConfiguration.getName()`, the phase name |
| `SlackGroupDiscussionListener` | 1 | `groupConversationId`, `e.getMessage()` |

Only String-typed arguments are wrapped; the enums, `Instant`s and counters in the same calls
are left alone. `MemberTurnExecutor` and `SlackGroupDiscussionListener` gained the import; each
of the other six already had it, and each call follows the style its own file already used
(qualified `LogSanitizer.sanitize` in six, the static import in `ConversationHitlService` and
`AuditLedgerService`).

The alert list was resolved through `gh api`, not read off `main` at HEAD: a CodeQL alert's
line number is relative to `most_recent_instance.commit_sha`. Two of the 41 reported alerts
turned out to be stale against an older sha — one line had already been sanitized, the other no
longer exists — which is how 41 became 38. The eight files carry a further ~70 log arguments of
the same shape that CodeQL has *not* flagged, overwhelmingly `e.getMessage()`; those are left
alone, because sanitizing them is a codebase-wide policy question and not this PR's.

**Tests.** `SanitizedLogSinksTest` pins all 38 at the source: each is keyed by a fragment of its
own message rather than a line number, and every flagged argument must occur only inside a
`sanitize(...)`. Dropping one fails the build with the file, the message and the expression
named. `GroupHitlCoordinatorLogInjectionTest` covers the two sinks reachable through a public
method with one mock — the forged-id and the forged-exception-message halves — in the
`LogCaptureSupport` idiom the earlier regression tests established. Both mutation-checked.

A source guard rather than 38 behavioural tests is a deliberate call and is argued in the test's
own Javadoc: the rest sit inside a phase loop or a state-race `catch` that takes a whole group
discussion to reach, and a test that builds one to observe a single WARN grades the harness more
than the fix.

### What's next

- `RestAgentAdministration`'s deploy-failed WARN carries a comment on branch
  `fix/log-injection-agent-deployment-logs` (#799) explaining that the throwable cannot be
  sanitized and that only a log handler can fix it. That branch is not merged, so the comment
  does not exist on `main` and could not be updated here: **whichever of the two lands second
  must update it** to say the handler now exists.
- 110 further `java/log-injection` alerts remain open on `main` in files this PR does not touch —
  `RestScheduleStore`, `RestUserMemoryStore`, `VaultSecretProvider`, the REST stores and others.
  `RestAgentAdministration` and `AgentFactory` among them are PR #799's scope and were left to it.
  None of them can forge a record at runtime now, so they are alert hygiene rather than exposure.

---

## 🕷️ feat(ingestion): web crawler — streaming, bounded, robots-aware (2026-09-17)

**Repo:** EDDI (`feat/ingestion-web-crawler`)

### Why a rewrite rather than a patch

The crawler salvaged from PR #529 was competently written but wrong in shape: it buffered every page's
full HTML in a `List` and returned it when the crawl finished, identified pages by the URL *requested*
rather than the one reached, read every body with an unbounded `ofString()` **before** checking its
Content-Type, and had no run budget. None of that is patchable without touching every line.

It also had no `robots.txt` at all. EDDI installations crawl sites their operators do not own, on a
schedule — ignoring robots gets the installation blocked and its operator a complaint.

### What replaces it

- **`WebCrawler`** — BFS, streaming to a `CrawlSink` one page at a time, so memory is independent of the
  site's size. Budgets for pages, fetch attempts, bytes per page, total bytes and wall clock, each
  reported as a `StopReason`. Cancellation checked between pages.
- **`CrawlUrls`** — canonicalization. Lowercases scheme and host **but not the path**: the draft
  lowercased the whole URL, so `/Docs/Guide` and `/docs/guide` collapsed into one entry and whichever
  came second was silently never crawled. Also strips fragments, default ports, tracking parameters and
  index filenames, and sorts query parameters, so one page is not ingested three times.
- **`UrlPattern`** — exclude globs matched against the **path**, with every metacharacter escaped and
  compiled once. Two defects fixed: the documented `*.pdf` could never match anything (`*` cannot cross
  the slashes in `https://host/`), and a pattern containing `+` or `(` threw `PatternSyntaxException`
  inside the crawl loop, where a blanket catch logged it as a *fetch* error and dropped the current
  page's links — one bad pattern reduced a crawl to its seed URL.
- **`RobotsPolicy`** — groups, longest-match `Allow`/`Disallow`, `*`/`$`, `Crawl-delay` and `Sitemap`.
  Blank lines deliberately do not end a group: real files are full of them, and orphaning a group's
  rules silently allows everything the site meant to block.
- **`PageFetcher`/`SafeHttpPageFetcher`** — `sendValidated` per request (the crawler follows links
  harvested from third-party pages, which is as user-controlled as a URL gets), with a hard cap on the
  body read and charset taken from the header or sniffed from the document. Assuming UTF-8 turns legacy
  pages into mojibake, and mojibake embeds without complaint.

Identity is the URL after redirects, re-checked against the scope: a 301 to another host satisfied
`sameSiteOnly` on the pre-redirect host and smuggled a foreign page into the knowledge base.
`<link rel="canonical">` is honoured, but only when it stays on the same host.

Sitemaps from robots.txt are crawled without needing a link — the cheapest discovery there is, and the
mitigation for the one cost of conditional requests: a 304 has no body, so an unchanged page's links are
not re-read that run.

### Tests

**113 unit tests, no network, no container, no test server.** The `PageFetcher` seam is there for exactly
this: `FakeSite` serves an in-memory website, so scope decisions, budgets, redirect identity, robots,
conditional requests, charset handling and error accounting all run in the unit gate. The draft's only
coverage was one Testcontainers test the unit run does not execute, which is why none of these defects
were caught.

Three of the five failures on the first run were real bugs the tests found, not test bugs: sitemap URLs
bypassed the scope check, `https://host/` and `https://host` canonicalized differently, and fetching the
canonicalized form invented URLs the site never published (the crawler now fetches the address as
published and uses the canonical form only as identity).

Mutation-checked: reverting the final-URL identity and re-lowercasing the path fails four tests.

### Next

The source configuration and the pipeline that ties crawl → convert → state store → embed, with vector
removal driven by the tombstone list, plus the Manager UI.
## 📄 refactor(ingestion): HTML→Markdown converter, and WebScraperTool stops duplicating it (2026-09-17)

**Repo:** EDDI (`feat/html-to-markdown-converter`)

### Why

Ingesting a web page for retrieval needs more than `Jsoup.text()`. Flat text loses the structure a
chunker needs (heading boundaries, which section a passage came from) and merges neighbouring blocks
into single tokens. `WebScraperTool` was doing exactly that, with its own inline
`"script, style, nav, footer, header, aside"` strip — a second, weaker copy of the same rules.

This lands the converter salvaged from the stale PR #529, with its defects fixed, and makes the
existing tool use it instead of its own extraction.

### Why not a library

Checked the classpath first: jsoup and pdfbox are present; flexmark-html2md, commonmark and the
langchain4j document parsers are not. A general HTML→Markdown library optimises for fidelity to the
source document, while ingestion wants the opposite — aggressive removal of everything a reader skips.
~450 lines with a 60-case suite is cheaper than a new supply-chain dependency for that job.

### Defects fixed from the salvaged draft

Each of these silently degraded what reached the vector store; all 46 of the draft's own tests passed
with them present, which is the point — bad ingestion has no stack trace.

- **Adjacent blocks merged.** `div`/`section`/`article` appended children with no separator, so
  `<div>Hello</div><div>World</div>` embedded as `HelloWorld`.
- **`<header>` stripped globally**, deleting the page title in the `<article><header><h1>` layout most
  documentation themes use. Now only `body > header` (the site banner) is removed.
- **Unescaped `|` in table cells**, which ends the column early and shifts every later value under the
  wrong header — corruption that surfaces only as a wrongly cited number.
- **Code blocks flattened**: `text()` collapses whitespace, so every multi-line sample became one line.
  Uses `wholeText()`.
- **Headings resolved links against `null`**, leaving them relative and useless as citations.
- **`<dl>`, `<details>`, `<figure>` fell through to the default branch** and ran together — collapsed
  `<details>` content is still content and is now ingested with its summary as the label.
- Alt-less images emitted `![](url)`: tokens spent on nothing. Dropped.
- Boilerplate selectors extended with `role=navigation|banner|contentinfo|complementary`, cookie
  banners, buttons, `svg`, `template`, `aria-hidden`.
- `maxLength <= 0` truncated everything; now falls back to the default.
- Dead `inPreBlock` plumbing removed (never set true by any caller).

### WebScraperTool

`extractWebPageText` now returns Markdown from the converter rather than a `text()` dump prefixed with
`Title: `. Same 5000-character cap. This is a visible change to an LLM tool's output, and a deliberate
one: the model gets headings, lists and tables instead of one run-on paragraph. Its `extractMainContent`
helper is gone — it was the duplicate.

### Tests

60 converter tests: all 46 inherited from the draft pass unchanged against the rewrite (useful evidence
the behaviour was preserved where it was right), plus 14 in `HtmlToMarkdownConverterSalvageTest`, one per
defect above. `WebScraperToolExtendedTest` gains a case asserting structure survives.

Note: `WebScraperToolTest` cannot run in this environment — its `setUp` constructs a real
`SafeHttpClient`, and creating an `HttpClient` here fails with "Unable to establish loopback
connection". Pre-existing and environmental; CI covers it.

## ♿ fix(ui): closing a dialog hands focus back to what opened it (2026-09-19)

**Repo:** EDDI (`fix/dialog-return-focus`)

`AccessibleDialog` promises "return focus to trigger element on close". It did not keep that promise
in either of the two ways the Manager closes a dialog, so keyboard and screen-reader users were left on
`<body>` and had to find their place from the top of the page again.

### What was wrong

- **With an `autoFocus` field inside** (`CreateAgentDialog`'s Name, the dictionary picker's search),
  "what had focus" was recorded in a `useEffect`. React applies `autoFocus` during commit, before any
  effect runs, so the recorded element was the dialog's own field. On close it had unmounted, and
  focusing it did nothing.
- **When closed by unmounting.** `ShareDialog` (on the Agents, Workflows and resource list pages) and
  the Triggers dialog are rendered as `{target && <X open … />}` and close by unmounting. Focus was
  only restored on an `open === false` render, which an unmount never produces.

### What changed

- `ui/manager/src/components/ui/accessible-dialog.tsx`: the trigger is recorded while rendering the
  opening render, before React commits the dialog. It is restored in the effect's cleanup, which runs
  on close and on unmount alike, but only if focus was actually lost with the dialog (it sits on
  `<body>`). That guard does two things: StrictMode runs the cleanup once on mount with the dialog
  still up, where an unconditional restore pulled focus out of the open dialog, and focus the user
  deliberately moved elsewhere is not taken back.
- `ui/manager/src/components/ui/__tests__/accessible-dialog-focus-return.test.tsx` (new): autoFocus
  close, unmount close, StrictMode mount, and focus moved elsewhere. Against `main` the first two fail;
  with the `<body>` guard removed the last two fail.

### Note

This rewrites the same effect as #788 (initial focus no longer steals from a focused field), which
landed first. `main` is merged in here and the conflict resolved to keep both: #788's guarded,
cancelled frame, and this branch's cleanup restore — the cleanup now cancels the frame *and* returns
focus.

---

## 🧪 fix(ui): a dialog no longer takes focus from a field the user is typing in (2026-09-18)

**Repo:** EDDI (`fix/share-dialog-flaky-test`)

`UI Manager Checks` failed intermittently (run 35295321603) in two unrelated-looking tests that
pass locally: `share-dialog` › "does not let two quick Enters skip the ownership confirmation"
(`share-owner-warning` never appeared) and `create-agent-dialog` › "allows typing in description
field" (the field was empty after `user.type`). They had one cause, and it was in the component, not
the tests.

### Root cause

`AccessibleDialog` moved initial focus to its first focusable element (the header's Close button)
inside a `requestAnimationFrame` scheduled on open. On a loaded runner that frame fired *after* the
test had clicked into a field, and user-event sends keystrokes to `document.activeElement`: "bob"
went to the Close button, the share subject stayed empty, Enter failed validation, and no warning was
ever rendered. The same frame overrode every `autoFocus` inside the dialog in the real UI —
`CreateAgentDialog` autofocuses its Name field, and focus ended on the X a frame later.

Reproduced by stubbing `requestAnimationFrame` to a 30–150 ms timeout: the original share-dialog tests
then fail with exactly the CI error, and the create-agent tests with exactly the empty value.

### What changed

- `ui/manager/src/components/ui/accessible-dialog.tsx`: the frame leaves focus alone when it is
  already inside the dialog, and is cancelled on cleanup. The trap, Escape and return-focus behaviour
  are unchanged.
- `ui/manager/src/components/ui/__tests__/accessible-dialog.test.tsx` (new): holds the frame and
  releases it by hand, so "focus reached a field first" is deterministic. Covers the empty-dialog
  default (Close gets focus), a field focused before the frame, and an `autoFocus` field.
  Mutation-checked: removing the guard fails the latter two.
- `ui/manager/src/components/workspaces/__tests__/share-dialog.test.tsx`: the warning assertions made
  straight after a user event now wait (`findByTestId` for it appearing, `waitFor` for it
  disappearing, the latter safe because each test has just seen it present). This is hygiene, **not**
  the fix — with the delayed frame and the old component these still fail, just after the wait. The
  behavioural guards (`shared` not called, `sentSubject` null) are unchanged.

### Verification

With the fix, the 80 ms and 150 ms delayed-frame copies of both the old and the new share-dialog tests
and of `create-agent-dialog` pass (246/246); without it, they fail as CI did. Full Manager suite with
coverage green locally.

### Not done

`previousFocusRef` is captured in the same effect, after an `autoFocus` child has already taken focus,
so on close focus "returns" to that (now unmounted) field instead of the trigger. Pre-existing and
separate; left alone here.

---

## 🔐 fix(a2a): make the A2A endpoints' anonymity real, and decide which of them deserve it (2026-09-17)

**Repo:** EDDI (`fix/a2a-anonymous-discovery-permissions`)

`RestA2AEndpoint` annotated five endpoints `@PermitAll`, intending them to be reachable by peer
agents that hold no EDDI credential. None of them were named in a
`quarkus.http.auth.permission.*` entry. **Quarkus evaluates those path policies before declarative
RBAC**, so the `/*` catch-all (`policy=authenticated`) claimed all five: on any instance with
`quarkus.oidc.tenant-enabled=true`, Agent Card discovery answered **401** to exactly the callers it
exists for — a bare 401, since `quarkus.oidc.application-type=service` sends no login redirect. The
annotation and the deployment had disagreed for as long as the endpoints existed.

Nothing caught it because `A2aEndpointIT` runs against a `BaseStandaloneIT` instance with
authorization off, where `DisabledAuthController` switches the path policies off wholesale and a
permitted path and a protected one answer identically.

### The decision, endpoint by endpoint

Not all five were meant to be anonymous, so this is not "add a permit entry for the five".

| Endpoint | Posture | Why |
|---|---|---|
| `GET /.well-known/agent.json` | **permit** | The A2A discovery convention. A peer reads the card *before* it holds any credential |
| `GET /a2a/agents/{agentId}/agent.json` | **permit** | The card EDDI's own client fetches — `A2AToolProviderManager.fetchAgentCard` sends `apiKey` only if one is configured. Needs the agent id, so it discloses one agent, not the roster |
| `GET /a2a/agents` | **authenticated** — `@PermitAll` removed | The whole roster: every A2A agent's name, description, skills and URL. Strictly more than the skill-name list that sits behind `eddi.a2a.capabilities.public`, and nothing in the protocol or in this repo fetches it |
| `GET /.well-known/capabilities` | **permit** at the HTTP layer | `eddi.a2a.capabilities.public` (default `false`) is the only *authorization* gate — `eddi.a2a.enabled` gates it as well, but neither looks at the caller. While either is off the handler answers 404 to authenticated and anonymous callers alike, so permitting the path widens nothing — and while both are on, "public" has to mean *without a token* |
| `GET /.well-known/capabilities/skills` | **permit** at the HTTP layer | Same flag, same reasoning |

Where code and config disagreed, the **code** was changed: `listA2AAgents` lost `@PermitAll` and
gained `@Authenticated`, rather than gaining a permit entry.

### Design decisions

- **`/a2a/agents/*/agent.json`, not `/a2a/agents/*`.** Quarkus 3.39's `ImmutablePathMatcher`
  supports an inner wildcard matching exactly one path segment, and the distinction is load-bearing
  twice over. A `/a2a/agents/*` prefix would (a) permit the roster, because the prefix registers
  under `/a2a/agents` and wins over the catch-all, and (b) **break A2A outright**: the JSON-RPC
  `POST /a2a/agents/{agentId}` would match a `methods=GET` entry, and Quarkus *denies* on a method
  mismatch rather than falling through. Both are asserted.
- **No `/.well-known/*` wildcard.** RFC 9728 protected-resource metadata is planned under that
  prefix (`planning/saas-connectors-plan.md` §6.3); a wildcard would pre-permit it, and anything
  else later dropped there, with nobody deciding to. The paths are enumerated and a test asserts a
  sibling still requires authentication.
- **One knob for capability discovery.** The permission entry does not re-express
  `eddi.a2a.capabilities.public`; duplicating the gate into a second property is how the two drift.

### Tests

- **`A2aEndpointPermissionsTest`** (new, unit — runs in `./mvnw test`, no container). Feeds the
  shipped `application.properties` through Quarkus's own `ImmutablePathMatcher` and resolves the
  effective policy per path and method, replicating `findHttpMatchers`' method-filtering rule. Its
  last test reflects over `RestA2AEndpoint` and asserts every `@PermitAll` / `@Authenticated` method
  resolves to the policy it claims — so the *next* endpoint added with a forgotten permit entry
  fails here. Mutation-checked three ways: removing the card entry reproduces the original
  `[authenticated]`; widening to `/a2a/agents/*` catches both failure modes above; a
  `/.well-known/*` wildcard trips the sibling assertion.
- **`ui/manager/e2e/auth/a2a-discovery.spec.ts`** (new). The auth E2E tier is the only one that
  enforces authentication, so it is where the real status codes belong: it creates an A2A-enabled
  agent (a card is built from stored config, no deployment needed), then asserts 200 anonymous for
  both cards and both capability endpoints and 401 anonymous for the roster, the JSON-RPC surface
  and an unlisted `/.well-known` sibling. It opens with its own "this backend really is enforcing
  auth" guard so it cannot pass vacuously, and re-checks the roster with an admin token so the 401
  is provably about anonymity.
- `docker-compose.integration-keycloak.yml` sets `EDDI_A2A_CAPABILITIES_PUBLIC=true`, because with
  the flag off the spec could not tell "permitted, flag says no" (404) from "the permission entry is
  missing again". The flag-off 404 stays covered by `RestA2AEndpointTest`.

### Files

- `src/main/resources/application.properties` — new `a2a-agent-card` and `a2a-capabilities` permit
  entries, GET-only, before the catch-all
- `src/main/java/ai/labs/eddi/engine/a2a/RestA2AEndpoint.java` — `listA2AAgents` is
  `@Authenticated`; Javadoc on every endpoint records the posture and why
- `src/test/java/ai/labs/eddi/engine/a2a/A2aEndpointPermissionsTest.java` — new
- `ui/manager/e2e/auth/a2a-discovery.spec.ts` — new
- `ui/manager/docker-compose.integration-keycloak.yml` — capability flag on
- `docs/a2a-protocol.md` — an "Anonymous?" column and a "Who can call them" section
- `docs/configuration-reference.md` — `eddi.a2a.capabilities.public` says what it actually gates

### Review follow-up (PR #782)

Three findings, all valid, all fixed on the branch:

- The generic guard resolved the HTTP verb as `isAnnotationPresent(GET) ? "GET" : "POST"`, so a
  future `@PermitAll @PUT` would have been graded against a method it does not serve — and since
  the permit entries are GET-only, that is precisely the drift the guard exists to catch. The verb
  now comes from whichever annotation is meta-annotated `jakarta.ws.rs.HttpMethod`, and the guard
  fails on anything other than exactly one. Confirmed by planting a `@PermitAll @PUT` endpoint plus
  a permit entry naming POST: the old code passed it, the new code names the entry and the verb.
- `docs/a2a-protocol.md` said everything is reachable without a token when OIDC is off. True of
  authentication, misleading about the result — `eddi.a2a.capabilities.public` is an independent
  switch and its endpoints 404 either way while it is off.
- `docs/configuration-reference.md` said the capability endpoints expose agent *names*.
  `CapabilityMatch` is `(agentId, skill, confidence, attributes)` — ids. The surface is smaller
  than the doc claimed, which if anything strengthens the case for leaving `/a2a/agents` (names,
  descriptions, URLs) authenticated.

**Second pass** (CodeRabbit's first review was rate-limited before it saw the fix commits, so both bots
were asked for a fresh look):

- `eddi.a2a.capabilities.public` was described as "the only gate". `eddi.a2a.enabled` gates the
  capability endpoints too (`if (!a2aEnabled || !capabilitiesPublic) → 404`). Reworded in all five
  places that said it to "the only *authorization* gate — neither flag inspects the caller", which
  is the claim the permit entry actually rests on.
- The Agent Card's `authentication.credentials` is built from `quarkus.oidc.auth-server-url`, i.e.
  the URL **EDDI** uses to reach the IdP. The shapes that bundle Keycloak set that to an in-cluster
  or compose hostname, so the token endpoint advertised to an outside peer does not resolve — which
  this PR makes consequential, because the card is now anonymously readable under auth. Initially
  deferred as a config-design decision; **fixed here** once CodeRabbit raised it independently at
  Major severity — see the fourth pass below.

**Third pass — two findings Copilot *suppressed* into its review body**, where they have no thread and
a `reviewThreads` query cannot see them. Both were real, and both are properly this PR's:

- **`/.well-known/agent.json` fanned out over the whole roster.** `getDefaultAgentCard()` called
  `listA2AAgents()` and returned `cards.get(0)` — building a card for every A2A-enabled agent
  (`getCurrentResourceId` + `read` + `readDescriptor` apiece, up to 100 candidates) and discarding
  all but one. Merely wasteful while the endpoint required a token; an amplification vector now that
  this PR makes it anonymous. `AgentCardService.getDefaultAgentCard()` now stops at the first match
  (`collectA2AAgents(stopAtFirst)`), and `AgentCardServiceTest` asserts **one** store read across 25
  candidates rather than asserting the card — the card was always right, the cost was not.
- **The E2E cleanup scored a failed request as success.** `await call().catch(() => undefined)`
  followed by `res === undefined || res.status() < 400` passed when the request never completed,
  leaking the A2A-enabled fixture agent. That one contaminates specifically: the default Agent Card
  is whichever A2A agent comes first, so a leftover is exactly what a later run reads. The soft
  assertion now requires a real 2xx/3xx and reports the status or the error.

**Fourth pass — the advertised token endpoint, raised independently by both reviewers.** Deferred
twice on scope, then implemented: two reviewers agreeing, both framing it as "the permission change
makes this pre-existing URL consequential", outweighed the argument for keeping it separate.

`AgentCardService.advertisedTokenEndpoint()` resolves what the card advertises:

- **`eddi.a2a.public-token-endpoint`** (new, optional) — advertised verbatim. The *endpoint*, not
  the issuer, because the path is the provider-specific part.
- Otherwise `<issuer>/protocol/openid-connect/token`, where `<issuer>` is **`eddi.keycloak.public.url`**
  grafted onto the realm path from `quarkus.oidc.auth-server-url`, falling back to
  `quarkus.oidc.auth-server-url` itself. Both shipped authenticated deployments already set the
  public URL — Helm *requires* it, since the Manager SPA cannot start a login without it — so they
  become correct with no new configuration. Only the origin is taken from it; the realm path stays
  what EDDI is configured against, so the two cannot drift. **Nothing moves for a deployment that
  does not opt in**, which is what made this safe to do inside a permissions PR.

The derivation **assumes Keycloak**, which the docs now say rather than gloss. OIDC discovery would
remove the assumption instead of documenting it and is the right follow-up; it is not done here
because it turns rendering an anonymous card into an outbound HTTP call, needing `SafeHttpClient`,
a cache and a failure policy.

Verified end to end rather than by unit test alone — built the image, ran the Keycloak tier, and read
the anonymous card: `credentials` is now
`http://localhost:8180/realms/eddi/protocol/openid-connect/token`, the published port an outside peer
sees, where it was `http://keycloak:8080/...`. That URL is provably reachable — it is the one the
test fixtures fetch their tokens from. `a2a-discovery.spec.ts` now asserts it exactly, as the
reviewer asked.

**Fifth pass — a bug in the fourth pass.** CodeRabbit (Major) caught that the property introduced
above was the *issuer*, while the Keycloak path `/protocol/openid-connect/token` was appended to
whatever it named. So the one knob documented as "the escape hatch for a non-Keycloak IdP" handed an
Okta or Auth0 operator their issuer with a Keycloak path stapled on — it did not do the job it was
documented as doing, and the docs, the commit message and the reply to the reviewer all repeated the
claim.

Replaced `eddi.a2a.public-auth-server-url` with `eddi.a2a.public-token-endpoint`, advertised
verbatim: **one** property instead of two, and it actually covers the case the other one claimed to.
The property was one commit old and unreleased, so nothing depended on it. Two tests pin the
distinction, including one asserting the Keycloak path is never appended to an endpoint given in
full.

Also seen this pass and **not** fixed here: `UI Manager Checks` went red on
`share-dialog.test.tsx › does not let two quick Enters skip the ownership confirmation`, a file this
branch does not touch. It passes 15/15 locally three runs in a row, and the cause is visible in the
test — a synchronous `expect(screen.getByTestId("share-owner-warning"))` immediately after an async
`userEvent.type`, with no `waitFor`, so a slow runner loses the race. A real flake with a one-line
fix, but in unrelated code; filed separately rather than smuggled into a permissions PR.

### What's next

Nothing outstanding for A2A. The generic lesson — `@PermitAll` is not a permit entry — applies to
any future endpoint meant to be anonymous; `A2aEndpointPermissionsTest` only guards
`RestA2AEndpoint`, and widening it to every `@PermitAll` in the codebase would be a reasonable
follow-up.

---

## 🔏 chore(ci): settle the dependency-review licence policy — deny-list kept, broadened, documented (2026-09-17)

**Repo:** EDDI (`chore/dependency-review-license-policy`)

### Why

`.github/workflows/dependency-review.yml` printed a deprecation warning on every PR
("The deny-licenses option is deprecated for possible removal in the next major
release"). The comment above the option already recorded the deferral: migrating to
`allow-licenses` means enumerating every licence the project accepts, which is a
repo-wide policy decision, not a mechanical swap. This session established the real
input, put the decision to the maintainer, and implemented the answer.

### What the dependency graph actually contains

Enumerated three ways: `license-maven-plugin:add-third-party` for the resolved Maven
tree (582 artefacts), `npm query ":not(.dev)"` for both UIs, and — the one that
matters — the live graph the action actually reads,
`gh api repos/labsai/EDDI/dependency-graph/sbom`.

GitHub's Maven graph parses `pom.xml` directly and does **not** resolve transitives,
so the policy is evaluated against 95 Maven entries, not 582:

| Count | Licence |
|---|---|
| 58 | `NOASSERTION` — BOM-managed (`io.quarkus:*`, `jakarta.annotation`, `caffeine`) or `${property}`-versioned (all 22 `dev.langchain4j:*`) |
| 27 | `Apache-2.0` |
| 4 | `MIT` (testcontainers) |
| 2 | `Apache-2.0 AND BSD-3-Clause AND MIT` (maven plugins) |
| 2 | `LicenseRef-bad-non-standard` — `org.jsoup:jsoup`, `io.github.classgraph:classgraph`; both are really MIT |
| 1 | `BSD-2-Clause` (postgresql) |
| 1 | `EPL-2.0 OR (Apache-2.0 AND EPL-2.0)` (jacoco) |

npm contributes 1137 graph entries but `fail-on-scopes` defaults to `runtime` and
`main.ts` runs the licence check on the scope-filtered set, so only production deps
count: manager 179 (MIT 164, OFL-1.1 8, ISC 2, Apache-2.0 2, BSD-3-Clause 1,
`MPL-2.0 OR Apache-2.0` 1) and chat 126 (MIT 122, ISC 2, BSD-3-Clause 1). The
MPL-2.0, CC-BY-4.0 and Python-2.0 entries in the graph are all devDependencies.

### Decision

Keep `deny-licenses`, broaden it, and record why the warning is accepted. Three
findings from reading the action's source made the allow-list migration the worse
option rather than merely the more expensive one:

1. **It would fail the build today.** `spdx.satisfies()` returns `false` for an
   expression it cannot match, so the two `LicenseRef-bad-non-standard` entries land
   in `forbidden` → `setFailed` under an allow-list. Under a deny-list
   `satisfiesAny()` returns `false` and they pass. Migrating would mean two permanent
   per-package exclusions that exist only to work around GitHub's own normalisation.
2. **It buys no coverage.** The 58 unknown-licence entries go to the `unlicensed`
   bucket, and `printNullLicenses()` only prints — it never sets `issueFound`. They
   are informational in *both* modes.
3. **Removal is not scheduled.** Upstream issue #997 was closed by stalebot after 180
   days of inactivity, not by a decision, and v5.0.0 (2026-05-08) is a node20 → node24
   runtime bump that leaves `deny-licenses` fully documented in `action.yml`. There is
   no newer v4 digest, so the pin stays at v4.9.0.

The line is drawn at the library level, because EDDI is Apache-2.0 and ships a fat jar
inside a distributed Docker image — a combined work. Permissive and weak (file-level)
copyleft stay acceptable; EPL especially has to, since the whole Jakarta EE / JUnit /
JaCoCo layer Quarkus pulls in is EPL, usually dual with GPL-2.0 under the Classpath
Exception. Denied: AGPL-3.0, GPL-2.0, GPL-3.0, LGPL-2.0/2.1/3.0 (each `-only` and
`-or-later`), SSPL-1.0, BUSL-1.1, Elastic-2.0. The additions past the original two are
not hypothetical — the realistic hazard for middleware is a dependency relicensing to
source-available, and EDDI already depends on MongoDB and Elasticsearch clients.

### Verified, not assumed

Ran the candidate list through the same libraries the action uses
(`@onebeyond/spdx-license-satisfies`, `spdx-expression-parse`) against every licence
value in the live SBOM:

- nothing currently in the graph is newly denied — the change is a strict superset of
  the old behaviour with no regression;
- every listed hazard is caught;
- deprecated ids still match: a dep declared `GPL-3.0` is caught by `GPL-3.0-only`, so
  modernising the identifiers does not weaken the gate;
- Classpath-Exception artefacts do **not** false-positive —
  `EPL-2.0 OR GPL-2.0-with-classpath-exception` and
  `CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0` both pass with `GPL-2.0-only`
  and `GPL-2.0-or-later` denied. This was the main risk of adding GPL-2.0 and it is
  disproven, not hoped.

Known trade-off, recorded in the workflow: `satisfiesAny()` treats `A OR B` as denied
when either side is, so a *directly declared* dep offering `Apache-2.0 OR LGPL-2.1`
would be flagged despite the Apache option. Nothing hits this today — the dual-licensed
artefacts (`net.java.dev.jna`, `org.javassist`, `com.github.java-json-tools:*`) are all
transitive and invisible to GitHub's Maven graph.

### Dropped the Caffeine exemption

The `allow-dependencies-licenses` entry for Caffeine is **removed**. It was first kept
with a corrected comment calling it cosmetic; CodeRabbit pushed back on the PR, and it
was right. `groupChanges` in the action's `src/licenses.ts` says so in its own comment —
*"we leave it off of the `licensed` and `unlicensed` lists"* — so the input drops a
package from the licence check **entirely**, not just from the unknown-licence notice.
The exemption therefore also waived `deny-licenses` for any future Caffeine release
whose licence GitHub *can* resolve, while buying nothing: per finding 2 an unresolved
licence cannot fail the build anyway, and 57 other entries sit in the same bucket
unexempted. Caffeine remains verified Apache-2.0 (its own POM on Maven Central at 3.2.4,
the version the Quarkus BOM resolves), shipped transitively via `quarkus-caffeine`
before it was ever declared here — nothing needed waiving. The replacement note records
when that input *is* appropriate: a package whose licence GitHub reports wrongly, naming
the licence being accepted.

### Files

- `.github/workflows/dependency-review.yml` — broadened `deny-licenses`, removed
  `allow-dependencies-licenses`; rewrote the comments to record the decision, the
  evidence, and the revisit condition (upstream announcing removal, or GitHub resolving
  BOM-managed Maven coordinates).

## ⬆️ chore(ui): Node 22 toolchain, Stryker 10, Vitest 5 for the Chat UI (2026-09-17)

**Repo:** EDDI (`feat/node-22-toolchain`, stacked on `fix/ui-npm-vulnerabilities` / #770)

Node 20 reached end of life on 2026-04-30, and it was what held the UIs on Stryker 9 and Vitest 4:
Stryker 10 dropped Node 20 (Dependabot #768 was red for that reason) and Vitest 5 requires ≥ 22.12.

### What changed

- **Node 20 → 22 everywhere the build names a version.** `pom.xml` `node.version` `v20.20.2` →
  `v22.23.2` (the latest 22.x; Maintenance LTS until 2027-04-30), `mise.toml` to match, and all eight
  `actions/setup-node` steps in `ci.yml` (`node-version: 22`, step names too). `AGENTS.md` and
  `README.md` no longer say Maven downloads Node 20. No test asserts on the version and no Dockerfile
  uses Node.
- **Manager: Stryker `9.6.1` → `10.0.0`** (still exact-pinned). Its only breaking change is the Node
  floor. The `typed-rest-client` → `qs` override stays: Stryker 10 still takes `typed-rest-client`
  `~2.3.0`.
- **Chat UI: Vitest `^4.1.11` → `^5.0.1`.** No test or config change was needed.
- **Manager stays on Vitest 4.1.11 — see Decisions.** `dependabot.yml` now ignores Vitest/`@vitest/*`
  *majors* for `/ui/manager` only, with the reason and the upstream issue beside the rule
  (`update-types` scopes it to version updates; security updates still arrive).
- **The UIs' own docs caught up** (Copilot review): `ui/chat/README.md` and `ui/chat/AGENTS.md` still said
  Node ≥ 20, Vitest 3 and react-markdown 9.x; `ui/manager/README.md` still said Node ≥ 20. A contributor
  following them would install an unsupported runtime. Each now names the floor that applies to that UI
  — 22.12 for the Chat (Vitest 5), 22.18 for the Manager (Stryker 10's Babel 8) — and the pinned 22.23.2.
- **`updates.test.ts`: a test for the two cleanups in `getWithoutCredentials`'s `finally`.** Stryker 10
  mutates more statements than 9.6.1 (265 mutants on `updates.ts` against 263), and both new ones
  survived: deleting `clearTimeout(timer)` or `csp.stop()` failed no test. The existing "stops listening
  for violations once the request is done" test cannot see that leak — each request reads its own
  closure's flag, so a leftover listener never touches the next verdict, it only accumulates. The new
  test pins both calls (spies on add/removeEventListener and set/clearTimeout) and fails with either
  line deleted.

### Decisions

- **The Manager cannot take Vitest 5 yet: it breaks Stryker.** On Vitest 5, `@stryker-mutator/vitest-runner`
  10.0.0 selects zero tests per mutant — its per-test filter joins names with a space, Vitest 5 joins
  them with `' > '` ([stryker-mutator/stryker-js#6210](https://github.com/stryker-mutator/stryker-js/issues/6210),
  open, no fixed release on npm). Measured here: `updates.ts` scored **0.00** (all 257 covered mutants
  "survived") against 81.85 on Vitest 4 with the same runner and Node. The break threshold would at least
  fail the run, but a gate whose every mutant survives measures nothing. The Chat UI has no Stryker, so it
  moves. Revisit the Manager when a fixed runner ships — the Dependabot ignore rule names the issue.
- **The real Node floor is 22.18, not 22.12.** Stryker 10 moved to Babel 8, whose packages declare
  `engines.node` `^22.18.0 || >=24.11.0`. The `pom.xml` comment records it; on an older 22.x Stryker
  warns `EBADENGINE` and may not run.
- **22, not 24.** 24 is Active LTS until 2028-04-30, but this change was scoped to leaving the EOL line.
  Every package in both lockfiles declares an `engines.node` range that also accepts 24.21.0, so moving
  on later is a pin change, not a migration.

### Verification (on Node 22.23.2 — the binary Maven downloads)

- `./mvnw package -DskipTests` installed Node v22.23.2 and ran `npm ci` + `npm run build` for both UIs:
  BUILD SUCCESS.
- Manager (Vitest 4.1.11, Stryker 10): lint, typecheck, `vitest run --coverage` — 411 files, 6,515
  tests, thresholds met. Scoped `stryker run --mutate src/lib/api/updates.ts`: **83.78** (216 killed of
  265), above `thresholds.break` 82 — 81.85 before the new cleanup test, 82.49 on Stryker 9.6.1.
- Chat (Vitest 5.0.1): typecheck, 278/278 tests. A static sweep found none of Vitest 5's removals in use
  (`.sequential`, non-top-level `vi.mock`, removed `vitest/*` entry points, `toThrow('')`), and its new
  `clearMocks: true` default broke nothing.
- `npm ci` for both lockfiles in a `node:22.23.2` Linux container. The Windows `@emnapi` pruning did not
  recur; all four entries are present. `npm audit`: 0 vulnerabilities in both UIs.

---

## 🔒 feat(context): secret context values — usable for one turn, never stored or returned (2026-09-17)

**Repo:** EDDI (`feat/secret-context-values`)

### Why

A client that needs the agent to call a downstream API as the signed-in user has to hand EDDI that
user's credential, and context is the only channel for it. But `Conversation` stores every context
entry as a step datum and echoes it in the conversation output, so the credential landed in
`conversationmemories`, in every detailed response and conversation read, and — once a property
setter copied it — in the properties and the user memory store. `scope: "secret"` does not fit a
per-user, per-request credential: the vault key is `agentId + "." + propertyName`, one slot per agent,
so concurrent users overwrite each other's value. `secretInput` only hides the typed message.
`${caller:token}` only covers calls back to EDDI's own origin.

### What changed

- **`Context.secret`** (`Boolean`, nullable so ordinary entries serialize unchanged). The client marks
  the entry: `{"type": "string", "value": "…", "secret": true}`. The flag lives on the value because the
  sender knows what is sensitive; putting a key list in the agent configuration would couple every agent
  to one client's field names.
- **`Conversation`**: a secret entry is never put into the echoed `context` output, not even mid-turn.
  The step datum keeps the live value while the pipeline runs, so templates, HTTP call headers and
  behavior rules work. When the pipeline stops — completed, stopped, paused or failed — the new
  `scrubSecretContextValues()` runs first in `executeConversationStep`'s `finally`, before the audit
  flush, the longTerm write and the stored snapshot. It replaces the entry with
  `MemoryKeys.SECRET_CONTEXT_PLACEHOLDER` (`<secret context>`) and every copy of the value in
  properties (in place, so their step mirrors follow), other step data (result and possible results)
  and the conversation output. Objects the plain walk cannot enter (output items, records) are scrubbed
  through their JSON form, using the persistence mapper configuration, and replaced by the scrubbed tree
  — so a reply that echoed the value is stored and returned as
  `[{"type":"text","text":"Token was <secret context>"}]`, keeping its shape. Only an object that
  cannot be converted falls back to whole replacement (WARN).
- **`TurnAuditBuffer.flush(memory, secretContextValues)`** redacts those values from every buffered
  entry, independent of the secret-input redaction.
- **`ApiCallExecutor.rejectExpiredSecretContext`**: an HTTP call whose resolved header, query parameter,
  body or path (also URL-encoded) still carries the placeholder is refused with an error naming the
  location — the case of a call that runs after a HITL approval resumed the turn, or a later turn that
  references the value. Same fail-loudly pattern as unsatisfiable `${caller:…}` references.
- **`SecretValueScrubber`** (new, `engine.memory`): the string/list/map/Context walk extracted from
  `PropertySetterTask` (which now uses it), plus `scrubDeep` for the JSON-form scrub and `scrubTyped` for
  values that must keep their type. Map keys carrying a secret are scrubbed with the values; a number is
  replaced when its string form equals a secret; longest-first replacement is enforced inside the
  utility instead of relying on the caller's order (review follow-ups).
- **HITL tool-call pauses**: the persisted pending batch (`argumentsRaw` used by the resume, the redacted
  arguments, request previews, transcript) is scrubbed with the step, so a secret context value does not
  survive a pause there either. A resumed HTTP tool call then hits the expired-value refusal below.
- **Fire-and-forget batch HTTP calls build every request on the turn's thread** and only send in the
  background. A request that cannot be built — expired secret context value, unsatisfiable `${caller:…}`
  or `${connection:…}` reference — now fails the turn like a single fire-and-forget call does, instead of
  being logged by a worker while the turn reports success. Behaviour change for configs whose batch
  build fails today: that failure becomes visible.
- Docs: `passing-context-information.md` (new "Secret Context Values" section) and a pointer in
  `secrets-vault.md`.

### Design decisions

- **Scrub at the end of the turn, not at read time.** Tasks need the plaintext while they run; what
  must not happen is the value outliving the request. A HITL resume therefore sees the placeholder —
  documented: send the value again with the request that needs it.
- **Values shorter than 8 characters are only removed from their own entry**, not searched for
  elsewhere, so a short value cannot wreck unrelated output. Longest values are replaced first.
- **Separate placeholder from `<secret input>`**: that one on `input:initial` is how the audit ledger
  decides the INPUT was a secret.

### Verification

- `ConversationSecretContextTest` (10), `SecretValueScrubberTest` (10), `ApiCallExecutorSecretContextTest`
  (6) and a new `TurnAuditBufferTest` case; the engine audit/memory/runtime, properties and apicalls
  suites plus the repo-wide guards stay green. The review follow-ups (pending batch, numbers, map keys,
  scrub order, audit numbers) were each mutation-checked.
- Mutation-checked: removing the end-of-turn scrub, the output masking, the audit redaction, the property
  scrub, the possible-results scrub or the JSON-form scrub each fails at least one test.
- End to end on the packaged build (real MongoDB, mock API that echoes the header back): the HTTP call
  sent the real token in its header; the say response, the conversation read, the stored document and
  the server log did not contain it; the saved API response and the reply showed the placeholder with
  their shape intact; a control run without the flag stored the token as before (12/12).

### Not covered

- The value still reaches anything a template sends out of EDDI while the turn runs: a prompt (model
  provider), a reply streamed over SSE (the returned and stored reply is scrubbed), or a query
  parameter/body (written to the server log by the HTTP call task). Documented: use it in headers only.

---

## 🔒 fix(ui): clear the 30 npm advisories Scorecard reports (2026-09-17)

**Repo:** EDDI (`fix/ui-npm-vulnerabilities`)

OpenSSF Scorecard's *Vulnerabilities* check reported 30 open advisories. All 30 were npm and all were
in the two UI lockfiles that arrived with the monorepo migration (`ui/manager`, `ui/chat`); none were
in `pom.xml` or `ui/manager/.ds-sync`. Every one is a devDependency (test runner, bundler, Stryker
and their transitives), so nothing affected ships in the jar — but Scorecard counts them regardless.

### What changed

- **vitest `^3` → `^4.1.11` in both UIs** (plus `@vitest/coverage-v8` in the Manager). This is the
  only fix line for GHSA-82fw-gwwq-j7x9 (`@vitest/mocker` path traversal): no 3.x backport exists.
  Chat also moves `vite` to `^6.4.3` (GHSA-fx2h-pf6j-xcff, GHSA-v6wh-96g9-6wx3) and clears the
  critical GHSA-5xrq-8626-4rwp, which its locked vitest 3.2.4 was still exposed to.
- **Stryker `9.2.0` → `9.6.1`** (still exact-pinned) — drops the old `minimatch`/`ajv`/`@babel/core`
  chain.
- **`overrides` → `typed-rest-client` → `qs: ^6.16.0` in the Manager.** `typed-rest-client@2.3.1`
  (via `@stryker-mutator/core`) pins `qs` to exactly `6.15.1`; no 2.x release relaxes it and Stryker
  10 still takes `~2.3.0`, so an override scoped to that one parent is the only fix
  (GHSA-4mjr-xmp4-gh2g, GHSA-x5fp-wj9c-mxmx). `typed-rest-client@3` itself requires `qs ^6.16.0`.
- **The remaining transitives** (`browserslist`, `fast-uri`, `js-yaml`, `minimatch`, `brace-expansion`,
  `nanoid`, `postcss`, `ws`) were refreshed in place in the lockfiles.
- **Four Manager test files fixed for Vitest 4's mocking changes** (one tsc error, 29 failures):
  - `bearer-event-source.test.ts` — `ReturnType<typeof vi.spyOn>` no longer carries `fetch`'s parameter
    types; typed as `MockInstance<typeof fetch>`.
  - `infinite-scroll-sentinel.test.tsx` — a `vi.fn` called with `new` must now be a `function`, not an
    arrow.
  - `workforce-coverage-2.test.tsx` (24 failures) — the ExportMenu tests spied on
    `document.createElement` and never restored it. Vitest 3 stacked the second spy on the first;
    Vitest 4 returns the *same* mock, so the captured "original" was the mock itself — infinite
    recursion, and the leaked spy broke every later describe. Now restored after each ExportMenu test.
  - `use-operator-chat.test.tsx` — `restoreAllMocks` no longer resets `vi.fn()`s created in `vi.mock`
    factories, so call history leaked across tests (a "called once" assertion saw 43). `resetAllMocks`
    added beside it, which is what `restoreAllMocks` used to do for those mocks. The other 11 files
    calling `restoreAllMocks` were checked: none holds a module-scope `vi.fn`, so none can now pass on
    history left by an earlier test.
- **Manager coverage floors recalibrated: lines 85 → 83, statements 85 → 81** (`vitest.config.ts`).
  Not a relaxation. Vitest 3's `v8-to-istanbul` counted every source *line* as a statement, so JSX
  markup, which runs on every render, padded both figures — `main` measured 90.25 / 90.25. Vitest 4
  remaps against the AST and counts real statements: the unchanged suite reads 83.45 % lines and
  81.83 % statements. The *uncovered code* is identical — `view-toggle.tsx` was flagged at lines 23–33
  under both — only the denominator moved. Branches (84.03 → 76.44) and functions (74.29 → 76.37) still
  clear 75 / 70 and were left alone. The new floors sit under half a point below the measurement, far
  tighter than the ~5-point slack the old ones had; whether that slack should be restored is open.
- **A real bug the upgrade surfaced: the vault popup closed itself** (`secret-key-picker.tsx`, own
  commit). With a canonicalisable value such as `vault:jira-client-secret` in a reference-only picker,
  the popup focuses its filter 50 ms after opening; that blurred the input, blur canonicalised the value
  into a chip, and the chip state renders no popup, so it vanished right after opening. The existing
  test only passed because it asserted before the timer fired; under Vitest 4 on a loaded run it failed
  2 of 3 times. `handleBlur` now ignores focus moving into the popup (scoped to the popup, not the whole
  picker: tabbing on to the vault button still canonicalises, which two existing tests pin). A new
  test waits for the filter to take focus and fails with the fix reverted.
  Deferring that blur made every way *out* of the popup responsible for normalising instead (Copilot
  review): Escape and the opener button hand focus back to the input, whose own blur then normalises;
  clicking away or tabbing out of the popup normalises directly. A blur with no `relatedTarget` is
  deliberately ignored — that is what picking a key looks like, and normalising the stale value there
  would overwrite the key just picked. Four tests, one per path, each mutation-checked; they assert
  the chip by structure, because the popup lists the same key as an option and a text match alone
  passed with the popup still open.
  A fifth exit, found by CodeRabbit: **"Create new secret"**. It closes the popup and opens the modal,
  so focus leaves the field and the deferred blur never comes — cancel the dialog and the unbraced
  reference was stranded. Normalising as the dialog *opens* (the suggested fix) cannot work: it
  switches the picker to its chip state, which returns before the modal is rendered, so the dialog
  would never appear. It normalises on *close* instead, and only when the user cancelled — `onSuccess`
  runs before `onClose` without a re-render in between, so an unconditional normalise there would
  write the old value over the key just created. Both halves are pinned by a test and each fails
  under the mutation the other guards.
- **Dependabot: `vitest` + `@vitest/*` (both UIs) and `@stryker-mutator/*` (Manager) are grouped.**
  These packages peer-depend on each other at the exact same version, so a single-package bump can never
  pass `npm ci`. That is precisely what happened: #766 (vitest 4.1.11), #768 (Stryker 10) and the
  security-updates groups #762/#764 were all red with `ERESOLVE`, and #762 would have left vitest on
  3.2.6, still vulnerable. This branch supersedes all four.

### Decisions

- **Vitest 4, not 5.** Vitest 5.0.1 is `latest`, but it (and Stryker 10) requires Node ≥ 22.12, while
  `pom.xml` pins `node.version` v20.20.2 and every UI job in `ci.yml` uses Node 20. 4.1.11 fixes the
  advisory on the Node the build actually runs. Node 20 reached end of life in April 2026, so the Node 22
  move — and with it Vitest 5 / Stryker 10 — is the follow-up.
- **Vite stays on 6** (6.4.3 carries the fixes); Vite 7/8 are a separate migration.
- **Windows lockfile pruning, again.** `npm install` on Windows dropped
  `@tailwindcss/oxide-wasm32-wasi`'s `@emnapi/core` and `@emnapi/runtime` from the Manager lock (see
  `ui/manager/AGENTS.md`); both were restored from `main`'s lock. Both lockfiles were then proven with
  `npm ci` in a `node:20` Linux container.

### Verification

- OSV ranges for all 30 advisories evaluated against every `packages` entry of the three lockfiles:
  41 affected instances (30 distinct IDs) on `main`, 0 on this branch. `npm audit` is clean in all three.
- Chat: typecheck, 278/278 unit tests, build. Manager: lint, typecheck, i18n check, all 411 test files (6,515 tests)
  with coverage (the same 411 `main` collects — Vitest 4 narrowed its default `exclude`, but this config
  sets its own), build.
- Stryker 9.6.1 on Vitest 4 (the runner gained Vitest 4 support in 9.3.0): a scoped run over
  `src/lib/api/updates.ts` completed at 82.49 %, above `thresholds.break` 82.
- The production dependency tree is untouched: no non-dev entry changed in either lockfile. Every
  transitive major (chai 6, `@inquirer/*` 5, zod 4, …) is inside the Vitest 4 or Stryker 9.6 trees.

---

## 🔐 fix(auth): the shipped realm gives tokens an identity again (2026-09-17)

**Repo:** EDDI (`fix/keycloak-realm-client-scopes`)

### What was broken

Since 6.1.0, `eddi-realm.json` has defined one client scope, `openid`. A realm file that defines
any client scopes gets only those: Keycloak creates its built-ins solely for realms that define none,
and logs `Referenced client scope 'profile' doesn't exist. Ignoring` for each missing reference. So
`eddi-frontend` lost `profile`, `email`, `roles`, `web-origins` and `acr`, and never had `basic`.
Tokens authenticated and carried their roles (the client maps those itself), but had no `sub`,
`preferred_username`, `name` or `email`. Measured against `labsai/eddi:ci` and Keycloak 26.7:

- the backend's principal had no name, so `GET /workspaces` reported no principal and every
  conversation was stamped `anonymous-<hex>`;
- a non-admin opening or continuing **their own** conversation got HTTP 500:
  `OwnershipValidator.requireOwnerOrAdmin` called `equals` on a null `callerId`;
- the Manager's avatar showed "?" (worked around separately on `fix/manager-avatar-no-claims`).

With the fix the principal is the username (`eddi`, `user`), the stored owner is that username, and the
owner reads and continues their conversation with 200 while another non-admin gets 403. Releases before
6.1.0 had the built-in scopes, so their principal was already the username: nothing to migrate.

### What changed

- **All three realm copies** add `basic`, `profile`, `email`, `web-origins` and `acr`, copied verbatim from
  a stock Keycloak 26.7 realm minus server-generated ids. `eddi-frontend` lists `openid, basic, profile,
  email, web-origins, acr`, and the realm's `defaultDefaultClientScopes` says the same, so a client an
  operator adds later issues identity claims too. `openid` stays: it puts `openid` in the `scope` claim of
  a token that did not request it (any direct grant), and Keycloak's userinfo, which the backend calls
  (`user-info-required=true`), refuses a token without it. `roles` is dropped from the list rather than
  defined: it never existed on import, and the client's own mappers already emit `realm_access.roles`
  and the `eddi-backend` audience.
- **`install.sh` repairs existing realms** (import is one-shot). `repair_keycloak_identity_scopes` creates any
  missing scope from the realm file and attaches it to `eddi-frontend`. It runs from `configure_keycloak_client`
  on a fresh setup and from a new `repair_running_keycloak` in `main()`'s already-running branch, which is how
  an existing installation is re-run: that path skipped every setup step, so it detects auth from
  `.eddi-config`, reads Keycloak's port from `.env` (quotes and CRLF tolerated), reads the scope definitions
  from a fresh copy in a temporary file (the realm file on disk, which an operator may have edited, is never
  touched; a proxy's HTML page just produces a warning), and re-applies nothing else (CORS origins depend on ports that run may not be given). `basic`, `profile` and `email` are
  attached unless the client has them as a default or optional scope; `web-origins` and `acr` only when this
  run created them, so an operator who detached them is respected. Idempotent, removes nothing, and every
  pipeline assignment carries `|| var=""` under the installer's `set -euo pipefail`. Verified in `bash:3.2`
  against Keycloak 26.7 and 26.0.8 (what the compose files and charts ship): a 6.1–6.4 realm on the jq and
  python3 paths and through the already-running path (5 created, 5 attached), a pre-6.1 realm (`basic`
  attached), an operator's detached `web-origins` and optional `email` (untouched), a no-auth install (silent),
  malformed JSON (survives; the unguarded form exits), and a second run of each (no-op). `eddi update` does not
  run it. `install.ps1` has no Admin API step at all, so its users and Helm/Kustomize operators get a documented
  one-time repair in `docs/security.md` (a subshell with `set -eu`, so pasting it cannot close the terminal; fails loudly on a bad login or missing scope, strips the CRLF
  a Windows `jq.exe` emits), run verbatim on 26.0.8 twice through a CRLF-emitting jq and once with a wrong
  password, and pasted into a live shell after an unset or wrong password. The docs tell a custom-port install to
  re-run the installer with the same `EDDI_PORT`, since the installer recognises a running EDDI only on that port.
  Known gap: a run that creates `web-origins`/`acr` and fails before attaching them leaves them unattached on
  later runs (they carry no identity). Both test-user lists there stop claiming `eddi`/`eddi` and a forced password change: `eddi` ships
  with no password, and Keycloak 26 forces no change on import.
- **Guards.** `DeploymentManifestsTest` gains three: every referenced client scope is defined in the same
  file; the SPA client's mappers emit `sub`, `preferred_username`, `name` and `email` into the access
  token and it keeps `openid`; and `install.sh`'s repair loop matches the realm file. The auth E2E tier
  gains five: claims for all three fixtures without a scope parameter, `GET /workspaces` naming the
  admin, and a non-admin opening the conversation they started (whose cleanup undeploys with
  `endAllActiveConversations=true`; without it a live conversation made undeploy answer 409 and leaked the agent).
  The installer guard also pins the already-running path and the docs loop. Mutation-checked: against the old realm
  all three unit guards fail and 7 of 12 E2E tests fail (the 5 new ones plus the two role tests, which
  now also assert `preferred_username`); with the fix, 12/12 on Keycloak 26.7 and twice on 26.0.8 with no agent left
  deployed, and the class passes apart from three
  `create-secrets.ps1` tests that fail identically on a clean `origin/main` in this environment.

---

## 🎨 fix(manager): the user-menu avatar no longer shows "?" (2026-09-17)

**Repo:** EDDI (`fix/manager-avatar-no-claims`)

### What changed

- **`ui/manager/src/lib/user-display.ts` (new)** derives the avatar's initials and the menu label from
  whatever claims the token carries: given + family name, then the display name's first and last word,
  then the first letter or digit of the username, then of the email's local part. When none yields a
  character it returns `""`, and `TopBar` and `Sidebar` render a `UserRound` icon instead of the
  literal `"?"` they used to print. The label falls back to a new `auth.signedIn` key ("Signed in", all
  11 locales), and the email line is not repeated when the email is the only label available. The top-bar
  trigger also gained a visible keyboard focus ring.
- **Review follow-ups.** Initials are the first *letter or digit* of each part, NFC-normalised, so punctuation
  and emoji no longer become initials ("Doe, Jane (Contractor)" used to give "D("); Thai and Lao preposed
  vowels are skipped; two Arabic initials get a zero-width non-joiner so they do not join into a word; and
  `toUpperCase` replaces `toLocaleUpperCase`, which followed the browser's locale rather than the app's (a
  Turkish system turned "isabel" into "İ"). A username with no letter now falls through to the email. The
  helper documents why initials prefer given + family name while the label prefers the display name.
  `userSecondaryEmail` hides the email case-insensitively when it is already the label; truncated name and
  email lines carry a `title`; the collapsed sidebar avatar is `role="img"` with the user's name as its
  label and tooltip (expanded, it is `aria-hidden`, since the name is printed beside it); French reads
  "Session ouverte".
- Tests: `user-display.test.ts` (18), plus the no-claims token shape in `top-bar.test.tsx` and
  `sidebar.test.tsx`. Mutation-checked: restoring the `"?"` fallback fails three of them.

- **CI: a UI-only pull request no longer reports "Build Failed" to Slack.** `notify-slack` required
  `build-and-test` to be `success`, but that job is skipped by design on a pull request touching neither the
  backend nor the operator docs, so this PR's run was classified a failure with nothing failed and tried to
  post. A skip now counts as passing only in exactly that case; a skip on push or tag, a cancel or a failure
  still fails. Checked against a seven-case truth table. Separately, the webhook itself answers HTTP 4xx
  (`curl` exit 22, also on a genuinely failed run on 2026-09-16), so the job stays red on any real failure
  until the `SLACK_WEBHOOK_URL` secret is replaced.

### Why the claims were empty

The shipped realm (6.1.0 through 6.4.0) defines only the `openid` client scope, so Keycloak never created
`profile`, `email` or `basic`, and its tokens carry no `preferred_username`, `name`, `email` or `sub`.
That is fixed at the source, with the backend consequences it had, on `fix/keycloak-realm-client-scopes`.
This change stays useful after it: realms provisioned by hand, other identity providers, and users
without a name or email still reach the fallback.

---

## ⚡ perf(monorepo): the efficiency review follow-ups (2026-09-15)

**Repo:** EDDI (`chore/monorepo-migration`) — the follow-ups from the two-reviewer efficiency review
recorded in the monorepo entry below.

### What changed

- **The backend Playwright tiers drive the bundle that ships.** In `e2e-fullstack` the integration and
  full-stack tiers now run with `PORT=7070 E2E_AGAINST_BACKEND=1`: `playwright.config.ts` then starts no
  Vite dev server and every page load goes to the app EDDI serves out of the image — the hashed chunks,
  the multi-page shells, `/manage/__auth_config__.js` — instead of a dev-mode build of the same source.
  (`main.tsx` only falls back to mocks in a dev build, so the full-stack tier cannot silently run on
  MSW this way.) Local runs without the variable are unchanged.
- **`OpenAPI Snapshot`, a new job, checks the Manager's snapshot without a container.** `Build Image`
  passes `-Dquarkus.smallrye-openapi.store-schema-directory=target/openapi` and uploads the document;
  the job compares `ui/manager/src/test/mocks/openapi-operations.json` with it
  (`refresh-openapi-operations.mjs` reads `OPENAPI_FILE`) and uploads the regenerated file when it
  differs. A job of its own rather than a step in `Build Image`, so a stale snapshot does not withhold
  the image from the E2E tiers; `E2E Gate` and `docker` require it. The runtime check in `e2e-fullstack`
  stays on the MongoDB leg and now also guards that the served document agrees with the stored one.
- **The MSW Playwright tier runs with two workers in CI** (`npm run test:e2e -- --workers=2`); the
  config keeps one worker for the backend tiers, whose specs share state in serial groups.
- **Dependabot npm entries** gain a cooldown (3 days, 14 for majors; security updates are never
  delayed) and a `security-updates` group, so open advisories arrive as one PR per ecosystem.
- **`README.md`, `AGENTS.md` and `.githooks/**` leave the `code` filter for `backend`.** Only unit tests
  read them, so a change to only those files now runs `Build & Test` and nothing that builds, scans or
  publishes an image. `BuildQualityGatesTest` checks the root documents against `backend` and keeps
  `backend` equal to `code` without `ui/**`, plus `ui/**/*.md` and those three.
- **`Integration Tests` no longer re-runs the ~20k unit tests.** A new `skipUTs` property (it follows
  `skipTests`, so `-DskipTests` still skips everything) skips surefire alone. `Build & Test` uploads
  `target/jacoco.exec`; `Integration Tests` restores it and the surefire reports before
  `verify -DskipITs=false -DskipUTs=true`, so the merged 90/80 coverage gate grades the same data as
  before. `BuildQualityGatesTest` fails if the job skips the unit tests without that hand-off.

### Decisions

- **Rejected: Vitest `css: false`.** Measured in a `node:20` container on the full Manager suite: 301 s
  against 285 s with CSS processing on, and it broke a real test
  (`export-dialog.test.tsx` › "toggle all checkbox selects and deselects resources").
- **Two Playwright workers, measured before adopting:** 234 MSW tests passed twice at two workers, in
  5.4 and 5.8 minutes, with no flaky results, against 13.1 minutes at one.
- **The Dependabot keys were validated against the published schema** (`json.schemastore.org`
  `dependabot-2.0.json`, checked with ajv, which rejects a deliberately invalid `cooldown` key) — an
  unrecognised key invalidates the whole file and silently stops every update in it.

### Verification

All on 2026-09-15, locally (Windows 11, JDK 25.0.1) unless marked.

- **Shipped-bundle E2E (follow-up 1).** The image built from this branch under the Manager's MongoDB
  compose file, with `PORT=7070 E2E_AGAINST_BACKEND=1`: API integration **44/44 in 12.5 s**, full
  stack **35/35 in 45 s**, and no Vite dev server started. The same tiers through the dev server had
  taken 7.6 minutes, a retried serial group included.
- **Build-time OpenAPI document (follow-up 2).** `refresh-openapi-operations.mjs` with `OPENAPI_FILE`
  pointed at the document a `clean package` stored gives exactly the 344 operations of the snapshot
  taken from the running backend ("No change"): the stored document is a faithful substitute for a
  booted backend.
- **Playwright workers and Vitest CSS (follow-up 3).** In a `node:20-bookworm` container: the MSW tier
  at two workers passed 234/234 twice (5.4 and 5.8 min, no flaky tests); Vitest with `css: false`
  took 301 s against 285 s and failed one test, so it was not adopted.
- **Dependabot (follow-up 4).** The edited file validates against the published schema; both npm
  entries carry the cooldown and the `security-updates` group.
- **Coverage hand-off (follow-up 6).** `package -DskipTests` still reports "Tests are skipped". With a
  unit-test `jacoco.exec` restored into a clean `target`, `verify -DskipITs=false -DskipUTs=true`
  skipped surefire, the unit-test report loaded the restored file (1,263 classes, no class-mismatch
  warning), and the `merge` execution loaded both `jacoco-it.exec` and `jacoco.exec` into
  `jacoco-merged.exec`. The trial's single IT (`LogAdminIT`) could not boot Quarkus on this machine —
  Netty could not open a selector, "Unable to establish loopback connection", the environmental failure
  recorded in the monorepo entry below — so that build stopped before `merged-check`. The 90/80
  evaluation on the full suites is first seen in this PR's CI run.
- **Backend guard tests** on the final state (177, including the changed `backend`-filter tests and the new coverage hand-off test): only the 3 environmental PowerShell failures recorded below.
- **Not verifiable locally:** the workflow graph itself — the new `OpenAPI Snapshot` job, the artifact
  hand-offs between jobs, and how Dependabot applies the cooldown.

### Fixed after PR #757 opened

The first CI run on the PR reported three findings that no local check could have seen, all of them
in code the import brought in unchanged:

- **`Dependency Review` and `Trivy Filesystem Scan` failed on `ui/manager/.ds-sync/package-lock.json`**,
  the Manager's design-sync tooling: `brace-expansion@5.0.7` (via `ts-morph` → `minimatch`) carries
  GHSA-mh99-v99m-4gvg and GHSA-rgw5-rvv9-x895, both high. It lists `ts-morph` as a runtime dependency,
  so both scanners grade it; the Chat and Manager lockfiles are clean at production scope. Bumped to
  5.0.12 inside `minimatch`'s `^5.0.5` range — the lockfile diff is that one entry; the nested
  `@emnapi` entries npm on Windows drops were restored. That directory also gets its own Dependabot
  entry (validated against the schema), so the lockfile does not go stale again.
- **The `CodeQL` result check** reported four alerts once TypeScript was scanned for the first time:
  `js/remote-property-injection` in `use-current-screen-context.ts`, `js/missing-origin-check` in MSW's
  generated `mockServiceWorker.js`, `js/http-to-file-access` in the `refresh-openapi-operations.mjs`
  dev script, and `js/empty-password-in-configuration-file` on `helm/eddi/values.yaml` — the JavaScript
  extractor reads YAML across the whole repository. The UI analysis is now scoped by
  `.github/codeql/codeql-ui.yml` to `ui/manager/src` and `ui/chat/src`, the code that ships, in both
  `ci.yml` and the scheduled `codeql.yml` (the file records what is left out and why), and
  `.github/codeql/**` joins the `code` and `backend` filters. The hook alert is a false positive — its
  keys come from the static route table — but `toContextPayload` now accepts only the known context
  keys and builds the payload with `Object.fromEntries`, with a test that `constructor`, `__proto__`
  and unknown keys are dropped. `password: ""` in the Helm chart is the documented "required, no
  default" setting, unchanged.
- Verified locally: the `.ds-sync` production audit is clean; the hook's 14 tests, the Manager lint and
  typecheck pass; both workflows parse, and `backend` still equals `code` without `ui/**` plus the
  test-only entries.

### Auth E2E tier, and the role mapping it found broken

Asked whether the full backend E2E really passes, whether it passes on PostgreSQL, and whether
anything covers Keycloak. The first two: yes. The third found a production bug.

**PostgreSQL is clean.** Ran the built image against
`ui/manager/docker-compose.integration-postgres.yml` locally: 44 API integration tests and 35
full-stack browser tests, every one passing first try, no retries. CI runs MongoDB only on pull
requests and both stores on `main`, so this leg had never actually executed on this branch.

- One local-only flake surfaced on the way: `chat page loads and allows agent selection` failed when
  the tier ran wide. `fullyParallel: true` applied to the backend-facing tiers too, and they share one
  EDDI and one datastore — so one spec undeployed the agent another was asserting on. CI never saw it
  because `workers: 1` serialises everything there. `fullyParallel` is now off at the top level and
  re-enabled on the mock-backed `ui` tier alone, so a local run behaves like CI.

**Nothing had ever exercised an authenticated request.** Every backend-facing compose file sets
`EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true`, which ties `authorization.enabled` to false and makes
every `@RolesAllowed` a no-op. So a new tier: `ui/manager/docker-compose.integration-keycloak.yml`
(EDDI + MongoDB + Keycloak 26, OIDC enforced), a Playwright `auth` project, and a CI job
`Auth E2E (Keycloak)` folded into the E2E Gate.

- **What it found, on its first run: the administrator the shipped realm seeds is refused
  everything.** Quarkus OIDC reads roles from the `groups` claim whenever that claim is present and
  never falls back to `realm_access`. EDDI already owns `groups` — workspaces resolve `team:<group>`
  spaces from it (`WorkspaceSettings.eddi.workspaces.groups-claim`, same default). And
  `helm/eddi/files/eddi-realm.json` — byte-identical to `k8s/overlays/auth/eddi-realm.json` — puts the
  seeded `eddi` administrator in the `engineering` group. So installing the chart with
  `keycloak.enabled=true` and giving `eddi` a password as NOTES.txt instructs produced an
  administrator who authenticated and was then denied every endpoint, `/administration/*` included.
  The unprivileged fixtures belong to no group and behaved correctly, which is why nothing showed it.
  Fixed with `quarkus.oidc.roles.role-claim-path=realm_access/roles`.
  - Verified by experiment, not by reading: with the property, `eddi` gets 200 on
    `/agentstore/agents/descriptors` and `/administration/orphans`; without it, 403 on both while the
    token plainly carries `realm_access.roles=[eddi-admin, eddi-editor]`. Mutation-checked against the
    unfixed image: exactly one test fails, the administrator one, with the other six green.
  - **Pre-existing, not caused by the migration** — the backend and the chart realm both predate it.
    The migration is only why it was finally executed.
- **The Manager's committed Keycloak realm was dead code that could not have worked.**
  `ui/manager/keycloak/eddi-realm.json` named the client `eddi-manager` while the backend hardcodes
  `eddi-frontend` into `/manage/__auth_config__.js`, and its roles were `admin`/`editor`/`viewer`
  against a backend that enforces `eddi-admin`/`eddi-editor`/`eddi-user`/`eddi-viewer`/`eddi-approver`.
  Deleted. Both the dev-aid compose and the new tier now mount the chart's realm, so there is one
  realm in the repo that anything runs against and it cannot drift again.
- The tier's realm is **generated, never committed** (`scripts/make-test-realm.mjs`): it reads the
  chart realm and adds a throwaway password for `eddi`, which the shipped realm deliberately omits.
  Every assumption it makes — the client id, its public/direct-access flags, each fixture's roles and
  passwords, and that `eddi` still ships credential-less — is asserted, so a change to the shipped
  realm fails the script by name instead of leaving the tier testing nothing.
- Issuer handling is the part that usually breaks: the tests fetch tokens through the published port
  on `localhost:8180` while EDDI validates against `http://keycloak:8080/realms/eddi` on the compose
  network. `KC_HOSTNAME` pins the issuer to the compose-network URL regardless of the request's Host
  header, so both sides agree; without it every token would claim an issuer EDDI rejects and the tier
  would report 401 for reasons unrelated to the code under test.
- Coverage is API-level on purpose — which token is accepted and which role opens which door —
  rather than driving Keycloak's login form, whose failures are mostly its own. Seven tests: the
  SPA's auth config, anonymous 401, malformed-token 401, the admin's claims, the admin allowed
  through, and `user`/`viewer` authenticated but refused.

### Copilot review findings on PR #757

Four unresolved threads, all confirmed against the code before being fixed. CodeRabbit skipped the
PR entirely (1345 files against a 100-file limit) and Codacy reported nothing, so these were the
whole review.

- **`-DskipUi=true` could still ship a UI.** The flag skips the npm build and the `copy-ui-bundles`
  execution, but skipping a copy cannot undo one: a plain `./mvnw package` followed by
  `./mvnw package -DskipTests -DskipUi=true` in the same `target/` left the first run's bundles in
  `target/classes` and packaged them, so the documented "the jar then serves no UI" was not what you
  got. `maven-clean-plugin` gains a `drop-stale-ui-bundles` execution on `prepare-package` that
  deletes the thirteen generated paths from `target/classes/META-INF/resources`, leaving the three
  backend-owned files (`index.html`, `robots.txt`, `scripts/js/landing-redirect.js`) alone. It runs
  unconditionally rather than only under `skipUi`, because a UI *rebuild* has the same bug in
  miniature: Vite content-hashes its filenames, so every repackage without a `clean` shipped the new
  `assets/index-<hash>.js` beside the old one.
  - The plugin moved above `maven-resources-plugin` in the POM — executions of one phase run in
    declaration order, and the drop has to land after the UIs build and before the copy — and both
    filesets moved from the plugin onto their executions (`default-clean` keeps the source-tree
    list). A plugin-level `<configuration>` merges into *every* execution, and had the `clean`-phase
    list reached this one it would have deleted `ui/manager/dist` moments before the copy read it.
  - Verified: `./mvnw clean:clean@drop-stale-ui-bundles` against a seeded `target/classes` removes
    exactly the generated paths and leaves `target/`, `ui/manager/dist` and the three backend files;
    `./mvnw clean` still clears the source tree and both `dist/` directories.
- **`Preflight Dry-Run (PR)` never ran for a UI-only pull request.** It listed `build-and-test` in
  `needs` although it consumes only Build Image's `eddi-ci-image` artifact. `build-and-test` gates on
  `backend`, which is false when a PR touches only `ui/`, and GitHub skips a job whose dependency was
  skipped *before* evaluating its `if` — so the job's own `code == 'true'` condition never got a say
  and the image that PR would publish went uncertified. Now `needs: [detect-changes, build-image]`.
  This is the same trap `UI Gate` and `E2E Gate` exist to avoid on the publish path, so
  `BuildQualityGatesTest` now grades it for every job: a pull-request-reachable job gated on `code`
  alone may not wait on `build-and-test` without `always()`. Mutation-checked — restoring the old
  `needs` fails the test naming `preflight-check`.
  - That broke `Build & Test` on the push: `DeploymentManifestsTest` still required both `sbom` and
    `preflight-check` to need `build-and-test`, so "don't publish untested code" was pinning the very
    dependency that skips the job. The assertion now holds for `sbom` alone, which uploads; for
    `preflight-check`, a dry run that pushes only to a registry inside the job, it requires
    `build-image` and forbids `build-and-test`. The local guard run had missed it because it named
    its test classes by hand; the re-run covered every test that reads `ci.yml`.
- **`.github/dependabot.yml` was in no filter a Java test reads.** `BuildQualityGatesTest` parses it
  to check the Docker ecosystems it declares stay in step with `base-image-check.yml`'s skip logic;
  Dependabot's own check validates the schema, not that contract. A PR changing only that file
  resolved `backend=false` and skipped the one test that owns it. Added to the `backend` filter
  alongside `README.md`, `AGENTS.md` and `.githooks/**`, and to the parity assertion.
- **The published SBOM described only the Maven half.** Both UIs are built into the jar, so their npm
  production dependencies are part of the shipped supply chain, and `cyclonedx-maven-plugin:makeBom`
  inventories Maven only. The `sbom` job now also runs `@cyclonedx/cyclonedx-npm` for
  `ui/manager` and `ui/chat` with `--package-lock-only` (reads the lockfile, so no `npm ci` and no
  install scripts) and `--omit dev`, which is what Vite actually bundles. Verified locally: valid
  CycloneDX 1.6, 192 components for the Manager and 134 for the Chat UI.
  - Three documents are uploaded rather than one merged BOM. Merging across ecosystems needs
    `cyclonedx-cli`, a GitHub-release binary we would have to fetch unverified, and hand-rolling the
    metadata/bom-ref/dependency-graph merge is how you get a plausible but invalid BOM. Each file is
    valid on its own and Dependency-Track, Red Hat certification and grype all ingest a set.
  - `ui/manager/.ds-sync` is deliberately not inventoried: it is design-sync tooling that never
    reaches the image.

## 🧩 chore(monorepo): EDDI-Manager and EDDI-Chat-UI move into this repository as ui/manager and ui/chat (2026-09-15)

**Repo:** EDDI (`chore/monorepo-migration`) — executes `planning/monorepo-migration-plan.md` (PR #670, Revision 3).

**Merge this PR with "Create a merge commit" — never squash.** The imported histories are the reason
the import exists; a squash flattens 1,275 commits into one.

### What changed

- **History import (§5.1).** `labsai/EDDI-Manager` `main` @ `0870ae87` (1,213 commits) and
  `labsai/EDDI-Chat-UI` `master` @ `71fa395` (62 commits) were rewritten with
  `git filter-repo --to-subdirectory-filter` on bare clones and merged with
  `--allow-unrelated-histories` — not `git subtree add`, which loses path-scoped `git log`.
  Verified: both imported trees are blob-for-blob identical to the source commits, no tags
  were imported (55 before and after), `git log -- ui/manager/package.json` returns 81 commits and
  blame shows the original authors. Gitleaks (v8.30.1, the CI version) over both rewritten
  histories: 0 findings, so no `.gitleaksignore` entry had to land on `main` first.
- **Post-import fixups.** The Manager's helper scripts moved from `.github/scripts/` to
  `scripts/`. Two of them computed their root as `../..` for the old depth, which would have
  written the OpenAPI snapshot one directory too high — fixed, with the two tests that import
  them. `.github/` (CI now lives in the root `ci.yml`), husky/lint-staged, `renovate.json`, the
  `deploy-to-local-eddi-repo` scripts and the Chat's tracked `dist/` are gone. The Manager
  lockfile was pruned of husky/lint-staged **by hand**: `npm uninstall` on Windows also drops the
  nested `@emnapi/*` entries that Linux `npm ci` requires.
- **The UIs build with Maven (§6–§7).** `manage.html`, `welcome.html`, `workforce.html` moved into
  `ui/manager` as Vite multi-page inputs sharing one hashed bundle; the Chat builds to `dist/`
  instead of `../EDDI/src/main/resources`. `frontend-maven-plugin` runs `npm ci` + `npm run build`
  for both in `prepare-package` (Node 20.20.2 vendored into `ui/node/`) — so `compile`, `test` and
  `quarkus:dev` never touch npm, while `package`, `verify` and `install` always build the UI — and
  `copy-ui-bundles` copies both `dist/` trees into the jar — without the MSW worker and without
  ever overwriting `index.html`, `robots.txt` or `landing-redirect.js`. 756 generated files are
  no longer tracked; `src/main/resources/META-INF/resources` holds exactly those three
  hand-written files. The `.gitignore` block, the default-resource excludes and the
  `maven-clean-plugin` fileset list the same 13 paths, so a stale pre-migration bundle on disk
  can neither be committed, nor copied into the jar, nor survive `./mvnw clean`.
  `-DskipUi=true` skips all of it.
- **Backend tests.** Five tests read the committed shells or assets and would have failed once
  those stopped being committed. The four resource tests now read stand-in shells under
  `src/test/resources/META-INF/resources`; `StaticAssetCachingTest` drops the `.manager-assets`
  manifest check (the orphaned-bundle problem it guarded against is gone by construction); the
  content-hash check on the built assets lives in `Build Image`, where those assets are produced.
- **CI (§8).** New jobs: `UI Manager Checks`, `UI Manager E2E (MSW)` and `UI Chat` (the Manager's
  former CI plus its MSW Playwright tier, and the Chat's typecheck and tests, as three parallel
  jobs), `UI Gate`, `Build Image` (jar + both UIs + image, built once with an npm cache and passed on
  as an artifact, after checking the four shells, the content hashes and the Chat bundle), `Backend E2E (mongodb|postgres)` (the Manager's API and
  full-stack Playwright tiers against the image built from the same commit, a check that every
  same-origin dependency of the four shipped shells answers 2xx, and a blocking OpenAPI snapshot
  check), `E2E Gate`, `CodeQL Analysis (UI)`. `Build Image` also asserts that the packaged
  `assets/` set equals what Vite just emitted: `copy-resources` never prunes, and a local build without
  `clean` really did package 913 assets against 737 built. `docker` no longer builds: it publishes the tested
  image. `preflight-check` certifies the same image. On a pull request `Build & Test` and
  `Integration Tests` gate on a new `backend` filter — `code` without `ui/**`, plus the UI markdown
  the documentation tests walk, kept equal by `BuildQualityGatesTest` — so a Manager, Chat or npm
  Dependabot PR skips the Java suite; on push and on tags they gate on `code`, so nothing publishes
  untested. `pom.xml` is not in the `ui` filter: the UI jobs never run Maven. The OpenAPI snapshot
  check runs on the MongoDB leg only and uploads the regenerated file when it fails.
- **Housekeeping (§9).** Dependabot npm entries for both UIs (replacing Renovate, carrying its
  react-router major-version block); the scheduled CodeQL scans TypeScript; the Manager's
  compose files take `EDDI_IMAGE` and set the two `HighValueSurfaceGuard` opt-outs whose absence
  had kept the Manager's backend E2E red since 2026-08-26; one Node pin in the root `mise.toml`;
  AGENTS.md, README, CONTRIBUTING and the Manager/Chat docs describe the new layout.

### Decisions and deviations from the plan

- **Fixes landed here, not as preparatory PRs in the source repos.** The Chat's `react-router`
  7.13.1 → 7.18.3 bump (two high advisories, six HIGH Trivy findings) and the compose opt-outs.
  Every gate that reads them (dependency-review, Trivy, Scorecard, the E2E job) reads this PR's
  head, and the source repos are about to be archived.
- **`CodeQL Analysis (UI)` is a separate job, not a language matrix on `codeql`.** A matrix renames
  the required `CodeQL Analysis` check, which would wedge every PR on a context that never reports.
- **`Build Image` labels the image by event.** A pull request gets the bare pom version (what the PR
  preflight certifies and asserts), a push the tag it is published under — exactly what the two
  builds it replaces did.
- **The OpenAPI snapshot check is blocking on PRs too.** In the Manager repo it was advisory there
  because the backend image tracked `latest`; here the backend is built from the PR.
- **Efficiency review (two independent reviewers, Fable 5 and Opus 5).** Both found the same costs:
  every local `compile`/`test`/`quarkus:dev` rebuilt both UIs (and deleted `node_modules` under a
  running `npm run dev`), UI-only PRs ran the ~20k-test Java suite, Maven Dependabot PRs ran the UI
  job, and the UI job ran Manager and Chat serially. Fixed as above. Rejected: defaulting `skipUi`
  to true (a local `package` — `install.sh --local`, `mise run docker-build` — would then silently
  build an image without a UI), and frontend-maven-plugin's incremental build options (its `npm`
  goal has none in 1.15.1 — checked in the plugin descriptor).
- **Phases 2 and 3 are one commit.** Deleting the committed bundles before Maven builds them would
  leave a commit whose jar serves a blank `/manage`.
- **The Chat `typecheck` script is now `tsc -b --noEmit`.** `tsc --noEmit` against its solution-style
  `tsconfig.json` checked nothing; plain `tsc -b` would have emitted into `dist/`.
- **Not ported:** the Chat branch `fix/release-6.2-polish` (two commits, never merged to `master`,
  never shipped). Port with `git format-patch` + `git am --directory=ui/chat` or drop it.
  The Manager's Stryker `mutation.yml` is not carried over (§13).

### Verification

All run locally on 2026-09-15 (Windows 11, JDK 25.0.1) unless marked Linux.

- **Import.** Both imported trees blob-identical to the source commits; 55 tags before and after;
  path-scoped `git log`, `--follow` and blame show the original commits. Gitleaks over both rewritten
  histories: 0 findings.
- **`-DskipUi=true`** (`clean package`): no frontend execution ran and `target/classes` holds no UI.
- **Dirty workspace, `package` without `clean`**, with stale `assets/index-STALE00.js`, `manage.html`,
  `chat.html`, `mockServiceWorker.js`, `img/loading-indicator.svg` and a `chat-ui.*.js` planted in
  `src/main/resources/META-INF/resources`: none reached `target/classes`, and both shells there are
  the freshly built ones — the default-resource excludes hold without help from `clean`.
- **`./mvnw clean`** deleted every planted file and both `dist/` trees and kept `index.html`,
  `robots.txt` and `landing-redirect.js`.
- **Full build:** `copy-ui-bundles` copied 742 Manager and 11 Chat files; 737 hashed assets; all three
  Manager shells load the same `assets/main-<hash>.js`; no `mockServiceWorker.js`, no root
  `logo_eddi.png`, no pre-migration `index-*` entry; the bundle carries `EDDI Demo 6.4.0` from
  `EDDI_VERSION`.
- **Linux (`node:20-bookworm`), the `UI Build & Test` steps from a `git archive` of the branch:**
  Manager `npm ci` (so the hand-pruned lockfile satisfies Linux npm), `audit:prod`, lint, i18n check,
  typecheck, Vitest with coverage, build, the build-output check, and all 234 Playwright MSW tests;
  Chat `npm ci`, typecheck, tests and build, with no stray `ui/EDDI`. All green.
- **Backend guard tests** (`StaticAssetCachingTest`, the four `Rest*ResourceTest`s,
  `BuildQualityGatesTest`, `ReleaseVersionSourceTest`, `DocumentationLinksTest`,
  `DocumentationAccuracyTest`, `ImportStyleTest`, `ComposeStackTest`, `DeploymentManifestsTest`,
  `StrictBoundaryShippedConfigsTest`, `RuleSetStoreShippedRulesetsTest`, `ChangelogRotationTest`,
  `DocumentedRestPathsTest`, `ConfigurationReferenceCoverageTest`, `DemoImageDockerfileTest`), run after
  every review fix and with this entry in place: 176 run, 3 failures — all in
  `DeploymentManifestsTest`'s PowerShell `create-secrets.ps1` cases, which fail identically on an
  untouched `origin/main` worktree on this machine (environmental).
- **Environmental, not this change:** locally `quarkus:build` ends with "Unable to establish loopback
  connection" on untouched `origin/main` too — it is the build-analytics ping; builds here pass
  `-Dquarkus.analytics.disabled=true`.
- **The image built from this branch** (`labsai/eddi:ci`), booted under the Manager compose files on a
  shifted host port, on **both MongoDB and PostgreSQL**: healthy within seconds; the `Verify the shipped
  shells` step, extracted verbatim from `ci.yml`, passed on both (487 same-origin dependencies of the
  four shells answer 2xx, the entry chunk carries the immutable `Cache-Control`, `mockServiceWorker.js`
  and a root `logo_eddi.png` answer 404).
- **OpenAPI snapshot against that backend: drifted**, exactly as the new blocking check is meant to
  catch. Refreshed in this commit (+8 operations: resource sharing, `/workspaces`, connection settings;
  −2: `GET /chat` and `GET /chat/{path}`, now hidden from the OpenAPI document), which made five
  `EXEMPT` entries in `openapi-contract.test.ts` stale; they are removed and the contract test passes.
- **The Manager's Playwright tiers against that image on MongoDB** (compose file as CI uses it, port 7070):
  API integration **44/44**. Full stack **34 passed, 1 failed** on the first run — the card test in
  `resources-crud.fullstack.spec.ts` still clicked the pre-v6 `resource-type-behavior` id, which
  `resources.tsx` no longer renders. Untouched Manager `main` (`0870ae87`) against the same image fails
  the same test, so it predates this change: the tier had not been able to boot a backend since
  2026-08-26 and never reached it. Fixed here, together with the spec's outdated known-failure header
  (with `60188c2bd` in the image all six resource types list). The spec runs in serial mode, so the one
  failure had retried the whole group twice; after the fix it passes **9/9 in 20 s**.
- **Not run locally:** the Playwright tiers on PostgreSQL (the image boots there and passes the
  shipped-shell step, above) and any of it on a GitHub runner — the first signal is this PR's own run.
- **Efficiency changes:** `./mvnw clean compile` and a `test` run executed **zero** frontend steps;
  `clean package` ran surefire, then all five frontend steps in `prepare-package`, then
  `copy-ui-bundles`, then the jar (737 assets, and the build-time OpenAPI document in
  `target/openapi`); the new `Verify the built and packaged UI` step, extracted from `ci.yml`, passes
  on that output; the guard tests (176, including the new `backend`-filter drift test) show only the
  3 environmental failures above.
- **Not verifiable locally:** the CI graph itself (job skips, artifact hand-off, fork PRs, required
  checks).

### Outside this repository (plan §9.5, §10) — still to do

- Branch protection: require `UI Gate` and `E2E Gate` (both always report; the jobs behind them
  are path-gated); keep `CodeQL Analysis` and `Build & Test` as they are.
- Do **not** enable GitHub's CodeQL default setup. It is `not-configured` (checked with
  `gh api …/code-scanning/default-setup`); every analysis comes from `ci.yml`, and turning it on
  would make GitHub reject the workflow's own uploads and fail the required `CodeQL Analysis`
  check. `CodeQL Analysis (UI)` already scans the TypeScript.
- After the first green `main` pipeline including Backend E2E: pointer READMEs, close the open PRs
  (Manager #72, #95, #140, #168, #207; Chat #19–#24, #26) with a link here, archive both repos.
- Local: `EDDI.code-workspace`, stale copies of the deploy scripts; run `./mvnw clean` once.

## 🏷️ chore: refresh README badges and set the project domain to eddi.technology (2026-09-15)

**Repo:** EDDI (`chore/deps-and-version-6-4-0`)

- `README.md` — the tests badge said `14,000+`; the last full unit run on this branch was 20,221
  tests, so it now reads `21,000+`. The coverage badge names both gates, `>90% instr / >80% branch`,
  matching the figures AGENTS.md already quotes.
- `application.properties` — `systemRuntime.projectDomain` moves from `eddi.labs.ai` to
  `eddi.technology`. Its only consumer is `HttpClientWrapper`, which builds the outbound
  User-Agent from it, so outbound calls now identify as `EDDI.TECHNOLOGY/<version>`. The other
  `eddi.labs.ai` references (OpenAPI contact URL, banner, Dockerfile image label, docs) are left
  as they are in this change.

## 🔀 chore(merge): origin/main into the 6.4.0 dependency branch (2026-09-15)

**Repo:** EDDI (`chore/deps-and-version-6-4-0`)

`origin/main` @ `13e8feb73` (PR #751, the connections review findings) merged in. Only this file
conflicted, and only because both sides added entries at the top: every entry from both sides is
kept, main's newer 2026-09-14 entries above this branch's dependency and release entries. No other
file was changed on both sides — the Manager UI assets and `pom.xml` bumps are this branch's alone.

## 🔁 fix(connections): the fourth PR #751 review round, with main merged in (2026-09-14)

**Repo:** EDDI (`fix/connections-review-findings`)

**Merge.** `origin/main` @ `52d031940` (PR #750, the 6.4.0 E2E fixes, and PR #746) merged in. Only this
file conflicted: both sides' entries are kept, as are both sides' Decision Log rows. The merged file
was over the 250 KB cap, so `scripts/rotate-changelog.py` moved 12 entries into the existing
`docs/changelog/2026-08.md` (no new archive, so `docs/SUMMARY.md` is unchanged).

**Review findings** — the open thread and the two outside-diff findings from the round-3 review body:

- **A reshaped connection still had its code redeemed.** `boundConnection` checked only that the
  name still belonged to the bound id, so a connection switched to `OAUTH2_CLIENT_CREDENTIALS` or
  `SERVICE` while the user sat on the consent screen had the authorization code exchanged, a refresh
  token minted and stored, and only then discarded by the post-write re-read.
  `RestConnectionAuthorization.callback` now refuses with `exchange_failed` before the exchange
  unless the document is still a `PER_USER` authorization-code connection
  (`takesPerUserGrants`, shared with `connectionStillTakesTheGrant`). The post-write re-read stays
  for an update landing after that check. Tests: `shapeChangedBeforeTheExchangeRedeemsNothing`;
  `grantStoredUnderAShapeTheConnectionNoLongerHasIsDeleted` now reshapes the connection inside the
  write window.
- **A stale callback's discard could delete a newer grant.** `discardGrant` deleted by
  `(tenant, name, principal)`, so a later link of the same account that replaced the grant between
  the write and the discard was removed with it. New `IConnectionGrantStore.deleteIfSealedWith`
  deletes only while the row still carries the access-token IV of the write being taken back
  (Mongo: filter on `accessTokenIv`; Postgres: `AND access_token_iv = ?`; the in-memory double
  mirrors it). `OAuthTokenService.persistNew` now returns the grant it wrote so the callback can
  name it. A `null` IV matches nothing — in Mongo `eq(field, null)` would also match a row without
  the field. Tests in `MongoConnectionGrantStoreTest`, `PostgresConnectionGrantStoreUnitTest`,
  `ConnectionGrantStoreCasTest` and every discard test in `RestConnectionAuthorizationCallbackTest`
  (which now also assert no keyed `delete`).
- **Docs:** `docs/connections.md` described plaintext-origin enforcement in terms of the property,
  which a stored `allowPlaintextRemoteOrigins: true` makes wrong. Both places now say "the effective
  `allowPlaintextRemoteOrigins` setting".

**Decision:** the discard is keyed on the IV rather than on `version`. `upsert` does not report the
version it wrote, and making it do so means `findOneAndUpdate` / `RETURNING` on both backends for
one caller. The IV is random per seal, so it names the write; its one blind spot is a DEK re-seal in
the milliseconds between write and discard, where the delete misses and the grant stays until
disconnect or connection deletion — the safe direction.

---

## ⚙️ feat(connections): runtime connection settings — no restart, properties pin (2026-09-14)

**Repo:** EDDI (`fix/connections-review-findings`, PR #751) · Manager counterpart on `feat/connection-settings`

The four deployment settings of the connections feature — `enabled`, `publicBaseUrl`,
`credentialEndpointAllowlist`, `allowPlaintextRemoteOrigins` — were properties only, so
turning the feature on or approving one more OAuth provider meant a restart, and enabling
it without a base URL refused the boot outright. They are runtime settings now, written
through `PUT /connectionstore/settings` and read back with their provenance.

**Why the properties-only argument did not hold.** It rested on "an operator, not an
administrator, approves where a client secret may go". Nothing else in EDDI draws that
line: `eddi-admin` writes the vault, and an httpcall header resolves any `${vault:…}` and
sends it to any host (`ApiCallExecutor` resolves vault references in headers, query, body
and URL with no destination binding), which `eddi-editor` may also author. A properties-only
allowlist therefore cost a restart and protected nothing from an administrator. What it
*does* still need protecting from — an LLM, an imported agent, a connection document
vouching for itself — is kept out by where the endpoint is exposed, not by a restart.

**Design.**

- **Precedence: pinned → stored → default.** A property or environment variable that is
  set *pins* its value: it wins, reads as `PINNED`, and a `PUT` that would change it is a
  **409** naming the property. That keeps the operator/administrator split available to a
  deployment that genuinely has one. Restating the pinned value, or omitting it, is
  accepted. Every default fails closed. The four property lines in
  `application.properties` are now commented out, because an uncommented line would pin.
- **No seeding.** A pinned value is never copied into the store, and a pinned field's
  previously stored value is carried forward untouched — so removing the property later
  falls back to what an administrator stored, not to a silent copy of the old pin.
- **Freshness.** `ConnectionsConfig` caches the stored document for 5 s (it is read on hot
  paths — every resolution asks about plaintext origins). The writing instance adopts its
  own write immediately; others see it within the TTL. A store read failure keeps the last
  values; before the first successful read only pins and defaults apply.
- **Validation at the write boundary** (`RestConnectionSettings`): the base URL must be a
  bare https origin (dev/test: loopback http), allowlist entries are canonicalised and
  de-duplicated, and a *remote plaintext* allowlist entry is refused — a credential endpoint
  must be https or loopback before a secret is sent, so it would approve nothing. The shape
  rules live once in `ConnectionSettingsRules`, shared with the boot and request-time checks.
- **The boot no longer refuses a missing base URL.** Only per-user OAuth linking needs it,
  and `POST /connections/{name}/authorize` now answers **400** naming the setting. A
  *pinned* base URL of the wrong shape still refuses the boot — it is operator configuration
  that only a restart changes.
- **Exposure.** `eddi-admin` only (`IRestConnectionSettingsRoleGateTest`). Not an MCP tool,
  not in export/import or Agent Sync, and excluded from the Platform Operator's write scope
  (pinned by a Manager test). The response carries the `redirectUri` to register at each
  provider and warnings for a configuration that saves but will not fully work. Each change
  is logged at INFO with the principal and the before/after values (none is a secret).
- **One document per tenant**, Mongo `connection_settings` (`_id = tenantId`) and Postgres
  `connection_settings` (nullable columns, `TEXT[]` allowlist); `tenantId` is `"default"`
  until multi-tenancy supplies one. Whole-document replace, last write wins.

**Files.** New: `connections/settings/` — `ConnectionSettings`, `ConnectionSettingsRules`,
`ConnectionSettingsView`, `IConnectionSettingsStore`, `MongoConnectionSettingsStore`,
`PostgresConnectionSettingsStore`, `IRestConnectionSettings`, `RestConnectionSettings`.
Changed: `ConnectionsConfig` (pinned/stored/default resolution, cache), `CredentialEndpointAllowlist`
(reads the effective allowlist per call), `ConnectionStartupGuard` (pinned-only refusal,
warning otherwise), `RestConnectionAuthorization` (`requirePublicBaseUrl`), messages in
`ConnectionResolver`, `RestConnectionStore` and `ConnectionConfiguration` naming both
handles, `DataStoreProducers`, `application.properties`, `docs/connections.md`,
`docs/configuration-reference.md`. Tests: `ConnectionsConfigStoredSettingsTest`,
`RestConnectionSettingsTest`, `ConnectionSettingsRulesTest`, both store tests, the role gate;
`ConnectionStartupGuardTest` and `RestConnectionAuthorizationCallbackTest` updated for the
new boot and authorize behaviour.

**Review round (Fable 5.1 review + a live boot against MongoDB).**

- **Unauthenticated writes are refused outside dev/test** while `authorization.enabled=false`
  (403), unless `eddi.connections.settings.allow-unauthenticated-writes=true`. The rationale
  "properties-only protected nothing from an administrator" is right for administrators, but on
  a deployment without OIDC `@RolesAllowed` is a no-op — and the shipped compose files run that
  way — so an *anonymous* caller could otherwise approve an origin for a client secret. Same
  narrow per-surface opt-out shape as `HighValueSurfaceGuard`; adding the path to that guard
  instead would have failed every existing unauthenticated boot.
- **A stored value hidden behind a pin is surfaced** (`Setting.shadowedStoredValue`) with a
  warning. Removing a restrictive pin brings a permissive stored value back; that must never be
  invisible.
- **Enabling at runtime runs the stored-connection report** (`ConnectionStartupGuard.reportStoredConnections`)
  that a boot with the feature off skipped. A store unreadable at boot says the report was skipped.
- **Restating a pinned value is compared before strict validation and normalised**: a trailing
  slash or scheme case on the base URL, and an operator-pinned plaintext allowlist entry, no
  longer produce a 409 or 400.
- **Postgres `updated_by` is `TEXT`** (an OIDC principal can exceed 255 characters); `Array.free()`.
- **Serialization contract.** EDDI omits null fields (`NON_NULL`), so an unset `value`,
  `redirectUri`, `updatedAt` or `updatedBy` is absent from the JSON, not `null`. Documented on
  `ConnectionSettingsView`; the Manager types and fixtures follow it.
- **Upgrade note**: a property set even to its default now pins (configuration-reference).

**Not done / open.** Cross-replica invalidation is TTL-only (no event bus). Changes are
logged, not written to the audit ledger — the ledger is conversation-task shaped. The
Manager's OpenAPI snapshot exempts the two new operations until it is refreshed against a
backend that has them.

---

## 🔁 fix(connections): the PR #751 review round — claims, clocks, export, plaintext and redaction (2026-09-13)

**Repo:** EDDI (`fix/connections-review-findings`)

Twenty-two review comments on PR #751 (CodeRabbit, Copilot, CodeQL, code quality), all
verified against the code and all valid. One commit per finding, each with a regression
test. Two of them correct fixes recorded in the entries below: R7 bound the contender's JVM
clock, which only moved the skew, and C3 enforced name uniqueness with scans that could not
see across replicas.

**Uniqueness and races.**

- **A durable name claim** (`10962ccab`). `IConnectionNameClaimStore` keeps one claim per
  (tenant, name) under a MongoDB unique index or a PostgreSQL unique constraint. A create
  claims first and answers 409 for a live holder; a stale claim (crashed create, failed
  release) is taken over by compare-and-set. The create records its id against its token,
  then writes the descriptor, and a descriptor failure is now a failed create. Connections
  that predate claims are found by the one remaining name scan and backfilled. The unused
  multi-holder scan, `IConnectionStore.idsOfName`, is removed.
- **Grants linked during an authType/binding change** (`e5db9c0a8`). A count before the
  update is not atomic with an OAuth callback. Both sides now re-check after their own
  write — the update counts again and deletes what appeared, the callback re-reads the
  connection uncached and discards its grant if the shape changed — so one of the two
  always sees the other.
- **The refresh lease uses the database clock on both sides** (`22f153147`):
  `CURRENT_TIMESTAMP AT TIME ZONE 'UTC'` on PostgreSQL, `$$NOW` on MongoDB. `claimRefresh`
  takes a duration. The lease is released when the claimant's re-read fails (`9cbbb83c2`).

**Credentials in transit and at rest.**

- **Plaintext remote origins need an opt-in** (`e0bf28a9b`):
  `eddi.connections.allow-plaintext-remote-origins`, default `false`. Refused at save (400),
  per request (`TARGET_NOT_ALLOWED`) and reported at ERROR at boot. Loopback http is always
  allowed.
- **A plaintext token URL is refused before the client secret is sent** (`1adb43c22`);
  https, or http to a loopback host, shared with save-time validation.
- **Non-admin reads are redacted** (`414982f3d`). An editor reading a connection gets
  `clientSecret`, `passwordRef`, `valueTemplate` and each `extraAuthParams` entry only if it
  passes the write-time rule; a legacy literal is replaced by a marker. Admins see the
  stored document so they can fix it.
- **Log lines sanitize user-controlled values** (`a9f87b184`, plus the four refresh-path
  lines in `OAuthTokenService`), closing CodeQL `java/log-injection`.

**Export, import and grants checking.**

- Export authorizes VIEW on every referenced connection and refuses without it
  (`2d85bce21`); only a dangling reference is skipped, every other failure fails the export
  (`c35005ceb`).
- `strategy=upgrade` imports the archive's connections too (`3b65c91cf`), and an import that
  cannot account for a connection it created (no resource URI, no descriptor) fails and
  rolls it back (`5c602dd91`).
- The vault-grant check expands `${vars:}` in the connection's own tenant (`5735c021d`) and
  before the connection scan as well as the vault scan (`5a86bbc98`).
- A malformed stored origin no longer aborts the startup report (`6a8b73ac9`); the allowlist
  docs no longer mention discovery endpoints (`3cdf0780a`); two mocked `ResultSet` stubs no
  longer read as leaks (`830acb20e`).

**Decision — export refuses rather than skips** a connection the caller cannot view. An
archive quietly missing a connection imports into an agent whose references do not resolve,
and nothing says why.

**Decision — the claim is a separate store, not an index on the config document.** The
versioned document store cannot carry a unique index on a field inside the document.

**Upgrade notes.**

- Connections with a remote `http://` origin stop resolving until the new property is set
  or the origin moves to https.
- MongoDB 4.2+ is required (`$$NOW`); the project documents 6.0+.
- New storage, created lazily: collection or table `connection_name_claims`.
- A duplicate name on create answers **409**, not 400.
- During a rolling upgrade, a replica without claims can still race a create briefly.

**Not changed:** CodeRabbit's docstring-coverage pre-merge warning, which counts every
touched function; the project documents behaviour at class and non-obvious-method level.

**Second round.** The push drew nine more threads from CodeRabbit, CodeQL and GitHub code
quality.

- **The OAuth callback is bound to the connection's id, not its name** (`bcddbb4a2`). A
  connection deleted and re-created under the same name while the user was on the consent screen
  received a grant issued for its predecessor's client, and its own allowlist decided where that
  token went. The state row now carries the connection id (a MongoDB field; a PostgreSQL
  `connection_id` column added with `ADD COLUMN IF NOT EXISTS`). The callback exchanges nothing
  unless the name still resolves to that id, reads the connection by id uncached rather than from
  the name-keyed registry, and after storing the grant re-checks id and shape and discards it on a
  mismatch.
- **A method CodeQL read as a permission check is renamed**, `requirePlaintextOriginsPermitted` to
  `refusePlaintextRemoteOriginsUnlessAllowed`. `java/tainted-permissions-check` matches any
  one-argument method whose name contains "permitted"; this one validates input, and who may
  write is `@RolesAllowed("eddi-admin")`. Renamed rather than dismissed, so no alert needs an
  admin's judgement.
- **The PostgreSQL store tests assert resource closing** (`f7be4653f`). The code-quality leak
  findings pointed at Mockito stubbing expressions, which acquire nothing, and last round's
  restyling only moved the warning. The tests now check that every connection, statement and
  result set the name-claim and grant stores open is closed, on the failure paths too.
- **The in-memory grant store validates the refresh lease first** (`192895d29`), as both real
  stores do; it used to leave a claim with no expiry behind on a null lease.
- **The plain-text variable test proves expansion ran** (`fbe3bd213`).

**Upgrade note:** an account link started before the upgrade and finished after it is answered
`invalid_state`, because its state row carries no connection id. States live ten minutes; the user
starts the link again.

**Third round.** Two more threads. CodeRabbit found that `authorize` still built the consent
URL from the name-keyed registry cache while taking the connection id from the store, so right
after a delete and re-create a replica could send the user to the predecessor's consent screen and
fail at the callback. `authorize` now reads the connection by id at its current version, uncached,
and uses that one document for validation, the state and the URL; the registry it no longer
reads is dropped as a dependency. A further code-quality "leak"
on a mocked `ResultSet` in the name-claim store test is the same false positive as round two; that
test already asserts the store closes it.

**Companion:** labsai/EDDI-Manager#208 answered its own review round (a complete reference
before the chip, retries only on network/5xx, the name grammar in references, Retry through
the `Button` primitive).

---

## 🔐 fix(connections): runtime and security findings from the connections review (2026-09-13)

**Repo:** EDDI (`fix/connections-review-findings`)

Thirteen findings from the code review of the connections feature, runtime and security side
(the config/store/docs findings are on a sibling branch). One conventional commit per finding;
every fix carries a regression test that fails without it.

**Credentials that leaked or went missing.**

- **R1 — connection-owned headers persisted in plaintext.** `RequestRedactor` recognised a
  credential only by conventional header name, a `${vault:` marker, or value shape. A `STATIC`
  connection on `X-Amp-Id` or a `CALLER_SUPPLIED` one on `X-Gnowbe-Key` matched none, so the live
  value was written to MongoDB and shown to a HITL approver. `buildRequest` now returns the header
  names a connection filled, and both the persisted request map and the approval preview redact them
  unconditionally, case-insensitively. MCP and A2A persist no request headers.
- **R2 — no `CallerIdentity` on turn 0.** `startConversation` bound the `ResolutionPrincipal` around
  the synchronous CONVERSATION_START turn but not the caller, so `${caller:token}` and every
  `CALLER_SUPPLIED` connection failed closed on turn 0 with advice about scheduled runs. The identity
  is captured once, binds the start turn, and the previous binding is restored (nested starts).
- **R5 — principal not propagated to cascade/batch threads.** `callerIdentityContext.propagate`
  carried the caller only; a `PER_USER` connection inside an agent-mode cascade step or a
  fire-and-forget batch was refused. `ResolutionPrincipalContext` gains `propagate`/`withPrincipalSupplying`,
  and `CallerIdentityContext.propagate` composes both — the single helper mid-pipeline dispatches
  use, so the next binding is added there. The explicit `withIdentity(...)` sites stay caller-only:
  they dispatch from request threads where the member conversation binds its own principal, or
  compose with `withPrincipal` themselves (the HITL resume).

**The token endpoint.**

- **R3 — redirects followed with the body.** `sendValidated` re-implements redirect following
  (method and body preserved on 307/308) and validates the hop against SSRF rules only. A token
  request carries the client secret and the refresh token or code, so an allowlisted endpoint
  answering 307 re-sent all of it elsewhere, while docs and Javadoc claimed otherwise.
  `SafeHttpClient` gains `sendValidatedNoRedirect` and `sendNoRedirect`; any 3xx from a token
  endpoint is `TOKEN_ENDPOINT_UNAVAILABLE` with a message saying a token endpoint must not redirect.
- **R4 — transient failures marked terminal.** An access token that would not unseal inside the
  refresh claim, and a 200 whose body is not a token response (HTML maintenance page, empty body,
  no `access_token`), were `GRANT_UNUSABLE` and wrote `REFRESH_FAILED`. Both are transient now; only
  `invalid_grant` / `invalid_client` / `unauthorized_client` is terminal.
- **R6 — escaping exceptions, and on-prem IdPs.** The callback caught only `ConnectionException`
  after claiming the state; an `IllegalArgumentException` from URL validation or an
  `IllegalStateException` from the store reached the browser as a 500 with the state consumed.
  Every `RuntimeException` after the claim is counted `exchange_failed`, logged at ERROR (class name
  only) and answered 303. `refreshAsClaimant` wraps the same as `TOKEN_ENDPOINT_UNAVAILABLE` (503).
  The token request no longer goes through the SSRF address block: the credential-endpoint
  allowlist is a stricter rule (an exact operator-listed origin), so an on-premises IdP on a private
  network is usable; scheme and host are still validated, nothing else gets the exemption.
- **R7 — Postgres lease vs the DB clock.** `claimRefresh` compared a JVM-written lease against
  `CURRENT_TIMESTAMP`; app/DB skew shortened the lease and let a second replica refresh mid-flight.
  The JVM instant is bound, as `PostgresOAuthStateStore` and the Mongo store already do.

**What the operator is told.**

- **R8** — with `authorization.enabled=false` every request is anonymous and its credential headers
  are dropped, so `NO_CALLER_CREDENTIAL` now says the deployment cannot accept one and names the fix,
  instead of claiming the request carried nothing.
- **R9** — the four OAuth/refresh meters were unprefixed and registered through a helper the
  `MetricsDashboardCoverageTest` regex cannot see; they are `eddi.connection.*` now, registered via
  `increment("eddi.…")`, charted in four new panels, and listed in `docs/metrics.md` and the
  `docs/connections.md` table. `UNSUPPORTED_PLACEMENT` was mapped to 400 but never thrown —
  `ApiCallExecutor` now throws it for a `${connection:…}` outside a header.
- **R10** — the MCP discovery warning named `PER_USER` for `CALLER_SUPPLIED` too and A2A logged
  nothing; `ConnectionResolver.bindingOf` names the actual binding for both, and routes a registry
  read failure through `countLookupFailure`. RFC 9728 discovery is not implemented, and the
  allowlist Javadoc and "Two allowlists" section no longer claim discovery endpoints.
- **R11** — a cached `STATIC`/`BASIC` document without `staticAuth` NPE'd, which the MCP failure
  classifier fed to the circuit breaker; both refuse with `INVALID_CONFIGURATION`, and BASIC with a
  null username refuses rather than sending `null:password`.
- **R12** — `VaultGrantChecker` skipped an unreadable connection at DEBUG (its secrets counted as
  granted); it is now a violation naming the connection. `${vars:}` is expanded through
  `GlobalVariableResolver` before the vault scan, on the connection hop and the general scan alike.
- **R13** — tests for `X-EDDI-Connection-Credential` parsing, the HITL resume bindings, the
  `lease_expired` branch (via a package-private await-timeout seam) and a failing `REFRESH_FAILED`
  write, which no longer replaces the provider's verdict with a raw store exception.

**Decision — `propagate` carries both bindings; `withIdentity` does not.** A wrapper that carries
one of the two thread bindings and not the other is the drift R5 fixed, so the snapshot-current
helper composes them. The explicit-identity helpers are used where the principal is deliberately
different (HITL resume) or established later from stored memory (group members), so they stay
single-purpose rather than silently overriding a principal the caller set.

**Decision — the credential-endpoint allowlist outranks the SSRF address block for the token
endpoint only.** An exact origin an operator wrote down is a stronger statement than "not a private
address"; the httpcalls path keeps `eddi.security.ssrf-protection` untouched.

**Not verifiable here:** `SafeHttpClientTest` binds a loopback server, which this sandbox refuses at
`HttpServer.create` (pre-existing for the whole class); its three new no-redirect cases run in CI.

---

## 🧩 fix(connections): configuration, store and export findings from the connections review (2026-09-13)

**Repo:** EDDI (`fix/connections-review-findings`, merged from `wip/connections-config-findings`)

Thirteen findings from the connections code review, each its own commit with a regression test
that fails without it. The runtime findings of the same review are on a sibling branch; the
two meet in `docs/connections.md` and here.

**Write-boundary validation (`ConnectionConfiguration`):**

- **`valueTemplate` literal text is now checked** (C1). The old check inspected only the `${…}`
  segments, so `sk-live-abcdef${vault:unused}` saved while the docs said it was refused. Rule:
  every `${` must be a well-formed `${vault:…}`/`${vars:…}` reference (an unclosed brace or a
  key over 256 chars used to count as literal text), at least one reference, and each literal
  segment ≤ 32 chars with no run of ≥ 12 key characters. Scheme prefixes pass; a key does not.
- **The name has a grammar** (C2): `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`, refused rather than
  trimmed. `ConnectionReference` stops at `/` and `}`, the credential header splits at the first
  space, so `acme/jira` resolved as tenant `acme` and a name with a space resolved for nobody.
- **`extraAuthParams` values are checked** (C7): no `${…}` reference (it would be resolved into a
  browser-visible URL), ≤ 512 chars, no credential-shaped prefix (`sk-`, `xox?-`, `gh?_`,
  `AKIA`, `eyJ`, `Bearer`). The key denylist gains the parameters EDDI composes itself
  (`redirect_uri`, `state`, `code_challenge`, …), normalised so `Redirect_uri` is caught.
- **`timeoutMs` is bounded 1..60000** at save time (C9); the token client's own ceiling still
  clamps at use. A non-loopback `http://` origin in `baseUrlAllowlist` stays accepted but is
  logged at WARN when saved and again by the startup guard.

**REST store (`RestConnectionStore`):**

- **Name uniqueness is no longer check-then-act** (C3). Creates of one `(tenant, name)` are
  serialised on a striped lock inside the JVM; after the write lands the store is asked again
  who holds the name (`IConnectionStore.idsOfName`, oldest-first). **Review fix:** the first
  version of this left the descriptor to `DocumentDescriptorFilter` *after* the method
  returned — outside the lock — so the lock guarded nothing: a second create on the same
  node took it the instant the first released it, scanned, found no descriptor yet, and both
  landed. `RestConnectionStore` now writes the descriptor itself inside the lock (the filter
  finds it and does nothing; `RestImportService.recordCreatedConnection` writes one only when
  missing). Our own id is expected in the post-write scan and filtered out; the rule stays
  "any other holder visible now wins" — ours is removed permanently, descriptor included, and
  the caller gets 409. Chosen over "oldest wins" because under asymmetric visibility the latter
  duplicates the name; the cost is that two replicas seeing each other both stand down and both
  callers retry. What remains is replication lag between nodes.
- **Duplicate goes through `validateForWrite`** (C4) — it skipped the deployment checks.
- **`CALLER_SUPPLIED` needs OIDC** (C5): `CallerIdentityContext` drops the credential header for
  an anonymous identity, so with `authorization.enabled=false` the connection saved and failed
  every call as `NO_CALLER_CREDENTIAL`. Now 400 at the write boundary and a stored-state report
  at boot, like `PER_USER`.
- **An `authType`/`binding` change with linked accounts is a 409** (C6), naming the count and
  the way out (`DELETE /connections/{name}/grant`, or delete the connection). Re-saving a
  `PER_USER` OAuth connection as `STATIC` left every user's refresh token at rest under a name
  the resolver never read again. `IConnectionGrantStore.countByConnection` added (Mongo,
  Postgres, in-memory double) — the only change under `connections/grants/`.
- **Editors may list and read connections** (C11): `eddi-editor` on the descriptor listing and
  the single read; every write stays admin-only. A document carries references only.

**Startup guard:** dev/test now require a bare origin and accept plain http on loopback only,
and the scheme is compared case-insensitively in every profile (C8); a first-release
`OAUTH2_AUTHORIZATION_CODE` + `SERVICE` document is reported with the fix (C12).

**Export/import (C10):** an agent archive now carries the connections its configs reference,
as `connections/{connectionId}.connection.json` — document only, never a grant. Two defects
made this necessary rather than nice: `AbstractBackupService` had no connection entry, and
`SecretScrubber` redacted `${connection:jira}` in an `Authorization` header to
`${vault:REDACTED}`, so the reference died before the archive was written. **Review fix:** the
scrubber's exemption is for a value that *is* exactly one `${connection:…}` reference, not one
that contains it — every outbound path refuses a mixed value (`ConnectionReference.requireSole`),
so a "contains" exemption only kept `Bearer sk-… ${connection:jira}` legible. Import creates a
connection only when the name is free — an existing one is never overwritten — through
`RestConnectionStore.createConnection` (same validation, deployment checks and lock as REST);
a refused document is skipped with its reason and counted in `X-Connections-Skipped`. The
descriptor is written by the store inside its name lock; the import writes one by hand only
when it is missing, as `createResourceDirect` does. Live sync still does not carry
connections; said so under Limitations. `AGENTS.md` §5.5 lists the file and counts thirteen.

**Docs (C13):** `configuration-reference.md` no longer describes
`credential-endpoint-allowlist` as where resolved credentials go; it bounds the client secret.

**Files:** `configs/connections/**`, `connections/ConnectionStartupGuard.java`,
`connections/grants/{IConnectionGrantStore,MongoConnectionGrantStore,PostgresConnectionGrantStore}.java`,
`backup/impl/{AbstractBackupService,RestExportService,RestImportService}.java`,
`secrets/sanitize/SecretScrubber.java`, `docs/{connections,configuration-reference,import-export-an-agent,agent-sync-architecture}.md`,
`AGENTS.md`, and the tests named in each commit.

**Not done / for the runtime branch:** the resolver still reads `timeoutMs` through the
client's own clamp (fine, now documented); `SecretRedactionFilter` was not touched. A legacy
document written before C1 could carry a literal in `valueTemplate` and is now readable by
editors (C11) — re-saving it fails validation, which is the signal to fix it.

**Review pass — token URL syntax check ran after the URI was built.** R6 added
`UrlValidationUtils.validateUrlSyntax` to `OAuthTokenClient.exchange`, but after
`URI.create(tokenUrl)`, so a token URL the allowlist accepts once trimmed but that will not
parse (trailing whitespace on a document written straight to the store) still surfaced as a raw
`IllegalArgumentException` — not a `ConnectionException`, which on the service-grant mint path is
exactly what R11 keeps out of the MCP circuit breaker. The check now runs first, its parsed URI
is the one fetched, and a failure is `INVALID_CONFIGURATION` naming `oauth.tokenUrl`.

---

## 🧪 fix: the defects a full end-to-end run of 6.4.0 found (2026-09-13)

**Repo:** EDDI (`fix/e2e-test-findings`, from `origin/main` @ `d0832d9da`)

A full E2E pass over a running 6.4.0 exercised 342 REST operations, all 84 MCP tools, 28 single-agent
variations and every group style (Anthropic `claude-sonnet-5` via `${vault:anthropic-key}`). It
confirmed 15 bugs and ~30 usability defects. **S1 (a vault-resolved key persisted in an httpcall body)
was retracted as a false positive**: the check had matched the `<REDACTED>` placeholder, and
`RequestRedactor` already scrubs bodies. Everything else is fixed here with regression tests.

**Security**

- **S2 — a `scope: "secret"` input survived in derived forms.** `PropertySetterTask` scrubbed only
  exact copies, but the parser tokenizes input (`unknown(sk-live_abc)`), so the key stayed in
  `expressions:parsed`, `expressions:matches` and `intents`. Those are now dropped wholesale once the
  input is scrubbed. The audit ledger was worse: parser and rules entries, submitted before the
  property setter ran, carried the plaintext into an append-only signed ledger. New `TurnAuditBuffer`
  holds a turn's entries until the pipeline finishes (say and resume paths), then redacts the recorded
  input everywhere in the entry before submitting. `MemoryKeys.SECRET_INPUT_PLACEHOLDER` is the shared
  marker.
- **S3 — unknown `environment` silently targeted production.** `EnvironmentParamConverterProvider`
  makes every JAX-RS `Deployment.Environment` parameter strict: `staging` → 400 naming the valid values.
- **S4 — config-authored httpcalls reached the cloud metadata service** with SSRF protection off (the
  default). `UrlValidationUtils.rejectCloudMetadataTarget` now refuses `169.254.169.254`,
  `fd00:ec2::254`, `100.100.100.200`, `metadata.google.internal` and the link-local ranges —
  including hostnames resolving there — on httpcall, MCP and A2A paths **regardless of the setting**.
  With protection off the httpcalls client still follows redirects, so `HttpClientModule` wraps its
  redirect handler and refuses any hop onto the metadata service (on a worker thread — the check can
  resolve DNS). Private/loopback targets stay reachable (that is what opting out is for).
- **S5 — export rewrote `modelName: claude-sonnet-5` to `${vault:REDACTED}`** (the entropy heuristic);
  the imported agent failed every turn. Model identifier fields are structural for `SecretScrubber`.
- **B16 — an unresolvable `${vault:…}` was sent to the provider as the key.** `SecretResolver.requireResolved`
  fails closed, naming the parameter and reference (never a value), in `ChatModelRegistry`,
  `EmbeddingModelFactory` and `EmbeddingStoreFactory`.
- **W16** `TRACE`/`TRACK` → 405 (`HttpMethodGuard`). **W17** input over
  `eddi.conversations.max-input-chars` (default 200000) is refused before any paid call: 413
  `input_too_large` on REST and SSE (checked before the stream opens, so it is a real status, not an
  `error` event), 400 on the OpenAI API, invalid params over A2A. **W15** plaintext credentials in LLM
  configs are warned about at save (not rejected — setup falls back to plaintext without a vault),
  including Hugging Face `accessToken` and Azure OpenAI `nonAzureApiKey`. **B12** a declared image/PDF whose bytes carry no
  signature is rejected.

**Engine / metrics**

- **B6 — every priced tool failed in production.** Gauge `eddi.tool.costs.total` and counter
  `eddi.tool.costs{tool}` both render as `eddi_tool_costs_total`; Prometheus refused the second.
  Gauge renamed **`eddi.tool.costs.accrued`** (dashboards, `docs/metrics.md` and alert examples
  updated); meter failures no longer fail cost accounting, and a cost-tracking failure no longer
  replaces a tool result. *Breaking for anyone querying the old gauge name.*
- **B7 — shared artifacts were invisible to other members.** All members run as one user, so under
  USER cache scope `listArtifacts()` served a stale "no artifacts". `ToolCacheService.isCacheable`
  excludes every `@Tool` of the artifact, group-task, dynamic-agent, memory and recall tool classes
  (derived reflectively). The discuss/continue/human-input responses now attach artifacts too.
- **B14** rule pauses kept `hitlPauseType: null` (`clearToolPauseState` cleared it); now `RULE`, and
  stored legacy pauses report `RULE` in the inbox. **W25** the audit `modelName` was the provider type.
- **A1** active conversations no longer require `agentVersion`. **W12** memory search splits the query
  into terms (`MemorySearchTerms`) — "dog name" finds `dog_name`, and `%`/`_` are no longer wildcards.

**Groups**

- **B8 — `create_sub_agent` never inherited the parent's key.** Inheritance read the parent over the
  REST loopback from the LLM tool thread, which has no request to forward credentials from; the
  failure was swallowed. It now reads the in-process stores, and a failed inheritance says why.
- **B9** `GroupMember` defaults a null `memberType` to AGENT — the ops-task-force template found zero
  bidders. **B15/W5/W6** new save-time errors: member without `agentId`, negative
  turns/retries/timeouts/caps/repeats, preset DEBATE/DEVIL_ADVOCATE without their roles, nesting
  cycles; template instantiation rejects non-existent agents; RAG rejects unknown `embeddingProvider`.
  Every shipped template passes (asserted). **W1/W7** enabling artifacts/tasks/dynamic agents logs the
  `enableBuiltInTools` prerequisite.
- **W3** `LAST_SYNTHESIS` accepts `**Option A:**`, list markers, `.`/`)` separators, any case.
  **W4** CRITIQUE without `targetEachPeer` reviews all peers (`TEMPLATE_CRITIQUE_PANEL`) instead of an
  empty target. **W8** EXECUTE without PLAN materializes configured tasks.

**API consistency**

- **W18/W32** agents referencing a malformed or non-existent workflow → 400; deleting an agent
  undeploys its live versions. **B10** bad snippet name / missing patch op → 400 (were 500). **B11**
  `/actions` skips steps without a URI (NPE → 500 on every real workflow) and names `workflowId`.
  **B13** A2A cancel of a finished task → not cancelable. The task's state is recorded per task
  (`a2aTaskMapping:state`), not inferred from its conversation, which a completed turn leaves `READY`
  for the context's next task: `tasks/get` answers `completed`/`canceled`/`failed` from that record. **W19** MCP discover-tools reports a refused
  configuration as 400. **W20** tool costs resolve by slug. **W21** unmatched endpoint filters are
  reported. **W22** aliased extensions listed once. **W23** unknown ingestion id → 404. **W24** channel
  descriptors get their name (create/update descriptor-version lag). **W26** `list_agent_resources`
  maps v6 step types. `read_conversation`'s `returningFields` passes sections to the service and
  filters output keys (it returned an empty snapshot for its own example). **W13** snippets read their
  current version. **W14** template preview resolves `{vars.*}`. **W29** setup states the streaming
  backstop. `apply_agent_changes` reports a failed redeploy.

**Docs:** security (metadata block), configuration reference, metrics, dashboards, group
conversations (roles, options, cadence semantics, async approve/human-input, cycles, critique, EXECUTE
without PLAN, cache), hitl, langchain (`anchorFirstSteps` is token-window only), user memory search,
MCP client notes (protocol-version warning, non-idempotent retries).

**Review follow-ups** (independent review of the whole change): the audit buffer now redacts every
input form any entry recorded from *every* buffered entry — including task-failure entries, which carry
no `userInput` but can quote the token, and entries built after the scrub; a resolved RULE pause no
longer leaves `hitlPauseType: RULE` on a READY conversation (it logged a stale-state WARN on every later
turn); `updateAgent` checks EDIT on the agent before the workflow-existence lookup (no existence oracle,
and a workflow the caller cannot view is a 403 by intent); the OpenAI adapter maps the input cap to
400 `input_too_large` instead of 500; a PDF header anywhere in the first 1024 bytes counts as a PDF;
`EmbeddingModelFactory` trims the provider as `RagConfiguration` does;
`eddi.conversations.max-input-chars` is declared in `application.properties` and documented in the
conversation table. A later workflow of a multi-workflow agent sees empty parser data on a turn whose
input was vaulted — deliberate, noted in `PropertySetterTask`. `EnvironmentParamConverterProvider` and
`HttpMethodGuard` are unit-tested only; an integration test through the Quarkus stack is still open.

**Not changed, by design:** `${eddivault:` is a supported legacy alias; GDPR export `complete:false`;
sync rejecting loopback sources.

## ⬆️ chore(deps): update Quarkus to 3.39.3 and langchain4j to 1.20.0 / 1.20.0-beta30 (2026-09-13)

**Repo:** EDDI (`chore/deps-and-version-6-4-0`)

- Bump Quarkus platform from `3.39.2` to **`3.39.3`** in `pom.xml`.
- Bump `langchain4j.version` from `1.19.0` to **`1.20.0`** and `langchain4j-beta.version` from `1.19.0-beta29` to **`1.20.0-beta30`** in `pom.xml`. Both lines are updated together to maintain module version alignment.

Also checked Docker base image statuses across the repository:
- **Production image** (`src/main/docker/Dockerfile`): `registry.access.redhat.com/ubi10/openjdk-25-runtime:1.24` pinned at `@sha256:c49d36c03d0a9472935b9f318f709c4cf158afa2dc204099f1f463cfbf9d4626` is already current (remote registry matches digest; tag 1.25 and ubi11 do not exist).
- **Sidecar image** (`mcp-sidecar/Dockerfile`): `ghcr.io/sparfenyuk/mcp-proxy` pinned digest is current with latest.
- **Demo image** (`src/main/docker/Dockerfile.demo`): newer digests exist on Docker Hub for `maven:3.9-eclipse-temurin-25` and `eclipse-temurin:25-jre`.

**Files touched:**
- `pom.xml` — updated `quarkus.platform.version`, `langchain4j.version`, and `langchain4j-beta.version`
- `docs/changelog.md` — this entry

## ⬆️ chore(release): EDDI 6.4.0, Quarkus 3.39.1, and every safe patch/minor ahead of the release (2026-08-30)

**Repo:** EDDI (`chore/deps-and-version-6-4-0`)

Two things that belong in one branch, because the version bump is only meaningful once the
dependency state it will ship is settled: the release number moves `6.3.0` → **`6.4.0`**, and
every update `versions:display-dependency-updates` reported for the artefacts **we pin ourselves**
is taken, provided it is a patch or minor of a GA release.

### Dependencies

Quarkus platform `3.38.3` → **`3.39.1`**. The previous bump (aa6c48bfa) deliberately skipped
`3.39.0.CR1` as a pre-release; `3.39.1` is GA, so the same reasoning now argues for taking it.

Also taken: `quarkus-mcp-server` 1.13.1 → 1.13.2, `classgraph` 4.8.192 → 4.8.194, `jsoup`
1.23.1 → 1.23.2, `json-schema-validator` 1.5.4 → 1.5.9, `bson4jackson` 2.15.1 → 2.18.0.

`jackson-dataformat-csv` and `jackson-dataformat-xml` are now pinned to **2.22.2** alongside
`jackson-core`/`jackson-databind`. This is new managed state, not a version bump of something we
already pinned, and the reason is skew: the Quarkus BOM manages the whole Jackson family at
2.22.0, and overriding only core and databind for the two advisories left the two dataformat
modules we declare a patch behind their own core. Same family, same patch.

**Deliberately not taken**, so the release ships a stable state: `jsonschema-generator` 5.0.0,
`json-path` 3.0.0, `json-schema-validator` 3.0.7, `bson4jackson` 3.2.0, `testcontainers` 2.0.5,
`vertx-web-client` 5.1.6, `wiremock` 4.0.0-beta.38, `quarkus-mcp-server` 2.0.0,
`maven-compiler-plugin` 4.0.0-beta-5, `maven-surefire-plugin` 3.6.0-M1 and
`reactor-netty-http` 1.4.0-M1 — every one a major jump, a milestone, or a beta. `mockito-core`
5.21.0 → 5.23.0 and `snakeyaml` 2.6 → 2.7 are managed by the Quarkus BOM, so they are the
platform's to move, not ours.

**The security pins survive the platform bump**, which was checked rather than assumed —
`dependency:list` on the built tree resolves `jackson-core`/`jackson-databind` 2.22.2,
`postgresql` 42.7.13, `bcprov-lts8on` 2.73.12.1, `jinjava` 2.8.4, `reactor-netty-http` 1.2.8 and
`jnats` 2.26.2. The `ban-jackson3` enforcer rule still passes, so the Elasticsearch client has
not smuggled `tools.jackson` back in under 3.39.1.

### Version

`6.3.0` → `6.4.0` across the same artefact set the previous two bumps used (7a64f9c84,
c0835c98d), rather than a blanket grep:

- **Build/runtime** — `pom.xml`, `application.properties` (`projectVersion`,
  `smallrye-openapi.info-version`, `container-image.additional-tags`), `OpenApiConfig` `@Info`,
  `Dockerfile` `EDDI_VERSION` build arg.
- **Deployment** — helm `Chart.yaml` appVersion + `values.yaml` image tag, k8s deployment and
  quickstart (version labels, pinned image tag, cosign/crane comment examples),
  `redhat-certify.yml` workflow input default.
- **Docs** — `build-reproducibility.md`, `redhat-openshift.md` and `developer-quickstart.md`, the
  three pages whose copy-pasteable commands name the current tag. The per-page version headers
  are already dynamic release badges.

Two differences from the previous bump's file set. `src/main/resources/initial-agents/` no longer
exists, so there is no bundled `Agent+Father-<version>.zip` to rename and no
`available_agents.txt` to follow it. And `developer-quickstart.md` is new to the set — its
`EDDI_VERSION=6.3.0 docker compose up -d` was added after the 6.3.0 bump.

**Left at 6.3.0 deliberately**, because these are historical facts and not claims about the
current release: every `@since 6.3.0` and `@Deprecated(since = "6.3.0")`, the "pre-6.3.0
behaviour" notes in `application.properties`, `docs/hitl.md` and `docs/configuration-reference.md`
describing the `eddi.hitl.tool.task-approvals.mode=replace` legacy path, and the worked examples
in `release-signing.md` / `release-versioning.md`, which illustrate the tagging flow rather than
name the shipping version. The `6.3.0` in the bundled Manager JS assets is built output from the
EDDI-Manager repo and moves when that bundle is rebuilt.

### Verified

`mvnw compile` is green. The full unit run is **20,221 tests, 8 failures, 213 errors, 3 skipped**,
and every one of those 221 is this sandbox rather than the bump: `Unable to establish loopback
connection`, `failed to create a child event loop`, `IOReactorException: Failure opening selector`
(no socket binding), or `Could not find a valid Docker environment` (the Mongo and Postgres
container stores). Causation was checked rather than assumed, because Quarkus is a platform bump
and "environmental" is exactly what a real regression would hide behind: the nine affected classes
were re-run against the pre-bump `pom.xml` and produced **151 tests, 8 failures, 123 errors** — the
same counts, and `diff` over the 146 reported error and failure lines shows the *same test methods*
failing in the same way before and after. CI is the gate for the socket- and Docker-bound cases.

---

## ⚙️ fix(config): fifteen configuration defects, from scheduler units to a nine-megabyte orphan (2026-09-07)

**Repo:** EDDI (`fix/review-quickwins-config`)

Slice 2 of the code-review quick-win backlog (`planning/code-review-backlog/`). Fifteen findings
whose common shape is a knob that does not do what it says.

**Two were live defects.** `@Scheduled(delay = N)` is measured in **minutes**, and two jobs were
written as though it were seconds: the deployment check started ten minutes after boot rather than
ten seconds, and the daily maintenance job — which undeploys superseded agent versions and ends
idle conversations — first ran five hours in, so a pod restarted more often than that never ran it
at all. Both now use `delayed = "10s"` / `"5m"`, which carries its unit, plus
`ConcurrentExecution.SKIP`. Separately, the properties migration renamed the legacy collection into
a backup even when entries had failed to migrate; because `collectionExists` is then false, that
made the migration a permanent no-op, so a transient error on three of four hundred users stranded
those users' long-term properties in the backup collection. The rename is now conditional on a
clean run, and the loop is idempotent so the next boot retries.

**Two settings could not fire at all.** `quarkus.mcp-server.http.root-path` is not a key the MCP
extension knows — the namespace is `quarkus.mcp.server.*` — so changing it moved nothing while the
auth rule kept guarding the old path. And `ComplianceStartupChecks` read
`quarkus.http.ssl.certificate.file`, singular; the real keys are plural, so the TLS warning fired
for operators who had configured TLS correctly and — worse — following the banner's own advice
silenced it while Quarkus ignored the key, leaving the check reporting satisfied on a plaintext
listener.

**Three defaults disagreed with themselves.** `Conversation` held its own
`DEFAULT_MAX_RECALL_ENTRIES = 1000` for the absent-config case while `UserMemoryConfig` defaults to
50, so adding a `userMemoryConfig` block for an unrelated reason cut long-term recall twentyfold in
a diff that never mentions the field. `WorkspaceSettings` validated an operator's value in a lazily
created bean, so a typo booted green and threw a 500 on the first guarded request — and on every
request after it. `TaskForceEngine` ignored the designer's `inputTemplate` at all three of its
phases, which is the whole TASK_FORCE style, so the phase-template mechanism was inert end to end
for that style.

**Four things were hard-coded that the neighbouring surface treats as configuration.** The A2A turn
timeout, the Slack turn timeout and retry budget, and the LLM refusal heuristic — four English
prefixes, so a German- or Japanese-language agent's `onRefusal` guardrail could never fire while
the prefixes over-matched a legitimate "I cannot confirm that from the data provided". All are now
configurable, defaulting to exactly the constants they replace. Slack also answers a timeout with a
notice naming the limit rather than a generic error.

**And two were dead weight.** 9.3 MB of orphaned Manager build output — a complete second Vite
build, 19 files that only imported each other — shipped in every jar and image. The immutable
one-year cache header matched an un-hashed script loaded by the landing page every visitor hits
first, so a fix to it would have gone unseen for up to a year with no revalidation request even
sent.

**Decision — fail safe on a value that would disable a surface.** A zero or negative Slack timeout,
or a retry budget below one, is ignored in favour of the shipped default. Honouring it would take
the channel down on a typo, and an operator who meant to disable Slack has a better way to say so.

**Decision — de-anchor the metrics guard rather than exempt the meters.** Fourteen meters across
Dream, connection resolution, summarization and guardrails dropped the `eddi` prefix, and
`MetricsDashboardCoverageTest`'s regexes required it — so the guard that exists to make an unwatched
meter impossible could not see them, which is how they came to be absent from the metrics reference
on a green build. They are renamed, the dashboard follows, and the anchor is gone so the next
unprefixed meter fails rather than hides. The same shape applies to
`ConfigurationReferenceCoverageTest`: it treated a declaration in `application.properties` as proof
the code reads a key, so a documented-but-unwired key passed the very assertion whose failure
message describes that situation. Split in two, it immediately surfaced a real one —
`eddi.gdpr.restriction-cache-ttl-seconds`, injected and undocumented.

**Files:** eleven production classes, one new (`SlackConfig`), `application.properties`, the
metrics dashboard, six pages under `docs/`, and eight test classes — three new
(`StaticAssetCachingTest`, `ScheduledDelayUnitsTest`, `WorkspaceSettingsTest`, `SlackConfigTest`).

**Next:** slice 3 — API consistency across the REST surface.

---

## 📘 docs: correct 23 false claims and pin them with a guard test (2026-09-07)

**Repo:** EDDI (`docs/review-quickwins-docs`)

The first slice of the code-review quick-win backlog (`planning/code-review-backlog/`). Twenty-three
findings, all of them documentation that said something the code does not do. Markdown compiles to
nothing, so none of them were visible to any check in the build and several had been wrong for more
than one release.

**The ones that cost a reader real time.** `AGENTS.md` and `configuration-reference.md` said a
`scope: "secret"` property degrades to plaintext without a vault key; it fails the whole turn
closed. The README named `quarkus.mongodb.connection-string` as the connection knob — a real
Quarkus key that only the extension's health check reads, so pointing it at another host reports
the new host as UP while every read and write still goes to the old one. Both first-agent tutorials
named the workflow field `packageextensions`, which the strict write boundary rejects with a 400,
and documented a Facebook channel connector that does not exist. The README documented span
attributes without the `eddi.` prefix the code emits, so a trace filter on `task.id` matches nothing
and reads as "tracing is not emitting".

**The ones that hid a shipped feature.** `behavior-rules.md` presented eight of the twelve
registered condition types as the complete list; `deploymentContext` was documented nowhere at all.
`AGENTS.md`'s ZIP section listed seven of twelve backup file extensions, so the most common case —
an agent with a regular dictionary — could not be built from the file that tells you how to build
one, and its workflow step table omitted the templating step the same section calls mandatory.
Six shipped LLM task fields were documented nowhere, including the rolling conversation summary;
`scheduling.md` omitted `oneTimeAt` and `metadata`; `user-memory.md` omitted `dream.parameters`,
without which every summarization step fails with a provider 401 while stale pruning keeps working.

**Three admin surfaces gained their first documentation:** tenant quotas, coordinator dead letters
and template preview. New pages `docs/tenant-quotas.md` and `docs/coordinator-admin.md`, a section
in `docs/output-templating.md`, all linked from `SUMMARY.md`.

**Five plans in `planning/` described shipped subsystems as unbuilt.** Two went further and
instructed an agent to implement them task-by-task, with 107 unchecked boxes between them, against
a spec whose class names all resolve to files that already exist. Those two now carry a status
marker, the directive is gone, and the checkbox syntax is stripped rather than ticked — stripping
does not assert that every sub-step shipped, which ticking would.

**Decision — guard the claims, not the wording.** `DocumentationAccuracyTest` asserts that the docs
mention what the code declares: every condition `ID`, every `*_EXT` backup constant, the schedule
fields the validator enforces, the LLM task fields the model declares. A new condition type is then
a failing test naming the missing type, not a reference table that quietly goes stale. Fixed
expected strings would have needed editing on every rename and would have guarded nothing.

Proven by mutation. Removing the `oneTimeAt` row, dropping the templating step, deleting a backup
extension and restoring the wrong MongoDB property each fail with the missing name in the message.
The test also caught three errors in this very commit's edits before it was run deliberately.

**Files:** `AGENTS.md`, `README.md`; under `docs/` — `SUMMARY.md`, `behavior-rules.md`,
`configuration-reference.md`, `creating-your-first-agent/creating-your-first-agent.md`,
`creating-your-first-agent/creating-your-first-agent-1.md`,
`deployment-management-of-agents.md`, `docker.md`, `langchain.md`,
`monitoring/monitoring-guide.md`, `output-templating.md`, `putting-it-all-together.md`,
`rag.md`, `release-versioning.md`, `scheduling.md`, `user-memory.md`, plus the two new pages
`coordinator-admin.md` and `tenant-quotas.md`; under `planning/` — `conversation-cancel-plan.md`,
`hitl-tool-approval-plan.md`, `mcp-hitl-surface-plan.md`,
`multimodal-attachments-completion-plan.md`, `observability-and-pipeline-plan.md`; and
`src/test/java/ai/labs/eddi/docs/DocumentationAccuracyTest.java`.

**Next:** slices 2-6 of the same backlog — config keys, API consistency, and 63 test-quality
findings.

---

## 🏷️ fix(ci): a release tag could execute on the runner (2026-09-07)

**Repo:** EDDI (`fix/review-quality-gates`)

`PRIMARY_TAG` is `${GITHUB_REF#refs/tags/}`, and the only check on it was a *prefix* comparison
against the pom version. So `6.3.0-$(id)` passed — a legal git ref name, therefore pushable — and
nine `run:` blocks spliced it in with `${{ }}`, which the runner substitutes into the script text
*before* bash parses it. Two of those blocks hold the Docker Hub credentials and the Sigstore
keyless identity. Pushing a tag needs write access, so this is not anonymous execution; it is
tag-push rights becoming arbitrary commands in the job where the release secrets live.

The whole tag is now matched against
`^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$` before the parity check, and every one of
the nine sites takes the value through `env:` instead of interpolation. All 54 published tags since
4.8.0 match; the pattern carries no version literal, so the single-source-of-truth guard stays
satisfied, and it agrees with the no-`v`-prefix rule the release trigger depends on.

Two relational tests pin it. One lifts the pattern out of the YAML, compiles it, and runs 6 accepted
and 18 rejected tags through it, so it grades the regex's behaviour rather than its presence — shown
by a second experiment that widened the pattern to `^[0-9].*$` and still failed. The other sweeps
every workflow for `${{ }}` interpolation of the tag inside a `run:` block, so a new site cannot
reappear.

`ReleaseVersionSourceTest` caught the first draft of the error message for quoting a literal version
as an example, which would have gone stale on the next bump. The guard works.

---

## 🧷 fix(build): a gate test that passed with its guard deleted (2026-09-07)

**Repo:** EDDI (`fix/review-quality-gates`)

Four review comments, two of them the kind this whole review exists to catch.

**A gate test was green with the thing it guards removed.** `dependabotSkipBlock()` falls back to
returning the rest of the workflow when the guard is absent, and that text still contains both
`--json files` and `"$DOCKERFILE"` — so both assertions passed against a workflow with no
de-duplication at all. Confirmed empirically before fixing: guard physically deleted, old
assertions, `Tests run: 20, Failures: 0`. The test now tracks whether the loop found the guard and
asserts it afterwards, and fails with the guard gone.

**`defaultTestDeadLetterPath()` still accepted the source tree.** It checked only
`Path.isAbsolute()`, so a `java.io.tmpdir` pointing at the repository root or a child was allowed
and the sink landed back in the tree — the artifact this branch removed, reachable through a JVM
flag instead of a code change. It now also rejects a temp directory inside the project directory,
naming the property and the value. Containment uses `Path.startsWith`, which matches whole name
elements, so a sibling that merely shares a textual prefix is still accepted; that control case is
asserted so a regression to string comparison fails.

**Dependabot PRs were selected with the wrong filter.** `gh pr list --author 'app/dependabot'` is
the user filter and is not guaranteed to return App-authored PRs. An empty result is silent here —
the skip never fires and the job raises a digest PR duplicating Dependabot's. Now `--app dependabot`.

**Unquoted redirections.** 83 of them, into `$GITHUB_STEP_SUMMARY`, `$GITHUB_OUTPUT`, `$GITHUB_ENV`
and `$GITHUB_PATH` across three workflows, all quoted — and a new test sweeps every workflow so the
count cannot creep back.

---

## 🪝 fix(githooks): fetch from the remote being pushed to (2026-09-07)

**Repo:** EDDI (`fix/review-quality-gates`)

The pre-push hook's shallow-clone recovery fetch named `origin` literally, while git passes the
remote as `$1`. A push to any other remote fetched from the wrong one. The effect is narrow — the
fetch is reached only after `merge-base --is-ancestor` has already failed — but it made the recovery
path silently useless for anyone pushing to a fork or a second remote, which is exactly the
contributor workflow `CONTRIBUTING.md` describes. The hook now captures the remote argument and uses
it.

---

## 🔧 fix(build): make the base-image check ask which Dockerfile, declare the YAML dependency (2026-09-06)

**Repo:** EDDI (`fix/review-quality-gates`)

Eight review comments, four behavioural. All eight were proven in a single mutation run that
reverted every behaviour at once and produced exactly eight named failures, one per comment, with
no cross-talk.

**The base-image check skipped itself on the wrong pull request.** It treated the first open
`dependabot/docker/*` branch as covering the production Dockerfile, but `dependabot.yml` declares
three docker ecosystems (`/src/main/docker`, `/mcp-sidecar`, `/.clusterfuzzlite`) and all three push
branches under that prefix. A sidecar or fuzzing bump therefore silenced the check for the image
that actually ships. It now asks `gh pr view --json files` whether the PR touches the production
Dockerfile before skipping.

**A dependency was reaching the classpath by accident.** `jackson-dataformat-yaml` was arriving
only through `json-schema-validator`'s transitive tree, so an unrelated bump could have removed it
and broken YAML parsing with no declaration to point at. It is now declared alongside the CSV and
XML modules.

**The dead-letter path trusted `java.io.tmpdir`.** A relative or empty value resolved against the
working directory, which put the audit dead-letter file inside the source tree. It is now rejected
with an `IllegalStateException` naming the property. The matching test also no longer requires the
*global* absence of `eddi-audit-deadletter.jsonl` — a stale file from an older checkout made it
fail for the wrong reason — and instead snapshots the repository-root sink and asserts it is
unchanged.

**Note for the merge order.** This branch arms the build gates: Checkstyle moves to
`failOnViolation`, and the formatter from `format` to `validate`. It should merge **last**, after a
pre-flight run of the armed gates against main with everything else already in, or it will turn
green branches red on violations they currently get away with.

---

## 🧪 test(build): make the gate tests unable to pass a disarmed gate (2026-09-04)

**Repo:** EDDI (`fix/review-quality-gates`)

Follow-up on the same branch, from an independent review round and four GitHub Copilot
comments. Every one was the same defect in a different place: a test that grades the build
gates while itself being satisfiable by a disarmed gate.

- **The coverage-gate test graded only what survived.** It walked the JaCoCo limits and
  asserted a value per counter it found, so deleting the `BRANCH` limit — or emptying the
  `<limits>` block entirely — still passed. It now compares the whole limit map against
  `{INSTRUCTION=0.90, BRANCH=0.80}`, so a deleted, renamed or retuned limit fails.
- **The langchain4j pinning test let an unpinned artifact through**, because the condition
  began `version != null`. An artifact with no `<version>` falls back to a BOM or transitive
  version, which is exactly what the test exists to forbid. Reproduced first by stripping the
  version from a real dependency and watching the old test pass.
- **The version-duplication sweep named `redhat-certify.yml` as an offender and did not scan
  it.** Reintroducing the very `default:` this branch removed would have passed. The sweep now
  covers five files and a dedicated assertion rejects any `default:` on that workflow's
  `version` input — the check that still bites after `pom.xml` moves past the stale literal.
- **The CI `code` path filter omitted `README.md` and `AGENTS.md`**, so a PR touching only
  those skipped the tests that grade them. Rather than fix the pair by hand, a new test derives
  the requirement: it sweeps the test tree for root documents any test opens, parses the filter
  out of `ci.yml`, and fails if one is unlisted. It asserts the sweep found something, so it
  cannot go vacuous itself.

**Not changed, deliberately:** the same gap exists for `docs/**`, where three more tests grade
documentation. `ci.yml` documents skipping the build for docs-only changes as intentional, so
widening it is a policy decision rather than a review fix.

---

## 🚦 fix(build): make the style, coverage and image gates able to fail (2026-09-04)

**Repo:** EDDI (`fix/review-quality-gates`)

From the whole-repository code review. Several of this project's quality gates were wired
so that they could not fail — which is the root cause the review named for why the other
findings shipped at all.

- **Checkstyle ran with `failOnViolation=false`.** The import and file-size rules AGENTS.md
  calls mandatory could not fail anything, and are violated on `main` today.
- **The formatter's `format` goal rewrote tracked sources on every compile** instead of
  checking them, so a contributor's build silently edited files rather than reporting them.
- **The JaCoCo 90/80 gate graded `jacoco-merged.exec`** in runs where the integration tests
  never produced it, so `./mvnw verify` failed a clean, all-green tree at 89 %. It now
  carries `<skip>${skipITs}</skip>` and runs where the data actually exists.
- **Failsafe was pinned to Surefire's version property**, so the two could silently diverge.
- **The container integration tests built a hand-copied Dockerfile** that had already
  drifted from the production one, so what CI verified was not what ships. They now build
  the real image.
- **The project version was duplicated as a literal** in the OpenAPI info block and in
  `application.properties`; both now resolve from the build.

### Regression coverage

`BuildQualityGatesTest` and `ReleaseVersionSourceTest` assert the gates are armed — that
Checkstyle fails on violation, that the coverage check is skip-aware rather than
unconditionally disabled, and that no version literal is reintroduced. `EddiImageDockerfileTest`
pins the integration image to the production Dockerfile.

Recorded honestly as unverifiable here: the image build itself and the two version
assertions need Docker and MongoDB Dev Services, so they compile but have never executed
locally. CI is the authority for those.

**Reviewer note.** This branch changes the local build contract: `./mvnw compile` now fails
on unformatted or badly-imported sources instead of quietly rewriting them. AGENTS.md is
updated to say so, because the previous wording described the old behaviour.

## 🔌 fix(install): close the PR #714 review — a busybox probe that read every port as free (2026-09-07)

**Repo:** EDDI (`fix/installer-mongodb-port-conflict`)

Four findings from the Copilot review of [#714](https://github.com/labsai/EDDI/pull/714): two inline,
two the review filed as "suppressed comments" in its body (no thread, so nothing to answer in place).
All four were real. Also merged `origin/main`, which the PR had drifted behind far enough to go
`CONFLICTING` — and per the repo's own experience a conflicting PR never runs `ci.yml` at all, so the
merge is what puts this branch back under CI.

**1. `port_in_use` had over-corrected into the opposite bug (`install.sh`).** The previous entry
below fixed busybox lsof reading every port as *taken* by requiring `LISTEN` in the output. But the
probe was an `elif` chain: on Alpine-class systems the lsof branch is entered, finds no `LISTEN` in
busybox's file dump, and the `nc` / `/dev/tcp` branches are never reached — so every port now read as
*free*, and the raw docker bind error came back. A missing marker is not evidence. The chain is now:
`ss` short-circuits (its absence of a match really is proof); a **positive** lsof match is trusted, a
negative one falls through to a connect probe that behaves identically on every implementation.
Verified in a real `alpine:3.20` container with a live listener — busy port detected, free port still
free — and mutation-checked by restoring the `elif` chain, which fails the busy case.

While there: the lsof output is captured instead of piped into `grep -q`. `grep -q` exits on first
match and can SIGPIPE the producer, which `set -o pipefail` then reports as a failed pipeline even
though the port *was* found.

**2. The PowerShell installer validated every port variable up front (`install.ps1`).** A stale
`GRAFANA_PORT=abc` in the environment aborted a plain install that never starts Grafana, and `-Full`
rejected a bad `-MongoPort` before switching the database to PostgreSQL. The Bash installer has
always validated inside `resolve_published_port`, i.e. only for components that are actually enabled.
The eager loop is gone; `Resolve-PublishedPort` now validates its own argument. `-MongoPort` also
gains a `$MongoPortRequested` capture, matching the six overlay ports and keeping the resolved value
from overwriting the request.

**3 + 4. The Compose project name was derived two ways that Compose does not use (both scripts).**
`compose_project_name` / `Get-ComposeProjectName` decide whether a listener is *our* container (reuse
the port) or a foreign one (remap, or fail an explicit request). Both ignored `COMPOSE_PROJECT_NAME`,
and both assumed the project directory is `EDDI_DIR` — but Compose derives it from the directory of
the **first** `-f` file, which under `--local` / `-Local` is the repo checkout. Confirmed against
docker compose 29.7.2: first `-f` in `RepoCheckout/` yields project `repocheckout`, first `-f` in
`.eddi/` yields `eddi`, and `COMPOSE_PROJECT_NAME` overrides both. Both functions now follow the same
three rules.

**Design decision.** Detection was fixed by falling through rather than by sniffing for busybox
(`lsof -v`, `--help`, applet name). Busybox ignores its argv here, so every sniff is a guess about
which not-real-lsof this is; "trust a positive, verify a negative" needs no such guess and is correct
for any implementation, present or future.

**Verification.** `alpine:3.20` behavioural harness plus its mutation check; `bash:3.2.57` harness
for `compose_project_name` (7 cases, `set -u` safe); shellcheck at CI's exact invocation
(`--severity=warning --shell=bash`) clean; `install.ps1` parses and passes a 9-case harness under
**both** pwsh 7.6.5 and Windows PowerShell 5.1; five end-to-end `-WhatIf` runs of the real installer
covering the stale-variable case, its negative control, `-Full` with a bad `-MongoPort`, and explicit
`-MongoPort` accepted and rejected. PSScriptAnalyzer: **7 findings, 0 Error — now genuinely identical to
`main`'s baseline.** The PR description had claimed that already and it was not true: the branch was
adding 7 `PSAvoidUsingPositionalParameters` warnings, one per `Resolve-PublishedPort` call site, for
14. Those call sites now pass named parameters.

**Codacy is still red and it is not those seven.** It reports `7 new issues (0 max.)` — the same
count before the branch's fixes, after them, and after the positional-parameter change, so the
matching number was a coincidence. Ruled out from outside: PSScriptAnalyzer is byte-identical to
`main` at every severity including `Information`, and shellcheck with no severity filter at all
(Codacy's default, not CI's `--severity=warning`) reports a single `SC2129` note on a pre-existing
line. The check publishes no annotations, an empty summary and no text — its title even reads "of at
least  severity" with the severity name missing — so the issue list exists only inside the Codacy
dashboard, which needs an account to read. **Open:** someone with Codacy access has to open
[the PR page](https://app.codacy.com/gh/labsai/EDDI/pull-requests/714) and say what the seven are.

**Superseded on merge.** `main` (#736) deleted `docker-compose.postgres.yml` outright — it was a
drifted near-duplicate that could not work as the overlay the README documented, and its role is now
`docker-compose.postgres-only.yml`. This branch's two fixes to that file (the doubled `7070:7070`
publish, and moving `postgres` to a loopback binding) are moot: the replacement already interpolates
`${EDDI_PORT:-7070}` once and publishes no database port at all. The merge takes the deletion, and
the `POSTGRES_PORT` line this branch added to `.env.example` is removed with it — it pointed at a file
that no longer exists.

**Second review round (CodeRabbit).** Two more findings, both valid.

*The `ss` branch had the same SIGPIPE hazard I had just fixed one branch below.* Fixing it for `lsof`
and leaving `ss` piped into `grep -q` was inconsistent, and the consequence is the worse direction:
`grep -q` exits on its first match, ss takes SIGPIPE while still writing, `set -o pipefail` makes the
pipeline non-zero, and a port that **is** in use reads as free — straight into the raw docker bind
error this whole branch exists to prevent. It needs a host with enough listening sockets to overflow
the 64 KiB pipe buffer, which is exactly the kind of machine that has a port conflict. Now captured
before matching, like the lsof branch. Proved with a stub `ss` that emits the matching row first and
then 330 KB of filler: the piped form reports the busy port as free, the captured form does not.
Re-checked against a real `ss` (debian + iproute2) with a live listener, including the anchor case
where port 99 must not match a listener on 9999.

*Both installers documented the opposite of what a pinned port does.* `install.sh --help` closed its
list of port variables with "kept when free, moved to the next free port when something holds it",
and `install.ps1`'s `.NOTES` said the same — but every entry in that list is an **explicit** request,
and an explicit request that is busy fails by design rather than moving. A user who pinned
`KEYCLOAK_PORT` was promised a silent remap and got an abort. Both texts now separate the two paths,
as does the `-MongoPort` parameter help.

**Files:** `install.sh`, `install.ps1`, `docs/changelog.md`

---

## 🔐 fix(deploy): no credential in the auth component has a default any more (2026-09-07)

**Repo:** EDDI (`fix/review-deploy`)

Three review comments on the development auth overlay, all of them fair.

**The shipped realm allowed cleartext for everything.** `sslRequired: "none"` let Keycloak serve the
login form, the authorization code and the token endpoint over plain HTTP to any caller. It is now
`"external"` in all three realm copies — Keycloak exempts local addresses, so every documented path
still works: the quick start uses `kubectl port-forward` (arrives as `127.0.0.1`), compose sees the
bridge gateway, and EDDI's backchannel runs pod-to-pod on RFC 1918. The one case where `external`
would bite, a pod CIDR outside RFC 1918 such as `100.64.0.0/10`, is documented with the
`X-Forwarded-Proto` requirement rather than left to be discovered.

**The secret generator printed a key it had not installed.** Under any `-WhatIf` run the PowerShell
script reached the key box, because the report block sat outside the `ShouldProcess` gate — telling
an operator a master key was installed when nothing was, and printing a secret that exists nowhere.
It now tracks whether the create actually happened. The bash twin has no dry-run mode and so no
equivalent path; that invariant is now written down next to its key box, and the new test sweeps
both scripts so the two cannot drift.

**The component shipped a guessable full administrator.** `admin/admin` plus a privileged
`eddi/eddi` account, on a workload fronted by a ClusterIP every pod in the namespace can reach. The
"development only" framing is real but does not cover that, and `"temporary": true` is not a
mitigation — Keycloak 26 drops the required action on realm import, which this repo had already
recorded. `KC_BOOTSTRAP_ADMIN_PASSWORD` now comes from an operator-created Secret with no default,
so the component fails closed with `secret "keycloak-admin" not found`; and the privileged `eddi`
fixture ships with no credential at all, keeping its roles so recovery is one console action rather
than recreating a user, two roles and a group. The unprivileged fixtures are untouched, so the demo
login still works.

Each change is pinned by a relational assertion rather than a literal: the realm must require TLS
for external clients, a generator may print a key only if it issued the create, no shipped manifest
may carry a usable default privileged password, and the Secret name in the YAML must appear in the
`kubectl create secret` command the docs give.

---

## 🧭 fix(ci): build on every operator document the manifest suite asserts about (2026-09-07)

**Repo:** EDDI (`fix/review-deploy`)

A review comment asked that no operator-facing document this suite pins can be edited without
`build-and-test` running. The `operator_docs` filter covered `docs/kubernetes.md` and `README.md`,
but `docsNameTheRealClientId` also reads `docs/security.md` and asserts it names `eddi-frontend`
rather than the stale `eddi-manager` — and that file matched neither filter. This branch changed
`docs/security.md` itself, so a revert of it would have sailed past the only test that guards it.

Closed at the mechanism rather than in prose: the filter now covers what the suite actually reads.

---

## ☸️ fix(deploy): stop the secret generator deleting on the normal path, pin config to its pods (2026-09-06)

**Repo:** EDDI (`fix/review-deploy`)

Fifth review round on this branch. Five comments, all behavioural, all fixed and each proven by
reverting the change and watching a named test fail.

**The secret generator deleted before it created, on every run.** `kubectl delete secret
eddi-secrets --ignore-not-found` ran unconditionally, so the window between delete and create
existed even when the operator had asked for nothing destructive. A pod starting in that window
came up without its vault key. The delete now lives inside the `--force` branch in both
`create-secrets.sh` and `create-secrets.ps1`; the normal path relies on `kubectl create` refusing
with `AlreadyExists` and reports that nothing was changed.

**The test suite had pinned the race as a requirement.** Two normal-path tests asserted the
scripts "must keep the delete-then-create it does once past the guard" — so fixing the scripts
alone would have turned them red, and leaving them would have blocked the fix forever. This is the
failure mode this review keeps finding: a test that guards the bug rather than the contract. Both
now assert the delete is reachable only under `--force`.

**A config change did not restart the pods that read it.** The Deployment pod template gained
`checksum/config` and `checksum/secret` annotations hashing the rendered `configmap.yaml` and
`secret.yaml`, so `helm upgrade` rolls pods when their configuration actually changed. Without it
`envFrom` kept serving the old values until something unrelated caused a restart — the same
trap the Keycloak upgrade note now documents, with `kubectl rollout restart deployment/eddi`.

**Files:** `k8s/create-secrets.sh`, `k8s/create-secrets.ps1`,
`helm/eddi/templates/deployment.yaml`, `k8s/overlays/auth/kustomization.yaml`,
`docs/kubernetes.md`, `src/test/java/ai/labs/eddi/deploy/DeploymentManifestsTest.java`

---

## 🔎 test(deploy): assert manifest relationships, not the presence of strings (2026-09-04)

**Repo:** EDDI (`fix/review-deploy`)

Follow-up on the same branch, from three independent review rounds over the deployment fixes.

The manifest suite was asserting that literals exist. A test for the Keycloak security context
checked only that `runAsNonRoot: true` and `runAsUser: 1000` appear somewhere in the file after
comment-stripping, which a commented-out or wrongly-nested block satisfies. Assertions now
resolve the YAML and check the value on the container that actually runs.

The CI path-filter test asserted that a forced-true exists for tagged releases, but that
contract is **positional** — the `echo "<filter>=true"` has to sit inside the
`if [[ "$GITHUB_REF" == refs/tags/* ]]` branch to mean anything. It now checks placement.

The secret-generator ordering test was strengthened to assert the guard precedes the
destructive delete *and* that an `exit 1` sits between them, so a guard that only warns fails.

Two justifications were withdrawn after the reviewer disproved them: the secret scripts were
filed as needing a live cluster, when their fail-closed classification is plain text parsing;
and the Helm chart version claim was pinned to the break it documents by asserting `manager`,
`monitoring` and `namespace` really are absent from `values.yaml`, so the major bump cannot
become a different lie.

**Coverage note.** Diff coverage of Java changed lines is 100%, but that figure is the
intersection of the tool with an almost-empty `src/main` diff — this branch is 38 non-Java
files. The manifests are covered by the structural suite instead, which is stated plainly
rather than presented as a coverage result.

---

## ☸️ fix(deploy): repair Keycloak realm substitution and the secret generator, add a manifest regression suite (2026-09-04)

**Repo:** EDDI (`fix/review-deploy`)

From the whole-repository code review. The shipped Kubernetes and Helm assets failed
*silently at deploy time* rather than loudly at render time.

**Applying the documented quick start destroyed the vault master key.** `k8s/base` shipped
`eddi-secret.yaml` as a live resource, so every `kubectl apply -k` re-applied the
placeholder committed to this repository over whatever key was installed. On a first
install the operator silently ran with a published key; on any later apply the real key was
overwritten and everything sealed with it became undecryptable. The manifest is now
`eddi-secret.yaml.example` and is not applied; `create-secrets.sh` refuses to clobber an
existing secret unless asked.

**Both shipped `k8s/examples/` kustomizations failed to build at all** — verified by running
`kubectl kustomize`, which exits non-zero because an overlay reaches a file outside its own
root. Composing overlays the way their own headers instruct also silently discarded their
ConfigMap patches, so a NATS deployment came up still set to in-memory messaging.

**Keycloak never became ready and never had a realm.** The probes targeted a port serving
no health endpoint, so the pod stayed NotReady forever and its Service got no endpoints; and
the deployment never imported the realm, so OIDC discovery resolved against a realm that did
not exist. Keycloak also ran `start-dev` with no persistent volume, so every restart wiped
realms, clients and users — it is now a StatefulSet with a volume.

**The Helm realm substitution matched nothing.** The chart replaced the literal
`https://eddi.example.com`, but both shipped realm copies advertised a *different*
placeholder host, so `helm` exited 0 and login died with `Invalid parameter: redirect_uri`.

### Regression coverage

Deployment assets were the one area with almost no automated coverage, which is why these
shipped. This branch adds `DeploymentManifestsTest` — a structural suite that asserts
*relationships* rather than presence: that the realm placeholder the chart substitutes is
the one the realm files actually carry; that the token issuer is derived from the same
public URL on both the Helm and Kustomize paths; that the realm volume resolves to a
ConfigMap the overlay genuinely generates; and that the secret generator checks before it
deletes.

A new CI `manifest-lint` job renders every kustomization and lints the chart, so a manifest
that does not build fails the pipeline instead of a deployment. It is deliberately a PR gate
and not a release blocker, for the same reason `shell-lint` is: a manifest typo should not
hold up a security patch.

The six tests were proven by mutating all six inputs at once and confirming exactly six
failures with no collateral.

## 🧾 fix(gdpr): name the conversations an export lost, and stop a cache miss disowning a delete (2026-09-07)

**Repo:** EDDI (`fix/review-audit-gdpr`)

Six review comments, four of which were raised only inside collapsed review-body sections that
never became inline threads, so nobody had opened them.

**A cache failure disowned a delete that had succeeded.** In `deleteUserData`, `forgetRestriction`
sat between the memory delete and the count assignment inside one `try`, so an eviction failure
reported zero memories erased and named `userMemories` as the failed step — for a delete that had
already committed. The count is now assigned immediately after the delete, and cache eviction is its
own step recording `restrictionCache`.

**The export silently dropped conversations it could not read.** A snapshot that threw, or came back
null between the id lookup and the read, was logged and skipped while `complete()` knew nothing about
it — the same reports-success-while-incomplete failure this branch exists to fix, one method away.
Failed ids are now collected, surfaced on `UserDataExport` and the MCP payload, counted in the
`GDPR_EXPORT` audit entry, and folded into `complete()`.

**Two documents described an API that no longer exists.** The erasure example in
`docs/gdpr-compliance.md` predated six counters, `failedSteps` and `complete`, and the export note
still said 206; both are corrected, and the operator is told to check `complete` before filing an
Art. 17 request as fulfilled. `docs/audit-ledger.md` listed the wrong indexes and claimed the
collection takes inserts only — `pseudonymizeByUserId` issues an `updateMany` under Art. 17(3)(e).

**Two tests that could pass while broken.** `McpGdprToolsTest` now derives its expected key set from
`GdprDeletionResult`'s record components, so a component added to REST cannot silently miss MCP; and
the pseudonym assertion checks the full `pseudonymFor` value rather than just the prefix, which a
pseudonym computed over the wrong input would have satisfied.

---

## 🕵️ fix(gdpr): log the pseudonym, not the identifier the erasure just removed (2026-09-07)

**Repo:** EDDI (`fix/review-audit-gdpr`)

A reviewer pushed further than the previous round did, and was right to. Sanitising the `userId` in
the GDPR delete-all log stopped a caller forging log records, but it left the identifier itself
sitting in the log — on the one code path whose entire purpose is to remove that identifier. Logs
outlive the database and travel further than it does, so an erasure that writes the user's id into
them has not finished the job (CWE-532).

Both user-memory stores now log `AuditHmac.pseudonymFor(userId)`: the same deterministic SHA-256 the
erasure cascade already substitutes into the audit ledger. An operator can still correlate the log
line with the ledger entry, and neither holds the identifier. The pseudonym is hex, so it also
cannot carry a record boundary — the injection fix is subsumed rather than discarded.

`PostgresUserMemoryStore.deleteAllForUser` gained the null guard `MongoUserMemoryStore` has always
had. Erasing "all entries for user null" is not a request anyone means, and the pseudonym refuses a
null identifier rather than hashing one.

The regression test asserts the stronger contract: the raw id must not appear in the captured log at
all, and the pseudonym prefix must. Reverting the change fails it with the offending line quoted.

---

## 📤 fix(gdpr): stop calling an export complete while four data categories are missing (2026-09-07)

**Repo:** EDDI (`fix/review-audit-gdpr`)

Six review comments, plus the log-injection round.

**The portability export overstated itself.** `conversationsTruncated` was the only completeness
signal, but the endpoint's own interface documents that every export omits group transcripts,
shared artifacts, schedules and HITL journal entries. A user whose data lived only in those
categories received a 200 the API described as complete — a GDPR Art. 20 answer that is not true.
`UserDataExport` now derives `complete` and `omittedCategories`, both mirrored into the MCP payload
so the two surfaces agree, and the response carries the distinction in the status line as well as
the body.

**206 was the wrong status and is now 207.** 206 Partial Content is a *range* status and means
something specific about byte ranges. 207 Multi-Status says "composite operation, read the body",
which is what this is, and it is already what the sibling erasure endpoint uses. Because the four
categories are always omitted today, the export always answers 207; the 200 branch returns when
those exporters land. The four missing exporters are deliberately not implemented here.

**A warning claimed a write that had not happened.** `warnIfCostBudgetIsUnenforceable` ran before
`setQuota`, so a failing store still logged that the limit was stored and would apply. Moved after
the write returns.

**The SSE and synchronous quota surfaces disagreed.** A `QuotaAccountingUnavailableException` with
no message produced `"message":""` on the streaming path while the synchronous mapper produced
`Quota accounting unavailable`. Both now use one shared fallback.

**Two stores gave a different refusal reason than the gates around them.** The Mongo and PostgreSQL
tenant-quota stores said "Cost accounting failed" where every sibling gate says "Quota accounting
unavailable — denying request for safety". Aligned.

**A documentation contradiction, half real.** `AuditLedgerService` has two overflow paths, and only
one dead-letters. `submit()` reserves its slot before a sequence is assigned, so a rejected
submission is simply counted and dropped; `offerBounded()` on the retry paths dead-letters, because
those entries already hold a chain position. The Failure Handling prose described the second and
generalised it to both. It is now a table naming each path and its recoverability, with the
consequence stated: a dropped submission is unrecoverable *and* leaves the chain `INTACT`, so
`eddi_audit_entries_dropped_total` is the only signal and a clean `/auditstore/verify` is not proof
of completeness.

**One comment was wrong and is recorded as such.** A reviewer said a fixture stubbed the wrong
descriptor read. The call graph is the other way round — `describe()` reaches `readDescriptor`, not
`readCurrentDescriptor`, and the latter appears nowhere in the service. What misled the reviewer was
the fixture's own comment, which claimed the opposite; the comment was corrected and the stub left
alone. Following the suggestion would have stopped the test reaching the production guard at all.

**Log injection.** The GDPR delete-all logs on both user-memory stores wrote the caller-supplied
`userId` raw. Both now sanitize it — on the erasure path, which is exactly where a log has to be
trustworthy.

---

## 🔐 fix(gdpr): stop caching "not restricted" by default; make foreign audit sequences visible (2026-09-06)

**Repo:** EDDI (`fix/review-audit-gdpr`)

Two reviewer comments had been reported closed but were not. Both are now closed properly, and the
second one is closed by admitting what is not fixed rather than by claiming it is.

**The Art. 18 restriction cache failed open by default.** A previous pass added
`eddi.gdpr.restriction-cache-ttl-seconds` and made `0` disable the cache, but left the default at
30 seconds — so on a stock multi-replica deployment a node that had cached "not restricted" kept
processing for up to 30 seconds after another node applied the restriction. That is exactly the
window the comment described, and an opt-in switch does not close it. The default is now `0`:
every turn reads the store, and caching becomes an explicit single-node or conversation-affinity
optimisation, documented as such. The alternative considered was a positive-only cache, which was
rejected because it would only ever help restricted users — the rare case — while still delaying
an *un*restriction across the cluster.

**Audit sequence allocation is still not cluster-safe, and now says so.** The reviewer asked for a
storage-level atomic reservation plus a unique `(conversationId, sequence)` constraint and retry.
That is deferred: it moves a store round trip from once per conversation to once per entry on the
pipeline thread, and it is a schema change on two backends. The constraint must not land on its
own either — for an audit ledger a rejected insert silently drops a record, which is worse than a
duplicate the verifier can detect. What was genuinely missing and is cheap is *detection*:
`flush()` now re-reads the store's maximum for each conversation it wrote and, if the store already
holds a position at or beyond this node's next free one, increments
`eddi_audit_sequence_collisions_total`, logs a WARN naming the conversation, and advances its
counter past the foreign rows. An operator running multi-replica without affinity sees it in
metrics instead of discovering it as a `BROKEN` verdict at verify time. The detector has no false
positives and is documented as partial: two nodes handing out an identical range leave a maximum
consistent with both counters and are still only caught at verify time.

`IAuditStore` and `docs/audit-ledger.md` now lead with the limitation, the deferred fix, and why it
is deferred, so the row can no longer be read as "already correct".

---

## 📒 fix(audit,gdpr,tenancy): repair ledger persistence, erasure reporting and quota windows (2026-09-04)

**Repo:** EDDI (`fix/review-audit-gdpr`)

From the whole-repository code review. The subsystem a regulated buyer is actually paying
for did not work on a supported backend.

**The PostgreSQL audit backend could not store entries EDDI legitimately produces.**
`conversation_id`, `AGENT_ID` and `AGENT_VERSION` were `NOT NULL`, and the insert unboxed a
nullable `agentVersion` with `setInt`, throwing an NPE that escaped `appendBatch`'s catch
entirely. Six shipped call sites pass a literal null — ordinary HITL approvals and group
turns among them — so a single compliance or oversight entry **discarded roughly three
flush windows of unrelated conversations' audit data**, while the compliance event itself
was never recorded. On the read side `getInt` mapped a stored SQL NULL to `0`, which the
HMAC canonical form renders differently, so any such row would have verified as tampered.

The ledger is append-only evidence, not logs. Losing other conversations' entries because
one entry is malformed is the worst possible failure mode for it.

**GDPR erasure returned 200 and "complete" even when steps failed**, and reported memories
as deleted before deleting them. It now reports per-step outcomes and answers 207 when the
cascade is partial. The MCP admin surface for the same operation hardcoded
`"status": "completed"` and is now driven by the real result, so an operator — or an agent
calling the tool — is no longer told a lossy erasure succeeded.

**Quota windows** were compared against a stale in-memory view, and the bootstrap silently
ignored later configuration changes; both now warn when stored and configured values
diverge instead of quietly preferring one.

## ⏸️ fix(schedule): a human-approval pause is a skip, not a failure (2026-09-07)

**Repo:** EDDI (`fix/review-schedules`)

Two review comments that were raised but never posted as inline threads, so nobody had opened them.

**A scheduled turn that paused for human approval was dead-lettered.** `ConversationService.say`
throws `ConversationAwaitingApprovalException` *before* the response handler is wired, so the
`SKIPPED` branch this branch added for exactly that case could never run. The executor's broad catch
recorded the fire `FAILED`, incremented `failCount`, and eventually dead-lettered a conversation
whose only crime was waiting for a human. A catch for that exception now sits ahead of the broad one
and sets `FireStatus.SKIPPED`.

**The Mongo existence probe claimed a primary read it never requested.** `logFire`'s compensating
re-read asked for no read preference, so it inherited `ReadPreference.nearest()` from the single
`MongoDatabase` producer and could be answered by a lagging secondary still holding the schedule
that had just been deleted — while its own Javadoc said it "reads from the primary". The probe now
asks for the primary explicitly, and the Javadoc describes what the code requests rather than what a
deployment might happen to be configured as.

---

## ⏰ fix(schedule): a fire log can no longer outlive the schedule it belongs to (2026-09-06)

**Repo:** EDDI (`fix/review-schedules`)

Review round on this branch: 17 comments. Six were genuinely open and are fixed; the substantive
one took two attempts, because the first was a mitigation described as a fix.

**Erasure could report success over a log row it had not removed.** `deleteWithCascade` deleted
logs and schedules in a transaction and then swept again after commit, which catches every log
written before the sweep — but a fire already in flight can commit its log afterwards, so a GDPR
erasure still reported success over a row carrying the erased user's `conversationId`. The window
is now closed at the *write* side rather than by widening the sweep.

On PostgreSQL `logFire` issues a guarded insert — `INSERT … SELECT … WHERE EXISTS (SELECT 1 FROM
eddi_schedules WHERE id = ?)` — so the subquery is evaluated under the same snapshot that writes
the row and a log for a deleted schedule cannot be committed at all. Zero rows is the correct
outcome, logged at DEBUG, never thrown: a benign race must not surface on the fire path. A foreign
key with `ON DELETE CASCADE` was the other candidate and was rejected — existing deployments
already hold orphaned fire logs, which is the bug, so `ADD CONSTRAINT` would fail on exactly the
installs that need it.

MongoDB has no conditional insert, and a pre-check only moves the race. So it inserts, re-reads the
schedule from the primary, and deletes the log it just wrote if the schedule has gone. Against the
cascade's three steps there is no interleaving where the log survives its schedule: either the
schedule delete precedes the re-read and the compensation fires, or it does not and the cascade's
own delete or the post-commit sweep catches the document.

Both sweeps are kept, re-framed as belt-and-braces for logs written by a replica that had not yet
observed the delete. The one operator-visible consequence — a schedule deleted mid-fire may lose
that attempt's log — is documented in `docs/scheduling.md` as deliberate.

**Outcome writes are fenced by the claim's fire id.** `markCompleted`/`markFailed`/`markSkipped`
and `markDeadLettered` now take the expected fire id, so a fire that exceeded its lease cannot
overwrite the outcome of the fire that reclaimed the row.

**Three CodeQL log-injection sites** in `PostgresScheduleStore` (`scheduleId`, `agentId` and a HITL
timeout schedule name, all caller-supplied) now go through `LogSanitizer.sanitize`, matching what
`MongoScheduleStore` already did.

**Redirects no longer rewrite every method to GET.** `SafeHttpClient` splits the rule per status:
307/308 preserve method and body, 303 rewrites to GET, and 301/302 rewrite only POST — so PUT,
PATCH and DELETE keep their method, body and `Content-Type`.

**The minimum-interval check no longer depends on when it runs.** `CronParser` derived the gap by
walking fires from `Instant.now()`, so the same expression could pass validation on one day and
fail on another. It is now computed from the parsed fields: the tightest pair within a firing day,
and the tightest gap across days scanned over a full 28-year Gregorian cycle.

**Correction.** An earlier entry on this branch described scheduling as "exactly-once". Delivery is
at-least-once — `IScheduleStore`, `docs/scheduling.md` and `docs/hitl.md` all say so — and that
line has been corrected in place.

---

## Decision Log

_For recording decisions that come up during implementation that aren't in the plan._

| Date       | Decision                                                              | Context                               | Alternative Considered                                      |
| ---------- | --------------------------------------------------------------------- | ------------------------------------- | ----------------------------------------------------------- |
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
|            |                                                                       |                                       |                                                             |

---

## Regression Notes

_Track any regressions introduced during implementation for quick debugging._

| Date | Regression | Cause | Fix | Commit |
