# Upgrading from EDDI 6.5

This guide is for an existing **EDDI 6.5.x deployment** moving to the next release. It
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
