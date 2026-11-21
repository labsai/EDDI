# Email Channel v3 — Control: Sender Lists, Review, Approvals, Roles, Send-out, S/MIME

> **Status:** plan, not started (2026-11-18, reviewed against the code the same day). Written so a coding agent can
> implement it without the conversation that produced it. Line numbers drift; search for the named symbol.
>
> **Builds on** [`email-channel-plan.md`](email-channel-plan.md) (v1) and
> [`email-channel-v2-plan.md`](email-channel-v2-plan.md) (v2: mail log, addressing, thread replies, Workspace
> mailboxes, consulting). v2 Phase 2 is PR #1458 (mail log, lookup, owner digest), v2 Phase 4 is PR #1476 (mailbox
> grant). Read v1 §9 (Identity), §22 (What I would NOT do), v2 §2 (Decisions) and §3.1 (#1394, the signed view),
> [`docs/hitl.md`](../docs/hitl.md) and [`docs/hitl-review-and-audit.md`](../docs/hitl-review-and-audit.md) first.
> This plan amends one v2 decision explicitly (§2.2) and moves one v2 backlog item into a phase (§7.2).
>
> **Open PRs this plan relies on** (check their state before starting a phase): **#1394** (DKIM bound to signed
> headers, `signature_seen` replay protection — C5 and C6 depend on it), **#1438** (Slack approval cards for every
> pause — the Slack half of C3/C4), **#1396** (configurable deciders, approver role — who may decide in C3/C4).

## Table of Contents

1. [Goals and non-goals](#1-goals-and-non-goals)
2. [Decisions](#2-decisions)
3. [What exists today](#3-what-exists-today)
4. [Storage of the new settings](#4-storage-of-the-new-settings)
5. [Phase C1 — Sender lists as first-class, editable over REST and MCP](#5-phase-c1--sender-lists-as-first-class-editable-over-rest-and-mcp)
6. [Phase C2 — S/MIME signing and the sending-health check](#6-phase-c2--smime-signing-and-the-sending-health-check)
7. [Phase C3 — Held mail: review instead of drop](#7-phase-c3--held-mail-review-instead-of-drop)
8. [Phase C4 — Approval before acting, and reply review](#8-phase-c4--approval-before-acting-and-reply-review)
9. [Phase C5 — Sender roles: it matters who said it](#9-phase-c5--sender-roles-it-matters-who-said-it)
10. [Phase C6 — Approved send-out to third parties](#10-phase-c6--approved-send-out-to-third-parties)
11. [Phase C7 — The admin view and MCP parity](#11-phase-c7--the-admin-view-and-mcp-parity)
12. [Order and dependencies](#12-order-and-dependencies)
13. [Cross-cutting checklist](#13-cross-cutting-checklist)
14. [Things to verify before relying on them](#14-things-to-verify-before-relying-on-them)
15. [Open questions](#15-open-questions)
16. [What I would NOT do](#16-what-i-would-not-do)

---

## 1. Goals and non-goals

The email channel becomes a way of working with EDDI that is rich and powerful **and** under the admin's control:
every mail is accounted for, nothing consequential happens without the right person's approval, and every lever is
reachable from the Manager, REST and MCP.

**Goals**

1. **Every mail can reach a decision.** Mail EDDI would refuse because the sender is not allowed or not authenticated
   can be held for a human instead of dropped: discard, run once, or run and allow the sender from now on (C3).
2. **Run, but ask first.** A processed mail can still require an approval before the agent acts on it, before its
   reply leaves, or both — on the existing HITL machinery, never by replying to a mail (C4).
3. **It matters who said it.** An authenticated sender's role decides which agents they reach, which tools the turn
   may use, what needs approval and what it may cost (C5).
4. **Cc EDDI and ask it to send something.** "Send customer xyz the offer" from an authorised sender makes a dedicated
   send-out agent mail that customer — recipient from an admin-controlled set, approved by a human, the customer's
   answer handled in a separate, narrow conversation (C6).
5. **Mail from EDDI is verifiably EDDI's.** Mail the integration sends is S/MIME-signed by default (product-owner
   decision), and the admin is told plainly when the domain's DKIM signing is not active (C2).
6. **Admin-friendly.** Sender lists, roles, held mail and policies are one screen per integration, and every one of
   them is an MCP tool so an agent (the Platform Operator, or the admin's own) can manage them (C1, C7).

**Non-goals**

- S/MIME **encryption** (needs every recipient's certificate; a separate plan if ever).
- Approving anything by mail (v1 §22 — reaffirmed).
- Any outgoing mail caused by an unauthenticated mail, even after a human released it (§16).
- A model typing a recipient address outside an admin-controlled set (§2.2).
- Using a person's own mailbox; domain-wide delegation (v2 §2.1 D5, reaffirmed).

## 2. Decisions

### 2.1 Product-owner decisions (2026-11-18)

| # | Question | Decision |
|---|---|---|
| D6 | Sign EDDI's mail? | **Yes, S/MIME, supported and on by default**; can be switched off in the integration's JSON (C2) |
| D7 | Mail EDDI does not accept (not allowed, unauthenticated) | **The owner hears about it and decides** whether it is processed at all (C3) |
| D8 | Mail EDDI processes that asks for something | **It can be made to ask first**, via HITL, before it acts (C4) |
| D9 | Allow- and blocklists | **Configurable, and editable over MCP** so an agent can manage them (C1) |
| D10 | Who sent it | **Matters**: sender roles drive targets, tools, approvals, limits (C5) |
| D11 | "Cc EDDI, send our customer xyz …" | **Yes**, through a dedicated, narrowly scoped send-out agent, approved by a human (C6) |

### 2.2 Amendment (binding — record it)

**v2 §2.3 "No LLM-chosen recipients"** is narrowed, not dropped. v1 §22 already allowed "a generic `sendEmail` tool"
as "the allowlisted, approval-gated Phase 7 item"; C6 is that item, specified:

- The model never sends to an address outside an admin-controlled set (`outboundRecipients`, §10.2). Anything outside
  it is refused in code before an approval is asked for.
- A send to an external recipient (outside `internalDomains`) is approved by a human **every time** by default, bound
  to the exact resolved recipient, subject and body (§10.3).
- Only a sender whose role allows send-out (C5) can cause one; the tool is not assembled otherwise.

Everything else in v2 §2.3 stands. v1 §22, v2 §2.3 and v2 §11 item 1 get "Amended by v3" notes (added together with
this plan).

## 3. What exists today

Verified on `feat/email-mail-log` (PR #1458 = `main` + mail log) on 2026-11-18. Paths under `src/main/java/ai/labs/eddi/`.

| Piece | Where | Relevance |
|---|---|---|
| Settings storage | `ChannelIntegrationConfiguration.platformConfig` is `Map<String,String>`; `EmailChannelSettings` refuses unknown keys and splits keys into common / trigger / conversation sets (`CONVERSATION_KEYS`: `smtpHost`, `smtpPort`, `fromName`, …). Its Javadoc: no secrets in settings, credentials come only from the connection | §4: nested settings need a typed field |
| Sender lists | `senderAllowlist` / `senderBlocklist`, comma-separated, ≤ 200 (`MAX_LIST_ENTRIES`), `SenderPatternSyntax` (`*@*` is already refused); `SenderPatterns`, `AddressNormalizer` in `engine/mail` | C1 |
| Check order | `EmailInboundProcessor`: loop/bounce → `blocked:no_single_sender` → own address **or** blocklist (`blocked:blocklist`) → allowlist (`blocked:not_allowlisted`, an **empty allowlist allows everyone**) → `unauthenticated:<verdict>`. The authentication verdict is computed before these checks but **not stored** on the ledger row | C3 |
| Ledger dispositions | `EmailInboundProcessor.dispositionOf`: only `RECEIVED`/`CLAIMED` are live, every other state is `SETTLED` (marked processed). `State.isTerminal()` treats unlisted states as terminal | C3 adds states explicitly |
| Re-fetch by Message-ID | `EmailMailboxPoller.resubmit` + `EmailInboundProcessor.pendingResubmissions` → `findDueResubmissions` (Mongo/Postgres: `state = RECEIVED`, `kind = TRIGGER`, **conversation not null**) + `resubmissionLost`. Searches the polled and the move folder | C3 extends it |
| Ledger kinds | `TRIGGER`, `REPLY`, `NOTIFY`, `NOTICE` | C6 adds `SENDOUT` |
| Mail log, digest | PR #1458: `findByIntegration`, `GET /channelstore/channels/{id}/email-log` (EDIT), `EmailOwnerNotifier` digest and once-a-day notices — sent through `SystemMailer`, not the integration's SMTP | C3 counts, C7 view |
| Owners | `ownerNotifyAddresses`: up to 5 **mail addresses**, not EDDI principals | C3 notifications |
| Identities | `email:<integrationId>:<address>` (`perSender`), `email-anon:<random>` for an unauthenticated sender (`EmailUserIdentity`; already in `ReservedPrincipals.PREFIXES` and memory-free); `SELF_ASSERTED` provenance; `PER_USER` connections refused | C3, C5, C6 |
| Tool approvals | `hitlConfig.toolApprovals` (`requireApproval`, `exempt` always beats it, rules, `timeoutPolicy`), per-task blocks resolved by `TaskToolApprovalsResolver` (`task-approvals.mode=replace` replaces the agent block). **Request pinning covers HTTP tools only**; other tools have `requestPreview: null`, and amended arguments are not fingerprint-checked. `ApiCallsTask` (rule-driven httpcalls) has **no** approval gate; `McpCallsTask` refuses a gated call instead of pausing | C4 must not rely on merging |
| Rule pauses, edited answers | `PAUSE_CONVERSATION`; `hitlConfig.review.allowAnswerEdits` + `editedAnswer` (`docs/hitl-review-and-audit.md`). A new conversation's `CONVERSATION_START` turn runs before any rule pause | C4 |
| Email pause notice | `EmailHitlResumeObserver` on `HitlPauseCommittedEvent` queues the in-thread notice | C4 |
| Slack approvals | `hitlApprovalChannel` is a key of a **Slack** integration and is read for Slack-originated flows only; #1438 posts cards for every pause | C3/C4 Slack half |
| Group targets | Tool approvals inside group turns are auto-rejected (`system:group`) | C4 refuses `approvalPolicy` on GROUP targets |
| MCP | `list_pending_approvals`, `get_approval_status`, `resume_conversation`; `list/read/create/update/delete_channel_integration` (whole-document update) | C1, C7 |
| Save-time checks | `EmailAgentToolsInspector` — best effort: skips unreadable documents and GROUP targets, runs only when the integration is saved | C1 guard must not rely on it |
| GDPR | `EmailIdentityGdprParticipant` finds conversations by identity, not by the ledger's `conversationId` | C3, C6 |
| Crypto | `org.bouncycastle:bcprov-lts8on` pinned as a literal version in `pom.xml` (a CVE override), no version property | C2 |

## 4. Storage of the new settings

All new v3 settings live in **one typed object**, `emailControl`, on `ChannelIntegrationConfiguration` (next to
`platformConfig`), not in the flat string map: maps, arrays and nested objects cannot be encoded there, and unknown
`platformConfig` keys are refused. Both stores persist it with the rest of the document (Mongo: a subdocument;
Postgres: the configuration's JSON column — verify how the channel store serialises it, §14). Validation lives in
`EmailChannelSettings.validate` (one place for every email rule) and runs on save, on ZIP import and before every
targeted operation. `emailControl` on a non-email integration → 400.

Two exceptions stay where they are: `senderAllowlist` / `senderBlocklist` (existing keys, old JSON keeps working) and
`smtpHost` / `smtpPort` / `fromName`, which C2 moves from the conversation-only key set to the **common** set (trigger
mode still never replies; it needs SMTP only for the self-test and send-out).

Per-target fields (`approvalPolicy`, `readOnlyTools`, `sendOutTarget`, `rejectedReply`) are new optional fields on
`ChannelTarget`, refused for non-email integrations. Each section below says which mode a setting applies to.

## 5. Phase C1 — Sender lists as first-class, editable over REST and MCP

**Size:** S. Branch `feat/email-sender-lists`. No dependencies.

Changing one address today means reading and rewriting the whole integration — which an agent does badly and which
races with an admin editing the same integration.

### 5.1 Operations

Targeted operations produce a **new version** of the integration through the same `RestChannelIntegrationStore`
update path as a save (same validation, warnings, polling re-registration), so history and rollback are free.

| Method | Path | Body | Notes |
|---|---|---|---|
| `GET` | `/channelstore/channels/{id}/email-senders` | — | `{version, allow: [...], block: [...]}` of the current version |
| `POST` | `/channelstore/channels/{id}/email-senders` | `{expectedVersion, add: {allow, block}, remove: {allow, block}, reason, confirmOpen}` | Idempotent per entry (no-ops reported). `expectedVersion` mismatch → 409 with the current lists. Returns the new version and warnings |

- Authorisation: EDIT on the integration.
- Validation: `SenderPatternSyntax`, normalised, ≤ 200 per list, an entry in both lists → 400.
- **Emptying a non-empty allowlist opens the integration to every sender** (an empty allowlist allows all): refused
  unless `confirmOpen: true`, and then warning `email.allowlist-emptied`.
- Warning `email.allowlist-broad` for `*@*.<public suffix>` and for the integration's own domain.
- Audit: one `audit_ledger` entry per change (actor, integration, diff, `reason`).

### 5.2 MCP

`list_email_senders(integrationId)`, `update_email_senders(integrationId, expectedVersion, addAllow, addBlock,
removeAllow, removeBlock, reason, confirmOpen)`. Descriptions: changes create a new version; prefer exact addresses.

### 5.3 The escalation guard (authoritative at runtime)

A turn that a mail started must never be able to widen who can make EDDI act, or decide its own approvals.

- **Taint.** Every conversation whose root turn came from email — and every conversation it starts (delegation,
  consult, A2A, group member turns) — carries `origin=email` (set where the turn is submitted, propagated with the
  caller context; verify the propagation points, §14).
- **Runtime refusal** in `ToolExecutionService` and in the HTTP/MCP call paths for a tainted turn: any write to
  `/channelstore/channels/**`, the MCP tools `create/update/delete_channel_integration`, `update_email_senders`,
  `update_email_roles`, `update_email_send_out`, `decide_held_email`, `resume_conversation`, and approval endpoints.
  This check is the boundary.
- **Save-time error** in `EmailAgentToolsInspector` for a target agent holding those tools by name — best effort,
  documented as such (it skips unreadable documents and GROUP targets, and does not run when the agent changes later).

### 5.4 Manager, operator, docs

- Email settings: two chip lists with add/remove, a "why" field, the last change and who made it.
- Operator: both endpoints on the allow-list (the POST gated), prompt section, revision bump.
- `docs/email-integration.md` "Managing senders"; skills in `integrations/claude-plugin/`.

**Acceptance:** an MCP client adds `alice@partner.com` with `expectedVersion`; the next poll lets Alice through; a stale
version gets 409; removing the last allowlist entry without `confirmOpen` is refused; a mail-triggered agent calling
`update_email_senders` (directly, or through a delegated agent) is refused at runtime.

## 6. Phase C2 — S/MIME signing and the sending-health check

**Size:** M. Branch `feat/email-smime`. No dependencies.

**Scope:** mail the integration sends through its own SMTP — replies, pause notices, send-outs (C6), the self-test.
Owner notices, digests and HITL notifications go through `SystemMailer` and are out of scope here (§15 Q3).

### 6.1 Why both DKIM and S/MIME

DKIM is done by the mail provider for the domain — EDDI cannot do it, but must **tell** the admin when it is not
happening. The live test on 2026-11-18 found exactly that: `labs.ai` publishes a Google key, but Workspace signing was
never started, so mail was signed by Google's stand-in domain and refused `unauthenticated:pass-unaligned`. S/MIME is
EDDI's own signature: the recipient's client shows the message as signed by the mailbox, it survives forwarding, and
it is per mailbox.

### 6.2 Settings

| Setting | Default | Meaning |
|---|---|---|
| `emailControl.smime.sign` | `true` | `true`: sign when a signing connection is configured; without one, mail goes out unsigned and the save warns `email.smime-no-certificate` (upgrades and quick setups keep working). `required`: never send unsigned — a missing, expired or mismatched certificate holds outgoing mail (rows wait with reason `smime_unavailable`, owner notice). `false`: off |
| `emailControl.smime.connection` | — | Name of a connection of the new auth type `SMIME_SIGNING` |

The certificate is a **credential**, so it lives on a connection, not in the settings (the "no secrets here" rule of
`EmailChannelSettings`): `SMIME_SIGNING` holds `certificate` (`${vault:…}`, PKCS#12 base64 or PEM key + chain) and
`password` (`${vault:…}`, optional), binding `SERVICE`, resolved only by the email transport. Vault grants work as for
every connection secret (verify a non-URL connection type fits the resolver and grant model, §14). "On by default" is
`sign: true`; "can be switched off in the JSON" is `sign: false`.

### 6.3 Validation (save time and daily)

- The certificate's `rfc822Name` SAN (or subject e-mail) equals `address` (normalised); key usage `digitalSignature`;
  extended key usage `emailProtection`; not expired; the chain builds to the JDK trust store or is shipped complete.
  A mismatch → 400 under `required`, warning otherwise.
- Daily: expiry within 30 days → owner notice (new `EmailOwnerNotifier` reason `CERTIFICATE_EXPIRING`), metric
  `eddi_email_smime_certificate_days_left`.

### 6.4 Signing

- `multipart/signed`, detached `application/pkcs7-signature`, SHA-256; RSA (PKCS#1 v1.5 unless client support for
  PSS is confirmed, §14) or ECDSA by key type. Clients without S/MIME still read the text.
- Signed right before SMTP submission, after the body is final. The ledger stores `signed: true|false` on OUT rows
  (new column; Postgres `ALTER TABLE … ADD COLUMN IF NOT EXISTS`, Mongo field), never signature bytes.
- Library: Bouncy Castle's PKIX and Jakarta-Mail S/MIME modules of the same line as `bcprov-lts8on` (artifact names
  to verify, §14). Introduce a `bouncycastle.version` property for all three. No second MIME stack (v1 §22): sign the
  Angus `MimeMessage` already built.
- Metric `eddi_email_outbound_total{…, signed="true|false"}`.

### 6.5 Sending-health check

`POST /channelstore/channels/{id}/email-self-test` (EDIT; at most 5 per integration per hour) sends a short mail from
the integration to its own `address` carrying a single-use token valid for 15 minutes. The processor recognises it
**before the own-address refusal** (token matches an open self-test of this integration, From is `address`), records
the result and settles the row as `self_test` — it can never start a turn; a reused or expired token is refused like
any own-address mail. `GET …/email-self-test/{token}` returns, in plain words: the DKIM verdict and signing domain,
alignment, whether S/MIME verified, DMARC present (advice only). Example: "Your provider signs as
`labs-ai.20230601.gappssmtp.com`, not `labs.ai`: start DKIM authentication for `labs.ai` in the Google Admin console."
Microsoft 365 does not DKIM-sign mail inside one organisation, so there an unsigned result is reported as
**inconclusive**, with the advice to test from outside. MCP tool `run_email_self_test`.

**Acceptance:** with a signing connection, a reply arrives signed and Gmail/Outlook show it as signed; with
`sign: false` it is not signed; under `required` with an expired certificate nothing is sent and the owner is told;
the self-test names a provider that does not sign aligned; a replayed self-test mail does nothing.

## 7. Phase C3 — Held mail: review instead of drop

**Size:** M–L. Branch `feat/email-held-mail`. Depends on C1 (run and allow). Both modes.

### 7.1 Settings

| Setting | Default | Meaning |
|---|---|---|
| `emailControl.onRefused.notAllowlisted` | `drop` | `drop` or `review` for `blocked:not_allowlisted` |
| `emailControl.onRefused.unauthenticated` | `drop` | `drop` or `review` for `unauthenticated:*` |
| `emailControl.onRefused.notAddressed` | `drop` | Only once v2 Phase 3 exists |
| `emailControl.review.expiry` | `P7D` | Undecided held mail is dropped (`review_expired`). At most the ledger retention minus one day |
| `emailControl.review.maxHeldPerDay` | `50` | Per integration; beyond it `review_full` |
| `emailControl.review.maxHeldPerSenderDomainPerDay` | `5` | So one sender cannot fill the queue for everyone |
| `emailControl.review.reviewers` | — | EDDI user ids or roles who may decide (default below) |

**Never reviewable:** loops and bounces, `blocked:no_single_sender`, `blocked:blocklist` (which also covers mail from
the integration's own address), oversize, rate and budget limits. The Manager preselects `notAllowlisted: review` for
new integrations (§15 Q4).

### 7.2 Holding

- The processor evaluates **all** reviewable checks; if any fails and all failing ones are set to `review`, the row
  becomes `HELD` with every failing reason (mail-log reason e.g. `held:not_allowlisted+unauthenticated`), the
  **authentication verdict** stored on the row (new column `authVerdict`), and the mail is marked processed and — when
  `processedAction` is `move:` — moved to a sibling `EDDI/Held`. The cursor advances; a held mail never pins it.
- `HELD` (and C4's states) are added explicitly to `State`, `isTerminal()`, `dispositionOf` and the mail-log state
  filter, in both stores. Postgres: `ALTER TABLE … ADD COLUMN IF NOT EXISTS` for every new column.
- No body is stored. The preview is fetched live: `GET /channelstore/channels/{id}/email-held/{rowId}` re-reads the
  mail by Message-ID (polled, move and Held folders) and returns sender, verdict, subject, the first 4 000 characters
  of the text (quoted history stripped, hidden content removed by the existing filter), attachment names. Not cached.
- **Notification:** reviewers get one notice per batch through `SystemMailer` with a deep link to the Manager, sent to
  `ownerNotifyAddresses` and to the verified addresses of the reviewer principals. The digest gains a "held" count.
  Slack: once #1438 lands, held mail is posted like a pause (its card binding needs a held-mail subject; part of this
  phase if #1438 is merged, otherwise a follow-up).

### 7.3 Deciding

`POST /channelstore/channels/{id}/email-held/{rowId}/decision` `{decision: discard | runOnce | runAndAllow, note}`;
MCP `list_held_email`, `decide_held_email`; the Manager approvals page lists held mail as its own kind next to
pauses (one queue for the human).

- **Who decides:** `reviewers`, default = EDIT holders of the integration plus `eddi-approver` (#1396's approver
  role). `runAndAllow` additionally requires **EDIT**, because it writes the allowlist.
- `discard` → `REFUSED`, reason `review_discarded`.
- `runOnce` → the row gets `released = true` and `nextAttemptAt = now`, state `RECEIVED`. `findDueResubmissions` is
  extended to return released rows **without** a conversation, and `resubmit` searches the Held folder. On the run,
  only the checks that held it are skipped; every non-reviewable check runs again.
- `runAndAllow` = `runOnce` + the exact sender address added to the allowlist through C1 (audited, `reason` = note).
  **Offered only when the stored verdict is an aligned pass** — a forged From fails the allowlist check before
  authentication is looked at, so the reason alone cannot tell.
- **A released mail without an aligned pass** runs as `email-anon:<random>` (memory-free), **never causes outgoing
  mail** (no reply, no pause notice, no send-out — v1 §22), and its turn may use only the target's `readOnlyTools`
  (enforced in C3 itself, before C5's `anonymous` role exists). The reviewer's UI says this before the decision.
- A released mail still meets the target's `approvalPolicy` (C4): releasing means "process it", not "do what it asks".
- Every decision is audited.

Existing integrations with `requireAuthentication: false` keep today's behaviour (anonymous identity, full tools); a
save warning `email.anonymous-tools` names the tools such a target exposes.

**Acceptance:** a stranger's signed mail is held, the owner gets one notice, sees the text, picks "run and allow"; the
mail runs once, the sender is on the allowlist, a second mail from them runs directly; a forged mail is held with
verdict `fail`, offers no "run and allow", and after "run once" runs with read-only tools and sends nothing; a held
mail does not stop the cursor.

## 8. Phase C4 — Approval before acting, and reply review

**Size:** M. Branch `feat/email-approval-policy`. No hard dependency (§8.3 reads C5 when present). Both modes for
8.1; conversation mode for 8.2.

### 8.1 Per target: `approvalPolicy`

| Value | Behaviour |
|---|---|
| `actions` | The agent runs; every tool call not listed in the target's `readOnlyTools` waits for approval |
| `firstContact` | As `actions`, and a sender's first mail to this target (no earlier `DONE` row from that address) pauses before the agent runs |
| `everyTurn` | Every mail pauses before the agent runs; the approver sees the mail and decides go / reject / go with a note |
| `none` | Today: only the agent's own `toolApprovals` |

Strictness order: `none` < `actions` < `firstContact` < `everyTurn`. Default on upgrade: `none` (no behaviour change);
the Manager preselects `actions` for new email targets; save warning `email.approval-policy-none` for a target with
tools. Refused on GROUP targets (group turns auto-reject tool approvals).

**The email gate is its own gate, not a merge.** Merging `requireApproval: ["*"]` into the agent's block would be
undone by the agent's `exempt` (exemptions always win), by `task-approvals.mode=replace`, and by an agent
`timeoutPolicy: AUTO_APPROVE`. Instead, for a turn tainted `origin=email` (§5.3), after `TaskToolApprovalsResolver`:

- a tool call not in `readOnlyTools` pauses, whatever the agent's and task's `exempt` say; timeout policy
  `AUTO_REJECT` after `emailControl.review.expiry`;
- rule-driven `httpcalls` (`ApiCallsTask`, which has no gate) and `mcpcalls` (`McpCallsTask`, which refuses gated
  calls) of a tainted turn are **refused** unless named in `readOnlyTools`; save warning `email.rule-calls-refused`
  when the target's workflow contains them.

**Pausing before the agent runs** (`firstContact`, `everyTurn`): the gate sits **before conversation start** for a new
conversation (otherwise `CONVERSATION_START` and its model calls run first) and before the pipeline for a continued
one — a pause of the mail's conversation with no step run yet (verify the hook, §14). The sender gets the existing
in-thread pause notice (`EmailHitlResumeObserver`, conversation mode, authenticated senders only), approvers get the
existing notification, and the Manager approvals page and MCP (`list_pending_approvals`, `resume_conversation`) work
unchanged; Slack once #1438 is merged. "Reject" ends the turn with the target's `rejectedReply` (optional; default
none in trigger mode, "Your request was not approved." in conversation mode).

### 8.2 Reply review (v2 §11 backlog item 1, moved here)

| Setting (conversation mode) | Default | Meaning |
|---|---|---|
| `emailControl.replyReview` | `never` | `always`, `firstContact`, `external` (recipient outside `internalDomains`), `never` |
| `emailControl.internalDomains` | empty | Patterns counted as internal here and in C6 |

Implemented as a **rule pause at the end of the turn, before the reply row is queued**, reusing
`hitlConfig.review.allowAnswerEdits` / `editedAnswer`: the approver sees the reply, approves, rejects or edits it, and
the edited text is the one signed and sent. No new ledger state; Manager, MCP `resume_conversation` and the audit
trail work as for any rule pause. An expired review sends nothing.

### 8.3 "Approved sender, forbidden ask"

Layers, documented together in "Keeping an email agent in bounds": the agent's tools are the blast radius (v1); the
email gate and per-tool rules (8.1); sender roles (C5); reply review (8.2); guardrails
([`guardrails-architecture.md`](guardrails-architecture.md)) once they ship.

**Acceptance:** a target on `actions` answers a question without approval, its `http.post` call pauses even though the
agent exempts it; under `everyTurn` a new mail pauses before `CONVERSATION_START` (no model cost recorded); a
rule-driven httpcall of a tainted turn is refused; under `replyReview: always` the edited reply is the one that
arrives, signed.

## 9. Phase C5 — Sender roles: it matters who said it

**Size:** M. Branch `feat/email-sender-roles`. Depends on C1, C4 and **#1394** (replay protection: without
`signature_seen`, a captured mail from a powerful address can be replayed with a valid signature).

### 9.1 Settings

```json
"emailControl": {
  "senderRoles": [
    { "role": "exec",     "senders": ["cfo@company.com", "ceo@company.com"] },
    { "role": "staff",    "senders": ["*@company.com"] },
    { "role": "customer", "senders": ["*@customer-a.com", "*@customer-b.de"] }
  ],
  "rolePolicies": {
    "exec":     { "targets": ["*"], "approvalPolicy": "actions", "sendOut": true },
    "staff":    { "targets": ["*"], "approvalPolicy": "actions", "sendOut": true, "maxTurnsPerSenderPerDay": 30 },
    "customer": { "targets": ["support"], "approvalPolicy": "actions", "tools": ["kb_search"] }
  }
}
```

- First matching entry wins. No match → role `default`: all targets, the target's own `approvalPolicy`, no tool
  restriction, `sendOut: false`, the integration's caps — i.e. today's behaviour.
- Roles apply only to an **authenticated** sender (aligned pass). Unauthenticated or released mail is role
  `anonymous`: `readOnlyTools` only, `sendOut: false`, never outgoing mail.
- A role policy can only **narrow**: `targets` ⊆ the integration's, `tools` intersected with the agent's,
  `approvalPolicy` at least the target's (strictness order, §8.1), caps (`maxTurnsPerSenderPerDay`, same name as the
  integration key) at most the integration's. Validation refuses a widening policy. Roles and v2 §7.6
  `allowedSenders` both apply; a mail must pass both.
- Under v2 `replyAudience: thread` the identity is v2's `email-thread:…`; roles still apply to the sender.
- The turn context carries `mail.senderRole` so a prompt can adapt its tone; anything that matters is enforced in code.
- REST/MCP like C1: `list_email_roles`, `update_email_roles` (expected version, audited, §5.3 guard).

### 9.2 Trust

DKIM authenticates the **domain**, not the person. Within one Workspace/M365 domain a person normally cannot send as a
colleague, but aliases, "send mail as" and shared mailboxes can open that up. Roles bind to addresses, not EDDI users;
identities stay `SELF_ASSERTED`; `PER_USER` connections stay refused. The Manager shows, next to a role with sensitive
powers, "trust rests on your mail provider preventing one person from sending as another" and links the self-test.
Linking a sender to an EDDI user's grants is open question Q1.

**Acceptance:** the CFO's mail reaches the finance target and may send out; a customer's mail reaches only `support`
with only `kb_search`; a policy widening tools or caps is refused at save; a replayed CFO mail is refused (#1394).

## 10. Phase C6 — Approved send-out to third parties

**Size:** L. Branch `feat/email-send-out`. Depends on C2 (SMTP in common keys, signing), C4, C5, and **#1394**; the
Cc/@mention case also on v2 Phase 3.

### 10.1 The shape

"@eddi please send customer xyz the offer we discussed" from an authorised sender is routed to a **send-out target**
(`sendOutTarget: true`): an agent built for it — one tool, `sendMail`, plus read-only access to exactly the data it
may quote or attach, no long-term memory. Ship a starter configuration ("Mail clerk") in `docs/agent-configs/`.

### 10.2 Settings

| Setting | Default | Meaning |
|---|---|---|
| `emailControl.sendOut.enabled` | `false` | Assemble `sendMail` for send-out targets, for senders whose role has `sendOut: true` |
| `emailControl.sendOut.outboundRecipients` | — (required when enabled) | Patterns (`*@customer-a.com`, exact addresses), max 500 |
| `emailControl.sendOut.contactSource` | — | Optional lookup of a contact name (a global variable or an `httpcalls` call, e.g. a CRM) returning `{name, address}`. An ambiguous name is refused; a resolved address must **still** match `outboundRecipients` — the source can narrow, never widen |
| `emailControl.sendOut.approveExternal` | `every` | `every`: each send to an address outside `internalDomains` is approved. `new`: only the first send to that address. Never off |
| `emailControl.sendOut.approveInternal` | `new` | `every`, `new`, `never` |
| `emailControl.sendOut.maxPerDay` | `20` | Per integration, counted from the ledger |
| `emailControl.sendOut.copyRequester` | `true` | The requester is Cc'd |
| `emailControl.sendOut.allowReplies` | `true` | The recipient's answer is handled (§10.4) |
| `emailControl.sendOut.replyWindow` | `P14D` | How long answers are accepted |

Editable over REST and MCP (`update_email_send_out`, expected version, audited, §5.3 guard). One recipient per send;
the model never adds Cc or Bcc.

### 10.3 The tool and its approval

`sendMail(recipient, subject, body)`, `recipient` a contact name or an address. In code, in this order: resolve →
match `outboundRecipients` → suppression list → caps → approval.

Request pinning does not cover non-HTTP tools today, so `sendMail` pins its own: the approval is bound to a stored
hash of (resolved address, subject, body). The approver may edit subject and body, **not** the recipient; after an
edit the hash is recomputed and every check runs again before sending. From is the integration's `address`, signed
(C2). Ledger kind `SENDOUT`, direction `OUT`, with the requesting sender and conversation (new columns; both stores);
digest counts; mail-log filter `kind=SENDOUT`.

### 10.4 The recipient's answer: a separate, narrow conversation

The requester's conversation holds internal context, memory and `sendMail`; the recipient must never run in it (a
conversation's user id is fixed at start). With `allowReplies`:

- An answer is accepted only when its `In-Reply-To` is a `SENDOUT` row's Message-ID, its **From equals that row's
  recipient exactly**, with an aligned pass, within `replyWindow` (signed view, #1394). This replaces only the
  allowlist check; loop, auto-reply, blocklist and suppression checks still run.
- It opens (or continues) a **separate conversation** keyed by the thread root, running as
  `email-sendout:<integrationId>:<sha256(threadRoot)>` (added to `ReservedPrincipals.PREFIXES`,
  `hasNoPersistentMemory`, `EmailUserIdentity.isEmailIdentity`), seeded only with the sent mail's text. `sendMail` is
  never assembled there; tools are the target's `readOnlyTools`.
- The agent's answer to the recipient is reviewed like `replyReview: external` regardless of the setting. The requester
  is told of the answer (Cc or notice, per `copyRequester`).

**Acceptance:** staff writes "send the offer to Jane at customer-a"; the clerk resolves Jane, the approver sees
recipient, subject and body, approves; Jane receives a signed mail with the requester in Cc; Jane's reply opens a
separate conversation without the requester's history or `sendMail`, and its answer waits for review; a reply from
anyone but Jane with Jane's Message-ID is not accepted; an address outside `outboundRecipients` is refused without
asking anyone; a customer-role sender gets no `sendMail`.

## 11. Phase C7 — The admin view and MCP parity

**Size:** M. Branch `feat/email-control-view`. Grows with each phase; start with C1.

One "Mail control" tab per email integration in the Manager:

- **Senders:** lists (C1), roles and their policies (C5), last change and who made it.
- **Waiting for a human:** held mail (C3), paused turns and reply reviews (C4), send-outs (C6) — a filtered view of the
  approvals page, which stays the one queue.
- **What goes out:** mail-log filter for `REPLY`, `NOTICE`, `NOTIFY`, `SENDOUT`, with signed/unsigned.
- **Health:** signing certificate and expiry, last self-test (DKIM, alignment, DMARC), last poll.
- **Policy summary in plain words:** "Staff can reach all agents; their actions need approval; customers can only reach
  support; mail from unknown senders is held for review."

MCP parity: `list_email_senders`, `update_email_senders`, `list_email_roles`, `update_email_roles`,
`update_email_send_out`, `list_held_email`, `decide_held_email`, `run_email_self_test`, and `read_email_policy`
(returns the resolved `emailControl`, the per-target `approvalPolicy` and `readOnlyTools`, and the plain-words summary
the tab shows). Pauses and reply reviews are decided with the existing `resume_conversation`. Operator prompt,
allow-list (writes gated) and revision bump in each phase that adds a tool; operator PRs land serially.

## 12. Order and dependencies

| Phase | Size | Depends on |
|---|---|---|
| C1 Sender lists + guard | S | — |
| C2 S/MIME + self-test | M | — |
| C3 Held mail | M–L | C1; #1438 for Slack |
| C4 Approval gate, reply review | M | — (§5.3 taint from C1); #1438 for Slack |
| C5 Sender roles | M | C1, C4, **#1394** |
| C6 Send-out | L | C2, C4, C5, **#1394**; v2 Phase 3 for Cc/@mention |
| C7 Admin view | M | grows per phase |

Merge #1458 and #1476 first. C1 and C2 can run in parallel; C3 and C4 in parallel after C1.

## 13. Cross-cutting checklist

Every phase: both datastores (Mongo and Postgres; new columns with `ADD COLUMN IF NOT EXISTS`), REST + MCP + Manager
+ operator, all 11 locales, `docs/email-integration.md` and the skills in `integrations/claude-plugin/`, metrics, GDPR,
audit entries for every human decision and policy change, save-time validation with actionable errors, unit tests and
an IT through the real store, a mutation check of each security test, a live run against the test mailbox before
declaring done, a changelog fragment.

**GDPR:** erasing an address must also reach the conversations named by its ledger rows (`email-anon:` releases,
`email-sendout:` threads), not only conversations found by identity — extend `EmailIdentityGdprParticipant` to cascade
over the `conversationId` of the address's rows; the held-mail rows and `SENDOUT` rows (as recipient and as requester)
are covered by `findByRecipient`/`deleteByRecipient` and the new requester column.

## 14. Things to verify before relying on them

1. How the channel store serialises a typed `emailControl` field in Mongo and Postgres, and ZIP export/import.
2. The propagation points for the `origin=email` taint: delegation (`converseWithAgent`), consult, A2A, group member
   turns, scheduled follow-ups.
3. The hook for a pause before conversation start (new conversation) and before the pipeline (continued one), and that
   the resume path then runs the full turn.
4. That an email gate after `TaskToolApprovalsResolver` pauses (not refuses) for every tool source the LLM task
   assembles, and integrates with the execution journal (at-most-once).
5. A connection auth type without a URL (`SMIME_SIGNING`): resolver, allowlist and grant model.
6. Bouncy Castle artifact names and versions for S/MIME on the LTS line next to `bcprov-lts8on`; licence and
   OpenSSF checks.
7. How Gmail (consumer, Workspace), Outlook (desktop, web), Apple Mail and Thunderbird show a clear-signed message from
   a public CA S/MIME certificate; RSA-PSS support.
8. Moving held mail to `EDDI/Held` on Gmail (labels) and Exchange; `resubmit` finding it there.
9. How #1438 binds a card to its subject, and what a held-mail subject needs.
10. On Windows, dnsjava without `eddi.email.dns.servers` takes every adapter's resolvers (including dead `fec0::` and
    disconnected ones) and every DKIM lookup fails temporarily (seen 2026-11-18). Document it in
    `docs/email-integration.md`; consider naming the servers tried in the `auth_temporary` note.

## 15. Open questions

- **Q1 — Link senders to EDDI users?** An authenticated sender mapped to exactly one directory user with a verified
  address could carry that user's roles and `PER_USER` grants. Needs a provenance decision (`SELF_ASSERTED` today) and
  a per-domain admin assertion that the provider prevents impersonation.
- **Q2 — One signing connection per integration or a deployment default?**
- **Q3 — S/MIME for `SystemMailer`** (owner notices, digests, HITL notifications)? Same code, a deployment-level
  signing connection.
- **Q4 — Default of `onRefused.notAllowlisted`** for new integrations: `review` (preselected) or ask in a wizard.

## 16. What I would NOT do

- **No approval by replying to a mail**, ever (v1 §22, v2 §2.3).
- **No outgoing mail caused by an unauthenticated mail**, even after a human released it.
- **No address outside `outboundRecipients`**; no recipient edits by the approver; no Cc/Bcc added by the model.
- **No storing mail bodies** for review; previews are fetched live.
- **No email-originated turn that can change its integration, its senders, roles or policies, or decide an approval**
  — enforced at runtime, not only at save.
- **No merging the email gate into the agent's `toolApprovals`**; it is its own gate.
- **No widening by roles.**
- **No silent unsigned mail under `smime.sign: required`.**
