## 🐛 fix(email): HITL email notification review findings (2026-10-25)

**Repo:** EDDI (`feat/email-hitl-notifications`)

Eight review findings on the HITL email notifications ([`docs/hitl.md`](../hitl.md), section "Email notifications").

### What changed and why

- **GDPR erasure keeps the user directory entry when an earlier participant failed**
  (`GdprComplianceService`). The email ledger finds a person's rows by the address in the user directory, which is
  why the directory participant runs `LAST`. The loop did not link the two: with the ledger step failing, the
  directory entry was still erased, so the re-run the result asks for could no longer find those rows and they
  stayed until ledger retention. A `LAST` participant is now skipped, and reported as failed, when an earlier
  participant failed (the same reasoning as step 4c, which keeps the snapshots for the retry).
- **A pause on the conversation-start turn is announced only after the descriptor is written**
  (`ConversationService.startConversation`). A failed descriptor write discards the conversation again, and the
  asynchronous notifier could already have mailed about it. The say and resume sites already fire after the
  conversation is final.
- **`eddi.mail.smtp.connection` must match the connection-name grammar** (`SystemMailer.parse`). A name no stored
  connection can carry (`"my mail"`) used to leave the mailer reported as enabled with every send failing; it now
  disables the mailer at startup with the reason.
- **The email ledger caps `providerId` (255) and `lastError` (512) on every write, in both stores**
  (`IEmailMessageLedger.cap`, Mongo and PostgreSQL). Those are the PostgreSQL column widths; an uncapped render
  error made PostgreSQL refuse the update, which left the row claimed until its lease ran out and retried it after
  every lease. The cap never splits a surrogate pair.
- **A timeout past the end of the time-line no longer aborts the notification** (`HitlNotificationContent`). A
  positive duration such as `P1000000000000D` passes config validation but overflows `Instant`; the deadline line
  now names the duration instead of a date. `HitlEmailNotifier.attempt` also treats any runtime failure while
  building the mail as a terminal `FAILED` row, instead of letting it escape and leave the row `PENDING`.
- **Manager: Save is refused while the recipients box is invalid** (`HitlConfigSection` reports it through
  `onValidityChange`, `agent-detail.tsx` refuses every save path, as it does for the spend limit). An invalid list
  never enters the draft, so an earlier valid edit was saved while the field showed something else. The recipients
  label is now associated with its textarea, and the hint no longer says the mail carries no conversation content:
  a tool-call pause lists the pending tools with redacted, shortened arguments (all 11 locales, plus the new
  `agentDetail.hitlNotifyFixBeforeSave` message).

The operator system prompt already described the content correctly (it names the redacted tool arguments), so it is
unchanged.
