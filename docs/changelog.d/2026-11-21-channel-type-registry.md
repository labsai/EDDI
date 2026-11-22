## ✨ feat(channels): channel type registry, secret-free channel configs, connection-backed credentials (2026-11-21)

**Repo:** EDDI · **Branch:** `feat/channel-type-registry`

Phase 0.1–0.3 of the Discord / Teams / Outlook / OneDrive umbrella plan. The router was still shaped around Slack, and a second chat adapter built on it could not hold a usable credential. This change is the groundwork; no new channel type ships yet.

### What changed and why

- **0.1 Channel type registry.** A new `ChannelTypeDescriptor` SPI (one CDI bean per type) declares a type's id, its validation, its API origin, whether observe mode makes sense and the keys the router indexes it under. `ChannelTypeRegistry` discovers the beans and refuses to construct (on first use) on a duplicate or non-lower-case id: every channel save then fails, and the router's refresh logs a WARN and routes nothing. `RestChannelIntegrationStore.REGISTERED_CHANNEL_TYPES` is gone. `SlackChannelType`, `WebhookChannelType` and `EmailChannelType` wrap the behaviour that already existed, including the webhook and email validators and the observe-mode refusals, with the same messages.
- **0.2 New types hold no secrets.** A descriptor that says `connectionBacked()` declares the `platformConfig` keys it allows (ids and options only). Save refuses any other key, a plaintext token as much as a `${vault:…}` reference, and the message points to connections. This is an allow-list of non-secret keys, not a list of credential names (saas-connectors plan §12). The router never resolves a vault reference for such a type, and drops undeclared keys at refresh, because a ZIP import or a direct store write never ran save validation. Slack's `CREDENTIAL_KEYS`, `botTokenMayGoToSlack` and `SECRET_PLATFORM_CONFIG_KEYS` stay as legacy paths and do not grow.
- **0.3 Connection-backed credentials.** `ChannelConnectionCredentials` checks at save time that the named connection exists, is bound `SERVICE`, has an accepted auth type, and that its `baseUrlAllowlist` covers the type's API origin. This uses the new `ConnectionResolver.requireServiceTarget`. On every send it resolves the credential with the real target URL and no principal, and it caches nothing.
- `ChannelIntegrationConfiguration` Javadoc no longer suggests `appPassword` / `botToken` keys for Teams and Discord. `docs/connections.md` notes that new channel types are connection-backed.

### Design decisions

- **The legacy types keep accepting unknown keys.** Stored Slack documents carry keys this release does not know, and refusing them on the next update would break integrations that work today. The allow-list applies only to connection-backed types.
- **Email is not declared connection-backed.** It already names a connection, but it keeps its own stricter validator and its deployment checks in `EmailChannelSupport`.
- **Registry fallback for code outside CDI.** The shorter constructors of the router and the store use `ChannelTypeRegistry.builtIns()`, so existing tests and callers are unchanged.

### Tests

New: `ChannelTypeRegistryTest`, `ChannelConnectionCredentialsTest`, `ConnectionResolverServiceTargetTest`, `RestChannelIntegrationStoreChannelTypeTest`, `ChannelTargetRouterChannelTypeTest`, with `FakeChatChannelType` (a Teams-shaped third type). The four new refusals were mutation-checked: reverting each one fails its test.

**Files:** [`ChannelTypeDescriptor.java`](../../src/main/java/ai/labs/eddi/integrations/channels/ChannelTypeDescriptor.java), [`ChannelTypeRegistry.java`](../../src/main/java/ai/labs/eddi/integrations/channels/ChannelTypeRegistry.java), [`ChannelConnectionCredentials.java`](../../src/main/java/ai/labs/eddi/integrations/channels/ChannelConnectionCredentials.java), [`RestChannelIntegrationStore.java`](../../src/main/java/ai/labs/eddi/configs/channels/rest/RestChannelIntegrationStore.java), [`ChannelTargetRouter.java`](../../src/main/java/ai/labs/eddi/integrations/channels/ChannelTargetRouter.java), [`ConnectionResolver.java`](../../src/main/java/ai/labs/eddi/connections/ConnectionResolver.java)

## ✨ feat(channels): inbound origin and routing keys per channel type (2026-11-21)

**Repo:** EDDI · **Branch:** `feat/channel-type-registry`

Phase 0.4–0.5 of the same plan.

### What changed and why

- **0.5 Routing keys per type.** The router indexed only integrations with a non-blank `channelId` (webhooks by resource id as a special case), so a Teams integration for personal chats, which has no channel, would never be routed. Each integration is now indexed under the keys its descriptor returns: `channelId` for Slack, the resource id for a webhook, nothing for email (unchanged), and for a channel-less integration of a type that declares an application id, the namespaced key `@app:<id>`. The scans that look for one integration by name, signing secret or approval channel (`getIntegrationByName`, `getSigningIdentities`, `threadCredentialsForDm`, `resolveDefaultForDm`, `getIntegrationByApprovalChannel`, `hasAnyChannels`) now go through a per-type list of distinct integrations, derived from and memoized on the index snapshot. An integration under two keys is therefore not mistaken for two, which would have made `getIntegrationByName` refuse it as ambiguous. The save-time uniqueness guard compares every routing key of the type (`validateUniqueRoutingKeys`, also run by `duplicateChannel` now), so two channel-less integrations of one application are refused.
- **0.4 Inbound origin.** New `InboundOrigin` record (`channelType`, `integrationName`, `verifiedApplicationId`, `verifiedSigningSecret`, with a redacting `toString`) describes what an adapter's authentication established. New router methods: `resolveDefaultForOrigin(origin, text)`, which picks by name, then by verified application id (fails closed for an unknown one), then the Slack legacy connector by secret; `threadCredentialsForDm(lockedTarget, origin)`, where the integration must match the same secret or the same application id and serve the locked target; and `getIntegrationForApplication(type, appId)`. The Slack-specific `threadCredentialsForDm(type, target, secret)` delegates to the origin overload, so both paths share one comparison.

### Design decisions

- **Additive for Slack.** `SlackEventHandler.EventOrigin` and every Slack call site are unchanged. Slack's tests pin those signatures, and the shared comparison now lives in the router. Teams and Discord construct `InboundOrigin.application(...)` from their verified JWT `aud` and endpoint path.
- **The per-type list is derived, not maintained.** It is rebuilt whenever `integrationMap` is swapped, so it cannot disagree with the index, including in tests that set the map by reflection.
- **An application's DMs belong only to its channel-less integration.** `getIntegrationForApplication` answers only from the `@app:` key, and re-checks that the integration still declares that id. It never falls back to a channel-bound integration that declares the same application. Several may do that (one per Discord channel), and "the lowest-keyed one" could hand one tenant's DM to another tenant's agent and connection. `resolveDefaultForOrigin` returns `null` for an origin it cannot map to an owner.
- **`@app:` keys cannot be reached as channels.** A connection-backed type refuses a `channelId` that starts with `@`. Every channel lookup in the router (`resolveTarget`, `getIntegration`, `integrationFor`, `observeCandidates`, `isChannelOwned`, …) refuses a key in the application namespace, so a channel id read from a request body cannot land on an application's integration.
- **Fixes from the pre-push review.** The uniqueness check computes routing keys without the resource id, so a webhook update no longer runs a scan that could never match and could fail it with a 503. `ChannelTypeRegistry.find` no longer trims, so `" email"` is refused as unknown instead of being validated as email and then never polled.
- **Second review round (16 findings).**
  - **Who may name a connection.** The caller now needs USE access to the connection a channel names (`ResourceAccessGuard.requireUseAccess`, found via `IConnectionStore.idOfName`). A connection that does not exist and one the caller may not use get the same message, so the check is no oracle. Binding, auth-type and allowlist details are shown only after that.
  - **Who owns an application id.** A descriptor can declare `applicationIdIsConnectionClientId()` (Teams will). Its `applicationId` must then equal the connection's OAuth client id (`ConnectionResolver.oauthClientIdOf`). That ties the application to a connection an administrator configured with that client id. It does not prove the client secret, which connection saves never test; the third round below adds an edit-access requirement for the DM route. A type that proves its application another way, such as Discord's public key, checks it in its own `validate`.
  - **Send time re-checks.** `ChannelConnectionCredentials.resolve` goes through the new `ConnectionResolver.resolveService`, which re-checks the `SERVICE` binding and the accepted auth types. A connection switched to `PER_USER` after the save therefore never hands a bound user's token to the chat platform.
  - **Duplicates.** A copy of a connection-backed integration needs edit access to its source, and it is refused if it would become an application's channel-less DM owner.
  - **Routing keys.** Every routing key in the `@` namespace other than the integration's own application key is refused, not just `channelId`. The router reads routing keys only after dropping undeclared keys. Type ids may not contain `:`, and the per-type index takes an integration's type from the integration itself, not from its key.
  - **Origin names.** `resolveDefaultForOrigin` with both a name and a verified application accepts the named integration only if it is that application's.
  - **Save-time allowlist.** A type without a fixed origin still needs a non-empty, https-only `baseUrlAllowlist` at save. A connection without an `authType` is refused, where before it threw a `NullPointerException`.
  - **Smaller fixes.** The registry refuses a connection-backed type whose allow-list lacks `connection`. Workspace connections can be named as `tenant/name`, so the channel core no longer borrows the email model's name parser. The uniqueness 503 says "routing key".
  - **Metrics.** New counters: `eddi_channel_config_rejected_total{type,reason}`, `eddi_channel_config_keys_dropped_total{type}` and `eddi_channel_credential_refused_total{type,reason}`. They are listed in [`metrics.md`](../metrics.md) and charted in one "Channel config refusals" panel of the Full Metrics Reference dashboard (Integrations row). Each is registered under its literal name, so `MetricsDashboardCoverageTest` sees all three.
  - **No code change:** channel integrations are not part of agent ZIP export or import, so only a direct store write can bypass save validation, and the router already strips what such a write leaves.
- **Third review round.** No live bug or regression was found. The fixes close paths that Teams and Discord, the first callers of the origin methods, would otherwise inherit.
  - **An application's DMs, by name or by thread.** "Belongs to the verified application" now means "is that application's channel-less integration", in `resolveDefaultForOrigin` (a name from a request body must name that same integration) and in `threadCredentialsForDm`. Before, a channel-bound integration of the same application qualified: one with a numeric channel id sorted ahead of the `@app:` key, so another tenant's integration could pick up a DM thread.
  - **A bare name proves nothing.** `resolveDefaultForOrigin` resolves a name only together with a verified secret it checks, or a verified application id. `InboundOrigin.isVerified()` no longer counts a name. The two-argument `resolveDefaultForDm(type, text)` ("the first integration of the type") answers nothing for a connection-backed type.
  - **Who may claim an application's DMs.** A save that makes an integration its application's channel-less DM owner needs EDIT access to the connection, not only USE: anyone a connection is shared with may send through it, but must not take over its application's direct messages.
  - **One read per send.** `resolveService` checks the binding and auth type on the same connection document it resolves from, instead of reading it a second time that an edit could change in between. It also refuses a non-https target.
  - **Smaller fixes.** A registry that fails to construct now reaches the router's WARN instead of one DEBUG line per document. A client-id lookup failure at save is reported, not thrown. The dropped-key counter counts once per document version and key, not on every refresh, and the logged key is sanitized. Type tags on the counters are registered ids or `other`. `applicationKey` refuses a blank id. A duplicate of an integration with an application id gets a message that says why it cannot be copied. Stale Javadoc references were fixed.

### Tests

`ChannelTargetRouterChannelTypeTest` covers:
- channel-less routing by DM fallback, by name and by application;
- fail-closed handling of an unknown application, an unverified origin, and a channel-bound application;
- channel lookups that ignore application keys;
- multi-key indexing that is not ambiguous;
- application-scoped thread credentials;
- Slack secret equivalence and redaction.

Twelve mutants across both commits were checked against a no-op control (which survived); each one fails the test written for it. The second review round added 13 more mutants, one per fix, and each is killed as well; so are the 12 of the third round. The 2,355 channel, Slack, webhook, email, connection and alert tests all pass, as do the repo-wide guards and `MetricsDashboardCoverageTest`.

### What's next

Teams T1 and Discord D1, each its own PR. Items 0.6 (`ChatThreadConversations`) and 0.7 (`ChatHitlNotices`) are extracted together with their second consumer (plan §6.1).
