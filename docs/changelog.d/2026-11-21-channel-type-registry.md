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

### What's next

Phase 0.4–0.5 (inbound origin, routing keys per type) on this branch, then Teams T1 and Discord D1 as their own PRs.
