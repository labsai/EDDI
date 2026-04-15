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

### 5. Configure EDDI

Add to `application.properties` (or use environment variables / SecretsVault):

```properties
eddi.slack.enabled=true
eddi.slack.bot-token=${eddivault:slack-bot-token}
eddi.slack.signing-secret=${eddivault:slack-signing-secret}
# Optional: default agent/group when no ChannelConnector matches
# eddi.slack.default-agent-id=<agent-id>
# eddi.slack.default-group-id=<group-id>
```

### 6. Map Channels to Agents

Create a `ChannelConnector` on your agent configuration:

```json
{
  "channels": [
    {
      "type": "slack",
      "config": {
        "channelId": "C0123ABCDEF",
        "groupId": "optional-group-id-for-discussions"
      }
    }
  ]
}
```

The `channelId` is the Slack channel ID (find it in Slack by right-clicking a channel → **View channel details** → copy the ID at the bottom).

---

## Architecture

```
Slack Workspace                          EDDI Cluster
─────────────────                        ─────────────────────────
┌─────────────┐   Events API (HTTPS)    ┌─────────────────────┐
│ Slack App    │ ───────────────────────→│ RestSlackWebhook    │
│ (Bot Token) │                         │   ├─ Verify sig     │
└─────────────┘                         │   └─ Dedup events   │
                                        └──────────┬──────────┘
                                                   │ async
                                        ┌──────────▼──────────┐
                                        │ SlackEventHandler    │
                                        │   ├─ Route to agent  │
                                        │   ├─ Detect group:   │
                                        │   └─ Follow-up ctx   │
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
| `RestSlackWebhook` | JAX-RS endpoint, signature verification, URL challenge, event dispatching |
| `SlackSignatureVerifier` | HMAC-SHA256 verification with 5-minute replay protection |
| `SlackEventHandler` | Core event logic: message routing, group triggers, follow-up detection |
| `SlackChannelRouter` | Maps Slack channels → EDDI agents/groups via `ChannelConnector` configs |
| `SlackGroupDiscussionListener` | Streams multi-agent discussions into Slack with two UX modes |
| `SlackWebApiClient` | Minimal HTTP client for `chat.postMessage` |

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

### Retry Logic

All Slack API calls use **exponential backoff** (3 attempts, 500ms/1s/2s base). Failed messages are logged but don't crash the event handler.

### Event Deduplication

Slack retries webhook deliveries on timeout. EDDI uses an in-memory cache (`ICache`) to deduplicate events by `event_id`, preventing duplicate processing.

### Follow-up Memory Management

Active group discussion contexts use EDDI's `ICache` infrastructure with automatic TTL expiration. This prevents unbounded memory growth from long-lived discussions.

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

| Property | Default | Description |
|----------|---------|-------------|
| `eddi.slack.enabled` | `false` | Master toggle |
| `eddi.slack.bot-token` | — | Slack Bot User OAuth Token (`xoxb-...`) |
| `eddi.slack.signing-secret` | — | Slack Signing Secret for request verification |
| `eddi.slack.default-agent-id` | — | Fallback agent when no `ChannelConnector` matches |
| `eddi.slack.default-group-id` | — | Fallback group config for `group:` triggers |

### ChannelConnector Config Keys

| Key | Required | Description |
|-----|----------|-------------|
| `channelId` | ✅ | Slack channel ID (e.g., `C0123ABCDEF`) |
| `groupId` | ❌ | Group config ID for multi-agent discussions |
