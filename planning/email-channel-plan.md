# Implementation Plan — Email Support

**Status:** Phases 0–3 are implemented (mail core, sockets and tokens, HITL notifications, trigger mode, conversation mode — see [`docs/email-integration.md`](../docs/email-integration.md)); Phases 4–7 (Graph, relay webhooks, Gmail, extras) are not. Revised once after a critical review (§2 lists what changed and why). The sections below are the plan as written; where the implementation deviated, the changelog fragments under `docs/changelog.d/2026-10-2*-feat-email-*.md` say so.
**Created:** 2026-10-24
**Verified:** 2026-10-24 against `main` @ `5028af0a3`. Every EDDI class named here exists at that commit, and
no mail, DNS or Markdown library was in `pom.xml` there (Phase 0 adds Angus Mail). The provider facts in §2 and §7 were checked against
provider documentation on 2026-10-24. Re-check them at implementation time, because they drift.
**Effort:** T-shirt sizes only — **XS** (a day or two) · **S** · **M** · **L** · **XL** (several people or a long
stretch). Sizes are relative to each other, not commitments.
**Related:** [`rag-ingestion-source-types-plan.md`](rag-ingestion-source-types-plan.md) §8 (a mailbox as a read-only
knowledge source: a different feature that needs the same mail core), [`saas-connectors-plan.md`](saas-connectors-plan.md)
(connections), [`multi-agent-ux-improvements.md`](multi-agent-ux-improvements.md) §2.2 (multi-channel adapters),
[`hitl-framework-plan.md`](hitl-framework-plan.md).

---

## Table of Contents

1. [Verdict](#1-verdict)
2. [What the review changed](#2-what-the-review-changed)
3. [Who this is for — and who should not use it](#3-who-this-is-for--and-who-should-not-use-it)
4. [What already exists](#4-what-already-exists)
5. [Three capabilities, built in order of risk](#5-three-capabilities-built-in-order-of-risk)
6. [Architecture](#6-architecture)
7. [Transports](#7-transports)
8. [Sender authentication — EDDI verifies DKIM itself](#8-sender-authentication--eddi-verifies-dkim-itself)
9. [Identity](#9-identity)
10. [The configuration](#10-the-configuration)
11. [Threading and conversation mapping](#11-threading-and-conversation-mapping)
12. [The message ledger: dedup, outbox, threading index](#12-the-message-ledger-dedup-outbox-threading-index)
13. [Loops, spam, cost abuse](#13-loops-spam-cost-abuse)
14. [Untrusted content and prompt injection](#14-untrusted-content-and-prompt-injection)
15. [Rendering replies](#15-rendering-replies)
16. [HITL](#16-hitl)
17. [Personal data, retention, audit](#17-personal-data-retention-audit)
18. [Phases](#18-phases)
19. [Files that would change](#19-files-that-would-change)
20. [Metrics](#20-metrics)
21. [Tests and verification](#21-tests-and-verification)
22. [What I would NOT do](#22-what-i-would-not-do)
23. [Open questions](#23-open-questions)

---

## 1. Verdict

**Yes, but smaller and in a different order than the obvious design.** Ship it as three capabilities, from least
to most dangerous:

1. **Outbound notifications** — EDDI emails *its own staff*, for example "a conversation is waiting for your
   approval", with a link to the existing Manager approvals page. No inbound mail, no public exposure. Small, and
   it closes a real HITL gap for approvers who are not on Slack.
2. **Inbound trigger mode** — mail arriving at a process mailbox (`invoices@`, `orders@`, `claims@`) starts an
   agent turn with its attachments, and the agent acts through its tools. **No reply goes back to the sender.**
   This is the webhook channel's shape with mail as the payload. Most of the business value of "email +
   agents" sits here, and leaving out the reply removes the worst risks: backscatter, reply loops, and the agent
   as an exfiltration path.
3. **Conversational mode** — the agent replies in the thread. This is the full email channel and the riskiest
   part. It is built last, on the guards the first two already proved.

It is a **channel integration** (`channelType: "email"`), not a lifecycle task and not a new resource type.

Two things EDDI should not become: a mail server (no MX, no SMTP listener), and a "the LLM emails whoever it
likes" tool.

---

## 2. What the review changed

The first draft was internally consistent, but several of its foundations were wrong or too expensive:

| # | First draft | Problem found | Now |
| --- | --- | --- | --- |
| 1 | Trust the provider's SPF/DKIM/DMARC verdict "from the signed webhook payload" | **Mailgun's signature is HMAC(key, timestamp + token). It does not cover the body**, so the verdict fields are not signed at all. Exchange Online's `Authentication-Results` **has no authserv-id** (it does not follow RFC 8601), so "trust the header whose authserv-id is ours" cannot work there. Every provider reports a verdict differently, or not at all | **EDDI verifies DKIM itself on the raw MIME and checks DMARC alignment** (§8). This works the same on every transport. Provider verdicts are only a fallback, and only where they are signed (SES over SNS) |
| 2 | Relay provider (Mailgun) as the first transport | EDDI is self-hosted, and often deployed with no public ingress. Push webhooks need ingress. Most target organisations already have a mailbox on Exchange Online or a hoster, not a Mailgun account | **IMAP in + SMTP out first** (§7). They are universal and work outbound-only. On Exchange Online they authenticate with OAuth client credentials, and the app's access is **granted per mailbox**. Relay webhooks come later, as a low-latency option |
| 3 | Conversational replies as Phase 1 | Most of the risk is in the reply (backscatter, loops, exfiltration), while much of the value needs none | Replies come **last**. Notifications and trigger mode go first (§5) |
| 4 | Email-specific approvals: single-use tokens, a new Manager page, `hitlApproverAddresses` | The Manager **already has an approvals page** (`ui/manager/src/pages/approvals.tsx`) and OIDC login. A token flow duplicates that and adds a credential type | The notification **deep-links to the existing page**, and EDDI's existing authorization decides who may approve. The token phase is gone (§16) |
| 5 | HITL notifications configured on the email channel | A pause notification is needed for *every* conversation (Manager, Slack, REST, `/v1`), not only email ones | Notifications move to the **agent's HITL config plus a deployment-level mailer** (§16), separate from the channel |
| 6 | `onAuthenticationFailure: anonymous` still replied | Replying to an unauthenticated `From` sends mail to whoever was forged: backscatter, harassment, reputation damage | **The agent never replies to an unauthenticated sender**, in any mode (§9) |
| 7 | Thread hijack prevented "because the mapping is keyed by user id" | That only holds for `perSender` identity. With a shared identity, anyone who has a thread's Message-ID joins the thread | An **explicit sender check** against the conversation's original sender, whatever the identity mode (§11) |
| 8 | Separate stores for outbox, dedup and thread index, with HMAC-derived Message-IDs | Three stores and a crypto scheme for one fact: *this message, this conversation, this state* | **One message ledger** (§12) and random Message-IDs. A suppression list is the only other store |
| 9 | Outbox written when the turn completes | A crash between turn completion and the outbox write loses the reply silently | The ledger entry is written **before** the turn is submitted, and a sweeper finishes stranded entries from the conversation snapshot (§12) |
| 10 | On quota exhaustion, park the mail and retry | Builds a parking mechanism that already exists elsewhere | Push: answer non-2xx and the provider retries. Poll: leave the message unmarked and the next poll retries (§13) |
| 11 | HTML replies from Markdown, quick replies parsed from "reply 1/2/3" | No Markdown renderer in `pom.xml`. Parsing digits out of a reply breaks on signatures and quoted text | **`text/plain` only in v1**. HTML is a later, separately sized item. No quick-reply parsing (§15) |
| 12 | Always strip quoted history | Inline answers ("see my answers below") live inside the quote and would be deleted | Strip only recognised quote blocks, and make it configurable (`stripQuotedHistory`, default on) (§14) |
| 13 | Spam and LLM cost not addressed | DMARC-passing spam is normal, since spammers own their domains. Every accepted mail spends tokens | Poll only the folder that comes after the mail server's own filtering, plus a per-integration daily cost budget on top of `TenantQuota.maxMonthlyCostUsd`, per-sender limits, and allowlists by default in trigger mode (§13) |
| 14 | "Address unique per tenant" | Two tenants polling the same mailbox would each process every mail | Unique **per deployment** for polling transports |
| 15 | Ignored that a helpdesk often owns `support@` | If Zendesk, Freshdesk, Front or Help Scout already handle the mailbox, an email channel bypasses their threading, assignment and human handoff | §3 says when **not** to use the email channel, and documents the webhook channel + `httpcalls` recipe for helpdesks |
| 16 | Week estimates | — | T-shirt sizes |

The rest of the first draft held up and is kept: the channel framing, namespaced identity, `Reply-To` not honoured,
no subject-line threading, the loop guard, the hidden-content filter, no LLM-chosen recipients, no approve-on-GET,
one mail core shared with RAG, and polling through the schedule machinery.

---

## 3. Who this is for — and who should not use it

| Situation | Use |
| --- | --- |
| A helpdesk (Zendesk, Freshdesk, Front, Help Scout, ServiceNow) already receives the mail | **Not the email channel.** Use the helpdesk's trigger → EDDI **webhook channel**, and have the agent answer through `httpcalls` against the helpdesk API with a connection. The helpdesk keeps threading, spam handling, assignment and human handoff. Deliverable: a documented recipe and an example agent config (Phase 2) |
| A process mailbox with no humans corresponding on it: invoices, orders, CVs, claims, forms | **Trigger mode** — the core case |
| A small team's `info@` / `support@` with no helpdesk, mostly known correspondents | **Conversational mode**, with an allowlist where possible |
| Public, high-volume support | A helpdesk, with EDDI behind it (first row) |
| Internal staff need to know an agent is waiting for them | **Notifications** |

---

## 4. What already exists

| Piece | Where | Reused for |
| --- | --- | --- |
| Channel integrations | `ChannelIntegrationConfiguration`, `RestChannelIntegrationStore` (`REGISTERED_CHANNEL_TYPES = {"slack","webhook"}`) | The resource, `targets`, `defaultTargetName`, save validation, USE checks on targets, Manager Channels page, MCP `create_channel_integration` |
| Router and vault | `ChannelTargetRouter` | `${vault:…}` resolution, cache, invalidation when a secret changes |
| Typed settings | `WebhookChannelSettings` | Pattern for `EmailChannelSettings`: one reader for the save path and the receive path |
| Turn submission with a completion callback | `conversationService.say(…, onComplete)` as used by `SlackEventHandler` | Running the turn and capturing its output |
| Thread → conversation mapping | `IUserConversationStore`, `WebhookEventHandler.correlatedConversation` | Mapping a thread to a conversation, including the create-race handling |
| Namespaced identity | `SlackUserIdentity` | `email:` prefix that no other source can produce |
| Identity provenance | Conversation `VERIFIED` / `SELF_ASSERTED`; `ConnectionResolver` refuses `PER_USER` without `VERIFIED` | Email turns are `SELF_ASSERTED`, so per-user credentials are refused automatically |
| Connections | `connections/*` | `BASIC` (app password), `OAUTH2_CLIENT_CREDENTIALS` (Exchange Online IMAP/SMTP, Graph) |
| Scheduled source runs | `RagIngestionSchedules` + `ScheduleFireExecutor` (schedules tagged by metadata) | Mailbox polling: same mechanism, a new metadata marker. Cluster-safe and logged |
| Maintenance sweeps | Quarkus `@Scheduled(identity=…)`, e.g. `HitlCrashRecoveryObserver` | Ledger sweeper (§12) |
| HITL continuation | `HitlResumeCompletedEvent`, `SlackHitlResumeObserver` | Pushing the resumed output into the mail thread |
| Approvals UI | `ui/manager/src/pages/approvals.tsx` | Target of notification links |
| User directory | `UserDirectory*Store` (`email`, `email_verified`) | Notifying EDDI users by their verified address |
| Attachments | `IAttachmentStore` (GridFS / Postgres) | Inbound attachments (trigger mode needs them early) |
| HTML conversion | `HtmlToMarkdownConverter` (jsoup) | Mail HTML → text, gains the hidden-content pass |
| Quotas | `TenantQuota` (`maxConversationsPerDay`, `maxApiCallsPerMinute`, `maxMonthlyCostUsd`) | Applied per mail as for webhooks |
| GDPR | `IGdprParticipant` | Erasure and export of email-derived data |

**Missing:** a MIME library, DNS TXT lookups (for DKIM and DMARC), a public-suffix list (for DMARC alignment), a
socket-level SSRF guard (`SafeSocketConnector` is only *proposed* in the RAG plan §3.10), a bare-token accessor on
connections (`resolveAccessToken`, also only proposed there), and any mail rendering.

---

## 5. Three capabilities, built in order of risk

| | Notifications | Trigger mode | Conversational mode |
| --- | --- | --- | --- |
| Direction | out only | in only | in and out |
| Who receives mail from EDDI | Configured staff / EDDI users | Nobody | The authenticated sender |
| Public exposure | none | the mailbox address | the mailbox address |
| Main risks | wrong recipient, leaking content | injection through tools, cost abuse | all of those, plus backscatter, loops, hijack, exfiltration |
| Needs sender authentication | no | for identity and memory only — unauthenticated mail can still be processed without memory | yes, to reply at all |
| Config | deployment mailer + agent `hitlConfig` | channel `mode: "trigger"` | channel `mode: "conversation"` |

The channel is **one** `channelType: "email"` with `mode`, so a mailbox can move from trigger to conversation mode
without being re-created.

---

## 6. Architecture

```text
 IMAP poll (schedule) ─┐                                          ┌─▶ SMTP / Graph / provider API
 Graph poll (schedule) ┼─▶ raw MIME ─▶ mail core ─▶ guards ─▶ turn ┤      (via ledger, retried)
 Provider webhook ─────┘   (bytes)    (parse,     (auth, loop,  │    └─▶ (trigger mode: nothing)
                                       strip,      dedup,       │
                                       sanitise)   hijack)      └─▶ HitlResumeCompletedEvent ─▶ continuation
```

- **Raw MIME is the one input format.** Every transport delivers the RFC 5322 bytes (IMAP `BODY[]`, Graph
  `/$value`, Mailgun's MIME-forwarding route, SES's SNS content). There is one parser, one DKIM verifier and one
  set of guards, and a transport is only "fetch bytes, mark done, send bytes". Transports whose payload is
  pre-parsed JSON without raw MIME are not supported.
- **Mail core** (`engine/mail/`, shared with RAG): MIME parsing, address normalisation, DKIM verification + DMARC
  alignment, quote stripping, auto-reply/bounce detection, the hidden-content pass, `InboundMail`/`OutboundMail`
  records. It knows nothing about conversations.
- **`MailTransport` SPI**, String-keyed like `channelType`: `fetch(cursor) → batch of raw messages + new cursor`
  or `receive(request) → raw message`, `markProcessed(id)`, `send(raw MIME) → provider id`,
  `declaredEndpoints()` (for save-time allowlist checks).
- **Not a lifecycle task.** It creates turns and consumes their output (AGENTS.md §4.2).

---

## 7. Transports

### 7.1 IMAP + SMTP — first

- **Universal:** Exchange Online, Microsoft 365, Gmail/Workspace, every hoster and on-prem Exchange. It is
  **outbound-only**: no public ingress, which matters for a self-hosted engine.
- **Exchange Online:** OAuth client credentials (`OAUTH2_CLIENT_CREDENTIALS` connection, XOAUTH2 SASL). The
  service principal is granted access **per mailbox** (`Add-MailboxPermission`), which is narrower than Graph
  application permissions. Basic auth is retired there. Verify Microsoft's current IMAP/SMTP OAuth guidance at
  implementation time.
- **Other hosts:** an app password through a `BASIC` connection.
- **Gmail:** IMAP with XOAUTH2 needs a per-user grant. A polling job has no conversation and so no `VERIFIED`
  principal, so it depends on the RAG plan's run-as-principal mechanism for scheduled sources. Gmail therefore
  ships after that exists (Phase 6), never through domain-wide delegation.
- **Inbound:** poll one configured folder (default `INBOX`, which is after the server's own spam filtering) on a
  schedule tagged like `RagIngestionSchedules` (default every 60 s). The cursor is `UIDVALIDITY` + last processed
  UID per folder. A changed `UIDVALIDITY` triggers a rescan, deduplicated by the ledger. After the ledger records
  a message: set a keyword (`$EddiProcessed`) or move it to a configured folder. Never delete. IMAP IDLE is a
  later latency improvement.
- **Outbound:** SMTP submission (587 STARTTLS / 465 TLS), same connection.
- **Sockets:** through `SafeSocketConnector`, built once for this plan and the RAG plan. It resolves the host
  once, refuses private, loopback, link-local and metadata ranges, connects to the resolved address, allows only
  ports 993/143+STARTTLS and 465/587+STARTTLS, always validates certificates, and applies timeouts. Hosts must also
  match `eddi.email.allowed-hosts` when that is set, so an operator can confine which servers channel authors may
  name.
- **Library:** Angus Mail (Jakarta Mail), with the socket factory replaced by `SafeSocketConnector`. Quarkus
  Mailer is the wrong fit: its mailers are static configuration, and integrations bring their own credentials at
  runtime. Check Angus Mail's license and CVE history and pin it in `pom.xml`.

### 7.2 Microsoft Graph — second, when Graph is preferred over IMAP

Same mailbox, API access: delta query as the cursor (via the same schedule mechanism), `/$value` for raw MIME,
`/reply` for threaded replies. Application permissions cover **every mailbox in the tenant** unless restricted
with Exchange Online RBAC for Applications. That restriction is a mandatory step in setup, and the docs include a
test ("read an unrelated mailbox, expect 403"). Save-time validation cannot detect an unrestricted grant. Build
it together with the RAG M365 transport. Only worth building if customers refuse IMAP.

### 7.3 Relay provider webhooks — optional, for cloud deployments that want push

- **Mailgun** with a MIME-forwarding route. The signature authenticates *that Mailgun sent the request*, not the
  body. So the token must be **single-use cluster-wide** (stored in the ledger), the timestamp fresh, and the
  body's authenticity comes from EDDI's own DKIM check (§8).
- **SES** through SNS. The SNS signature *does* cover the message. The signing-certificate URL must be pinned to
  `https://sns.<region>.amazonaws.com/` and cached. SNS-delivered content is size-limited: larger mail goes
  through S3, which needs a separate fetch path.
- Each provider is a small adapter on the SPI. Outbound goes through the provider's send API via `OutboundHttp`.

---

## 8. Sender authentication — EDDI verifies DKIM itself

**Why:** the `From:` header is a string the sender writes. Provider verdicts are inconsistent, often unsigned
(§2 #1), and absent for IMAP. Verifying the signature on the raw message works everywhere.

**What:** `DkimVerifier` (RFC 6376: relaxed/simple canonicalisation, `rsa-sha256` and `ed25519-sha256`,
`l=` refused, body hash, header list) plus a DMARC alignment check (RFC 7489: the `From` domain and a
passing signature's `d=` share an organisational domain via the public-suffix list, relaxed or strict according
to the domain's `_dmarc` policy).

- **Result** `mail.authentication`: `aligned-pass` · `pass-unaligned` · `fail` · `none`, plus the signing domain.
  Only `aligned-pass` counts as authenticated.
- **SPF is not evaluated.** EDDI never sees the connecting IP. DMARC is satisfied by aligned DKIM alone. Senders
  that publish SPF but don't sign with DKIM fail, which is documented as a known limitation (most large senders
  sign).
- **Forwarded and list mail** often breaks DKIM. ARC is out of scope for v1.
- **DNS:** TXT lookups for `selector._domainkey.<d>` and `_dmarc.<org>`, through a resolver with timeouts, a
  cache honouring the record's TTL (capped), a limit on lookups per message, and DNSSEC not required. This needs
  a DNS library (dnsjava or similar): check and pin it. The resolver is configurable (`eddi.email.dns.servers`)
  for air-gapped deployments.
- **Fallback:** where the transport delivers a **signed** provider verdict (SES), it may be used when the local
  check cannot run. It is never used in place of a local `fail`.
- **Strength, stated plainly:** an aligned pass authenticates the *domain*, not the person. Anyone who can send
  from that domain can write as any of its addresses.

Size: **M**, mostly the test corpus (§21).

---

## 9. Identity

- **User id:** `email:<integrationId>:<normalised address>`. The prefix means no OIDC principal or other channel
  can produce it. Scoping it to the integration means a weakly configured integration cannot read memories built
  under a strict one. The cost: deleting and re-creating an integration starts every correspondent with fresh
  memory. Normalisation lower-cases the address and converts IDNs to ASCII. There is no dot or `+tag` folding,
  because that is wrong for most domains.
- `identityMode: "integration"` → one shared id `email:<integrationId>`, like webhooks.
- **Not authenticated** (`fail` / `none` / `pass-unaligned`): the turn may run (trigger mode, if allowed) under a
  one-off id `email-anon:<random>`. It gets **no long-term memory**, and **no reply is ever sent**, in any mode.
- **Provenance** is always `SELF_ASSERTED`, so `PER_USER` connections are refused by the existing rule. The docs
  say not to set `allowUnverifiedPrincipal` for email.
- **Reply recipient** (conversation mode): the authenticated `From`, never `Reply-To` (it is not covered by
  DMARC) and never `Cc`. The only exception is `honorReplyTo` (default `false`) for a form relay that sends on
  behalf of a user. When it is `true`, a reply goes to the `Reply-To` only if the message carries exactly one
  `Reply-To` address (none or several → the authenticated `From`); the address is still checked against the
  suppression list, never widened to `Cc`, and saving the integration with the flag raises a warning, because a
  sender who passes DMARC chooses that recipient.

---

## 10. The configuration

```json
{
  "name": "Invoice intake",
  "channelType": "email",
  "platformConfig": {
    "mode": "trigger",
    "transport": "imap",
    "connection": "exo-invoices-mailbox",
    "host": "outlook.office365.com",
    "address": "invoices@contoso.com",
    "folder": "INBOX",
    "processedAction": "move:EDDI/Processed",
    "pollSeconds": "60",

    "requireAuthentication": "false",
    "senderAllowlist": "*@supplier-a.com,*@supplier-b.de",
    "senderBlocklist": "",
    "identityMode": "perSender",

    "attachments": "store",
    "attachmentMimeTypes": "application/pdf,image/png,image/jpeg",
    "maxMessageBytes": "26214400",
    "stripQuotedHistory": "true",
    "inputTemplate": "{mail.subject}\n\n{mail.text}",

    "maxTurnsPerSenderPerDay": "50",
    "dailyCostBudgetUsd": "20"
  },
  "targets": [
    { "name": "intake", "type": "AGENT", "targetId": "<agentId>" }
  ],
  "defaultTargetName": "intake"
}
```

Conversation mode adds `"mode": "conversation"`, `smtpHost`, `fromName`, `replyTemplate`, `aiDisclosure`,
`threadParticipants`, `maxRepliesPerThreadPerHour`, and forces `requireAuthentication: true`.

| Key | Default | Meaning |
| --- | --- | --- |
| `mode` | — (required) | `trigger` or `conversation` |
| `transport` | — (required) | `imap`, later `graph`, `mailgun`, `ses`, `gmail` |
| `connection` | — (required) | Credentials. The connection's allowlist must cover the transport's endpoints, checked at save time |
| `host` / `smtpHost` | per transport | IMAP / SMTP server. Checked against `SafeSocketConnector` and `eddi.email.allowed-hosts` |
| `address` | — (required) | The mailbox. Unique per deployment. Also the `From` of replies |
| `folder`, `processedAction` | `INBOX`, `flag` | What is polled, and what happens to a processed message (`flag` or `move:<folder>`, never delete) |
| `requireAuthentication` | `true` | `false` lets unauthenticated mail run a turn in trigger mode, with no memory (§9). Forced `true` in conversation mode |
| `senderAllowlist` / `senderBlocklist` | empty / empty | Address or `*@domain` patterns. The integration's own address is always blocked. The save path **warns** for a trigger-mode integration with an empty allowlist and `requireAuthentication: false` |
| `identityMode` | `perSender` | `perSender` or `integration` |
| `attachments` | `drop` | `drop`, `metadata` or `store` (→ `IAttachmentStore` → multimodal pipeline) |
| `attachmentMimeTypes` | empty | Allowlist. Executables are always refused, and archives are never expanded |
| `maxMessageBytes` | 25 MB | Checked on the raw size before parsing, and before fetching where the protocol reports the size |
| `stripQuotedHistory` | `true` | Removes recognised quote blocks only (§14) |
| `inputTemplate` | `{mail.subject}\n\n{mail.text}` | Author Qute template over the `mail` map. The mail is data, never the template (AGENTS.md §4.4) |
| `replyTemplate` | `{output}` | Conversation mode only: wraps the agent output |
| `aiDisclosure` | a short default sentence | Conversation mode only. Appended to every reply. Empty disables it, and the save path warns ([`docs/eu-ai-act-compliance.md`](../docs/eu-ai-act-compliance.md)) |
| `threadParticipants` | `originalSenderOnly` | Conversation mode only (§11) |
| `maxRepliesPerThreadPerHour` | `5` | Conversation mode only. Loop brake (§13) |
| `maxTurnsPerSenderPerDay` | `50` | Abuse brake |
| `dailyCostBudgetUsd` | unset | Per-integration ceiling, on top of `TenantQuota.maxMonthlyCostUsd`. When reached, new mail stays unprocessed until the next day and one notification goes to the integration's owners |

Save-time validation sits in `RestChannelIntegrationStore`, beside the webhook branch: transport is known,
secrets are vault references or connections, the connection allowlist covers the endpoints, the address is
unique, the host passes the socket policy, `observeMode` targets are refused, and `mode`-specific keys are refused
in the other mode (a reply setting on a trigger integration reads as if it did something).

**What the agent sees:** input from `inputTemplate`. Context `mail` holds `from`, `fromName`, `to`, `cc`,
`subject`, `date`, `messageId`, `authentication`, `signingDomain`, `quotedHistoryRemoved` and `attachments`
(metadata, with attachment ids when `store`). All of it is untrusted input.

---

## 11. Threading and conversation mapping

- **Thread key:** EDDI's own outbound Message-IDs are random (`<eddi.<uuid>@<domain of address>>`) and recorded in
  the ledger. An inbound `In-Reply-To` / `References` that names a recorded id maps to its conversation.
  Otherwise the first id in `References` is the thread root. Otherwise the message starts a new conversation.
  **No subject-line matching.**
- **Mapping:** `IUserConversationStore`, as in `WebhookEventHandler.correlatedConversation`. The intent is
  `email:<integrationId>:<agentId>:<sha256(threadRoot)>`, with the same create race handling. An ENDED
  conversation is replaced.
- **Hijack guard — explicit, not implied by the user id:** a continuation is accepted only if the authenticated
  sender equals the conversation's original sender. `threadParticipants: anyAuthenticatedParticipant` also
  admits authenticated addresses that EDDI's own outbound mail in that thread was sent to. Any other
  authenticated sender starts their own conversation. Message-IDs travel in every forwarded mail and are not
  secrets.
  **Limit:** "authenticated" means an aligned pass for the sender's *domain* (§8). Someone who can send
  DKIM-signed mail from the same domain and forges the `From` local part passes this guard, and reaches that
  person's per-sender memory (§9) as well. Email has no mailbox-level provenance that EDDI could check; the
  protection is the sending domain's own control over who may use which address. Where that is not enough, keep
  sensitive data out of per-sender long-term memory.
- **Trigger mode:** each mail is its own conversation by default. `correlateThreads: true` threads them (useful
  when a supplier sends a correction to an earlier invoice), with the same hijack guard.
- **Routing:** `targets` triggers match the plus-tag (`support+billing@` → `billing`) and words in the subject,
  falling back to `defaultTargetName`. A mapped thread stays with its agent. Group targets run a discussion. In
  conversation mode the synthesis is the reply.

---

## 12. The message ledger: dedup, outbox, threading index

One store, `IEmailMessageLedger` (Mongo and Postgres twins), one row per message per integration:

```text
{ integrationId, kind (NOTIFY|REPLY|NOTICE|INBOUND), messageId (RFC, or SHA-256 of raw bytes when absent),
  direction IN|OUT, idempotencyKey, conversationId, threadRoot, state, attempts, nextAttemptAt,
  claimToken, providerId, replayToken, createdAt, expiresAt }
```

- **Unique `(integrationId, direction, messageId)`** gives cluster-wide, restart-proof deduplication. Pollers see
  the same message again after a crash, and a provider retries. The webhook channel's 10-minute in-memory window
  is not enough here.
- **Unique `idempotencyKey`** — for outbound rows `(conversationId, stepId, kind)`, extended by whatever else can
  repeat a step id (the inbound row, a pause id). Rows are inserted with it before anything is sent, so a second
  completion observer's insert fails and that observer sends nothing; the send itself runs only under the row's
  claim (`claimToken` plus a lease in `nextAttemptAt`).
- **Inbound states:** `RECEIVED → CLAIMED → TURN_SUBMITTED → DONE | REFUSED(reason)`. The row is written, by a
  conditional insert, **before** the turn is submitted, and `CLAIMED → TURN_SUBMITTED` is written immediately
  before the first side effect. The mailbox is marked processed only once the row is past that point or refused.
  **A `RECEIVED` row, or a `CLAIMED` one whose claim has expired, is not a duplicate:** mail deferred by a budget
  or quota (§13), or claimed by a node that died, stays in that state and the next poll claims it again. Only
  rows past `CLAIMED` deduplicate. The result is at least once, made exactly once by the unique key.
- **Outbound states:** `PENDING → SENT | FAILED | SUPPRESSED`.
  The first send attempt happens inline in the turn's `onComplete`. Retries come from a Quarkus
  `@Scheduled(identity=…)` sweeper that claims rows by conditional update, with exponential backoff. After N
  attempts the row is `FAILED` and the integration's owners are notified.
- **Stranded turns:** the sweeper also finds `TURN_SUBMITTED` rows older than a threshold whose conversation step
  has completed, but which have no outbound row. That happens when the node died between turn and send. It
  renders the reply from the stored conversation snapshot. Without this, a crash silently drops a reply.
- **Replay tokens** for Mailgun-style webhooks are stored here, single-use, on the delivery's own row. A token is
  consumed only when that row is accepted (reaches `CLAIMED`) in the same conditional write; a delivery answered
  with a non-2xx status (limit hit, quota, node shutting down) leaves the token unconsumed, so the provider's retry
  of that same delivery is processed, while a replay of an accepted delivery is refused as a duplicate.
- **Body:** never in the ledger. Inbound content lives in conversation memory, and outbound MIME is rebuilt from
  the snapshot on retry. So personal data lives in one place.
- **Retention:** 30 days (`eddi.email.ledger-retention`). Dedup beyond that relies on the mailbox flag.

A separate `IEmailSuppressionStore` holds addresses that bounced hard or complained. EDDI never sends to them
until an admin clears the entry.

Size: **M**.

---

## 13. Loops, spam, cost abuse

`EmailLoopGuard` runs before any turn. Every drop is counted and none is answered:

- `Auto-Submitted` other than `no` (RFC 3834), `X-Autoreply`, `X-Autorespond`, `Precedence: bulk|junk|list`,
  `List-Id`, `List-Unsubscribe`;
- null or `MAILER-DAEMON`/`postmaster` return paths, and `multipart/report` (DSN). These are delivery feedback for
  the ledger, not input;
- mail from the integration's own address, or carrying a Message-ID from another EDDI integration's ledger (two
  agents mailing each other);
- per-thread and per-sender budgets. A thread that exceeds its budget is marked throttled and its owners are
  notified once.

Every EDDI mail carries `Auto-Submitted: auto-replied` (replies) or `auto-generated` (notifications), and
`X-Auto-Response-Suppress: All`.

**Spam and cost:** polling the folder that comes after the mail server's filtering is the first line. Then the
allowlists, `maxTurnsPerSenderPerDay`, `dailyCostBudgetUsd`, and the existing tenant quotas (a new conversation
counts against `maxConversationsPerDay`, a turn against `maxApiCallsPerMinute`, all of it against
`maxMonthlyCostUsd`). **When a limit is hit, no bounce goes to the sender**: a bounce reveals automation and
invites retries. Polled mail stays unprocessed and the next poll picks it up. Pushed mail gets a non-2xx answer
and the provider retries.

---

## 14. Untrusted content and prompt injection

An email-facing agent is the textbook indirect-injection target. In trigger mode, the agent's tools are the
blast radius.

1. **Hidden-content pass** in `HtmlToMarkdownConverter` removes `display:none`, `visibility:hidden`,
   zero-size/opacity, same-colour text, comments, `<style>` and off-screen positioning. It is tested against real
   injection samples and shared with RAG §3.6.
2. **`text/plain` preferred**, HTML only as a fallback.
3. **Quote stripping** of recognised blocks only: `>` lines, Outlook's `From: … Sent: …` header block, Gmail's
   `gmail_quote`, Apple's `blockquote type=cite`. Anything not recognised is kept. `quotedHistoryRemoved` tells
   the author it happened, and `stripQuotedHistory: false` turns it off for inline-reply correspondents.
4. **Framing:** the default `inputTemplate` and a shipped prompt snippet `email_untrusted_input` mark the mail as
   quoted third-party content.
5. **No recipient choice for the model**, in any mode. Trigger mode sends nothing. Conversation mode replies only
   to the thread's authenticated sender.
6. **Tool approvals:** for an agent targeted by an email channel, the save path **warns** when the agent has
   write tools without `toolApprovals`. It is a warning and not a refusal, because the engine is config-driven
   and the author decides. The Manager's email wizard pre-selects approvals.
7. **Guardrails:** email input goes through the input guardrails of
   [`guardrails-architecture.md`](guardrails-architecture.md) once they exist. Nothing email-specific is needed.
8. **Caps:** raw size before parsing, characters after conversion, number of attachments, per-attachment size,
   MIME allowlist, no archive expansion, no executables, converter time limits (the RAG converter limits).

---

## 15. Rendering replies

- **v1: `text/plain`, UTF-8, `format=flowed`.** Markdown is left mostly as it is (it reads fine as text), with
  links shown as `text <url>`.
- Threading headers: `In-Reply-To`, `References` (with the chain capped), and `Subject: Re: …` without stacking
  `Re:` prefixes.
- `replyTemplate`, then `aiDisclosure`, then the `Auto-Submitted` headers.
- Outbound DKIM signing is the mail server's job (Exchange Online and hosters sign submitted mail). EDDI does not
  sign.
- **Later (S):** `multipart/alternative` with HTML rendered from Markdown (commonmark, a new dependency) and
  cleaned with jsoup's `Safelist`: no remote images, no forms.
- **Not planned:** parsing quick-reply choices out of a reply.

---

## 16. HITL

**Notifications (Phase 1 — no email channel needed):**

- **Deployment mailer:** `eddi.mail.smtp.*` (host, port, TLS, connection name or vault-referenced credentials,
  `from`). It is operator configuration, the one SMTP sender for system mail, and it reuses the §7.1 SMTP client.
  Nothing is configured by default, and then nothing is sent.
- **Agent opt-in:** `hitlConfig.notify.email` with `recipients` (addresses) and/or `notifyApprovers: true`
  (EDDI users who may approve this agent, with an `email_verified` address in the user directory). Agent-level,
  because the agent decides who cares about its pauses, whichever channel the conversation came from.
- **Content, kept minimal:** agent name, pause type, reason, deadline, and a link to `/manage/approvals` (the
  existing page, which needs an OIDC login). A TOOL_CALL pause includes the tool name and redacted, truncated
  arguments, as the Slack card does. No conversation content.
- **Who may decide** stays with EDDI's existing authorization on the approvals page. The email confers nothing:
  no tokens, no approve-on-GET (link scanners such as Safe Links or Proofpoint open every link), no
  reply-to-approve (a forwarded mail would carry the decision).
- Sent through the ledger (kind `NOTIFY`) for retries. One notification per pause.

**In email conversations (Phase 3):** on a pause the sender gets the agent's pause notice in the thread.
`EmailHitlResumeObserver` listens for `HitlResumeCompletedEvent`, as `SlackHitlResumeObserver` does, and queues
the continuation reply. A human taking over ([`human-takeover-design.md`](human-takeover-design.md)) answers from
the Manager, and that answer goes out through the same ledger from the integration's address.

---

## 17. Personal data, retention, audit

- **No raw MIME stored.** The parsed text enters conversation memory like any user input. Attachments are stored
  only with `attachments: store`, under the attachment store's retention.
- **Erasure and export:** an `EmailGdprParticipant`. For a subject address it covers the conversations and
  memories of `email:*:<address>`, thread mappings, ledger rows, and the suppression entry (§23 Q4).
- **Audit ledger:** every outbound mail (recipient, kind, conversation, provider id — no body), refused inbound
  mail by reason category, and integration create/update.
- **Logs** carry integration id, conversation id, ledger id and an address hash — never bodies, subjects or plain
  addresses.
- **Data flow:** the mailbox host / relay is a processor. Add it to
  [`docs/compliance-data-flow.md`](../docs/compliance-data-flow.md).

---

## 18. Phases

| Phase | Content | Size | Depends on |
| --- | --- | --- | --- |
| **0 — Mail core** | `engine/mail/`: Angus Mail parsing, address normalisation, quote stripper, auto-reply/DSN detector, hidden-content pass, `InboundMail`/`OutboundMail`, text renderer. Fixture corpus from real clients. Shared with RAG §8 | **M** | — |
| **0b — Sockets & tokens** | `SafeSocketConnector`, `ConnectionResolver.resolveAccessToken` (bare OAuth token for XOAUTH2), `eddi.email.allowed-hosts`. Shared with RAG §3.10 | **S** | — |
| **1 — Notifications** | Deployment mailer (SMTP via 0b), ledger (outbound part) + sweeper, `hitlConfig.notify.email`, notifying approvers through the user directory, Manager fields, docs, operator prompt / allow-list + revision bump | **M** | 0, 0b |
| **2 — Trigger mode (IMAP)** | `channelType: "email"` registered, `EmailChannelSettings` + save validation, IMAP transport + schedule-tagged poller, DKIM/DMARC verifier (§8), identity, loop guard, ledger (inbound + stranded-turn recovery), attachments → `IAttachmentStore`, cost/sender budgets, metrics, GDPR participant, Manager Channels form, MCP `create_channel_integration`, `docs/email-integration.md`. Plus the **helpdesk recipe** (webhook channel + `httpcalls`, §3) as docs and an example config | **L** | 0, 0b |
| **3 — Conversation mode** | SMTP replies through the ledger, threading + hijack guard, `replyTemplate` / `aiDisclosure`, reply-recipient rules, per-thread reply budget, bounce handling → suppression, HITL in-thread notice + continuation, takeover replies, group targets | **L** | 2 |
| **4 — Graph transport** | Delta polling, `/$value`, `/reply`, RBAC-for-Applications setup guide. Build together with the RAG M365 transport, and only if customers refuse IMAP | **M** | 2 |
| **5 — Relay webhooks** | Push endpoint `POST /integrations/email/{id}` (permit path, size cap, signature first), Mailgun (single-use token) and SES (SNS signature, pinned cert URL, S3 for large mail) | **M** | 2 |
| **6 — Gmail** | XOAUTH2 IMAP or Gmail API, on the RAG plan's run-as-principal and shared-mailbox safeguards | **M** | 2 + RAG run-as-principal |
| **7 — Extras, on demand** | HTML replies (S) · IMAP IDLE (S) · ARC (M) · an allowlisted, approval-gated `sendEmail` tool (M) | — | 3 |

Phases 0–2 are the first useful release. Phase 1 can ship before Phase 2 on its own.

---

## 19. Files that would change

**New** (under `src/main/java/ai/labs/eddi/`):

- `engine/mail/` — `MimeMessageParser`, `AddressNormalizer`, `QuoteStripper`, `AutoReplyDetector`,
  `DkimVerifier`, `DmarcAlignment`, `MailDnsResolver`, `PublicSuffixList`, `PlainTextMailRenderer`,
  `InboundMail`, `OutboundMail`, `SystemMailer` (deployment SMTP)
- `engine/net/SafeSocketConnector` (or wherever the RAG plan puts it — one class)
- `configs/channels/model/EmailChannelSettings`
- `integrations/email/` — `EmailEventHandler`, `EmailLoopGuard`, `EmailThreadResolver`, `EmailUserIdentity`,
  `EmailReplyComposer`, `EmailHitlResumeObserver`, `EmailGdprParticipant`, `EmailPollSchedules` (marker, like
  `RagIngestionSchedules`)
- `integrations/email/transport/` — `MailTransport`, `MailTransportRegistry`, `ImapSmtpTransport`, later
  `GraphMailTransport`, `MailgunTransport`, `SesTransport`, `GmailTransport`, `rest/RestEmailInbound` (Phase 5)
- `integrations/email/ledger/` — `IEmailMessageLedger`, `MongoEmailMessageLedger`, `PostgresEmailMessageLedger`,
  `EmailLedgerSweeper`, `IEmailSuppressionStore` + twins

**Changed:** `RestChannelIntegrationStore` (register `email`, validation branch), `ChannelIntegrationConfiguration`
(Javadoc), `ChannelTargetRouter` (key `email:<address>`), `HtmlToMarkdownConverter` (hidden-content pass),
`ConnectionResolver` (`resolveAccessToken`), `ScheduleFireExecutor` (dispatch the poll marker), the agent HITL
config model (`notify.email`), `McpAdminTools`, `application.properties` (`eddi.mail.*`, `eddi.email.*`),
`pom.xml` (Angus Mail, DNS library, public-suffix data; GreenMail for tests).
**Manager** (`ui/manager/`): Channels form for `email`, HITL notify fields, operator `system-prompt.ts`,
`tool-scopes.ts`, `operator-revision.json`. **Docs:** `docs/email-integration.md` (new), `docs/SUMMARY.md`,
`docs/hitl.md`, `docs/security.md`, `docs/metrics.md`, `docs/compliance-data-flow.md`,
`docs/configuration-reference.md`, a changelog fragment. **Claude plugin skills** if they teach channel creation.

---

## 20. Metrics

Tagged by `integration`, `transport` and `mode`:

- `eddi.email.inbound{outcome=processed|duplicate|unauthenticated|blocked|loop|throttled|budget|too_large|parse_error}`
- `eddi.email.auth{result=aligned_pass|pass_unaligned|fail|none}` — shows whether a mailbox's correspondents can
  be authenticated at all
- `eddi.email.outbound{kind=reply|notify, outcome=sent|retry|failed|suppressed}`
- `eddi.email.outbound.latency`, `eddi.email.ledger.pending` (gauge), `eddi.email.ledger.stranded_recovered`
- `eddi.email.poll.duration`, `eddi.email.poll.cursor_resets`
- `eddi.email.dns.lookups{result}`

`MetricsDashboardCoverageTest` will require the dashboard row and the `docs/metrics.md` entries.

---

## 21. Tests and verification

- **DKIM/DMARC:** RFC 6376 / 8463 test vectors; real signed mail from major senders (Gmail, Outlook.com, Exchange
  Online, a mailing list) stored as fixtures with their DNS records, so tests run offline; header-injection cases
  (a second `From`, `l=` body extension, a signature over a header that is absent, a duplicated signed header);
  subdomain alignment against the public-suffix list. **Mutation-check every verdict path**: revert the check,
  and the test must fail.
- **Mail core:** quote stripping and parsing against a corpus of real client replies (Outlook desktop/web,
  Gmail, Apple Mail, Thunderbird, mobile clients), OOO replies, DSNs, broken charsets, oversized headers, MIME
  bombs, injection samples for the hidden-content pass.
- **Guards:** every loop header one at a time; two integrations mailing each other; hijack with a known
  Message-ID under both identity modes; unauthenticated mail never producing a reply in either mode.
- **Ledger:** concurrent inserts of the same message on two nodes; crash between turn and send (stranded
  recovery); retries with backoff; suppression after a hard bounce; no body persisted.
- **Persistence boundary:** ledger, mappings and suppression round-tripped through **both** the Mongo and the
  Postgres store (ITs).
- **Live:** GreenMail Testcontainer for IMAP + SMTP end-to-end ITs. Before calling Phase 2 or 3 done, run against
  a real Exchange Online test mailbox (OAuth client credentials, per-mailbox permission — and a 403 on a mailbox
  that was not granted) from a Docker-run EDDI.

---

## 22. What I would NOT do

- **No LLM-chosen recipients** (a generic `sendEmail` tool), unless as the allowlisted, approval-gated Phase 7
  item.
- **No reply to an unauthenticated sender**, ever.
- **No trust in unsigned provider verdicts** or in `Authentication-Results` headers. EDDI verifies DKIM itself.
- **No `VERIFIED` provenance for email identities**, and no advice to set `allowUnverifiedPrincipal` for them.
- **No email approval tokens, approve-on-GET or reply-to-approve.** The link goes to the Manager.
- **No MTA/MX in EDDI**, no Gmail domain-wide delegation, no deleting mail from the mailbox.
- **No subject-line threading.**
- **No custom scheduler.** Polling uses the schedule machinery, sweeps use `@Scheduled(identity=…)`.
- **No raw MIME or bodies in the ledger.**
- **No second MIME, HTML or socket stack** next to the RAG mail source's.
- **Not the email channel where a helpdesk owns the mailbox.** Document the webhook recipe instead.

---

## 23. Open questions

1. **Is IMAP acceptable to the first customers?** Some security teams disable IMAP tenant-wide on Exchange
   Online. If so, Graph (Phase 4) moves ahead of Phase 2's transport. Everything else in Phase 2 is
   transport-independent.
2. **Default `identityMode`:** `perSender` gives memory per correspondent, which is only as strong as the sender
   domain. Is `integration` (no per-person memory) the safer default for trigger mode?
3. **Senders who don't sign with DKIM:** small suppliers often don't. In trigger mode their mail can still be
   processed with `requireAuthentication: false`, but without memory. Is that enough, or do we need the signed
   SPF verdicts of relay providers sooner?
4. **Suppression list vs erasure:** keep a hashed address after erasure so that "never mail me again" is honoured
   (legitimate interest), or delete it?
5. **Who builds the mail core** — this plan or RAG §8, whichever starts first. Agree on the package
   (`engine/mail/`) and the fixture corpus up front.
6. **Generic channel SPI:** with Slack, webhook and email, `multi-agent-ux-improvements.md` §2.2's
   `IChannelAdapter` is worth extracting. Do it in Phase 2, or after the Teams adapter shows what the abstraction
   really needs?
