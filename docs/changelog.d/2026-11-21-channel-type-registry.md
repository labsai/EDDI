## ✨ feat(channels): channel type registry, secret-free channel configs, connection-backed credentials (2026-11-21)

**Repo:** EDDI · **Branch:** `feat/channel-type-registry`

Phase 0.1–0.3 of the Discord / Teams / Outlook / OneDrive umbrella plan. The router was still shaped around Slack, and a second chat adapter built on it could not hold a usable credential. This change is the groundwork; no new channel type ships yet.

### What changed and why

- **0.1 Channel type registry.** A new `ChannelTypeDescriptor` SPI (one CDI bean per type) declares a type's id, its validation, its API origin, whether observe mode makes sense and the keys the router indexes it under. `ChannelTypeRegistry` discovers the beans and fails at startup on a duplicate or non-lower-case id. `RestChannelIntegrationStore.REGISTERED_CHANNEL_TYPES` is gone. `SlackChannelType`, `WebhookChannelType` and `EmailChannelType` wrap the behaviour that already existed, including the webhook and email validators and the observe-mode refusals, with the same messages.
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
- **Open for Teams T1:** who may declare an application id. Two tenants' channel-bound integrations can both name app A today, and `requireServiceTarget` does not check who owns the named connection (the same as email). Decide this before an adapter trusts an application id across tenants.

### Tests

`ChannelTargetRouterChannelTypeTest` covers:
- channel-less routing by DM fallback, by name and by application;
- fail-closed handling of an unknown application, an unverified origin, and a channel-bound application;
- channel lookups that ignore application keys;
- multi-key indexing that is not ambiguous;
- application-scoped thread credentials;
- Slack secret equivalence and redaction.

Eight mutants were checked, and each one fails a test. The 970 channel, Slack, webhook, email, connection and alert tests all pass, as do the repo-wide guards (431 tests).

### What's next

Teams T1 and Discord D1, each its own PR. Items 0.6 (`ChatThreadConversations`) and 0.7 (`ChatHitlNotices`) are extracted together with their second consumer (plan §6.1).
