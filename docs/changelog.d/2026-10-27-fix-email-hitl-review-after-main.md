## 🐛 fix(email): HITL notification review findings after the retarget to main (2026-10-27)

**Repo:** EDDI (`feat/email-hitl-notifications`)

### What changed and why

Once #1140 merged and #1141 was retargeted to `main`, the review bots saw the whole slice and raised these:

- **One recipient's failure no longer drops the rest.** `HitlEmailNotifier.notifyPause` handles each recipient on its own: a store error while counting, inserting or attempting one recipient is logged under that recipient's pseudonym and the loop moves on. The sweep only retries rows that exist, so a recipient skipped this way would never have been mailed for that pause.
- **A send failure that is not a `MailSendException` is an attempt.** A `RuntimeException` from `SystemMailer.send` (a bad address, a mailer bean that could not be built) used to escape `attempt`, leaving the row PENDING and reclaimed after every lease without ever reaching `max-attempts`. It is now rescheduled with backoff and FAILED at the limit, like a transient SMTP failure.
- **The audit entry names the recipient by keyed pseudonym.** It carried an unkeyed SHA-256 of the address beside the recipient's domain, which anyone reading the ledger or the logs could confirm by hashing candidate addresses. It is now `recipientPseudonym`, from `GdprPseudonymizer` (the audit ledger's keyed pseudonym, or the per-deployment salt), the same token GDPR erasure writes for that identifier; the rate-limit WARN uses it too.
- **A `From` with a valid mailbox beside an unparseable entry has no sender.** `MimeMessageParser.parseFrom` now records `MULTIPLE_FROM` for it, as `AddressNormalizer.parseSingle` already did, so DKIM/DMARC never authenticates a domain the header did not name unambiguously.
- **Manager:** a switched-off HITL section no longer blocks the save over hidden recipients, and the section resets per agent and version (`key` on `HitlConfigSection`).
- **`SystemMailer`** states `mail.smtp.ssl.checkserveridentity=true` where the session is built (CodeQL `java/insecure-smtp-ssl`); `SafeSocketConnector.angusProperties` already set it under the protocol's prefix.
- **Dashboard:** the email panels collided with main's Conversation Ratings panels on ids 264 and 265; they are now 267 and 268.

Files: [`HitlEmailNotifier.java`](../../src/main/java/ai/labs/eddi/integrations/email/notify/HitlEmailNotifier.java), [`MimeMessageParser.java`](../../src/main/java/ai/labs/eddi/engine/mail/MimeMessageParser.java), [`SystemMailer.java`](../../src/main/java/ai/labs/eddi/engine/mail/SystemMailer.java), [`agent-config-sections.tsx`](../../ui/manager/src/components/editors/agent-config-sections.tsx), [`agent-detail.tsx`](../../ui/manager/src/pages/agent-detail.tsx), [`eddi-full-metrics-dashboard.json`](../monitoring/eddi-full-metrics-dashboard.json), with tests for each; each new test fails with its fix reverted.
