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
> `eddi.vault.master-key` empty, which means the vault is inactive and
> `scope: "secret"` properties fall back to plaintext. Nothing in the startup log
> mentions the variable you set.
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

## Data store & messaging

| Property | Default | Description |
|---|---|---|
| `eddi.datastore.type` | `mongodb` | `mongodb` or `postgres`. Selects the whole persistence layer — see [Architecture → DB-agnostic design](architecture.md) |
| `eddi.messaging.type` | `in-memory` | `in-memory` or `nats`. `in-memory` confines the conversation coordinator to a single instance; `nats` distributes it |

### NATS JetStream (only when `eddi.messaging.type=nats`)

| Property | Default | Description |
|---|---|---|
| `eddi.nats.url` | `nats://localhost:4222` | Server URL |
| `eddi.nats.stream-name` | `EDDI_CONVERSATIONS` | JetStream stream carrying conversation work |
| `eddi.nats.dead-letter-stream-name` | `EDDI_DEAD_LETTERS` | Stream that receives messages past `max-retries` |
| `eddi.nats.max-retries` | `3` | Redelivery attempts before dead-lettering |
| `eddi.nats.ack-wait-seconds` | `60` | How long JetStream waits for an ack before redelivering. Must exceed your slowest conversation turn, or slow turns are processed twice |

---

## Conversation lifecycle & retention

| Property | Default | Description |
|---|---|---|
| `eddi.conversations.maximumLifeTimeOfIdleConversationsInDays` | `90` | Idle conversations are closed after this many days |
| `eddi.conversations.deleteEndedConversationsOnceOlderThanDays` | `365` | Ended conversations are permanently deleted after this many days |
| `eddi.usermemories.deleteOlderThanDays` | `-1` | Persistent user memories older than this are deleted. **`-1` disables the sweep** — memories are kept forever until you set a positive number. Relevant to [GDPR](gdpr-compliance.md) and [HIPAA](hipaa-compliance.md) |
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
| `eddi.schedule.fire-log-retention` | `90d` | Fire logs older than this are deleted by a periodic sweep. `0` keeps everything — a 60-second heartbeat alone writes ~525,600 rows a year |
| `eddi.schedule.fire-log-prune-interval` | `1h` | How often that sweep runs. The `DELETE` is by timestamp and therefore idempotent, so it needs no cluster claim |

---

## Security & authentication

> Authentication itself is Quarkus OIDC, not an `eddi.*` property:
> `quarkus.oidc.tenant-enabled` (default `false`) is the master switch, and
> `authorization.enabled` tracks it so `@RolesAllowed` is enforced exactly when
> OIDC is on. See [security.md](security.md).

| Property | Default | Description |
|---|---|---|
| `eddi.security.allow-unauthenticated` | `false` | Permits running with OIDC disabled outside dev. `AuthStartupGuard` refuses a production boot without it |
| `eddi.security.ssrf-protection.enabled` | `false` | **Opt-in.** Validates the fully resolved target of httpCalls, MCP and A2A calls and stops following redirects. Off by default because configured targets legitimately reach internal hosts — **turn it on if any outbound URL is influenced by conversation input.** See [security.md → SSRF Protection](security.md#ssrf-protection--urlvalidationutils) |
| `eddi.mcp.allow-unauthenticated` | `false` | Exposes the MCP server without auth. Needs its own opt-in on top of `eddi.security.allow-unauthenticated` — inheriting one flag must not be enough to open agent CRUD |
| `eddi.secretstore.allow-unauthenticated` | `false` | Same, for the secrets vault REST surface |
| `eddi.caller-identity.enabled` | `true` | Enables `${caller:token}` / `${caller:userId}` in httpCall headers. See [httpcalls.md](httpcalls.md) |
| `eddi.keycloak.public.url` | *(empty)* | Browser-facing Keycloak URL when it differs from the in-cluster one |

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
| `eddi.workspaces.legacy-visibility` | `shared` | Who may see resources with no recorded owner: `shared` (everyone, so an upgrade hides nothing) or `admin-only` (only `eddi-admin`, so an operator migrates deliberately and then closes the door). Any other value fails startup rather than picking a policy by guessing |
| `eddi.workspaces.default-space` | *(empty)* | Where new resources are filed. Empty means the creator's personal space; a Keycloak group name gives a team-first deployment, where colleagues see each other's work by default and personal spaces are reached by explicitly moving a resource |

### Secrets vault

Full guide: [secrets-vault.md](secrets-vault.md).

| Property | Default | Description |
|---|---|---|
| `eddi.vault.master-key` | *(empty)* | KEK source. **Empty means the vault is inactive** and `scope: "secret"` properties fall back to plaintext with an ERROR log |
| `eddi.vault.grant-enforcement` | `enforce` | `off`, `warn` or `enforce`. An unrecognised value fails startup rather than silently disabling the check |
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
| `eddi.tools.websearch.provider` | `duckduckgo` | `duckduckgo` (no key) or `google` |
| `eddi.tools.websearch.google.api-key` | *(empty)* | Required for the `google` provider |
| `eddi.tools.websearch.google.cx` | *(empty)* | Google Programmable Search engine ID |
| `eddi.tools.weather.openweathermap.api-key` | *(empty)* | Required by the weather tool |
| `eddi.httpcalls.default-timeout-millis` | `30000` | Per-call timeout when the httpCall does not set one. Without it a call can occupy the conversation thread indefinitely |
| `eddi.httpcalls.default-max-response-size-bytes` | `2000000` | Response-body ceiling. Deliberately above the memory cap, so an over-long body is truncated into memory rather than failing the turn |
| `eddi.mcpcalls.default-rate-limit` | `100` | Default per-minute limit for MCP tool calls |
| `eddi.ollama.default-base-url` | `http://localhost:11434` | Used when an Ollama LLM config omits `baseUrl` |

---

## Attachments

Full guide: [attachments-guide.md](attachments-guide.md).

| Property | Default | Description |
|---|---|---|
| `eddi.attachments.max-size-bytes` | `20971520` (20 MB) | Largest single upload |
| `eddi.attachments.max-per-turn` | `5` | Attachments per turn — **per member turn** in a group conversation |
| `eddi.attachments.max-per-conversation` | `50` | Attachments per conversation |
| `eddi.attachments.max-total-bytes-per-conversation` | `104857600` (100 MB) | Aggregate bytes per conversation |
| `eddi.attachments.max-forward-bytes` | `10485760` (10 MB) | Per-file ceiling on what is forwarded to the LLM, across every source |
| `eddi.attachments.max-forward-aggregate-bytes` | `20971520` (20 MB) | Aggregate ceiling for one message |
| `eddi.attachments.extraction.max-chars` | `50000` | Cap on text extracted from a document |

> The upload cap and the forward cap are different numbers on purpose: a 20 MB
> PDF may be stored and read on demand via the `readAttachment` tool without
> being inlined into every prompt.

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
| `eddi.a2a.capabilities.public` | `false` | Serve `/.well-known/capabilities` unauthenticated |
| `eddi.a2a.tool-description.max-chars` | `1024` | Truncation cap on peer tool descriptions |
| `eddi.a2a.signing.nonce.max-age-ms` | `300000` (5 min) | Replay window for signed requests |
| `eddi.a2a.signing.nonce.clock-skew-ms` | `30000` | Tolerated clock difference between peers |

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
| `eddi.openai-compat.model-cache-seconds` | `30` | How long `/v1/models` is cached |
| `eddi.openai-compat.max-concurrent-requests` | `64` | Concurrency ceiling for the adapter |
| `eddi.openai-compat.request-timeout-seconds` | `120` | Per-request timeout |

### Connections

Full guide: [connections.md](connections.md).

| Property | Default | Description |
|---|---|---|
| `eddi.connections.enabled` | `false` | Master switch for the connection credential model |
| `eddi.connections.public-base-url` | *(empty)* | Externally reachable base URL for OAuth redirect URIs |
| `eddi.connections.credential-endpoint-allowlist` | *(empty)* | Hosts permitted to receive resolved credentials |
| `eddi.connections.state-sweep-interval` | `1h` | How often expired OAuth state entries are cleared |

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
| `eddi.migration.v6-rename.enabled` | `false` | Rewrite v5 resource URIs to v6 spellings |
| `eddi.migration.v6-qute.enabled` | `false` | Convert Thymeleaf templates to Qute |
| `eddi.migration.backupBeforeWrite` | `true` | Snapshot documents into `.history` collections before rewriting. **Leave this on** |
| `eddi.migration.skipConversationMemories` | `false` | Skip conversation memories, which are the bulk of the data and rarely need rewriting |

---

## See also

- [Metrics & Monitoring](metrics.md) — what to watch once these are set
- [Security](security.md) — the reasoning behind the security defaults
- [Kubernetes](kubernetes.md) — how these map onto Helm values and ConfigMaps
- [`.env.example`](../.env.example) — the Docker Compose subset, ready to copy
- `src/main/resources/application.properties` — the shipped defaults, with inline commentary
