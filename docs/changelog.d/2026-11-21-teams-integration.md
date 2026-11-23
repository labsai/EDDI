## ✨ feat(teams): Microsoft Teams channel, tier 1 (2026-11-21)

**Repo:** EDDI · **Branch:** `feat/teams-integration` (stacked on `feat/channel-type-registry`; PR it after that one merges)

Plan phase T1 of the Discord / Teams / Outlook / OneDrive umbrella plan: a Teams bot that answers in personal chats,
group chats and channel threads, built on the Phase 0 channel-type core. No Bot Framework SDK.

### What changed and why

- **`TeamsChannelType`** (`channelType: "teams"`), connection-backed. Keys `appId`, `tenantId`, `connection`,
  `allowedTenantIds`, `conversationId`, `hitlApprovalConversationId`, `hitlApproverAadObjectIds`, all ids validated as
  GUIDs or `19:` conversation ids. Routed by `conversationId` when set, otherwise by `@app:<appId>`. Observe mode is
  off. Accepted connection auth: `OAUTH2_CLIENT_CREDENTIALS` only (with a secret or a certificate via
  `private_key_jwt`); `OAUTH2_JWT_BEARER` is the RFC 7523 §2.1 grant Entra issues no app token for.
- **Who may declare an app id** (the decision Phase 0 left open; the user chose "client id match plus token proof").
  The core's review rounds (merged into this branch) already require the connection's `oauth.clientId` to equal the
  app id (`applicationIdIsConnectionClientId`, which Teams sets; the comparison now ignores case, the ids being GUIDs),
  use access to the connection, and edit access for a channel-less integration. On top of that, the new descriptor hook
  `requiredConnectionIdentity` with `ConnectionResolver.requireServiceIdentity` / `proveServiceIdentity` requires a
  `tokenUrl` of exactly `https://login.microsoftonline.com/<tenantId>/oauth2/v2.0/token` (a discovered or substituted
  endpoint could "mint" for any client id) and the scope `https://api.botframework.com/.default`, and the save obtains
  one token: proof that the saver's connection holds the app's secret or certificate (reason `identity_not_proven` on
  `eddi_channel_config_rejected_total`). The cheap part is re-checked on every inbound activity
  (`ChannelConnectionCredentials.holdsRequiredIdentity`, reason `identity_mismatch` on
  `eddi_channel_credential_refused_total`), because imports and direct writes skip save validation.
- **`TeamsTokenVerifier` / `TeamsSigningKeys`**: full Bot Connector JWT validation, hand-written on the JDK: RS256
  only (checked from the header before any key lookup), `kid` required, RSA keys of 2048 bits or more from the
  OpenID metadata, `jwks_uri` pinned to `https://login.botframework.com`, 24-hour refresh, one single-flight refetch a
  minute for an unknown `kid` (global, as it is reachable before authentication), old keys kept on a failed refresh,
  `iss`, `aud` (a configured app id), `exp` required, `nbf`, 5 minutes of skew, `serviceurl` equal to the activity's,
  key endorsed for `msteams`. No switch disables a check.
- **`RestTeamsWebhook`** (`POST /integrations/teams/messages`): 256 KB body cap, verify, de-duplicate by app id +
  conversation + activity id in the new shared cache `teams-activity-dedup` (`CacheFactory.TEAMS_ACTIVITY_DEDUP`,
  bucket `DEDUP`; `SHARED_BUCKETS` became `Map.ofEntries`, having reached 11 entries), 200 at once, async dispatch.
  **`TeamsWebhookAuthMechanism`**: the Bot Connector sends `Authorization: Bearer`, which proactive OIDC would reject
  before the `permit` policy is consulted; for exactly that path and method it yields the anonymous identity, like
  `OpenAiPermitModeAuthMechanism`.
- **`TeamsActivityHandler`**: integration = the one bound to the channel and declaring the verified `aud`, else the
  app's channel-less one; tenant must be `tenantId` or in `allowedTenantIds` (fail closed, a missing tenant included);
  connection identity re-checked; own messages ignored; `<at>` mentions of the bot only are stripped; routing through
  `resolveTarget` / `resolveDefaultForOrigin(InboundOrigin.application("teams", aud))` / `threadCredentialsForDm`, and
  every route must belong to that same integration. Welcome on `conversationUpdate`; the bot in `membersRemoved`
  deletes the conversation reference. When `channelData.tenant.id` and `conversation.tenantId` are both present and
  disagree, the activity has no tenant and is refused. A targeted (private) activity (`recipient.isTargeted`) is
  ignored rather than answered for the whole channel. Group targets and HITL cards answer with a notice for now.
- **`TeamsMetrics`**: `eddi_teams_requests_refused_total{reason}` (the failed token check) and
  `eddi_teams_activities_total{outcome}` (`handled`, `duplicate`, `no_integration`, `tenant_refused`,
  `identity_mismatch`, `targeted_ignored`, `bot_removed`), both with fixed tag values.
- **`TeamsConnectorClient`** over `OutboundHttp` (STRICT SSRF, no redirects): token from
  `ChannelConnectionCredentials.resolve(integration, activityUri)` per attempt, pacing per conversation (7/1 s, 8/2 s,
  60/30 s, 1800/h; waits up to 30 s, refuses beyond), backoff on 429/412/502/504 (2–20 s, ±20 % jitter, three
  retries, `Retry-After` honoured), 20 000 UTF-16-unit chunks (under 80 KB), `textFormat: extendedmarkdown`. Limits and
  format verified against Microsoft's rate-limit and formatting pages on 2026-11-21.
- **`TeamsConversationReferences`**: `ITeamsConversationReferenceStore` with Mongo and Postgres twins (producer in
  `DataStoreProducers`), and `TeamsReferenceGdprParticipant` (a personal chat's row names its user).
- **`TeamsUserIdentity`**: `teams:<tenantId>:<aadObjectId>`, both GUID-validated before joining. The brief asked for
  "provenance CHANNEL"; no such provenance exists (`ResolutionPrincipal.Provenance` is VERIFIED / SELF_ASSERTED), so
  these conversations are not verified principals, like Slack's.
- **`ChatThreadConversations`** (plan item 0.6): the thread → conversation mapping with ended-conversation replacement
  and the create race, extracted for Teams. Slack keeps its own copy (it adds legacy id adoption); moving it is a
  follow-up.
- **`RestTeamsAppPackage`** (`GET /channelstore/teams/{id}/app-package`, admin/editor, read through the channel store
  so workspace access applies): manifest 1.29 (newest GA; 1.30 is published), bot scopes personal/team/groupChat,
  generated colour and outline icons.
- `ChannelTargetRouter.declaresApplication` (an app id any integration declares, so a token for an unknown audience is
  refused early). `ChannelTypeRegistry.builtIns()` includes Teams.
- **[`microsoft-365.md`](../microsoft-365.md)** (plan §7.3): the one Entra setup page — app registration with a
  certificate (`private_key_jwt`) by default and a client secret as the last resort, one app per feature, each feature
  row least privilege first with a negative test, the Exchange RBAC-for-Applications steps for Graph mail
  (`New-ServicePrincipal`, `New-ManagementScope`, `New-ManagementRoleAssignment`, `Test-ServicePrincipalAuthorization`),
  national clouds, and the Keycloak mappers for Entra `oid`. The Teams and Email pages link to it.
- Docs: new [`teams-integration.md`](../teams-integration.md) and SUMMARY entries. MCP `create_channel_integration`
  describes the type. Manager: `teams` in the Channels create dialog and detail page (fields, messaging endpoint,
  app-package download), 13 `teamsChannel.*` keys in all 11 locales. Platform Operator prompt learns the type; revision
  bumped.

### Fixes from the local review

- **The proof of possession never uses a stored grant.** A grant is keyed by the connection's name, not its client
  id or endpoint, and survives an edit of either, so a connection re-pointed at another app's client id would have
  "proven" that app with its own cached token. New `AccessTokenSupplier.freshServiceToken` (implemented by
  `OAuthTokenService`, refusing by default) mints once without touching the grant store.
- **The per-activity check now needs a proof too**, not only matching strings: `requireProvenServiceIdentity`
  remembers a proof for 6 hours under a SHA-256 fingerprint of the connection's identity fields (client id, token URL,
  scopes, auth method, secret/key/thumbprint references), proves again after any edit, and remembers a failure for
  5 minutes. So an imported integration plus connection naming another app's client id with an invented secret no
  longer receives that app's messages. A Teams integration too incomplete to name its identity holds none.
- Thread-lock keys carry the app id, so two bots in one conversation keep separate locks.
- Chunks are 20,000 UTF-16 units, under 80 KB however Teams counts bytes; a failed delivery posts a short notice
  instead of failing silently.
- An absurd signed `exp` / `nbf` is clamped instead of becoming a 500; empty app-package parameters fall back to their
  defaults and a missing `version` is a 400.
- Docs: the Keycloak user-profile step (unmanaged attributes are dropped since Keycloak 24), the `oid` mapping
  described as groundwork, sources for the 2027 file-token rule and the archived Emulator, GCC's different service
  URL, and per-deployment uniqueness of `conversationId`.

### Design decisions

- **Routing keys follow the Phase 0 default** (`conversationId`, else the app key), not "both": indexing both would
  make the uniqueness guard refuse a second channel-bound integration of the same app.
- **Connection access comes from the core**: use access to name a connection, edit access to claim an app's direct
  messages. The token proof adds possession of the credential, which access checks cannot show.
- **The app package uses the bot's app id as the manifest id** and is served at `/channelstore/teams/{id}/app-package`,
  not a generated-and-stored manifest id at `/channelstore/channels/{id}/teams-app-package` as plan §8.2 sketches: a
  stored id needs a new `platformConfig` key, and a second resource under `/channelstore/channels` would share the
  channel store's root path. Prompt starters and RSC permissions come with observe mode (T2).
- **The app-package endpoint is not on the public path**: `/integrations/teams/*` is not permitted wholesale, only
  `POST /integrations/teams/messages`.

### What's next

- Test against a real Azure Bot through a tunnel (not done here).
- Teams HITL approval cards (`hitlApprovalConversationId`, `hitlApproverAadObjectIds`) and group-discussion targets.
- Move `SlackEventHandler` onto `ChatThreadConversations`; extract `ChatHitlNotices` (0.7); the Teams handler borrows
  `SlackHitlSupport.extractSlackResponseText` until then.

**Files:** [`TeamsChannelType.java`](../../src/main/java/ai/labs/eddi/integrations/teams/TeamsChannelType.java),
[`TeamsTokenVerifier.java`](../../src/main/java/ai/labs/eddi/integrations/teams/TeamsTokenVerifier.java),
[`TeamsSigningKeys.java`](../../src/main/java/ai/labs/eddi/integrations/teams/TeamsSigningKeys.java),
[`TeamsActivityHandler.java`](../../src/main/java/ai/labs/eddi/integrations/teams/TeamsActivityHandler.java),
[`TeamsConnectorClient.java`](../../src/main/java/ai/labs/eddi/integrations/teams/TeamsConnectorClient.java),
[`RestTeamsWebhook.java`](../../src/main/java/ai/labs/eddi/integrations/teams/rest/RestTeamsWebhook.java),
[`ConnectionResolver.java`](../../src/main/java/ai/labs/eddi/connections/ConnectionResolver.java),
[`ChatThreadConversations.java`](../../src/main/java/ai/labs/eddi/integrations/channels/ChatThreadConversations.java)
