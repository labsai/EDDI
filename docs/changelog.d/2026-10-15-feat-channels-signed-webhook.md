## ✨ feat(channels): signed inbound webhook channel — CloudEvents over HTTP (2026-10-15)

**Repo:** EDDI (`feat/channels-signed-webhook`)

### What changed and why

Agents could react to chat turns and schedules, not to events. A new channel type,
`channelType: "webhook"`, lets an external system (GitHub, a payment provider, a
monitoring stack) start an agent turn or a group discussion by POSTing a signed
event to `POST /integrations/webhook/{integrationId}`. It reuses the channel
machinery rather than adding a trigger system: `ChannelIntegrationConfiguration`,
its `targets` table and `ChannelTargetRouter` (Pillar 3).

- **Authentication is the HMAC signature**, verified over the raw body bytes against
  the addressed integration's one secret, fail-closed. Schemes: `eddi-v1` (default;
  `X-Eddi-Signature: t=…,v1=…`, 5-minute window, several `v1` for rotation),
  `github` (`X-Hub-Signature-256`), `generic-hmac` (header, hex/base64, prefix
  configurable). The HMAC/constant-time/freshness primitives moved into the shared
  `HmacSignatures`; `SlackSignatureVerifier` now uses them (behaviour unchanged).
- **Formats:** CloudEvents 1.0 structured and binary mode, and plain JSON (type
  from a header or JSON pointer, else `webhook`). All three yield the same `event`
  map, which reaches the conversation as the context value `event` — data, never a
  template. The input text is the author's `inputTemplate`, rendered by the
  restricted runtime Qute engine, default `event:<type>`.
- **Routing** by event type against `targets[].triggers` (exact or `prefix*`, most
  specific wins), else the default target; agents and groups.
- **Review fixes:** a repeat delivery that arrives while the first is still
  being processed answers a retryable `409` (`Retry-After: 5`), not `200
  duplicate` — the first may still be refused, and a 200 would have lost the
  event; only a recorded `202` makes a repeat a duplicate. `HmacSignatures.isFresh`
  is overflow-safe (`subtractExact`/`absExact`; a timestamp of
  `Long.MIN_VALUE + now` used to read as fresh — also used by Slack). Events
  without an id are deduplicated on the signature value for every scheme, not only
  `eddi-v1`: for `github` and `generic-hmac`, which sign no timestamp, that window
  is the only replay protection, as the docs now say.
- **PR review (#1059):** deduplication is keyed by the event id AND by a canonical
  replay key the verifier derives from the MAC it computed (never from the sender's
  header — an extra `v1=` candidate or upper-case hex no longer yields a fresh key),
  so a captured request replayed under a new, unsigned delivery id is a duplicate.
  Trade-off, documented: byte-identical signed deliveries within the window count as
  one. Docs separate `eddi-v1`'s `t + "." + body` MAC input from the body-only
  schemes; the Manager labels the create-dialog secret field, names `X-Signature` as
  the generic-HMAC default, and promises stale-request refusal only for `eddi-v1`.
- **Conversations:** `new` per event, or `correlate` — one conversation per value at
  `correlationKeyPath`, mapped through `IUserConversationStore` like Slack threads.
- **Identity:** turns run as `webhook:<integrationId>`.
- **Delivery:** 202 with the conversation id; repeated event id → 200 duplicate
  (10-minute Caffeine cache, released when an event is refused so retries work);
  413 above 256 KB (`eddi.webhook.max-body-bytes`); 429 + `Retry-After` on tenant
  quota; 503 when the agent is not deployed.
- **Validation:** a webhook integration must have `platformConfig.signingSecret`,
  and it must be a `${vault:…}` reference (400 otherwise); unknown schemes,
  non-pointer paths, `correlate` without a key path and `observeMode` targets are
  refused.
- HTTP policy `quarkus.http.auth.permission.webhook` (POST, permit) plus
  `@PermitAll` on the resource, as for Slack.
- Manager: `webhook` in the channel type list, a create-dialog step and detail-page
  section for its settings, the endpoint URL and the signing format (11 locales).
  MCP `create_channel_integration` documents the type and returns
  `webhookEndpoint`. Platform Operator prompt knows the type (revision bump).
- Docs: new [`webhook-triggers.md`](../webhook-triggers.md).

### Design decisions

- **Quota-consuming steps run before the response.** `startConversation` and
  `say` are called on the request thread: `say` only admits the turn and queues it
  on the conversation coordinator, so the agent's work is still asynchronous, but
  the 202 can carry the conversation id and a quota refusal can still be a 429. A
  separate executor (Slack's pattern) would have made the 429 unreportable. Cost:
  a new conversation's `CONVERSATION_START` turn runs inline — documented.
- **Body as bytes, not `String`.** The MAC covers what the sender signed; a charset
  round trip can alter invalid UTF-8. The body is read with a hard cap before any
  verification; nothing is parsed before the signature passes.
- **Webhook integrations are keyed by their resource id** in the router (no
  `channelId`), so the URL needs no extra configuration and cannot collide.
- **Signing secret must be a vault reference** — the endpoint is public, and a
  plaintext secret is returned by `GET /channelstore/channels/{id}` and exported.
  Slack only warns, for back-compat; the new type has none to keep.
- **Not reused:** `TurnIdempotencyService` is per-conversation and the event has no
  conversation yet; deduplication uses the cache factory, like Slack.

### Not done / next

- Deduplication is per node; a NATS cluster could run a replayed event once per node.
- A new integration is reachable only after the router's 60-second refresh.

**Files:** [`RestWebhookChannel.java`](../../src/main/java/ai/labs/eddi/integrations/webhook/rest/RestWebhookChannel.java),
[`WebhookEventHandler.java`](../../src/main/java/ai/labs/eddi/integrations/webhook/WebhookEventHandler.java),
[`WebhookEventParser.java`](../../src/main/java/ai/labs/eddi/integrations/webhook/WebhookEventParser.java),
[`WebhookSignatureVerifier.java`](../../src/main/java/ai/labs/eddi/integrations/webhook/WebhookSignatureVerifier.java),
[`HmacSignatures.java`](../../src/main/java/ai/labs/eddi/integrations/channels/HmacSignatures.java),
[`WebhookChannelSettings.java`](../../src/main/java/ai/labs/eddi/configs/channels/model/WebhookChannelSettings.java),
`ChannelTargetRouter`, `RestChannelIntegrationStore`, `SlackSignatureVerifier`, `McpAdminTools`,
`application.properties`, Manager channel pages + `webhook-settings.tsx`, operator prompt.

```decision-log
| 2026-10-15 | Webhook channel starts the conversation and admits the turn on the request thread | 202 must carry the conversation id and quota refusals must be a 429 | A separate executor like Slack's (429 unreportable), a non-consuming quota pre-check (none exists; racy) |
```
