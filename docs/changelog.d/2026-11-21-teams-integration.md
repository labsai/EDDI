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
  New descriptor hook `requiredConnectionIdentity` and `ConnectionResolver.requireServiceIdentity` /
  `proveServiceIdentity`: the connection's `oauth.clientId` must equal `appId`, its `tokenUrl` must be exactly
  `https://login.microsoftonline.com/<tenantId>/oauth2/v2.0/token` (a discovered or substituted endpoint could "mint"
  for any client id), its scopes must contain `https://api.botframework.com/.default`, and the save obtains one token.
  The cheap part is re-checked on every inbound activity (`ChannelConnectionCredentials.holdsRequiredIdentity`),
  because imports and direct writes skip save validation.
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
  every route must belong to that same integration. Welcome on `conversationUpdate`. Group targets and HITL cards
  answer with a notice for now.
- **`TeamsConnectorClient`** over `OutboundHttp` (STRICT SSRF, no redirects): token from
  `ChannelConnectionCredentials.resolve(integration, activityUri)` per attempt, pacing per conversation (7/1 s, 8/2 s,
  60/30 s, 1800/h; waits up to 30 s, refuses beyond), backoff on 429/412/502/504 (2–20 s, ±20 % jitter, three
  retries, `Retry-After` honoured), 40 000 UTF-16-unit chunks (~80 KB), `textFormat: extendedmarkdown`. Limits and
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
- Docs: new [`teams-integration.md`](../teams-integration.md) and SUMMARY entry. MCP `create_channel_integration`
  describes the type. Manager: `teams` in the Channels create dialog and detail page (fields, messaging endpoint,
  app-package download), 13 `teamsChannel.*` keys in all 11 locales. Platform Operator prompt learns the type; revision
  bumped.

### Design decisions

- **Routing keys follow the Phase 0 default** (`conversationId`, else the app key), not "both": indexing both would
  make the uniqueness guard refuse a second channel-bound integration of the same app.
- **Connection ownership is still open.** The proof shows the saver can use the app's credential, not that they own
  the named connection; connections are not workspace-scoped. Documented on the docs page.
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
