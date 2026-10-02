# Upgrading from EDDI 6.4 to 6.5

This guide is for an existing **EDDI 6.4.x deployment** that you want to run on 6.5. There is
no migration to run by hand: the stores add their new indexes, collections and columns when
6.5 starts. What needs you is configuration. Several defaults now **fail closed**, a few
identities are namespaced, and the shipped Keycloak realm and Helm chart changed in ways an
existing install doesn't pick up by itself.

Read [section 2](#2-changes-that-break-a-working-deployment) before you upgrade. Each item
there can take a working 6.4 deployment down: every request answers `401` or `403`, the pod
doesn't start, or `helm upgrade` refuses to render. The later sections cover changes that
a client or an agent author notices.

If this database first came from **EDDI 5**, also read
[section 9](#9-if-this-deployment-was-migrated-from-5x). For a 5.x database that has never
run on 6.x, start with [Upgrading from 5.x](upgrading-from-5x.md) instead. Everything in this
guide applies to it too.

---

## 1. Before you start

1. **Take a backup** (`mongodump`, or `pg_dump` for PostgreSQL). 6.5 rebuilds an index on
   the deployment rows and adds collections, tables and columns at startup
   ([section 8](#8-what-changes-in-the-database-automatically)).
2. **Stop every 6.4 instance, then start 6.5.** Don't run a rolling upgrade with both
   versions against one database, and don't plan on rolling back to 6.4 once 6.5 has served
   traffic:
   - a group discussion a human rejects is now stored as `REJECTED`, a value 6.4 cannot read;
   - conversation documents gain revision fields that 6.4 doesn't maintain, so a 6.4 write
     can silently undo a 6.5 write;
   - the deployment-row index is dropped and rebuilt when 6.5 starts.

   To roll back, restore the backup.
3. **Rehearse on a copy** if you can: restore the backup into a throwaway database, boot 6.5
   against it with your production configuration, and work through
   [section 10](#10-check-the-result).
4. **Resolve pending Slack approvals** (HITL) before you stop 6.4, or plan to decide them in
   the Manager or over REST. Buttons on cards posted by 6.4 stop working
   ([section 2.7](#27-slack-users-get-a-new-identity)).

---

## 2. Changes that break a working deployment

### 2.1 A Keycloak realm imported from EDDI 6.1–6.4 answers 401 on every request

**What changed.** A token that names no user is now refused with `401`. EDDI reads the
user from `quarkus.oidc.token.principal-claim` or, when that is unset, from `upn`,
`preferred_username` or `sub`. Up to 6.4 such a token was let in, and its conversations got a
random `anonymous-<hex>` owner.

**Who is affected.** Every deployment whose Keycloak realm was imported from the realm file
shipped with **EDDI 6.1.0 to 6.4.0** (for Helm and Kustomize, 6.4.0 only). That file defined
none of the `basic`, `profile` and `email` client scopes, so its tokens carry no `sub` and no
`preferred_username`. Keycloak imports a realm only on its first boot, so the corrected realm
file in 6.5 doesn't repair an existing realm. **Without the repair, every request answers
`401` after the upgrade.** Each refusal logs a `[SECURITY]` warning, at most once every five
minutes.

**What to do.** Repair the realm **before** you upgrade. The script is in
[Identity claims, and realms imported from EDDI 6.1.0–6.4.0](security.md#identity-claims-and-realms-imported-from-eddi-610640).
It creates the missing client scopes and attaches them to `eddi-frontend`. `install.sh` does
the same when you re-run it. `eddi update` does not. With another identity provider, point
`QUARKUS_OIDC_TOKEN_PRINCIPAL_CLAIM` at a claim its tokens do carry. Users sign in again
afterwards. Conversations and memories written under the broken realm keep their
`anonymous-<hex>` owner and are not re-attributed.

### 2.2 The token audience is enforced

**What changed.** `quarkus.oidc.token.audience=eddi-backend` is now set. 6.4 accepted a token
minted for any client of the realm. A token without `eddi-backend` in its `aud` claim now gets
`401`.

**Who is affected.** Realms and identity providers whose clients don't add that audience:
hand-built realms, other IdPs, service accounts, and tokens used for `/v1` or MCP in OIDC mode.
The realm EDDI ships has an `eddi-backend-audience` mapper on `eddi-frontend` (and on the new
`eddi-mcp` client), so an unmodified shipped realm is not affected.

**What to do.** Give every client whose tokens EDDI must accept an audience mapper
(`oidc-audience-mapper` with `included.client.audience=eddi-backend`). That is the fix, and EDDI
should accept only tokens that carry `eddi-backend` in `aud`.

`QUARKUS_OIDC_TOKEN_AUDIENCE=any` turns the check off again (see
[security.md](security.md)), but don't treat it as a fallback. EDDI reads roles from
`realm_access/roles`, which doesn't depend on the client. With the check off, a token the realm
issues to *any* of its clients on a user's behalf carries that user's full EDDI rights. That is
the 6.4 exposure this release closes. Use it at most as a stopgap while the mappers are being added,
and remove it afterwards.

### 2.3 Roles are read from `realm_access/roles`

**What changed.** 6.5 sets `quarkus.oidc.roles.role-claim-path=realm_access/roles`. The 6.4
image didn't set it, so Quarkus read roles from the `groups` claim whenever a token had one.
That was a 6.4 bug: a user in any Keycloak group lost every EDDI role and got `403`
everywhere, the seeded `eddi` administrator included.

**Who is affected.**
- Keycloak users who belong to a group: the upgrade fixes them.
- **Deployments whose IdP delivers the EDDI roles in `groups` or another claim: those users
  lose their roles and get `403`.**

**What to do.** In the second case, set `QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH` to the claim that
carries the roles. It must not be the claim `eddi.workspaces.groups-claim` names (`groups` by
default), which EDDI reads workspace membership from. If the two collide, or the path is
missing, EDDI logs an ERROR at startup that names the problem. A 6.4 workaround of
`QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH=realm_access/roles` can stay or go.

### 2.4 Helm with the in-chart MongoDB: the upgrade refuses to render

**What changed.** The chart's MongoDB now runs with authentication, and
`mongodb.rootPassword` is **required** when `mongodb.enabled=true`. The mongo image creates
the root user only on an empty volume, so a plain upgrade would leave MongoDB demanding a user
that doesn't exist. A live `helm upgrade` detects a MongoDB that predates authentication and
refuses to render. The credentialed connection string moves into the chart's Secret.

**Who is affected.** Helm releases with `mongodb.enabled=true`.

**What to do.** Create the root user in the still-unauthenticated database, then upgrade with
the same password and `mongodb.authMigrated=true`. The render error prints the exact
`kubectl exec … mongosh` command for your release, and
[Upgrading to an authenticated MongoDB](kubernetes.md#upgrading-to-an-authenticated-mongodb)
has the full procedure, including the extra values an OIDC-off release needs.

The **Kustomize** `mongodb` overlay also runs MongoDB with authentication now, and has no
render guard. Create the user in the running database first, then the `mongodb-secrets`
Secret with the same password, then re-apply. The commands are in the header of
`k8s/overlays/mongodb/kustomization.yaml`. Applied in the other order, MongoDB restarts with
authentication and no user, and EDDI can't log in until you create one. The PostgreSQL
overlay no longer ships a `postgres-secrets` Secret: an existing install keeps the one it has,
because `kubectl apply -k` doesn't prune.

### 2.5 A weak vault master key stops a production boot

**What changed.** In production, EDDI refuses to start when `EDDI_VAULT_MASTER_KEY` is shorter
than 16 characters, has fewer than 6 distinct characters, or is a known placeholder
(`changeme`, `password`, `secret`, the demo key, …). Dev and test modes only warn.
`rotate-kek` also refuses to rotate **to** a weak key.

**Who is affected.** Deployments with a weak master key.

**What to do.** Boot once with `EDDI_VAULT_ALLOW_WEAK_MASTER_KEY=true`, rotate with
`POST /secretstore/secrets/admin/rotate-kek` (`eddi-admin`, over TLS, as described in
[KEK rotation](secrets-vault.md#kek-rotation)), set the new `EDDI_VAULT_MASTER_KEY`, remove
the flag and restart. **Losing the master key makes every stored secret unrecoverable**, so
store the new one before you restart.

### 2.6 Credentials in HTTP calls must come from the configuration

**What changed.** An HTTP call resolves a credential reference (`${vault:…}`,
`${eddivault:…}`, `${connection:…}`, `${caller:…}`) **only where its configuration wrote
it**: in the template of that URL, header, body or query parameter. A reference that reaches
the request through conversation data (user input, a model reply, an API response, client
context, or a `{properties.x}` that holds one) now refuses the call. The one exception is a
property EDDI itself auto-vaulted: it carries a new `autoVaulted` marker, and only then is its
`${vault:…}` value resolved. The same rule applies to the parameters of an LLM task, a cascade
step and a cascade judge. Two related changes:
- A reference that can't be resolved (the secret doesn't exist, or the vault is off) now
  fails the call. 6.4 sent the literal `${vault:name}` as the credential.
- A variable (`${vars:…}`) whose value holds a credential reference is checked the same way.

**Who is affected.** Agents that pass a credential to an HTTP call through a property, context
or model output. Conversations that auto-vaulted a secret under 6.4 (`scope: "secret"`) and
make the call after the upgrade: the 6.4 property carries no marker, so the call is refused.

**What to do.** Write credential references directly into the HTTP call's template. For an
in-flight secret-scoped conversation, the user enters the secret again or starts a new
conversation. See [Where vault references work](secrets-vault.md#where-vault-references-work).

### 2.7 Slack users get a new identity

**What changed.** A Slack user is now `slack:<team_id>:<user_id>` in EDDI, not the raw Slack
user id. The team comes only from the integration's `platformConfig.teamId`; without it the id
is `slack:<user_id>`. Also:
- An event whose team disagrees with the declared `teamId` gets `403`.
- `/integrations/slack/events` requires the signature of the integration that owns the
  channel.
- Channel-integration names must be unique per channel type. Updating one of two integrations
  with the same name fails validation, and a name may not contain `|`.
- Approval buttons carry a card id, recorded in the new `slack_hitl_approval_records` store.
  A button without one, on a card posted by 6.4, is refused.

**Who is affected.** Every Slack deployment. Ongoing threads keep their conversation, and that
conversation keeps its memories. **A new conversation does not see long-term memories stored
under the raw Slack id**, and nothing moves them.

**What to do.**
- Declare `platformConfig.teamId` on every Slack integration.
- Rename integrations whose names collide or contain `|`.
- Decide pending Slack approvals before the upgrade, or afterwards in the Manager or over REST.
- Decide whether memories stored under raw ids need to be moved by hand. For GDPR requests,
  address both ids until the old conversations have ended.

See [Slack user identity](slack-integration.md#slack-user-identity).

### 2.8 `/v1` users from the Open WebUI header get a new identity

**What changed.** On the OpenAI-compatible API, the user from `X-OpenWebUI-User-Id: 42` is now
`openwebui:42`. OIDC principals are unchanged, but a principal that itself starts with
`openwebui:` gets `401`.

**Who is affected.** Open WebUI and other `/v1` clients that identify users through the header.
**Each existing chat starts a new EDDI conversation on its next message**, without the long-term
memories of the old one.

**What to do.** If `/v1` **never** ran with `http-policy=authenticated`, set
`EDDI_OPENAI_COMPAT_ADOPT_LEGACY_HEADER_MAPPINGS=true` to keep the existing chats on their
conversations. Leave it off otherwise. GDPR requests for an adopted user must cover both
`openwebui:<id>` and the raw `<id>`. See
[Identity resolution](open-webui-integration.md#identity-resolution).

### 2.9 Templates: fetched data is no longer a template, and the engine is restricted

**What changed.**
- **A value read through `fromObjectPath` is stored as found.** This covers the property
  setter and the `postResponse` property instructions of HTTP calls. An upstream value
  `Hello {properties.name}` used to be stored with the substitution applied; now the property
  holds that literal text. Only `valueString` is a template.
- **Output from context or from `postResponse` output-building is delivered verbatim**: the
  context keys starting with `output` or `quickReplies`. Rendered output is also final, so a
  second `ai.labs.templating` step in a later workflow doesn't render it again.
- **Runtime templates run in a restricted Qute engine.**
  - Namespaces: only `vault`, `eddivault`, `connection`, `vars`, `caller`, `uuidUtils`,
    `json`, `encoder`, `str` and `time`. `config:`, `inject:` and `cdi:` render empty, and
    `str:eval` is gone.
  - Sections: only `if`, `for`/`each`, `let`/`set`, `with` and `when`/`switch`. Any other,
    such as `{#include}` or `{#eval}`, **fails the render**.
  - Only properties are read from objects (getters, record components, public fields). No
    method with arguments is called, apart from EDDI's string helpers such as `substring`,
    `replace` and `toLowerCase`.
  - A missing value always renders empty.
  - New bounds: `eddi.templating.max-output-chars` (2,000,000) and
    `eddi.templating.max-iterations` (100,000). `0` disables either.

**Who is affected.** Configurations that relied on templates inside fetched data, on
double rendering across workflows, or on the namespaces, sections and method calls listed
above. None of this was documented behaviour.

**What to do.** Move templates into `valueString` or the output configuration. Check the
templates you rely on with `POST /administration/preview/template`. See
[What a template can reach](output-templating.md#what-a-template-can-reach) and
[Runtime template engine](security.md#runtime-template-engine).

### 2.10 Docker Compose publishes on loopback

**What changed.** `docker-compose.yml` and `docker-compose.postgres-only.yml` publish EDDI's
ports on `127.0.0.1` unless a bind-address variable says otherwise, and
`docker-compose.auth.yml` does the same for Keycloak (`${KEYCLOAK_BIND:-127.0.0.1}`). Keycloak's bootstrap admin still defaults to `admin`/`admin`
(`KC_BOOTSTRAP_ADMIN_USERNAME`, `KC_BOOTSTRAP_ADMIN_PASSWORD`).

**Who is affected.** Compose deployments reached from other hosts, or through a proxy on
another host.

**What to do.** Set EDDI's bind address to `0.0.0.0` in `.env` (the comment above the
`ports:` entry in `docker-compose.yml` names the variable), and `KEYCLOAK_BIND` for Keycloak.
Keep authentication on, and change the Keycloak admin password first.

---

## 3. Authentication and OIDC: other changes

- **Token cache.** `quarkus.oidc.token-cache.*` is new (1,000 entries, TTL `3M`). Signature,
  expiry and audience are still checked on every request. What the cache defers is Keycloak's
  userinfo call, which is also its session-revocation check, so a logged-out session keeps
  working for up to three minutes. Lower `QUARKUS_OIDC_TOKEN_CACHE_TIME_TO_LIVE` if that
  matters.
- **`/mcp` is advertised as an OAuth protected resource** (RFC 9728), so MCP clients can sign
  themselves in. The metadata document is served anonymously at
  `/.well-known/oauth-protected-resource/mcp`.
  - On plain HTTP with authentication on, set
    `QUARKUS_OIDC_RESOURCE_METADATA_FORCE_HTTPS_SCHEME=false`.
  - The advertised authorization server is `quarkus.oidc.token.issuer`, falling back to
    `auth-server-url`, which is usually an internal address. Set `QUARKUS_OIDC_TOKEN_ISSUER`
    (or `QUARKUS_OIDC_RESOURCE_METADATA_AUTHORIZATION_SERVER`) to the public issuer.
  - Behind a proxy that doesn't pin `Host`, set `QUARKUS_OIDC_RESOURCE_METADATA_RESOURCE` to
    the absolute public URL of `/mcp`.
  - In Helm, these are `eddi.oidc.resourceMetadata.forceHttpsScheme` (defaults to whether
    `ingress.tls` is set), `.authorizationServer` and `.resource`.
- **The shipped realm has an `eddi-mcp` client, and the `eddi` administrator also holds
  `eddi-viewer`.** An existing realm is not re-imported. Only if MCP clients should sign in
  through Keycloak: create `eddi-mcp` as described in [MCP server](mcp-server.md) and
  [security](security.md), and grant `eddi-viewer` to administrators who use MCP. There is no
  role hierarchy: an administrator without `eddi-viewer` is refused the read tools over MCP. (Releases after 6.5.0
  admit `eddi-admin` and `eddi-editor` to the MCP read tools directly — see [MCP role mapping](mcp-server.md#role-mapping).)
- **The shipped realm seeds no passwords and disables the password grant.** New installs only:
  the `viewer` and `user` fixtures no longer ship as `viewer`/`viewer` and `user`/`user`, and
  `eddi-frontend` no longer allows the direct-access (password) grant. An existing realm keeps
  what it has.
- **A2A.** The agent cards (`/.well-known/agent.json`, `/a2a/agents/{agentId}/agent.json`) and
  `/.well-known/capabilities` are now anonymous. They answered `401` under OIDC in 6.4.
  `GET /a2a/agents` still needs a signed-in user. `POST /a2a/agents/{agentId}` (`tasks/send`)
  now needs `eddi-admin`, `eddi-editor` or `eddi-user`. The Agent Card's token endpoint comes
  from `eddi.keycloak.public.url`; with another IdP, set `EDDI_A2A_PUBLIC_TOKEN_ENDPOINT`.

---

## 4. Other things that now fail closed or are refused

| What changed | Who is affected | What to do |
| --- | --- | --- |
| **`${caller:token}` is also released to EDDI's own address**: `eddi.self.base-url`, else `http://127.0.0.1:${quarkus.http.port}`. That address bypasses any reverse proxy. | Deployments that rely on proxy path rules to limit what agents may call on EDDI itself. | Set `EDDI_CALLER_IDENTITY_SELF_RELEASE_ENABLED=false`, or set `EDDI_SELF_BASE_URL`. See [Calling as the signed-in user](httpcalls.md#calling-as-the-signed-in-user). |
| **Engine-reserved context keys are dropped from client input**: `groupId`, `groupConversationId`, `groupDepth`, `groupTranscript`, `dynamicAgentConfig`, `dynamicCreatedAgentIds`, `delegationDepth`, and any key that starts with one of them (`groupIdLabel`, for example). Removed silently, at REST conversation start and turn and at the MCP managed trigger. `{context.groupId}` renders empty for a client-started turn, and a `groupId` property no longer selects the group memory scope. | Clients that set these keys, or keys that start with them. | Rename your own keys. Only for trusted callers, permit them with `EDDI_CONVERSATION_CLIENT_CONTEXT_PERMITTED_RESERVED_KEYS=<comma list>`. |
| **New role requirements.** `GET /conversationstore/conversations/active/{agentId}` and `POST /conversationstore/conversations/end` need `eddi-admin` or `eddi-editor`, and so does `/parser/*`. Schedules are owner-scoped: a non-admin sees and changes only their own. A global variable whose value holds a `${vault:…}`, `${eddivault:…}` or `${connection:…}` reference needs `eddi-admin`. | Users with only `eddi-user`, and scripts that call these endpoints. | Grant the role, or move the script to an editor or admin account. |
| **User-memory writes are validated** (`PUT /usermemorystore/memories` and the MCP memory tools). `visibility` is required (a missing one used to cause a `500`); `self` and `group` need `sourceAgentId`, and `group` needs `groupIds`. Only the known categories are accepted. Keys are limited to 255 characters and values to 65,536 characters. `_gdpr_*` keys are refused with `400`. | Clients that write user memories directly. | Send `visibility` and the ids its scope needs. See [Write validation](user-memory.md#write-validation). |
| **The model can write only `self` memories unless you allow more.** The memory-tool guardrails gain `allowedVisibilities` (default `["self"]`, plus the configured `defaultVisibility`) and `allowGlobalKeyOverwrite` (default `false`). A model can no longer overwrite a global key another agent owns, or one with no recorded owner, which includes migrated data. | Agents with `enableMemoryTools` whose model is meant to write `group` or `global` memories. | Set both fields in the agent's `userMemoryConfig.guardrails`. See [Guardrails](user-memory.md#guardrails). |
| **Property visibility is honoured.** An unknown `visibility` on a property instruction now fails the configuration; it used to become `global`. A long-term property without its own visibility gets `userMemoryConfig.defaultVisibility`, whether or not memory tools are enabled. | Rule-based agents that share long-term properties across agents and have a `defaultVisibility` other than `global`: other agents stop seeing those properties. | Set `visibility` on the properties that other agents need. |
| **`scope: "secret"` works on every path, or fails the turn.** Secret-scoped values from HTTP-call, MCP and LLM pre- and post-instructions used to be stored in plaintext; they are now vaulted, and without `EDDI_VAULT_MASTER_KEY` the turn fails. Saving a secret-scoped `valueObject`, `valueInt` or other non-string value, or a name containing `/`, `{`, `}` or `$`, is refused with `400`. | Agents that use `scope: "secret"` outside the property setter, and deployments without a master key. | Set `EDDI_VAULT_MASTER_KEY`, or use `scope: "conversation"`. See [properties](properties.md). |
| **ZIP import limits**: 10,000 entries, 32 MiB per entry and 256 MiB in total, uncompressed. Over a limit, every import, merge, upgrade and sync path answers `413`. The request itself is also held to the body limit in [section 6](#6-helm-kubernetes-compose-and-the-image). | Very large agent archives. | Split the archive, or raise `eddi.backup.import.max-entries`, `eddi.backup.import.max-entry-bytes` or `eddi.backup.import.max-uncompressed-bytes`. |
| **RAG ingestion crons are validated.** A cron that can never fire (`0 0 30 2 *`) or that fires more often than `eddi.schedule.min-interval-seconds` (60) is refused when the knowledge base is saved, and on ZIP import. Six-field Quartz expressions are refused. | Knowledge bases with such a cron. A too-frequent one keeps firing until the knowledge base is next saved. | Use a five-field cron within the interval. |
| **Agent Sync between instances** refuses a source that is plain HTTP or a private address, with a `400` naming the setting (`eddi.backup.sync.require-https`, default `true`; `eddi.backup.sync.allow-private-targets`, default `false`). | Instances that sync from an internal or HTTP source. | List the source in `eddi.backup.sync.allowed-sources`. See [Agent Sync](agent-sync-guide.md). |

---

## 5. Outbound HTTP

Test the integrations that depend on the first three points before you upgrade production.

- **HTTP calls no longer keep cookies between calls.** The shared client used to be a cookie
  session; an API that relies on a session cookie set by an earlier call now sees none.
- **A request whose headers carry a resolved credential doesn't follow redirects** (a
  `${vault:…}`, `${connection:…}` or `${caller:…}` reference). Credential headers are also
  stripped from any cross-origin redirect hop. Point credentialed calls at the final URL.
- **More address ranges are blocked** as private or internal: IPv4 embedded in IPv6 (mapped,
  NAT64, 6to4, Teredo), the NAT64 local-use prefix `64:ff9b:1::/48`, `198.18.0.0/15`, and
  more cloud metadata endpoints such as `168.63.129.16`. An internal target in one of them is
  now refused. See [SSRF protection](security.md#ssrf-protection--urlvalidationutils).
- **One deadline covers a whole exchange** in the built-in tools' HTTP client, redirects and
  body included, so an endpoint that trickles its answer slowly now times out.
- **Size caps.** An HTTP call's `maxLength` is enforced while the response streams in. New
  limits: `eddi.tools.web-scraper.max-response-bytes` (5 MiB),
  `eddi.tools.pdf-reader.max-download-bytes` (25 MiB) and
  `eddi.attachments.extraction.max-pages` (500).
- **Fan-out cap.** A fire-and-forget `batchRequests` call larger than its `maxBatchSize`
  (default `eddi.httpcalls.batch.default-max-size`, 100) fails the turn. Saving a
  `maxBatchSize` above `eddi.httpcalls.batch.max-size-ceiling` (1,000) is refused with `400`.
- **OpenAPI specs for MCP API tools** may no longer use `file:` or relative `$ref`s.
- **The web scraper tool returns Markdown** instead of a plain-text dump.

---

## 6. Helm, Kubernetes, Compose and the image

Besides the MongoDB change in [section 2.4](#24-helm-with-the-in-chart-mongodb-the-upgrade-refuses-to-render):

- **Helm chart.** 6.4.0 shipped chart 2.0.0; this release ships 2.3.0.
  - `eddi.image.tag` is empty by default, which means the chart's `appVersion`. **Set
    `eddi.image.tag` (and ideally `eddi.image.digest`) to the release you are deploying.** A
    chart taken from the repository names the previous image until the release's
    post-release update lands.
  - With OIDC off, `eddi.security.allowUnauthenticatedMcp` and
    `eddi.security.allowUnauthenticatedSecretStore` no longer follow `eddi.oidc.enabled`. Unset
    means `false`, and the chart refuses to render until both are set. `ingress.enabled=true`
    with OIDC off also needs `eddi.security.allowUnauthenticated` set explicitly. Enable OIDC
    or set the values. `--reuse-values` can't carry values the old chart never had, so put
    them on the upgrade command line.
  - The startup probe checks `/q/health/live` instead of readiness, so a long startup
    migration no longer gets the pod killed. Its limits are `eddi.startupProbe.*`.
  - The EDDI and MongoDB pods no longer mount a service-account token
    (`serviceAccount.automountToken`, default `false`). Set it to `true` only if you add
    something to the pod that calls the Kubernetes API.
  - `networkPolicy.enabled` (default `false`) renders a policy for EDDI and isolates the
    in-chart datastores and Keycloak.
  - New values: `eddi.chat.frameAncestors`, `eddi.metrics.httpPolicy` and
    `eddi.oidc.resourceMetadata.resource` ([section 3](#3-authentication-and-oidc-other-changes)).
- **Kustomize.**
  - The `k8s/base` Deployment names the placeholder image
    `labsai/eddi:pinned-by-kustomization`; the real tag comes from `images:` in
    `k8s/base/kustomization.yaml`. Always apply with `kubectl apply -k`, and set `newTag` in
    your own overlay.
  - The `mongodb` overlay adds a NetworkPolicy that admits only EDDI pods to port 27017. The
    `postgres` overlay adds the same for PostgreSQL. Allow backup or admin pods explicitly.
  - The `monitoring` overlay needs a `grafana-admin` Secret, and Prometheus now uses a Role
    instead of a ClusterRole. `kubectl apply -k` doesn't prune, so delete the old
    ClusterRole and ClusterRoleBinding by hand.
  - As in the chart, pods no longer mount a service-account token, and the startup probe
    checks liveness.
  - See [Kubernetes](kubernetes.md).
- **Image.**
  - Application files belong to `root:0`, so the process (UID 185) can write only under
    `/deployments/tmp` and `/opt/eddi`. Custom images or entrypoints that write elsewhere
    under `/deployments` need changing.
  - The image sets `JDK_JAVA_OPTIONS=--add-modules=jdk.incubator.vector`, which the in-process
    Jlama provider needs. Append to `JDK_JAVA_OPTIONS` instead of replacing it.
- **Request body size.**
  - `quarkus.http.limits.max-body-size` rises from `25M` to `60M`, but only RAG file uploads
    can use it. Every other endpoint, ZIP import included, is held to
    `eddi.http.limits.default-max-body-size` (`25M`), raised automatically to fit an attachment
    of `eddi.attachments.max-size-bytes` (about 27.7 MB by default). A larger body gets `413`.
  - A chunked HTTP/1.1 body without `Content-Length` gets `411`, unless
    `eddi.http.limits.refuse-unsized-bodies=false`.
  - Raise a proxy's body limit only if you want large RAG uploads.
- **Chat UI framing.** `/chat` has its own Content-Security-Policy, with
  `frame-ancestors` set from `eddi.chat.frame-ancestors` (default `'none'`). It couldn't be
  framed under 6.4 either. To embed it, set `eddi.chat.frame-ancestors` (`EDDI_CHAT_FRAME_ANCESTORS`; Helm
  `eddi.chat.frameAncestors`) to the embedding origins.
- **Metrics.** `/q/metrics` still needs authentication, now through its own setting,
  `eddi.metrics.http-policy` (Helm `eddi.metrics.httpPolicy`). Set it to `permit` only where
  the path can't be reached from outside.
- **Manager and Chat UI.**
  - Both are now built from `ui/manager` and `ui/chat` in this repository and bundled into
    the image as before (`/manage`, `/welcome`, `/workforce`, `/chat`). The former separate
    repositories are archived, so stop deploying bundles built from them.
  - The Chat UI now sends the route's environment, so `/chat/test/{agentId}` really talks to
    the `test` deployment.
  - It removes `?token=` from the address bar after loading, and accepts only a same-origin
    path in `?apiServer=`.
- **Installers.** `install.sh` and `install.ps1 --with-auth` generate the Keycloak (and
  Grafana) admin passwords into `.env`. Plain Compose still defaults Keycloak to
  `admin`/`admin`.

---

## 7. What a REST, MCP or SSE client notices

No REST path was removed between 6.4.0 and 6.5.

### 7.1 Responses and status codes

- **Failed turns say why.** A turn in which a task failed carries a `taskErrors` entry in its
  output, in the default response and when streaming. Under 6.4 such a turn answered `200`
  with `conversationState: "ERROR"` and an output holding only actions.
- **`POST /conversationstore/conversations/end`** reads only conversation ids from the body
  and answers `{ended, skipped, failed}`: `200` when all ended, `500` if any end failed, `400`
  for a missing body. It accepts an optional `endReason` query parameter; an unknown reason is
  a `400`. Undeploying with "end conversations" refuses to undeploy when an end failed.
- **Group conversations.**
  - A discussion a human rejects ends in the new terminal state `REJECTED` instead of
    `FAILED`. The `group_complete` SSE event can carry it, and MCP clients that poll should
    treat it as terminal.
  - New SSE events `cost_updated` and `stance_updated`; `speaker_complete` gains `outcome`.
  - Starting or continuing a group conversation needs USE access to the group. Non-admins
    page through their own group conversations only.
- **Agent visibility.** `GET /administration/{environment}/deploymentstatus` and the MCP
  `list_agents` and `discover_agents` tools list only the agents the caller may use.
- **Memory reads show less.** Detailed reads (`returnDetailed`) drop `audit:*`, `*:trace:*`
  and `*Error` keys and redact values under credential-named keys (`apiKey`, `token`,
  `secret`, `password`, `authorization`). A turn flagged as secret input reads back as
  `<secret input>`, older stored turns included. The stored documents are not rewritten.
- **HITL.** MCP `get_approval_status` with `detail=full` no longer returns `argumentsRaw`,
  `chatTranscriptJson`, `traceSoFar` or `gatingAssistantMessageJson`. A resume may carry
  `pauseId`; if the pause has changed, the answer is `409 PAUSE_CHANGED`. A turn resumed by
  someone other than the conversation owner runs without a caller identity, apart from the
  tool calls that person approved, so a later `${caller:token}` call fails.
- **`404` instead of `500`** for unknown ids on undo, redo, `PATCH /agents/{agentId}/state`
  and `/llm/tools/history/{id}`. The managed-agent POST answers `401` instead of `500` for an
  anonymous caller.
- **Schedules.** `POST …/{id}/dismiss` answers `409` unless the schedule is dead-lettered, and
  a `PUT` on a RAG-ingestion schedule answers `409`.
- **Export downloads** carry a token in their key (`<slug>--<id>-<version>-<token>.zip`), so an
  export link made before the upgrade no longer downloads. Export again.
- **`/v1` model ids.** Agents whose slugs collide are listed as `<slug>-<agentId>`.
- **Audit ledger.** New entries are signed in a new format (`v5:<keyId>:<hex>`), and
  verification can report `UNKNOWN_KEY`. An external verifier has to understand the new
  format. New optional settings: `eddi.audit.hmac-key` and `eddi.audit.hmac-previous-keys`.
- **MCP transport.** The MCP server library moved to a new major version with a stricter
  `Origin` check against DNS rebinding. Retest MCP clients that send an unusual `Origin`.

### 7.2 Agents and conversations

- **Conversations can follow a new agent version.** A version saved with `compatible=true`
  (`PUT /agentstore/agents/{agentId}?compatible=true`, MCP `apply_agent_changes`) lets running
  conversations move to it on their next turn. The default is `false`, and versions saved
  before 6.5 carry no compatibility marker, so the upgrade itself moves no conversation.
  Undeploying no longer ends conversations that can move to a compatible version. See
  [Running conversations and new agent versions](deployment-management-of-agents.md#running-conversations-and-new-agent-versions).
- **Failed deployments are retried** with a growing delay. Readiness stays UP and reports
  `agentsInErrorCount`.
- **NEGOTIATION groups get their arbitration prompt back.** A stored NEGOTIATION group whose
  Arbitration phase had no prompt used the generic synthesis prompt in the backend. The moderator then
  summarised the deadlock instead of deciding it, and that summary was recorded as the verdict. Only
  the Manager repaired such a group, and only on its next save. 6.5 restores the arbitration prompt at
  run time and on save, so the outcome of those negotiations changes: a real verdict instead of a
  summary.
- **Soft delete ends the conversation.** Ended, soft-deleted conversations are purged by the
  retention sweep (`eddi.conversations.deleteEndedConversationsOnceOlderThanDays`, 365)
  counted from their last interaction.
- **Undo** reverts property changes too, and a new turn clears the redo stack. In recall and
  summaries the greeting is turn 0 and the first user message turn 1.
- **Tool results from HTTP, MCP and A2A tools are no longer cached** unless the LLM task names
  the tool in `toolCacheScopes`. Built-in tools are cached as before. See
  [Tool cache scoping](langchain.md#tool-cache-scoping).
- **RAG context is wrapped in a provenance envelope** before it reaches the model
  (`markRagProvenance`, default `true`). A `eddi://ai.labs.rag` workflow step now works, and a
  broken knowledge-base URI in it fails the deployment. Ingestion sources whose cron was never
  armed are armed at startup (`eddi.rag.ingestion.schedule-repair.enabled`, default `true`),
  so expect first runs, and their embedding cost, soon after boot.
- **JSON mode** falls back to plain output only on errors that can't be retried. A gateway
  that answers a JSON response format with a `5xx` now fails the turn. Set
  `jsonResponseFormat: "off"` for it.
- **Gemini** defaults `returnThinking` and `sendThinking` to `true`, which Gemini 3.x needs
  for tool calls.
- **`includeFirstAgentMessage` is deprecated.** `false` now drops the first history message
  only when it is the agent's. An agent without a greeting keeps the user's first message.
- **New LLM types** for OpenAI-compatible providers (`xai`, `deepseek`, `moonshot`, `qwen`,
  `zhipu`, `minimax`, `openrouter`, `groq`). Existing `openai` configurations with a
  `baseUrl` keep working. See [LLM configuration](langchain.md).
- **LLM metrics.** Every model call is measured: `eddi.llm.request.duration` (per provider,
  model and outcome), `eddi.llm.tokens` and `eddi.llm.request.errors`. Watch the cardinality
  in Prometheus.
- **Log lines** escape CR and LF on the console, so a multi-line exception message becomes one
  line. Alerting rules that match across lines need updating.
- **NATS.** With `eddi.messaging.type=nats`, the conversation stream is updated in place to
  bounded retention (`eddi.nats.stream-max-age=1h`, `stream-max-messages=100000`,
  `stream-max-bytes=256MiB`).
- **Platform Operator.** An operator activated under 6.4 may carry a `targetServerUrl` taken
  from the browser. Reconfigure it; the Manager now pre-fills the address. Check that a 6.4
  reconfiguration didn't leave two operators deployed.
- **Jlama.** Agents created by the 6.4 wizard with a bare model name never loaded; fix the
  model name. Put Jlama's `modelCachePath` on a volume.

---

## 8. What changes in the database automatically

None of this needs a command. Two of the steps **delete duplicate documents**, which is why
[section 1](#1-before-you-start) asks for a backup.

- **Duplicate user memories are merged at startup (MongoDB).** Before building the new unique
  indexes `idx_um_upsert_global` and `idx_um_upsert_agent` on `usermemories`, EDDI merges
  entries that share an identity. The one with the newest `updatedAt` survives and takes the
  summed `accessCount`; **the others are deleted, values included**. The log says
  `Merged duplicate usermemories identities: <n> redundant entries removed`. If the merge or
  the index fails, EDDI logs an ERROR and starts without the guarantee.
- **Deployment rows (MongoDB).** Every index on `(environment, agentId, agentVersion)` is
  replaced by one partial unique index. If duplicate rows block it, the newest row is kept and
  the others are deleted. Boot is never blocked.
- **Resource indexes (MongoDB).** An index on a store's own key that an earlier EDDI built
  with a different specification is rebuilt: the new one first, then the old one dropped.
  Custom indexes on those keys are replaced.
- **Conversation documents** gain `_rev` and `_histRev`. A document without them counts as
  revision 0, so nothing is rewritten. Concurrent undo, redo or resume can now answer `409`;
  the metric `eddi_conversation_store_conflict_count` counts the conflicts.
- **New collections and tables:** `rag_ingestion_documents`, `rag_ingestion_runs`, the
  `rag_ingested_files` GridFS bucket (a table on PostgreSQL), `slack_hitl_approval_records`,
  and principal indexes on connection grants (`idx_grant_principal` on MongoDB,
  `idx_cg_principal` on PostgreSQL). GridFS attachments gain metadata indexes.
- **PostgreSQL** runs `ALTER TABLE … ADD COLUMN IF NOT EXISTS` on the ingestion and Slack
  approval tables, so the database user needs DDL rights.
- **Vault.** Secret checksums become keyed HMACs, and new secrets are sealed with additional
  authenticated data. Existing secrets keep working and are migrated on their next write.
  Two new refusals:
  - a vault salt that can't be read stops startup, where 6.4 fell back to the legacy salt;
  - when the vault already holds keys and the master key changes, new keys are refused. Run
    `rotate-kek`, or, if the old master key is lost,
    `POST /secretstore/secrets/admin/adopt-master-key?confirm=true`. See
    [Lost master key](secrets-vault.md#lost-master-key).

---

## 9. If this deployment was migrated from 5.x

The 5.x migration in 6.4.0 had defects that could leave it half done while recording it as
complete. 6.5 fixes the migration, but it doesn't re-run it on a database whose `migrationlog`
already records it. Check before you upgrade:

1. **`db.migrationlog.find()`** contains `v6-rename-migration-complete` and
   `v6-qute-migration-complete`.
   - If `v6-qute…` is missing, start 6.5 once with `EDDI_MIGRATION_V6_QUTE_ENABLED=true` and
     read the per-document messages.
   - If `v6-rename…` is missing, follow [Upgrading from 5.x](upgrading-from-5x.md).
2. **Nothing v5 is left:** no collections `bots`, `packages`, `behaviorrulesets`, `httpcalls`,
   `langchain` or `regulardictionaries`, and no configs referencing `eddi://ai.labs.bot/`,
   `ai.labs.package/`, `ai.labs.behavior/`, `ai.labs.httpcalls/`, `ai.labs.langchain/` or
   `ai.labs.regulardictionary/`.
3. **Every agent that should be deployed has a row in `deployments`.** 6.4.0's first boot
   could delete deployment rows. Redeploy any agent that lost its row.
4. **No template still holds Thymeleaf syntax** (`[[${`, `[(${`, `th:`). Convert any that do
   by hand.

With `EDDI_MIGRATION_V6_RENAME_ENABLED=true` still set, every 6.5 boot also moves triggers an
earlier 6.x left in `bottriggers` to `agenttriggers`, and brings triggers and
user-conversation mappings to the v6 shape. With the flag off, that catch-up doesn't run.

The same flag enables two one-time catch-ups on the first 6.5 boot:
- **The step shape of stored conversations.** `packages` becomes `workflows` in every conversation
  that an earlier 6.x left with v5 steps. Those conversations loaded only through a read-time alias,
  which stays as a safety net.
- **The field names of conversation descriptors.** `botResource` / `botName` become `agentResource` /
  `agentName`. 6.4 read a v5 descriptor without its agent and wrote it back that way on every turn and
  every idle end. So many descriptors have no agent at all; they get it back from their conversation.
  Until then, `GET /conversationstore/conversations?agentId=<id>` doesn't list those conversations.

Each logs `Migrating … which an earlier 6.x left in place` and, once it has succeeded, records
`v6-rename-step-shape-complete` or `v6-rename-descriptor-fields-complete` in `migrationlog`. A catch-up
that fails logs an ERROR and runs again on the next boot. Afterwards, both of these should be 0:

```javascript
db.descriptors.countDocuments({ botResource: { $exists: true } })
db.conversationmemories.countDocuments({ "conversationSteps.packages": { $exists: true } })
```

**The idle limit's `-1` now means "never".** In 6.4, setting
`eddi.conversations.maximumLifeTimeOfIdleConversationsInDays` to `-1` made every conversation count as
idle, so the daily sweep ended all of them. From 6.5, any value below 1 disables idle-ending, the same
way `-1` works for retention. If you set a very large number to work around that, you can switch to
`-1`.

The retention hold described in
[Upgrading from 5.x](upgrading-from-5x.md#3-retention-decide-before-the-first-boot) applies
only to a first boot from 5.x. A database that 6.4 already migrated keeps the retention sweep
it had under 6.4.

---

## 10. Check the result

- **Sign in.** Open the Manager as a normal user and as an administrator. A `401` points to
  [section 2.1](#21-a-keycloak-realm-imported-from-eddi-6164-answers-401-on-every-request) or
  [2.2](#22-the-token-audience-is-enforced); a `403` points to
  [section 2.3](#23-roles-are-read-from-realm_accessroles).
- **Startup log.** Look for:
  - `[SECURITY] Rejected an authenticated request (401)` — tokens without a user name;
  - an ERROR about `quarkus.oidc.roles.role-claim-path` — a missing or clashing roles claim;
  - `[VAULT]` lines — master key strength, salt and key checks;
  - `Merged duplicate usermemories identities` — how many user memories were merged away.
- **Agents.** `GET /administration/production/deploymentstatus` lists every agent READY, and
  the readiness check reports `agentsInErrorCount: 0`.
- **HTTP calls.** Run a conversation that uses each credentialed API. A refusal names the
  field and the reference ([section 2.6](#26-credentials-in-http-calls-must-come-from-the-configuration)).
- **Templates.** Render the templates you rely on with `POST /administration/preview/template`.
- **Slack and Open WebUI.** Send a message from each, and check that it reaches an existing
  thread's or chat's conversation, or starts a new one if you expect that
  ([2.7](#27-slack-users-get-a-new-identity), [2.8](#28-v1-users-from-the-open-webui-header-get-a-new-identity)).

See also: [configuration reference](configuration-reference.md), [security](security.md),
[secrets vault](secrets-vault.md), [Kubernetes](kubernetes.md),
[Slack integration](slack-integration.md), [Open WebUI integration](open-webui-integration.md).
