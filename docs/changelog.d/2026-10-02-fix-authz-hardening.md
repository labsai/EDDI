## 🔒 fix(security): conversation owner can no longer be stripped; role gates on /v1, metrics, OpenAPI; deny-by-default JAX-RS (2026-10-02)

**Repo:** EDDI (`fix/authz-hardening`)

Answers the authorization findings F1–F7 of the 6.5.0 review (`REVIEW-2026-10-02.md` §2 #1 and #9,
§3.4, §4.2).

### F1 (Critical) — conversation owner stripped through the generic descriptor API

Conversation descriptors share the `descriptors` collection with configuration descriptors.
`GET /descriptorstore/descriptors?type=ai.labs.conversation&filter=<user>` enumerated them, and
`PATCH /descriptorstore/descriptors/{conversationId}` read one as a configuration descriptor and
replaced it whole — without `userId`, `agentResource`, `environment` or `conversationState`.
`ConversationAccessGuard` then admitted every authenticated caller ("legacy, unowned"), and a viewer
and a role-less token read another user's conversation. Fixed in depth:

- **Configuration only.** New [`ConfigResourceTypes`](../../src/main/java/ai/labs/eddi/configs/descriptors/ConfigResourceTypes.java)
  derives the configuration descriptor types from the configuration stores themselves (every
  `IRestVersionInfo` store's resource URI) — no hand-kept list. `RestDocumentDescriptorStore` refuses
  a listing whose `type` is not one of them (`400`; a blank type or a prefix used to match every
  descriptor) and answers `404` for a read, simple read or patch of a non-configuration id.
  `ResourceSharingService` does the same for every `/shares/**` operation (and cascade targets), and
  the access-request endpoint checks it too.
- **No lossy write.** `DocumentDescriptorStore.setDescriptor`/`updateDescriptor` refuse to write a
  conversation descriptor through the configuration shape, whatever the caller — the whole-document
  replace is what dropped the fields. Same code path on MongoDB and PostgreSQL.
- **Fail closed.** `ConversationAccessGuard` resolves the owner from the descriptor, else from the
  conversation memory snapshot (the pre-v5.1.6 fallback the listing already used, now the projected
  `loadListingSummaries` read), and refuses a conversation with no owner anywhere to everyone but an
  admin — on every read, write and listing path (`requireConversationOwner`,
  `requireExistingConversationOwner`, the strict variant, `canAccessConversation`). The legacy-open
  rule existed for pre-v5.1.6 descriptors, whose memory records the owner; EDDI never writes a
  conversation without one.

### F2 — agent-trigger hijack

`RestAgentTriggerStore` create, update and delete now require **EDIT** (was USE) on the agents the
trigger routes to — the stored ones and the new ones. A `PUT` whose body names another intent is a
`400` (MongoDB used to rename the trigger, PostgreSQL kept the row under the old key with the new
intent inside). Create on an existing intent was already refused by both stores (unique index /
primary key); a test now pins that it is not a takeover. The trigger cache is untouched (the NATS PR
owns it).

### F3 — `/v1` had no role gate in OIDC mode

`OpenAiAuthFilter` requires `eddi-admin`, `eddi-editor` or `eddi-user` in `http-policy=authenticated`
mode (with authorization on) and answers `403 insufficient_permissions` otherwise. The default
shared-key `permit` mode is unchanged.

### F4 — team memories readable by naming a group

New [`GroupMemoryAccessGuard`](../../src/main/java/ai/labs/eddi/engine/security/GroupMemoryAccessGuard.java)
for `GET /usermemorystore/memories/{userId}/visible?groupId=` and MCP `get_visible_memories`: USE on
the group under workspaces (as before), and — the gap — with workspaces off the caller must take part
in the group (own one of its group conversations, or be a `HUMAN` member). Admins and auth-off
deployments are unaffected.

### F5 — listings ignoring workspace scope

`GET /deploymentstore/deployments` and `GET /capabilities` (search and skills) list only agents the
caller may USE (the capability filter runs before the selection strategy);
`GET /administration/{env}/deploymentstatus/{agentId}` requires USE; `GET /a2a/agents` requires
`eddi-admin`/`eddi-editor`/`eddi-user` instead of any token; `/.well-known/capabilities` (and
`/skills`) name only A2A-enabled agents — `CapabilityRegistryService` now tracks which registered
agents are `a2aEnabled`.

### F6 — legacy group-conversation delete

`RestGroupConversation` delete and close use `requireOwnerOrAdminStrict`: a record without an owner
is an admin's to delete, not every caller's.

### F7 — role-less tokens reached metrics, OpenAPI and unannotated endpoints

- `quarkus.security.jaxrs.deny-unannotated-endpoints=true`. Audited every resource; the public ones
  now say `@PermitAll` explicitly (SPA shells, `/connections/callback`, the Slack webhook, the `/v1`
  adapter, whose gate is its filter), the per-conversation `/conversationstore` operations require
  any EDDI role on top of the owner check. New `EndpointSecurityAnnotationsTest` walks every compiled
  resource and fails on an unannotated method — it found the `/v1` adapter, which the deny-default
  would otherwise have shut for everyone. The live run found one more: `…/{id}/currentversion` on
  every configuration store is a default method of the `IRestVersionInfo` mixin, and Quarkus takes
  the class-level annotation from the *declaring* type, so the store interface's `@RolesAllowed`
  never applied — it was open to any token, and the deny-default refused it to everybody. Both
  methods now carry `@RolesAllowed({"eddi-admin", "eddi-editor"})`, and the test models the
  declaring-type rule.
- `/q/metrics` defaults to the new named policy `eddi-metrics-reader` (`eddi.metrics.roles-allowed`,
  default `eddi-admin,eddi-metrics`); `authenticated` and `permit` stay selectable through the
  existing `eddi.metrics.http-policy`. **Upgrade note:** a scrape that authenticated without a role
  gets `403` until its service account has the `eddi-metrics` realm role (create it; the shipped realm
  does not define it), or set `EDDI_METRICS_HTTP_POLICY=authenticated`.
- `/openapi`, `/q/openapi`, `/q/swagger-ui` require any EDDI role (`eddi.api-docs.http-policy`,
  `eddi.api-docs.roles-allowed`).
- Helm: only the comment on `eddi.metrics.httpPolicy` in `values.yaml` changed (it named the old
  default). The chart version is deliberately not bumped here: nothing renders differently, and the
  chart's next version is taken by a concurrent change.

### Docs

[`security.md`](../security.md) (permissions table, new "Deny by default" and "Conversations are not
configuration" sections), [`workspaces.md`](../workspaces.md), [`managed-agents.md`](../managed-agents.md),
[`monitoring-guide.md`](../monitoring/monitoring-guide.md), [`open-webui-integration.md`](../open-webui-integration.md),
[`configuration-reference.md`](../configuration-reference.md) (four new properties).

**Platform Operator:** unchanged. Its allow-list holds `PATCH /descriptorstore/descriptors/{id}`
and `GET /administration/{env}/deploymentstatus/{agentId}`; it uses both on configuration and as an
admin, which this change leaves as it was, and its prompt says nothing about metrics, `/v1` roles or
triggers that became wrong.

```decision-log
| 2026-10-02 | Conversations with no owner anywhere are admin-only; the snapshot owner is the fallback | F1: a stripped descriptor opened a conversation to every token | Keeping "unowned = open" for legacy data — the pre-v5.1.6 legacy case records its owner in the snapshot, so the open rule protected nothing real |
| 2026-10-02 | The generic descriptor/sharing API answers for configuration types derived from the IRestVersionInfo stores | F1 | A deny-list of non-config types (breaks silently on the next new type); moving conversation descriptors to their own collection (a data migration on both backends for the same protection) |
| 2026-10-02 | Triggers need EDIT on their targets for create, update and delete | F2 | USE (any chat recipient could re-point); OWN (excludes team editors who legitimately maintain the routing) |
| 2026-10-02 | Metrics default to a role (eddi-admin, eddi-metrics) rather than any token | F7 | Keeping `authenticated` (a role-less token read the exposition); shipping the eddi-metrics role in the realm files (needs three realm copies and a chart bump for an opt-in scrape account) |
```
