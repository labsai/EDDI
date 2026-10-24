## ✨ feat(mail): email notifications for pending approvals — email Phase 1 (2026-10-24)

**Repo:** EDDI (`feat/email-hitl-notifications`, on top of `feat/email-mail-core`)

Phase 1 of [the email plan](../../planning/email-channel-plan.md) (§16 "Notifications", the outbound half of §12).

### Socket guard: an operator policy

- **`SafeSocketConnector.resolveOperator`** (`Policy.OPERATOR`) for a host the operator set in
  `application.properties` — today only the system mailer's relay. Private and loopback addresses, IP literals,
  single-label names and any port (25 included) are allowed, and `eddi.email.allowed-hosts` (which confines
  authors) is not consulted. Still resolved once and pinned, still no link-local or cloud-metadata address, TLS
  still verified. `TlsMode.PLAINTEXT` exists only on an operator endpoint: the `Endpoint` constructor refuses it
  for an author one. `resolve` (the author policy) is unchanged.

### Connections: mail origins on `baseUrlAllowlist`

- A connection's `baseUrlAllowlist` now accepts `smtp://`, `smtps://`, `imap://` and `imaps://` origins
  (`ConnectionConfiguration.requireCanonicalAllowlistOrigin`), so `resolveAccessToken` (XOAUTH2) and the new
  `resolveBasicLogin` (username + vault-backed password for SMTP/IMAP login) can check a mail server against it.
  The ports a mail scheme implies fold away (`smtps` 465, `smtp` 587, `imaps` 993, `imap` 143); `smtp://relay:25`
  stays a distinct origin. An https entry for the same host does not cover a mail origin. The credential-endpoint
  allowlist and the public base URL stay http(s) only. The Manager's `connection-validation.ts` mirror accepts the
  same schemes, with the `originScheme` message updated in all 11 locales.

### The system mailer

- **`SystemMailer`** (`engine/mail`): the deployment's one SMTP sender, configured by the operator under
  `eddi.mail.smtp.*` (host, port, `tls` = `starttls` | `implicit` | `none`, `from`, `from-name`, and at most one
  credential source: `connection` — `BASIC` or `OAUTH2_CLIENT_CREDENTIALS`/XOAUTH2 — or `username` + `password`).
  Unset host = disabled, one INFO line at startup; an unusable configuration disables it with one ERROR line and
  never falls back to something weaker. Sockets come from `SafeSocketConnector.resolveOperator`. STARTTLS is
  required, not opportunistic; `tls=none` needs `eddi.mail.smtp.allow-plaintext=true` and never carries a
  credential. Failures are classified (`MailSendException.Category`), permanent only for a 5xx refusal.
- **GreenMail 2.1.14** (Apache-2.0, test scope; its aggregate `org.eclipse.angus:jakarta.mail` and JUnit 4 are
  excluded) runs implicit TLS, plain SMTP and AUTH in `SystemMailerTest`; a small in-test STARTTLS server covers the
  upgrade GreenMail does not speak, and proves AUTH (PLAIN and XOAUTH2) only ever crosses the wire after it. The
  certificate is a throwaway made with the JDK's `keytool`; the production TLS path, with certificate and host-name
  checks, is what accepts it.

### The message ledger (outbound)

- **`IEmailMessageLedger`** (`integrations/email/ledger`) with `MongoEmailMessageLedger` (`email_message_ledger`
  collection) and `PostgresEmailMessageLedger` (`email_message_ledger` table, created on first use), selected by
  `eddi.datastore.type` in `DataStoreProducers`. One row per message: integration (`system` for system mail),
  kind (`NOTIFY`; `REPLY` later), direction, RFC Message-ID, conversation, `sourceRef` (what the message is rebuilt
  from — the pause id for a notification), idempotency key, recipient, state `PENDING → SENT | FAILED | SUPPRESSED`,
  attempts, `nextAttemptAt`, claim token, provider response, last error, created/updated/expires. **No body,
  subject or MIME** — a retry re-renders from the source. Two unique keys (idempotency key;
  `(integrationId, direction, messageId)`) make an insert a cluster-wide claim. `claimDue` leases rows by a
  conditional update (`findOneAndUpdate` per row on Mongo; `UPDATE … FOR UPDATE SKIP LOCKED … RETURNING` on
  Postgres), and every state change is conditional on the claim token, so a taken-over claim cannot overwrite the
  new owner's result.
- `MongoEmailMessageLedgerIT` / `PostgresEmailMessageLedgerIT` run one shared contract against real databases
  (Testcontainers): duplicate keys, eight concurrent inserts of one notification (one winner), two sweepers claiming
  20 rows at once (no overlap), lease expiry and stale claims, reschedule, retention, erasure by recipient, and the
  stored field set (no body field exists).

### HITL email notifications

- **Agent opt-in** `hitlConfig.notify.email.recipients` (`HitlNotifyConfig`): 1–20 plain ASCII addresses, validated
  at save and import time (`HitlConfigValidation.validateNotify` — empty, over 20, malformed, display-name,
  non-ASCII and duplicate-after-normalisation entries refused with the position, never the address). Configs without
  the field are unchanged.
- **The hook:** `HitlPauseCommittedEvent(conversationId, pauseId)`, fired asynchronously by
  `ConversationService.firePauseCommitted` at the three places a regular pause is committed — the say turn
  (`ConversationStepRunner`, after the state CAS and the timeout schedule), the `CONVERSATION_START` turn, and a
  resume that pauses again (`ConversationHitlService`). Not for a pause restored after a failed resume, nor on a CAS
  miss. Group discussions are not covered yet.
- **`HitlEmailNotifier`** (`integrations/email/notify`): re-reads the pause from the store (state and `pauseId` must
  still match), inserts one ledger row per recipient under a digest key of kind/conversation/pause/recipient, and
  sends at once for the rows it won. A `@Scheduled(identity = "email-ledger-sweeper")` sweep retries due rows with
  exponential backoff (`eddi.email.outbound.retry-backoff`, capped at 1 h) up to `eddi.email.outbound.max-attempts`,
  removes expired rows (`eddi.email.ledger-retention`) and refreshes the `eddi.email.ledger.pending` gauge. A
  decided pause ends `SUPPRESSED`; a 5xx fails at once.
- **Content** (`HitlNotificationContent`): agent name, pause type, reason, start, deadline + policy, conversation id,
  and for a tool-call pause the same redacted, 300-char-truncated argument lines as the Slack card — extracted from
  `SlackHitlSupport` into the shared `HitlToolCallPreview`. No conversation content. Subject
  `[EDDI] Approval needed: <agent name>`, designer text flattened to one line. The link is
  `eddi.connections.public-base-url` + `/manage/approvals`, no token; without a base URL the page is named, not
  linked.
- **Metrics** `eddi.email.outbound{kind,outcome}` and `eddi.email.ledger.pending`, on the Full Metrics dashboard's
  Human-in-the-Loop row and in `metrics.md`. **Audit** entry `email.notify` per sent/failed notification with the
  recipient as a SHA-256 hash plus domain (the audit ledger is write-once). **GDPR**: `EmailLedgerGdprParticipant`
  (`emailLedger`) erases/exports rows by the user id's address and the directory's recorded email;
  `IGdprParticipant.order()` makes the user directory run last so the address is still readable.

### Recipient domain policy and flood brake (review follow-up)

- **`eddi.mail.notify.allowed-recipient-domains`** (`NotifyRecipientPolicy`, `engine/mail`): exact domains, `*.domain`
  for subdomains, `*` for any; **unset = the domain of `eddi.mail.smtp.from` and its subdomains**. Recipients are set by
  anyone with EDIT on an agent while the mail leaves through the operator's relay under the operator's sender, so
  without a bound the relay was an open mailer for agent authors. Enforced at send time on every attempt (the row ends
  `SUPPRESSED`, `RECIPIENT_NOT_ALLOWED`, `outcome=not_allowed`) and at save time in `HitlConfigValidation` — refused
  when a mailer is configured, accepted with a WARN when not (read through `ConfigProvider`, since the validation is
  static). A malformed entry fails closed: nobody is mailed, ERROR at startup.
- **`eddi.mail.notify.max-per-recipient-per-hour`** (default 30): `IEmailMessageLedger.countCreatedSince(recipient, kind,
  since)` counts the recipient's non-suppressed `NOTIFY` rows of the last hour across the cluster (new index
  `(recipient, kind, createdAt)` on both stores); over the limit the row is `SUPPRESSED` (`RATE_LIMITED`,
  `outcome=rate_limited`) and each instance logs one WARN per recipient per hour, naming it by hash. Suppressed rows do
  not count, so the window empties once the flood stops. Count and insert are two steps, so concurrent pods can
  overshoot by a few.
- Docs (`hitl.md`, `configuration-reference.md`, `metrics.md`), the Manager hint and the operator prompt say so;
  operator revision stays 37 (one PR) with the new fingerprint.

### Manager and the Platform Operator

- The agent's **Human-in-the-Loop** settings gain "Email people when this agent waits for approval" with a
  recipients field (`agent-config-sections.tsx`, parser in `lib/hitl-notify.ts` mirroring the save-time rules: 1–20,
  plain ASCII, no duplicates). As with the approval timeout, an unsavable list never enters the draft; the field shows
  what is wrong. Eight new `agentDetail.hitlNotify*` keys in all 11 locales.
- **Operator prompt** (`system-prompt.ts`): how email notifications are set up — the operator's
  `eddi.mail.smtp.*`, the agent's `hitlConfig.notify.email.recipients` in the Manager (the operator cannot write
  `hitlConfig`), what the mail contains, why there is no "all approvers" switch. No new endpoint, so `tool-scopes.ts`
  is unchanged. **Operator revision 36 → 37.**
- Docs: [`hitl.md` → Email notifications](../hitl.md#email-notifications) (with a sample config),
  [`configuration-reference.md` → Email](../configuration-reference.md#email), [`metrics.md`](../metrics.md#hitl-metrics),
  [`security.md` → the operator policy](../security.md#the-operator-policy--the-system-mailers-relay),
  [`connections.md`](../connections.md), [`gdpr-compliance.md`](../gdpr-compliance.md),
  [`compliance-data-flow.md`](../compliance-data-flow.md).

### For Phase 2 and 3

- The ledger API is the one they extend: add inbound states and a stranded-turn query to `IEmailMessageLedger`,
  `Kind.REPLY` rows get their own worker (`claimDue` takes a kind), `sourceRef` carries the conversation step a
  reply is rebuilt from. The unique `(integrationId, direction, messageId)` key is already the inbound dedup key.
- The SMTP client is `SystemMailer` for operator relays only; a channel's author-configured SMTP must use
  `SafeSocketConnector.resolve` (the author policy), not `resolveOperator`.
- Group discussions (`AWAITING_APPROVAL`) are not notified yet; `GroupHitlCoordinator` commits group pauses in three
  places and would need its own event.

```decision-log
| 2026-10-24 | HITL email notifications go to configured recipients only, not to "every approver" | EDDI's approvers are role holders in the identity provider, which EDDI cannot enumerate; a list built from past sign-ins would keep mailing a revoked approver indefinitely | Recording roles in the user directory at sign-in (stale and unrevokable); mailing the conversation owner (usually the end user, not an approver) |
| 2026-10-24 | The audit entry of a notification records the recipient as SHA-256 + domain, not the address | The audit ledger is write-once and erasure only pseudonymises its userId, so a plain address would outlive an erasure; the hash still answers "was this address mailed?" for someone who has it | Plain address (unerasable personal data); no recipient at all (unauditable) |
| 2026-10-25 | HITL notification recipients are bounded by an operator domain allowlist, defaulting to the sender's own domain | Agent authors choose recipients but the mail goes out under the operator's sender and relay, with author- and user-influenced text; an unbounded list makes the relay an open mailer | Unbounded recipients (open mailer); a per-agent allowlist (set by the same author it is meant to bound) |
| 2026-10-24 | Operator-configured relays get their own socket policy instead of a bypass | An on-premises relay on a private address or port 25 is a normal deployment, but the pinning, metadata refusal and TLS verification are still worth having; a bypass would lose them | Allowing private addresses through eddi.email.allowed-hosts (would open them to channel authors too); opening a raw socket outside SafeSocketConnector |
```
