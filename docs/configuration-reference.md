# Configuration Reference

Every `eddi.*` property EDDI reads, with its default and the environment
variable that sets it. This is the deployment/operator surface — **agent
behaviour is configured as JSON documents, not here**. If you are trying to
change what an agent *says or does*, you want
[Behavior Rules](behavior-rules.md), [LLM Integration](langchain.md) or
[Properties](properties.md) instead.

---

## How to set these

Three interchangeable mechanisms, in ascending order of precedence:

| Where | Form | Use it for |
|---|---|---|
| `src/main/resources/application.properties` | `eddi.schedule.poll-interval=15s` | Source builds and the shipped defaults |
| Environment variable | `EDDI_SCHEDULE_POLL_INTERVAL=15s` | Docker, Compose, Kubernetes — the normal path |
| JVM system property | `-Deddi.schedule.poll-interval=15s` | One-off local overrides |

**The environment-variable spelling is mechanical:** uppercase the property name
and replace **every character that is not a letter or a digit** with `_`. Both
`.` and `-` are replaced — neither is dropped.

```text
eddi.schedule.poll-interval         →  EDDI_SCHEDULE_POLL_INTERVAL
eddi.vault.master-key               →  EDDI_VAULT_MASTER_KEY
eddi.openai-compat.api-key          →  EDDI_OPENAI_COMPAT_API_KEY
eddi.tools.websearch.google.api-key →  EDDI_TOOLS_WEBSEARCH_GOOGLE_API_KEY
```

> **Getting this wrong fails silently.** An unrecognised environment variable is
> not an error — the property simply keeps its default and the service starts
> normally. `EDDI_VAULT_MASTERKEY` (dash deleted rather than replaced) leaves
> `eddi.vault.master-key` empty, which means the vault is inactive, and a
> `scope: "secret"` property setter then fails the whole turn. Nothing in the
> startup log mentions the variable you set.
>
> To check what actually bound, read the value back from the Dev UI at `/q/dev`,
> or compare against the spellings already used in `docker-compose.yml`,
> `.env.example` and `k8s/`.

Quarkus profiles prefix the key: `%dev.eddi.usermemories.deleteOlderThanDays=-1`
applies in dev mode only.

> **Empty is not the same as unset.** Several properties ship deliberately empty
> (`eddi.vault.master-key`, `eddi.deployment.env`). An empty value is a real
> value that disables or defaults the feature; it is not an error, and startup
> will not complain.

---

## Settings operators set first

The settings a new deployment needs before anything else are mostly **not**
`eddi.*` properties: they belong to Quarkus or to EDDI's MongoDB client, so the
tables further down do not list them. The defaults below are the ones in
`src/main/resources/application.properties`; the environment-variable spelling
follows the same mechanical rule as above (`quarkus.http.cors.origins` →
`QUARKUS_HTTP_CORS_ORIGINS`).

### Datastore

EDDI uses **one** datastore per deployment: MongoDB (the default) or
PostgreSQL.

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `mongodb.connectionString` | `MONGODB_CONNECTIONSTRING` | `mongodb://mongodb:27017/eddi?retryWrites=true&w=majority&connectTimeoutMS=10000&socketTimeoutMS=30000` (`mongodb://localhost:27017/…` in dev mode) | MongoDB connection string, credentials included (`docker-compose.yml` passes one with `authSource=admin`). Read by `PersistenceModule` |
| `mongodb.database` | `MONGODB_DATABASE` | `eddi` | The database EDDI uses. This, not the path in the connection string, selects the database |
| `quarkus.profile` | `QUARKUS_PROFILE` | *(none — MongoDB)* | `postgres` switches the deployment to PostgreSQL: the profile sets `eddi.datastore.type=postgres` and activates the JDBC datasource, which is inactive (`quarkus.datasource.active=false`) otherwise |
| `quarkus.datasource.jdbc.url` | `QUARKUS_DATASOURCE_JDBC_URL` | *(unset)* | PostgreSQL only, e.g. `jdbc:postgresql://postgres:5432/eddi`. Required whenever the `postgres` profile is active — there is no default, and datasource Dev Services are disabled (`quarkus.datasource.devservices.enabled=false`) |
| `quarkus.datasource.username` | `QUARKUS_DATASOURCE_USERNAME` | *(unset)* | PostgreSQL only. Required with the `postgres` profile |
| `quarkus.datasource.password` | `QUARKUS_DATASOURCE_PASSWORD` | *(unset)* | PostgreSQL only. Required with the `postgres` profile |

`docker-compose.postgres-only.yml` is a worked example of the PostgreSQL
settings.

### Authentication (OIDC)

Authentication is off until you turn it on. With it off, a production boot
fails unless you opt out explicitly (see the escape hatches below).

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `quarkus.oidc.tenant-enabled` | `QUARKUS_OIDC_TENANT_ENABLED` | `false` | The master switch. `true` turns on bearer-token validation, and `authorization.enabled` follows it, so `@RolesAllowed` is enforced exactly when this is on |
| `quarkus.oidc.auth-server-url` | `QUARKUS_OIDC_AUTH_SERVER_URL` | `http://localhost:8180/realms/eddi` | The issuer EDDI validates tokens against (for Keycloak, the realm URL) |
| `quarkus.oidc.client-id` | `QUARKUS_OIDC_CLIENT_ID` | `eddi-backend` | EDDI's own client in the identity provider |
| `quarkus.oidc.token.audience` | `QUARKUS_OIDC_TOKEN_AUDIENCE` | `eddi-backend` | Required `aud` of an access token. A token minted for another client is refused unless that client adds this audience (the shipped realm's clients do). The literal `any` disables the audience check |
| `quarkus.oidc.roles.role-claim-path` | `QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH` | `realm_access/roles` | Where EDDI reads roles from in the token. Leave it unless your provider puts roles elsewhere: the Quarkus default (the `groups` claim) replaces a grouped user's roles with group paths, and every role-protected endpoint then answers 403 |

The three opt-outs for running without authentication outside dev mode are all
`false` by default and are described under [Security &
authentication](#security--authentication):
`EDDI_SECURITY_ALLOW_UNAUTHENTICATED` (`eddi.security.allow-unauthenticated`,
checked by `AuthStartupGuard`), and, separately for the two surfaces
`HighValueSurfaceGuard` protects, `EDDI_MCP_ALLOW_UNAUTHENTICATED`
(`eddi.mcp.allow-unauthenticated`) and `EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED`
(`eddi.secretstore.allow-unauthenticated`). The first does not imply the other
two.

### HTTP

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `quarkus.http.port` | `QUARKUS_HTTP_PORT` | `7070` | HTTP port (`quarkus.http.ssl-port` is `7443`) |
| `quarkus.http.host` | `QUARKUS_HTTP_HOST` | Quarkus default: `localhost` in dev and test, `0.0.0.0` otherwise | Bind address. The container image sets `-Dquarkus.http.host=0.0.0.0` in `JAVA_OPTS_APPEND`, and a system property outranks an environment variable, so in the image change it through `JAVA_OPTS_APPEND`, not `QUARKUS_HTTP_HOST` |
| `quarkus.http.cors.origins` | `QUARKUS_HTTP_CORS_ORIGINS` | `http://localhost:3000,http://localhost:7070,https://localhost:7443` | Origins allowed to call the API from a browser. Add the origin of every page that embeds the Chat UI or calls EDDI from another host. CORS itself is on (`quarkus.http.cors.enabled=true`); the allowed methods and headers are set next to it in `application.properties` |
| `quarkus.http.limits.max-body-size` | `QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE` | `60M` | The absolute ceiling on any request body, enforced before EDDI sees the request. Only a knowledge base's file upload may use all of it; every other endpoint is held to `eddi.http.limits.default-max-body-size` (below) |

### Secrets and external configuration

| Setting | Default | Meaning |
|---|---|---|
| `EDDI_VAULT_MASTER_KEY` (`eddi.vault.master-key`) | *(empty — vault inactive)* | The master key the secrets vault derives its key-encryption key from. Set it before storing any secret, and keep it: secrets encrypted under a lost key cannot be recovered. A weak or known key fails a production boot. Details under [Secrets vault](#secrets-vault) and in [secrets-vault.md](secrets-vault.md) |
| `QUARKUS_CONFIG_LOCATIONS` (`quarkus.config.locations`) | *(unset)* | Comma-separated list of extra properties files to load, for keeping secrets in a mounted file instead of environment variables. The `k8s/` manifests mount `/etc/eddi/secrets/application-secrets.properties` this way. Set as an environment variable, the listed file is loaded at environment-variable priority and its value wins over an environment variable that sets the same key, so keep each key in one place |

---

## Data store & messaging

| Property | Default | Description |
|---|---|---|
| `eddi.datastore.type` | `mongodb` | `mongodb` or `postgres`. Selects the whole persistence layer — see [Architecture → DB-agnostic design](architecture.md) |
| `eddi.datastore.postgres.substring-search-index` | `true` | PostgreSQL only. Builds `pg_trgm` trigram indexes for the descriptor listings' search box, in the background after boot (`CREATE INDEX CONCURRENTLY`, so writes are not blocked). Needs `CREATE EXTENSION pg_trgm`, which is trusted since PostgreSQL 13; if the role is refused, a warning is logged and search runs unindexed. Costs about 0.1 ms more per descriptor write. `false` builds nothing |
| `eddi.messaging.type` | `in-memory` | `in-memory` or `nats`. `in-memory` confines the conversation coordinator to a single instance; `nats` distributes it |

### NATS JetStream (only when `eddi.messaging.type=nats`)

| Property | Default | Description |
|---|---|---|
| `eddi.nats.url` | `nats://localhost:4222` | Server URL |
| `eddi.nats.stream-name` | `EDDI_CONVERSATIONS` | JetStream stream carrying conversation work |
| `eddi.nats.dead-letter-stream-name` | `EDDI_DEAD_LETTERS` | Stream that receives messages past `max-retries` |
| `eddi.nats.max-retries` | `3` | Redelivery attempts before dead-lettering |
| `eddi.nats.ack-wait-seconds` | `60` | How long JetStream waits for an ack before redelivering. Must exceed your slowest conversation turn, or slow turns are processed twice |
| `eddi.nats.stream-max-age` | `1h` | Age bound of the conversation stream. Its messages are ordering markers nothing consumes, so the oldest are discarded when any of the three bounds is reached |
| `eddi.nats.stream-max-messages` | `100000` | Message-count bound of the conversation stream |
| `eddi.nats.stream-max-bytes` | `268435456` | Size bound of the conversation stream (256 MiB) |

---

## Conversation lifecycle & retention

| Property | Default | Description |
|---|---|---|
| `eddi.conversations.maximumLifeTimeOfIdleConversationsInDays` | `90` | Idle conversations are ended after this many days without a turn. A daily sweep does it, first run five minutes after boot; it only changes the state to `ENDED` and deletes nothing, and a conversation paused for human approval is never ended. **`-1` (or `0`) disables it**: no conversation is ended for idleness, and startup logs that it is off. The same sweep still undeploys an old agent version once no conversation on it is active, whatever this is set to |
| `eddi.conversations.deleteEndedConversationsOnceOlderThanDays` | `365` | Ended conversations are permanently deleted after this many days since their last interaction. Soft-deleted conversations (deleted without `deletePermanently`) are ended and follow the same clock |
| `eddi.conversations.max-input-chars` | `200000` | Longest turn input, in characters, a caller may send to an existing conversation. Longer input is refused before anything reaches the model: **413** `input_too_large` on the REST and streaming conversation endpoints, **400** `input_too_large` on the OpenAI-compatible API, invalid params over A2A; other surfaces built on those entry points report the refusal as an error. `0` or negative disables the limit. Turns the engine drives itself for group members and sub-agents are exempt |
| `eddi.usermemories.deleteOlderThanDays` | `-1` | Persistent user memories older than this are deleted. **`-1` disables the sweep** — memories are kept forever until you set a positive number. Relevant to [GDPR](gdpr-compliance.md) and [HIPAA](hipaa-compliance.md) |
| `eddi.conversation.client-context.permitted-reserved-keys` | *(empty)* | Comma-separated engine-reserved context keys that clients are allowed to set. Empty (the default) drops all of them from client-supplied context. Only `groupId`, `groupConversationId`, `groupDepth`, `groupTranscript`, `dynamicAgentConfig`, `dynamicCreatedAgentIds` and `delegationDepth` are recognized; anything else is ignored. Set this only where every caller is trusted — see [Passing Context Information](passing-context-information.md#reserved-context-keys) |
| `eddi.coordinator.max-active-conversations` | `10000` | Ceiling on concurrently tracked conversations |
| `eddi.coordinator.max-dead-letters` | `1000` | Retained dead-letter entries. `-1` unbounded, `0` retain none |

### Graceful shutdown

Tuned for Kubernetes rolling updates: EDDI reports itself not-ready first, waits
for the load balancer to notice, then drains in-flight conversations.

| Property | Default | Description |
|---|---|---|
| `eddi.shutdown.readiness-grace-seconds` | `3` | Time between failing readiness and starting the drain. Must exceed your ingress's health-check interval, or requests keep arriving mid-drain |
| `eddi.shutdown.drain-timeout-seconds` | `20` | How long to wait for in-flight conversations before exiting anyway. Keep it below your orchestrator's `terminationGracePeriodSeconds` |
| `eddi.shutdown.drain-poll-millis` | `100` | How often the drain re-checks for completion |

### Streaming

| Property | Default | Description |
|---|---|---|
| `eddi.streaming.cancel-on-client-disconnect` | `true` | Abort the turn when an SSE client goes away. Set `false` to let the turn finish and persist, so a reconnecting client can read the result |
| `eddi.llm.tool-loop.streaming.enabled` | `true` | Stream tokens during tool-calling turns rather than falling back to a single chunk |

---

## Scheduling

Full narrative and metrics: [scheduling.md → Deployment Configuration](scheduling.md#deployment-configuration).

| Property | Default | Description |
|---|---|---|
| `eddi.schedule.enabled` | `true` | Master switch for the poller |
| `eddi.schedule.poll-interval` | `15s` | How often due schedules are looked for — the floor on firing punctuality |
| `eddi.schedule.poll-batch-size` | `100` | Schedules claimed per cycle |
| `eddi.schedule.lease-timeout` | `5m` | Claim lease before another instance may re-claim. **Set above your slowest fire**, or slow runs execute twice |
| `eddi.schedule.max-retries` | `5` | Attempts before `DEAD_LETTERED` |
| `eddi.schedule.backoff-base-seconds` | `15` | Retry delay = `base × multiplier^(attempt-1)` |
| `eddi.schedule.backoff-multiplier` | `4` | Defaults give 15s, 60s, 4m, 16m, 64m |
| `eddi.schedule.min-interval-seconds` | `60` | Smallest cron interval a schedule may request |
| `eddi.schedule.instance-id` | *(hostname)* | Cluster claim identity. Set explicitly where hostnames are recycled |
| `eddi.schedule.default-timezone` | `UTC` | IANA zone for schedules that name none |
| `eddi.schedule.fire-timeout` | `5m` | How long one conversation fire may run before it is abandoned as failed. **Keep it at or below `lease-timeout`** — past the lease another instance may reclaim the schedule regardless |
| `eddi.schedule.persistent-conversation-max-steps` | `0` | Off by default. Steps after which a `conversationStrategy: persistent` schedule ends its (idle) conversation and starts a new one, carrying over its `conversation`-scoped properties but not the LLM history. Keeps the document clear of MongoDB's 16 MB limit; see [Scheduling](scheduling.md#long-running-persistent-schedules) |
| `eddi.schedule.fire-log-retention` | `90d` | Fire logs older than this are deleted by a periodic sweep. `0` keeps everything — a 60-second heartbeat alone writes ~525,600 rows a year |
| `eddi.schedule.fire-log-prune-interval` | `1h` | How often that sweep runs. The `DELETE` is by timestamp and therefore idempotent, so it needs no cluster claim |
| `eddi.rag.ingestion.schedule-repair.enabled` | `true` | At startup, gives a next fire time to any RAG ingestion schedule stored without one. Such a row reads back enabled and can never be selected by the poller, so it looks scheduled and never runs. The sweep only touches rows that have no fire time at all, so an already armed row is skipped and a boot with nothing left to repair does no writes. It is not guaranteed to finish in one pass: it stops at its own 20,000-row bound, and at a store failure, logging a warning that says which — a later boot picks up the rows it never examined — see [rag.md](rag.md#ingestion-sources) |

---

## Security & authentication

> Authentication itself is Quarkus OIDC, not an `eddi.*` property:
> `quarkus.oidc.tenant-enabled` (default `false`) is the master switch, and
> `authorization.enabled` tracks it so `@RolesAllowed` is enforced exactly when
> OIDC is on. See [security.md](security.md).

| Property | Default | Description |
|---|---|---|
| `eddi.security.allow-unauthenticated` | `false` | Permits running with OIDC disabled outside dev. `AuthStartupGuard` refuses a production boot without it |
| `eddi.security.ssrf-protection.enabled` | `false` | **Opt-in.** Validates the fully resolved target of httpCalls, MCP and A2A calls and stops following redirects. Off by default because configured targets legitimately reach internal hosts — **turn it on if any outbound URL is influenced by conversation input.** The cloud instance-metadata service (and the link-local range it lives in) is refused regardless of this setting. See [security.md → SSRF Protection](security.md#ssrf-protection--urlvalidationutils) |
| `eddi.mcp.allow-unauthenticated` | `false` | Exposes the MCP server without auth. Needs its own opt-in on top of `eddi.security.allow-unauthenticated` — inheriting one flag must not be enough to open agent CRUD |
| `eddi.secretstore.allow-unauthenticated` | `false` | Same, for the secrets vault REST surface |
| `eddi.caller-identity.enabled` | `true` | Enables `${caller:token}` / `${caller:userId}` in httpCall headers. See [httpcalls.md](httpcalls.md) |
| `eddi.caller-identity.self-release.enabled` | `true` | Also releases `${caller:token}` to this deployment's own address (`eddi.self.base-url`), not only to the caller's origin — how the Platform Operator's tools call EDDI as the chatting user. The self address bypasses any reverse proxy in front of EDDI, so set `false` if that proxy enforces restrictions EDDI's own authorization does not. See [httpcalls.md](httpcalls.md) |
| `eddi.self.base-url` | *(derived: `http://${quarkus.http.host}:${quarkus.http.port}` when `quarkus.http.host` names one specific address, loopback ones such as `127.0.0.5` or `::1` included, an IPv6 literal bracketed as `http://[::1]:7070`; `http://127.0.0.1:${quarkus.http.port}` for a wildcard, empty or `localhost` bind)* | The address EDDI can reach **itself** at — what the Platform Operator's generated tools target. A bare `scheme://host[:port]`; a path, query, fragment or credentials make it ignored. Set it only when loopback is wrong (TLS terminated in-process, a mesh-required service name) or when SSRF protection is on — the value must then pass the full SSRF target policy. Required with `quarkus.http.port=0`, where nothing can be derived. Served at `GET /administration/operator/self-url` |
| `eddi.keycloak.public.url` | *(empty)* | Browser-facing Keycloak URL when it differs from the in-cluster one |
| `eddi.http.limits.default-max-body-size` | `25M` | Largest request body any endpoint accepts, **except** a knowledge base's file upload (`POST /ragstore/rags/{id}/sources/{sourceId}/files`, matched below `quarkus.http.root-path`), which keeps `quarkus.http.limits.max-body-size` (60 MB). Raised automatically to what an attachment at `eddi.attachments.max-size-bytes` takes base64-encoded in a JSON body (4/3 plus 1 MB — about 27.7 MB for the default 20 MB), so raising the attachment limit never leaves inline attachments refused here — up to `quarkus.http.limits.max-body-size`, which refuses a larger request before any endpoint sees it. An attachment limit above about 44 MB needs more than the 60 MB ceiling once base64-encoded: raise `quarkus.http.limits.max-body-size` with it (a 60 MiB attachment needs `82M`). EDDI logs a WARN at startup naming both settings and the ceiling needed when they disagree. A ZIP import is held to it too: raise it to import a larger archive. Judged on the declared `Content-Length` before the body is read; refusals close the connection. See [rag.md](rag.md#ingestion-sources) |
| `eddi.http.limits.refuse-unsized-bodies` | `true` | Refuses, with 411, a chunked HTTP/1.1 request body — one sent without a `Content-Length` — on every endpoint but the file upload. Such a body cannot be measured before it is read, and counting it as it arrives is not possible where the limit is enforced. JSON clients and browsers send the length; set `false` only for a client that cannot, which leaves those bodies bounded by the global 60 MB alone. An HTTP/2 request without a `content-length` is always left to that global ceiling: at the point the limit is enforced it cannot be told apart from a request with no body |
| `eddi.metrics.http-policy` | `authenticated` | Quarkus HTTP policy for `/q/metrics`. With OIDC on, an anonymous Prometheus scrape gets 401; `permit` opens this one path — only where it cannot be reached from outside. See [monitoring-guide.md → Scraping with authentication on](monitoring/monitoring-guide.md#scraping-with-authentication-on) |
| `eddi.chat.frame-ancestors` | `'none'` | CSP `frame-ancestors` for the Chat UI at `/chat` — the origins allowed to embed it in an `<iframe>`, space-separated (`https://www.example.com https://*.example.org`). The default refuses every embedder; the rest of EDDI always sends `frame-ancestors 'none'` and `X-Frame-Options: DENY` |
| `eddi.csp.extra-connect-sources` | *(empty)* | Sources appended to the Manager's CSP `connect-src`. Needed only for the agent wizard's "fetch the OpenAPI spec from a URL", the one browser request to a host nobody chose in advance — list the spec hosts you use. `https:` allows any host, and with it lets any injected script send data anywhere |

### Workspaces & resource sharing

Full guide: [workspaces.md](workspaces.md).

> Enforcement and ownership are deliberately separate switches. Ownership is
> stamped on every new resource whenever `authorization.enabled` is on,
> regardless of `eddi.workspaces.enabled`, so a deployment can run a release with
> attribution recorded and nothing filtered, confirm the data looks right, and
> only then enforce. Enforcing before ownership has been stamped and backfilled
> is what would hide people's own work from them.

| Property | Default | Description |
|---|---|---|
| `eddi.workspaces.enabled` | `false` | Whether workspace access is actually enforced: listings filtered, and reads, writes, deletes and re-sharing each checked against the level the caller holds (`VIEW` / `USE` / `EDIT` / `OWN`). No effect while `authorization.enabled=false` — with no authenticated principal there is nothing to scope to, and startup warns rather than denying everyone everything |
| `eddi.workspaces.groups-claim` | `groups` | The JWT claim listing the caller's teams. Needs a group-membership protocol mapper on the `eddi-backend` client; without one every user simply gets a personal space and no teams, which is a correct answer rather than a failure |
| `eddi.workspaces.legacy-visibility` | *(unset → `shared`)* | Who may see resources with no recorded owner: `shared` (everyone, so an upgrade hides nothing) or `admin-only` (only `eddi-admin`, so an operator migrates deliberately and then closes the door). **Changeable at runtime** in the Manager or with `PUT /workspaces/settings`; setting the property *pins* it, and the API then refuses to change it. A pinned value other than the two fails startup rather than picking a policy by guessing |
| `eddi.workspaces.default-space` | *(empty)* | Where new resources are filed when a request names no space. Empty means the creator's personal space; a Keycloak group name gives a team-first deployment — for that group's members, while everyone else keeps their personal space. **Changeable at runtime** like `legacy-visibility`, and pinned the same way. A request's `X-EDDI-Space` header — the Manager's "Create in" choice — wins over it for that request |
| `eddi.workspaces.directory.enabled` | `true` | Records every signed-in user (principal, name, username, email and whether the identity provider verified it, teams) so a share can be addressed by name or **verified** email and resolves to a real account, with autocomplete. Off: shares are taken exactly as typed, as before the directory existed. Covered by GDPR erasure and export |
| `eddi.workspaces.directory.expose-email` | `true` | Whether share-box suggestions show people's email addresses. Off shows the username instead |

### Secrets vault

Full guide: [secrets-vault.md](secrets-vault.md).

| Property | Default | Description |
|---|---|---|
| `eddi.vault.master-key` | *(empty)* | KEK source. **Empty means the vault is inactive.** A `scope: "secret"` property setter then scrubs the plaintext, logs an ERROR and **fails the turn** with a `LifecycleException` naming `EDDI_VAULT_MASTER_KEY` — it never persists the value. (`AgentSetupService`'s own `vaultApiKey` path is the exception and still degrades; see [secrets-vault.md](secrets-vault.md).) |
| `eddi.vault.grant-enforcement` | `enforce` | `off`, `warn` or `enforce`. An unrecognised value fails startup rather than silently disabling the check |
| `eddi.vault.allow-weak-master-key` | `false` | Opt-out for the startup master-key strength gate, mirroring `eddi.security.allow-unauthenticated`. A weak or publicly-known master key (too short, too low-entropy, or a known demo/placeholder) normally **fails startup in production**; setting this `true` downgrades that to a WARN so a deployment already on a weak key can boot, rotate to a strong key via `POST /secretstore/secrets/admin/rotate-kek`, then remove the flag. Dev/test always warn regardless |
| `eddi.vault.cache-ttl-minutes` | `5` | Resolved-secret cache lifetime |
| `eddi.vault.cache-max-size` | `1000` | Resolved-secret cache entries |
| `eddi.setup.vault-key-reuse` | `checksum` | `checksum` reuses an existing vault entry when the value matches; `never` always writes a new one. A typo fails startup |
| `eddi.setup.llm.log-conversation-content` | `false` | Log conversation content during agent setup. Leave off outside debugging |

### Compliance gates

| Property | Default | Description |
|---|---|---|
| `eddi.compliance.audit-signing-required` | `false` | Refuse to start unless audit HMAC signing is active |
| `eddi.compliance.database-encryption-acknowledged` | `false` | Operator attestation that encryption-at-rest is configured — see [hipaa-compliance.md](hipaa-compliance.md) |

---

## Audit ledger

Full guide: [audit-ledger.md](audit-ledger.md). Note there is **no retention
property** — the ledger is append-only by design; see
[gdpr-compliance.md](gdpr-compliance.md).

| Property | Default | Description |
|---|---|---|
| `eddi.audit.enabled` | `true` | Master switch |
| `eddi.audit.flush-interval-seconds` | `3` | Batch flush cadence |
| `eddi.audit.max-queue-size` | `100000` | In-memory queue. When full, entries are **dropped** and counted by `eddi_audit_entries_dropped_total` — alert on it, because a non-zero value means the trail has holes |
| `eddi.audit.dead-letter-path` | `/opt/eddi/data/eddi-audit-deadletter.jsonl` | Where undeliverable entries are written. **Must be on a persistent volume**, or dropped entries vanish with the container |
| `eddi.audit.agent-signing-enabled` | `true` | Sign agent configurations for provenance |
| `eddi.audit.verify.recover-legacy` | `true` | Accept pre-HMAC rows during chain verification |
| `eddi.audit.verify.recover-legacy-max-rows` | `500` | Cap on how many such rows are tolerated |
| `eddi.audit.hmac-key` | *(empty)* | Independent signing secret for the ledger (`EDDI_AUDIT_HMAC_KEY`). Empty: the ledger signs with the key it pinned in the vault on first start, which a KEK rotation does not change. See [audit-ledger.md](audit-ledger.md#signing-keys-and-rotation) |
| `eddi.audit.hmac-previous-keys` | *(empty)* | Comma-separated retired `eddi.audit.hmac-key` values (or retired vault master keys) that old entries were signed with. Verification only. An entry naming a key the deployment recorded as having signed, but that nobody lists, reports `UNKNOWN_KEY`; an id that was never recorded reports `INVALID` |

---

## GDPR / CCPA

Full guide: [gdpr-compliance.md](gdpr-compliance.md).

| Property | Default | Description |
|---|---|---|
| `eddi.gdpr.restriction-cache-ttl-seconds` | `0` | How long an Art. 18 restriction verdict may be reused without re-reading the store. **`0` — the default — switches the cache off**, so every check reads the store. The cache is node-local with no cross-node invalidation, so a cached "not restricted" on one node keeps a restricted user being processed for the length of the TTL after another node applies the restriction, and keeps answering from cache through a store outage instead of failing closed. Raise it only on a single-node deployment or one with conversation affinity |

---

## Human-in-the-Loop

Full guide: [hitl.md](hitl.md).

| Property | Default | Description |
|---|---|---|
| `eddi.hitl.tool.enabled` | `true` | Per-tool-call approval gating |
| `eddi.hitl.tool.task-approvals.mode` | `strict` | `strict` unions `requireApproval` patterns, ignores task-level `exempt`, and demotes task-level `AUTO_APPROVE` — a task cannot loosen its agent's gate. `replace` restores pre-6.3.0 behaviour where a task config fully replaces the agent gate |
| `eddi.hitl.tool.journal-retention` | `30d` | How long tool-approval journal entries are kept |
| `eddi.hitl.tool.transcript-max-bytes` | `2000000` | Cap on a stored approval transcript |
| `eddi.hitl.pending.max-age` | *(empty)* | Auto-cancel pending approvals older than this ISO-8601 duration. Empty = never auto-cancel, so approvals wait indefinitely |
| `eddi.hitl.pending.sweep-interval` | `6h` | How often the auto-cancel sweep runs |
| `eddi.hitl.crash-recovery.enabled` | `true` | Restore paused conversations after a restart |
| `eddi.hitl.crash-recovery.recover-in-progress` | `true` | Also recover turns that were mid-execution |
| `eddi.mcp.hitl.mutations.enabled` | `true` | Allow approve/reject decisions through the MCP surface |

---

## Tools & outbound calls

| Property | Default | Description |
|---|---|---|
| `eddi.tools.budget.enforce-by-default` | `false` | Enforce per-conversation tool cost ceilings without a per-task `enforceBudget` flag. See [langchain.md](langchain.md) |
| `eddi.tools.ratelimit.global.enabled` | `false` | Deployment-wide tool rate limit, on top of per-tool limits. Both must admit a call |
| `eddi.tools.ratelimit.global.limit` | `1000` | Calls per minute when the above is on |
| `eddi.tools.web-scraper.max-response-bytes` | `5242880` (5 MB) | Cap on the response body the web-scraper tool reads from an LLM-chosen URL, bounded as it streams |
| `eddi.tools.pdf-reader.max-download-bytes` | `26214400` (25 MB) | Cap on the PDF the PDF-reader tool downloads from an LLM-chosen URL, bounded as it streams |
| `eddi.tools.websearch.provider` | `duckduckgo` | `duckduckgo` (no key) or `google` |
| `eddi.tools.websearch.google.api-key` | *(empty)* | Required for the `google` provider |
| `eddi.tools.websearch.google.cx` | *(empty)* | Google Programmable Search engine ID |
| `eddi.tools.weather.openweathermap.api-key` | *(empty)* | Required by the weather tool |
| `eddi.httpcalls.default-timeout-millis` | `30000` | Per-call timeout when the httpCall does not set one. Without it a call can occupy the conversation thread indefinitely |
| `eddi.httpcalls.batch.default-max-size` | `100` | Most requests a fire-and-forget `batchRequests` may expand into when the call sets no `maxBatchSize`. A larger target array refuses the whole call. See [httpcalls.md](httpcalls.md) |
| `eddi.httpcalls.batch.max-size-ceiling` | `1000` | Highest `maxBatchSize` an http call may set. Saving a config above it is refused (`400`); one stored before the ceiling was lowered runs at the ceiling, with a WARN |
| `eddi.httpcalls.default-max-response-size-bytes` | `2000000` | Response-body ceiling. Deliberately above the memory cap, so an over-long body is truncated into memory rather than failing the turn |
| `eddi.mcpcalls.default-rate-limit` | `100` | Default per-minute limit for MCP tool calls |
| `eddi.ollama.default-base-url` | `http://localhost:11434` | Used when an Ollama LLM config omits `baseUrl` |
| `eddi.templating.max-output-chars` | `2000000` | Upper bound for what one template render may produce, and for any single string an expression evaluates to while rendering. `0` disables it. See [security.md](security.md#runtime-template-engine) |
| `eddi.templating.max-iterations` | `100000` | Upper bound for loop iterations per template render, summed over nested loops. `0` disables it |

---

## Attachments

Full guide: [attachments-guide.md](attachments-guide.md).

| Property | Default | Description |
|---|---|---|
| `eddi.attachments.max-size-bytes` | `20971520` (20 MB) | Largest single upload. Sent inline, an attachment travels base64-encoded (4/3 of its size, plus the message), so above about 44 MB raise `quarkus.http.limits.max-body-size` (60 MB) too, or the request is refused with a bare 413 — a WARN at startup names the ceiling needed |
| `eddi.attachments.max-per-turn` | `5` | Attachments per turn — **per member turn** in a group conversation |
| `eddi.attachments.max-per-conversation` | `50` | Attachments per conversation |
| `eddi.attachments.max-total-bytes-per-conversation` | `104857600` (100 MB) | Aggregate bytes per conversation |
| `eddi.attachments.max-forward-bytes` | `10485760` (10 MB) | Per-file ceiling on what is forwarded to the LLM, across every source |
| `eddi.attachments.max-forward-aggregate-bytes` | `20971520` (20 MB) | Aggregate ceiling for one message |
| `eddi.attachments.extraction.max-chars` | `50000` | Cap on text extracted from a document |
| `eddi.attachments.extraction.max-pages` | `500` | Cap on PDF pages extracted before truncation, bounding a many-page document |

> The upload cap and the forward cap are different numbers on purpose: a 20 MB
> PDF may be stored and read on demand via the `readAttachment` tool without
> being inlined into every prompt.

---

## Backup, export & import

Full guide: [import-export-an-agent.md](import-export-an-agent.md).

| Property | Default | Description |
|---|---|---|
| `eddi.backup.export.retention-minutes` | `60` | How long a finished export archive stays downloadable |
| `eddi.backup.export.sweep-interval` | `15m` | How often the retention sweep runs on its own, independently of exports |
| `eddi.backup.import.max-entries` | `10000` | Most entries (files and directories) an imported or synced agent archive may hold. A larger archive is refused with `413` |
| `eddi.backup.import.max-entry-bytes` | `33554432` (32 MiB) | Most bytes one archive entry may inflate to, counted from what is actually decompressed rather than from the entry header. The importer reads each entry whole into memory, so this also bounds the heap one import can take per file |
| `eddi.backup.import.max-uncompressed-bytes` | `268435456` (256 MiB) | Most bytes a whole archive may inflate to. Together with the two above this stops a small upload that decompresses to gigabytes from filling the disk under `tmp/import` |
| `eddi.backup.sync.require-https` | `true` | Whether live sync refuses a plain `http://` source. The caller's `X-Source-Authorization` bearer travels to that host, so HTTP hands it to anyone on the path — turn this off only between instances on a network you trust |
| `eddi.backup.sync.allow-private-targets` | `false` | Whether live sync accepts a loopback, RFC 1918, ULA, CGNAT or link-local source. Off by default because a caller who can reach the sync endpoint could otherwise use this deployment to probe hosts behind it; on for a single-tenant deployment whose other instances are internal |
| `eddi.backup.sync.allowed-sources` | *(empty)* | Comma-separated exact origins (`scheme://host[:port]`) that live sync accepts whatever the two settings above say. The narrow way to reach one internal staging instance without opening the endpoint to every internal address — **prefer this** |

> These three decide what `POST /backup/import/sync*` will read an agent **from**.
> The strict default is why two instances on one private network — staging and
> production as neighbouring services — could not sync at all before 6.4.1.
> Dev and test mode accept `http://` whatever `require-https` says, so a
> `quarkus:dev` instance needs none of this. A refused URL answers `400` with a
> message naming the setting that would allow it.
> See [agent-sync-guide.md → Reaching the source instance](agent-sync-guide.md#reaching-the-source-instance).

> `POST /backup/export/{agentId}` writes a ZIP under `tmp/archives/` and answers
> with a `Location` header the client then GETs, so the file has to outlive the
> request. Nothing else deletes it: the sweep runs before every export *and* on
> the interval above, so an instance that stops exporting still reclaims what it
> already wrote. It also removes the loose `tmp/*.zip` archives earlier releases
> left behind, which are no longer downloadable. Raise the retention if a client
> may take longer than that between the POST and the GET; lower it to bound disk
> use on an instance that exports on a cron.

---

## Protocols & integrations

### MCP

| Property | Default | Description |
|---|---|---|
| `eddi.mcp.tool-cache.ttl-ms` | `300000` (5 min) | How long a remote server's tool list is cached. Lower it while developing against a changing MCP server |
| `eddi.mcp.tool-description.max-chars` | `1024` | Truncation cap on imported tool descriptions, bounding prompt cost |

### A2A

| Property | Default | Description |
|---|---|---|
| `eddi.a2a.enabled` | `true` | Serve the A2A endpoints |
| `eddi.a2a.base-url` | `http://localhost:7070` | The URL advertised in Agent Cards. **Wrong here means peers cannot reach you** |
| `eddi.a2a.public-token-endpoint` | *(derived)* | The token endpoint advertised in Agent Cards, for peers that cannot resolve the address EDDI uses. Empty derives `<issuer>/protocol/openid-connect/token` from `eddi.keycloak.public.url`, falling back to `quarkus.oidc.auth-server-url` — **which assumes Keycloak**. Set it on any other IdP |
| `eddi.a2a.capabilities.public` | `false` | Serve `/.well-known/capabilities` and `/.well-known/capabilities/skills` unauthenticated, provided `eddi.a2a.enabled` is on too. With either off, both answer 404 to authenticated and anonymous callers alike; no separate authentication gate applies. Exposes agent **ids** (not names), their matching skill, a confidence and any registry attributes; `/skills` exposes the registered skill names |
| `eddi.a2a.tool-description.max-chars` | `1024` | Truncation cap on peer tool descriptions |
| `eddi.a2a.signing.nonce.max-age-ms` | `300000` (5 min) | Replay window for signed requests |
| `eddi.a2a.signing.nonce.clock-skew-ms` | `30000` | Tolerated clock difference between peers |
| `eddi.a2a.task-timeout-seconds` | `systemRuntime.agentTimeoutInSeconds` | How long a peer's `tasks/send` may wait for the turn. Inherits the REST surface's budget, because an operator who raised that has already decided how long a turn may take |

### Slack

Full guide: [slack-integration.md](slack-integration.md).

| Property | Default | Description |
|---|---|---|
| `eddi.slack.request-timeout-seconds` | `60` | How long a single agent turn may take before Slack is told it timed out. A turn that legitimately runs longer — a multi-step tool call, a slow provider, a cascade escalation — needs this raised, and answers with a timeout-specific notice naming the limit rather than a generic error |
| `eddi.slack.group-completion-timeout-seconds` | `300` | How long a whole group discussion may take before follow-up routing gives up |
| `eddi.slack.api-max-retries` | `3` | Attempts, including the first, for a Slack Web API call |
| `eddi.slack.api-retry-base-ms` | `500` | Base delay for the exponential backoff between those attempts |
| `eddi.slack.hitl.approval-record-retention` | `30d` | How long a posted HITL approval card's binding record is kept. A decision (Approve/Reject) on a card older than this is refused; the pause can still be resolved via REST/MCP, and a new message in the thread posts a fresh card. Zero/negative falls back to `30d` |

### OpenAI-compatible API

Full guide: [open-webui-integration.md](open-webui-integration.md).

| Property | Default | Description |
|---|---|---|
| `eddi.openai-compat.enabled` | `false` | Serve `/v1` |
| `eddi.openai-compat.api-key` | *(empty)* | Shared key clients present |
| `eddi.openai-compat.http-policy` | `permit` | `permit` accepts the shared key; `authenticated` has Quarkus OIDC validate per-user tokens instead |
| `eddi.openai-compat.trust-user-headers` | `true` | Believe `X-OpenWebUI-User-Id` as the EDDI userId. Safe only because the caller proved possession of the shared key — **a leaked key therefore permits impersonating any user** |
| `eddi.openai-compat.allow-anonymous` | `false` | Serve requests carrying no user identity |
| `eddi.openai-compat.default-user` | `openai-anonymous` | userId used when anonymous is allowed |
| `eddi.openai-compat.environment` | `production` | Deployment environment agents are resolved from |
| `eddi.openai-compat.expose-stateless-variants` | `true` | Also list `…-stateless` model ids |
| `eddi.openai-compat.adopt-legacy-header-mappings` | `false` | Let an `openwebui:<id>` caller adopt a chat mapped under the raw header id from before namespacing. Enable only if `/v1` never ran with `http-policy=authenticated` — such a mapping may belong to an OIDC principal. Adopted conversations stay owned by the raw id: address both ids in GDPR export/erasure |
| `eddi.openai-compat.model-cache-seconds` | `30` | How long `/v1/models` is cached |
| `eddi.openai-compat.max-concurrent-requests` | `64` | Concurrency ceiling for the adapter |
| `eddi.openai-compat.request-timeout-seconds` | `120` | Per-request timeout |

### Connections

Full guide: [connections.md](connections.md).

The first four are **runtime settings**: an administrator changes them without a
restart through `PUT /connectionstore/settings` (or the Manager's Connections page).
Setting the property **pins** the value instead — it wins over the stored one, and
the endpoint refuses to change it (409). Unset and unstored, each takes the default
below. See [Enabling connections](connections.md#enabling-connections).

| Property | Default | Description |
|---|---|---|
| `eddi.connections.enabled` | `false` | Master switch for the connection credential model. Runtime setting `enabled` |
| `eddi.connections.public-base-url` | *(none)* | Externally reachable base URL for OAuth redirect URIs. Runtime setting `publicBaseUrl`. A pinned value that is not a bare https origin refuses the boot; without one, per-user account linking answers 400 |
| `eddi.connections.credential-endpoint-allowlist` | *(empty)* | Origins that may receive the **client secret** — a connection's token and authorization endpoints, and only those (RFC 9728 resource-metadata discovery is not implemented). Not where the access token goes: that is each connection's own `baseUrlAllowlist`. Runtime setting `credentialEndpointAllowlist` |
| `eddi.connections.allow-plaintext-remote-origins` | `false` | Whether a connection's `baseUrlAllowlist` may send its credential over plaintext `http://` to a non-loopback host. While `false` such an origin is refused at save time (400), refused per request (`TARGET_NOT_ALLOWED`) and reported at ERROR at boot; `true` accepts it with a WARN. Loopback `http://` is always allowed. Runtime setting `allowPlaintextRemoteOrigins`. **Upgrade note:** existing connections with a remote `http://` origin stop resolving until this is set |
| `eddi.connections.settings.allow-unauthenticated-writes` | `false` | Whether `PUT /connectionstore/settings` accepts a write with no verified identity. Outside dev and test, while `authorization.enabled=false`, such a write is refused (403) unless this is `true` — `@RolesAllowed` is a no-op without OIDC, and an anonymous caller must not be able to approve a credential endpoint. Not a runtime setting |
| `eddi.connections.state-sweep-interval` | `1h` | How often expired OAuth state entries are cleared |

> **Upgrade note.** Any of the first four properties that is *set* — even to its
> default, e.g. `EDDI_CONNECTIONS_ENABLED=false` copied from an old example — now
> **pins** that value, and the settings page shows it read-only. Unset it to let
> administrators manage the value at runtime.

---

## Groups, tenancy & variables

| Property | Default | Description |
|---|---|---|
| `eddi.groups.max-depth` | `3` | Nesting limit for groups-of-groups |
| `eddi.groups.cadence.claim-ttl` | `PT24H` | How long a standing-team cadence claim is held |
| `eddi.tenant.default-id` | `default` | Tenant assigned when a request names none |
| `eddi.tenant.quota.enabled` | `false` | Per-tenant quota enforcement |
| `eddi.tenant.quota.max-conversations-per-day` | `-1` | `-1` = unlimited |
| `eddi.tenant.quota.max-agents-per-tenant` | `-1` | `-1` = unlimited |
| `eddi.tenant.quota.max-api-calls-per-minute` | `-1` | `-1` = unlimited |
| `eddi.tenant.quota.max-monthly-cost-usd` | `-1` | `-1` = unlimited |
| `eddi.variables.cache-ttl-minutes` | `2` | [Global variable](global-variables.md) cache lifetime |
| `eddi.deployment.env` | `development` *(when unset)* | Value matched by the `deploymentContext` behavior-rule condition, letting one agent behave differently per environment |

---

## Logging & documentation surfaces

| Property | Default | Description |
|---|---|---|
| `eddi.logs.buffer-size` | `10000` | In-memory ring buffer backing `/administration/logs` |
| `eddi.logs.db-enabled` | `true` | Also persist logs to the database |
| `eddi.logs.db-flush-interval-seconds` | `5` | Persistence batch cadence |
| `eddi.logs.db-persist-min-level` | `WARN` | Minimum level persisted. Lowering this to `DEBUG` in production will fill the database quickly |
| `eddi.docs.enabled` | `true` | Serve EDDI's own docs at `/administration/docs`, as MCP resources (`eddi://docs/*`) and as the `list_docs`/`read_docs` MCP tools. One switch covers all three. The content is the public repository documentation, so this is an exposure policy, not a secrecy control |
| `eddi.docs.path` | `docs` | Directory the above is served from |

---

## Migration

These run once against an existing database and then stay off.

| Property | Default | Description |
|---|---|---|
| `eddi.migration.v6-rename.retention-confirmed` | `false` | Confirms that 6.x's ended-conversation retention (`eddi.conversations.deleteEndedConversationsOnceOlderThanDays`) may delete on a database that came from EDDI 5. EDDI 5 kept ended conversations for ever, so until this is `true` the sweep deletes nothing on such a database and logs daily how much it would delete. That applies once the rename migration has run (recorded in `migrationlog`, so on every later boot and replica), while it is pending, and while the v5 collections still hold documents. Set the retention to `-1` instead to keep them |
| `eddi.migration.v6-rename.enabled` | `false` | Rewrite v5 resource URIs to v6 spellings, and rename the v5 collections (`bots` → `agents`, …). While this is on and has not completed, the ten-second deployment sweep is **parked**: before the rename the agent configs are still under their v5 names, so the sweep would read every deployed agent as deleted and retire its deployment row. Nothing is deployed or reconciled until the migration records completion, so a run that keeps failing shows as agents that never come back — read the migration's own ERROR line for why |
| `eddi.migration.v6-qute.enabled` | `false` | Convert Thymeleaf templates to Qute. A document whose template cannot be converted is left unchanged and logged at ERROR with its collection, id and field the first time it is found; later starts list the documents already reported in one WARN (the list is kept in `migrationlog` as `v6-qute-migration-unconvertible`). The migration is then *not* recorded as complete — so it runs again on the next start, converting any document fixed meanwhile, until none is left |
| `eddi.migration.backupBeforeWrite` | `true` | Before the legacy-format migration (`MigrationManager`: v5-era property, httpcalls and output formats) rewrites a document, copy it into `<collection>[.history].premigrationbackup`. It covers **only** that step: the v6 rename and Qute migrations rewrite in place with no backup of their own, so take a `mongodump` first. See [Upgrading from 5.x](upgrading-from-5x.md) |
| `eddi.migration.skipConversationMemories` | `false` | Skip conversation memories, which are the bulk of the data and rarely need rewriting |
| `eddi.migration.properties.skip-keys` | `userInfo` | Top-level keys of the EDDI 5 `properties` collection that are **not** copied into long-term memory (`usermemories`) on the first 6.x boot. `userInfo` was the caller's per-request identity in 5.x — a platform bearer token beside names and ids — not a memory. Independently of this list, any key whose value holds a credential by the export scrubber's rules is skipped too. Skipped keys are logged by name and count and stay in `properties_migrated_v6` |

---

## See also

- [Metrics & Monitoring](metrics.md) — what to watch once these are set
- [Security](security.md) — the reasoning behind the security defaults
- [Kubernetes](kubernetes.md) — how these map onto Helm values and ConfigMaps
- [`.env.example`](../.env.example) — the Docker Compose subset, ready to copy
- `src/main/resources/application.properties` — the shipped defaults, with inline commentary
