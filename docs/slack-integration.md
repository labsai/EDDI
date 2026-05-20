# Slack Integration

> **Status**: Production-ready · **Since**: v6.0.0

EDDI's Slack integration enables conversational AI agents — including multi-agent group discussions — to operate natively in Slack channels and direct messages. It supports 1:1 agent conversations, live-streamed panel discussions with multiple agents, trigger-keyword routing, and context-aware threaded follow-ups.

## Quick Setup

### 1. Create a Slack App

1. Go to [api.slack.com/apps](https://api.slack.com/apps) → **Create New App**
2. Choose **From a manifest** or **From scratch**

### 2. Configure OAuth & Permissions

Add these **Bot Token Scopes**:

| Scope | Purpose |
|-------|---------|
| `chat:write` | Post messages to channels and DMs |
| `app_mentions:read` | Respond to @mentions in channels |
| `channels:read` | Read channel metadata |
| `channels:history` | Read message events in channels |
| `im:read` | Read direct message metadata |
| `im:history` | Receive DM events |
| `im:write` | Send DM responses |

### 3. Install to Workspace

1. Go to **Install App** → **Install to Workspace**
2. Copy the **Bot User OAuth Token** (starts with `xoxb-`)
3. Copy the **Signing Secret** from **Basic Information**

### 4. Store Credentials in Vault

Store your Slack credentials in EDDI's Secrets Vault:

```bash
curl -X POST http://localhost:7070/secretstore/keys \
  -H "Content-Type: application/json" \
  -d '{"keyName":"slack-bot-token","secretValue":"xoxb-your-token-here"}'

curl -X POST http://localhost:7070/secretstore/keys \
  -H "Content-Type: application/json" \
  -d '{"keyName":"slack-signing-secret","secretValue":"your-signing-secret"}'
```

### 5. Configure Channel Integration

There are two configuration methods. The **recommended** approach uses `ChannelIntegrationConfiguration` (new-style); the legacy `ChannelConnector` on agents is supported for backward compatibility.

#### Recommended: ChannelIntegrationConfiguration

Create a channel integration with trigger-keyword routing:

```bash
curl -X POST http://localhost:7070/channelstore/channels \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Main Slack Channel",
    "channelType": "slack",
    "platformConfig": {
      "channelId": "C0123ABCDEF",
      "botToken": "${vault:slack-bot-token}",
      "signingSecret": "${vault:slack-signing-secret}"
    },
    "defaultTargetName": "default",
    "targets": [
      {
        "name": "default",
        "type": "AGENT",
        "targetId": "your-agent-id",
        "triggers": []
      },
      {
        "name": "panel",
        "type": "GROUP",
        "targetId": "your-group-id",
        "triggers": ["panel", "group", "discuss"]
      },
      {
        "name": "debate",
        "type": "GROUP",
        "targetId": "your-debate-group-id",
        "triggers": ["debate"]
      }
    ]
  }'
```

With this configuration:
- `@EDDI hello` → routes to the default agent
- `@EDDI panel: Should we use microservices?` → triggers the group discussion
- `@EDDI debate: REST vs GraphQL` → triggers the debate group

#### Legacy: ChannelConnector on Agent

Add a `ChannelConnector` to your agent configuration:

```json
{
  "channels": [
    {
      "type": "slack",
      "config": {
        "channelId": "C0123ABCDEF",
        "botToken": "${vault:slack-bot-token}",
        "signingSecret": "${vault:slack-signing-secret}",
        "groupId": "optional-group-id-for-discussions"
      }
    }
  ]
}
```

> **Note**: When both a `ChannelIntegrationConfiguration` and a legacy `ChannelConnector` cover the same `channelId`, the new-style config always wins.

### 6. Enable Direct Messages (App Home)

For the bot to accept DMs, you must enable the Messages Tab:

1. Go to **App Home** → **Show Tabs**
2. Enable **Messages Tab** (toggle on)
3. ✅ Check **"Allow users to send Slash commands and messages from the messages tab"**

> ⚠️ If this checkbox is unchecked, users will see "Sending messages to this app has been turned off" and cannot DM the bot.

### 7. Enable Event Subscriptions in Slack

> ⚠️ **This step must come last.** When you set the Request URL, Slack immediately sends a signed `url_verification` challenge. EDDI verifies this using the signing secrets from step 4. If no agent is configured yet, verification fails and Slack rejects the URL.

1. Go to **Event Subscriptions** → Enable
2. Set the **Request URL** to: `https://<your-eddi-host>/integrations/slack/events`
3. Slack will verify the URL (you should see a green checkmark)
4. Subscribe to **Bot Events**:
   - `app_mention` — triggers when the bot is @mentioned in a channel
   - `message.im` — triggers on direct messages to the bot
   - `message.channels` — enables thread-reply continuity without @mention
5. Click **Save Changes**

---

## Architecture

```
Slack Workspace(s)                       EDDI Cluster
─────────────────                        ─────────────────────────
┌─────────────┐   Events API (HTTPS)    ┌─────────────────────────┐
│ Slack App    │ ───────────────────────→│ RestSlackWebhook        │
│ (per wksp)   │                         │   ├─ Try all secrets    │
└─────────────┘                         │   └─ Dedup events       │
                                        └───────────┬─────────────┘
                                                    │ async
                                        ┌───────────▼─────────────┐
                                        │ SlackEventHandler        │
                                        │   ├─ Route via triggers  │
                                        │   ├─ DM fallback         │
                                        │   └─ Per-channel token   │
                                        └───────────┬─────────────┘
                                                    │
                              ┌──────────────────────┼───────────────────┐
                              ▼                      ▼                   ▼
                    ┌─────────────────┐   ┌──────────────────┐  ┌───────────────┐
                    │ ConversationSvc │   │ GroupConvSvc     │  │ SlackWebAPI   │
                    │ (1:1 agent)     │   │ (multi-agent)    │  │ (post msgs)   │
                    └─────────────────┘   └──────────────────┘  └───────────────┘
```

### Key Components

| Component | Responsibility |
|-----------|---------------|
| `RestSlackWebhook` | JAX-RS endpoint, multi-secret signature verification, URL challenge, event dispatching |
| `SlackSignatureVerifier` | HMAC-SHA256 verification with multi-secret support and 5-minute replay protection |
| `SlackEventHandler` | Core event logic: DM/channel routing, trigger keywords, group triggers, follow-up detection |
| `ChannelTargetRouter` | Maps Slack channels → agents/groups with trigger-keyword matching and credential resolution |
| `SlackGroupDiscussionListener` | Streams multi-agent discussions into Slack with header+thread UX |
| `SlackWebApiClient` | HTTP client for `chat.postMessage` with Markdown→mrkdwn conversion |

### Credential Flow

```
ChannelIntegrationConfiguration
  ├─ platformConfig.botToken: "${vault:slack-bot-token}"
  └─ platformConfig.signingSecret: "${vault:slack-signing-secret}"
        │
        ▼
ChannelTargetRouter (60s cache refresh)
  ├─ SecretResolver resolves vault references
  ├─ channelType:channelId → resolved config + targets
  └─ allSigningSecrets set (for webhook verification)
        │
        ├──→ RestSlackWebhook: verify(signature, allSigningSecrets)
        └──→ SlackEventHandler: postMessage(resolvedBotToken, ...)
```

---

## Features

### 1:1 Agent Conversations

@mention the bot in a channel:

```
@EDDI What's our Q4 revenue forecast?
```

The bot responds in a thread under the user's message.

### Direct Messages (DMs)

Send a message directly to the bot — no @mention needed:

```
Hello, what can you do?
```

DMs are automatically routed to the default agent from any configured Slack integration. Since DM channel IDs are dynamic (unique per user-bot pair), they don't need explicit channel configuration — EDDI resolves to the first available Slack integration's default target.

> **Note**: DMs use `message.im` events (Slack does not fire `app_mention` in DMs). Make sure `message.im` is subscribed in your Slack app's event settings.

### Trigger Keywords

Use colon-delimited trigger keywords to route to specific targets:

```
@EDDI panel: Should we adopt microservices?     → routes to "panel" target
@EDDI debate: REST vs GraphQL                   → routes to "debate" target
@EDDI architect: Review this design              → routes to "architect" target
```

Triggers are case-insensitive. The text after the colon becomes the message sent to the target agent/group. Messages without a trigger keyword route to the default target.

Type `@EDDI help` to see available trigger keywords for the channel.

### Multi-Agent Group Discussions

When a trigger keyword routes to a GROUP target, a multi-agent panel discussion starts. All configured agents in the group participate in a live discussion streamed to Slack.

#### UX Pattern: Header + Thread

All discussion styles use the same UX pattern — **header at channel level, full content in thread**:

```
User: @EDDI panel: Should we rewrite in Rust?

🗣️ *round table discussion started* — 3 agents participating
> _Should we rewrite in Rust?_

🟢 *Backend Expert*
_Rust would give us memory safety and performance..._ (preview)
  └─ [full response in thread]
  └─ 💬 *Frontend Expert* → *Backend Expert*: I agree on safety, but... (peer feedback)

🟢 *Frontend Expert*
_From the frontend perspective, the tooling is still maturing..._
  └─ [full response in thread]
  └─ 🔄 *Frontend Expert (revised)*: After hearing feedback... (revision)

📋 *Panel Synthesis* (by Moderator)
_The panel recommends a hybrid approach..._ (preview)
  └─ [full synthesis in thread]
```

This pattern keeps the channel scannable while preserving full discussion detail in threads.

#### Discussion Styles in Slack

Each style produces a distinct phase flow, but all use the same header+thread UX:

| Style | Phases | Slack Behavior |
|-------|--------|---------------|
| **ROUND TABLE** | Opinion → Synthesis | Each agent posts a channel header; moderator synthesizes |
| **PEER REVIEW** | Opinion → Critique → Revision → Synthesis | Peer feedback threads under the target agent's header |
| **DEVIL'S ADVOCATE** | Opinion → Challenge → Defense → Synthesis | Challenger threads under the original agent's header |
| **DEBATE** | Pro Arguments → Con Arguments → Rebuttals → Judge | PRO and CON agents post separate headers; rebuttals thread under opponents |
| **DELPHI** | Anonymous Round 1 → Round 2 (convergence) → Synthesis | Each round's opinions post as headers; convergence visible across rounds |

#### Peer Feedback Threading

In styles with agent-to-agent feedback (PEER_REVIEW, DEVIL_ADVOCATE, DEBATE), feedback is posted as a **thread reply under the target agent's channel header**. This creates a natural conversation flow:

```
🟢 *Alice*                         ← channel-level header
  └─ I believe we should...        ← full response (thread)
  └─ 💬 *Bob* → *Alice*: I disagree because...    ← peer feedback (thread)
  └─ 💬 *Carol* → *Alice*: I agree, and also...   ← peer feedback (thread)
  └─ 🔄 *Alice (revised)*: After hearing feedback...  ← revision (thread)
```

### Context-Aware Follow-ups

After a discussion, users can reply in an agent's thread to ask follow-up questions:

```
Alice's header: 🟢 *Alice*
  └─ [original contribution]
  └─ 💬 Bob → Alice: I disagree...
  └─ User: "Alice, can you address Bob's concerns?"
  └─ Alice: [responds with full context of the discussion + peer feedback]
```

The follow-up system:
1. Detects the thread reply is under an agent's message
2. Retrieves the agent's discussion context (contribution + feedback received)
3. Injects that context into the prompt
4. Routes to the correct agent for a contextual response

### Markdown Conversion

Agent responses often contain standard Markdown. The `SlackWebApiClient` automatically converts to Slack's `mrkdwn` format at the egress point:

| Markdown | Slack mrkdwn |
|----------|-------------|
| `**bold**` | `*bold*` |
| `# Heading` | `*Heading*` (bold) |
| `~~strike~~` | `~strike~` |
| `---` | `───────────` (Unicode line) |
| Tables (`\| col \|`) | Wrapped in `` ``` `` code blocks |
| Code blocks | Preserved unchanged |

---

## Enterprise & Clustering

### Multi-Workspace Support

Each `ChannelIntegrationConfiguration` can use different bot tokens and signing secrets, allowing a single EDDI instance to serve multiple Slack workspaces. The `ChannelTargetRouter` caches all credentials and the `SlackSignatureVerifier` tries all known signing secrets during webhook verification.

### Retry Logic

All Slack API calls use **exponential backoff** (3 attempts, 500ms/1s/2s base). Failed messages are logged but don't crash the event handler.

### Event Deduplication

Slack retries webhook deliveries on timeout. EDDI uses an in-memory cache (`ICache`) to deduplicate events by `event_id`, preventing duplicate processing.

### Follow-up Memory Management

Active group discussion contexts use EDDI's `ICache` infrastructure with **TTL-based expiration** (2 hours for group listeners, 10 minutes for event dedup). This prevents unbounded memory growth from long-lived discussions.

### Thread Safety

- `ChannelTargetRouter` uses volatile reference swaps with an `AtomicBoolean` refresh gate — no thundering herd on cache expiry
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

### ChannelIntegrationConfiguration (Recommended)

```json
{
  "name": "Production Slack",
  "channelType": "slack",
  "platformConfig": {
    "channelId": "C0123ABCDEF",
    "botToken": "${vault:slack-bot-token}",
    "signingSecret": "${vault:slack-signing-secret}"
  },
  "defaultTargetName": "default",
  "targets": [
    {
      "name": "default",
      "type": "AGENT",
      "targetId": "agent-id",
      "triggers": []
    },
    {
      "name": "panel",
      "type": "GROUP",
      "targetId": "group-id",
      "triggers": ["panel", "group"]
    }
  ]
}
```

| Key | Required | Description |
|-----|----------|-------------|
| `channelType` | ✅ | Must be `"slack"` |
| `platformConfig.channelId` | ✅ | Slack channel ID (e.g., `C0123ABCDEF`) |
| `platformConfig.botToken` | ✅ | Bot User OAuth Token. Use vault reference. |
| `platformConfig.signingSecret` | ✅ | Slack Signing Secret. Use vault reference. |
| `defaultTargetName` | ✅ | Name of the target used when no trigger keyword matches |
| `targets[].name` | ✅ | Target name (must match `defaultTargetName` for the default) |
| `targets[].type` | ✅ | `AGENT` or `GROUP` |
| `targets[].targetId` | ✅ | Agent ID or Group Config ID |
| `targets[].triggers` | ❌ | List of trigger keywords (case-insensitive) |

### Legacy ChannelConnector (on Agent)

```json
{
  "type": "slack",
  "config": {
    "channelId": "C0123ABCDEF",
    "botToken": "${vault:slack-bot-token}",
    "signingSecret": "${vault:slack-signing-secret}",
    "groupId": "optional-group-id"
  }
}
```

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
| Integration configured? | Create a `ChannelIntegrationConfiguration` with the channel's `channelId` |
| Bot token configured? | `platformConfig.botToken` should reference a vault key |
| Bot in channel? | Invite the bot to the channel in Slack |
| Event subscription active? | Check **Event Subscriptions** in Slack app settings |
| Request URL verified? | Slack must have verified `https://<host>/integrations/slack/events` |
| Signing secret set? | Without a signing secret, webhook verification fails (HTTP 403) |

### Bot doesn't respond to DMs

| Check | Fix |
|-------|-----|
| "Sending messages has been turned off"? | **App Home** → Messages Tab → ✅ check "Allow users to send Slash commands and messages" |
| `message.im` subscribed? | Add `message.im` to Bot Events in Slack app settings |
| `im:history` scope? | Add `im:history` to Bot Token Scopes and reinstall the app |
| `im:write` scope? | Add `im:write` to Bot Token Scopes and reinstall the app |
| Any Slack integration configured? | DMs fall back to the first available Slack integration's default target |

### Signature verification fails (HTTP 403)

| Check | Fix |
|-------|-----|
| Signing secret correct? | Copy from **Basic Information** in Slack app settings, store in vault |
| Clock drift? | Timestamp validation uses 5-minute window — sync clocks |
| Reverse proxy stripping body? | The raw body must reach EDDI unchanged for HMAC verification |
| No agents configured? | At least one deployed agent must have a Slack integration with `signingSecret` |

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
│  (ChannelTargetRouter)│     Trigger-keyword matching, vault-backed secrets
└──────────┬──────────┘
           │
┌──────────▼──────────┐
│  API Client          │  ← Platform's outgoing API (send messages)
│  (SlackWebApiClient) │     Retryable exceptions, Markdown→mrkdwn conversion
└──────────────────────┘
```

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
| **All credentials in config** | Per-channel credentials via vault references. No server-level secrets. |
| **Convert formatting at the egress point** | Markdown→mrkdwn conversion in the API client ensures consistent rendering across all code paths. |
