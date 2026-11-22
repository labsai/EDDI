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
  or PEM), `SmimeCertificateCheck` (SAN/subject e-mail = address, key usage, EKU, validity, chain to the JDK roots,
  shipped complete, or to an absent root through an intermediate), `SmimeSigner` (clear-signed `multipart/signed`, SHA-256, RSA PKCS#1 v1.5 or ECDSA, on the Angus
  `MimeMessage`; plus verification). `EmailSmimeSigning` applies the policy right before SMTP submission in
  `ImapMailTransport`. Under `required` an unsignable reply is **held** (`IEmailMessageLedger.hold`: rescheduled hourly
  without spending an attempt, reason `smime_unavailable`, owner notice `SMIME_UNAVAILABLE`); saving with an unusable
  certificate is refused. `EmailSmimeCertificateMonitor` checks daily: gauge
  `eddi_email_smime_certificate_days_left`, owner notice `CERTIFICATE_EXPIRING` within 30 days. OUT ledger rows store
  `signed` (Postgres `ADD COLUMN IF NOT EXISTS signed BOOLEAN`, Mongo field `signed`). Every
  `eddi_email_outbound_total` series now carries `signed` (Prometheus needs one tag set per name). Save warnings
  `email.smime-no-certificate`, `email.smime-certificate-unusable`, `email.smime-certificate-expiring`,
  `email.smime-untrusted-chain`.
- **Self-test (§6.5).** `POST`/`GET /channelstore/channels/{id}/email-self-test[/{token}]` (EDIT; 5 per clock hour, 429) and
  MCP `run_email_self_test` (121 tools now). The test mail is a new ledger kind `SELF_TEST` (token SHA-256 in
  `sourceRef`, result encoded in `lastError` when it comes back); the inbound processor recognises it after sender
  authentication and before the loop guard's own-address refusal, settles it `self_test` and never starts a turn. The
  `SENT → DONE` transition makes the token single-use; a replay meets `loop:own_address`. Findings in plain words,
  Microsoft 365 unsigned = inconclusive.
- **pom.** `bouncycastle.version` (2.73.13) shared by the `bcprov-lts8on` override and the new `bcpkix-lts8on`,
  `bcutil-lts8on`, `bcjmail-lts8on`.
- **Manager** (the stacked UI PR, not this one). S/MIME section and SMTP fields in trigger mode in the email settings, the `SMIME_SIGNING` connection form
  (app-kit validation and labels), a self-test panel on the channel page; all 11 locales.
- **Operator** (the stacked UI PR, not this one). The prompt describes `emailControl`, `SMIME_SIGNING` and the
  self-test; no new allow-list entries (the operator still has no channel tools).
- **Docs.** `email-integration.md` (S/MIME signing, self-test, common SMTP keys, `emailControl`), `connections.md`
  (`SMIME_SIGNING`), `metrics.md`, `configuration-reference.md` (`eddi.email.smime.check-interval`, `eddi.email.smime.max-hold`), the Full Metrics
  dashboard (days-left query on the email suppressions panel), `mcp-server.md`, the `eddi-admin` skill.

### Design decisions

- **`SMIME_SIGNING` keeps a `baseUrlAllowlist`** (SMTP origins only) instead of being exempt: the resolver's allowlist
  check and the vault secrets' `allowedHosts` then apply unchanged, with the relay as the destination. Agent grants do
  not apply — no agent resolves it.
- **Held, not failed — but not for ever.** A held reply waits for a certificate fix rather than burning its attempts,
  for at most `eddi.email.smime.max-hold` (72h); then it is `FAILED` (`smime_unavailable:expired`), audited, and the
  owners get `smime_hold_expired`, instead of the ledger retention deleting it silently.
- **Self-test state in the ledger**, not a new store: cluster-wide, both datastores, and the rate limit is a claim of
  one of the hour's slot rows (unique idempotency key) — atomic, without a new ledger method. Its inbound row is `REFUSED` with reason `self_test` (not `DONE`, which counts as a turn).
- **ZIP export/import:** channel integrations are not part of a ZIP export (only agent resources are), so
  `emailControl` travels exactly as the integration does — nothing to add.

### Review fixes (2026-11-22)

An independent review of the branch; each finding verified in the code first.

- **M1 — a `required` hold had no end.** `EmailReplySender` gives a held reply up after `eddi.email.smime.max-hold`
  (default `72h`, clamped below the ledger retention): `FAILED` with `smime_unavailable:expired`, an audit entry, the new
  owner notice `SMIME_HOLD_EXPIRED` (`smime_hold_expired`). The signature is still tried first on every attempt, and the
  last hold is shortened to end at the deadline. `attempt` now takes the sweep's (or the enqueue's) `now` instead of
  reading the clock.
- **M2 — trailing spaces in a `7bit` signed part.** `SmimeSigner` re-encodes a `7bit`/`8bit`/`binary` text part as
  quoted-printable before signing, so `format=flowed`'s soft-break spaces travel as `=20` and a whitespace-stripping
  relay no longer breaks the signature (RFC 3676 §4.2; RFC 8551 §3.1.4's example; RFC 1847 forbids re-encoding after
  signing). Test: the signed message has no raw trailing space, and still verifies after every trailing space is
  stripped.
- **M3 — the chain rule refused the usual `.p12`.** `SmimeCertificateCheck.Chain.UNTRUSTED_ROOT`: leaf → intermediate(s)
  → a certificate whose issuer is not in the bundle is usable, with the existing `email.smime-untrusted-chain` warning
  (now raised for `COMPLETE` and `UNTRUSTED_ROOT`). Still refused: a link whose shipped issuer's key does not verify it,
  a lone leaf whose issuer is absent, and an expired, mismatched or wrong-usage leaf whatever its chain. Docs say what
  EDDI checks and that trust is the recipient's decision.
- **L1.** `MailTransport.sendMail`'s default (a transport that cannot sign) throws a non-permanent `SIGNING` failure
  under `sign: required` instead of sending unsigned.
- **L2.** Key usage `nonRepudiation` alone is accepted, as RFC 8550 §4.4.2 allows.
- **L3.** The self-test limit is per integration id and atomic: a start claims one of the hour's five slot rows
  (`self-test-slot:<integrationId>:<hour>:<n>`, terminal, no recipient, removed an hour after the window) through
  `insertIfAbsent`. A fixed UTC-hour window.
- **L4.** A self-test result is recorded only for the token in the `X-EDDI-Self-Test` header (the body no longer carries
  it) and only when the mail's Message-ID equals the test row's.
- **L5.** The self-test folds in the chain the signature carries: `verified` (public root), `verified_untrusted_root`
  (finding `smime.untrusted-root`, warning) or `chain_broken` (`smime.chain-broken`, problem).
  `SmimeSigner.VerificationResult` now returns the carried certificates.
- **L6.** An unknown `emailControl.smime.sign` value reads as `true` with a WARN on the persistence mappers, and stays a
  400 on REST/MCP writes (a `SignMode` deserializer keyed on `FAIL_ON_UNKNOWN_PROPERTIES`, which only the strict
  mapper enables).
- **L7.** `ChannelTargetRouter.deepCopyConfig` copies `emailControl` (deeply, `EmailControl.copy()`).
- **L8.** `ImapMailTransportSigningTest` asserts the `SIGNING` category for the reply and the self-test.
- **No `SMIMECapabilities`.** The signature advertised AES-256/128-CBC, inviting replies encrypted to EDDI's
  certificate, which EDDI cannot decrypt. The attribute is gone (test: OID 1.2.840.113549.1.9.15 absent); the docs
  recommend a signing-only certificate.
- **Bouncy Castle tree.** `dependency:tree -Dincludes=org.bouncycastle`: only `*-lts8on` 2.73.13; nothing to exclude.
- **Review round on the PR.** A shipped issuer must be a valid CA (valid now, `basicConstraints`, `keyCertSign`), and the
  chain verdict no longer depends on the order of the bundle; a subject e-mail is read by OID from the structured
  subject (a `+` address works); a certificate bundle over ten certificates is refused instead of cut; a
  `multipart/signed` with a third part does not verify; a certificate that can no longer be inspected withdraws its
  days-left gauge; `eddi.email.ledger-retention` below 3h is raised to 3h so a held row always reaches its give-up
  step; a held group reply's content is kept for the hold's length.

### Unverified / next

- How Gmail, Outlook, Apple Mail and Thunderbird display the signature (plan §14 item 7), RSA-PSS support; Bouncy
  Castle's mailcap handlers under Quarkus classloading; a live run against the test mailbox.
- Next: C3 (held mail review) builds on `emailControl`.
