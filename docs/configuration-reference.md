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
| `eddi.mongodb.max-wait-time-ms` | `EDDI_MONGODB_MAX_WAIT_TIME_MS` | `10000` | How long an operation waits for a pooled MongoDB connection before it fails. The driver's own default is 120 s, twice the turn timeout, so a saturated pool held turns long after their callers had given up. A `waitQueueTimeoutMS` in the connection string wins over this; `0` keeps the driver default |
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
| `quarkus.http.port` | `QUARKUS_HTTP_PORT` | `7070` | HTTP port (`quarkus.http.ssl-port` is `7443`, opened only once a TLS certificate is configured — the compose stacks publish it, and it refuses connections until then) |
| `quarkus.http.host` | `QUARKUS_HTTP_HOST` | Quarkus default: `localhost` in dev and test, `0.0.0.0` otherwise | Bind address. The container image sets `-Dquarkus.http.host=0.0.0.0` in `JAVA_OPTS_APPEND`, and a system property outranks an environment variable, so in the image change it through `JAVA_OPTS_APPEND`, not `QUARKUS_HTTP_HOST` |
| `quarkus.http.cors.origins` | `QUARKUS_HTTP_CORS_ORIGINS` | `http://localhost:3000,http://localhost:7070,https://localhost:7443` | Origins allowed to call the API from a browser. Add the origin of every page that embeds the Chat UI or calls EDDI from another host. CORS itself is on (`quarkus.http.cors.enabled=true`); the allowed methods and headers are set next to it in `application.properties`, and `Location`, `Mcp-Session-Id` and `Retry-After` are exposed to browser clients. While authentication is off these origins are also the only foreign ones allowed to send state-changing requests (see `eddi.security.unauthenticated.allowed-hosts`) |
| `quarkus.http.limits.max-body-size` | `QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE` | `60M` | The absolute ceiling on any request body, enforced before EDDI sees the request. Only a knowledge base's file upload may use all of it; every other endpoint is held to `eddi.http.limits.default-max-body-size` (below) |
| `eddi.rest.listing.max-limit` | `EDDI_REST_LISTING_MAX_LIMIT` | `1000` | Largest `limit` any paginated listing accepts — the descriptor listings of every config store and the cross-resource `/descriptors`, the conversation listing, `/usermemorystore/memories/{userId}/visible` and both `/administration/logs` listings. A request above it, or with a negative `index`/`skip`, is refused with **400** (`{"error": …}`) rather than silently clamped, so a client that asked for more than a page holds cannot mistake a truncated page for the whole; page through `index` instead. The Manager's largest page is 1000 (agent-name lookups, dashboard counts), so do not set it lower |

### Secrets and external configuration

| Setting | Default | Meaning |
|---|---|---|
| `EDDI_VAULT_MASTER_KEY` (`eddi.vault.master-key`) | *(empty — vault inactive)* | The master key the secrets vault derives its key-encryption key from. Set it before storing any secret, and keep it: secrets encrypted under a lost key cannot be recovered. A weak or known key fails a production boot. Details under [Secrets vault](#secrets-vault) and in [secrets-vault.md](secrets-vault.md) |
| `QUARKUS_CONFIG_LOCATIONS` (`quarkus.config.locations`) | *(unset)* | Comma-separated list of extra properties files to load, for keeping secrets in a mounted file instead of environment variables. The `k8s/` manifests mount `/etc/eddi/secrets/application-secrets.properties` this way. Set as an environment variable, the listed file is loaded at environment-variable priority and its value wins over an environment variable that sets the same key, so keep each key in one place |

---

## Data store & messaging

| Property | Default | Description |
|---|---|---|
| `eddi.datastore.type` | `mongodb` | `mongodb` or `postgres`, matched exactly and case-sensitively — any other value, `Postgres` included, means MongoDB. Selects which store implementations EDDI uses, but not the connection: PostgreSQL also needs the `postgres` Quarkus profile (`QUARKUS_PROFILE=postgres`, which activates the datasource and sets this property for you) and `QUARKUS_DATASOURCE_JDBC_URL`, `QUARKUS_DATASOURCE_USERNAME` and `QUARKUS_DATASOURCE_PASSWORD`. Setting `EDDI_DATASTORE_TYPE=postgres` without the profile leaves the datasource inactive. A PostgreSQL deployment never opens a MongoDB connection, so no MongoDB is needed — see [Architecture → DB-agnostic design](architecture.md) |
| `eddi.datastore.postgres.startup-lock-timeout` | `180` | PostgreSQL only. Seconds a booting replica waits for the startup advisory lock, which runs the startup (table creation, migrations) of replicas sharing one database one after another — `CREATE TABLE IF NOT EXISTS` is not safe against a concurrent copy of itself. On expiry the node starts without the lock |
| `eddi.datastore.postgres.substring-search-index` | `true` | PostgreSQL only. Builds `pg_trgm` trigram indexes for the descriptor listings' search box, in the background after boot (`CREATE INDEX CONCURRENTLY`, so writes are not blocked). Needs `CREATE EXTENSION pg_trgm`, which is trusted since PostgreSQL 13; if the role is refused, a warning is logged and search runs unindexed. Costs about 0.1 ms more per descriptor write. `false` builds nothing |
| `eddi.messaging.type` | `in-memory` | Read at runtime; one image serves both. `in-memory`: a single node, no dependencies. `nats`: any number of replicas behind a plain round-robin load balancer, coordinated through NATS JetStream — see [Clustering](clustering.md). Any other value fails the boot |

### Clustering (only when `eddi.messaging.type=nats`)

Every property below is inert in `in-memory` mode. The model, the degraded-mode
contract and the operator runbook are in [Clustering](clustering.md).

| Property | Default | Description |
|---|---|---|
| `eddi.cluster.node-id` | host name | This replica's id. Must be unique per replica; falls back to `eddi.schedule.instance-id`, then the host name. Helm and the Kustomize overlay set it from the pod name |
| `eddi.nats.url` | `nats://localhost:4222` | NATS servers, comma-separated — list every member of the NATS cluster |
| `eddi.nats.prefix` | `EDDI` | Prefix of every KV bucket, stream and subject (`eddi.<prefix>.…`), so two deployments can share one NATS cluster |
| `eddi.nats.replicas` | `1` | JetStream replicas of every bucket and stream. `3` on a three-node NATS cluster. A bucket created with another value keeps it (logged as a warning) |
| `eddi.nats.dead-letter-stream-name` | `EDDI_DEAD_LETTERS` | Stream holding the dead letters of failed turns, readable, discardable and replayable from every node. While left at the default it follows `eddi.nats.prefix` (`<prefix>_DEAD_LETTERS` for any prefix other than `EDDI`), so deployments with different prefixes on one NATS cluster never share it |
| `eddi.nats.username` / `eddi.nats.password` | — | NATS user credentials |
| `eddi.nats.token` | — | NATS token authentication |
| `eddi.nats.creds-file` | — | Path of a NATS `.creds` file (JWT + nkey) |
| `eddi.nats.nkey-seed-file` | — | Path of an nkey seed file |
| `eddi.nats.tls.enabled` | `false` | TLS to NATS |
| `eddi.nats.tls.truststore-path` / `eddi.nats.tls.truststore-password` | — | Trust store for the NATS server certificate (`.p12`/`.pfx` read as PKCS12, anything else as the JVM default type) |
| `eddi.nats.tls.keystore-path` / `eddi.nats.tls.keystore-password` | — | Client key store, for mutual TLS |
| `eddi.nats.connection-timeout` | `2s` | Per-attempt connect timeout. The node never waits for NATS at boot: it connects in the background and serves in degraded mode until connected |
| `eddi.nats.request-timeout` | `2s` | Timeout of every KV, JetStream and RPC call. While disconnected, calls fail at once instead |
| `eddi.nats.reconnect-wait` | `1s` | Pause between reconnect attempts (plus jitter); reconnects never stop |
| `eddi.cluster.lease.ttl` | `20s` | A conversation lease that is not refreshed expires on the NATS server after this long — the longest a crashed node can keep a conversation |
| `eddi.cluster.lease.heartbeat-interval` | `5s` | How often a node refreshes the leases of its running turns. At most half the TTL (checked at boot) |
| `eddi.cluster.lease.acquire-timeout` | `45s` | How long a turn waits for a lease another node holds before it is answered `409` with `Retry-After` (its input was not consumed). Keep it below `systemRuntime.agentTimeoutInSeconds` |
| `eddi.cluster.lease.handoff-grace` | `25ms` | A node that releases a lease another node is waiting for holds back its own next acquire this long, so a busy node cannot starve the others |
| `eddi.cluster.presence.interval` | `10s` | How often a node writes its presence record (members expire after three intervals) |
| `eddi.cluster.degraded.grace` | `5s` | A NATS outage shorter than this is ridden out; longer, each area applies its degraded policy |
| `eddi.cluster.degraded.turns` | `local` | `local`: turns run with node-local ordering, unfenced (no loss — appends merge — but concurrent turns on different nodes may miss each other's context). `reject`: `409` with `Retry-After` |
| `eddi.cluster.degraded.nonces` | `reject` | Signed-envelope replay protection when NATS is down: `reject` refuses signed envelopes with `503` (fail closed); `local` checks node-locally only |
| `eddi.cluster.degraded.rate-limits` | `local-share` | `local-share`: each node enforces its share of a global tool limit (limit ÷ known members, never looser than configured); `reject`: rate-limited tools are refused |
| `eddi.cluster.readiness.require-nats` | `false` | `true` reports the node not-ready while degraded. Off by default: a NATS blip must never empty the load balancer |
| `eddi.cluster.events.max-age` | `1h` | Retention of the cluster event stream. A node disconnected for longer, or that sees a gap, flushes every invalidatable cache |
| `eddi.cluster.activity.max-age` | `24h` | Retention of the cluster activity timeline (`<prefix>_ACTIVITY` stream, at most 10,000 entries) the cluster console shows: nodes joining and getting lost, lease takeovers, fenced writes, degraded mode, deployments and admin actions |
| `eddi.cluster.events.outbox-size` | `10000` | Events kept for publishing while NATS is unreachable; on overflow the node asks every node to resync on reconnect |
| `eddi.cluster.local-cache-ttl` | `60s` | Backstop expiry of node-local caches that events invalidate (agent triggers, user conversations), for an event that was lost anyway |
| `eddi.cluster.model-cache.max-age` | `15m` | A cached chat model is rebuilt at least this often even while busy, so a rotated key reaches it even if the rotation event was lost |
| `eddi.cluster.hitl-recovery.interval` | `60s` | Period of the leader-elected sweep that recovers conversations a crashed node left `IN_PROGRESS` |
| `eddi.cluster.hitl-recovery.min-age` | `3m` | A conversation must have been seen `IN_PROGRESS`, with no lease and an unchanged revision, for at least this long before the sweep recovers it |
| `eddi.a2a.task-ttl` | `24h` | How long an A2A task/context mapping is kept in the shared bucket, so a peer's follow-up reaches the conversation whichever node it lands on |
| `eddi.cluster.cost.ttl` | `30d` | How long a conversation's tool-cost total is kept in the shared bucket after its last update |
| `eddi.coordinator.dead-letter.max-age` | `7d` | Retention of the dead-letter stream |
| `eddi.coordinator.dead-letter.max-bytes` | `1073741824` | Size cap of the dead-letter stream in bytes (`<= 0`: none). When full, new dead letters are refused — the oldest are kept for the operator — and stay in the node-local ring (audit entries go to the JSONL file) until there is room |
| `eddi.coordinator.dead-letter.capture-input` | `true` | Record a failed turn's input and context in its dead letter, which is what makes it replayable. `false` keeps only the error. What is recorded passes through the secret redaction filter, and a turn flagged `secretInput` by the client — or addressed to an agent with any `scope: secret` property instruction — records neither input nor context |

---

## Conversation lifecycle & retention

| Property | Default | Description |
|---|---|---|
| `eddi.conversations.maximumLifeTimeOfIdleConversationsInDays` | `90` | Idle conversations are ended after this many days without a turn. A daily sweep does it, first run five minutes after boot; it only changes the state to `ENDED` and deletes nothing, and a conversation paused for human approval is never ended. **`-1` (or `0`) disables it**: no conversation is ended for idleness, and startup logs that it is off. The same sweep still undeploys an old agent version once no conversation on it is active, whatever this is set to |
| `eddi.conversations.deleteEndedConversationsOnceOlderThanDays` | `365` | Ended conversations are permanently deleted after this many days since their last interaction. Soft-deleted conversations (deleted without `deletePermanently`) are ended and follow the same clock |
| `eddi.conversations.max-input-chars` | `200000` | Longest turn input, in characters, a caller may send to an existing conversation. Longer input is refused before anything reaches the model: **413** `input_too_large` on the REST and streaming conversation endpoints, **400** `input_too_large` on the OpenAI-compatible API, invalid params over A2A; other surfaces built on those entry points report the refusal as an error. `0` or negative disables the limit. Turns the engine drives itself for group members and sub-agents are exempt |
| `eddi.expressions.max-length` | `32768` | Longest expression string, in characters, the expression parser accepts — a `context` entry of type `expressions`, a dictionary's `expressions`, a rule's `inputmatcher`/`contextmatcher` text. A client-sent context entry over it is refused with **413** `input_too_large` (the same refusal as a too-long input); one the engine meets inside a turn fails that task. `0` or negative disables the bound. See [security.md](security.md#parser-and-matcher-bounds) |
| `eddi.expressions.max-depth` | `32` | Deepest parenthesis nesting the expression parser accepts, counted the same way and refused the same way as `eddi.expressions.max-length`. Each level is one recursion; the shipped agent configs use three |
| `eddi.turns.idempotency.ttl-seconds` | `600` | How long a completed turn stays recognisable by its `Idempotency-Key` / `X-EDDI-Request-Id`, so a retried `say` returns the stored answer instead of running again. `0` disables idempotent turns. See [Conversations](conversations.md#idempotent-turns) |
| `eddi.usermemories.deleteOlderThanDays` | `-1` | Persistent user memories older than this are deleted. **`-1` disables the sweep** — memories are kept forever until you set a positive number. Relevant to [GDPR](gdpr-compliance.md) and [HIPAA](hipaa-compliance.md) |
| `eddi.conversation.client-context.permitted-reserved-keys` | *(empty)* | Comma-separated engine-reserved context keys that clients are allowed to set. Empty (the default) drops all of them from client-supplied context. Only `groupId`, `groupConversationId`, `groupDepth`, `groupTranscript`, `dynamicAgentConfig`, `dynamicCreatedAgentIds`, `delegationDepth` and `selfContinuation` are recognized; anything else is ignored. Set this only where every caller is trusted — see [Passing Context Information](passing-context-information.md#reserved-context-keys) |
| `eddi.coordinator.max-active-conversations` | `10000` | Ceiling on concurrently tracked conversations: how many conversations may hold a turn queue at once. Only a turn for a conversation that has none is refused; follow-up turns are always queued |
| `eddi.conversation.max-in-flight-turns` | `auto` | Turns this node runs at once, queued or executing. One more is refused with **503** `capacity_exceeded` and `Retry-After`, and counted as `eddi_turn_rejected_total{reason="in_flight_limit"}`. Turns run on virtual threads, so this limit, not the worker pool, is the node's turn ceiling, and heap is what bounds it. **`auto`** derives it from the maximum heap: (max heap − 256 MB) ÷ `eddi.conversation.in-flight-turn-memory-mb`, e.g. 128 at a 512 MB heap, ~1400 at 3 GB, ~2900 at 6 GB. The node logs the value it resolved at startup and exports it as `eddi_conversation_max_in_flight_turns`. A number sets it; `0` or negative disables it. **Leave headroom above the number of conversations you expect to be active at once (roughly 10–20 %).** A turn holds its place until it has been persisted, and that happens after its caller already has the answer, so a client that sends its next turn straight away can arrive before the previous turn released its place. In a load test with exactly as many clients as the limit (1000 against 1000), 5–12 % of turns were refused this way. Keep the database pool in step (MongoDB driver default 100 connections, `maxPoolSize` in the connection string; PostgreSQL `quarkus.datasource.jdbc.max-size`, Quarkus default 50) |
| `eddi.conversation.skip-turns-of-timed-out-callers` | `true` | A REST turn is answered `408` once `systemRuntime.agentTimeoutInSeconds` has passed since its request **arrived**, time spent waiting for a thread included. A turn still queued at that moment is dropped instead of run: its caller already has the `408`, and running it would spend a model call and database writes on an answer nobody reads. Under overload that is every queued turn, which is how goodput can fall to zero. The input is not consumed, so a retry runs normally. Counted as `eddi_turn_skipped_total{reason="caller_timed_out"}`. `false` runs such turns as before. Streams, schedules and group members carry no caller deadline and are unaffected |
| `eddi.conversation.in-flight-turn-memory-mb` | `2` | Heap budget per in-flight turn for `max-in-flight-turns=auto`. Load tests measured about 1 MB for short conversations; 2 leaves room for long histories and tool payloads. Raise it for agents with large RAG contexts or tool results |
| `eddi.conversation.max-queued-turns-per-conversation` | `20` | Turns one conversation may have waiting behind the one it is running; one more answers **503** `capacity_exceeded`, counted as `eddi_turn_rejected_total{reason="conversation_queue"}`. Turns of a conversation run one at a time, so a client that keeps sending without waiting for answers (a retry storm, a bot loop) only builds a queue that holds permits and loaded conversations. `0` or negative disables it |
| `eddi.conversation-upgrade.turn-wait-ms` | `30000` | How long `POST /administration/{environment}/upgradeconversations/{agentId}` waits, per page of 100 conversations, for each conversation's queued turns to finish before its generation write runs. A conversation still busy then is withdrawn and reported as `busy`; run the call again |
| `eddi.coordinator.max-dead-letters` | `1000` | Retained dead-letter entries. `-1` unbounded, `0` retain none |

### Graceful shutdown

Tuned for Kubernetes pod replacement (an upgrade, an eviction, a reschedule): EDDI reports itself
not-ready first, waits for the load balancer to notice, then drains in-flight conversations.

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
| `eddi.llm.max-response-bytes` | `33554432` (32 MB) | Most bytes one LLM provider response may deliver to the OpenAI, Anthropic, Gemini, Mistral and Ollama clients (a streaming response counts over the whole stream). Past it the call fails instead of reading on |
| `eddi.llm.parameter-catalog.drop-rejected` | `true` | Remove an LLM task parameter the catalog says the configured model rejects before the model is built (with a WARN); `false` only warns and still sends it. See [LLM Parameters Reference](llm-parameters.md) |

---

## Scheduling

Full narrative and metrics: [scheduling.md → Deployment Configuration](scheduling.md#deployment-configuration).

| Property | Default | Description |
|---|---|---|
| `eddi.schedule.enabled` | `true` | Master switch for the poller |
| `eddi.schedule.poll-interval` | `15s` | How often due schedules are looked for — the floor on firing punctuality |
| `eddi.schedule.poll-batch-size` | `100` | Schedules claimed per cycle |
| `eddi.schedule.lease-timeout` | `10m` | Claim lease before another instance may re-claim. Must outlast `fire-timeout` + 30 s, or a running fire becomes reclaimable and executes twice; a shorter value is raised at startup (to `fire-timeout + 30s + 1m`) with a warning |
| `eddi.schedule.max-retries` | `5` | Attempts before `DEAD_LETTERED` |
| `eddi.schedule.backoff-base-seconds` | `15` | Retry delay = `base × multiplier^(attempt-1)` |
| `eddi.schedule.backoff-multiplier` | `4` | Defaults give 15s, 60s, 4m, 16m, 64m |
| `eddi.schedule.min-interval-seconds` | `60` | Smallest cron interval a schedule may request |
| `eddi.schedule.instance-id` | *(hostname)* | Cluster claim identity. Set explicitly where hostnames are recycled |
| `eddi.schedule.default-timezone` | `UTC` | IANA zone for schedules that name none |
| `eddi.schedule.fire-timeout` | `5m` | How long one fire (conversation turn, Dream run, team cadence) may run before it is interrupted and recorded FAILED ("exceeded eddi.schedule.fire-timeout"). Raise it for long Dream runs; the lease follows |
| `eddi.schedule.hitl-timeout-retry-interval` | `2m` | When a HITL approval timeout's decision cannot be applied yet (store blip, node draining, a decision in flight), when it is tried again. HITL timeouts are re-armed, never dead-lettered |
| `eddi.schedule.persistent-conversation-max-steps` | `0` | Off by default. Steps after which a `conversationStrategy: persistent` schedule ends its (idle) conversation and starts a new one, carrying over its `conversation`-scoped properties but not the LLM history. Keeps the document clear of MongoDB's 16 MB limit; see [Scheduling](scheduling.md#long-running-persistent-schedules) |
| `eddi.schedule.fire-log-retention` | `90d` | Fire logs older than this are deleted by a periodic sweep. `0` keeps everything — a 60-second heartbeat alone writes ~525,600 rows a year |
| `eddi.schedule.fire-log-prune-interval` | `1h` | How often that sweep runs. The `DELETE` is by timestamp and therefore idempotent, so it needs no cluster claim |
| `eddi.rag.ingestion.schedule-repair.enabled` | `true` | At startup, gives a next fire time to any RAG ingestion schedule stored without one. Such a row reads back enabled and can never be selected by the poller, so it looks scheduled and never runs. The sweep only touches rows that have no fire time at all, so an already armed row is skipped and a boot with nothing left to repair does no writes. It is not guaranteed to finish in one pass: it stops at its own 20,000-row bound, and at a store failure, logging a warning that says which — a later boot picks up the rows it never examined — see [rag.md](rag.md#ingestion-sources) |

---

## Knowledge bases (RAG)

| Property | Default | Description |
|---|---|---|
| `eddi.rag.in-memory.max-segments` | `50000` | Most segments all `in-memory` knowledge bases may hold together. In-memory stores are never evicted, so this is what bounds their heap use — at 1536 dimensions, 50,000 segments are roughly 300 MB of vectors plus their text. A write past it fails (an ingestion run reports `FAILED` naming this setting) and nothing is evicted to make room. `0` or negative disables the cap — see [rag.md](rag.md#vector-stores) |

---

## Agent testing

Full narrative: [agent-testing.md](agent-testing.md).

| Property | Default | Description |
|---|---|---|
| `eddi.agent-tests.max-parallel-cases` | `4` | Case repetitions of one run that execute at the same time, each a real conversation in the `test` environment |
| `eddi.agent-tests.max-concurrent-runs` | `2` | Runs that may execute at once on one node; another start is answered 429 |
| `eddi.agent-tests.max-runs-per-agent` | `50` | Run records kept per agent; older ones are deleted when a run finishes |

---

## Security & authentication

> Authentication itself is Quarkus OIDC, not an `eddi.*` property:
> `quarkus.oidc.tenant-enabled` (default `false`) is the master switch, and
> `authorization.enabled` tracks it so `@RolesAllowed` is enforced exactly when
> OIDC is on. See [security.md](security.md).

| Property | Default | Description |
|---|---|---|
| `eddi.security.allow-unauthenticated` | `false` | Permits running with OIDC disabled outside dev. `AuthStartupGuard` refuses a production boot without it |
| `eddi.security.ssrf-protection.enabled` | `false` | **Opt-in.** Validates the fully resolved target of httpCalls, MCP and A2A calls and stops following redirects; also every configured LLM/embedding endpoint, vector-store host and OpenAPI spec location (exempt: `eddi.self.base-url` and the `allowed-hosts` below). Off by default because configured targets legitimately reach internal hosts — **turn it on if any outbound URL is influenced by conversation input.** The cloud instance-metadata service (and the link-local range it lives in) is refused regardless of this setting. See [security.md → SSRF Protection](security.md#ssrf-protection--urlvalidationutils) |
| `eddi.security.ssrf-protection.allowed-hosts` | *(unset)* | Internal hosts httpCalls, LLM/embedding endpoints, vector stores and OpenAPI spec locations may still reach with `eddi.security.ssrf-protection.enabled=true`: comma-separated exact `host` or `host:port` entries, case-insensitive, no wildcards (a bare host matches any port). Listed hosts skip the private-address check, never the cloud-metadata check. While protection is **off**, an httpCall whose host or port is a template is refused at deploy time, and production logs a `[SECURITY]` WARN at startup. See [security.md → SSRF Protection](security.md#ssrf-protection--urlvalidationutils) |
| `eddi.security.unauthenticated.allowed-hosts` | *(unset)* | Only while authentication is **off** (`authorization.enabled=false`): the host names, besides `localhost`, `*.localhost`, IP addresses and the container-host names `host.docker.internal`, `gateway.docker.internal` and `host.containers.internal`, that EDDI answers to — comma-separated `host` or `host:port`, case-insensitive, a bare host matching any port. A request addressed to any other `Host` gets **421**, which is what stops a DNS-rebinding page from reaching an unauthenticated EDDI through the browser; `*` turns the check off. The same filter refuses, with **403**, a `POST`/`PUT`/`PATCH`/`DELETE` that a browser marks cross-site (an `Origin` that is neither the request's own nor in `quarkus.http.cors.origins`, or `Sec-Fetch-Site: cross-site`/`same-site` without one). `/q/health` is exempt from both. The compose stacks default it to `eddi`, their service name. See [security.md → Running without authentication](security.md#running-without-authentication) |
| `eddi.security.hsts.enabled` | `false` | Sends `Strict-Transport-Security: max-age=<eddi.security.hsts.max-age>` on every response, so a browser that has seen the origin once refuses a plain-http connection to it afterwards. Off because EDDI usually serves plain http behind a TLS-terminating proxy; turn it on when the public origin is https only (the Helm chart does when `ingress.tls` is set). Sent whatever the request's own scheme, since behind the proxy every request EDDI sees is http. `includeSubDomains`/`preload` are not offered — set them at the proxy if the whole domain is https-only |
| `eddi.security.hsts.max-age` | `31536000` | The `max-age`, in seconds, of the header above. `0` tells browsers to forget the policy |
| `eddi.health.verbose` | `false` | Whether the public `/q/health` readiness output carries the cluster check's details — mode, NATS connection state, `degradedSince`, node id, member count, drain state. Off, the check reports its status only: that is all a kubelet or load balancer reads, and node ids and cluster size are reconnaissance for anyone who can reach the port. `GET /administration/cluster/overview` (authenticated) reports them regardless |
| `eddi.mcp.allow-unauthenticated` | `false` | Exposes the MCP server without auth. Needs its own opt-in on top of `eddi.security.allow-unauthenticated` — inheriting one flag must not be enough to open agent CRUD |
| `eddi.secretstore.allow-unauthenticated` | `false` | Same, for the secrets vault REST surface |
| `eddi.caller-identity.enabled` | `true` | Enables `${caller:token}` / `${caller:userId}` in httpCall headers. See [httpcalls.md](httpcalls.md) |
| `eddi.caller-identity.author-roles` | `eddi-admin` | Roles allowed to **save or import** a configuration containing `${caller:token}` (httpcalls, mcpcalls and LLM configs, through REST or an import or sync); any one suffices. Others get **403**. `${caller:userId}` is not affected. Applies only with `authorization.enabled=true`. Widen it (e.g. `eddi-admin,eddi-editor`) only if every editor is trusted with the privileges of everyone who chats with their agents. See [httpcalls.md](httpcalls.md#who-may-write-callertoken) |
| `eddi.caller-identity.self-release.enabled` | `true` | Also releases `${caller:token}` to this deployment's own address (`eddi.self.base-url`), not only to the caller's origin — how the Platform Operator's tools call EDDI as the chatting user. The self address bypasses any reverse proxy in front of EDDI, so set `false` if that proxy enforces restrictions EDDI's own authorization does not. See [httpcalls.md](httpcalls.md) |
| `eddi.self.base-url` | *(derived: `http://${quarkus.http.host}:${quarkus.http.port}` when `quarkus.http.host` names one specific address, loopback ones such as `127.0.0.5` or `::1` included, an IPv6 literal bracketed as `http://[::1]:7070`; `http://127.0.0.1:${quarkus.http.port}` for a wildcard, empty or `localhost` bind)* | The address EDDI can reach **itself** at — what the Platform Operator's generated tools target. A bare `scheme://host[:port]`; a path, query, fragment or credentials make it ignored. Set it only when loopback is wrong (TLS terminated in-process, a mesh-required service name) or when SSRF protection is on — the value must then pass the full SSRF target policy. Required with `quarkus.http.port=0`, where nothing can be derived. Served at `GET /administration/operator/self-url` |
| `eddi.build.commit` | `unknown` | The git commit this build came from, reported by `GET /administration/version` and the `get_eddi_version` MCP tool so an operator can tell which build is running after an upgrade. The Docker image sets it at build time (environment variable `EDDI_BUILD_COMMIT`); set it yourself only for a jar run outside the image. A value that is not a hex commit is reported as `unknown` |
| `eddi.keycloak.public.url` | *(empty)* | Browser-facing Keycloak URL when it differs from the in-cluster one |
| `eddi.http.limits.default-max-body-size` | `25M` | Largest request body any endpoint accepts, **except** a knowledge base's file upload (`POST /ragstore/rags/{id}/sources/{sourceId}/files`, matched below `quarkus.http.root-path`), which keeps `quarkus.http.limits.max-body-size` (60 MB). Raised automatically to what an attachment at `eddi.attachments.max-size-bytes` takes base64-encoded in a JSON body (4/3 plus 1 MB — about 27.7 MB for the default 20 MB), so raising the attachment limit never leaves inline attachments refused here — up to `quarkus.http.limits.max-body-size`, which refuses a larger request before any endpoint sees it. An attachment limit above about 44 MB needs more than the 60 MB ceiling once base64-encoded: raise `quarkus.http.limits.max-body-size` with it (a 60 MiB attachment needs `82M`). EDDI logs a WARN at startup naming both settings and the ceiling needed when they disagree. A ZIP import is held to it too: raise it to import a larger archive. Judged on the declared `Content-Length` before the body is read; refusals close the connection. See [rag.md](rag.md#ingestion-sources) |
| `eddi.http.limits.refuse-unsized-bodies` | `true` | Refuses, with 411, a chunked HTTP/1.1 request body — one sent without a `Content-Length` — on every endpoint but the file upload. Such a body cannot be measured before it is read, and counting it as it arrives is not possible where the limit is enforced. JSON clients and browsers send the length; set `false` only for a client that cannot, which leaves those bodies bounded by the global 60 MB alone. An HTTP/2 request without a `content-length` is always left to that global ceiling: at the point the limit is enforced it cannot be told apart from a request with no body |
| `eddi.metrics.http-policy` | `eddi-metrics-reader` | Quarkus HTTP policy for `/q/metrics`. The default admits a token carrying one of `eddi.metrics.roles-allowed`; with OIDC on, an anonymous scrape gets 401 and a token without the role 403. `authenticated` admits any token (the 6.5 default); `permit` opens this one path — only where it cannot be reached from outside. See [monitoring-guide.md → Scraping with authentication on](monitoring/monitoring-guide.md#scraping-with-authentication-on) |
| `eddi.metrics.dedicated-scrape-threads` | `true` | Serves `GET`/`HEAD /q/metrics` on two threads of its own instead of the worker pool the blocking REST endpoints share, so the scrape still answers when that pool is saturated (in a load test, conversation starts held it for 106 s and the scrape timed out throughout). The permission above still applies. `false` hands the endpoint back to Quarkus' own handler |
| `eddi.metrics.roles-allowed` | `eddi-admin,eddi-metrics` | The realm roles the default metrics policy admits. `eddi-metrics` is not in the shipped realm: create it and give it to the scrape's service account |
| `eddi.api-docs.http-policy` | `eddi-api-docs-reader` | Quarkus HTTP policy for the API description — `/openapi`, `/q/openapi`, `/q/swagger-ui`. The default admits a token carrying one of `eddi.api-docs.roles-allowed`; `authenticated` admits any token, `permit` anyone. See [security.md → Deny by default](security.md#deny-by-default-and-what-a-role-less-token-reaches) |
| `eddi.api-docs.roles-allowed` | `eddi-admin,eddi-editor,eddi-user,eddi-approver,eddi-viewer` | The realm roles the default API-description policy admits — any EDDI role. The Manager reads `/openapi` for the backend version, and the Platform Operator for its tool list |
| `eddi.chat.frame-ancestors` | `'none'` | CSP `frame-ancestors` for the Chat UI at `/chat` — the origins allowed to embed it in an `<iframe>`, space-separated (`https://www.example.com https://*.example.org`). The default refuses every embedder; the rest of EDDI always sends `frame-ancestors 'none'` and `X-Frame-Options: DENY` |
| `eddi.chat.url-token.enabled` | `false` | Whether the Chat UI honours a bearer token passed as `?token=` in its URL. Off, the widget ignores the parameter (and says so in the browser console, naming this property) and takes a token only from the `postMessage` hand-off (`?tokenOrigin=`) or `ChatConfig.authToken`. A token in the URL lets a crafted link sign the widget in as whoever minted it (login CSRF), and the first request logs it before the widget strips it from the address bar — turn this on only for an embedding that cannot use the hand-off. See [the Chat UI embedding contract](chat-ui-embedding.md) |
| `eddi.chat.img-sources` | `https:` | Sources appended to the Chat UI's CSP `img-src` (after `'self' data: blob:`), so the image output items an agent designer configures can be shown from wherever they are hosted. Markdown images in model-written text are never rendered as `<img>`, whatever this says. `https:` allows any https host, which also lets markup injected by some other flaw send data to any host through an `<img>`; list your image hosts (`https://cdn.example.com`) to keep that narrow, or set `'self'` to allow none beyond EDDI (an empty value falls back to the default). See [the Chat UI embedding contract](chat-ui-embedding.md) |
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
| `eddi.workspaces.directory.enabled` | *(unset → follows `eddi.workspaces.enabled`)* | Records every signed-in user (principal, name, username, email and whether the identity provider verified it, teams) so a share can be addressed by name or **verified** email and resolves to a real account, with autocomplete. Off: shares are taken exactly as typed, as before the directory existed. Unset, it is on exactly when workspaces are enforced. Suggestions start at three characters, and a non-admin is offered only their own teams and their members. Covered by GDPR erasure and export |
| `eddi.workspaces.directory.expose-email` | `true` | Whether share-box suggestions show people's email addresses. Off shows the username instead. Even on, an address is shown only to `eddi-admin` or to somebody who shares a team with that person |

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
[gdpr-compliance.md](gdpr-compliance.md). A GDPR erasure keeps the user's rows
but redacts their content (`eddi.audit.erasure-mode`).

| Property | Default | Description |
|---|---|---|
| `eddi.audit.enabled` | `true` | Master switch |
| `eddi.audit.flush-interval-seconds` | `3` | Batch flush cadence |
| `eddi.audit.max-queue-size` | `100000` | In-memory queue. When full, entries are **dropped** and counted by `eddi_audit_entries_dropped_total` — alert on it, because a non-zero value means the trail has holes |
| `eddi.audit.dead-letter-path` | `/opt/eddi/data/eddi-audit-deadletter.jsonl` | Where undeliverable entries are written. **Must be on a persistent volume**, or dropped entries vanish with the container |
| `eddi.audit.agent-signing-enabled` | `true` | Sign each audit entry with the agent's Ed25519 key, when the vault holds one under `agent-signing-key:{agentId}`. An agent without a key is not looked up again for 60 s, so a key written into the vault by hand is used within a minute |
| `eddi.audit.verify.recover-legacy` | `true` | Accept pre-HMAC rows during chain verification |
| `eddi.audit.verify.recover-legacy-max-rows` | `500` | Cap on how many such rows are tolerated |
| `eddi.audit.hmac-key` | *(empty)* | Independent signing secret for the ledger (`EDDI_AUDIT_HMAC_KEY`). Empty: the ledger signs with the key it pinned in the vault on first start, which a KEK rotation does not change. See [audit-ledger.md](audit-ledger.md#signing-keys-and-rotation) |
| `eddi.audit.erasure-mode` | `redact` | What `DELETE /admin/gdpr/{userId}` does to the user's ledger rows. `redact`: the user id is pseudonymized **and** the recorded content (`input`, `output`, `llmDetail`, `toolCalls`) is replaced by a redaction marker; rows that verified are re-signed, so they still verify, and the chain stays intact. `pseudonymize`: the user id only — prompts, responses and tool calls are **kept**; for a legal hold that requires the content, and logged as a WARN at startup. Any other value means `redact`. See [audit-ledger.md](audit-ledger.md#gdpr-erasure-redaction-not-deletion) |
| `eddi.audit.admin-actions.enabled` | `true` | Record every mutating REST request outside the chat APIs — caller, method, path, endpoint, status; never the body — in the ledger. See [audit-ledger.md](audit-ledger.md#administrative-actions) |
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
| `eddi.hitl.revision.transcript-max-bytes` | `262144` | Cap on the request an answer keeps for a reviewer's revision (`hitlConfig.onRejection: REVISE`); it is stored in the paused step. An answer over the cap has nothing to revise, and a rejection skips |
| `eddi.hitl.pending.max-age` | *(empty)* | Auto-cancel pending approvals older than this ISO-8601 duration. Empty = never auto-cancel, so approvals wait indefinitely |
| `eddi.hitl.pending.sweep-interval` | `6h` | How often the auto-cancel sweep runs |
| `eddi.hitl.metrics.refresh-interval` | `60s` | How often `eddi_hitl_pending` and `eddi_hitl_pending_oldest_age_seconds` re-read the approval backlog from the conversation store (a bounded, projected read of at most 1000 rows, off the scrape thread). `off` disables it; both gauges then read `NaN` |
| `eddi.hitl.crash-recovery.enabled` | `true` | Restore paused conversations after a restart |
| `eddi.hitl.crash-recovery.recover-in-progress` | `true` | Also recover turns that were mid-execution |
| `eddi.mcp.hitl.mutations.enabled` | `true` | Allow approve/reject decisions through the MCP surface |

---

## Tools & outbound calls

| Property | Default | Description |
|---|---|---|
| `eddi.tools.budget.enforce-by-default` | `true` | Whether a per-conversation tool cost ceiling (`maxBudgetPerConversation`) refuses calls when the task does not set `enforceBudget` itself. `false` makes such ceilings advisory (tracked, never refused) and names each one in a WARN. See [langchain.md](langchain.md#budgets) |
| `eddi.tools.ratelimit.global.enabled` | `false` | Deployment-wide tool rate limit, on top of per-tool limits. Both must admit a call |
| `eddi.tools.ratelimit.global.limit` | `1000` | Calls per minute when the above is on |
| `eddi.tools.web-scraper.max-response-bytes` | `5242880` (5 MB) | Cap on the response body the web-scraper tool reads from an LLM-chosen URL, bounded as it streams |
| `eddi.tools.web-scraper.parse-threads` | `2` | Platform threads that may parse pages / evaluate CSS selectors at the same time; a call that finds all busy is refused at once. Bounds the CPU a runaway selector can hold — see [Web Scraper Tool](langchain.md#web-scraper-tool) |
| `eddi.tools.web-scraper.parse-timeout-ms` | `10000` | Deadline for parsing one page and evaluating one selector; past it the call is reported to the model as a failure |
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

### Outbound HTTP client

EDDI's own outbound requests — the built-in tools, the RAG crawler, OAuth token requests, attachment downloads, A2A peers, Slack and remote agent sync — go through one facade, `OutboundHttp`, which applies the SSRF checks, redirect rules, cross-origin credential stripping, deadlines and body caps. `httpcalls.json` and the LLM providers still use their own clients, and these settings do not reach them yet. Remote sync uses its own `internal-peer` pool but, having no settings of its own yet, the `default` profile's values below.

| Property | Default | Description |
|---|---|---|
| `eddi.http.outbound.engine` | `jdk` | The HTTP engine. `jdk` (`java.net.http`) is the only one in this release; any other value fails startup |
| `eddi.http.outbound.default.connect-timeout` | value of `httpClient.connectTimeoutInMillis` (10 s) | TCP + TLS connect timeout per hop, as a duration (`10s`, `500ms`) |
| `eddi.http.outbound.default.total-timeout` | 3 × connect timeout | Least wall-clock budget for reaching the final response, every redirect hop included. A request whose own timeout is longer gets that instead. The final response body is bounded separately, by 4 × the request's per-hop timeout |
| `eddi.http.outbound.default.protocol` | `auto` | `auto` (HTTP/2 via ALPN, else HTTP/1.1), `http1` or `http2` |
| `eddi.http.outbound.default.tls.configuration-name` | *(empty)* | A named Quarkus TLS configuration (`quarkus.tls.<name>.*`) for trust and client certificates. Empty uses the JVM's default trust store. An unknown name fails the first request |
| `eddi.http.outbound.proxy.host` | *(empty)* | Forward proxy for all of the above. Empty means no proxy |
| `eddi.http.outbound.proxy.port` | *(none)* | Required when `eddi.http.outbound.proxy.host` is set |
| `eddi.http.outbound.proxy.non-proxy-hosts` | *(empty)* | `\|`-separated hosts that bypass the proxy; `*` is a wildcard at either end, e.g. `localhost\|*.internal` |
| `eddi.http.outbound.proxy.credentials` | *(empty)* | `user:password` for the proxy, ideally a `${vault:...}` reference. Answered only to the proxy's challenges, never a target server's. An unresolvable vault reference fails closed. The JDK does not send Basic credentials when tunnelling HTTPS through a proxy unless `jdk.http.auth.tunneling.disabledSchemes` allows it |

---

## Attachments

Full guide: [attachments-guide.md](attachments-guide.md).

| Property | Default | Description |
|---|---|---|
| `eddi.attachments.max-size-bytes` | `20971520` (20 MB) | Largest single upload. Sent inline, an attachment travels base64-encoded (4/3 of its size, plus the message), so above about 44 MB raise `quarkus.http.limits.max-body-size` (60 MB) too, or the request is refused with a bare 413 — a WARN at startup names the ceiling needed |
| `eddi.attachments.max-per-turn` | `5` | Attachments per turn — **per member turn** in a group conversation |
| `eddi.attachments.max-per-conversation` | `50` | Attachments per conversation |
| `eddi.attachments.max-total-bytes-per-conversation` | `104857600` (100 MB) | Aggregate bytes per conversation |
| `eddi.attachments.max-per-user` | `500` | Attachments per user across all of their conversations, counted against the conversation's owner. `0` disables the quota — it used to be the default, which let one user fill the attachment store alone. Quotas are checked and the blob stored under a lock per scope, so concurrent uploads cannot overshoot any of them |
| `eddi.attachments.max-total-bytes-per-user` | `1073741824` (1 GiB) | Aggregate bytes per user across all of their conversations; `0` disables |
| `eddi.attachments.max-forward-bytes` | `10485760` (10 MB) | Per-file ceiling on what is forwarded to the LLM, across every source |
| `eddi.attachments.max-forward-aggregate-bytes` | `20971520` (20 MB) | Aggregate ceiling for one message |
| `eddi.attachments.extraction.max-chars` | `50000` | Cap on text extracted from a document |
| `eddi.attachments.extraction.max-pages` | `500` | Cap on PDF pages extracted before truncation, bounding a many-page document — for a page range (`readAttachment`, `PdfReaderTool`) too, counted from its first page. Every PDF read for an attachment or a tool also runs under the ingestion path's decode budgets: one compressed stream may expand to at most 64 MB and the whole document to 128 MB |

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
| `eddi.backup.import.max-uncompressed-bytes` | `268435456` (256 MiB) | Most bytes a whole archive may inflate to. Together with the two above this stops a small upload that decompresses to gigabytes from filling the disk under `tmp/import`. It also bounds the archive's own (compressed) size: this value plus 1 KiB per allowed entry (`max-entries`) — anything larger is refused with `413` while it streams, whoever supplies the stream |
| `eddi.backup.sync.require-https` | `true` | Whether live sync refuses a plain `http://` source. The caller's `X-Source-Authorization` bearer travels to that host, so HTTP hands it to anyone on the path — turn this off only between instances on a network you trust |
| `eddi.backup.sync.allow-private-targets` | `false` | Whether live sync accepts a loopback, RFC 1918, ULA, CGNAT or link-local source. Off by default because a caller who can reach the sync endpoint could otherwise use this deployment to probe hosts behind it; on for a single-tenant deployment whose other instances are internal |
| `eddi.backup.sync.allowed-sources` | *(empty)* | Comma-separated exact origins (`scheme://host[:port]`) that live sync accepts whatever the two settings above say. The narrow way to reach one internal staging instance without opening the endpoint to every internal address — **prefer this** |
| `eddi.backup.sync.max-response-bytes` | `16777216` (16 MiB) | Most bytes live sync accepts in one JSON response from the source instance (descriptor listings, configs). A larger declared `Content-Length` is refused before reading, an undeclared body is cut off at the cap, and the sync fails with `502`. Bounds how much heap a compromised or misbehaving source can make one sync take. The archive a first-time sync downloads has its own fixed 256 MiB cap |

> `require-https`, `allow-private-targets` and `allowed-sources` decide what `POST /backup/import/sync*` will read an agent **from**.
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
| `eddi.mcp.tool-description.max-chars` | `1024` | Truncation cap on imported tool descriptions, bounding prompt cost. Also the cap on every description, title and enum value inside a tool's parameter schema |
| `eddi.mcp.tool-schema.max-bytes` | `32768` | Cap on one remote tool's serialised parameter schema. Over it, the schema's descriptions are stripped first; a schema still over it is dropped with its tool (`eddi_mcp_tool_schema_governed_total{outcome=dropped}`) |
| `eddi.mcp.tool-schema.max-bytes-per-server` | `524288` (512 KB) | Cap on the schema bytes one MCP server may put into the prompt across all its tools per discovery; tools past it are dropped (`outcome=dropped_server_cap`) |

### A2A

| Property | Default | Description |
|---|---|---|
| `eddi.a2a.enabled` | `true` | Serve the A2A endpoints |
| `eddi.a2a.base-url` | `http://localhost:7070` | The URL advertised in Agent Cards. **Wrong here means peers cannot reach you** |
| `eddi.a2a.public-token-endpoint` | *(derived)* | The token endpoint advertised in Agent Cards, for peers that cannot resolve the address EDDI uses. Empty derives `<issuer>/protocol/openid-connect/token` from `eddi.keycloak.public.url`, falling back to `quarkus.oidc.auth-server-url` — **which assumes Keycloak**. Set it on any other IdP |
| `eddi.a2a.capabilities.public` | `false` | Serve `/.well-known/capabilities` and `/.well-known/capabilities/skills` unauthenticated, provided `eddi.a2a.enabled` is on too. With either off, both answer 404 to authenticated and anonymous callers alike; no separate authentication gate applies. Exposes agent **ids** (not names), their matching skill, a confidence and any registry attributes; `/skills` exposes the registered skill names |
| `eddi.a2a.tool-description.max-chars` | `1024` | Truncation cap on peer tool descriptions, and on every text inside a peer tool's parameter schema |
| `eddi.a2a.tool-schema.max-bytes` | `32768` | As `eddi.mcp.tool-schema.max-bytes`, for A2A peer tools |
| `eddi.a2a.tool-schema.max-bytes-per-agent` | `524288` (512 KB) | As `eddi.mcp.tool-schema.max-bytes-per-server`, per A2A peer |
| `eddi.a2a.signing.nonce.max-age-ms` | `300000` (5 min) | Replay window for signed requests |
| `eddi.a2a.signing.nonce.clock-skew-ms` | `30000` | Tolerated clock difference between peers |
| `eddi.a2a.task-timeout-seconds` | `systemRuntime.agentTimeoutInSeconds` | How long a blocking A2A send (`SendMessage`, `message/send`, `tasks/send`) waits for the turn before answering with the task still `working` — the peer then polls `GetTask`. Inherits the REST surface's budget, because an operator who raised that has already decided how long a turn may take |
| `eddi.a2a.max-concurrent-requests` | `64` | How many A2A turns may run at once, across all peers and agents. The excess is refused with HTTP `503`, `Retry-After: 1` and a JSON-RPC error, before any conversation is started. A slot is held for the turn (a `returnImmediately` send and a stream keep it until the turn settles) and released at the latest task timeout + 30 s after it was taken |

### Slack

Full guide: [slack-integration.md](slack-integration.md).

| Property | Default | Description |
|---|---|---|
| `eddi.slack.request-timeout-seconds` | `60` | How long a single agent turn may take before Slack is told it timed out. A turn that legitimately runs longer — a multi-step tool call, a slow provider, a cascade escalation — needs this raised, and answers with a timeout-specific notice naming the limit rather than a generic error |
| `eddi.slack.group-completion-timeout-seconds` | `300` | How long a whole group discussion may take before follow-up routing gives up |
| `eddi.slack.api-max-retries` | `3` | Attempts, including the first, for a Slack Web API call |
| `eddi.slack.api-retry-base-ms` | `500` | Base delay for the exponential backoff between those attempts |
| `eddi.slack.hitl.approval-record-retention` | `30d` | How long a posted HITL approval card's binding record is kept. A decision (Approve/Reject) on a card older than this is refused; the pause can still be resolved via REST/MCP, and a new message in the thread posts a fresh card. Zero/negative falls back to `30d` |
| `eddi.webhook.max-body-bytes` | `262144` | Largest body, in bytes, the signed inbound webhook endpoint (`POST /integrations/webhook/{integrationId}`) reads before answering 413. The cap is applied to the raw bytes before the signature is checked, so it also bounds what an unauthenticated caller can make EDDI buffer |

### Email

There is no email channel yet. These settings govern the **system mailer** — the deployment's one SMTP sender, used
today for [HITL email notifications](hitl.md#email-notifications) — its outbound message ledger, the mail socket
guard the coming IMAP/SMTP transports will connect through, and the DNS lookups of sender authentication (DKIM and
DMARC) that the email channel will use. Details:
[security.md → Mail Sockets](security.md#mail-sockets--safesocketconnector),
[security.md → Mail Sender Authentication](security.md#mail-sender-authentication--dkim-and-dmarc).

| Property | Default | Description |
|---|---|---|
| `eddi.mail.smtp.host` | *(unset — disabled)* | The SMTP relay for system mail. Unset, no system mail is sent and one INFO line says so at startup. Operator configuration: a private address, a loopback sidecar, an IP literal and any port are allowed (`SafeSocketConnector`'s operator policy), the socket is still pinned to one resolution, and TLS still verifies the certificate |
| `eddi.mail.smtp.port` | `587` | The relay port; `465` implies implicit TLS, `25` is allowed |
| `eddi.mail.smtp.tls` | `starttls` (`implicit` on port 465) | `starttls` (required, never opportunistic), `implicit`, or `none` |
| `eddi.mail.smtp.allow-plaintext` | `false` | **Must be `true` for `tls=none`**, which sends system mail unencrypted; without it the mailer is disabled with an ERROR. Even then no credential is sent over plaintext: a credential with `tls=none` disables the mailer |
| `eddi.mail.smtp.from` | *(required when a host is set)* | The sender address of system mail. An invalid or missing value disables the mailer with an ERROR |
| `eddi.mail.smtp.from-name` | `EDDI` | The sender's display name |
| `eddi.mail.smtp.connection` | *(unset)* | Name of a connection holding the relay credential: `BASIC` (username + vault-backed password, `AUTH PLAIN/LOGIN`) or `OAUTH2_CLIENT_CREDENTIALS` (SASL `XOAUTH2`, e.g. Exchange Online, with the sender as the mailbox). Its `baseUrlAllowlist` must name the relay's mail origin (`smtp://smtp.office365.com`, `smtps://…`). Mutually exclusive with `username`/`password` |
| `eddi.mail.smtp.username` | *(unset)* | SMTP username, together with `eddi.mail.smtp.password`. Neither this nor a connection means no `AUTH` (an IP-allowlisted relay) |
| `eddi.mail.smtp.password` | *(unset)* | SMTP password. Supply it through the environment (`EDDI_MAIL_SMTP_PASSWORD`) or a secret-backed config source, not `application.properties` |
| `eddi.mail.notify.allowed-recipient-domains` | *(unset — the domain of `eddi.mail.smtp.from` and its subdomains)* | Comma-separated domains HITL email notifications may be sent to: an exact domain (`example.com`), `*.domain` for its subdomains (not the domain itself), or `*` for any. Recipients come from agent configs that anyone with EDIT on an agent can change, while the mail leaves through this relay from this sender, so the default keeps it inside the sender's organisation. A recipient outside is refused at agent save time when a mailer is configured (accepted with a WARN when not) and is never mailed (ledger row `SUPPRESSED`, `outcome=not_allowed`). An entry that is not a domain makes notifications go to nobody, with an ERROR at startup |
| `eddi.mail.notify.max-per-recipient-per-hour` | `30` | Flood brake: at most this many notifications per recipient in any hour, counted across the cluster from the email ledger (suppressed rows do not count). Over it, rows are `SUPPRESSED` (`outcome=rate_limited`) and each instance logs one WARN per recipient per hour, naming the recipient by hash. Concurrent pauses on several instances can overshoot by a few. `0` or less disables the limit |
| `eddi.email.ledger-retention` | `30d` | How long an email ledger row (one per notification: recipient, state, Message-ID — never a body) is kept. Zero or negative falls back to `30d` |
| `eddi.email.outbound.max-attempts` | `6` | Send attempts before a notification is marked `FAILED`. A 5xx refusal fails it at once |
| `eddi.email.outbound.retry-backoff` | `1m` | Delay before the first retry; it doubles per attempt, capped at one hour |
| `eddi.email.ledger.sweep-interval` | `30s` | How often each instance claims due retries, removes expired rows and refreshes `eddi_email_ledger_pending`. Claims are conditional updates, so instances never send the same row twice |
| `eddi.email.allowed-hosts` | *(empty — any public host)* | Comma-separated mail servers (IMAP/SMTP) that channel and knowledge-base authors may name: exact host names, or `*.domain` for its subdomains (not the domain itself). Applied on top of the always-on refusal of private, loopback, link-local and metadata addresses, which an entry here does not lift. An entry that is not a host name fails the first mail connection rather than being ignored |
| `eddi.email.dns.servers` | *(unset — the system's resolvers)* | Comma-separated DNS servers for DKIM key and DMARC record lookups: `host`, `host:port` or `[v6]:port`. Point an air-gapped deployment at its own resolver. DNSSEC is not required; a validating resolver adds it. A value that cannot be used makes every lookup fail temporarily, with one ERROR — no mail is then authenticated |
| `eddi.email.dns.timeout` | `5s` | Timeout of one DNS query (one retry). A timeout makes that signature or policy check temporary, never a pass |
| `eddi.email.dns.cache-max-entries` | `10000` | Size of the TXT answer cache shared by all mailboxes |
| `eddi.email.dns.cache-max-ttl` | `1h` | Longest time an answer is cached; shorter record TTLs (and, for a missing name, the zone's SOA minimum) are honoured. Temporary failures are never cached |
| `eddi.email.dkim.max-delivery-age` | `7d` | When a transport passes the time the mailbox server accepted a message (IMAP `INTERNALDATE`), DKIM `x=` (expiry) and `t=` (not in the future) are judged at that instant, so a short-lived signature that was valid on delivery still verifies when the mail is processed later. The instant is never taken as later than now nor earlier than now minus this, so a broken or forged value cannot revive an old signature; without one, signatures are judged at the current time |
| `eddi.email.dns.max-lookups-per-message` | `10` | Network lookups one message may cause (cache hits are free): one per DKIM signature, at most 5 of which are evaluated, plus up to two for DMARC. Past it, the remaining checks are temporary failures |

### OpenAI-compatible API

Full guide: [open-webui-integration.md](open-webui-integration.md).

| Property | Default | Description |
|---|---|---|
| `eddi.openai-compat.enabled` | `false` | Serve `/v1` |
| `eddi.openai-compat.api-key` | *(empty)* | Shared key clients present |
| `eddi.openai-compat.http-policy` | `permit` | `permit` accepts the shared key; `authenticated` has Quarkus OIDC validate per-user tokens instead, which, with `authorization.enabled=true`, must carry `eddi-admin`, `eddi-editor` or `eddi-user` (else 403) |
| `eddi.openai-compat.trust-user-headers` | `true` | Believe `X-OpenWebUI-User-Id` as the EDDI userId. Safe only because the caller proved possession of the shared key — **a leaked key therefore permits impersonating any user** |
| `eddi.openai-compat.allow-anonymous` | `false` | Serve requests carrying no user identity |
| `eddi.openai-compat.default-user` | `openai-anonymous` | userId used when anonymous is allowed |
| `eddi.openai-compat.environment` | `production` | Deployment environment agents are resolved from |
| `eddi.openai-compat.expose-stateless-variants` | `true` | Also list `…-stateless` model ids |
| `eddi.openai-compat.adopt-legacy-header-mappings` | `false` | Let an `openwebui:<id>` caller adopt a chat mapped under the raw header id from before namespacing. Enable only if `/v1` never ran with `http-policy=authenticated` — such a mapping may belong to an OIDC principal. Adopted conversations stay owned by the raw id: address both ids in GDPR export/erasure |
| `eddi.openai-compat.model-cache-seconds` | `30` | How long `/v1/models` is cached |
| `eddi.openai-compat.max-concurrent-requests` | `64` | Concurrency ceiling for the adapter |
| `eddi.openai-compat.request-timeout-seconds` | `120` | Per-request timeout. A stream that runs out of it ends with an OpenAI error event (`code: timeout`) and one `[DONE]` |
| `eddi.openai-compat.chat-key-fallback` | `history` | How a request that names no chat (`X-OpenWebUI-Chat-Id`, `X-EDDI-Chat-Id`, `metadata.chat_id` or `user`) is mapped: `history` keys it by a hash of its first system and first user message, so each client-side chat gets its own conversation; `shared` keeps one conversation per user and agent, as earlier releases did. See [open-webui-integration.md](open-webui-integration.md#session-mapping) |
| `eddi.openai-compat.max-conversation-steps` | `0` (off) | Steps after which an idle mapped conversation is ended and the chat continues in a fresh one, bounding the size of one conversation document. The agent no longer sees the earlier history; the ended conversation stays readable |

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
| `eddi.tenant.quota.max-monthly-cost-usd` | `-1` | `-1` = unlimited. Priced LLM and tool spend per UTC month; once reached, LLM tasks serve their fallback answer and tool calls are refused. Unpriced models meter $0 — see [Tenant Quotas](tenant-quotas.md#the-monthly-cost-budget) |
| `eddi.variables.cache-ttl-minutes` | `2` | [Global variable](global-variables.md) cache lifetime |
| `eddi.deployment.env` | `development` *(when unset)* | Value matched by the `deploymentContext` behavior-rule condition, letting one agent behave differently per environment |

---

## White-labeling

The brand is normally edited at runtime (`/administration/branding`, `eddi-admin`) and stored in the database. These properties **seed** every field the stored brand leaves unset; with `eddi.branding.locked=true` they are the whole brand and nothing can be edited at runtime. Every value passes the same validation as a REST write, and an invalid one fails startup naming the property. See [White-Labeling](white-labeling.md).

| Property | Default | Description |
|---|---|---|
| `eddi.branding.locked` | `false` | Properties are authoritative: the stored brand is ignored and every branding write answers 409. For installs that keep their brand in Git |
| `eddi.branding.product-name` | *(unset: `EDDI`)* | The product name the UIs show. At most 40 characters; no `<`, `>` or control characters |
| `eddi.branding.short-name` | *(unset: the product name)* | For tight spaces. At most 12 characters |
| `eddi.branding.tagline` | *(unset)* | One line for link previews and the welcome page. At most 120 characters |
| `eddi.branding.color.primary` | *(unset: EDDI amber)* | Primary colour: `#rgb`, `#rrggbb`, or `rgb()`/`hsl()`/`oklch()` with plain numbers |
| `eddi.branding.color.primary-foreground` | *(unset)* | Text on the primary colour. Below 4.5:1 contrast logs a WARN |
| `eddi.branding.color.dark-primary` | *(unset: the primary)* | Primary colour in dark mode |
| `eddi.branding.color.dark-primary-foreground` | *(unset: the foreground)* | Text on it in dark mode |
| `eddi.branding.color.chat-accent` | *(unset: the primary)* | The Chat UI's accent |
| `eddi.branding.powered-by` | *(unset: `true`)* | Show "Powered by EDDI" |
| `eddi.branding.show-update-check` | *(unset: `false`)* | Keep the Manager's update check UI while branded |
| `eddi.branding.assets-dir` | *(unset)* | Directory of brand images under fixed names (`logo.svg\|png\|webp`, `logo-dark.*`, `icon.*`, `favicon.ico\|png\|svg`, `og-image.*`), each checked like an upload. A missing directory or a refused file fails startup |

---

## Logging & documentation surfaces

| Property | Default | Description |
|---|---|---|
| `eddi.logs.buffer-size` | `10000` | In-memory ring buffer backing `/administration/logs` |
| `eddi.logs.db-enabled` | `true` | Also persist logs to the database |
| `eddi.logs.db-flush-interval-seconds` | `5` | Persistence batch cadence |
| `eddi.logs.db-persist-min-level` | `WARN` | Minimum level persisted. Lowering this to `DEBUG` in production will fill the database quickly |
| `eddi.logs.db-retention-days` | `-1` | Delete persisted log entries older than this many days. `-1` (or `0`) keeps them until deleted by hand. Persisted entries carry the user id (pseudonymized on GDPR erasure) and the message text, so set this wherever a data-retention policy applies. Applies to entries already stored even after `eddi.logs.db-enabled=false` |
| `eddi.logs.db-retention-interval` | `24h` | How often the retention sweep runs |
| `eddi.logs.db-retention-initial-delay` | `4m` | Delay before the first sweep after startup |
| `eddi.docs.enabled` | `true` | Serve EDDI's own docs at `/administration/docs`, as MCP resources (`eddi://docs/*`) and as the `list_docs`/`read_docs` MCP tools. One switch covers all three. The content is the public repository documentation, so this is an exposure policy, not a secrecy control |
| `eddi.docs.path` | `docs` | Directory the above is served from |

---

## Metrics & tracing

`eddi.metrics.http-policy` (who may read `/q/metrics`) is under [Security & authentication](#security--authentication). The rest of the telemetry configuration is Quarkus' own; these are the settings EDDI ships or that an operator usually changes. The alert rules and dashboards that read the metrics are described in [monitoring-guide.md](monitoring/monitoring-guide.md).

| Property | Default | Description |
|---|---|---|
| `quarkus.otel.sdk.disabled` | `true` | Tracing is off until a collector exists. Set `false` (env `QUARKUS_OTEL_SDK_DISABLED=false`) together with the endpoint below; `docker-compose.monitoring.yml` and the Helm chart's `tracing.otlpEndpoint` value (under `eddi:`) do both |
| `quarkus.otel.exporter.otlp.endpoint` | `http://localhost:4317` | OTLP gRPC endpoint spans are exported to |
| `eddi.tracing.propagate-outbound` | `true` | Adds the W3C `traceparent`/`tracestate` of the current span (a tool call's `execute_tool` span) to EDDI's outbound calls — httpcalls, everything on the `OutboundHttp` facade (built-in tools, A2A, the RAG crawler, OAuth, Slack, …) and MCP (header and `params._meta`) — so downstream services join the trace. Never sends `baggage`, never overwrites an author-configured header, drops the headers on cross-origin redirects. Inert while `quarkus.otel.sdk.disabled=true`. Set `false` to keep EDDI's own spans without revealing trace ids. See [trace propagation](monitoring/monitoring-guide.md#trace-propagation-to-the-services-eddi-calls) |
| `quarkus.micrometer.binder.http-server.max-uri-tags` | see `application.properties` | Distinct `uri` tag values `http_server_requests_seconds_*` may carry; requests past the cap are not measured. `http.server.requests` publishes no histogram buckets on purpose — buckets would multiply every one of these series — so the dashboards chart HTTP latency as mean and peak |
| `quarkus.datasource.metrics.enabled` | `true` | Build-time. Publishes the Agroal pool meters (`agroal_*`) for the PostgreSQL datastore; on the MongoDB datastore the datasource is inactive and nothing is registered. MongoDB pool meters (`mongodb_driver_pool_*`) come from a listener on EDDI's own client and need no setting |
| `quarkus.thread-pool.max-threads` | `max(8 × CPUs, 200)` | Size of the worker pool conversation turns run on, reported as `eddi_runtime_executor_max_threads`. Raise it when `eddi_runtime_executor_queued` stays above zero (alert `EddiExecutorSaturated`) |

---

## Migration

These run once against an existing database and then stay off.

| Property | Default | Description |
|---|---|---|
| `eddi.migration.v6-rename.retention-confirmed` | `false` | Confirms that 6.x's ended-conversation retention (`eddi.conversations.deleteEndedConversationsOnceOlderThanDays`) may delete on a database that came from EDDI 5. EDDI 5 kept ended conversations for ever, so until this is `true` the sweep deletes nothing on such a database and logs daily how much it would delete. That applies once the rename migration has run (recorded in `migrationlog`, so on every later boot and replica), while it is pending, and while the v5 collections still hold documents. Set the retention to `-1` instead to keep them. A PostgreSQL deployment is never held: EDDI 5 ran on MongoDB only |
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
