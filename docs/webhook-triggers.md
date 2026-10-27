# Webhook Triggers (Signed Inbound Webhooks)

> **Status**: New · **Since**: v6.6.0

A **webhook integration** lets an external system — a GitHub repository, a payment
provider, a monitoring stack, your own service — start an agent turn or a group
discussion by POSTing an event to EDDI. It is a channel integration with
`channelType: "webhook"`, so it reuses everything channels already have: the
`targets` routing table, `defaultTargetName`, vault-backed credentials, and the
Manager's Channels page.

Every request is authenticated by an **HMAC signature** over the raw body, checked
against the one signing secret of the integration it is addressed to, and refused
(401) when anything about it is wrong. There is no EDDI credential on the request —
the signature *is* the credential.

Events can be [CloudEvents 1.0](https://github.com/cloudevents/spec/blob/v1.0.2/cloudevents/bindings/http-protocol-binding.md)
(structured or binary mode) or plain JSON.

## Setup

### 1. Store a signing secret in the vault

The secret is shared between EDDI and the sender. Generate a long random value
(e.g. `openssl rand -hex 32`) and store it:

```bash
curl -X PUT http://localhost:7070/secretstore/secrets/default/github-webhook-secret \
  -H "Content-Type: application/json" \
  -d '{"value":"<the random secret>","description":"GitHub webhook signing secret"}'
```

A webhook integration **must** reference its secret as `${vault:...}`: saving one
without a signing secret, or with a plaintext one, is refused with 400. (A Slack
integration only warns about plaintext, because rejecting it would break
integrations stored before the rule.) The endpoint is public, so a secret readable
from `GET /channelstore/channels/{id}`, a ZIP export or a backup would hand its
authentication to anyone who can read the configuration.

Make the secret long and random (at least 16 characters; 32+ bytes of randomness is
right). The router also enforces this at runtime, for the cases save-time
validation cannot see: a plaintext secret that reached the store through a ZIP
import or a direct write, or a vault value shorter than 16 characters, is not used
- the integration answers `401` to everything and the log carries one `WARN`
naming the integration - until the secret is fixed.
Saving an integration whose vault secret already exists but is shorter than 16
characters is refused with 400 (a secret that is not in the vault yet cannot be
judged at save time, so the router's check is the one that catches it). **Upgrading
from 6.6.0:** an integration whose vault secret is under 16 characters used to
work and now answers `401` to every request; search the log for "signing secret is
shorter than" and rotate that secret to 32 random bytes (and update the sender).

### 2. Create the integration

```bash
curl -X POST http://localhost:7070/channelstore/channels \
  -H "Content-Type: application/json" \
  -d '{
    "name": "GitHub events",
    "channelType": "webhook",
    "platformConfig": {
      "signingSecret": "${vault:github-webhook-secret}",
      "signatureScheme": "github",
      "inputTemplate": "GitHub {event.type} on {event.data.repository.full_name}"
    },
    "targets": [
      { "name": "triage",  "type": "AGENT", "targetId": "<agentId>", "triggers": ["issues", "pull_request"] },
      { "name": "default", "type": "AGENT", "targetId": "<agentId>" }
    ],
    "defaultTargetName": "default"
  }'
```

The response's `Location` header names the new integration's id. Its endpoint is:

```text
POST /integrations/webhook/{integrationId}
```

The Manager shows the full URL and the signing format on the integration's page.
A new integration becomes reachable within a minute (the channel router refreshes
its cache every 60 seconds).

The MCP tool `create_channel_integration` accepts the same body and returns the
endpoint path as `webhookEndpoint`.

### `platformConfig` keys

| Key | Default | Meaning |
| --- | --- | --- |
| `signingSecret` | — (required) | `${vault:...}` reference to the HMAC secret |
| `signatureScheme` | `eddi-v1` | `eddi-v1`, `github` or `generic-hmac` (see below) |
| `signatureHeader` | `X-Signature` | `generic-hmac` only: header carrying the signature |
| `signatureEncoding` | `hex` | `generic-hmac` only: `hex` or `base64` |
| `signaturePrefix` | (none) | `generic-hmac` only: literal prefix before the MAC, e.g. `sha256=` |
| `eventTypeHeader` | `X-GitHub-Event` for `github`, else none | plain JSON: header naming the event type |
| `eventTypePath` | none | plain JSON: JSON pointer to the event type in the body, e.g. `/action` |
| `eventIdHeader` | `X-GitHub-Delivery` for `github`, else `X-Event-Id` | plain JSON: header with the delivery id used for deduplication |
| `inputTemplate` | `event:<type>` | Qute template for the turn's input text, rendered with `event` as its data |
| `conversationStrategy` | `new` | `new` (a conversation per event) or `correlate` |
| `correlationKeyPath` | — | `correlate` only (required there): JSON pointer into the event, e.g. `/data/pull_request/number` |
| `maxDeliveriesPerWindow` | `3000` | Most deliveries this integration may have remembered in one 10-minute window, per node (1 to 100000; a delivery stores up to two replay-protection keys, so the default is 6,000 keys). Over it, new deliveries get a retryable `503` while every other integration is unaffected. Raise it for a genuinely busy sender; see "Where the keys live" |

`channelId` is not used: a webhook integration is addressed by its own id.
`observeMode` targets are refused for this channel type.

## Signature schemes

All schemes compute HMAC-SHA256 over the **raw request body, exactly as sent**;
`eddi-v1` additionally prepends the timestamp and a `.` (`t + "." + body`), so a
sender that signs only the body gets a 401 under that scheme. Comparison is
constant-time.

| Scheme | Header | MAC input | Replay protection |
| --- | --- | --- | --- |
| `eddi-v1` | `X-Eddi-Signature: t=<unix seconds>,v1=<hex>` | `t + "." + body` | timestamp must be within 5 minutes, plus deduplication |
| `github` | `X-Hub-Signature-256: sha256=<hex>` | `body` | deduplication only (by `X-GitHub-Delivery` and by the signature) |
| `generic-hmac` | `<signatureHeader>: <signaturePrefix><hex or base64>` | `body` | deduplication only (by `eventIdHeader` and by the signature) |

`eddi-v1` accepts several `v1=` entries in one header, so a sender can sign with an
old and a new secret while you rotate. `github` and `generic-hmac` sign no
timestamp: a captured request can be replayed once its signature has left the
10-minute deduplication window. Prefer `eddi-v1` for anything you control.

**The routing headers are not signed.** The MAC covers the body (`eddi-v1`: the
timestamp and the body) and nothing else, so the CloudEvents binary-mode headers
(`ce-type`, `ce-id`, ...), `eventTypeHeader` and `eventIdHeader` are whatever the
request carries - a party who can tamper with a request in transit can change what
an event is routed as without breaking the signature. The signature protects who
sent the body, not which route it takes: use HTTPS end to end, and prefer a
sender that puts the type inside the signed body (a CloudEvents *structured*
envelope, or `eventTypePath`). Deduplication cannot be sidestepped with a header,
though: the replay key comes from the MAC, so within the window the same signed
body is a duplicate whatever `ce-type` or `X-GitHub-Event` it arrives with.

### Signing a request (`eddi-v1`)

With curl and openssl:

```bash
SECRET='<the random secret>'
BODY='{"specversion":"1.0","id":"evt-1","type":"com.example.alert.fired","source":"/monitoring","data":{"host":"db1"}}'
T=$(date +%s)
SIG=$(printf '%s.%s' "$T" "$BODY" | openssl dgst -sha256 -hmac "$SECRET" -hex | sed 's/^.* //')

curl -X POST "http://localhost:7070/integrations/webhook/<integrationId>" \
  -H "Content-Type: application/cloudevents+json" \
  -H "X-Eddi-Signature: t=$T,v1=$SIG" \
  --data-binary "$BODY"
```

A signer in Python:

```python
import hashlib, hmac, json, time, urllib.request

def send(url: str, secret: str, event: dict) -> int:
    body = json.dumps(event).encode()
    t = str(int(time.time()))
    sig = hmac.new(secret.encode(), t.encode() + b"." + body, hashlib.sha256).hexdigest()
    req = urllib.request.Request(url, data=body, method="POST", headers={
        "Content-Type": "application/cloudevents+json",
        "X-Eddi-Signature": f"t={t},v1={sig}"})
    return urllib.request.urlopen(req).status
```

Use `--data-binary` (not `-d`) with curl: `-d` strips newlines, and the body EDDI
receives must be byte-for-byte the one you signed.

## Event formats

All three shapes produce the same `event` map, so templates and correlation
pointers do not depend on how the sender delivers.

| Request | Detected by | `event` |
| --- | --- | --- |
| CloudEvents structured | `Content-Type: application/cloudevents+json` | the envelope (`specversion` `1.0`, `id`, `type`, `source` required) |
| CloudEvents binary | a `ce-specversion` header | `ce-*` headers as attributes (`ce-id`, `ce-type`, `ce-source` required), the body as `data` |
| Plain JSON | anything else with a JSON content type | `id` from `eventIdHeader`, `type` from `eventTypeHeader` / `eventTypePath` (else `webhook`), `source` = `webhook:<integrationId>`, the body as `data` |

CloudEvents batch mode (`application/cloudevents-batch+json`) and non-JSON bodies
(e.g. form-encoded) are refused with 415 — configure GitHub to send
`application/json`. Attribute values longer than 256 characters are refused with 400.

## Routing

The event **type** is matched against each target's `triggers`
(case-insensitive): a trigger is an exact type (`pull_request`,
`com.example.alert.fired`) or a prefix ending in `*` (`com.github.*`). An exact
match beats a prefix match, and a longer prefix beats a shorter one. No match falls
back to `defaultTargetName`.

Targets can be **agents** or **groups**. A group target starts a discussion with
the rendered input as its question — group members see only that text, not the
`event` map: put what they need into `inputTemplate`.

## What the agent sees

- **Input text** — `inputTemplate` rendered with the event as its only data, or
  `event:<type>`. The template is author configuration; the event is data — Qute
  substitutes its values and never evaluates them.
- **Context** — the full event as the context value `event` (type `object`), so
  behavior rules, property setters, httpcalls and LLM prompts can read
  `{context.event.data.repository.full_name}` and the like. Every value in it is
  untrusted input from the sender: treat it the way you treat user text.
- **Identity** — turns run as user `webhook:<integrationId>`. No OIDC user or other
  channel can produce that id. Connections scoped `PER_USER` have no human
  principal here and behave as they do for scheduled turns, and long-term
  properties are stored under that user id — shared by every event of the
  integration.

### Correlation

With `conversationStrategy: "correlate"`, events with the same value at
`correlationKeyPath` (e.g. every event of one pull request) share one
conversation per routed agent, so the agent remembers what happened before. The
mapping is keyed by integration, agent and a SHA-256 of the key value. A mapped
conversation that has ended is replaced by a new one; an event without a value at
the pointer gets a new conversation.

## Responses and delivery semantics

| Status | When |
| --- | --- |
| `202` | Accepted: `{"conversationId": "..."}` or `{"groupConversationId": "..."}` |
| `200` | Duplicate: this event was already accepted in the last 10 minutes — `{"duplicate": true, "conversationId": "..."}`, no turn runs |
| `400` | Malformed JSON or CloudEvent |
| `401` | Missing, malformed, stale or wrong signature, the secret cannot be resolved or is unusable (plaintext / too short), **or no integration has this id** - an unknown id is answered exactly like a bad signature, so ids cannot be probed (the server log says which it was). A just-created integration answers 401 for up to a minute until the router has loaded it |
| `409` | Two causes, told apart by the `reason` field of the body: `"in_progress"` - a repeat of an event whose first delivery is still being processed (`{"duplicate": true, "reason": "in_progress"}`, `Retry-After: 5`; retry, it is answered 200 once accepted, or processed if the first was refused); `"awaiting_approval"` - the correlated conversation is waiting for a human approval (no `Retry-After`; it will not clear by retrying until someone decides) |
| `413` | Body larger than `eddi.webhook.max-body-bytes` (default 262144 = 256 KB) |
| `415` | Unsupported content type |
| `422` | No target accepts this event type, or the target cannot run |
| `429` | Tenant quota exceeded (`Retry-After: 60`) |
| `503` | Agent not deployed, node shutting down, quota accounting unavailable, or the integration is over its `maxDeliveriesPerWindow` budget / the replay-protection cache is crowded (`Retry-After: 30`; metric outcome `refused_capacity`) |

The conversation is created, and the turn admitted, **before** the endpoint
answers — that is what lets it return the conversation id, and a `429` while the
sender can still retry. The agent's work itself runs asynchronously on the
conversation coordinator, as for any REST turn. The one exception is a new
conversation's `CONVERSATION_START` turn, which runs inline: keep webhook agents'
start rules light. If the turn is then refused (for instance `429` on the API-call
quota), the conversation just created is ended, so a sender that retries does not
leave one empty conversation behind per attempt (a correlated conversation is kept
- the retry reuses it). Each attempt still spends a conversation slot.

Deduplication keeps its keys for 10 minutes and is keyed twice: by
the event id when there is one, and always by a **replay key derived from the
signature EDDI computed** (not from the header the sender sent, which can be
varied without invalidating it — an extra `v1=` candidate, upper-case hex). A
delivery matching either key is a repeat. The second key matters because an event
id outside a CloudEvents envelope is an unsigned header: GitHub signs only the
body, so a captured request resent with a fresh `X-GitHub-Delivery` would
otherwise run again. The trade-off: two deliveries with byte-identical signed
content inside the window count as one (for `eddi-v1`, only within the same
timestamp second) — give events a distinguishing field if you send identical
payloads on purpose.

**Where the keys live, and what that guarantees.** On a single node (and in
`eddi.messaging.type=in-memory` mode) they are in an in-memory cache shared by
every webhook integration, sized for 100 keys per second across all of them (a
delivery writes up to two keys; that is 50 deliveries per second sustained,
120,000 entries at the 10-minute window with a factor-two head-room). The cache
never evicts a live key to make room, and one integration cannot fill it against
the others, in two layers. First, each integration has its own budget,
`maxDeliveriesPerWindow` (default 3000 deliveries per rolling 10 minutes on a node):
past it, that integration's new deliveries get a retryable `503`. Second, once the
shared cache is half full, only integrations above their fair share of it (half the
cache divided by the number of *active* integrations - those with at least 50
accepted deliveries in the window, so integrations that send one delivery each do
not shrink anyone's share) are refused; an integration with little traffic keeps
being accepted. As a last resort, once the cache is 90% full every new delivery is
refused whatever its integration holds. Only privileged roles (`eddi-admin`,
`eddi-editor`) can create integrations, and each one costs its sender a signing
secret, so the guarantee is: an integration below its fair share is not refused for
another integration's volume until the cache is 90% full (from half full, one above
its fair share can be refused even inside its own budget), and the cache never has
to evict a live key. The window the budget counts over (11 minutes) is longer than the
10-minute key lifetime, so a delivery is counted for as long as its keys exist. Only
accepted (202) deliveries spend budget - a delivery refused with 429, 503 or the like
is given back, so a sender looping on a quota refusal keeps seeing that refusal.
Repeats of remembered deliveries are always answered, and a refused delivery releases
its keys so its retry is processed. Refusing instead of evicting is deliberate - an evicted
key is a signed request that could be replayed. These refusals are counted as
`eddi_webhook_events_total{outcome="refused_capacity"}`, apart from the `503` of an
agent that is not deployed, and logged as a `WARN` at most once a minute per
integration. The budgets and the fair-share check are **per node**: the per-integration
counters are node-local, and in a cluster the cache size the backstop reads is the
node-local view (the shared bucket cannot be counted), so a sender spread over N
nodes may have N times the budget in total, and the backstop triggers on each node's
own traffic. In a
cluster (`eddi.messaging.type=nats`) the keys live in the shared `DEDUP` NATS KV
bucket (the one Slack event de-duplication uses), so a replay is caught on whichever
node it hits; the 10-minute bucket TTL is the window, and the bucket is not size
capped. If NATS is unreachable each node falls back to its own cache until it
returns (so a replay can then run once per node).

Only an accepted (202) event is remembered: a refused one (4xx, 429, 5xx) is
forgotten, so the sender's retry is processed, and a repeat that arrives while the
first delivery is still running gets a retryable 409 rather than a 200 it could
mistake for delivery.

### Quotas

Each event consumes what a REST conversation would: one conversation slot
(`maxConversationsPerDay`) for a new conversation and one API-call slot
(`maxApiCallsPerMinute`) for the turn. Exceeding either answers 429.

## Security notes

- The path `/integrations/webhook/*` is permitted at the HTTP layer (POST only),
  like `/integrations/slack/*`; the resource is `@PermitAll`, and the signature
  check is the authentication. It fails closed — no secret, an unresolvable vault
  reference or any mismatch is a 401.
- The body is read as bytes and capped before anything else happens; nothing is
  parsed before the signature is verified.
- Bodies and signatures are never logged.
- **`github` and `generic-hmac` carry no timestamp.** Their only replay protection
  is the deduplication window — 10 minutes, cluster-wide when NATS is configured: a
  captured request replayed after that runs again. Use `eddi-v1`
  wherever you can configure the sender; its 5-minute timestamp window is
  enforced by the signature itself.
- **Routing headers are outside the signature** - see the scheme table above.
  Terminate TLS end to end and, where you can, route on a field of the signed body.
- Rotate a secret by updating the vault entry; the channel router drops its cache
  when a vault secret changes. With `eddi-v1`, send both signatures during the
  switch.
- An integration's targets require USE access to the target agents and groups when
  it is saved, as for Slack.
