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
