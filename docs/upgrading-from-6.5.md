# Upgrading from EDDI 6.5

This guide is for an existing **EDDI 6.5.x deployment** moving to 6.6 or 6.7. Everything up to
[Changes in 6.7](#changes-in-67) applies from 6.6 on; the last section adds what 6.7 changes on top. It
collects the changes that a working 6.5 client notices. It is written as the changes land, so
read it again before you upgrade. For an older deployment, start with
[Upgrading from 6.4](upgrading-from-6.4.md).

---

## Authorization defaults

These apply only with authorization on (`QUARKUS_OIDC_TENANT_ENABLED=true`). A 6.5 client that
held a token but no EDDI role, or that relied on an endpoint being open to any token, now gets
refused:

- **Metrics.** `/q/metrics` now needs the `eddi-admin` or `eddi-metrics` realm role
  (`eddi.metrics.roles-allowed`); in 6.5 any token was enough, so a scrape whose service account
  holds neither now gets **403**. Create an `eddi-metrics` realm role and give it to the
  scraper's service account ([Monitoring](monitoring/monitoring-guide.md)). As a stopgap,
  `EDDI_METRICS_HTTP_POLICY=authenticated` restores the 6.5 behaviour.
- **API docs.** `/openapi` and the Swagger UI need an EDDI role.
- **`/v1` (OpenAI-compatible API).** In `authenticated` mode it needs `eddi-admin`, `eddi-editor`
  or `eddi-user`.
- **Other role-gated endpoints.** `/a2a/agents` and the per-conversation `/conversationstore`
  operations need an EDDI role.
- **Descriptor listing.** A `type` that is not a configuration type now gets **400**.
- **Triggers.** Changing a trigger needs EDIT access; a `PUT` whose body names a different intent
  than the path gets **400**.
- **Conversations without an owner** are readable by administrators only.
- **Custom JAX-RS resources** deployed as extensions must carry a security annotation
  (`@RolesAllowed`, `@PermitAll`, …). Unannotated endpoints are now denied
  (`quarkus.security.jaxrs.deny-unannotated-endpoints=true`).
- **`${caller:token}` needs an administrator to save it.** Creating, updating, patching,
  duplicating or importing an httpcalls, mcpcalls or LLM configuration that contains
  `${caller:token}` now needs `eddi-admin` (`eddi.caller-identity.author-roles`); an editor gets
  **403**. Stored configs keep working. See
  [Who may write `${caller:token}`](httpcalls.md#who-may-write-callertoken).

---

## Outbound HTTP calls

- **A templated host is refused while SSRF protection is off.** An httpCall whose scheme, host or
  port contains a template expression (`https://{properties.apiHost}`) now fails to deploy unless
  `eddi.security.ssrf-protection.enabled=true`. Write the host literally or as a `${vars:…}`
  reference, or turn protection on and list the internal hosts you need in
  `eddi.security.ssrf-protection.allowed-hosts`. Production also logs a `[SECURITY]` WARN at
  startup while protection is off. See
  [SSRF protection](security.md#ssrf-protection--urlvalidationutils).

---

## MCP server

### MCP role tiers

Up to 6.5 the MCP tools checked one role each, and those roles disagreed with REST: an editor
could provision agents over MCP that REST setup refuses it, could not deploy although REST lets
it, and a viewer could read agent configurations that REST refuses it. An account without
`eddi-viewer` was refused all 27 tools gated on it (`list_agents` and the conversation tools
among them), whatever else it held. Each tool now belongs to one
tier that lists every role it admits ([Role Mapping](mcp-server.md#role-mapping)).

What an existing MCP client loses, by role:

| Account holds only | Tools it can no longer call | Why |
| --- | --- | --- |
| `eddi-viewer` | `list_agents`, `list_agent_configs`, `get_agent`, `list_team_backlog`, `list_group_templates` | Configuration reads; REST refuses them to a viewer. Use `discover_agents` to find an agent |
| `eddi-editor` | `setup_agent`, `create_api_agent` | REST agent setup is admin-only: provisioning writes API keys into the vault |

What it gains:

- `eddi-admin` and `eddi-editor` no longer need `eddi-viewer` for the conversation, group
  discussion and memory-read tools. Exceptions: `read_audit_trail` and conversation-scoped
  `read_agent_logs` admit `eddi-admin` and `eddi-viewer` only (the REST audit and log endpoints
  are admin-only), so an editor that uses them keeps `eddi-viewer`.
- `eddi-editor` can deploy, undeploy and author agents, resources, triggers and channels, as
  over REST. `eddi-admin` can configure groups, which was editor-only.
- `eddi-user` can converse, run group discussions and read its own memories, as over REST.

**What to do:** nothing for a client whose account holds `eddi-admin`. Grant `eddi-admin` to an
editor account that provisions agents through `setup_agent`/`create_api_agent`, and
`eddi-editor` to a viewer account that reads agent configurations.

### Tool failures are `isError: true`

A refused or failed tool call used to come back as an ordinary result whose text was
`{"error": "..."}`, and a missing role came back as a JSON-RPC internal error naming
`io.quarkus.security.ForbiddenException`. Both are now a tool result with `isError: true`
carrying the same JSON body — a missing role with `errorCode: FORBIDDEN`. A client or model that
looked for the word `error` in a successful result keeps working; one that treated a JSON-RPC
error as "not permitted" should read `isError` and `errorCode` instead. See
[Argument contract](mcp-server.md#argument-contract).

### Optional arguments are optional

`tools/list` used to mark every argument required in 75 of the 84 tools, so a client that
omitted an "optional" argument was refused. Optional arguments are now optional and literal
defaults are published as the schema `default`. Clients that sent every argument keep working.
Two defaults changed meaning: `get_agent`, `read_workflow`, `read_resource` and
`list_agent_resources` without a `version` read the **latest** version (they read version 1),
and `create_group` with an unknown `style` is refused instead of becoming `ROUND_TABLE`.

---

## MongoDB least-privilege user

EDDI no longer connects to the bundled MongoDB as its root user. It connects as `eddi_app`, a
user defined in the `eddi` database with `readWrite` and `dbAdmin` there and no role on any other
database (`dbAdmin` because EDDI uses `collMod` to un-hide an index, which `readWrite` alone does
not allow). The root credentials stay with MongoDB and with one helper that creates the user:

- **Docker Compose** (`docker-compose.yml`, `docker-compose.openwebui.yml`,
  `docker-compose.cluster.yml`): a one-shot `mongodb-app-user` service runs before `eddi` on
  every `up`, creates the user or resets its password, and exits.
- **Helm and `k8s/overlays/mongodb`**: an `ensure-mongodb-user` init container in the EDDI pod
  does the same before every start.

A script in `/docker-entrypoint-initdb.d` would run only when MongoDB initialises an **empty**
volume, so an existing deployment would have been left connecting as a user that does not
exist. Because the helper runs every time, **an existing volume needs no manual step** for
Compose or Helm: pull, `up` (or `helm upgrade`), and the user is created against the data you
already have.

What to check:

- **Compose:** `MONGO_APP_PASSWORD` (and `MONGO_APP_USERNAME`, default `eddi_app`) are new. Left
  unset, the password falls back to `MONGO_INITDB_ROOT_PASSWORD`, so an existing `.env` keeps
  working. Set a distinct one, or EDDI's credential is still the root password under another
  name. It goes into `MONGODB_CONNECTIONSTRING` unencoded, so use URL-safe characters
  (`openssl rand -hex 24`). Changing it later is a rotation: the next `up` applies it.
- **Compose healthcheck:** `ping` needs no authentication, so the `mongodb` healthcheck no longer
  carries the root credentials.
- **Helm:** `mongodb.appUsername` (default `eddi_app`) and `mongodb.appPassword` are new. Left
  empty, the password is derived from `mongodb.rootPassword` with SHA-256: stable across renders,
  but not the root password itself. With `mongodb.existingSecret` nothing changes: your
  `eddi.existingSecret` still carries EDDI's connection string, and choosing its user is up to
  you.
- **Kustomize `overlays/mongodb`:** the `mongodb-secrets` Secret you created by hand has no
  `MONGO_APP_*` keys. The init container then does nothing and EDDI keeps connecting as root.
  Add the keys and the new connection string in one patch, then restart EDDI:

  ```bash
  apppw=$(openssl rand -hex 24)
  kubectl patch secret mongodb-secrets -n eddi --type merge -p "{\"stringData\": {
    \"MONGO_APP_USERNAME\": \"eddi_app\", \"MONGO_APP_PASSWORD\": \"$apppw\",
    \"mongodb-secrets.properties\": \"mongodb.connectionString=mongodb://eddi_app:$apppw@mongodb:27017/eddi?authSource=eddi&retryWrites=true&w=majority&connectTimeoutMS=10000&socketTimeoutMS=30000\"}}"
  kubectl rollout restart deployment/eddi -n eddi
  ```

- **Your own MongoDB** (an external connection string): nothing changes automatically. If EDDI
  connects as a root or `readWriteAnyDatabase` user, create one like the above and switch to it:

  ```javascript
  db.getSiblingDB("eddi").createUser({ user: "eddi_app", pwd: passwordPrompt(),
    roles: [{ role: "readWrite", db: "eddi" }, { role: "dbAdmin", db: "eddi" }] })
  ```

  and connect with `authSource=eddi`. If you changed `mongodb.database`, use that name instead
  of `eddi` throughout.

## Newly reserved names

- **`CONTINUE_CONVERSATION` is a reserved action** ([self-continuation](long-jobs.md)). A behaviour
  rule that already emits an action with this exact name keeps working unless the agent also sets the
  new `maxContinuations`; until then the action is ignored with a WARN and
  `eddi_continuation_refused_total{reason="disabled"}` counts it. Rename such actions anyway.
- **`selfContinuation` is an engine-reserved context key.** Like `groupId`, it is dropped from client
  context, and so is any key that starts with it (`selfContinuationStep`, for example). Rename a client
  key with that name.
- **`POST /agents/{conversationId}/cancel`** on a conversation that is neither paused nor running
  answers `200` instead of `409` when it has a pending self-continuation — the cancel withdrew it.

## Keycloak admin password off loopback

`docker-compose.auth.yml` now refuses to start Keycloak when `KEYCLOAK_BIND` is not a loopback
address and `KC_BOOTSTRAP_ADMIN_PASSWORD` is unset or `admin`. Its one-shot `keycloak-guard`
service exits 1 with the fix in its message. Installs made by `install.sh` / `install.ps1
--with-auth` or the GCP provisioner already have a generated password in `.env` and are
unaffected. If your Keycloak volume predates that and you changed the console password by hand,
put that password in `.env` as `KC_BOOTSTRAP_ADMIN_PASSWORD`: Keycloak reads it only on first
boot, and the guard only needs to see that it is not the default.

A volume that still has the original `admin`/`admin` login is fixed on the next `up`: the one-shot
`keycloak-admin-rotate` service logs in with `admin` once Keycloak is healthy and, if that works,
sets the account to the configured `KC_BOOTSTRAP_ADMIN_PASSWORD` before EDDI starts.

**If your Keycloak is published off loopback (`KEYCLOAK_BIND` set) on a volume from before this
release, start it once on loopback first.** `keycloak-guard` cannot know what an existing volume's
admin password is, so it refuses a wider bind until `keycloak-admin-rotate` has checked the login
and left a marker (`.eddi-admin-verified`) in the volume. One `up` with `KEYCLOAK_BIND=127.0.0.1`
does that; after it, set `KEYCLOAK_BIND` back. A fresh volume is created with your password and
needs nothing.

---

## Changes in 6.7

6.7 is mostly hardening and opt-in features. Stored agent, group and workflow configs keep working:
every new field is optional and absent on existing documents. What an existing deployment notices:

### Defaults that changed

- **A new-release check is on by default.** The backend asks `api.github.com` once a day (through the
  SSRF-safe `OutboundHttp`) whether a newer EDDI exists and raises one admin notification per
  release. Set `eddi.update-check.enabled=false` (or its environment-variable form) to turn it off before
  first boot; that also locks the switch in the Manager. The old browser-only `localStorage` flag is
  ignored. In an air-gapped deployment, disable it so the daily call does not fail.
- **Cost figures rise for some workloads.** Usage is normalised the way each provider bills it, so
  `inputTokens` and spend now include Anthropic/Bedrock prompt-cache reads and writes and Gemini
  thinking tokens. Dashboards and cost ceilings that were tuned on the old, understated numbers may
  trigger sooner. `eddi.llm.pricing.provider-normalization=false` restores the old counting. Prices
  now come from a managed model-price catalog (`/administration/model-prices`); the default
  `unpricedPolicy` is `ALLOW`, so nothing is refused until you opt in.
- **Team budgets are admin-edit only.** `eddi.groups.team-budget.admin-only-edits=true`: editors can
  no longer change a group's `teamBudget`. Set it to `false` to restore editor edits. New root
  discussions, follow-ups and continuations are refused with `409` when the team or tenant is spent
  or its budget cannot be read (fail closed), and scheduled cadence fires are skipped.
- **`HumanInputRequest.content` is optional on the DTO** (REST) and gains `choice`; MCP
  `submit_group_human_input` gains `choice`. A missing `content` is still a `400` for every pause
  kind except a tie-break.
- **The cascade judge sees the request.** With `modelCascade.strategy: judge_model`, the judge now
  receives the last 8,000 characters of the user message along with the answer, so its scores (and
  thus escalation rates) can shift.

### Several controls fail closed

The review sweep tightened many paths; callers that relied on the loose behaviour see an error
rather than a silent success. Examples: ingestion refuses a source whose ownership it cannot verify;
a resumed conversation re-checks USE access; Slack approval cards need a known pause; every bot-token
reference is checked against `allowedHosts`; scheme-relative and parser references in configs and
exports are vetted; a caller without access to a group gets `403` (not `500`) from the workspace
endpoints; the GDPR stop sequence now waits for the member count it read itself. Check your
automation and Slack/e-mail integrations after upgrading and read the per-area changelog entries for
the exact behaviour.

### Cluster deployments (NATS)

Applies only if you run more than one node.

- **Readiness is loud.** A node that never connected to NATS, or hit an unrecoverable
  authentication or TLS error, now reports not-ready (`eddi.cluster.readiness.require-first-connect`
  and `.fail-on-misconfiguration`, both default `true`). A wrong credential therefore takes every
  node with it out of rotation instead of running silently unfenced. Both switches can be turned
  off.
- **`tls://` and `wss://` servers are host-name verified** against the configured host names;
  `opentls://` fails boot. Boot also refuses non-positive durations, two auth methods at once and a
  malformed inbox prefix.
- **Signing is opt-in.** Set `eddi.cluster.signing.key` to sign every RPC, reply and event
  (`Eddi-Sig`, HMAC-SHA256); `eddi.cluster.signing.required=true` refuses unsigned messages and
  refuses to start without a key. Without a key only a WARN is logged. During a rolling upgrade
  keep `required=false` until every node carries the key.
- **Webhook de-duplication fails closed** (`503`) while NATS is unreachable, under
  `eddi.cluster.degraded.nonces=reject` (the default).
- **Duplicate node ids are detected.** A twin stops announcing, and the new `cluster-node-identity`
  readiness check goes DOWN.
- **New NATS objects.** A `<prefix>_DEPLOYMENTS` KV bucket; `<prefix>_ARCHIVES` is capped
  (`eddi.cluster.archives.max-bytes`, 1 GiB). Account isolation and a custom inbox prefix are opt-in
  because switching an existing release would strand its dead letters and archives.
- **Helm and Kustomize.** The chart is `2.14.0` or later. The Kustomize `nats` component now needs a
  `nats-auth` Secret that you create before applying; `nats.antiAffinity: required` needs at least
  three nodes; a `preStop` sleep and a short readiness grace are added for SIGTERM. The chart exposes
  `eddi.cluster.degradedTurns`; `reject` is recommended for production, though the code default is
  still `local`.
- **Rolling upgrades.** Group conversation schema goes to version 5; a pod that predates it refuses
  to resume a document that holds a human tie-break pause. Finish the rollout before enabling
  `HUMAN_DECIDES`. `SharedTaskList` gains `reviewerEdits`: confirm older pods tolerate the unknown
  field before enabling `allowResultEdits` on a mixed-version cluster. New collections and tables
  (settings, model prices, team budgets, approval analytics) are created on first use and ignored
  by older nodes.

### New opt-in features

None of these changes behaviour until configured; each has its own page.

| Feature | Switch | Read |
| --- | --- | --- |
| White-label branding | Admin → Branding in the Manager | [white-labeling](white-labeling.md) |
| `toolQuotas` — per-subject, per-window tool-call caps | `toolQuotas` on the LLM task | [langchain](langchain.md) |
| Tool-call guardrails | `toolCallGuardrails` | [langchain](langchain.md) |
| Edit-on-approve and decision audit v2 | `hitlConfig.review` | [hitl](hitl.md) |
| Approval analytics, sampled audit, earned autonomy | `hitlConfig.oversight` | [hitl](hitl.md) |
| Human tie-breaks and humans as task-force executors | `voteConfig.humanTieBreak`, `tiePolicy: HUMAN_DECIDES` | [group-conversations](group-conversations.md) |
| Cross-team handoff | `teamHandoff.targets`, `handoffConfig` | [group-conversations](group-conversations.md) |
| Model-backed convergence judge | `convergence.judge: SERVICE` | [group-conversations](group-conversations.md) |
| Team budgets | `teamBudget` on a group | [group-conversations](group-conversations.md) |

### Dependency advisories

6.7 clears the Guava advisory GHSA-xxph-c9ww-hj94 and overrides `minimatch` to 11.0.4 in the
Manager and Chat UI. Nothing to do besides pulling the new image.

## Changes after 6.7.0

### The default `websearch` price now follows the search provider

Cost tracking used to charge every `websearch` call $0.001 whatever the provider. The default now
depends on `eddi.tools.websearch.provider`: `brave` and `google` are priced at $0.005 per call (both
list $5 per 1,000 requests), `duckduckgo` stays at $0.001. Deployments on `google` therefore see the
price of a search rise from $0.001 to $0.005, five times. Agents, conversations and tenants that
carry a dollar cost ceiling (`maxBudgetPerConversation`, group and team budgets, the tenant's monthly
cost budget) and use web search reach it up to five times sooner for the same number of Google
searches. Agents that set `toolPricing.websearch` themselves are unaffected.

To keep the old price, set `eddi.tools.websearch.price-per-call=0.001` (or
`EDDI_TOOLS_WEBSEARCH_PRICE_PER_CALL=0.001`); it replaces the provider default for whichever provider
is configured, and also lets a plan that differs from list price be recorded as it is. See
[Web Search Tool](langchain.md#web-search-tool).
