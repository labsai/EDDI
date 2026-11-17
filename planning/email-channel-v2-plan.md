# Email Channel v2 — Addressing, Mentions, Thread Replies, Dedicated Gmail Mailboxes

> **Status:** plan, not started (2026-11-16, re-checked 2026-11-17). Written so a coding agent can implement it
> without the conversation that produced it. Every code reference below was checked against `main` at
> `e4fd0d0e5c` and re-checked at `4255562f6a` (the Javadoc pass #1343 shifted lines but changed no behaviour in
> these files); line numbers drift, so search for the named symbol if a line does not match. **Read §3.1 first** —
> an open PR changes what Phases 3 and 5 may build on.
>
> **Builds on** [`email-channel-plan.md`](email-channel-plan.md) (v1, shipped in 6.7.0) and amends two of its
> decisions explicitly (§2.2). Read v1 §9 (Identity), §11 (Threading), §12 (Ledger), §22 (What I would NOT do)
> before starting. The user-facing doc is [`docs/email-integration.md`](../docs/email-integration.md).

## Table of Contents

1. [Goals and non-goals](#1-goals-and-non-goals)
2. [Decisions](#2-decisions)
3. [What exists today (verified)](#3-what-exists-today-verified)
4. [Defects found during review](#4-defects-found-during-review)
5. [Phase 1 — Doc corrections and provider verification](#5-phase-1--doc-corrections-and-provider-verification)
6. [Phase 2 — Mail log, lookup, owner digest](#6-phase-2--mail-log-lookup-owner-digest)
7. [Phase 3 — Addressing, mentions, routing, per-target rules](#7-phase-3--addressing-mentions-routing-per-target-rules)
8. [Phase 4 — Dedicated-mailbox OAuth grant (Google Workspace)](#8-phase-4--dedicated-mailbox-oauth-grant-google-workspace)
9. [Phase 5 — Replies to the thread](#9-phase-5--replies-to-the-thread)
10. [Phase 6 — Consulting another agent](#10-phase-6--consulting-another-agent)
11. [Phase 7 — Backlog](#11-phase-7--backlog)
12. [Cross-cutting checklist for every phase](#12-cross-cutting-checklist-for-every-phase)
13. [Things to verify before relying on them](#13-things-to-verify-before-relying-on-them)
14. [Risks](#14-risks)

---

## 1. Goals and non-goals

**Goals**

1. People address agents inside ordinary email, the way they do in Slack: by putting an agent's address in To, or
   by writing `@finance` in their own text — including when EDDI is only in Cc of a human thread.
2. EDDI is quiet unless addressed (opt-in), and every mail it did not act on leaves a trail an admin can read.
3. Optionally, an answer the whole (internal) thread can see — safely.
4. An agent that owns a conversation can consult another agent when the user or the configuration allows it.
5. Dedicated mailboxes on **Microsoft 365** (works today) and **Google Workspace** (new), the targets enterprise
   customers use.

**Non-goals (deliberately out)**

- Using a person's own mailbox. Only dedicated mailboxes (§2.1 D5).
- Gmail domain-wide delegation (v1 §22, reaffirmed).
- Personal Gmail via OAuth, Outlook.com. Personal Gmail stays "app password, best effort"; Outlook.com unsupported.
- Microsoft delegated (auth-code) mailboxes. M365 keeps OAuth client credentials.
- Reply-to-approve, approval tokens in mail, model-chosen recipients (v1 §22, reaffirmed).
- Watch-only "observer" agents on email (v1 §10 refuses `observeMode` targets; stays so).

## 2. Decisions

### 2.1 Product-owner decisions (2026-11-16)

| # | Question | Decision |
|---|---|---|
| D1 | May people on the thread other than the sender see EDDI's answer? | **Yes, if made safe.** → Phase 5, opt-in, internal recipients only |
| D2 | Silently ignore mail where EDDI is not addressed? | **Yes, but with a trail.** → Phase 2 (mail log) before Phase 3 (addressing) |
| D3 | A mention of another agent inside a running EDDI conversation? | **Consult the other agent, if the configuration (or an approver) allows it; never switch owner.** → Phase 6 |
| D4 | App password vs OAuth for Gmail/Outlook.com? | **Whatever suits enterprise customers** → OAuth for Workspace, client credentials for M365; personal accounts best effort/unsupported |
| D5 | Send as a person's own mailbox, or dedicated only? | **Dedicated mailboxes only**, enforced in code (§8) |

### 2.2 Amendments to `email-channel-plan.md` (binding decisions — record them)

1. **v1 §9 "Reply recipient … never `Cc`"** is amended by Phase 5: with `replyAudience: thread` a reply may
   additionally go to To/Cc recipients of the answered mail that match an explicit internal allowlist. The model
   still never chooses a recipient; the set is derived from headers by one pure function.
2. **v1 §7.1 / §18 Phase 6 "Gmail … depends on the RAG plan's run-as-principal"** is replaced by a
   **mailbox-scoped grant** (`Binding.MAILBOX`, §8). Reason: run-as-principal keys the grant to the EDDI user who
   linked it (an admin), which models the wrong owner for a dedicated mailbox — the admin's offboarding or GDPR
   erasure would silently stop the channel, and nothing ties the grant to the mailbox. A mailbox grant is keyed to
   the mailbox address, usable only by the mail transport, and verified against the provider's account on link and
   refresh. **No domain-wide delegation.**

The "Amended by" notes are already in v1 (§7.1, §9, §18 Phase 6 row), added together with this plan, and its
changelog fragment carries the `decision-log` rows.

### 2.3 Reaffirmed bans (do not implement)

- No reply-to-approve / approve-by-mail (v1 §22). Approvals happen in the Manager or Slack, never by answering a mail.
- No LLM-chosen recipients.
- No reply to an unauthenticated sender; Phase 5 Cc recipients are never "authenticated" — they are bounded by an
  allowlist instead, and the To stays the authenticated sender.
- No domain-wide delegation.

## 3. What exists today (verified)

All paths under `src/main/java/ai/labs/eddi/` unless noted.

| Topic | Fact | Where |
|---|---|---|
| Inbound pipeline | ledger row → DKIM/DMARC → loop guard → block/allow lists → `requireAuthentication` → budgets → identity → **route** → continuation lookup → claim → turn | `integrations/email/EmailInboundProcessor.java` `process…` ~l.235–509 |
| No "addressed" check | Every mail in the polled folder runs, whether the address is in To, Cc or Bcc | same; nothing reads To vs Cc except routing |
| Agent context | `mail.from/fromName/to/cc/subject/date/messageId/inReplyTo/references/replyTo/authentication/signingDomain/quotedHistoryRemoved/attachments/anomalies` | `EmailInboundProcessor.mailData` ~l.766 |
| Routing | plus-tag of the integration address in To+Cc → subject word (first 50) → default target; observers skipped | `integrations/email/EmailTargetRouting.java` |
| Continuation | Conversation mode: In-Reply-To then References (newest first) looked up as this integration's OUT REPLY rows; sender must be the conversation's first sender or (with `anyAuthenticatedParticipant`) a recipient of a SENT reply. **Runs after `route()` and ignores the routed target** | `EmailInboundProcessor.continuedConversation` ~l.835, used ~l.367 |
| Correlation key | `email:<integrationId>:<agentId>:sha256(threadRoot + "\n" + sender)`; threadRoot = first References id, else In-Reply-To, else own id | `correlationIntent` ~l.914, `threadRoot` ~l.815 |
| Identity | `email:<id>:<addr>` (perSender), `email:<id>` (integration), `email-anon:<rand>` (unauthenticated, no memory) | `integrations/email/EmailUserIdentity.java` |
| No-memory gate | `ReservedPrincipals.hasNoPersistentMemory(userId)` = prefix `email-anon:`; read by `ConversationService` (~l.2626, ~l.2772) and `ContextualToolsProvider` (~l.249) | `engine/security/ReservedPrincipals.java` ~l.113 |
| Reply recipient | One address: authenticated From (or single Reply-To with `honorReplyTo`); re-decided on every attempt from the step's `mail` map | `integrations/email/reply/EmailReplyRecipients.decide` |
| Reply cap | `maxRepliesPerThreadPerHour` counts REPLY rows **per conversation** (`countByConversationSince`) | `reply/EmailReplySender.enqueue` ~l.455 |
| Send | `MailTransport.send(integrationId, settings, mime, String recipient)` — one envelope recipient; `SmtpSubmitter.submit` already takes a list | `integrations/email/transport/MailTransport.java` ~l.103, `engine/mail/SmtpSubmitter.java` ~l.84 |
| Renderer | `OutboundMail` and `PlainTextMailRenderer` already support Cc | `engine/mail/OutboundMail.java`, `PlainTextMailRenderer.java` ~l.89 |
| Ledger | One `recipient` per row; no list-by-integration query; Mongo + Postgres twins; Postgres schema via `CREATE TABLE IF NOT EXISTS` / `ALTER TABLE … ADD COLUMN IF NOT EXISTS` in the store class | `integrations/email/ledger/IEmailMessageLedger.java`, `PostgresEmailMessageLedger.java` ~l.47–77, `MongoEmailMessageLedger.java` ~l.74–87 |
| Refusal trail | Refused inbound rows keep reason in `lastError` (`blocked:not_allowlisted`, `unauthenticated:<verdict>`, loop reasons, `unroutable`, …), plus audit entry `taskId: email.inbound` and metric | `EmailInboundProcessor.refuse/audit` |
| REST | Only `GET /channelstore/channels/{id}/email-suppressions` and `POST …/email-suppressions/clear` for email | `configs/channels/IRestChannelIntegrationStore.java` ~l.89–99 |
| Quote stripping | Conservative: trailing `>` block with attribution (6 languages), Outlook header block (EN/DE/FR labels only); keeps inline replies, forwards (`isForwardSubject` disables stripping), signatures | `engine/mail/QuoteStripper.java`, `MimeMessageParser.java` ~l.182; text/plain preferred ~l.715 |
| Settings | Flat `Map<String,String>` → record `EmailChannelSettings`; key sets `COMMON_KEYS`, `TRIGGER_ONLY_KEYS`, `CONVERSATION_KEYS`; unknown keys refused; warnings `email.*` | `configs/channels/model/EmailChannelSettings.java` |
| Targets | `ChannelTarget{name, triggers, type, targetId, observeMode, observeConfig}` — shared by all channel types | `configs/channels/model/ChannelTarget.java` |
| Connections | `OAUTH2_AUTHORIZATION_CODE` must be `PER_USER`; `requireMailLoginTarget` refuses `PER_USER`; JWT bearer sets `sub = iss` | `connections/ConnectionResolver.java` ~l.302–330, ~l.585; `ConnectionStartupGuard.java` ~l.205; `connections/oauth/JwtAssertions.java` ~l.73 |
| Mail login | IMAP/SMTP use `resolveBasicLogin` or `resolveAccessToken(reference, target, null)` with username = integration `address` | `transport/ImapMailTransport.java` ~l.158–162, ~l.225–230 |
| Delegation tool | `converseWithAgent` with `allowDelegation`, `allowedDelegationTargets`, depth limits, user access check; **delegate runs under the caller's userId** | `modules/llm/tools/ConverseWithAgentTool.java`, `modules/llm/impl/DynamicAgentToolsProvider.java` |
| Manager | Email settings form and helpers | `ui/manager/src/components/channels/email-settings.tsx`, `ui/manager/src/lib/email-channel.ts`, `email-channel-messages.ts`, `ui/manager/src/pages/channel-detail.tsx` |
| MCP | `create_channel_integration` describes email keys in its description text | `engine/mcp/McpAdminTools.java` ~l.1319–1334 |
| Tests | Unit: `EmailInboundProcessorTest`, `EmailTargetRoutingTest`, `EmailReplyRecipientsTest`, `EmailReplySenderTest`, `QuoteStripperTest`, `MimeMessageParserTest`, contracts `EmailMessageLedgerContract`, in-memory fakes `InMemoryEmailMessageLedger` …; IT: `EmailImapPollingIT`, `EmailConversationModeIT`, `EmailFullApplicationIT`, `{Mongo,Postgres}EmailMessageLedgerIT` | `src/test/java/ai/labs/eddi/integrations/email/`, `…/engine/mail/` |

### 3.1 Related work on `main` and in flight (checked 2026-11-17)

| Item | State | What it means for this plan |
|---|---|---|
| **#1394** `fix(security): bind DKIM passes to signed headers; enforce MIME and Slack body limits` | **Open** (branch `fix/inbound-mail-slack-hardening`) | Adds `InboundMail.signedView()` and `SignedHeaders`; the processor switches to `parsed = asReceived.signedView()`. Under an aligned pass only **signed** `To`/`Cc` instances survive (no added plus-tag), an unsigned `Subject` becomes `""` (no subject routing), an unsigned `Reply-To` is dropped; `In-Reply-To`/`References`/`Date` are kept even unsigned because **Gmail does not sign the thread headers**. Adds signature-age replay protection and signature-digest dedup, new refusal reasons `unauthenticated:unsigned_message_id` and `signature_seen`, anomaly `HTML_TRUNCATED`. It explicitly declined a default-on "must be addressed to the integration" check (alias/list/BCC delivery) — consistent with `actWhen: always` as the default here. **Phases 3 and 5 depend on it** (§7.2, §9.3): land #1394 first, or carry its signed-view change into Phase 3. Phase 2's reason table must include its reasons |
| **#1302** Google Workspace recipe (`docs/google-workspace.md`) | Merged | Gmail/Drive as **per-user** LLM tools on a `PER_USER` auth-code connection. Phase 4 reuses its prerequisites and flow: connection settings `enabled`, `publicBaseUrl` (the `redirect_uri` is `<publicBaseUrl>/connections/callback`), `credentialEndpointAllowlist` with **both** `https://accounts.google.com` and `https://oauth2.googleapis.com`, an active vault, `POST /connections/{name}/authorize` → `/connections/callback`, the Manager's **Linked accounts** page. Its publishing-status table (Internal: no Google verification; External-Testing: refresh tokens expire after 7 days; External-production with restricted scopes: verification + security assessment) applies to `https://mail.google.com/` too. Keep the mailbox on **its own** OAuth client and connection, so ordinary users are never asked for the full-mail scope |
| **b76757a0e7** Workforce `@mention` hand-off | Merged | A browser-only composer feature: `@` picks an agent/team from the catalog and hands the message over as a **new** conversation; the backend records nothing. Email mentions (§7.3) use the same token rule (`@` at start or after whitespace) but a different meaning inside a running conversation (consult, D3). Say so in `docs/email-integration.md` so users of both are not surprised |
| User-memory visibility | Code fact | `IUserMemoryStore.getVisibleEntries(userId, agentId, groupIds, …)` returns the user's `self` entries **of that agent**, plus `group` and **`global`** entries. Bears on §10.3 |
| `ReservedPrincipalsTest` | Guard | Scans `src/main` for principal-like string prefixes; every new prefix in this plan (`mailbox:`, `email-thread:`, `email-consult:`) must be added to `ReservedPrincipals.PREFIXES` or the build fails |
| Platform Operator | Revision 79 on `main` | Every operator change bumps the revision; operator PRs conflict on that file, so land them one at a time |

## 4. Defects found during review

Fix these inside the phase named; each needs a regression test that fails without the fix (mutation-check it).

| # | Defect | Fix in |
|---|---|---|
| F1 | `docs/email-integration.md` ~l.107–108 promises "admin-linked SERVICE grant" **and** "domain-wide delegation"; v1 plan forbids DWD and the code refuses a SERVICE auth-code grant | Phase 1 |
| F2 | `maxRepliesPerThreadPerHour` is per conversation; a thread with N allowlisted senders allows N× the replies. Doc (l.447) says "per conversation", the key name says "thread" | Phase 3 (§7.7) |
| F3 | A continuation silently ignores the routed target: a reply to agent A's mail that addresses agent B runs in A's conversation with no record | Phase 3 (§7.5) records it; Phase 6 acts on it |
| F4 | Subject-word routing lets any allowlisted (or, with `requireAuthentication: false`, any) sender reach any target by putting a word in the subject | Phase 3 (§7.6 per-target `allowedSenders`) |

## 5. Phase 1 — Doc corrections and provider verification

**Size:** S. **No production code.** One PR (`docs/email-gmail-corrections`).

### 5.1 Doc changes

- `docs/email-integration.md` "Gmail and Google Workspace" (~l.94–109): remove both promises (F1). Say: Workspace
  dedicated mailboxes are planned via a mailbox-scoped OAuth grant (link to this plan); personal Gmail works by app
  password, best effort; domain-wide delegation will not be offered.
- `docs/email-integration.md` `maxRepliesPerThreadPerHour` row: unchanged until Phase 3.
- Add a "Provider notes" subsection for M365 recommending a **shared mailbox** (no licence) with
  `Add-MailboxPermission` scoped to it — after 5.2 confirms it.

### 5.2 Manual verification matrix (record results in the PR description and in `docs/email-integration.md`)

Run a local EDDI (`docker run -d -p 27017:27017 mongo:7`, then dev mode) with a real mailbox per row. For each:
log in (IMAP), poll, `processedAction` `flag` and `move:EDDI/Processed`, conversation-mode reply, inspect the
received reply's `Authentication-Results` (DKIM `d=`, DMARC pass), and the mailbox's Sent folder. Also send a
mail **from** each provider (plain, reply-all with Cc, and a forward) and record its DKIM `h=` list (§13 Q8) —
save the raw `.eml` files as test fixtures for Phase 3 (`MentionExtractorTest`, addressing) and #1394's
signed-view tests.

| Row | Account | Connection | What to find out |
|---|---|---|---|
| V1 | Personal Gmail with 2-Step Verification + app password | `BASIC`, `imaps://imap.gmail.com`, `smtps://smtp.gmail.com` | Works at all; is `$EddiProcessed` accepted (PERMANENTFLAGS)?; does `move:` remove the INBOX label?; duplicate copy in Sent?; DKIM `d=gmail.com` |
| V2 | Google Workspace test user + app password (if the admin allows) | same | Same as V1; DKIM `d=<workspace domain>` when Workspace DKIM is enabled |
| V3 | M365 **shared mailbox**, app registration with `IMAP.AccessAsApp` + `SMTP.SendAsApp`, `Add-MailboxPermission` | `OAUTH2_CLIENT_CREDENTIALS` | IMAP XOAUTH2 to a shared mailbox; **SMTP AUTH XOAUTH2 with an app-only token for a shared mailbox** (unverified — Microsoft's docs are the source); `Set-CASMailbox -SmtpClientAuthenticationDisabled $false` needed? |
| V4 | M365 user mailbox (regression of the documented path) | same | Still works |

Outcome decides Phase 4 details (`processedAction` default for Gmail, whether Gmail needs `move:`).

**Acceptance:** doc no longer promises DWD or a SERVICE auth-code grant; matrix filled in; `DocumentationLinksTest`
and the other `Documentation*Test`s green.

## 6. Phase 2 — Mail log, lookup, owner digest

**Size:** M. **Why first:** D2 — Phase 3 starts refusing mail as `not_addressed`; admins need to see why before that
ships. Branch `feat/email-mail-log`.

### 6.1 Store

Add to `IEmailMessageLedger` (both twins + in-memory fake `InMemoryEmailMessageLedger` + `EmailMessageLedgerContract`):

```java
record LedgerQuery(Direction direction, Kind kind, State state, String reasonPrefix,
                   Instant since, Instant until, int limit, String afterCursor) {}
record LedgerPage(List<LedgerEntry> rows, String nextCursor) {}

/** Rows of one integration, newest first, keyset-paginated on (createdAt desc, id desc). limit 1..200. */
LedgerPage findByIntegration(String integrationId, LedgerQuery query);

/** Counts of TRIGGER rows of one integration created in [since, until), grouped by state and reason category. */
Map<String, Long> countInboundByOutcome(String integrationId, Instant since, Instant until);
```

- Reason category = `lastError` up to the first `:` (`blocked`, `unauthenticated`, `loop…`, `unroutable`,
  `not_addressed`, …). Check every reason string `EmailInboundProcessor` and `EmailLoopGuard` produce and list them
  in the doc table (§6.5) — including #1394's `unauthenticated:unsigned_message_id` and its `signature_seen`
  marker rows (those are dedup markers, not mails: filter them out of the default view).
- Index: Mongo `(integrationId asc, createdAt desc, _id desc)`; Postgres
  `CREATE INDEX IF NOT EXISTS idx_email_ledger_integration_created ON email_message_ledger (integration_id, created_at DESC, id DESC)`.
- The cursor is opaque (base64 of `createdAt|id`); a malformed cursor is a 400, not a 500.

### 6.2 REST

In `IRestChannelIntegrationStore` / `RestChannelIntegrationStore`, beside the suppression endpoints and with the
**same authorization** (EDIT on the integration; reuse whatever check `…/email-suppressions` uses):

- `GET /channelstore/channels/{id}/email-log?direction=&kind=&state=&reason=&since=&until=&limit=&after=`
  → `{ rows: [...], nextCursor }`. Remember: Quarkus hands `?x=` to a `String @QueryParam` as `null`, same as absent.
- `POST /channelstore/channels/{id}/email-log/lookup` body `{ "messageId": "<…@…>" }` → the rows (IN and OUT)
  with that Message-ID, or 404. POST keeps Message-IDs (which often contain a domain) out of access logs, like
  the suppression `clear` endpoint does for addresses.
- Row JSON: `id, kind, direction, messageId, state, reason (= lastError), reasonCategory, conversationId,
  counterpart (= recipient: sender for IN, recipient for OUT), attempts, createdAt, updatedAt`.
- 404 when the integration is not `channelType: email`.
- Document both in `docs/email-integration.md` and check `DocumentedRestPathsTest` (it guards documented paths).

### 6.3 Owner digest

- New common key `ownerDigest`: `off` (default) | `daily`. Validation in `EmailChannelSettings.from`; add to
  `COMMON_KEYS`.
- New `EmailOwnerNotifier.Reason.DIGEST`. Once per UTC day per integration (use the existing once-per-day notice
  keys in `IEmailMailboxStateStore`), after 00:05 UTC, send to `ownerNotifyAddresses` filtered by
  `eddi.mail.notify.allowed-recipient-domains` (same policy as the other owner notices). Content: counts per
  outcome/reason category for the previous UTC day from `countInboundByOutcome`, plus a Manager link
  (`/manage/channels/<id>?tab=mail-log`). **No addresses, subjects or bodies.** Nothing when every count is 0.
- Trigger: the existing inbound sweep (`EmailInboundSweeper`, every 5 min) checks whether today's digest is due.
  Do not add a scheduler.

### 6.4 Manager and operator

- `ui/manager`: a **Mail log** tab on `channel-detail.tsx` for email integrations: table (time, direction, sender /
  recipient, state, reason, conversation link), filters (direction, state, reason category), "Look up Message-ID"
  box, "load more" with the cursor. i18n keys in every locale (the Manager has three i18n gates — run the full
  vitest suite, not only `i18n:check`). MSW handlers for mock mode. Tests next to `email-settings.test.tsx`.
- Platform Operator: add `GET …/email-log` and `POST …/email-log/lookup` to
  `ui/manager/src/lib/operator/tool-scopes.ts` (read `ui/manager/AGENTS.md` → Platform Operator first; a POST may
  need explicit classification as read-only), a paragraph in `system-prompt.ts` ("why didn't EDDI answer my mail?
  → look up the Message-ID, read `reason`"), and **bump `operator-revision.json`**.
- MCP: no new tool in this phase (keeps the tool count stable); mention the REST endpoints in the
  `create_channel_integration` description only if it already lists email endpoints.

### 6.5 Docs, metrics, tests

- `docs/email-integration.md`: new "Troubleshooting: why didn't EDDI act on a mail?" section with the reason table,
  the two endpoints, the digest key.
- Metrics: none new (outcomes already counted).
- Tests: contract tests for `findByIntegration` (paging, filters, ordering ties on equal `createdAt`) run against
  both ITs; REST tests for authorization (EDIT required, other integration's rows never returned), malformed
  cursor, non-email integration; digest test (sent once/day, nothing on all-zero, domain filter applied).

**Acceptance:** an admin can find any mail of the last 30 days by Message-ID and see why it was refused; the
operator can answer "why no reply"; digest off by default.

## 7. Phase 3 — Addressing, mentions, routing, per-target rules

**Size:** L. Branch `feat/email-addressing`. Depends on Phase 2 **and on #1394** (§3.1).

**Signed view only.** Addressing (§7.2), routing (§7.4) and the `mail` context must read the message **after**
`signedView()` — the `parsed` variable once #1394 is in. A raw-header read of `To`/`Cc`/`Subject` anywhere in
this phase is a bug: it would let anyone holding a genuine signed mail add a recipient or a subject word and steer
it. Add one test per rule with a replayed signed mail carrying an unsigned prepended `To`, `Cc` or `Subject`.

**Addressing is a noise filter, not a security boundary.** Rules (1) and (3) below read `In-Reply-To`, which
Gmail does not sign, so an authenticated, allowlisted sender can make a mail look addressed. That is acceptable:
who may make EDDI act at all is decided by authentication, `senderAllowlist` and per-target `allowedSenders`
(§7.6); `actWhen` only keeps EDDI quiet on threads it was not asked into. Say this in the docs.

### 7.1 New keys

| Key | Where | Default | Meaning |
|---|---|---|---|
| `actWhen` | common (both modes) | `always` | `always`: today's behaviour. `addressed`: a mail runs a turn only when it is addressed (§7.2); otherwise refused `not_addressed`. Default `always` keeps upgrades unchanged; the Manager preselects `addressed` for **new** integrations |
| `aliases` | common | empty | Extra addresses that count as the integration's own (a distribution list or alias delivering to the mailbox), comma-separated, max 20, normalised with `AddressNormalizer`. Mail **from** them is refused like mail from `address`; they are added to the loop guard's own-address set |

Per **target** (new optional fields on `ChannelTarget`, email-only — refuse non-empty values for other channel
types in `RestChannelIntegrationStore`, mirroring how mode-specific keys are refused):

| Field | Meaning |
|---|---|
| `mentions: [String]` | Handles that address this target in body text: `[a-z0-9][a-z0-9_-]{0,39}`, case-insensitive, unique across the integration's targets, max 10 per target. **Not** `triggers` — triggers also match subject words |
| `addresses: [String]` | Full addresses (aliases, lists) that address this target, e.g. `finance-agent@company.com`; each must also be in the integration's `aliases` or be a plus-address of `address`, so the mailbox actually receives it |
| `allowedSenders: [String]` | Sender patterns (`SenderPatterns` syntax). Non-empty → only an **authenticated** sender matching one may reach this target by any path, the default included (§7.6) |

Update `ChannelTarget` JSON (de)serialisation, the Manager target editor (email only), the MCP
`create_channel_integration` description, `docs/email-integration.md`.

### 7.2 "Addressed" — exact rule

New pure class `integrations/email/EmailAddressing.java`:

```java
static Addressing evaluate(InboundMail mail, EmailChannelSettings s, OwnAddresses own,
                           LedgerLookup ledger, List<MentionHit> mentions)
```

A mail is **addressed** when any of:

1. **Reply to EDDI**: its `In-Reply-To` (only that header, not References) is the Message-ID of an OUT `REPLY` row
   of this integration (`ledger.findByMessage(id, OUT, inReplyTo)`).
2. **Mention**: the strict extractor (§7.3) found at least one handle of a target of this integration.
3. **EDDI in To, newly**: one of the own addresses (`address`, its plus-addresses, `aliases`, target `addresses`)
   is in **To** (not Cc) **and** either the mail has no `In-Reply-To` (it starts a thread) **or** the parent
   (`In-Reply-To`) is not an IN row of this integration (`ledger.findByMessage(id, IN, inReplyTo)` empty → EDDI was
   not on the parent, so it was just added). This handles Outlook's reply-all, which keeps every To recipient in To:
   a human-to-human reply-all on a thread EDDI already received is **not** addressed unless (1) or (2).

Ledger rows expire after `eddi.email.ledger-retention` (30 d): a parent older than that looks "new" — acceptable,
document it.

Result carries `addressed` (bool) and `why` (`reply_to_eddi | mention | to_new | to_thread_start | none`) for the
mail log / context. With `actWhen: addressed` and `addressed == false` → `refuse(ctx, row, "not_addressed", …)` (new
metric outcome `not_addressed`), placed **after** the allow/block lists and authentication (so those reasons win)
and **before** budgets (an unaddressed mail must not consume the sender's daily turns).

Put `mail.addressing = {addressed, why}` into the agent's `mail` context.

### 7.3 Strict mention extractor

New pure class `engine/mail/MentionExtractor.java` (in `engine/mail`, shared with future channels). **Separate from
`QuoteStripper`**; it never changes `mail.text`.

Input: `InboundMail.text()` (already stripped or not — stripping only removes trailing parts, so a top-down scan is
valid on both) and `InboundMail.anomalies()`.

1. **No mentions at all** when anomalies contain `ALTERNATIVES_DIVERGE` (text and HTML say different things).
2. **Head region**: lines from the top, stopping before the first line that is any of:
   - starts with `>` (after optional whitespace);
   - `QuoteStripper.isAttribution(line)` (make it accessible; today package-private ~l.269);
   - the start of an Outlook header block (`QuoteStripper.outlookHeaderStart` logic) **or a generic header-label
     line in any language**: `^\s*\**[\p{L}][\p{L} .'-]{0,30}\**\s*:\s*.*(@|\d{1,2}[:./]\d{1,2})` (a label followed by
     an address or a date/time) — deliberately broader than the stripper, so Spanish/Italian/Dutch Outlook stops too;
   - a forward separator / "Original Message" / underscore rule (reuse the stripper's patterns);
   - a signature delimiter: `^-- ?$` or `^—\s*$`;
   - after 20 non-blank lines or 2,000 characters, whichever first.
   Forwards are allowed: the forwarder's own text above the forward header is the head region.
3. **Token**: `@` preceded by start-of-line, whitespace, `(` or `[` (HTML-only mail converts an Outlook mention to
   `[@Name](mailto:…)`), then a handle `[A-Za-z0-9][A-Za-z0-9_-]{0,39}`, then **not** followed by `@`, or by `.`
   + letter/digit (so `bob@finance.com`, `@finance.com`, URLs and addresses never match). Compare lower-cased to
   the configured `mentions`.
4. Output: ordered distinct `MentionHit(handle, targetName)`; unknown handles ignored (they may be people).

Missing a mention is acceptable (the user re-sends); a false route is not — when in doubt the extractor stops early.

Test corpus (`MentionExtractorTest`, fixtures beside `MailFixtures`): Gmail EN/DE reply, Outlook EN/DE/FR/ES/IT/NL
reply (header block), Apple Mail, inline reply with `> @finance`, signature `Follow us @acme`, quoted EDDI reply
containing "ask @legal", forward with "@finance please process" above the header, `bob@finance.com`,
`@finance.com`, `[@Finance](mailto:x)`, divergent alternatives, 25-line mail with the mention on line 22.

### 7.4 Routing order

Replace `EmailTargetRouting.route` with `RouteDecision route(...)` returning `{target, source, alsoAddressed}`
where `source ∈ {address_to, address_cc, plus_tag, mention, subject, default}`:

1. A target whose `addresses` contains a **To** recipient (first in header order), then a **Cc** recipient.
2. Plus-tag (existing logic), To before Cc.
3. Mention (extractor order).
4. Subject word (existing; after #1394 an unsigned subject under an aligned pass is empty and routes nothing).
5. Default target.

`alsoAddressed` = other distinct targets addressed by steps 1–3 (used by §7.5 / Phase 6). Put `mail.route =
{target, source, alsoAddressed}` into the turn context. Do not add it to the ledger (`sourceRef` is the IMAP
message reference and must stay so); the conversation step already records it.

### 7.5 Continuations (F3)

In `EmailInboundProcessor`, when an existing conversation is found (row / `continuedConversation` / correlation):

- The conversation's agent stays the owner. If the routed target (from addresses/mentions — not default/subject)
  is a **different** target, add `mail.route.redirectRefused = <targetName>` to the context and log it at INFO;
  the turn runs in the owner conversation. Phase 6 turns this into a consult request.
- Test: reply to agent A's mail mentioning `@b` → runs in A's conversation, context carries the refusal, mail log
  shows `DONE`.

### 7.6 Per-target `allowedSenders` (F4)

After routing (and for the continuation owner too): if the chosen target has non-empty `allowedSenders` and the
sender is unauthenticated or matches none → `refuse(…, "blocked:target_not_allowed")`. **No fallback** to the
default target (a fallback would turn a refusal into a silent reroute). Applies to every routing source.

### 7.7 Thread reply cap (F2)

- Add `threadKey` to `LedgerEntry` (nullable string, `sha256(integrationId + "\n" + threadRoot)`), Postgres
  `ALTER TABLE email_message_ledger ADD COLUMN IF NOT EXISTS thread_key TEXT` + index
  `(integration_id, thread_key, kind, created_at)`, Mongo field + index. Set it on REPLY rows at enqueue
  (`threadRoot` from the step's mail map, same function as `EmailInboundProcessor.threadRoot` — move it to a shared
  helper).
- `maxRepliesPerThreadPerHour` now counts REPLY rows by `threadKey` (not suppressed), cluster-wide. Rows without
  `threadKey` (written before the upgrade) do not count. Update the doc row and add an upgrade note
  (`docs/upgrading-from-6.7.md` or the current upgrade page).
- `LedgerEntry` is a record with a fixed constructor used in many places: add the field at the end and update every
  `new LedgerEntry(...)` (search the tree including tests).

### 7.8 Docs, Manager, operator, tests

- Doc sections: "Addressing EDDI" (the three rules, Outlook/Gmail reply-all behaviour, why it is a noise filter),
  "Mentions" (and how they differ from the Workforce composer's `@mention`, which starts a new conversation —
  §3.1), "Routing order" (signed headers only), "Per-target senders", updated keys table.
- Manager: `actWhen` toggle (preselect `addressed` on create), `aliases`, per-target mentions/addresses/allowed
  senders in the target editor for email.
- Operator prompt + revision bump (new keys an admin will ask about).
- Tests: `EmailAddressingTest` (each rule, Outlook-style reply-all not addressed, newly-added-to-To addressed,
  retention edge), `EmailTargetRoutingTest` extended, `EmailInboundProcessorTest` for `not_addressed` ordering
  (refused before budget, after allowlist), continuation redirect, `target_not_allowed` without fallback; Postgres
  and Mongo IT for `threadKey` counting.

**Acceptance:** with `actWhen: addressed`, EDDI in Cc of a human thread runs nothing and each mail appears in the
mail log as `not_addressed`; `@finance` in the sender's own text routes to finance; the same text inside quoted
history, a signature or an inline-quoted line does not; existing integrations behave exactly as before.

## 8. Phase 4 — Dedicated-mailbox OAuth grant (Google Workspace)

**Size:** L. Branch `feat/email-mailbox-grant`. Independent of Phases 2–3 (can run in parallel in its own worktree);
touches `connections/` heavily — read `docs/connections.md` fully first.

### 8.1 Model

- New `Binding.MAILBOX`, valid **only** with `authType: OAUTH2_AUTHORIZATION_CODE`. Update the model validation and
  `ConnectionStartupGuard` (~l.205: today any binding other than `PER_USER` is an error for auth-code).
- A MAILBOX connection holds **one grant per mailbox address**, stored in the existing grant store under the
  principal `mailbox:<normalised address>`. Add `mailbox:` to the reserved prefixes in `ReservedPrincipals` so no
  user, token claim or email identity can ever carry it.
- New connection field `oauth.accountCheck`: `google-gmail-profile` (required for MAILBOX; enum so more providers can
  be added). It names how the grant's account is verified (§8.3).

### 8.2 Where a MAILBOX grant can be used

- **Only** the mail transport. Add `ConnectionResolver.resolveMailboxAccessToken(String reference, URI target,
  String mailboxAddress)`; it requires `Binding.MAILBOX`, the target on `baseUrlAllowlist`, and resolves the grant
  of principal `mailbox:<address>`. Call it from `ImapMailTransport` (~l.162 SMTP, ~l.230 IMAP) when the
  connection's binding is MAILBOX.
- Every other entry point (`resolve`, `resolveAccessToken`, `resolveBasicLogin`, discovery, ingestion) refuses a
  MAILBOX connection with `UNSUPPORTED_PLACEMENT` and a message naming the reason. Add a test per entry point.
- `requireMailLoginTarget` accepts MAILBOX.

### 8.3 Linking (admin-only)

REST, beside the existing authorization endpoints in `connections/rest/RestConnectionAuthorization.java`. That class
already runs the per-user flow `docs/google-workspace.md` describes (`POST /connections/{name}/authorize` →
provider → `GET /connections/callback`, with state and PKCE, gated by the connection settings `enabled`,
`publicBaseUrl` and `credentialEndpointAllowlist`). **Do not build a second flow**: add a mailbox variant whose
state records "mailbox link for `<address>` by admin `<principal>`", and branch on that in the existing callback
(store under `mailbox:<address>` instead of the caller). Read the class end to end first.

- `POST /connections/{name}/mailbox-grants/authorize` body `{ "address": "eddi@company.com" }`, role `eddi-admin`
  only → returns the provider authorization URL with `login_hint=<address>`, `access_type=offline`,
  `prompt=consent` (Google needs both for a refresh token). The state binds the admin principal, the connection and
  the address.
- Callback: exchange the code, then **before storing**:
  1. **Account check** (`google-gmail-profile`): `GET https://gmail.googleapis.com/gmail/v1/users/me/profile` with
     the new access token via `OutboundHttp` (never a raw `HttpClient`); `emailAddress` normalised must equal the
     requested address. Requires `https://gmail.googleapis.com` on the connection's `baseUrlAllowlist`.
  2. **Dedicated check**: the address must not equal the linking admin's verified `email` claim.
  3. Store under `mailbox:<address>`; audit-ledger entry `connection.mailbox.link` (connection, address, admin).
- `POST /connections/{name}/mailbox-grants/unlink` body `{address}` (admin) → deletes the grant, audit entry.
- `GET /connections/{name}/mailbox-grants` (admin) → addresses and grant state (no tokens).
- **Re-check** the account on every refresh that mints a new access token (hook into `OAuthTokenService`'s refresh
  completion for MAILBOX grants) — a mismatch deletes the grant, logs `[SECURITY]`, and the next poll fails
  `NOT_CONNECTED`.
- `invalid_grant` on refresh → poll fails with category `NOT_CONNECTED`; `EmailOwnerNotifier` gets a new reason
  `MAILBOX_NOT_LINKED` (once a day); the mail log/Manager shows "Re-link mailbox".

### 8.4 Email integration save

- `EmailChannelSupport` (~l.98): a MAILBOX connection is accepted; if no grant exists for `address` yet, save
  succeeds with warning `email.mailbox-not-linked` (polling backs off with `NOT_CONNECTED` until linked).
- Deleting the integration does **not** unlink (the grant belongs to the mailbox); the Manager offers "also unlink".
- GDPR: a dedicated mailbox is not a person; erasure by an address that equals a linked mailbox must not delete the
  grant silently — document.

### 8.5 Google setup (document in `docs/email-integration.md`, verify every step)

Prerequisites are those of `docs/google-workspace.md` → Prerequisites (link there rather than repeating them):
connection settings `enabled`, `publicBaseUrl`, `credentialEndpointAllowlist` with both Google origins, active
vault, redirect URI from `GET /connectionstore/settings`. The dedicated mailbox does **not** need EDDI's own OIDC to
verify it (it is not a person signing in to EDDI), but the linking admin must be an authenticated `eddi-admin`.

1. Google Cloud project (the recipe's project is fine); OAuth consent screen **User type: Internal** — per the
   recipe's publishing-status table no Google verification is needed, and Internal grants do not hit the 7-day
   Testing expiry (re-check Google's current policy). Scope: `https://mail.google.com/` (IMAP + SMTP need the full
   scope; check that `users.getProfile` accepts it). **A separate OAuth client** from the per-user recipe, so the
   full-mail scope is never on the consent screen ordinary users see.
2. OAuth client (Web application), redirect URI = `<publicBaseUrl>/connections/callback`.
3. Workspace admin: IMAP access enabled for the dedicated user.
4. Connection:

```json
{
  "name": "gws-support-mailbox",
  "authType": "OAUTH2_AUTHORIZATION_CODE",
  "binding": "MAILBOX",
  "oauth": {
    "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth",
    "tokenUrl": "https://oauth2.googleapis.com/token",
    "clientId": "<client-id>",
    "clientSecret": "${vault:gws-mailbox-client-secret}",
    "scopes": ["https://mail.google.com/"],
    "extraAuthParams": { "access_type": "offline", "prompt": "consent" },
    "accountCheck": "google-gmail-profile"
  },
  "baseUrlAllowlist": ["imaps://imap.gmail.com", "smtps://smtp.gmail.com", "https://gmail.googleapis.com"]
}
```

5. Link: Manager → Connections → the connection → "Link mailbox" → address → Google sign-in **as the dedicated
   account**.
6. Integration: `host: imap.gmail.com`, `smtpHost: smtp.gmail.com`, `smtpPort: 465`, `processedAction` per Phase 1
   results (probably `move:EDDI/Processed`).

Check the credential-endpoint allowlist (`docs/connections.md` "two allowlists") covers `oauth2.googleapis.com`.

### 8.6 Manager, operator, docs, tests

- Manager: connection form allows `MAILBOX` for auth-code; "Mailbox grants" panel (list, link, unlink); email
  settings show grant state.
- Operator: prompt paragraph (how to connect a Workspace mailbox, what `email.mailbox-not-linked` means); allow-list
  the GET only; revision bump.
- `docs/connections.md`: the MAILBOX binding, why it exists, what it cannot do.
- Tests: resolver refusal per entry point; link flow with a fake token endpoint and fake profile endpoint (mismatch
  refused, own-email refused, success stored under `mailbox:`); refresh re-check deletes on mismatch;
  `ReservedPrincipals` refuses `mailbox:`; startup guard accepts MAILBOX only with auth-code; IMAP transport uses the
  mailbox token (extend `EmailImapPollingIT` with an XOAUTH2-capable test server if feasible, otherwise a unit test
  of the login choice).

**Acceptance:** a Workspace dedicated mailbox polls and replies via OAuth; the grant cannot be used by httpcalls,
MCP, ingestion or LLM tools; linking a mailbox whose Google account differs from the address is refused.

## 9. Phase 5 — Replies to the thread

**Size:** L. Branch `feat/email-thread-replies`. Depends on Phase 3 (`actWhen`, addressing) and therefore on
#1394's signed view.

### 9.1 Keys (conversation mode only — add to `CONVERSATION_KEYS`)

| Key | Default | Meaning / validation |
|---|---|---|
| `replyAudience` | `sender` | `sender`: today. `thread`: also Cc the answered mail's other To/Cc recipients that pass §9.3. Requires `actWhen: addressed` and non-empty `replyAllowedRecipients`, else refused at save. Warning `email.reply-audience-thread` |
| `replyAllowedRecipients` | empty | `SenderPatterns` syntax (`*@company.com`, `*@*.company.com`, exact addresses), max 50. Only recipients matching one are Cc'd |
| `maxReplyRecipients` | `10` | 1–50, total Cc count. Over it → reply to the sender only, row note `cc_capped` |
| `listAddresses` | empty | Patterns never Cc'd (distribution lists, groups) |

### 9.2 Identity — the memory leak this prevents

Per-sender identities load the sender's long-term memory into the turn; with more readers, that memory (and data
the sender's tools fetch) would reach colleagues. A conversation's user id is fixed at start, and later mails can
add readers, so the decision must be per **integration**, not per mail:

- `replyAudience: thread` ⇒ every conversation of the integration runs as
  **`email-thread:<integrationId>:<sha256(threadRoot)>`** (non-participants: `…:<sha256(threadRoot + "\n" + sender)>`,
  §9.4). Add the `email-thread:` prefix to `ReservedPrincipals` (reserved) and to `hasNoPersistentMemory` (no
  long-term memory read or written, no memory tools). Conversation memory (the thread itself) works as usual — every
  reader already has the thread.
- `EmailReplyRecipients.isAuthenticatedIdentity` accepts `email-thread:<thisIntegrationId>:…`; the step's mail map
  still has to show `aligned-pass` and the same From as the ledger row (unchanged checks).
- `identityMode` is ignored (refuse a non-default value at save) when `replyAudience: thread`.
- Document the trade-off: no per-person long-term memory on such an integration.

### 9.3 Audience — one pure function

New `integrations/email/reply/EmailReplyAudience.java`:

```java
static Audience compute(EmailChannelSettings s, Map<String,Object> stepMail, String sender, OwnAddresses own)
// Audience(String to, List<String> cc, List<String> dropped /* reasons only, for logs */)
```

- `to` = the authenticated sender (or single Reply-To under `honorReplyTo`, as today).
- The step's `mail` map must be built from the **signed view** (§3.1, #1394), so `to`/`cc` hold only signed
  header instances. Otherwise a replayed genuine mail with an unsigned prepended `Cc: ceo@company.com` would make
  EDDI mail an internal person the attacker chose. Test exactly that case.
- `cc` = (stepMail.to ∪ stepMail.cc), normalised, minus own addresses/aliases, minus `to`, minus anything matching
  `listAddresses`, keeping only addresses matching `replyAllowedRecipients`, header order, deduplicated; capped
  (`maxReplyRecipients` → empty cc + `cc_capped`).
- Used in **two** places with identical input: the inbound processor (to put `mail.audience = {to, cc}` into the
  turn context **before** the turn runs, so the agent knows who will read the answer) and `EmailReplyRecipients`
  at every send attempt.
- On every attempt the sent Cc = stored Cc ∩ freshly computed Cc ∖ suppressed (settings may only narrow, never
  widen, between attempts).

### 9.4 Conversation lookup in thread mode

- Correlation key without the sender: `email:<id>:<agentId>:sha256(threadRoot + "\nthread")`.
- **Hijack guard still applies** (a Message-ID is no secret): continuing via that key or via
  `continuedConversation` requires the sender to be a participant = the conversation's first sender **or** a To/Cc
  recipient of a SENT reply in that conversation (extend `hasSentReply` to the recipients table, §9.5). Anyone else
  gets their own conversation keyed with their address (and the per-sender thread identity above).

### 9.5 Ledger and sending

- New child store `email_reply_recipients` (Mongo collection / Postgres table): `rowId, integrationId,
  conversationId, address, role (TO|CC), createdAt, expiresAt`; indexes on `rowId`, `(conversationId, address)`,
  `address`. Written at enqueue for every recipient. The row's own `recipient` stays the To.
- `hasSentReply(conversationId, address)` → true if the address is the row recipient **or** a CC in the child store
  of a SENT row.
- GDPR: `findByRecipient`/`deleteByRecipient` and `EmailLedgerGdprParticipant` must cover the child store; erasing an
  address also identifies `email-thread:` conversations it took part in (inbound rows with that sender, reply rows
  with it as Cc) — decide with the owner whether those conversations are deleted or the address's mails redacted
  (§13 Q4); implement the decision.
- Suppression: check every address; drop suppressed Cc recipients, keep sending to the rest; a suppressed To still
  suppresses the whole row (unchanged).
- Bounces (`EmailReplySender.applyDeliveryReport` ~l.710): a permanent failure for a Cc recipient suppresses that
  address and leaves the row `SENT`; for the To, unchanged.
- `MailTransport.send(…, List<String> envelopeRecipients)`; `ImapMailTransport` passes the list to `SmtpSubmitter`.
- `EmailReplyComposer.render(settings, Audience, thread, body, messageId)` sets To and Cc headers.
- Audit `email.reply`: `recipientDomain`/`recipientPseudonym` for the To plus `ccCount` and `ccDomains` (no Cc
  pseudonyms list longer than 10).
- Metrics: `eddi_email_outbound_recipients` distribution summary; outcome `cc_capped` counter.

### 9.6 Loops

Replies keep `Auto-Submitted: auto-replied`; colleagues' auto-replies and other EDDI instances' replies are already
refused by `EmailLoopGuard`. Add a test: two integrations Cc'd on one thread, each with `replyAudience: thread`,
exchange at most one reply each (the second sees the first's `Auto-Submitted` and refuses).

### 9.7 Docs, Manager, operator, tests

- Doc section "Replying to the thread" with the safety rules and the memory trade-off; amend the "Who gets a reply"
  section.
- Manager: audience select, recipient patterns, list addresses, max recipients; the conversation view shows the
  audience of each reply.
- Operator prompt + revision bump.
- Tests: `EmailReplyAudienceTest` (external Cc dropped, list dropped, cap, own aliases dropped, narrowing between
  attempts never widens), memory isolation (an `email-thread:` conversation reads and writes no long-term
  property — assert through the store, not a mock), participant guard (forwarded Message-ID by a non-participant
  starts a new conversation), GDPR export/erasure covers Cc, bounce of a Cc recipient, Postgres + Mongo IT for the
  child store, `EmailConversationModeIT` with a Cc audience.

**Acceptance:** with `replyAudience: thread`, an internal colleague in Cc receives the reply, an external Cc does
not, a list address does not; the turn ran without any long-term memory; nothing changes for `sender`.

## 10. Phase 6 — Consulting another agent

**Size:** M. Branch `feat/email-agent-consult`. Depends on Phase 3 (§7.5 `redirectRefused`).

### 10.1 Behaviour

When a mail continues agent A's conversation and addresses target B (address or mention, §7.5):

- If B is in A's target `consultable` list → context `mail.consultRequested = [{target: "B", agentId}]` instead of
  `redirectRefused`. A's agent may call `converseWithAgent(B)`; A stays the only voice in the thread.
- Otherwise → `redirectRefused` as in Phase 3; A can tell the user it cannot involve B.

New per-target field `consultable: [targetName]` (email only, names of other targets of the same integration).
Save-time checks: every name exists; warning `email.consult-not-enabled` when A's agent does not have
`converseWithAgent` enabled with B's agent id in `allowedDelegationTargets` (read how `DynamicAgentToolsProvider`
enables the tool for a non-group agent — v1 only documents it for groups; if it cannot be enabled outside a group,
that is part of this phase).

### 10.2 Approval

**Not by mail** (v1 §22). If a consultation must be approved, the agent designer puts `converseWithAgent` under the
agent's `toolApprovals` (existing HITL, `docs/hitl.md`); the sender gets the existing in-thread pause notice and an
approver decides in the Manager or Slack. Document this recipe; add no new key.

### 10.3 Isolation (must verify — this is the risky part)

`ConverseWithAgentTool` runs the delegate under the **caller's userId**. For an email turn that is
`email:<id>:<sender>` (or `email-thread:…`, which has no memory anyway). Checked on `main`:
`getVisibleEntries(userId, agentId, …)` gives B the sender's `self` entries **of B only**, but also every
`global` (and `group`) entry of the sender, and B could write new entries about the sender. B's tools would also
act under the sender's id (its `PER_USER` connections stay refused: email identities are `SELF_ASSERTED`).
Required:

- B's conversation must not read or write the sender's long-term memory: when the caller is an email identity, run
  the delegate as a no-memory identity `email-consult:<integrationId>:<random>`, added to `hasNoPersistentMemory`
  and to `ReservedPrincipals.PREFIXES` (`ReservedPrincipalsTest` enforces the latter). Do not rely on agent
  scoping: `global` entries are visible across agents.
- The workspace access check (`useCheck`) must still pass for the email identity's integration owner; test with
  workspaces enforced.
- B's answer reaches A as tool output (data). B's conversation has no email ledger rows, so `EmailReplySender` never
  sends anything for it — add a test that proves no mail leaves for the delegate conversation.
- Depth: email-originated delegation depth max 1.

### 10.4 Tests, docs

- Consult allowed / not allowed; approval pause (existing HITL) sends the in-thread notice; memory isolation test
  through the real store; no outbound mail from B; depth limit.
- Doc section "Bringing in another agent"; operator prompt + revision bump.

**Acceptance:** "@legal can you check clause 4?" in a reply to finance's mail makes finance consult legal (when
configured), and the single reply comes from finance; legal never sees the sender's memory and never mails anyone.

## 11. Phase 7 — Backlog

Prioritised, each its own small plan before building:

1. **Reply review / drafts**: `replyReview: never | always | multiRecipient | firstContact` — hold a reply for
   approval in the Manager (reuse HITL), or `IMAP APPEND` it to Drafts for a person to send.
2. **HTML replies** with a configurable signature and the quoted original below (today `text/plain` only).
3. **Human takeover** of a thread (`planning/human-takeover-design.md` is under review).
4. **Setup wizard**: provider presets (M365 shared mailbox, Workspace mailbox grant, generic IMAP), test login,
   DNS check of the integration domain's DKIM/DMARC, "send yourself a test mail" with the computed verdict.
5. **Outbound attachments** (generated documents) in replies.
6. **Follow-ups into an existing thread** from a schedule (only to the thread's verified participants).
7. **IMAP IDLE** for seconds-level latency.
8. **Inbox actions** for the agent (label, move, mark urgent) as tools, scoped to the integration's mailbox.
9. **Calendar invites** (`.ics`) read/reply.
10. **`.eml` replay** through the full pipeline in agent test runs.
11. **ARC** for forwarded/list mail that breaks DKIM.

## 12. Cross-cutting checklist for every phase

From `AGENTS.md` — follow it; the highlights:

- Branch from `origin/main` with a `feat/…`/`docs/…` name (`git fetch origin main && git checkout -b feat/x
  origin/main`). Never commit to `main`; rename tool-generated `claude/*` branches before the first commit.
- One changelog fragment per PR: `docs/changelog.d/YYYY-MM-DD-<slug>.md`, relative links with one extra `../`;
  decision rows in a fenced `decision-log` block (Phase 1: the two amendments; Phase 4: MAILBOX binding; Phase 5:
  Cc amendment + `email-thread:` identity).
- Both datastores: every store change in the Mongo **and** Postgres twin, the in-memory fake and the contract test;
  Postgres schema changes via `ADD COLUMN IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` in the store class.
- `LedgerEntry` constructor changes touch many call sites (main and test) — search them all.
- Style gates: `./mvnw formatter:format`, then `./mvnw compile` (Checkstyle unused imports fail the build). On a
  type-signature refactor run `./mvnw clean compile` (incremental builds hide breaks).
- Before pushing, the plain `./mvnw test` (targeted `-Dtest` skips `ImportStyleTest`, `Documentation*Test`,
  `ChangelogFragmentTest`, `DocumentedRestPathsTest`, `BuildQualityGatesTest`). Redirect Maven output to a file;
  never pipe it through `grep`/`head`.
- ITs: `./mvnw verify -DskipITs=false -Dit.test='Email*IT,*EmailMessageLedgerIT'` with Docker running.
- Manager changes: Node 26 for vitest, `npm run typecheck` (not `tsc --noEmit`), the full vitest run for i18n.
  Any change to `system-prompt.ts` or `tool-scopes.ts` bumps `operator-revision.json`.
- Docs: update `docs/email-integration.md` in the same PR as the behaviour; new pages go into `docs/SUMMARY.md`.
- New principal prefixes (`mailbox:`, `email-thread:`, `email-consult:`) go into `ReservedPrincipals.PREFIXES`;
  `ReservedPrincipalsTest` fails otherwise.
- Operator PRs land one at a time (each bumps `operator-revision.json`, which conflicts).
- Before starting Phase 3 or 5, check whether #1394 has merged; if not, coordinate (it rewrites
  `EmailInboundProcessor` and `MimeMessageParser`, so a parallel branch will conflict heavily).
- Ask before pushing; never force-push; CodeRabbit review must be complete before merge (see `AGENTS.md` §2).
- Mutation-check each security test (revert the guard, see the test fail).

## 13. Things to verify before relying on them

Marked unverified during planning; check against current vendor docs or real tenants:

1. Gmail IMAP: custom keyword `$EddiProcessed` accepted? `MOVE` semantics with labels? (Phase 1 V1)
2. Google: Internal consent-screen apps need no verification for `https://mail.google.com/` and their refresh
   tokens do not hit the 7-day Testing expiry (stated in `docs/google-workspace.md` from #1302, still re-check
   Google's current policy); `users.getProfile` works with that scope. (Phase 4)
3. Microsoft: app-only SMTP AUTH (XOAUTH2) for a **shared** mailbox. (Phase 1 V3)
4. **Open question for the owner — GDPR in thread mode:** erasing one participant's address from an
   `email-thread:` conversation: delete the whole conversation, or redact that person's inputs? (Phase 5)
5. Outlook reply-all keeps original To recipients in To; Gmail moves them to Cc. The addressing rule (§7.2) is
   designed to work either way — confirm with fixtures captured from real clients.
6. What Outlook's and Gmail's text/plain parts contain for a native @mention of a contact (the HTML side becomes a
   `mailto:` link). Capture real fixtures for `MentionExtractorTest`.
7. Whether `converseWithAgent` can be enabled for a non-group agent today (Phase 6 §10.1). Memory visibility is
   settled (§3.1).
8. Which headers Gmail, Workspace and M365 put in DKIM `h=` — in particular `To`, `Cc` and `Subject`. After #1394 an
   unsigned `To`/`Cc` instance is invisible to routing, addressing and the thread audience; if a major provider
   does not sign `Cc`, Phase 5 Cc'd recipients would silently be empty for its senders. Capture real mails in
   Phase 1 and record the `h=` lists.

## 14. Risks

| Risk | Mitigation |
|---|---|
| Mentions mis-route because quoting differs by client/language | Strict head-region extractor that stops early; mentions only add routing, never widen recipients; per-target `allowedSenders` |
| Thread replies leak data to colleagues | Internal-only allowlist, list exclusion, cap, no long-term memory on such integrations, audience in the agent context, optional reply review (backlog 1) |
| Mailbox grant used outside the mail transport | Resolver refuses MAILBOX everywhere else; reserved `mailbox:` principal; tests per entry point |
| Grant linked to a person's own account | Provider account must equal the dedicated address; admin's own email refused; re-checked on refresh |
| Upgrade surprises | Every new behaviour is opt-in (`actWhen: always`, `replyAudience: sender` defaults); only F2's cap semantics change (stricter), with an upgrade note |
| Consulted agent acts for the sender | No-memory delegate identity for email callers, depth 1, approval via existing `toolApprovals` |
