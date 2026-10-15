# Troubleshooting

This page collects common failures, the exact text EDDI logs or returns for each, and the fix. Every message quoted here comes from the EDDI source, so you can search your logs for it word for word. Where a message is longer than shown, the quoted part is its stable beginning.

The detailed pages are linked from each section. Start with [Collecting diagnostics](#collecting-diagnostics) if you do not yet know which area the problem is in.

---

## Collecting diagnostics

| What | How | Notes |
| --- | --- | --- |
| Readiness | `GET /q/health/ready` | Readable without authentication. Lists each readiness check by name: `Agents are ready health check` (with `agentsInErrorCount`), `Graceful shutdown readiness check`, `PostgreSQL connection` on the postgres profile, and the MongoDB extension's check on the default profile. |
| Liveness | `GET /q/health/live` | Readable without authentication. |
| Metrics | `GET /q/metrics` | Requires authentication by default (`eddi.metrics.http-policy=authenticated`). With OIDC on, a scraper without a token gets 401. See [Monitoring](monitoring/monitoring-guide.md) and [Metrics](metrics.md). |
| Vault status | `GET /secretstore/secrets/health` | `200` with `"status": "UP"` when the vault is active, `503` with `"status": "DOWN"` when it is not. |
| Deployment status | `GET /administration/{environment}/deploymentstatus/{agentId}?version={n}` | Returns `{"status": "READY"}`, `IN_PROGRESS`, `NOT_FOUND` or `ERROR`. `GET /administration/{environment}/deploymentstatus` lists every deployed agent. `environment` is `production` or `test`. |
| Recent logs | `GET /administration/logs?level=WARN&conversationId=...` | Admin only. See [Log Administration](log-administration.md). |

**Log format.** Console lines carry the agent and conversation id in brackets: `[%X{agentId},%X{conversationId}]`. Search by conversation id to see one conversation's lines; the user id is deliberately not printed.

**Log levels.** Raise one category at runtime with a Quarkus category property, for example `quarkus.log.category."ai.labs.eddi.modules.llm".level=DEBUG`, or as an environment variable `QUARKUS_LOG_CATEGORY__AI_LABS_EDDI_MODULES_LLM__LEVEL=DEBUG`. EDDI sets no root level, so the root logger stays at `INFO`; [Log Administration](log-administration.md) explains why the in-memory log buffer shows `DEBUG` records only after the category level is lowered.

---

## 1. Startup guards

In production mode (`java -jar`, the Docker image), several guards refuse to start an unsafe configuration. In dev mode (`quarkus:dev`) and tests they only log. See [Security](security.md) and the [Configuration Reference](configuration-reference.md).

### Boot fails: OIDC must be enabled in production

- **Error:** `IllegalStateException: OIDC must be enabled in production. Set QUARKUS_OIDC_TENANT_ENABLED=true and configure your Keycloak realm, or explicitly opt out with EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true.`
- **Cause:** `quarkus.oidc.tenant-enabled=false` (the shipped default) in production mode, without the opt-out.
- **Fix:** Turn on OIDC (`QUARKUS_OIDC_TENANT_ENABLED=true` plus your realm settings). For a local or demo setup only, set `EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true`. EDDI then logs `[SECURITY]` ... `OIDC is DISABLED in production mode!` at startup and an hourly `REMINDER: OIDC is DISABLED in production.` warning.

### Boot fails: high-value surfaces open without authentication

- **Error:** `authorization.enabled=false, so @RolesAllowed is not enforced and the following high-value surfaces are open to anyone who can reach the port:` followed by `/mcp` and/or `/secretstore`, and a `Fix by one of:` list.
- **Cause:** Authentication is off, and `EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true` does **not** cover the MCP endpoint or the secrets vault. Each needs its own opt-in.
- **Fix:** Enable OIDC (recommended). Otherwise set `EDDI_MCP_ALLOW_UNAUTHENTICATED=true` and/or `EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED=true` to knowingly expose them. The shipped `docker-compose.yml` sets all three for local use. When you opt out, each startup logs `... is reachable WITHOUT authentication (eddi.mcp.allow-unauthenticated=true)` (or the secretstore equivalent).

### Boot fails: OpenAI-compatible API without authentication

- **Error:** `The OpenAI-compatible API (/v1) is enabled with no authentication, but this deployment has authorization enabled.`
- **Cause:** The `/v1` surface is enabled with OIDC on, but neither an api key nor OIDC validation is configured for it.
- **Fix:** Set one of `eddi.openai-compat.api-key`, `eddi.openai-compat.http-policy=authenticated`, or `eddi.openai-compat.enabled=false`, as the message lists. See [Open WebUI integration](open-webui-integration.md).

### Boot fails: weak vault master key

- **Error:** `[VAULT] Refusing to start: <reason>. Set EDDI_VAULT_MASTER_KEY to a strong, unique passphrase (at least 16 characters)`. The reason is one of: `the vault master key is a well-known demo/placeholder value...`, `the vault master key is only N characters; at least 16 are required`, or `the vault master key uses only N distinct character(s)...`.
- **Cause:** Production mode refuses a weak or publicly documented master key. Dev mode only warns (`... would FAIL startup in production`).
- **Fix:** Use a strong key. If an existing deployment must boot on the weak key to migrate off it, set `EDDI_VAULT_ALLOW_WEAK_MASTER_KEY=true`, rotate with `POST /secretstore/secrets/admin/rotate-kek`, then remove the flag. See [Secrets Vault](secrets-vault.md).

### Startup banner: secrets vault disabled

- **Log:** a box reading `WARNING: SECRETS VAULT DISABLED -- Master key not configured`.
- **Cause:** `EDDI_VAULT_MASTER_KEY` is empty, which is the shipped default. EDDI still starts.
- **Effect:** `${vault:...}` references are not resolved, `/secretstore` answers 503, and audit ledger entries are not HMAC-signed. If `eddi.compliance.audit-signing-required=true`, startup fails instead with `COMPLIANCE: eddi.compliance.audit-signing-required=true but no vault master key is configured.`
- **Fix:** Set `EDDI_VAULT_MASTER_KEY`. See [Secrets Vault](secrets-vault.md).

### Role claim warning, then every request answers 403

- **Log (ERROR at startup):** `[SECURITY] quarkus.oidc.roles.role-claim-path is NOT set.` or `[SECURITY] quarkus.oidc.roles.role-claim-path is '...', which reads roles from 'groups', the claim eddi.workspaces.groups-claim names.`
- **Cause:** Roles are read from the same token claim as workspace groups, so a user who belongs to a Keycloak group has their roles replaced by group paths. Every `@RolesAllowed` endpoint then answers 403 with an empty body.
- **Fix:** Keep the shipped `quarkus.oidc.roles.role-claim-path=realm_access/roles` (Keycloak), or set the equivalent path for your identity provider. Do not point it at the groups claim.

### EDDI not reachable from another machine (Docker Compose)

- **Cause:** The shipped `docker-compose.yml` publishes EDDI's ports on `127.0.0.1` only, because it runs unauthenticated by default.
- **Fix:** Turn authentication on first, then set `EDDI_BIND=0.0.0.0` in `.env`. See [Docker](docker.md).

### `Unable to establish loopback connection` (local development, Windows)

This message comes from the JDK, not from EDDI. The NIO selector creates a Unix-domain socket under the temp directory, and it fails in two known situations:

- During `./mvnw package`, from the Quarkus build analytics ping. Fix: add `-Dquarkus.analytics.disabled=true`.
- When `quarkus:dev` starts with a very long `%TEMP%` path (beyond the Unix-domain socket path limit). Fix: point `TEMP`/`TMP` at a short directory and pass `-Djvm.args="-Djdk.net.unixdomain.tmpdir=<short dir> -Djava.io.tmpdir=<short dir>"`, because dev mode forks a JVM.

---

## 2. Datastore

See [Architecture](architecture.md) and the [Configuration Reference](configuration-reference.md).

### MongoDB: cannot connect

- **Symptom:** Startup hangs or fails on the first database access, and the MongoDB readiness check in `/q/health/ready` is DOWN.
- **Cause:** The connection string is read from the `mongodb.connectionString` property (environment variable `MONGODB_CONNECTIONSTRING`). Outside dev mode its default is `mongodb://mongodb:27017/eddi?...`, which only resolves inside Docker Compose where the database service is called `mongodb`. Setting `quarkus.mongodb.connection-string` has no effect: EDDI does not read it.
- **Fix:** Set `MONGODB_CONNECTIONSTRING` to your server. With a root user created by the Mongo image (as in `docker-compose.yml`), add credentials and `authSource=admin`, for example `mongodb://eddi:<password>@mongodb:27017/eddi?authSource=admin`. The database name comes from `mongodb.database` (default `eddi`). In dev mode the default is `mongodb://localhost:27017/eddi`.

### PostgreSQL profile does not take effect

- **Cause:** The datastore is chosen by `eddi.datastore.type` (default `mongodb`). The `postgres` profile sets it to `postgres` and activates the datasource (`quarkus.datasource.active` is `false` otherwise).
- **Fix:** Start with `QUARKUS_PROFILE=postgres` (or `-Dquarkus.profile=postgres`) and set `QUARKUS_DATASOURCE_JDBC_URL`, `QUARKUS_DATASOURCE_USERNAME` and `QUARKUS_DATASOURCE_PASSWORD` explicitly; outside tests nothing provides them. `docker-compose.postgres-only.yml` is a working example.
- **Check:** `/q/health/ready` shows a `PostgreSQL connection` check. When it is DOWN the server log has `PostgreSQL readiness check failed` with the cause (the JDBC detail is not exposed over HTTP).

### PostgreSQL: listing search is slow

- **Log:** `Substring search on ... stays unindexed: could not create the pg_trgm extension or its indexes (...)`
- **Cause:** The database role cannot run `CREATE EXTENSION pg_trgm`.
- **Fix:** Grant the role `CREATE` on the database, or set `eddi.datastore.postgres.substring-search-index=false` to stop trying. Search still works without the indexes, only slower.

---

## 3. LLM providers

See [LLM Integration](langchain.md) and [Model Cascade](model-cascade.md).

### A turn fails and the conversation goes to ERROR

- **Log:** `Error while processing user input (conversationId=..., conversationState=ERROR)` with the provider exception attached.
- **Cause:** Anything the LLM task could not recover from: a provider rejecting the key, an unknown model name, a non-retriable 4xx, or retries exhausted.
- **Fix:** Read the attached exception; it carries the provider's own error. Fix the task config in the `langchain` resource and send the next message; the conversation is not locked by the ERROR state.

### Missing API key for an OpenAI-compatible provider

- **Error:** `<Provider> requires an apiKey, e.g. ${vault:<provider>-key}`
- **Cause:** A named OpenAI-compatible provider type was configured without `apiKey`.
- **Fix:** Store the key in the vault and reference it as the message suggests. See [Secrets Vault](secrets-vault.md).

### Unknown provider type

- **Error:** `Type "<type>" is not supported`
- **Fix:** Use one of the provider types listed in [LLM Integration](langchain.md).

### API key reference not resolved

- **Error:** `Parameter '<name>' of <what> references ${vault:...}, which could not be resolved — the secret does not exist, the vault failed, or the vault is not configured (EDDI_VAULT_MASTER_KEY). Refusing to send the unresolved reference to the provider.`
- **Fix:** See [Secrets vault](#6-secrets-vault) below.

### Rate limits and transient errors

- **Behaviour:** With a `retry` block on the task, EDDI retries only errors it classifies as retriable: socket and connect timeouts, unknown host, and HTTP 429, 502, 503 and 504. Authentication failures and malformed requests are never retried. Retries are clamped to 10 attempts and 60 seconds of total backoff.
- **Fix for sustained 429s:** lower concurrency, add a `retry` block, or add a cascade step on another provider.

### Unusable `timeout` parameter

- **Log:** `Ignoring unusable 'timeout' parameter '30s' for model type '...' — expected a positive number of milliseconds; the model is built without a provider timeout`
- **Fix:** Write the timeout in milliseconds as a number string, for example `"timeout": "30000"`.

### Turn interrupted after 60 seconds

- **Log:** `Execution of Workflows interrupted or timed out.`
- **Cause:** The whole turn exceeded `systemRuntime.agentTimeoutInSeconds` (60 in production, 600 in dev mode). The conversation is set to `EXECUTION_INTERRUPTED`; the next message recovers it automatically (`Auto-recovering conversation ... from EXECUTION_INTERRUPTED`).
- **Fix:** Raise `systemRuntime.agentTimeoutInSeconds` for slow models, or use a faster model or a lower provider `timeout`.

### Tool call timed out or rate-limited

- **What the model receives:** `Error: Execution timed out after <n>ms for tool: <tool>` or `Error: Rate limit exceeded for tool: <tool>`. The turn continues; the model decides what to do with the error.
- **Log:** `Tool '<tool>' exceeded its <n>ms execution timeout and was abandoned; the model was told so.`
- **Fix:** Raise `defaultToolTimeoutMs` (default 120000) or set a per-tool entry in `toolTimeoutsMs` on the LLM task. See the Agent Mode parameters in [LLM Integration](langchain.md).

### Model cascade

- **Log per failed step:** `Cascade step <i> failed (retryable_error|error): ..., escalating` or `Cascade step <i> timed out after <n>ms, escalating`.
- **When every step fails:** `Model cascade failed: all steps exhausted. Errors: Step 0 (<model>): ...; Step 1 ...`. If any earlier step produced an answer, EDDI returns the best one instead (`All cascade steps exhausted (last failed), returning best response`).
- **Config error:** `Model cascade enabled but no steps configured`.
- **Fix:** Read the per-step errors in the exhaustion message. See [Model Cascade](model-cascade.md).

---

## 4. Human-in-the-loop (HITL)

See [Human-in-the-Loop](hitl.md).

### New input is rejected with 409

- **Response:** `409` with `Conversation is awaiting human approval — a reviewer must resolve it via POST /agents/<conversationId>/resume (or cancel) before new input is accepted`
- **Cause:** The conversation is in `AWAITING_HUMAN`; a behavior rule emitted `PAUSE_CONVERSATION` or a tool call needs approval.
- **Fix:** Inspect `GET /agents/{conversationId}/approval-status`, then decide with `POST /agents/{conversationId}/resume` and a body such as `{"verdict": "APPROVED"}`, or `POST /agents/{conversationId}/cancel`. `GET /agents/pending-approvals` lists every paused conversation the caller may see.

### Resume answers 400 or 409

| Response | Text | Meaning |
| --- | --- | --- |
| `400` | `Request body must include a 'verdict' field (APPROVED or REJECTED)` | Missing or unknown verdict. |
| `409` | `Conversation is not in a resumable state (current state: <STATE>) — it may have been resumed, cancelled, or timed out already, or its agent is not deployed.` | Someone else decided first, the timeout policy fired, or the agent version was undeployed. |
| `409` | `The pending approval changed since this decision was made (pauseId no longer current) — re-read approval-status and decide again.` | The decision carried a stale `pauseId`. |

### Approval timeout never fires

- **Cause:** Timeout policies (`AUTO_APPROVE`, `AUTO_REJECT`, `ABORT`) are executed by the schedule poller. With `eddi.schedule.enabled=false` the poller logs `Schedule poller DISABLED (eddi.schedule.enabled=false)` and nothing fires. The default policy is `WAIT_INDEFINITELY`, which never times out.
- **Fix:** Keep scheduling enabled, and set both `approvalTimeout` (ISO-8601, for example `PT15M`) and a finite `timeoutPolicy` in `hitlConfig`.

---

## 5. Group conversations

See [Group Conversations](group-conversations.md).

### A member is skipped with "Agent not deployed"

- **Transcript entry:** `SKIPPED` with `Agent not deployed`. With `onMemberUnavailable: "FAIL"` the discussion aborts instead: `Agent <agentId> is not deployed and onMemberUnavailable=FAIL`.
- **Cause:** Group members are looked up in the `production` environment. A member deployed only to `test`, or not deployed at all, is unavailable.
- **Fix:** Deploy each member agent to `production` and check it with the deployment status endpoint.

### A member fails during its turn

- **Transcript entry:** `SKIPPED` with the failure. With `onAgentFailure: "ABORT"`: `<prefix> for agent <agentId> and onAgentFailure=ABORT: <cause>`.
- **Fix:** Start a 1:1 conversation with the failing member to see its own error, then fix that agent.

### Nesting depth exceeded

- **Response:** `400` with `Maximum group nesting depth exceeded.` A nested group member that hits the limit is skipped with `Sub-group depth exceeded: Maximum group discussion depth (3) exceeded`.
- **Fix:** Flatten the group hierarchy, or raise `eddi.groups.max-depth` (default 3).

### Group not found

- **Response:** `404` with `Group not found.` This is also the answer for a malformed group id.

---

## 6. Secrets vault

See [Secrets Vault](secrets-vault.md).

### `/secretstore` answers 503

- **Body:** `{"error": "Secrets Vault is not configured", "reason": "The EDDI_VAULT_MASTER_KEY environment variable is not set.", ...}`
- **Fix:** Set `EDDI_VAULT_MASTER_KEY` and restart.

### `/secretstore` answers 401 or 403

- **Cause:** All of `/secretstore/secrets` requires the `eddi-admin` role. EDDI has no role hierarchy, so `eddi-editor` or `eddi-viewer` alone is refused.
- **Fix:** Call it with an `eddi-admin` token. See [Authentication and roles](#11-authentication-and-roles).

### How to store a secret

Secrets are written with `PUT /secretstore/secrets/{tenantId}/{keyName}` and a body `{"value": "...", "description": "...", "allowedAgents": ["*"]}`. Single-tenant deployments use the tenant `default`, which the short reference form `${vault:keyName}` resolves to. An empty value is rejected with `Secret value must not be empty`.

### A `${vault:...}` reference is not resolved

- **Log:** `Vault reference not found: ${vault:...} — passing through unchanged` (the secret does not exist), or `Failed to resolve vault reference: ${vault:...} — ...` (decryption or persistence failure).
- **Effect:** LLM tasks and API calls refuse to send the literal reference; for HTTP calls the error ends with `Refusing to send the unresolved reference.`
- **Fix:** Check the key name and tenant (`${vault:keyName}` means tenant `default`; `${vault:tenantId/keyName}` names one explicitly). The older `${eddivault:...}` prefix is still accepted.

### Agent deployment refused because of a secret grant

- **Log:** `Agent '<id>' v<n> references vault secret(s) it is not granted: [...]. ... Deployment BLOCKED (eddi.vault.grant-enforcement=enforce).` The deployment status becomes `ERROR`.
- **Fix:** Add the agent to the secret's `allowedAgents` with `PUT /secretstore/secrets/{tenantId}/{keyName}/grant`, or remove the reference. `eddi.vault.grant-enforcement=warn` logs instead of blocking.

### Key rotation

`POST /secretstore/secrets/{tenantId}/rotate-dek` rotates one tenant's data key; `POST /secretstore/secrets/admin/rotate-kek` rotates the master key. A rotation that fails answers 500 with `DEK rotation failed: ...` or `KEK rotation failed: ...`. A deployment that started on the legacy fixed salt logs `[VAULT] Using legacy fixed salt for KEK derivation.` until a KEK rotation migrates it.

---

## 7. Connections

See [Connections](connections.md).

Saving a connection to `/connectionstore/connections` validates it and answers `400` with the reason. Common ones:

| Text (beginning) | Fix |
| --- | --- |
| `baseUrlAllowlist is required: a connection must name the origins its credential may be sent to` | List the bare origins (`https://api.example.com`) the credential may go to. |
| `staticAuth.valueTemplate contains no ${vault:…} reference, so it is a plaintext credential.` | Store the credential with `PUT /secretstore/secrets/{tenantId}/{keyName}` and reference it, for example `"Bearer ${vault:jira-token}"`. |
| `STATIC requires staticAuth.valueTemplate` | Add the template. |
| `authType is required (STATIC, BASIC, OAUTH2_CLIENT_CREDENTIALS or OAUTH2_AUTHORIZATION_CODE).` | Set `authType`. |
| `An OAuth connection requires an active SecretsVault (set EDDI_VAULT_MASTER_KEY).` | OAuth grants are always encrypted; set the master key. |
| `timeoutMs must be between ...` | Leave `timeoutMs` unset for the default, or pick a value in range. |

At startup EDDI also reports stored connections that will fail at request time, each line starting with `[CONNECTIONS]`. Two common ones: `A PER_USER connection is stored, but authorization.enabled=false.` and `A CALLER_SUPPLIED connection is stored, but authorization.enabled=false.`; both are refused at request time until OIDC is enabled.

---

## 8. Scheduling

See [Scheduling](scheduling.md).

### No schedule fires

- **Log at startup:** `Schedule poller DISABLED (eddi.schedule.enabled=false)`. When enabled you see `Schedule poller initialized (instance=..., leaseTimeout=..., maxRetries=...)`.
- **Fix:** Set `eddi.schedule.enabled=true`, the default. The poller checks every `eddi.schedule.poll-interval` (15s). A schedule can also be disabled on its own; re-enable it with `POST /schedulestore/schedules/{scheduleId}/enable`.

### Schedule rejected on save

- **Error:** `Cron interval (<n>s) is below minimum allowed (60s). Use a less frequent schedule or contact admin to adjust eddi.schedule.min-interval-seconds`

### A fire was skipped

- **Log:** `[SCHEDULE] Fire of schedule '<name>' (id=...) was skipped: conversation ... is awaiting a human approval, so the scheduled input was never processed` (or `... was in state <STATE>`), followed by `... re-armed for <time> without counting a failure`.
- **Cause:** A persistent-conversation schedule fired while its conversation was busy or paused for HITL. It is not counted as a failure.

### Failures, retries and dead letters

- **Log:** `[SCHEDULE] Schedule '<name>' (id=...) failed (attempt n/5), retry at <time>`, and after the last attempt `[SCHEDULE] Schedule '<name>' (id=...) dead-lettered after 5 retries`.
- **Inspect:** `GET /schedulestore/schedules/{scheduleId}/fires` for one schedule's fire log, `GET /schedulestore/schedules/admin/failed` for every failed and dead-lettered fire.
- **Recover:** `POST /schedulestore/schedules/{scheduleId}/retry` requeues a dead-lettered schedule; `POST /schedulestore/schedules/{scheduleId}/dismiss` drops it. Retries back off exponentially (`eddi.schedule.backoff-base-seconds`, `eddi.schedule.backoff-multiplier`); `eddi.schedule.max-retries` sets the limit.
- **Cluster:** A claimed schedule is leased for `eddi.schedule.lease-timeout` (5m); if the instance dies, another instance reclaims it after the lease expires. A fire that outlives its lease logs `[SCHEDULE] Dispatched fire task exceeded the batch lease deadline` and is cancelled.

---

## 9. GDPR

See [GDPR Compliance](gdpr-compliance.md) and [Audit Ledger](audit-ledger.md).

### Erasure answers 207

- **Response:** `DELETE /admin/gdpr/{userId}` answers `207 Multi-Status` when one or more steps failed; `failedSteps` in the body names them. The server log has `GDPR erasure incomplete — failed steps: [...]`.
- **Fix:** Fix the failing store and run the erasure again. Do not report the erasure to the data subject as complete until it answers `200`.
- **What is kept:** Audit ledger and database log entries are not deleted; their user id is pseudonymized.

### Export always answers 207

- **Response:** `GET /admin/gdpr/{userId}/export` answers `207` with `"complete": false` and an `omittedCategories` list. This is expected today: group transcripts, shared artifacts, schedules and HITL journal entries are not yet exported, although erasure deletes them.

### Conversations refused with 403 for one user

- **Response:** `403` with `Processing is restricted for this user (GDPR Art. 18)`.
- **Cause:** Processing was restricted with `POST /admin/gdpr/{userId}/restrict`.
- **Fix:** Lift it with `DELETE /admin/gdpr/{userId}/restrict`; check with `GET /admin/gdpr/{userId}/restrict`.

All `/admin/gdpr` endpoints require `eddi-admin`.

---

## 10. Deployment

See [Deployment management](deployment-management-of-agents.md).

### Starting a conversation answers 404

- **Response:** `404` with `Agent is not deployed or not ready`, from `POST /agents/{agentId}/start` or when sending input.
- **Log:** `Agent not ready: No version of agent (agentId=...) ready for interaction (environment=production)!`
- **Cause:** No version of the agent is `READY` in the requested environment. Conversations start in `production` unless `?environment=test` is passed, so an agent deployed only to `test` is not found.
- **Fix:** Check `GET /administration/production/deploymentstatus/{agentId}?version={n}`. A `NOT_FOUND` status means the version is not deployed: deploy it with `POST /administration/production/deploy/{agentId}?version={n}` (add `&waitForCompletion=true` to wait for the result). `ERROR` means the deployment failed; the server log has the reason, for example a refused vault grant ([above](#agent-deployment-refused-because-of-a-secret-grant)).

### Readiness DOWN with `agentsInErrorCount`

- **Cause:** `Agents are ready health check` is DOWN while any agent's deployment is in `ERROR`. The check reports only the count; the ids are in the log and in `GET /administration/{environment}/deploymentstatus`.

### Undeploy answers 409

- **Cause:** The version still has open conversations.
- **Fix:** Pass `endAllActiveConversations=true`, or deploy a compatible newer version first so the conversations move to it. `GET /administration/{environment}/deploymentimpact/{agentId}?version={n}` previews what happens.

---

## 11. Authentication and roles

See [Security](security.md).

### 401 on every request

- **Cause:** OIDC is on (`QUARKUS_OIDC_TENANT_ENABLED=true`) and the request carries no valid bearer token. The backend is bearer-only (`quarkus.oidc.application-type=service`): it never redirects to a login page. `/q/health/*` stays readable without a token; `/q/metrics` does not.

### 403 with an empty body

- **Cause 1:** The caller lacks the role the endpoint lists. **EDDI has no role hierarchy**: `@RolesAllowed` checks the literal role names, and `eddi-admin` does not imply the others. The REST surface uses:

  | Role | Typical REST access |
  | --- | --- |
  | `eddi-admin` | Everything, and the only role for `/secretstore`, `/connectionstore` (writes), `/admin/gdpr` and `/administration/logs` |
  | `eddi-editor` | Config stores (`/agentstore/agents`, `/workflowstore/workflows`, ...), deployment (`/administration`), schedules |
  | `eddi-user` | Conversations (`/agents/{agentId}/start`, `/agents/{conversationId}`) |
  | `eddi-approver` | HITL decisions (`/agents/{conversationId}/resume`, `/approval-status`, `/cancel`, `/agents/pending-approvals`) |
  | `eddi-viewer` | Mainly the MCP read tools; over REST only a few read endpoints (documentation, usage, workspaces) accept it |

  Give each user the roles they need explicitly.
- **Cause 2:** The role claim is misconfigured, so the token's roles are never seen. See [Role claim warning](#role-claim-warning-then-every-request-answers-403).

### 403 from `/v1` or Open WebUI

See [Open WebUI integration](open-webui-integration.md); the `/v1` surface has its own authentication settings (`eddi.openai-compat.*`).
