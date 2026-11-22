## ✉️ feat(email): S/MIME signing, the sending-health self-test, and `emailControl` storage — v3 phase C2 (2026-11-21)

**Repo:** EDDI · **Branch:** `feat/email-smime`

Phase C2 of the email channel v3 control plan (`planning/email-channel-v3-control-plan.md`, §4, §6), plus the
§4 storage every later phase builds on.

### What changed and why

- **`emailControl` (§4).** A typed object on `ChannelIntegrationConfiguration`, next to `platformConfig`, for email
  settings a flat string map cannot hold (`configs/channels/model/EmailControl.java`). Persisted with the document in
  both datastores (it is a plain field of the JSON the resource store writes), strict on REST/MCP writes, lenient on
  read, kept by the non-admin redacted read. Refused (400) on any non-email integration. Validated in
  `EmailChannelSettings.validate(ChannelIntegrationConfiguration)`; every production reader now uses
  `EmailChannelSettings.from(configuration)` so a typed setting is never read as its default on one path.
- **SMTP keys are common.** `smtpHost`, `smtpPort` and `fromName` moved from the conversation-only key set to the
  common one: trigger mode may name a relay for the self-test. Trigger mode still never replies
  (`ImapMailTransport.sendMail` refuses it; `EmailTriggerModeSendsNothingTest` now also sees `sendMail`/`sendSelfTest`).
- **S/MIME signing (§6.2–§6.4).** `emailControl.smime.sign` (`true` default, `"required"`, `false`) and
  `emailControl.smime.connection`. New connection auth type `SMIME_SIGNING` (`smimeSigning.certificate` and optional
  `password`, both `${vault:…}`; binding `SERVICE`; allowlist = SMTP origins), resolved only by
  `ConnectionResolver.resolveSmimeSigning` — every header/login path refuses it. `engine/mail/SmimeIdentity` (PKCS#12
  or PEM), `SmimeCertificateCheck` (SAN/subject e-mail = address, key usage, EKU, validity, chain to the JDK roots or
  shipped complete), `SmimeSigner` (clear-signed `multipart/signed`, SHA-256, RSA PKCS#1 v1.5 or ECDSA, on the Angus
  `MimeMessage`; plus verification). `EmailSmimeSigning` applies the policy right before SMTP submission in
  `ImapMailTransport`. Under `required` an unsignable reply is **held** (`IEmailMessageLedger.hold`: rescheduled hourly
  without spending an attempt, reason `smime_unavailable`, owner notice `SMIME_UNAVAILABLE`); saving with an unusable
  certificate is refused. `EmailSmimeCertificateMonitor` checks daily: gauge
  `eddi_email_smime_certificate_days_left`, owner notice `CERTIFICATE_EXPIRING` within 30 days. OUT ledger rows store
  `signed` (Postgres `ADD COLUMN IF NOT EXISTS signed BOOLEAN`, Mongo field `signed`). Every
  `eddi_email_outbound_total` series now carries `signed` (Prometheus needs one tag set per name). Save warnings
  `email.smime-no-certificate`, `email.smime-certificate-unusable`, `email.smime-certificate-expiring`,
  `email.smime-untrusted-chain`.
- **Self-test (§6.5).** `POST`/`GET /channelstore/channels/{id}/email-self-test[/{token}]` (EDIT; 5 per hour, 429) and
  MCP `run_email_self_test` (121 tools now). The test mail is a new ledger kind `SELF_TEST` (token SHA-256 in
  `sourceRef`, result encoded in `lastError` when it comes back); the inbound processor recognises it after sender
  authentication and before the loop guard's own-address refusal, settles it `self_test` and never starts a turn. The
  `SENT → DONE` transition makes the token single-use; a replay meets `loop:own_address`. Findings in plain words,
  Microsoft 365 unsigned = inconclusive.
- **pom.** `bouncycastle.version` (2.73.13) shared by the `bcprov-lts8on` override and the new `bcpkix-lts8on`,
  `bcutil-lts8on`, `bcjmail-lts8on`.
- **Manager.** S/MIME section and SMTP fields in trigger mode in the email settings, the `SMIME_SIGNING` connection form
  (app-kit validation and labels), a self-test panel on the channel page; all 11 locales.
- **Operator.** Prompt describes `emailControl`, `SMIME_SIGNING` and the self-test (no new allow-list entries: the
  operator still has no channel tools). Revision 81 → 82.
- **Docs.** `email-integration.md` (S/MIME signing, self-test, common SMTP keys, `emailControl`), `connections.md`
  (`SMIME_SIGNING`), `metrics.md`, `configuration-reference.md` (`eddi.email.smime.check-interval`), the Full Metrics
  dashboard (days-left query on the email suppressions panel), `mcp-server.md`, the `eddi-admin` skill.

### Design decisions

- **`SMIME_SIGNING` keeps a `baseUrlAllowlist`** (SMTP origins only) instead of being exempt: the resolver's allowlist
  check and the vault secrets' `allowedHosts` then apply unchanged, with the relay as the destination. Agent grants do
  not apply — no agent resolves it.
- **Held, not failed.** A held reply waits for a certificate fix rather than burning its attempts; it ends only when the
  ledger retention removes it.
- **Self-test state in the ledger**, not a new store: cluster-wide, both datastores, and the rate limit is a ledger
  count. Its inbound row is `REFUSED` with reason `self_test` (not `DONE`, which counts as a turn).
- **ZIP export/import:** channel integrations are not part of a ZIP export (only agent resources are), so
  `emailControl` travels exactly as the integration does — nothing to add.

### Unverified / next

- How Gmail, Outlook, Apple Mail and Thunderbird display the signature (plan §14 item 7), RSA-PSS support; Bouncy
  Castle's mailcap handlers under Quarkus classloading; a live run against the test mailbox.
- Next: C3 (held mail review) builds on `emailControl`.
