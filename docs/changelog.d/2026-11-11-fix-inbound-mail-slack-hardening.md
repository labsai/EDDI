## 🔒 fix(email, slack, alerts): inbound mail and Slack webhook hardening (2026-11-11)

**Repo:** EDDI (`fix/inbound-mail-slack-hardening`)

Seven confirmed findings of a security review of the inbound channels.

### What changed and why

**1 — A DKIM aligned pass no longer survives unsigned headers, and a replayed signed mail no longer runs as its
author (high, integrity).** A signature covers only the header instances its `h=` names. Anyone holding someone's
genuine signed mail could prepend `Content-Type: multipart/mixed; boundary=evil` (the body hash and signature still
verify, the mail library reads the topmost `Content-Type`) and have EDDI run a part they had the victim quote as the
victim's words; or deliver the signed mail again with an unsigned `Subject`, plus-tagged `To`, `In-Reply-To`,
`References` or `Reply-To` of their choosing, or under a new unsigned `Message-ID`.

- `DkimVerifier` returns each signature's `h=` list (with multiplicity), its `t=` and a digest of `d=`/`b=`
  (`DkimResult`), and the counts and values of the headers that matter (`Verification`).
- `SenderAuthenticator` grants `ALIGNED_PASS` only when no `Content-Type`, `Content-Transfer-Encoding` or
  `MIME-Version` occurs more often than `h=` lists it, and when the signature's `t=` — or else the signed `Date` — is
  no older than `eddi.email.dkim.max-signature-age` (default 7 days, `0` disables) at delivery. For a signer that
  leaves `Content-Type` out of `h=` (Gmail), a single unsigned `multipart/*` type passes only when the signed body's
  first non-blank line is exactly `--<its boundary>`, and `message/*` never; `eddi.email.dkim.require-signed-content-headers=true`
  (default `false`) is the hardened rule that refuses unsigned multipart types and non-identity transfer encodings.
  The pass carries the headers its signature covers (`SignedHeaders`).
- `InboundMail.signedView()` drops an uncovered `Subject` and `Reply-To` and keeps only the signed `To`/`Cc`
  instances; `EmailInboundProcessor` uses that view for routing (`EmailTargetRouting`), the agent's `mail` data and
  the reply recipient (`EmailReplyRecipients` sees no unsigned `Reply-To`). `In-Reply-To`, `References` and `Date` are
  kept unsigned: Gmail does not sign the thread headers, and a continuation is bound to the conversation's own
  authenticated sender anyway. A mail whose
  `Message-ID` is unsigned is also deduplicated on its signature: a terminal marker row keyed `<dkim.<digest>@eddi.invalid>`,
  so a copy under a new id is refused as `duplicate:replayed_signature`.
- `Reply-To`, `Content-Type`, `Content-Transfer-Encoding` and `MIME-Version` joined the singleton headers whose
  duplicate fails authentication. `To`/`Cc` legitimately repeat, so their unsigned instances are ignored instead.

**2 — The MIME part cap holds by construction (high, availability).** The delimiter pre-count missed delimiters the
mail library accepts (a bare CR around a boundary line), and a `Content-Type` over 16,000 characters was cut — EDDI
counted one boundary while the library split on another. One mail could make the library build millions of parts
before any authentication. Multiparts are now built by a `MimeMultipart` subclass that asks a per-message part
budget before every body part (nested multiparts share it), the library is handed the very `Content-Type` text EDDI
parsed, the counter treats a bare CR as a line start, and an over-long `Content-Type` refuses the message.

**3 — Encoded-word decoding is bounded (medium).** Charsets resolve from a table built once from
`Charset.availableCharsets()` (names and aliases, case-insensitive) instead of `Charset.forName` per word, and file
names and display names are capped before they are decoded.

**4 — Mail HTML is capped before parsing (low-medium).** `eddi.email.max-html-characters` (2 MB, all HTML parts
together) — the rest is cut and the mail flagged `html_truncated` — and the HTML quote strip does not copy a tree of
more than 100,000 elements.

**5 — Attacker DNS cannot defer a mail for a day (low-medium).** Signatures whose `d=` could align with `From`
(relaxed, judged without DNS) are verified first; once one passes, the others are `SKIPPED` without a lookup. A
temporary failure of a signature that can never align no longer makes the verdict temporary, so it cannot defer the
mail. Non-aligning signatures are otherwise still verified, so `PASS_UNALIGNED` is unchanged. Temporary DNS failures
are cached for two minutes.

**6 — Slack webhooks are small and verify before parsing (medium).** Both endpoints read the body as a stream, at
most `eddi.slack.max-body-bytes` (1 MB, 413 above), and `RequestBodyLimitGuard` refuses a larger declared length
before the body is read. `/interactive` refuses missing headers, a stale timestamp and a deployment without any Slack
signing secret before it decodes anything, verifies the raw body against the pooled secrets, and only then parses the
payload to find the owning integration, whose secret alone must verify it again (as before).

**7 — Alert rules post only into their own agent's Slack channels; the test endpoint is throttled (low).** An editor's
Slack target must be a channel whose serving integration targets the rule's agent (`SLACK_CHANNEL_NOT_PERMITTED`,
the same answer for a channel served by another integration or by none); checked on create, update and test, not on
delete. `POST /alerts/rules/test` allows `eddi.alerts.test-max-per-minute` (6) sends per caller and minute
(`429 TEST_THROTTLED`).

**Review follow-up (2026-11-12).**

- The signature replay key hashes the domain with the *decoded* `b=` bytes, not its text: `b=` is blanked from the
  signed header input, so stripping its padding or refolding it kept the signature valid and gave a new key.
- A mail without a usable Message-ID (keyed on a hash of its raw bytes, which an added unsigned header changes) now
  records its signature too, so an added header or Message-ID does not make the copy a new mail.
- A signed `Date` standing in for `t=` may be at most one hour ahead of the delivery instant; a far-future Date would
  otherwise keep the signature from ever growing too old.
- File names: the length cap keeps the end of the name (path's last segment, extension), a basename over 255
  characters keeps its extension when cut, and an attachment whose raw name exceeds the cap is withheld as
  executable — `a…a/payload.js` had lost its `.js` and been stored.
- Address headers: encoded words past 100 per header are broken before the mail library's `getPersonal()` decodes
  them (a charset lookup per word).
- Alerts: Slack targets are checked again at every delivery (`AlertDeliveryService`); the server records on each
  Slack target whether an administrator admitted it (`adminAdmitted`, never from a body, never shown). Pre-mark rules
  over several agents count as an administrator's; a pre-mark single-agent rule whose channel no longer belongs to
  its agent fails that delivery with the reason on the event. Disabling a rule no longer requires its unchanged Slack
  channels to still be deliverable.

### Design decisions

- The loop guard and delivery-report handling still read every header of the mail as received: a header that can
  only make EDDI refuse a mail is safe to believe unsigned.
- The default content-header rule is structural, not strict, so ordinary Gmail HTML mail (multipart/alternative,
  `Content-Type` not in `h=`) keeps authenticating. Residual: a single-part mail from such a signer whose body begins
  at byte 0 with attacker-quoted delimiter text can be re-labelled as multipart. `true` closes it where all senders
  sign `Content-Type`.
- `requireAddressed` (the integration's own address must appear in a signed `To`/`Cc`) was not added: distribution
  lists, aliases and BCC deliver mail whose `To` does not name the mailbox, and the replay it would narrow is closed by
  the signature age, the signed-header view and the signature-keyed dedup.
- The Slack-channel check for alerts is a separate `AlertAccess.requireDeliverable`, applied to what is stored or
  tested, so an editor can still delete a rule whose channel has changed hands.

**Files:** [`DkimVerifier.java`](../../src/main/java/ai/labs/eddi/engine/mail/DkimVerifier.java),
[`DkimResult.java`](../../src/main/java/ai/labs/eddi/engine/mail/DkimResult.java),
[`SenderAuthenticator.java`](../../src/main/java/ai/labs/eddi/engine/mail/SenderAuthenticator.java),
[`SignedHeaders.java`](../../src/main/java/ai/labs/eddi/engine/mail/SignedHeaders.java),
[`MailAuthentication.java`](../../src/main/java/ai/labs/eddi/engine/mail/MailAuthentication.java),
[`InboundMail.java`](../../src/main/java/ai/labs/eddi/engine/mail/InboundMail.java),
[`MimeMessageParser.java`](../../src/main/java/ai/labs/eddi/engine/mail/MimeMessageParser.java),
[`MailLimits.java`](../../src/main/java/ai/labs/eddi/engine/mail/MailLimits.java),
[`MailCharsets.java`](../../src/main/java/ai/labs/eddi/engine/mail/MailCharsets.java),
[`MailDnsResolver.java`](../../src/main/java/ai/labs/eddi/engine/mail/MailDnsResolver.java),
[`QuoteStripper.java`](../../src/main/java/ai/labs/eddi/engine/mail/QuoteStripper.java),
[`EmailInboundProcessor.java`](../../src/main/java/ai/labs/eddi/integrations/email/EmailInboundProcessor.java),
[`RestSlackWebhook.java`](../../src/main/java/ai/labs/eddi/integrations/slack/rest/RestSlackWebhook.java),
[`RequestBodyLimitGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/RequestBodyLimitGuard.java),
[`AlertAccess.java`](../../src/main/java/ai/labs/eddi/engine/alerts/rest/AlertAccess.java),
[`AlertTestThrottle.java`](../../src/main/java/ai/labs/eddi/engine/alerts/rest/AlertTestThrottle.java),
[`RestAlertRules.java`](../../src/main/java/ai/labs/eddi/engine/alerts/rest/RestAlertRules.java);
docs: [`security.md`](../security.md#mail-sender-authentication--dkim-and-dmarc),
[`email-integration.md`](../email-integration.md), [`slack-integration.md`](../slack-integration.md),
[`alerts.md`](../alerts.md), [`configuration-reference.md`](../configuration-reference.md).

```decision-log
| 2026-11-11 | An aligned DKIM pass vouches only for headers its signature wholly covers; Subject, Reply-To and To/Cc plus-tags steer only when covered, thread headers are read unsigned | Prepended Content-Type and unsigned Subject/To/Reply-To/References on a replayed signed mail ran as its author | Trusting every header of a signed mail; failing all mail with any unsigned header |
```
