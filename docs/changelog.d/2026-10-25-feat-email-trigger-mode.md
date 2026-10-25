## ✨ feat(email): the email channel in trigger mode over IMAP — email Phase 2 (2026-10-25)

**Repo:** EDDI (`feat/email-trigger-mode`, on top of `feat/email-dkim`, `feat/email-hitl-notifications` and
`feat/email-mail-core`)

Phase 2 of [the email plan](../../planning/email-channel-plan.md) (§3, §5 trigger column, §6, §7.1 inbound, §9–§14,
§17, §20, §21): `channelType: "email"` with `mode: "trigger"`. EDDI polls an IMAP mailbox and every new mail starts one
agent turn. **Nothing is ever sent to a sender** — no reply, no bounce. Conversation mode is refused at save time
until Phase 3. Docs: [email-integration.md](../email-integration.md).

### The channel

- **`EmailChannelSettings`** (`configs/channels/model`): one reader for the save and the poll path, every key of plan
  §10's trigger mode with its default, plus `port` (993 / 143), `correlateThreads` and `ownerNotifyAddresses`.
  Unknown keys and the conversation-mode keys (`smtpHost`, `replyTemplate`, `aiDisclosure`, …) are refused; the
  connection is a name, never a credential; `attachments: store` needs a MIME allowlist.
- **`RestChannelIntegrationStore`** registers `email`. Besides the static checks, `EmailChannelSupport` checks against
  the deployment: the host passes `SafeSocketConnector.resolve` (author policy), the connection exists, is `BASIC` or
  OAuth, not `PER_USER`, and its allowlist names `imaps://<host>` / `imap://<host>:143`
  (`ConnectionResolver.requireMailLoginTarget`, new — no credential is resolved), and the address is unique per
  deployment (fail closed). `observeMode` is refused. Saving (re)creates the poll schedule; deleting removes it;
  duplicating is refused (same address). **Warnings** ride in a new `X-Eddi-Warnings` header as stable codes:
  `email.open-trigger` (no allowlist and no authentication requirement) and `email.tools-without-approvals` (a target
  agent can use tools but has no `toolApprovals`, found by `EmailAgentToolsInspector` — plan §14 item 6). MCP
  `create_channel_integration` / `update_channel_integration` describe the email keys and return the warnings.

### Polling (no custom scheduler)

- **`EmailPollSchedules`** (marker, like `RagIngestionSchedules`): one `HEARTBEAT` schedule per integration,
  `email-poll:<integrationId>`, every `pollSeconds`. `ScheduleFireExecutor` hands it to `EmailMailboxPoller` before the
  creator check (the integration governs it) and refuses a row whose name does not match its metadata;
  `RestScheduleStore` refuses client bodies declaring the marker, refuses updates of a managed poll schedule (409), and
  requires EDIT on the integration to fire one. A mail-server outage is a COMPLETED fire with the error in the fire log:
  the poller backs off by itself (`pollSeconds × 2^(failures−1)`, ≤ 1 h) instead of the schedule dead-lettering.
- **`ImapMailTransport`** (Angus Mail on `SafeSocketConnector`, author policy only): XOAUTH2 with
  `resolveAccessToken` (the address as the user) or `BASIC` with `resolveBasicLogin`; one folder opened read-write;
  envelope + flags + `RFC822.SIZE` + `INTERNALDATE` first, then `BODY.PEEK[]` (never `\Seen`) bounded by
  `maxMessageBytes`; marking sets `$EddiProcessed` where the server takes keywords and moves with `MOVE` when
  configured — never a copy-and-delete, never an expunge (the folder is closed with `expunge=false`).
- **Cursor** (`IEmailMailboxStateStore`, Mongo + Postgres, new): `UIDVALIDITY` + the last UID up to which everything is
  settled; a deferred mail holds it back. The first poll starts at `UIDNEXT` and records `since`, so mail predating the
  integration is never processed (rescans included, with 5 min slack for whole-second `INTERNALDATE`s). A changed
  `UIDVALIDITY` rescans, deduplicated by the ledger and the keyword, counted.

### The inbound pipeline (`EmailInboundProcessor`)

Envelope Message-ID pre-check (a deferred or settled mail costs no download) → size → fetch → parse → **ledger row
`RECEIVED`** (conditional insert on `(integration, IN, Message-ID)`) → `SenderAuthenticator` as of `INTERNALDATE` (a
`temporary` verdict defers the mail, unmarked, for at most a day) → `EmailLoopGuard` (auto-reply headers, reports,
daemon/null return paths, own address, another integration's address, EDDI-minted Message-IDs, replies to mail another
integration or the system mailer sent) → blocklist → allowlist → `requireAuthentication` → daily cost budget (stops the
batch) → per-sender daily turns (defers to the next UTC day) → identity → routing (plus-tag, then subject word, then
default) → optional thread correlation → **claim `RECEIVED → TURN_SUBMITTED`** → conversation, attachments, turn
(queued, not awaited) → `DONE` from the completion callback with the turn's cost added to the day. Every refusal is
`REFUSED(<reason>)`, counted, never answered.

- **Identity** (`EmailUserIdentity`): `email:<integrationId>:<address>` (perSender) or `email:<integrationId>`
  (integration) only for `ALIGNED_PASS`; otherwise, with `requireAuthentication: false`, a one-off
  `email-anon:<random>` that never correlates and **has no memory**: `ReservedPrincipals.hasNoPersistentMemory` makes
  `ConversationService.createPropertiesHandler` hand it no user-memory store and no memory tools, and
  `ContextualToolsProvider` refuses the memory tool for it as a second line. Both prefixes are reserved principals.
  Turns are `SELF_ASSERTED` (no caller identity on the poll thread), so `PER_USER` connections are refused.
- **Hijack guard**: the thread mapping key (`IUserConversationStore`) is
  `email:<integrationId>:<agentId>:sha256(threadRoot + sender)` — a continuation from anyone but the original
  authenticated sender starts its own conversation, in both identity modes. Busy or paused conversations defer the mail.
- **Mail is data**: `inputTemplate` (default: the mail framed as untrusted third-party content, plan §14 item 4 — a
  deliberate deviation from §10's bare `{mail.subject}\n\n{mail.text}`) renders on the restricted runtime engine with
  the mail as data; a failing template falls back to subject + text joined in Java. The `mail` context carries plan
  §10's fields plus `anomalies` (e.g. `alternatives_diverge`); `mail.text` goes to the template only.
- **Attachments**: `store` puts allowlisted types into `IAttachmentStore` under the conversation and passes
  `attachment_<n>` (`storageRef`) to the multimodal pipeline; executables never, at most 20, every skip named.
- **Budgets and owner notices** (`EmailOwnerNotifier`): once per UTC day and reason (`budget`, `throttled`, `quota`),
  cluster-wide via `claimNotice`: a WARN line always, plus a plain-text mail through the Phase 1 system mailer to
  `ownerNotifyAddresses` inside `eddi.mail.notify.allowed-recipient-domains` when a mailer is configured; one attempt, a
  `NOTICE` ledger row, no retry. Tenant quotas apply as for webhooks (a refusal hands the claim back and stops the poll).

### Ledger, stranded turns, GDPR

- `IEmailMessageLedger` gains `Kind.TRIGGER`/`NOTICE`, the inbound states, `transition`, `findDue`, `findByMessage`,
  `findOutboundByMessageId` and `countInboundTurns` (new indexes on both stores). An inbound row's `recipient` is the
  sender, so the existing erasure/export path reaches it.
- **Stranded turns** (`EmailInboundSweeper`, `@Scheduled(identity = "email-inbound-sweeper")`): a row still
  `TURN_SUBMITTED` past `eddi.email.inbound.stranded-after` is resolved from its conversation — idle/failed/paused →
  `DONE`, running → deadline extended, no conversation → `REFUSED(stranded)`. In trigger mode there is no reply to
  rebuild, and a mail is **never run twice** (the raw bytes are not kept and a second run could repeat tool side
  effects), so a node dying between claim and turn is the one way a mail can be lost; it is logged and counted. The
  sweep also re-creates missing poll schedules (e.g. after a ZIP import).
- **Audit**: every refused mail is an audit entry `email.inbound` (integration, reason category, ledger row,
  Message-ID, sender domain + SHA-256, no address or content). Integration create/update is not audited separately yet.
- **GDPR**: `EmailIdentityGdprParticipant` (`emailIdentities`) runs the erasure/export cascade for every
  `email:<integrationId>:<address>` identity of an address (current integrations and those its inbound rows name);
  `EmailLedgerGdprParticipant` also matches `email:` identities.

### Metrics, docs, Manager, operator

- `eddi.email.inbound{integration,transport,mode,outcome}`, `eddi.email.poll.duration{…,outcome}`,
  `eddi.email.poll.cursor_resets`, `eddi.email.ledger.stranded_recovered{outcome}`; `eddi.email.outbound` gains
  `kind=notice`. Four panels on the Full Metrics dashboard's Integrations row (k8s copy re-synced),
  [metrics.md](../metrics.md). `eddi.email.auth{result}` stays deployment-wide (one tag set per name).
- New properties `eddi.email.poll.max-messages-per-poll` (50), `eddi.email.poll.max-duration` (2m),
  `eddi.email.inbound.stranded-after` (30m), `eddi.email.inbound.sweep-interval` (5m).
- Docs: [email-integration.md](../email-integration.md) (Exchange Online with OAuth client credentials and a
  per-mailbox permission, app passwords, every key, what the agent sees, identity, threads, budgets, security, the
  **helpdesk recipe** — webhook channel + `httpcalls`), [SUMMARY.md](../SUMMARY.md), [security.md](../security.md),
  [configuration-reference.md](../configuration-reference.md#email), [compliance-data-flow.md](../compliance-data-flow.md)
  (the mailbox host as processor), [gdpr-compliance.md](../gdpr-compliance.md), [connections.md](../connections.md).
- Manager: an Email channel type with a trigger-mode form, the security hints (allowlist, Exchange per-mailbox
  permission, tool approvals), the open-trigger warning before saving and the `X-Eddi-Warnings` codes as toasts after
  (`api-client.ts` now exposes response headers); 73 `emailChannel.*` keys in all 11 locales. **Operator revision
  37 → 38**: the system prompt explains the email trigger channel, its security posture and when to use the helpdesk
  recipe instead; no new endpoint, so `tool-scopes.ts` is unchanged. The `eddi-admin` Claude plugin skill mentions the
  email channel.
- `ReservedPrincipalsTest` classifies the new non-principal prefixes (and `sha256:` / `notify:`, which the previous
  slice left unclassified).

### Tests

Unit: `EmailChannelSettingsTest`, `EmailInboundProcessorTest` (state machine; temporary verdicts not marked;
unauthenticated refused or anonymous without correlation; loop guard; lists; oversize before download; crash after
claim; hijack guard in both identity modes; executables never stored; Qute in subject/body printed, not run; budgets,
throttle, quota), `EmailMailboxPollerTest` (mark after the ledger, cursor holding, STOP, skips, UIDVALIDITY, backoff,
self-removal), `EmailInboundSweeperTest`, `EmailChannelPiecesTest` (patterns, identity, schedule, notices, GDPR),
`RestChannelIntegrationStoreEmailTest`, and email cases in `ScheduleFireExecutorTest`, `RestScheduleStoreTest`,
`ConversationServiceDeepBranchTest`. ITs: `EmailImapPollingIT` (GreenMail IMAPS on loopback through the
`SafeSocketTestSupport` seam, TLS verified: new mail runs and is flagged but not `\Seen`, move, oversize, crash between
ledger and mark, UIDVALIDITY change, login failure), `Mongo/PostgresEmailMailboxStateStoreIT`, and the inbound cases of
`Mongo/PostgresEmailMessageLedgerIT`. The security guards were mutation-checked.

### Review follow-up: crash recovery, keywords, type switching, a send guard

- **A claimed mail that did nothing is processed again.** New inbound state `CLAIMED` between `RECEIVED` and
  `TURN_SUBMITTED`: the claim is taken first, and `TURN_SUBMITTED` (the point of no return) is written immediately
  before the first side effect (conversation start, group discussion, turn). A mail is marked or moved only when its
  row is past that point or refused, and the cursor never passes an unsettled mail, so a `CLAIMED` mail is still
  unmarked in the polled folder. A stale claim (past `eddi.email.inbound.stranded-after`) is given back to `RECEIVED` —
  by the next poll that meets it, or by the inbound sweep — and the poll fetches and processes it again
  (`stranded_recovered{outcome=requeued}`). The sweep deliberately opens no IMAP session: the poller owns the mailbox
  under its schedule's cluster claim. Only `TURN_SUBMITTED` without a recorded conversation (a crash inside
  `startConversation`, whose `CONVERSATION_START` may have called tools) stays `REFUSED(stranded)`, now also with the
  once-a-day owner notice reason `lost`.
- **Servers without custom keywords** (Exchange Online, some hosters): marking checks `PERMANENTFLAGS` for `\*` or
  `$EddiProcessed`; without them — or when the server refuses the STORE anyway — no keyword is set, the poll goes on
  (cursor + ledger), one WARN per integration per node, and the new `eddi.email.poll.marked{result}` counter (dashboard
  panel) shows `nothing`. Not refused at save time: saving does not connect to the mailbox. The docs recommend
  `move:<folder>` for Exchange.
- **Manager:** switching an existing channel's type drops the old type's keys (`platformConfigForType`); switching to
  email starts from the fixed mode/transport keys, and nothing resurfaces on a round trip.
- **No reply path, pinned:** `EmailTriggerModeSendsNothingTest` scans `integrations/email/` and fails if anything but
  the two staff notifiers (`EmailOwnerNotifier`, `HitlEmailNotifier`) references `SystemMailer`, an SMTP transport or the
  outbound renderer. Phase 3 changes that test on purpose.
- Not handled yet: a node that dies after starting a conversation but before the queued turn ran is resolved as `DONE`.

### Review follow-up 2: one start per mail, no partial integration list, CORS

- **A retried mail reuses the conversation an earlier attempt started.** When the turn failed after
  `startConversation` (a quota, a busy executor, a failed `say`), the row went back to `RECEIVED` and the retry started
  a second conversation, so `CONVERSATION_START` (which may call tools) ran again — for any mail without a thread
  mapping (`correlateThreads` off, or unauthenticated). `EmailInboundProcessor` now reads the row's `conversationId` on
  the retry and sends the turn there; a busy one defers, an ended one starts a new conversation (as a mapped one does).
  Chosen over refusing such mails, which would drop every mail that met a passing rate limit.
- **`EmailIntegrationDirectory.scan()` no longer returns a partial list.** A store read failure on one integration was
  swallowed per descriptor, so address uniqueness at save time (fail closed, 503), GDPR identity expansion and schedule
  ensuring could silently miss it. `find()` now throws `StoreUnavailableException`, which `scan()` lets through; only an
  unparseable descriptor is skipped.
- **`X-Eddi-Warnings` is in `quarkus.http.cors.exposed-headers`**, so a Manager on another origin sees a channel save's
  warnings (`CorsExposedHeadersConfigTest`).
- Docs: the ledger lifecycle in [email-integration.md](../email-integration.md#the-message-ledger) names `CLAIMED`; the
  Full Metrics Reference's stranded panel describes `requeued` and the current meaning of `lost`.

### For Phase 3 (conversation mode)

- Replies are `Kind.REPLY` rows, `Direction.OUT`, with `sourceRef` = the conversation step; the loop guard already
  exempts replies to an integration's *own* outbound ids. The thread key (`EmailInboundProcessor.correlationIntent`)
  and `threadRoot` are the mapping to reuse; add the outbound Message-IDs to the root lookup.
- The stranded sweep is where a lost reply is rebuilt from the snapshot (`TURN_SUBMITTED` past its deadline with a
  completed step and no `REPLY` row).
- SMTP for a channel must use `SafeSocketConnector.resolve` (author policy), never `SystemMailer`/`resolveOperator`.
- `email-anon:` identities must never receive a reply (plan §9); `requireAuthentication` is forced on there.

```decision-log
| 2026-10-25 | Email trigger mode never runs a mail twice once a side effect may have happened; a claim that did nothing is given back and the mail, still unmarked in the mailbox, runs again | The CLAIMED state separates "nothing left EDDI" from the point of no return; only a crash while starting the conversation is ambiguous, and that one is REFUSED with an owner notice | Refusing every stranded claim (lost mail that had run nothing); re-running submitted turns (double side effects); a sweep that opens its own IMAP session (two nodes on one mailbox) |
| 2026-10-25 | The thread mapping key of a correlated email conversation includes the sender's address | Message-IDs travel in every forward; under identityMode integration all senders share a user id, so a thread-only key lets anyone join any thread | An explicit sender field checked after lookup (one more stored fact and a check that can be forgotten); correlating by user id only (unsafe in integration mode) |
| 2026-10-25 | Unauthenticated email senders get a one-off email-anon id with no user-memory store and no memory tool, enforced in ConversationService and ContextualToolsProvider | Their From is a claim anyone can make; memory written under it would be read by the next forger | A shared anonymous id (one sender's injection persists for all); relying on the random id never recurring (still writes orphaned memories) |
| 2026-10-25 | Mail-server outages back off inside the poller and are COMPLETED fires with the error in the fire log | The schedule retry ladder dead-letters after a few minutes, which would silently stop a mailbox after a short outage | FAILED fires (dead-letter); SKIPPED fires (no error recorded) |
| 2026-10-25 | Polling state lives in a new IEmailMailboxStateStore (Mongo + Postgres), not the ledger or the cluster KV | The cursor is one value per mailbox that must outlive every ledger row's 30-day retention; the KV buckets are node-local and volatile in single-node mode | A cursor row in the ledger (expires, mixes per-message and per-mailbox facts); NATS KV (lost on restart) |
| 2026-10-25 | Save-time warnings travel as stable codes in an X-Eddi-Warnings response header | The channel save responses carry no body to extend, and codes let the Manager translate them in 11 locales | A response body (changes the create/update contract); log-only warnings (invisible to the author) |
| 2026-10-25 | Mail in the folder before an integration's first poll is never processed | Connecting an integration to a full mailbox must not make an agent work through years of mail | Processing everything unread (cost and side effects nobody expected); a startFrom key (more surface, deferred until asked for) |
```
