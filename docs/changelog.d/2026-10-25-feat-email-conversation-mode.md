## ✨ feat(email): conversation mode — the agent replies in the mail thread — email Phase 3 (2026-10-25)

**Repo:** EDDI (`feat/email-conversation-mode`, on top of `feat/email-trigger-mode`, `feat/email-dkim`,
`feat/email-hitl-notifications` and `feat/email-mail-core`)

Phase 3 of [the email plan](../../planning/email-channel-plan.md) (§5 conversation column, §9, §10, §11, §12, §13,
§15, §16, §17, §20, §21): `channelType: "email"` with `mode: "conversation"`. The agent answers in the thread over
SMTP — **only to the DKIM/DMARC-authenticated `From`** of the mail it answers, never `Cc`, never an
unauthenticated or `email-anon:` sender, never an address the model chooses. Trigger mode is unchanged and still sends
nothing. Docs: [email-integration.md → Conversation mode](../email-integration.md#conversation-mode).

### Settings and save validation

- **`EmailChannelSettings`**: `mode: "conversation"` is accepted, with `smtpHost` (required), `smtpPort` (587
  STARTTLS required, default, or 465), `fromName` (header-safe, ≤ 100), `replyTemplate` (`{output}`, ≤ 4000),
  `aiDisclosure` (a default sentence; **a present, empty value switches it off**, ≤ 500), `threadParticipants`
  (`originalSenderOnly` | `anyAuthenticatedParticipant`), `maxRepliesPerThreadPerHour` (5, 1–1000) and `honorReplyTo`
  (false). `requireAuthentication: false` is refused in conversation mode, and `from()` forces it true so no reader
  can see false; `correlateThreads` is implied (and refused with a value). Each mode refuses the other's keys when
  they carry a value. New save warnings in `X-Eddi-Warnings`: `email.ai-disclosure-disabled`, `email.honor-reply-to`.
- **`EmailChannelSupport`** also resolves `smtpHost` under the **author** socket policy (`SafeSocketConnector.resolve`,
  `Protocol.SMTP`) and requires the connection's allowlist to name `smtp://<host>:587` / `smtps://<host>` besides the
  IMAP origin.

### Transport and the reply path

- **`SmtpSubmitter`** (`engine/mail`, new): the one SMTP submission over Angus Mail on a `SafeSocketConnector`
  endpoint, extracted from `SystemMailer` (which now delegates). The policy is the caller's: it takes an `Endpoint`
  and never resolves a host.
- **`MailTransport.send`** (SPI) — `ImapMailTransport` sends over SMTP with the integration's connection (BASIC, or
  XOAUTH2 with the mailbox as user), envelope sender = the mailbox (bounces come back to it). It refuses an integration
  that is not in conversation mode, and any endpoint that is not an author-policy SMTP endpoint, before a socket opens.
  `MailboxSession.findByMessageId` finds a mail again by `SEARCH HEADER Message-ID` (exact match after) in the polled
  folder or the move folder.
- **`integrations/email/reply/`** (new): `EmailReplyRecipients` (the one recipient decision, re-applied on every
  attempt: conversation mode; the answered mail is this integration's inbound row and reached a turn; the conversation
  runs as `email:<id>` / `email:<id>:<address>`, never `email-anon:`; the mail's step in memory names the same mail,
  `aligned-pass` and the same `From`; under perSender + originalSenderOnly the identity is that address; never the own
  address; `Reply-To` only with `honorReplyTo` and exactly one address), `EmailReplyComposer` (`replyTemplate` on the
  restricted engine with the output and subject as data, then the disclosure; `OutboundMail.replyInThread`, new),
  `EmailReplyThread` (reads the reply from conversation outputs — the same outputs from the completion callback and from
  the stored conversation), `EmailReplySender` (ledger rows, inline first attempt on a managed executor, retries, bounces),
  `EmailHitlResumeObserver`.
- **Ledger**: `Kind.REPLY`, `Direction.OUT`, idempotency key `reply:<sha256(conversation, inbound row + step, kind)>`,
  `sourceRef` = `<inbound row>|<kind>|<step|pause|verdict>`; claim/lease/complete/reschedule like notifications, its own
  worker (`@Scheduled(identity = "email-reply-sweeper")`, `eddi.email.reply.sweep-interval`, `claimDue(Kind.REPLY)`),
  backoff and attempts from `eddi.email.outbound.*`, `FAILED` → owner notice `reply_failed`. No body: a retry re-reads
  the conversation and re-renders under the row's Message-ID. New `findByConversation` and `countByConversationSince`
  (indexes on both stores).
- **Inbound** (`EmailInboundProcessor`, conversation-mode branch only): the turn is submitted with all outputs in the
  **detailed** projection — the default one strips each step's `context`, where the mail map lives, so a reply would
  find no step to answer (`EmailReplyThreadSnapshotTest` pins this against the real converter, and the retry and
  sweep reads ask for the same) — and with the mail's ledger key as its idempotency key; its completion hands the snapshot to the reply sender **before** the row
  moves to `DONE`. A mail whose `In-Reply-To`/`References` names one of the integration's replies continues that
  reply's conversation — when its sender started it, or (anyAuthenticatedParticipant) a reply in it was sent to them;
  anyone else falls through to the sender-keyed thread key. The `mail` context map gains `inReplyTo`, `references`,
  `replyTo`. A group target's synthesis is the reply (`GroupDiscussionEventListener.onGroupComplete`).
- **HITL in the thread**: on `HitlPauseCommittedEvent` the sender gets the output so far plus the pause reason — never
  the tool or its arguments; on `HitlResumeCompletedEvent` the continuation (approval) or a plain notice (rejection,
  cancellation, timeout, failed continuation), the approver never named. Human takeover: not built
  (`planning/human-takeover-design.md` is a design under review), so nothing to send.

### Loops, bounces, suppression

- `maxRepliesPerThreadPerHour` counted from the ledger per conversation; over it the row is `SUPPRESSED`
  (`THREAD_BUDGET`) with a once-a-day owner notice `thread_throttled`.
- A DSN for one of the integration's own reply Message-IDs, with a permanent failure for **that reply's recipient**,
  fails the row (`BOUNCED: <status>`) and adds the address to the new **`IEmailSuppressionStore`** (Mongo + Postgres,
  per integration, SHA-256 + domain, no address); delays are noted. Never sent to a suppressed address (checked at
  queueing and before each attempt). Cleared by an administrator through the new
  `GET /channelstore/channels/{id}/email-suppressions` and `POST …/email-suppressions/clear` (EDIT on the
  integration; the address in the body, not the URL). Deleting the integration drops its list; GDPR erasure deletes
  an address's entries (`EmailSuppressionGdprParticipant`, step `emailSuppressions`).

### Stranded handling (both modes)

- A `TURN_SUBMITTED` row whose conversation holds the mail's step: conversation mode rebuilds the missing reply (or
  pause notice) from the stored conversation (`stranded_recovered{outcome=reply_rebuilt}`) before closing the row.
- **The trigger-mode gap is closed**: a conversation that was started but never ran the mail's turn (idle or paused,
  no step for the mail) is handed back to `RECEIVED` keeping its conversation (conditional — one sweeper wins,
  `outcome=resubmitted`); the next poll finds the mail by Message-ID in the polled or move folder and runs it once in
  that conversation (the turn's idempotency key is the mail's ledger key). Not in the mailbox any more →
  `REFUSED(stranded:not_in_mailbox)` + `lost` notice. Ended or failed conversations, and unreadable ones, are resolved
  as before — when in doubt, a mail is not run twice.

### Metrics, audit, Manager, operator, docs

- `eddi.email.outbound{kind=reply,outcome=sent|retry|failed|suppressed|throttled|refused|bounced|no_output}`,
  `eddi.email.outbound.latency{kind=reply}`, `eddi.email.suppressions{reason}`; `eddi.email.inbound` gains
  `outcome=bounce` and real `mode` tags. Three Full Metrics panels (k8s copy re-synced), [metrics.md](../metrics.md).
- Audit `email.reply` per sent, failed or bounced reply (integration, reply kind, outcome, Message-ID, ledger row,
  recipient domain + SHA-256 — no address, no body).
- Manager: a mode selector, a Replies section (SMTP host/port, sender name, who may continue a thread, reply limit,
  Reply-To with its warning, AI disclosure toggle with its warning, reply template), forced authentication in
  conversation mode, mode switches drop the other mode's keys, DKIM/DMARC and suppression notes, the two new warning
  toasts; 38 new `emailChannel.*` keys and one reworded (`modeNote`) in all 11 locales. **Operator revision 38 → 39**: the system prompt explains
  conversation mode, its recipient rules, HITL in the thread, bounces and how suppressions are cleared; no channel
  endpoints are granted (the operator has none), so `tool-scopes.ts` is unchanged. MCP channel tool descriptions and
  the `eddi-admin` plugin skill cover conversation mode.
- Docs: [email-integration.md](../email-integration.md), [hitl.md](../hitl.md#email-conversations),
  [security.md](../security.md#email-replies-conversation-mode), [configuration-reference.md](../configuration-reference.md#email),
  [gdpr-compliance.md](../gdpr-compliance.md), [compliance-data-flow.md](../compliance-data-flow.md); the plan's status
  line says Phases 0–3 are implemented and 4–7 are not.

### Tests

Unit: `EmailReplyRecipientsTest` (every rule alone), `EmailReplyComposerTest` (output and subject as data, `{#include}`
printed, threading headers, header injection), `EmailReplySenderTest` (idempotency under concurrency, suppression at
queueing and before the attempt, thread budget, retries rebuilt with the same Message-ID, permanent failure → owner
notice, re-checked rules on retry, pause notice minimisation, resume continuation, DSN mapping and spoofed DSNs, group
synthesis), `EmailConversationModeTest` (processor + sender: reply, thread via EDDI's Message-ID, hijack guard,
participants, unauthenticated never answered, bounce → suppression, resubmission, trigger mode never touches the reply
sender), `EmailChannelSupportTest`, `EmailHitlResumeObserverTest`, `EmailSuppressionGdprParticipantTest`, the
suppression store contract on the in-memory double, and new cases in `EmailChannelSettingsTest`,
`RestChannelIntegrationStoreEmailTest`, `EmailInboundSweeperTest`, the ledger contract. `EmailTriggerModeSendsNothingTest`
is rewritten on purpose: `SystemMailer` only in the staff notifiers, SMTP only in `ImapMailTransport` (and the save-time
host check), composing only in the notifiers and the reply path, `transport.send` only in `EmailReplySender`, and the
transport refusing trigger mode. ITs: `EmailConversationModeIT` (GreenMail IMAPS + SMTPS on loopback, TLS verified:
full loop with headers, answer to EDDI's Message-ID, third party, bounce → suppression, crash before send → exactly one
rebuilt reply, resubmission found by Message-ID and run once), `Mongo/PostgresEmailSuppressionStoreIT`, the new cases
of `Mongo/PostgresEmailMessageLedgerIT`. The security guards were mutation-checked.

### Review follow-up (PR #1144)

- **Resubmissions per integration.** `pendingResubmissions` read one global batch of 50 due `RECEIVED` rows and
  filtered it in memory, so rows nobody polls any more (a deleted or invalid integration's) could fill it for ever.
  The new `IEmailMessageLedger.findDueResubmissions(integrationId, now, limit)` filters in the store (MongoDB,
  PostgreSQL, in-memory, contract case). Orphaned rows block nobody now and are left to ledger retention.
- **No 500-row cap on a thread.** `findByConversation` (oldest 500 rows) gives way to `findInboundTurn(conversationId,
  latest)` and `hasSentReply(conversationId, recipient)`, so a long thread still finds the mail a pause notice or
  continuation answers, and `anyAuthenticatedParticipant` still admits an address a late reply went to.
- **Cost before the reply.** `completed()` records the turn's spend before queueing the reply, so a reply that cannot be
  queued no longer drops the cost from `dailyCostBudgetUsd`.
- **A failed reply rebuild keeps the row.** The stranded sweep leaves the row `TURN_SUBMITTED`
  (`reply_rebuild_failed:<n>`, deadline moved by `stranded-after`) and tries again, 5 sweeps in all, instead of closing
  it `DONE` with no reply.
- **Oversized resubmission.** A resubmitted mail found over `maxMessageBytes` ends `REFUSED(too_large)` and the poll
  goes on, instead of failing and backing off on the same row every poll.
- **Wording.** [hitl.md](../hitl.md#email-conversations), [configuration-reference.md](../configuration-reference.md#email)
  and [email-integration.md](../email-integration.md) say the ledger deduplicates rows, not deliveries: a crash after
  the relay accepted a message and before its row completed can send it twice (same Message-ID). The recipient rule in
  the Manager's mode note, the operator prompt (revision 40), the `create_channel_integration` description and the
  `eddi-admin` skill now name the `honorReplyTo` exception; the throttle hint says further replies are not sent (they
  are `SUPPRESSED`), not delayed — all 11 locales.
- **Manager mode.** `emailMode` reads the mode case-insensitively like `Mode.fromId`, so an API-saved
  `"CONVERSATION"` integration keeps its conversation controls and reply settings on save.
- Tests: `EmailConversationModeTest`, `EmailReplySenderTest`, `EmailInboundSweeperTest`, `EmailMailboxPollerTest`, the
  ledger contract (`Mongo/PostgresEmailMessageLedgerIT`) and `email-channel.test.ts`, each mutation-checked.

```decision-log
| 2026-10-25 | A conversation-mode reply's recipient is decided in one function, from the ledger row cross-checked with the mail's step in conversation memory and the conversation's identity, on every attempt | Authentication is a fact of the inbound mail; the processor forces it on, and the reply path checks it again from two independent records, so a reply to an unauthenticated or anonymous sender cannot be produced even for a turn that should not have run | An authenticated flag on the ledger row (a new column on both stores for a fact the step already holds); trusting the processor's check alone |
| 2026-10-25 | Replies are rebuilt from the conversation on retry; group replies, whose discussion keeps no copy of the mail, are retried only by the node that holds them | The ledger stores no body or subject (plan §12); a group conversation has no field for the answered mail's subject | Storing the subject in the ledger; an extra copy of the mail in the group conversation |
| 2026-10-25 | A mail whose conversation was started but whose turn never ran is found again in the mailbox by Message-ID and run once | The raw mail is not kept, and the mailbox still has it (flagged or moved); the hand-back is a conditional transition and the turn carries the mail's ledger key | Keeping the rendered input (a body in storage); marking it lost (a mail silently not processed) |
| 2026-10-25 | The email suppression list stores a SHA-256 of the address per integration, and GDPR erasure deletes the entry | Enough to answer "suppressed?" and to clear it for someone who knows the address; conversation mode only answers mail the person sends, so an erased person is written to again only if they write first | Plain addresses (a list of correspondents); keeping a hash after erasure (plan §23 Q4 — keeps the identifier the request asked to remove) |
| 2026-10-25 | Suppressions are cleared through two channel REST endpoints, not an MCP tool or an operator grant | An administrator needs a way to undo a bounce without database access; the operator holds no channel endpoints by design | Store-only clearing (database access); an MCP tool (one more admin surface to secure) |
| 2026-10-26 | Due resubmission rows of a deleted integration are left to ledger retention, not refused by the sweeper | Once the query is scoped to one integration they block nobody; a sweeper pass over all RECEIVED rows would again read one global batch | Refusing them in the inbound sweep; deleting an integration's ledger rows with it (the rows are GDPR-reachable history) |
```
