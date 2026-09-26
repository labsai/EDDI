## 🔒 fix(slack): bind HITL decisions and event signatures to the owning integration; namespace Slack user ids (2026-09-26)

**Repo:** EDDI (`fix/security-channel-identity`)

### What changed and why

Three gaps in the Slack channel, each one a place where proving *who sent a request*
was mistaken for proving *what it may act on*.

**1. A Slack HITL decision could resume any paused conversation or group.** The
interactivity endpoint verified the signature and the approver list against the
integration named in the button value, but never checked that the decision's
subject (conversation id, or `group:<id>`) had anything to do with that integration.
An approver of integration A who could get a signed `block_actions` payload carrying
an edited value resumed conversations that belonged to integration B, to another
channel, or to no Slack integration at all.

Every approval card is now recorded when it is posted, in a new persisted store
(`ISlackApprovalRecordStore`, collection/table `slack_hitl_approval_records`, MongoDB
and PostgreSQL), keyed by `(integrationName, subject, pauseEpoch)`. On a decision,
`SlackInteractivityHandler` requires a live record for that integration and subject
whose pause identity matches the subject's *current* pause (`hitlPausedAt` for a
conversation, `GroupConversation.pausedAt` for a group). No record → refused and
logged as `SLACK_HITL_DECISION_REFUSED`. A record from an earlier pause → the card is
marked "already resolved" and the newer pause is not touched. A store failure fails
closed and leaves the card intact for a retry.

The record replaces the in-memory `approvalNotified` cache as the "one card per pause"
marker, so that guarantee now survives a restart too. It is written *before* the card
is posted and removed if delivery fails. A card whose record cannot be written is
posted without buttons. Group pauses got the same treatment: `HitlPauseEvent` now
carries `pausedAt` (a 4-arg constructor keeps old callers compiling), and
`SlackGroupDiscussionListener` records the card before posting it. Records expire
after `eddi.slack.hitl.approval-record-retention` (default `30d`).

**2. `/integrations/slack/events` accepted a signature from any configured app.** It
verified against the pooled set of every integration's signing secret, so a holder of
one integration's secret could drive another integration's channels, agents and
approval flow. After parsing, `RestSlackWebhook` now requires the signature to match
the secret of the integration (or legacy connector) that owns `event.channel`. An owned
channel whose owner has no secret is rejected, never re-admitted through the pool. For
an unowned channel (a DM) the webhook finds which integration's secret signed it and
passes that to `SlackEventHandler`, which routes the DM to *that* integration's default
target through the new `ChannelTargetRouter.resolveDefaultForDm(type, text, integrationName)`.
Previously it went to whichever integration came first in map order. Events with no
channel at all keep the pooled check only, since the handler drops them anyway.

**3. Slack users shared the OIDC principal namespace.** The raw Slack user id
(`U0ALICE`) was the EDDI `userId`. A Keycloak principal equal to a Slack id shared that
user's long-term memories and passed ownership checks on their conversations, and ids
from two workspaces could collide. It is now `slack:<team_id>:<user_id>`
(`SlackUserIdentity`), with the team taken from the envelope's `team_id`.

Compatibility, with no migration step to run:
- A thread mapping stored under the raw id is found, **re-keyed** to the namespaced id,
  and the thread keeps its conversation. That conversation keeps its raw-id owner and
  its memories.
- Before a **new** conversation starts, long-term memory entries under the raw id are
  **moved** to the namespaced id. Only `U…`/`W…`-shaped ids are touched, and an entry the
  namespaced identity already holds for the same key and agent wins.

### Design decisions

- **Persisted, not cached**, as the task required. It reuses the existing
  `approvalNotified` bookkeeping instead of adding a second marker.
- **`UNKNOWN_PAUSE` records** (card posted before the bookmark was readable) only match
  a pause that began at or before the record was written. A card cannot be for a pause
  that did not exist yet.
- **Moving memories, not copying them**, makes the migration idempotent without a
  marker. A copy would bring back entries the user had erased under the new id.
- **Residual, documented:** checking the pause and resuming are two separate steps.
  Closing that window would need an expected-pause parameter on `resumeConversation`.

### Not done

- The approval records are not in the GDPR erasure cascade. They hold only an
  integration name, a conversation or group id and a channel id, and they expire on
  their TTL.
- The legacy `IUserMemoryStore` Properties blob (`readProperties`) is not migrated. Only
  structured entries are.

**Files:** [`ISlackApprovalRecordStore.java`](../../src/main/java/ai/labs/eddi/integrations/slack/hitl/ISlackApprovalRecordStore.java),
[`MongoSlackApprovalRecordStore.java`](../../src/main/java/ai/labs/eddi/integrations/slack/hitl/MongoSlackApprovalRecordStore.java),
[`PostgresSlackApprovalRecordStore.java`](../../src/main/java/ai/labs/eddi/integrations/slack/hitl/PostgresSlackApprovalRecordStore.java),
[`SlackInteractivityHandler.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackInteractivityHandler.java),
[`SlackEventHandler.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java),
[`SlackGroupDiscussionListener.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackGroupDiscussionListener.java),
[`SlackUserIdentity.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackUserIdentity.java),
[`RestSlackWebhook.java`](../../src/main/java/ai/labs/eddi/integrations/slack/rest/RestSlackWebhook.java),
[`ChannelTargetRouter.java`](../../src/main/java/ai/labs/eddi/integrations/channels/ChannelTargetRouter.java),
[`GroupConversationEventSink.java`](../../src/main/java/ai/labs/eddi/engine/lifecycle/GroupConversationEventSink.java),
[`DataStoreProducers.java`](../../src/main/java/ai/labs/eddi/datastore/DataStoreProducers.java).
Docs: [`hitl.md`](../hitl.md), [`slack-integration.md`](../slack-integration.md).

```decision-log
| 2026-09-26 | Slack HITL decisions require a persisted record of the card the owning integration posted, matched to the subject's current pause | Signature and approver list bound the integration, not the subject | In-memory marker (lost on restart); trusting the button value |
| 2026-09-26 | Slack users are `slack:<team_id>:<user_id>` in EDDI; raw-id data is re-keyed/moved lazily | Raw Slack ids shared the OIDC principal namespace | Keeping raw ids for existing users (leaves the collision open); a one-shot bulk migration |
```
