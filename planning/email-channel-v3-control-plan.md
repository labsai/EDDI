# Email Channel v3 — Control: Sender Lists, Review, Approvals, Roles, Send-out, S/MIME

> **Status:** plan, not started (2026-11-18). Written so a coding agent can implement it without the conversation
> that produced it. Line numbers drift; search for the named symbol.
>
> **Builds on** [`email-channel-plan.md`](email-channel-plan.md) (v1, shipped in 6.7.0) and
> [`email-channel-v2-plan.md`](email-channel-v2-plan.md) (v2: mail log, addressing, thread replies, Workspace
> mailboxes, consulting). Phase 2 of v2 is PR #1458 (mail log, lookup, owner digest); v2 Phase 4 (mailbox grant) is
> PR #1476. Read v1 §9 (Identity), §22 (What I would NOT do), v2 §2 (Decisions) and
> [`docs/hitl.md`](../docs/hitl.md) (both surfaces, tool approvals, request pinning, who may decide) first. This plan
> amends one decision explicitly (§2.2).

## Table of Contents

1. [Goals and non-goals](#1-goals-and-non-goals)
2. [Decisions](#2-decisions)
3. [What exists today](#3-what-exists-today)
4. [Phase C1 — Sender lists as first-class, editable over REST and MCP](#4-phase-c1--sender-lists-as-first-class-editable-over-rest-and-mcp)
5. [Phase C2 — S/MIME signing and the sending-health check](#5-phase-c2--smime-signing-and-the-sending-health-check)
6. [Phase C3 — Held mail: review instead of drop](#6-phase-c3--held-mail-review-instead-of-drop)
7. [Phase C4 — Approval before acting, and reply review](#7-phase-c4--approval-before-acting-and-reply-review)
8. [Phase C5 — Sender roles: it matters who said it](#8-phase-c5--sender-roles-it-matters-who-said-it)
9. [Phase C6 — Approved send-out to third parties](#9-phase-c6--approved-send-out-to-third-parties)
10. [Phase C7 — The admin view](#10-phase-c7--the-admin-view)
11. [Order and dependencies](#11-order-and-dependencies)
12. [Cross-cutting checklist](#12-cross-cutting-checklist)
13. [Things to verify before relying on them](#13-things-to-verify-before-relying-on-them)
14. [Open questions](#14-open-questions)
15. [What I would NOT do](#15-what-i-would-not-do)

---

## 1. Goals and non-goals

The email channel becomes a way of working with EDDI that is rich and powerful **and** under the admin's control:
every mail is accounted for, nothing consequential happens without the right person's approval, and every lever is
reachable from the Manager, REST and MCP.

**Goals**

1. **Every mail can reach a decision.** Mail EDDI would refuse (sender not allowed, unauthenticated) can be held for a
   human instead of dropped: discard, run once, or run and allow the sender from now on (C3).
2. **Run, but ask first.** A mail that is processed can still require an approval before the agent acts on it, before
   its reply leaves, or both — through the existing HITL machinery (Manager approvals page, Slack, MCP), never by
   replying to a mail (C4).
3. **It matters who said it.** An authenticated sender's role decides which agents they reach, which tools the turn
   may use, what needs approval and what it may cost (C5).
4. **Cc EDDI and ask it to send something.** "Send customer xyz the offer" from an authorised internal sender makes a
   dedicated send-out agent mail that customer — recipient from an admin-controlled set, every new recipient approved,
   the customer's answer continuing the thread in a narrow scope (C6).
5. **Mail from EDDI is verifiably EDDI's.** Outgoing mail is S/MIME-signed by default (product-owner decision), and
   the admin is told plainly when the domain's DKIM signing is not active (C2).
6. **Admin-friendly.** Sender lists, roles, held mail and policies are one screen per integration, and every one of
   them is an MCP tool so an agent (the Platform Operator, or the admin's own) can manage them (C1, C7).

**Non-goals**

- S/MIME **encryption** (needs every recipient's certificate; a separate plan if ever).
- Approving anything by mail (v1 §22 — reaffirmed).
- A reply to an unauthenticated sender, even after a human released the mail (§15).
- A model typing a free-form recipient address (§2.2 keeps this banned; C6 names a recipient from a controlled set).
- Using a person's own mailbox, domain-wide delegation (v2 §2.1 D5, reaffirmed).

## 2. Decisions

### 2.1 Product-owner decisions (2026-11-18)

| # | Question | Decision |
|---|---|---|
| D6 | Sign EDDI's mail? | **Yes, S/MIME, supported and on by default**; can be switched off in the integration's JSON (C2) |
| D7 | Mail EDDI does not accept (not allowed, unauthenticated) | **The owner hears about it and decides** whether it is processed at all (C3) |
| D8 | Mail EDDI processes that asks for something | **It can be made to ask first**, via HITL, before it acts (C4) |
| D9 | Allow- and blocklists | **Configurable, and editable over MCP** so an agent can manage them (C1) |
| D10 | Who sent it | **Matters**: sender roles drive targets, tools, approvals, limits (C5) |
| D11 | "Cc EDDI, send our customer xyz …" | **Yes**, through a dedicated, narrowly scoped send-out agent, every new recipient approved (C6) |

### 2.2 Amendment (binding — record it)

**v2 §2.3 "No LLM-chosen recipients"** is narrowed, not dropped. v1 §22 already allowed "a generic `sendEmail` tool"
as "the allowlisted, approval-gated Phase 7 item"; C6 is that item, specified:

- The model never types an address that is not already in an admin-controlled set (`outboundRecipients`, §9.2). It
  names a contact or an address; anything outside the set is refused before an approval is even asked for.
- Every recipient EDDI has not mailed before through send-out needs a human approval, bound to the exact
  recipient, subject and body (request pinning, `docs/hitl.md`).
- Only a sender whose role allows send-out (C5) can cause one; the tool is not assembled otherwise.

Everything else in v2 §2.3 stands: no reply-to-approve, no reply to an unauthenticated sender, no domain-wide
delegation.

## 3. What exists today

Verified on `feat/email-mail-log` (PR #1458) and `main` 2026-11-18. Paths under `src/main/java/ai/labs/eddi/`.

| Piece | Where | Relevance |
|---|---|---|
| `senderAllowlist` / `senderBlocklist` | `configs/channels/model/EmailChannelSettings.java` (comma-separated, ≤ 200, `SenderPatterns` syntax) | C1 makes them first-class |
| Order of checks | `integrations/email/EmailInboundProcessor.java` step 5–6: authentication → blocklist/allowlist (`blocked:not_allowlisted`) → `unauthenticated:<verdict>` | C3 hooks the refusals |
| Re-fetch by Message-ID | `EmailMailboxPoller.resubmit` + `EmailInboundProcessor.pendingResubmissions` / `resubmissionLost` | C3 releases held mail through it — **no mail body is ever stored** (v1 §22) |
| Mail log, digest | PR #1458: `IEmailMessageLedger.findByIntegration`, `GET /channelstore/channels/{id}/email-log`, `EmailOwnerNotifier` daily digest | C3 rows, C7 view |
| Tool approvals | `hitlConfig.toolApprovals` (`requireApproval`, `exempt`, per-tool rules, request pinning), `docs/hitl.md` | C4, C6 |
| Turn-level pause | `PAUSE_CONVERSATION` action from behavior rules; in conversation mode the sender gets an in-thread pause notice (`EmailReplySender`) | C4 |
| Approver notification | `hitlConfig.notify.email` (v1 Phase 1) — deep link to the Manager approvals page | C3, C4 |
| Approvals over MCP | `list_pending_approvals`, `get_approval_status`, `resume_conversation` | C3, C4 are visible to agents for free when built on HITL |
| Channel MCP tools | `McpAdminTools`: `list/read/create/update/delete_channel_integration` (whole-document update) | C1 adds targeted tools |
| Save-time warnings | `EmailAgentToolsInspector` (`email.tools-without-approvals`, …) | C4, C6 warnings |
| Identity | `email:<integrationId>:<address>` (`perSender`), `SELF_ASSERTED` provenance, `PER_USER` connections refused | C5 |
| Crypto library | `org.bouncycastle:bcprov-lts8on` managed in `pom.xml` (transitive) | C2 adds the PKIX/mail modules of the same line |
| SMTP out | `integrations/email/reply/*`, Angus Mail (`jakarta.mail`) | C2 signs here |

## 4. Phase C1 — Sender lists as first-class, editable over REST and MCP

**Size:** S. Branch `feat/email-sender-lists`. No dependencies.

Today a list is one comma-separated config value; changing one address means reading and rewriting the whole
integration, which an agent does badly and which races with an admin editing the same integration.

### 4.1 Model

Unchanged storage: `senderAllowlist` / `senderBlocklist` stay keys of `platformConfig` (old JSON keeps working). New
targeted operations produce a **new version** of the integration, exactly as a save does (same validation, same
warnings, same `@ConfigurationUpdate` path, polling re-registered), so history and rollback are free.

### 4.2 REST

| Method | Path | Body | Notes |
|---|---|---|---|
| `GET` | `/channelstore/channels/{id}/email-senders` | — | `{version, allow: [...], block: [...]}` of the current version |
| `POST` | `/channelstore/channels/{id}/email-senders` | `{expectedVersion, add: {allow: [], block: []}, remove: {allow: [], block: []}, reason}` | Idempotent per entry (adding a present entry, removing an absent one is a no-op, reported). `expectedVersion` mismatch → 409 with the current lists. Returns the new version and warnings |

- Authorisation: EDIT on the integration (same rule as the mail log, #1458).
- Validation: `SenderPatterns` syntax, normalised (`AddressNormalizer`), ≤ 200 per list, an entry in both lists → 400.
  Adding `*@*` or `*@<the integration's own domain>` to the allowlist → warning `email.allowlist-broad`.
- Audit: one `audit_ledger` entry per change with actor, integration, diff and `reason`.

### 4.3 MCP

New tools in `McpAdminTools` (or a small `McpEmailSenderTools`): `list_email_senders(integrationId)`,
`update_email_senders(integrationId, expectedVersion, addAllow, addBlock, removeAllow, removeBlock, reason)`.
Descriptions say: changes create a new version; prefer exact addresses over `*@domain`.

**Privilege-escalation guard.** An agent that a mail can trigger must never be able to widen who can trigger it.
`EmailAgentToolsInspector` refuses (save-time **error**, not warning) a target agent whose tools include
`update_email_senders`, `update_channel_integration` or `create_channel_integration` (MCP or HTTP to these paths),
and `ToolExecutionService` refuses the call at runtime when the conversation's user id is an `email:`/`email-*:`
identity. Test both.

### 4.4 Manager, operator, docs

- Email settings: two chip lists with add/remove, a "why" field, the last change and who made it.
- Operator: allow-list both endpoints (gated write for the POST), prompt section, revision bump.
- `docs/email-integration.md`: "Managing senders" (REST, MCP, the guard). Skills in `integrations/claude-plugin/`.

**Acceptance:** an MCP client adds `alice@partner.com` with `expectedVersion`; the next poll lets Alice's mail through;
a stale `expectedVersion` gets 409; a mail-triggered agent holding the tool cannot be saved.

## 5. Phase C2 — S/MIME signing and the sending-health check

**Size:** M. Branch `feat/email-smime`. No dependencies. Applies to every mail EDDI sends from an integration:
replies, pause notices, owner notices and digests, send-outs (C6).

### 5.1 Why both DKIM and S/MIME

DKIM is done by the mail provider for the domain — EDDI cannot do it, but must **tell** the admin when it is not
happening (the live test on 2026-11-18 found exactly this: `labs.ai` publishes a Google key but Workspace signing was
never started, so mail was signed by Google's stand-in domain and refused `pass-unaligned`). S/MIME is EDDI's own
signature over the message: the recipient's client shows it as signed by the mailbox, it survives forwarding, and
it is per mailbox, not per domain.

### 5.2 Keys (common to both modes, under `platformConfig`)

| Key | Default | Meaning |
|---|---|---|
| `smimeSign` | `true` | `true`: sign when a certificate is configured; without one, mail is sent unsigned and the save warns `email.smime-no-certificate` (so upgrades and quick setups keep working). `required`: never send unsigned — a missing, expired or mismatched certificate holds outgoing mail (`REPLY` rows wait, reason `smime_unavailable`, owner notice). `false`: off |
| `smimeCertificate` | — | `${vault:name}` of a PKCS#12 (base64) holding the key and chain. PEM (key + chain) also accepted |
| `smimeCertificatePassword` | — | `${vault:name}`, for a protected PKCS#12 |

"On by default" is the `true` default; "can be turned off in the JSON" is `false`. The vault grant of the
certificate secret must name the integration (same allowed-hosts style as connection secrets — verify how a non-URL
consumer is granted, §13).

### 5.3 Validation (save time and daily)

- The certificate's `rfc822Name` SAN (or e-mail in the subject) equals `address` (normalised); key usage
  `digitalSignature`; extended key usage `emailProtection`; not expired; chain builds to the bundled trust store or is
  shipped complete. Mismatch → 400 for `required`, warning otherwise.
- Daily: expiry within 30 days → owner notice (`EmailOwnerNotifier`, new reason `CERTIFICATE_EXPIRING`), metric
  `eddi_email_smime_certificate_days_left`.

### 5.4 Signing

- `multipart/signed` (clear-signed, `application/pkcs7-signature`, detached), SHA-256, RSA-PSS or RSA PKCS#1 v1.5
  (verify client support, §13) or ECDSA by key type. Clients without S/MIME still read the text.
- Sign in the reply sender right before SMTP submission, after the body is final; the ledger stores only
  `signed: true|false` on the row (no signature bytes).
- Library: Bouncy Castle `bcpkix-lts8on` plus its Jakarta Mail module (`bcjmail-lts8on` — verify the artifact for the
  LTS line, §13); same version property as `bcprov-lts8on`. No second MIME stack (v1 §22): build on the Angus
  `MimeMessage` already produced.
- Metric `eddi_email_outbound_total{…, signed="true|false"}`.

### 5.5 Sending-health check

`POST /channelstore/channels/{id}/email-self-test` (EDIT): sends a short mail from the integration to its own
`address` with a unique token, the next poll recognises it (by token, before any routing; never a turn), and the
result — DKIM verdict and signing domain, alignment, S/MIME verified or not — is returned by
`GET …/email-self-test/{token}` and shown in the Manager. Plain-language results, e.g. "Your provider signs as
`labs-ai.20230601.gappssmtp.com`, not `labs.ai`: start DKIM authentication for `labs.ai` in the Google Admin console".
Also: DMARC record present or not (advice only). MCP tool `run_email_self_test`.

**Acceptance:** with a certificate in the vault, a reply arrives signed and Gmail/Outlook show it as signed; with
`smimeSign: false` it does not; with `required` and an expired certificate nothing is sent and the owner is told; the
self-test names a provider that does not sign aligned.

## 6. Phase C3 — Held mail: review instead of drop

**Size:** M–L. Branch `feat/email-held-mail`. Depends on C1 (for "run and allow").

### 6.1 Keys

| Key | Default | Meaning |
|---|---|---|
| `onRefused` | `{}` (all `drop`) | Per reason class → `drop` or `review`. Classes: `notAllowlisted` (`blocked:not_allowlisted`), `blocklisted`, `unauthenticated` (`unauthenticated:*`), `notAddressed` (v2 Phase 3, when it exists). Loops, lists, auto-replies, oversize and rate limits are **never** reviewable. The Manager preselects `notAllowlisted: review` for new integrations |
| `reviewExpiry` | `P7D` | A held mail nobody decided is dropped (`review_expired`) |
| `maxHeldPerDay` | `50` | Beyond it, mail of that day is dropped (`review_full`) and counted in the digest — a spam wave cannot fill the queue |
| `reviewers` | the integration's owners | EDDI user ids or roles who are notified and may decide |

### 6.2 Holding

- The processor, where it would refuse with a reviewable reason, transitions the row to the new state `HELD` (both
  ledger stores, Mongo and Postgres; mail-log reason kept, e.g. `held:not_allowlisted`) and leaves the mail in the
  polled folder **unmarked**, or moves it to a sibling `EDDI/Held` folder when `processedAction` is `move:` — verify
  the cursor and resubmission still find it there, §13.
- No body is stored. The reviewer's preview is fetched live: `GET /channelstore/channels/{id}/email-held/{rowId}`
  re-reads the mail by Message-ID from the mailbox and returns sender, verdict, subject, the first 4 000 characters of
  the text (quoted history stripped, hidden content removed by the existing filter), attachment names. Not cached.
- **Notification**: `reviewers` get one notice per batch, not per mail — through the system mailer with a deep link
  (as `hitlConfig.notify.email`) and, when the integration names a Slack approval channel, there. The daily digest
  gains a "held" count.

### 6.3 Deciding

`POST /channelstore/channels/{id}/email-held/{rowId}/decision` `{decision: discard | runOnce | runAndAllow, note}`;
MCP `list_held_email`, `decide_held_email`; the Manager approvals page lists held mail as its own kind next to
conversation pauses (one queue for the human).

- `discard` → row `REFUSED`, reason `review_discarded`, mail marked processed.
- `runOnce` → row back to `RECEIVED` with a one-shot pass flag; the next poll re-fetches it (resubmission path) and
  runs it, skipping only the check that held it. `runAndAllow` additionally adds the exact sender address to the
  allowlist through C1 (audited, `reason` = the note).
- **An unauthenticated mail released by a human** runs only as an anonymous, memory-free identity
  (`email-anon:<integrationId>:<random>`, `hasNoPersistentMemory`, reserved in `ReservedPrincipals`), never gets a
  reply or pause notice (v1 §22 — the From may be forged), and the reviewer's UI says so in plain words before the
  decision. `runAndAllow` is not offered for it (an allowlist entry does not make a forged From true).
- A released mail still meets the target's `approvalPolicy` (C4): releasing it means "process it", not "do whatever
  it asks".
- Who may decide: EDIT on the integration, or a role named in `reviewers`. Every decision is audited.

**Acceptance:** a stranger's mail is held, the owner gets one notice, sees the text in the Manager, picks "run and
allow"; the mail runs once, the sender is on the allowlist (new version), and a second mail from them runs directly;
an unauthenticated held mail never produces outgoing mail.

## 7. Phase C4 — Approval before acting, and reply review

**Size:** M. Branch `feat/email-approval-policy`. Depends on nothing for 7.1–7.2; 7.3 needs C5 for role-based
policies.

"The mail is processed, but if it asks for something, ask me first."

### 7.1 Per target: `approvalPolicy`

New optional field on email targets (refused for other channel types, as v2 §7.1 does for its fields):

| Value | Behaviour |
|---|---|
| `actions` (**Manager default for new email targets**) | The agent runs; every tool call that is not read-only needs approval. Implemented by **merging** a deny-by-default rule into the turn's effective `toolApprovals` for email-originated turns: `requireApproval: ["*"]` minus the target's `readOnlyTools` (new field, explicit list; empty by default). The agent's own `toolApprovals` still apply on top (they can only add) |
| `firstContact` | As `actions`, and a sender's first mail to this target (no earlier `DONE` row from that address) pauses before the agent runs |
| `everyTurn` | Every mail pauses before the agent runs; the approver sees the mail and decides go / reject / go with a note |
| `none` | Today's behaviour: only the agent's own `toolApprovals` |

A pause **before the agent runs** is a regular HITL pause of the mail's conversation: the processor submits the turn
with the context flag `mail.preApproval = true`, and the pipeline pauses before any task that can act (verify the
cleanest hook — a built-in first step that emits `PAUSE_CONVERSATION`, or a pre-turn gate in `ConversationService` —
§13). In conversation mode the sender gets the existing in-thread pause notice; the approver gets the existing
approver notification; Manager, Slack and MCP (`list_pending_approvals`, `resume_conversation`) all work unchanged.
"Reject" ends the turn with the target's `rejectedReply` (new, optional; default: no reply in trigger mode, a short
"Your request was not approved." in conversation mode).

Migration: existing integrations keep `none` on upgrade (no behaviour change); the Manager preselects `actions` for
new email targets. Save warning `email.approval-policy-none` when a target with tools is `none`.

### 7.2 Reply review (v2 §11 backlog item 1, moved here)

| Key (conversation mode) | Default | Meaning |
|---|---|---|
| `replyReview` | `never` | `always`, `firstContact`, `external` (recipient not matching `internalDomains`), `never` |
| `internalDomains` | empty | Patterns that count as internal for `external` and for C6 |

A reply under review is a pause **after** the turn and **before** sending: the reply row waits in a new state
`REVIEW`, the approver sees the exact text and may approve, reject, or **edit** it (the edited text is what is signed
and sent; both versions are kept in the audit ledger, not in the ledger row). Approve in Manager, Slack or MCP; never
by mail. Expiry like `reviewExpiry`; an expired reply is not sent (`review_expired`).

### 7.3 "Approved sender, forbidden ask"

Layers, documented together in "Keeping an email agent in bounds":

1. The agent's tools are the blast radius (v1) — give it the fewest.
2. `approvalPolicy` (7.1) and per-tool rules (`docs/hitl.md`) — the consequential calls wait for a human.
3. Sender roles (C5) — what this sender may reach at all.
4. Reply review (7.2) — what leaves.
5. Guardrails ([`guardrails-architecture.md`](guardrails-architecture.md)) once they ship — refusals by topic.

**Acceptance:** a target on `actions` answers a question without approval, but its `http.post` call pauses; on
`everyTurn` the turn pauses before the model is called (no cost recorded); under `replyReview: always` an edited reply
is the one that arrives, signed.

## 8. Phase C5 — Sender roles: it matters who said it

**Size:** M. Branch `feat/email-sender-roles`. Depends on C1.

### 8.1 Keys

```json
"senderRoles": [
  { "role": "exec",     "senders": ["cfo@company.com", "ceo@company.com"] },
  { "role": "staff",    "senders": ["*@company.com"] },
  { "role": "customer", "senders": ["*@customer-a.com", "*@customer-b.de"] }
],
"rolePolicies": {
  "exec":     { "targets": ["*"], "approvalPolicy": "actions", "sendOut": true,  "maxTurnsPerDay": 200 },
  "staff":    { "targets": ["*"], "approvalPolicy": "actions", "sendOut": true },
  "customer": { "targets": ["support"], "approvalPolicy": "actions", "tools": ["kb_search"], "sendOut": false }
}
```

- First matching `senderRoles` entry wins (order is explicit); a sender matching none has role `default`, whose
  policy defaults to today's behaviour.
- A role applies only to an **authenticated** sender (aligned DKIM pass). Unauthenticated or released mail (C3) is
  always role `anonymous`, which can never send out and never reach tools beyond `readOnlyTools`.
- `rolePolicies.<role>` may **narrow** what the target allows (targets, tool allow-list intersected with the agent's,
  a stricter `approvalPolicy`, lower caps) — never widen it. Validation rejects a role policy that would.
- The turn context carries `mail.senderRole`, so an agent's prompt can address an executive differently from a
  customer; behaviour that matters is enforced in code, not left to the prompt.
- Roles and policies are editable over REST and MCP like the sender lists (C1): `list_email_roles`,
  `update_email_roles` (expected version, audited, same privilege-escalation guard).

### 8.2 Trust

DKIM authenticates the **domain**, not the person. Within one's own Workspace/M365 domain a person normally cannot
send as a colleague, but aliases, "send mail as" and shared mailboxes can open that up. Hence:

- Roles bind to addresses, not to EDDI users, in this phase. The identity stays `email:<id>:<address>`,
  `SELF_ASSERTED`; `PER_USER` connections stay refused (v1).
- The Manager shows, next to a role with sensitive powers (`sendOut`, any non-read-only tool), "trust rests on your
  mail provider preventing one person from sending as another" and links the self-test (C2).
- Linking a sender to an EDDI user's own grants is **open question Q1** (§14), not in this phase.

**Acceptance:** the CFO's mail reaches the finance target and may send out; a customer's mail reaches only `support`
and its tool list is cut to `kb_search`; a role policy that tries to add a tool the agent lacks is refused at save.

## 9. Phase C6 — Approved send-out to third parties

**Size:** L. Branch `feat/email-send-out`. Depends on C4 (approvals), C5 (`sendOut` role), and for the Cc case on v2
Phase 3 (addressing, `actWhen: addressed`), which depends on #1394.

### 9.1 The shape

"@eddi please send customer xyz the offer we discussed" in a mail an authorised sender sends (or Cc's EDDI on) is
routed to a **send-out target**: an agent built for it — one tool, `sendMail`, plus read-only access to exactly the
data it may attach or quote, no long-term memory. Ship it as a starter configuration ("Mail clerk") in
`docs/agent-configs/` so admins do not build it from scratch.

### 9.2 Keys (per integration)

| Key | Default | Meaning |
|---|---|---|
| `sendOut.enabled` | `false` | Assemble `sendMail` for targets marked `sendOutTarget: true`, for senders whose role has `sendOut: true` |
| `sendOut.outboundRecipients` | — (required when enabled) | `SenderPatterns` syntax (`*@customer-a.com`, exact addresses), max 500, and/or `contactSource`: a global variable or an `httpcalls` lookup (CRM) returning `{name, address}` for a contact name. An address outside these is refused before approval |
| `sendOut.approveNewRecipients` | `true` | A recipient never sent to through send-out needs approval. **Cannot be set `false` for external recipients** (not in `internalDomains`) |
| `sendOut.approveEverySend` | `false` | Every send waits for approval |
| `sendOut.maxPerDay` | `20` | Per integration, counted from the ledger |
| `sendOut.maxRecipientsPerMail` | `1` | No Cc/Bcc from the model, ever; >1 only lists addresses explicitly named in one approval |
| `sendOut.copyRequester` | `true` | The requester is Cc'd (they asked, they see what went out) |
| `sendOut.allowReplies` | `true` | The recipient's answer to that mail continues the send-out conversation (9.4) |
| `sendOut.replyWindow` | `P14D` | How long the recipient's answers are accepted in that thread |

`outboundRecipients` is editable over REST/MCP like the sender lists (C1).

### 9.3 The tool

`sendMail(recipient, subject, body)` where `recipient` is a contact name or an address. Resolution and checks run in
code, in this order: resolve contact → address in `outboundRecipients` → suppression list (GDPR) → caps → approval
(request pinning binds recipient, subject and body; the approver sees all three and may edit subject/body, as in
7.2). From is the integration's `address`, signed (C2). Ledger kind `SENDOUT`, direction `OUT`, with the requesting
sender and the conversation; digest counts; mail-log filter `kind=SENDOUT`.

### 9.4 A narrow conversation with the recipient

With `allowReplies`, the recipient's answer (In-Reply-To the send-out's Message-ID, signed view, aligned DKIM) is
accepted **for that thread only** even though the recipient is not on the allowlist: a thread-scoped participant
entry, expiring after `sendOut.replyWindow`. It runs in the send-out conversation under a memory-free
`email-sendout:<integrationId>:<threadRoot hash>` identity with `anonymous` powers plus answering in the thread; the
agent's answer to the recipient falls under `replyReview` (default for send-out threads: `external` → reviewed). The
requester is told of the answer (Cc or notice, per `copyRequester`). Anything else the recipient sends outside that
thread is ordinary mail and meets the allowlist (or C3).

**Acceptance:** staff writes "send the attached offer to Jane at customer-a"; the clerk resolves Jane, the approver
sees recipient/subject/body, approves; Jane receives a signed mail with the requester in Cc; Jane's reply continues
that conversation, its answer waits for review; an address outside `outboundRecipients` is refused without asking
anyone; a customer-role sender asking for a send-out gets no `sendMail` tool.

## 10. Phase C7 — The admin view

**Size:** M. Branch `feat/email-control-view`. Grows with each phase; start with C1.

One "Mail control" tab per email integration in the Manager:

- **Senders**: allow/block lists (C1), roles and their policies (C5), "last changed by".
- **Held mail** (C3) and **waiting replies / send-outs** (C4, C6) — or a filtered link to the approvals page, which
  stays the one queue.
- **What goes out**: mail-log filter for `REPLY`, `SENDOUT`, `NOTIFY`, with signed/unsigned.
- **Health**: certificate status and expiry, last self-test result (DKIM, alignment, DMARC), last poll.
- **Policy summary in plain words**: "Staff can reach all agents; consequential actions need approval by the finance
  owners; customers can only reach support; mail from unknown senders is held for review."

Every value on the tab is also reachable over MCP (`list_email_senders`, `update_email_senders`, `list_email_roles`,
`update_email_roles`, `list_held_email`, `decide_held_email`, `run_email_self_test`, `read_email_policy`), so the
Platform Operator can answer "who may make the finance agent do what?" and make the change behind an approval.
Operator prompt, allow-list and revision bump in each phase that adds a tool.

## 11. Order and dependencies

| Phase | Size | Depends on | Notes |
|---|---|---|---|
| C1 Sender lists | S | — | First: everything else builds on it |
| C2 S/MIME + self-test | M | — | Parallel with C1 |
| C3 Held mail | M–L | C1 | |
| C4 Approval policy, reply review | M | — (7.3: C5) | Parallel with C3 |
| C5 Sender roles | M | C1 | |
| C6 Send-out | L | C4, C5; v2 Phase 3 (→ #1394) for the Cc/mention case | A To-addressed request works without v2 Phase 3 |
| C7 Admin view | M | grows per phase | |

Merge order with v2: #1458 (v2 Phase 2) and #1476 (v2 Phase 4) first. Each phase that changes the operator bumps
`operator-revision.json`; land those serially.

## 12. Cross-cutting checklist

Every phase: both datastores (Mongo and Postgres twins, ledger state additions in both), REST + MCP + Manager +
operator, all 11 locales, `docs/email-integration.md` and the skills in `integrations/claude-plugin/`, metrics,
GDPR (`findByRecipient` / `deleteByRecipient` cover new rows and stores), audit entries for every human decision and
every policy change, save-time validation with actionable errors, unit tests plus an IT through the real store, a
mutation check of each security test (revert the guard, the test must fail), a live run against the test mailbox
(`eddi@eddi.solutions`) before declaring done, changelog fragment.

## 13. Things to verify before relying on them

1. The cleanest hook for a pause **before** the agent runs (C4 7.1): a built-in first workflow step emitting
   `PAUSE_CONVERSATION`, or a gate in `ConversationService` — and that the resume path then runs the full pipeline.
2. HITL tool approvals merged per turn (an email-only deny-by-default added to the agent's own `toolApprovals`)
   without changing the stored agent document, and that request pinning still holds.
3. How the vault grants a secret to a non-URL consumer (the S/MIME certificate) — connections use allowed hosts.
4. Bouncy Castle artifacts for S/MIME on the LTS line (`bcpkix-lts8on`, `bcjmail-lts8on`?) and their versions next to
   the managed `bcprov-lts8on`; license and OpenSSF checks.
5. How Gmail (web, consumer and Workspace), Outlook (desktop, web), Apple Mail and Thunderbird display a clear-signed
   message from a public CA S/MIME certificate; RSA-PSS vs PKCS#1 v1.5 support.
6. Holding mail in the polled folder vs an `EDDI/Held` folder: cursor progress, resubmission finding it by
   Message-ID, Gmail labels vs folders.
7. That the self-test mail is recognised before routing and can never start a turn, and that a forged copy of it
   (token reuse) does nothing.
8. Slack approval channels: can a non-conversation item (held mail) be posted and decided there, or only pauses?
9. On Windows, dnsjava without `eddi.email.dns.servers` takes every adapter's resolvers (including dead `fec0::` and
   disconnected ones) and every DKIM lookup fails temporarily (seen 2026-11-18). Document it; consider logging the
   lookup exception at DEBUG and naming the servers tried in the `auth_temporary` mail-log note.

## 14. Open questions

- **Q1 — Link senders to EDDI users?** An authenticated sender mapped to exactly one directory user with a verified
  address could carry that user's roles and `PER_USER` grants. Needs a decision on provenance (`SELF_ASSERTED` today)
  and an admin assertion per domain that the provider prevents impersonation. Out of C5 until decided.
- **Q2 — One certificate per integration or a deployment default?** Per integration matches "per mailbox"; a default
  would simplify small setups.
- **Q3 — S/MIME for the system mailer** (HITL notifications, `eddi.mail.smtp.*`) too? Same code, separate
  certificate.
- **Q4 — Default of `onRefused.notAllowlisted`** for new integrations: `review` (preselected) or ask in the wizard.

## 15. What I would NOT do

- **No approval by replying to a mail**, ever (v1 §22, v2 §2.3) — not for held mail, not for send-outs.
- **No reply, pause notice or send-out caused by an unauthenticated mail**, even after a human released it.
- **No model-typed address outside `outboundRecipients`**; no Cc/Bcc the model adds.
- **No storing mail bodies** to make review easier; previews are fetched live.
- **No mail-triggered agent that can change its own integration** (sender lists, roles, policies).
- **No widening by roles**: a role policy can only narrow what the target and agent allow.
- **No silent unsigned mail under `smimeSign: required`.**
