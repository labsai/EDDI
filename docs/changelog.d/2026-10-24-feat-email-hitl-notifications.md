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
