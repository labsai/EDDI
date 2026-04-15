# Slack Integration

> **Status**: Production-ready · **Since**: v6.0.0

EDDI's Slack integration enables conversational AI agents — including multi-agent group discussions — to operate natively in Slack channels. It supports 1:1 agent conversations, live-streamed panel discussions with multiple agents, and context-aware threaded follow-ups.

## Quick Setup

### 1. Create a Slack App

1. Go to [api.slack.com/apps](https://api.slack.com/apps) → **Create New App**
2. Choose **From a manifest** or **From scratch**

### 2. Configure OAuth & Permissions

Add these **Bot Token Scopes**:

| Scope | Purpose |
|-------|---------|
| `chat:write` | Post messages to channels |
| `app_mentions:read` | Respond to @mentions |
| `channels:read` | Read channel metadata |
| `im:read` | Read direct messages |

### 3. Enable Event Subscriptions

1. Go to **Event Subscriptions** → Enable
2. Set the **Request URL** to: `https://<your-eddi-host>/integrations/slack/events`
3. Subscribe to **Bot Events**:
   - `app_mention` — triggers when the bot is @mentioned
   - `message.im` — triggers on direct messages

### 4. Install to Workspace

1. Go to **Install App** → **Install to Workspace**
2. Copy the **Bot User OAuth Token** (starts with `xoxb-`)
3. Copy the **Signing Secret** from **Basic Information**

### 5. Enable Slack in EDDI

Set the master toggle (environment variable or `application.properties`):

```properties
eddi.slack.enabled=true
```

This is the only server-level setting. All credentials are configured per-agent.

### 6. Store Credentials in Vault

Store your Slack credentials in EDDI's Secrets Vault:

```bash
# Via REST API
curl -X POST http://localhost:7070/secretstore/keys \
  -H "Content-Type: application/json" \
  -d '{"keyName":"slack-bot-token","secretValue":"xoxb-your-token-here"}'

curl -X POST http://localhost:7070/secretstore/keys \
  -H "Content-Type: application/json" \
  -d '{"keyName":"slack-signing-secret","secretValue":"your-signing-secret"}'
```

### 7. Configure Channel Mapping on Your Agent

Add a `ChannelConnector` to your agent configuration:

```json
{
  "channels": [
    {
      "type": "slack",
      "config": {
        "channelId": "C0123ABCDEF",
        "botToken": "${eddivault:slack-bot-token}",
        "signingSecret": "${eddivault:slack-signing-secret}",
        "groupId": "optional-group-id-for-discussions"
      }
    }
  ]
}
```

The `channelId` is the Slack channel ID (find it in Slack by right-clicking a channel → **View channel details** → copy the ID at the bottom).

> **Multi-workspace**: Each agent can use different bot tokens and signing secrets, allowing a single EDDI instance to serve multiple Slack workspaces.

---

## Architecture

```
Slack Workspace(s)                       EDDI Cluster
─────────────────                        ─────────────────────────
┌─────────────┐   Events API (HTTPS)    ┌─────────────────────┐
│ Slack App    │ ───────────────────────→│ RestSlackWebhook    │
│ (per agent)  │                         │   ├─ Try all secrets │
└─────────────┘                         │   └─ Dedup events   │
                                        └──────────┬──────────┘
                                                   │ async
                                        ┌──────────▼──────────┐
                                        │ SlackEventHandler    │
                                        │   ├─ Route to agent  │
                                        │   ├─ Detect group:   │
                                        │   └─ Per-agent token │
                                        └──────────┬──────────┘
                                                   │
                              ┌─────────────────────┼──────────────────┐
                              ▼                     ▼                  ▼
                    ┌─────────────────┐   ┌────────────────┐  ┌───────────────┐
                    │ ConversationSvc │   │ GroupConvSvc   │  │ SlackWebAPI   │
                    │ (1:1 agent)     │   │ (multi-agent)  │  │ (post msgs)   │
                    └─────────────────┘   └────────────────┘  └───────────────┘
```

### Key Components

| Component | Responsibility |
|-----------|---------------|
| `RestSlackWebhook` | JAX-RS endpoint, multi-secret signature verification, URL challenge, event dispatching |
| `SlackSignatureVerifier` | HMAC-SHA256 verification with multi-secret support and 5-minute replay protection |
| `SlackEventHandler` | Core event logic: message routing, group triggers, follow-up detection, per-agent bot tokens |
| `SlackChannelRouter` | Maps Slack channels → agents with full credential resolution (vault-backed) |
| `SlackGroupDiscussionListener` | Streams multi-agent discussions into Slack with two UX modes |
| `SlackWebApiClient` | Minimal HTTP client for `chat.postMessage` |

### Credential Flow

All credentials live in the agent's `ChannelConnector.config` map:

```
Agent Config → ChannelConnector.config
  ├─ botToken: "${eddivault:slack-bot-token}"
  └─ signingSecret: "${eddivault:slack-signing-secret}"
        │
        ▼
SlackChannelRouter (60s cache refresh)
  ├─ SecretResolver resolves vault references
  ├─ channelId → SlackCredentials (agentId, botToken, signingSecret, groupId)
  └─ allSigningSecrets set (for webhook verification)
        │
        ├──→ RestSlackWebhook: verify(signature, allSigningSecrets)
        └──→ SlackEventHandler: postMessage(resolvedBotToken, ...)
```

---

## Features

### 1:1 Agent Conversations

@mention the bot in a channel or send a direct message:

```
@EDDI What's our Q4 revenue forecast?
```

The bot responds in a thread under the user's message.

### Multi-Agent Group Discussions

Trigger with the `group:` prefix:

```
@EDDI group: Should we adopt microservices for the payment system?
```

All configured agents in the group participate in a live panel discussion.

#### UX Modes

The UX mode is chosen automatically based on the discussion style:

| Discussion Style | UX Mode | Behavior |
|-----------------|---------|----------|
| `ROUND_TABLE` | **Compact** | All messages in a single thread |
| `DELPHI` | **Compact** | All messages in a single thread |
| `PEER_REVIEW` | **Expanded** | Each agent posts at channel level; peer feedback is threaded under the target |
| `DEVIL_ADVOCATE` | **Expanded** | Same as PEER_REVIEW |
| `DEBATE` | **Expanded** | Same as PEER_REVIEW |

**Compact mode** keeps things tidy — all contributions in one thread:
```
User: @EDDI group: What's important?
  └─ 🗣️ Panel Discussion (ROUND TABLE)
  └─ 💬 Alice: I think...
  └─ 💬 Bob: My view is...
  └─ 📋 Synthesis (by Moderator): ...
```

**Expanded mode** gives each agent a channel-level post. Peer feedback threads under the target agent's post:
```
User: @EDDI group: Should we rewrite?
💬 Alice: I believe we should...
  └─ 🔍 Bob → Alice: I disagree because...
  └─ 🔍 Carol → Alice: I agree, and also...
💬 Bob: My position is...
  └─ ✏️ Bob (revised): After hearing feedback...
📋 Synthesis (by Moderator): ...
```

### Context-Aware Follow-ups

After an expanded-mode discussion, users can reply in an agent's thread to ask follow-up questions:

```
Alice's original post: "I believe microservices would help..."
  └─ Bob → Alice: "I disagree because..."
  └─ User: "Alice, can you address Bob's concerns?"
  └─ Alice: [responds with full context of the discussion + peer feedback]
```

The follow-up system:
1. Detects the thread reply is under an agent's message
2. Retrieves the agent's discussion context (contribution + feedback received)
3. Injects that context into the prompt
4. Routes to the correct agent for a contextual response

---

## Enterprise & Clustering

### Multi-Workspace Support

Each agent can connect to a different Slack workspace by using different bot tokens and signing secrets. The `SlackChannelRouter` caches all credentials and the `SlackSignatureVerifier` tries all known signing secrets during webhook verification.

### Retry Logic

All Slack API calls use **exponential backoff** (3 attempts, 500ms/1s/2s base). Failed messages are logged but don't crash the event handler.

### Event Deduplication

Slack retries webhook deliveries on timeout. EDDI uses an in-memory cache (`ICache`) to deduplicate events by `event_id`, preventing duplicate processing.

### Follow-up Memory Management

Active group discussion contexts use EDDI's `ICache` infrastructure with **TTL-based expiration** (2 hours for group listeners, 10 minutes for event dedup). This prevents unbounded memory growth from long-lived discussions.

### Thread Safety

- `SlackChannelRouter` uses volatile reference swaps with an `AtomicBoolean` refresh gate — no thundering herd on cache expiry
- Event processing runs on virtual threads — non-blocking, scales to thousands of concurrent events
- The `CountDownLatch` in `SlackGroupDiscussionListener` signals completion cleanly without polling

### Cluster Considerations

When running EDDI as a multi-instance cluster behind a load balancer:

1. **Webhook Delivery**: Slack sends each event to ONE URL. The load balancer routes to one EDDI instance. Event dedup is per-instance (ICache), which is fine — Slack only delivers to one endpoint.

2. **Conversation State**: Conversations are stored in MongoDB, so any instance can handle follow-up messages. The `IConversationService` load-balances naturally.

3. **Group Discussion Affinity**: A group discussion runs on the instance that received the trigger. Since the `SlackGroupDiscussionListener` streams directly to Slack API, this is instance-local and correct. Follow-up context is cached per-instance in ICache — if a follow-up routes to a different instance, it gracefully falls back to a standard conversation (no context injection, but no error).

4. **NATS Integration**: When `eddi.messaging.type=nats`, conversation processing is ordered via NATS JetStream subjects. The Slack webhook handler still handles event dispatch locally (Slack only talks to one instance), but conversation execution benefits from NATS-backed ordering, retry (3 attempts), and dead-letter queuing.

---

## Configuration Reference

### Server-Level

| Property | Default | Description |
|----------|---------|-------------|
| `eddi.slack.enabled` | `false` | Master toggle — infrastructure-level kill switch |

### Per-Agent ChannelConnector Config

Configure on each agent's `channels[]` array:

```json
{
  "type": "slack",
  "config": {
    "channelId": "C0123ABCDEF",
    "botToken": "${eddivault:slack-bot-token}",
    "signingSecret": "${eddivault:slack-signing-secret}",
    "groupId": "optional-group-id"
  }
}
```

| Key | Required | Description |
|-----|----------|-------------|
| `channelId` | ✅ | Slack channel ID (e.g., `C0123ABCDEF`) |
| `botToken` | ✅ | Bot User OAuth Token (`xoxb-...`). Use vault reference. |
| `signingSecret` | ✅ | Slack Signing Secret for request verification. Use vault reference. |
| `groupId` | ❌ | Group config ID for multi-agent discussions |

---

## Retry & Error Handling

### Retry Policy

All outgoing Slack API calls (`chat.postMessage`) use exponential backoff:

| Attempt | Backoff | Cumulative Wait |
|---------|---------|----------------|
| 1 | 0ms (immediate) | 0ms |
| 2 | 500ms | 500ms |
| 3 | 1000ms | 1500ms |

Only **retryable failures** trigger retry:
- HTTP 429 (Rate Limited)
- HTTP 500, 502, 503, 504 (Server Error)
- Network errors (connection refused, timeout, DNS failure)

**Non-retryable failures** (HTTP 200 + `ok:false`) are logged and skipped:
- `channel_not_found` — bot not in channel
- `invalid_auth` — bad token
- `not_in_channel` — bot not invited

### What Happens After Retry Exhaustion

After 3 failed attempts, the message is **permanently lost from the user's perspective**. The system:

1. Logs a structured error for operator alerting:
   ```
   SLACK_DELIVERY_FAILED | channel=C0123 | threadTs=12345.000 | textLength=450 | attempts=3 | error=...
   ```
2. The agent's response **still exists in conversation memory** (MongoDB). Operators can manually retrieve it via the conversation API.
3. The user sees no response in Slack — they can try sending the message again.

**Recommended monitoring**: Set up a log alert for `SLACK_DELIVERY_FAILED` in your observability stack (Grafana, Datadog, etc.) to catch delivery failures.

### Group Discussion Resilience

During a multi-agent group discussion, individual Slack post failures do **not** abort the discussion. The `SlackGroupDiscussionListener` uses a fire-and-forget wrapper (`postSafe`) that catches delivery exceptions and continues. Users may see a missing agent contribution, but the discussion completes and synthesis is delivered.

---

## Troubleshooting

### Bot doesn't respond to @mentions

| Check | Fix |
|-------|-----|
| `eddi.slack.enabled=true` ? | Set in `application.properties` or env var |
| Bot token configured? | Check agent's ChannelConnector config — `botToken` should reference a vault key |
| Bot in channel? | Invite the bot to the channel in Slack |
| Event subscription active? | Check **Event Subscriptions** in Slack app settings |
| Request URL verified? | Slack must have verified `https://<host>/integrations/slack/events` |
| EDDI accessible? | The URL must be publicly reachable (or via tunnel for dev) |
| Channel mapped? | Check the agent's `ChannelConnector` has the correct `channelId` |
| Signing secret set? | Without a signing secret, webhook verification fails (HTTP 403) |

### Signature verification fails (HTTP 403)

| Check | Fix |
|-------|-----|
| Signing secret correct? | Copy from **Basic Information** in Slack app settings, store in vault |
| Clock drift? | Timestamp validation uses 5-minute window — sync clocks |
| Reverse proxy stripping body? | The raw body must reach EDDI unchanged for HMAC verification |
| No agents configured? | At least one deployed agent must have a Slack ChannelConnector with `signingSecret` |

### `No agent configured for this channel`

The bot responds but says no agent is mapped. Add a `ChannelConnector` with the correct `channelId` to your agent config.

### `No group configured for this channel`

Triggered by `group:` prefix but no group mapped. Add `groupId` to the channel's `ChannelConnector` config.

### Messages appear duplicated

Slack retries events up to 3 times if it doesn't receive HTTP 200 within 3 seconds. EDDI deduplicates by `event_id` using an in-memory cache (TTL: 10 minutes). If you see duplicates:
- Check EDDI response time — if pipeline processing blocks the webhook endpoint, Slack will retry
- The webhook endpoint responds immediately (async processing) — if you see slow responses, check network/proxy latency

### Group discussion times out

The `registerAgentThreadMappings` task waits up to 300 seconds. If the group discussion takes longer:
- Check agent LLM response times
- Consider using fewer agents or simpler discussion styles

---

## Building Custom Channel Integrations

This section is a guide for developers building integrations for other platforms (Teams, Discord, Telegram, etc.) based on lessons learned from the Slack implementation.

### Architecture Pattern

Every channel integration follows the same layered pattern:

```
┌─────────────────────┐
│  REST Webhook        │  ← Platform-specific webhook endpoint
│  (RestSlackWebhook)  │     Verify signatures, respond fast, dispatch async
└──────────┬──────────┘
           │
┌──────────▼──────────┐
│  Event Handler       │  ← Core routing logic
│  (SlackEventHandler) │     Dedup, route to agent/group, manage conversations
└──────────┬──────────┘
           │
┌──────────▼──────────┐
│  Channel Router      │  ← Map platform IDs → EDDI agents + credentials
│  (SlackChannelRouter)│     Scan ChannelConnector configs, resolve vault refs
└──────────┬──────────┘
           │
┌──────────▼──────────┐
│  API Client          │  ← Platform's outgoing API (send messages)
│  (SlackWebApiClient) │     Retryable exceptions, proper JSON handling
└──────────────────────┘
```

### Step-by-Step Implementation Guide

#### 1. Create a `ChannelType` entry

Add your platform type (e.g., `teams`, `discord`) to the `ChannelConnector.type` field convention. This is a URI string, not an enum — just use the platform name.

#### 2. Webhook Endpoint (`Rest*Webhook.java`)

- **Must respond within the platform's timeout** (Slack: 3s, Discord: 3s, Teams: 15s)
- **Verify request authenticity** (HMAC signature, token, etc.)
- **Process async** — dispatch to a handler on a virtual thread
- **Return immediately** with HTTP 200 or platform-specific acknowledgment
- **Dedup events** — most platforms retry on timeout

#### 3. Event Handler (`*EventHandler.java`)

- Use `IConversationService` for 1:1 agent conversations
- Use `IGroupConversationService` for multi-agent discussions
- Use `IUserConversationStore` to map platform thread IDs → EDDI conversations
- Use `ICacheFactory.getCache(name, Duration)` for dedup and session caches (always use TTL!)

#### 4. Channel Router (`*ChannelRouter.java`)

- Scan `AgentConfiguration.getChannels()` for your platform type
- Cache the mapping with time-based refresh (60s is good)
- Use `AtomicBoolean` for refresh gating (prevent thundering herd)
- Use `SecretResolver` to resolve vault references for all credentials
- Store per-agent credentials alongside the routing map

#### 5. API Client (`*ApiClient.java`)

- **Throw exceptions for retryable failures** (network, rate limit, server errors)
- **Return null for non-retryable API failures** (bad channel, bad token)
- Use Jackson `ObjectMapper` for JSON serialization (not manual string building)
- Use Jackson for response parsing (not string indexOf)

#### 6. Group Discussion Listener (`*GroupDiscussionListener.java`)

- Implement `GroupDiscussionEventListener`
- Use a `postSafe()` wrapper — never let a single failed post abort the discussion
- Track agent message IDs for follow-up routing (reverse map for O(1) lookups)
- Signal completion via `CountDownLatch` (not polling)

### Key Lessons from the Slack Implementation

| Lesson | Why |
|--------|-----|
| **Never catch-and-swallow in the API client** | The retry wrapper in the handler needs to see failures. Throw for retryable, return null for non-retryable. |
| **Always use TTL caches, not just size-based** | Size-based caches keep stale entries indefinitely in low-traffic systems. Use `ICacheFactory.getCache(name, Duration)`. |
| **Use Jackson, not string manipulation** | Manual JSON escaping misses control characters. Manual JSON parsing is fragile. Jackson handles both correctly. |
| **Gate cache refresh with AtomicBoolean** | Under load, many threads hit the refresh simultaneously. CAS-gate ensures only one thread refreshes. |
| **Use CountDownLatch, not polling** | Polling wastes CPU and has latency. CountDownLatch signals instantly. |
| **Fire-and-forget in listeners** | A failed Slack post should not crash the entire multi-agent discussion. Wrap in try/catch. |
| **Structured exhaustion logs** | After retry exhaustion, log enough context (channel, thread, text length, error) for operator recovery. |
| **Never leak internal IDs to users** | Error messages should be generic. Log the details server-side. |
| **All credentials in ChannelConnector** | Per-agent credentials via vault references. No server-level secrets except the master toggle. |
