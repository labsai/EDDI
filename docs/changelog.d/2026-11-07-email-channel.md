## 🐛 fix(email): pseudonymised audit, constructor-injected collaborators, bounded reply cache, one retry policy (2026-11-07)

**Repo:** EDDI (`fix/email-channel`)

### What changed and why

- **Audit entries name a mail address only by its keyed pseudonym.** A refused inbound mail (`email.inbound`) wrote
  `senderHash` and a sent, failed or bounced reply (`email.reply`) wrote `recipientHash`: an unkeyed SHA-256 next to
  the address's domain, in a write-once ledger erasure cannot reach. Anyone holding a list of candidate addresses at
  that domain could hash them and tell whose mail it was. Both entries now carry `senderPseudonym` /
  `recipientPseudonym` from `GdprPseudonymizer` — the keyed token HITL notifications (`email.notify`) already wrote
  under that name — and the bounce-suppression log line names the address the same way. The suppression list keeps
  its SHA-256: it is a lookup key, not audit output. Entries written before this change keep their `*Hash` field.
- **`EmailInboundProcessor` takes every collaborator through its constructor.** The reply sender and the audit ledger
  were field-injected and documented as "null in tests, and then no reply is ever attempted"; production code branched
  on `replies != null` three times, so a construction site that forgot them silently turned conversation-mode replies,
  group replies and bounce handling off. They are final constructor parameters now, with the pseudonymizer, and the
  null branches are gone; whether an integration replies is still `settings.replies()`.
- **The group-reply cache is bounded without forgetting everything.** A group reply's rendered request lives in the
  node's memory for its retries. The map was cleared wholesale once it reached 1,000 entries, and entries left only
  when the row ended on this node, so a row another node's sweep finished stayed forever; in a cluster the map filled
  until a clear dropped every reply still mid-retry, and each of those ended `FAILED (CONTENT_UNAVAILABLE)`. It is a
  Caffeine cache now: past 1,000 one entry is evicted, and an entry expires once its row cannot be retrying any more
  (`(lease + max backoff) × max-attempts`).
- **One retry policy for the email ledger's outbound rows.** The capped exponential backoff was copied into the HITL
  notifier, the reply sender and the mailbox poller, and the "fail, or reschedule and count a retry" branch four times;
  the copies had drifted — an unexpected (non-SMTP) reply failure was rescheduled but never counted in
  `eddi.email.outbound{kind=reply,outcome=retry}`. `LedgerRetry` holds the backoff and that branch; the three classes
  use it, and the reply sender now counts that retry.

### Design decisions

- `LedgerRetry` is a small instance (ledger, max attempts, base) rather than static helpers with nine parameters: it
  also owns the `max-attempts` / `retry-backoff` defaults both senders normalised separately. The send flows around it
  (retention, the pending gauge, content rebuilding) differ and stay in their classes — one shared worker would be a
  template method over two unlike flows.
- The cache's expiry is per write, not per access: the bound is "the longest a row can stay retryable", which is a
  property of the row's age, and a direct executor makes size eviction happen on the writing thread.

**Files:** [`LedgerRetry.java`](../../src/main/java/ai/labs/eddi/integrations/email/ledger/LedgerRetry.java),
[`EmailInboundProcessor.java`](../../src/main/java/ai/labs/eddi/integrations/email/EmailInboundProcessor.java),
[`EmailReplySender.java`](../../src/main/java/ai/labs/eddi/integrations/email/reply/EmailReplySender.java),
[`HitlEmailNotifier.java`](../../src/main/java/ai/labs/eddi/integrations/email/notify/HitlEmailNotifier.java),
[`EmailMailboxPoller.java`](../../src/main/java/ai/labs/eddi/integrations/email/EmailMailboxPoller.java),
[`email-integration.md`](../email-integration.md)

### What's next

- `EmailInboundSweeper` still field-injects its `EmailReplySender` the same way; it was outside this change.

```decision-log
| 2026-11-07 | Every `email.*` audit entry names a mail address as `GdprPseudonymizer`'s token (`senderPseudonym` / `recipientPseudonym`) beside its domain, superseding the SHA-256 + domain of 2026-10-24 for inbound refusals and replies | An unkeyed hash next to the domain is confirmed by hashing the candidate addresses at that domain, and the write-once ledger keeps it past erasure | Keep the SHA-256 (reversible by enumeration); drop the recipient (unauditable); the suppression list's hash (a lookup key, not audit output) |
```
