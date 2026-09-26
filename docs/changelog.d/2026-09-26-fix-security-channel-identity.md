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

### Item 4 — OpenAI-compat `X-OpenWebUI-User-Id` shared the OIDC principal namespace

`OpenAiAuthFilter` used the `X-OpenWebUI-User-Id` header verbatim as the EDDI
`userId`. Since a leaked shared `/v1` key lets a caller set that header to
anything, a caller could set it to an OIDC user's principal and reach that user's
conversations and long-term memories. The header value is now namespaced to
`openwebui:<id>` (`OpenAiUserIdentity`), so a self-asserted header can never equal
a bare OIDC principal. OIDC principals (in `authenticated` mode) and the
configured anonymous default are left unprefixed — the first is already verified,
the second is operator config. The documented trust model is unchanged: a leaked
key still impersonates any *Open WebUI* user; only the cross-namespace reach into
OIDC-owned identities is closed (`application.properties` `trust-user-headers`
comment still holds).

Compatibility is adopt-only, in `OpenAiConversationBridge` and only for the
`openwebui:`-namespaced path: a chat mapping stored under the raw header id is
adopted and re-keyed to the namespaced id, and because the conversation keeps its
raw-id owner its long-term memories load without any move. See the adversarial
review section below for why the standalone memory move was removed.

Test `OpenAiAuthFilterTest.headerUserId_isNamespaced_soItCannotEqualABareOidcPrincipal`
proves an OpenAI-compat identity can never collide with a bare OIDC principal;
mutation-checked (reverting the namespacing in the filter fails it). Adopt paths
covered in `OpenAiConversationBridgeTest`.

**Files:** [`OpenAiUserIdentity.java`](../../src/main/java/ai/labs/eddi/integrations/openai/OpenAiUserIdentity.java),
[`OpenAiAuthFilter.java`](../../src/main/java/ai/labs/eddi/integrations/openai/OpenAiAuthFilter.java),
[`OpenAiConversationBridge.java`](../../src/main/java/ai/labs/eddi/integrations/openai/OpenAiConversationBridge.java).

### Adversarial review fixes (A/B/C)

An adversarial review of this branch found that the memory-migration I added in
items 3 and 4 was itself exploitable, plus a name-collision hole in the item-1
binding. All three are fixed here.

- **A (BLOCK) — OpenAI-compat memory move was cross-namespace data theft.** In the
  default config a `/v1` shared-key caller sets `X-OpenWebUI-User-Id: <victim>`;
  `rawId` is then the fully attacker-chosen bare `<victim>`, which is exactly where
  OIDC principals keep long-term memories. The standalone "brand-new chat inherits"
  move called `getAllEntries(<victim>)`, upserted the result to the attacker's
  `openwebui:<victim>` and **deleted** it from the victim — one request relocated
  and erased an OIDC user's entire long-term memory. Fixed by **removing the
  standalone memory move entirely** (`OpenAiConversationBridge`). Only adoption of a
  conversation MAPPING under the exact intent remains — an OIDC principal never has
  an OpenWebUI mapping, and the adopted conversation keeps its raw-id owner so its
  memories load with no move. `IUserMemoryStore` is retained (unused for migration)
  so the no-migration invariant stays enforced by tests.

- **B (Should-fix) — same class in Slack.** `SlackEventHandler.migrateLegacyMemories`
  moved all bare-Slack-id entries keyed only on the raw id, and `team_id` is
  attacker-supplied in a validly-signed event, so a second integration's operator
  could pull a victim's legacy bare-id memories into their own
  `slack:<their-team>:<user>` namespace. Fixed by **removing the standalone move**
  (adopt/rekey keeps the raw-id owner, no move needed) and by **pinning `team_id`
  to the signing integration** where the webhook resolves it
  (`RestSlackWebhook.verifyOrigin` reads the owner/signer integration's
  `platformConfig.teamId`; the payload `team_id` is only a fallback when none is
  configured).

- **C (Should-fix) — HITL binding IDOR via non-unique display name.** The item-1
  binding keyed on the integration's mutable display **name**, and names were not
  unique. An attacker could name their integration identically to a victim's, so
  `getIntegrationByName` might resolve to the attacker while the approval record
  belonged to the victim. Fixed fail-closed: `ChannelTargetRouter.getIntegrationByName`
  now **refuses (returns empty) when more than one integration matches** a name, and
  `RestChannelIntegrationStore` **enforces global name uniqueness per channel type**
  on create/update/duplicate (duplicate copies get a `" (copy)"` suffix). An
  ambiguous name therefore resolves to no owning integration and the decision is
  refused before any record lookup. (Unique-resource-id keying was considered but
  the fail-closed ambiguity refusal plus enforced uniqueness closes the IDOR with a
  much smaller blast radius on a security branch.)

- **D (nit) — pause-check → resume TOCTOU** in the item-1 decision path is left as a
  documented residual: closing it needs an expected-`pausedAt` argument threaded
  into `resumeConversation`/`resumeDiscussion` and a CAS there, which is disproportionate
  here. Noted in `SlackInteractivityHandler`.

All three fixes are mutation-checked (revert → the new test fails → restore):
`OpenAiConversationBridgeTest.namespacedCaller_withNoLegacyMapping_neverTouchesBareIdMemories`
(A), `SlackUserIdentityTest.newConversation_neverTouchesBareIdMemories` (B),
`ChannelTargetRouterDeepBranchTest.duplicateNameRefused` (C). The approval-record
store also gains real-MongoDB coverage (`MongoSlackApprovalRecordStoreTest`:
`tryRecord` atomicity/idempotency/expiry/scoping via Testcontainers).

**Files:** [`OpenAiConversationBridge.java`](../../src/main/java/ai/labs/eddi/integrations/openai/OpenAiConversationBridge.java),
[`SlackEventHandler.java`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackEventHandler.java),
[`RestSlackWebhook.java`](../../src/main/java/ai/labs/eddi/integrations/slack/rest/RestSlackWebhook.java),
[`ChannelTargetRouter.java`](../../src/main/java/ai/labs/eddi/integrations/channels/ChannelTargetRouter.java),
[`RestChannelIntegrationStore.java`](../../src/main/java/ai/labs/eddi/configs/channels/rest/RestChannelIntegrationStore.java).

```decision-log
| 2026-09-26 | Slack HITL decisions require a persisted record of the card the owning integration posted, matched to the subject's current pause | Signature and approver list bound the integration, not the subject | In-memory marker (lost on restart); trusting the button value |
| 2026-09-26 | Slack users are `slack:<team_id>:<user_id>` in EDDI; raw-id data is re-keyed/moved lazily | Raw Slack ids shared the OIDC principal namespace | Keeping raw ids for existing users (leaves the collision open); a one-shot bulk migration |
| 2026-09-26 | OpenAI-compat header users are `openwebui:<id>` in EDDI; raw-id data is re-keyed lazily | `X-OpenWebUI-User-Id` shared the OIDC principal namespace, so a shared-key holder reached OIDC users | Namespacing OIDC principals too (they are already canonical); a one-shot bulk migration |
| 2026-09-27 | Removed the standalone bare-id memory MOVE for both OpenAI-compat and Slack; kept adopt/rekey only | The move read the shared bare-id namespace on an attacker-chosen id and could relocate+erase an OIDC user's memories (review Finding A/B) | A mapping-gated move (still leaks on a bare-id string collision with an OIDC principal) |
| 2026-09-27 | HITL owner binding refuses an ambiguous integration name and enforces global per-type name uniqueness | Integration display names were not unique, so a copied name could bind a decision to the attacker's config (review Finding C) | Keying everything on a unique resource id (larger blast radius on a security branch) |
```
