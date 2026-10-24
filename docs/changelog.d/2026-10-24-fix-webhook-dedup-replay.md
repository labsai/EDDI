## 🐛 fix(webhook): the replay-protection cache could evict live keys, was per node, and a few edges leaked or orphaned (2026-10-24)

**Repo:** EDDI (`fix/webhook-dedup-replay`)

### What changed and why

Review of the signed inbound webhook channel (#1059) found that its de-duplication - the only replay protection of the `github` and `generic-hmac` schemes, and a second line for `eddi-v1` - was weaker than documented.

- **Dedup cache sizing.** `webhook-event-dedup` had no size entry in `CacheFactory`, so it inherited the 1,000-entry default and was shared by every webhook integration, each delivery storing up to two keys. About 500 deliveries in ten minutes from any one integration evicted another's keys, after which a captured request ran again. The cache now has its own entry and is rate-sized like the A2A nonce cache (100 keys per second over the 10-minute window, factor-two head-room: 120,000 entries). On top of that the handler **refuses instead of evicting**: once the cache is half full, a delivery that is not already remembered answers a retryable `503` (and logs a `WARN`); repeats of remembered deliveries are still answered. An evicted key is a replayable request, a refused delivery is only a retry.
- **Cluster.** `webhook-event-dedup` joins `slack-event-dedup` in the shared `DEDUP` NATS KV bucket (same 10-minute TTL), so a replay is caught on whichever node it hits. With NATS unreachable each node falls back to its own cache, as for Slack.
- **Unsigned routing headers.** Binary-mode `ce-type`/`ce-id` and the plain-mode event-type header are outside every scheme's MAC. The replay key is already derived from the MAC alone, so within the window the same signed body is a duplicate whatever header it arrives with (now covered by tests at handler and endpoint level); the page now says plainly that routing headers are unsigned, what that means (tampering in transit can re-route a body without breaking the signature) and what to do (TLS, route on a field of the signed body). No wire-protocol change and no new signing scheme.
- **Orphaned conversations.** A `429` (or any refusal) from the turn after `startConversation` left one empty conversation per retried delivery. The conversation just created is now ended when its first turn is refused; a correlated conversation is kept, since the retry reuses it.
- **Weak or plaintext signing secrets.** Save-time validation already requires a vault reference, but a ZIP import or direct store write bypasses it, and no length was checked anywhere. The router now refuses to hand the verifier a plaintext secret or a resolved one shorter than 16 characters (the integration answers `401`, with one `WARN` per integration).
- **Id probing.** An unknown integration id answered `404` before the signature was looked at. It now answers `401` with the same body as a bad signature (the log keeps the distinction). The Slack endpoint likewise answers a uniform `403` for anything it cannot verify.
- **Two meanings of `409`.** The body now carries `reason`: `in_progress` (retry in 5 seconds) or `awaiting_approval` (a human has to decide). The status code is unchanged.
- Group targets still receive only the rendered input, not the event map; documented, not changed.

Files: [`CacheFactory.java`](../../src/main/java/ai/labs/eddi/engine/caching/CacheFactory.java), [`WebhookEventHandler.java`](../../src/main/java/ai/labs/eddi/integrations/webhook/WebhookEventHandler.java), [`RestWebhookChannel.java`](../../src/main/java/ai/labs/eddi/integrations/webhook/rest/RestWebhookChannel.java), [`ChannelTargetRouter.java`](../../src/main/java/ai/labs/eddi/integrations/channels/ChannelTargetRouter.java), [`webhook-triggers.md`](../webhook-triggers.md).

### Design decisions

- No new property: the capacity is derived from the window like the nonce cache's, and the refusal threshold follows from it. A property would let an operator size the cache below the traffic it must hold, which is the defect being fixed.
- The check for a quota refusal before creating the conversation was rejected: the API-call quota has only an atomic acquire, no read-only peek, and adding one would race. Ending the orphan is the safe equivalent (each attempt still spends a conversation slot).
- The shared bucket is not size-capped, so the capacity refusal applies to the node-local view only; in a cluster it triggers earlier than strictly needed, which is the safe direction.

### Tests

- `WebhookEventHandlerTest`: full cache refuses new deliveries and still answers remembered ones; same body under another event type is a duplicate; refused turn ends the new conversation; both `409` reasons.
- `RestWebhookChannelTest`: unknown/malformed id answers identically to a bad signature; rerouted replay is a duplicate; `reason` in the body.
- `CacheFactoryTest`: explicit, window-derived size; shared through the `DEDUP` bucket with the matching TTL.
- `ChannelTargetRouterWebhookTest`: plaintext and too-short secrets are stripped, a long vault secret is kept.

```decision-log
| 2026-10-24 | Webhook dedup refuses (503) at half capacity rather than evicting; shared via the DEDUP bucket in a cluster | The dedup cache is the only replay protection of the github / generic-hmac schemes, and it sat on the 1,000-entry default shared by all integrations | A configurable size property (could be set below the traffic); fail-closed on any eviction (Caffeine cannot report one) |
```
