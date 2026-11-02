## 🐛 fix(hitl): email-notification review follow-ups (2026-10-27)

**Repo:** EDDI (`fix/hitl-email-review-followups`)

### What changed and why

Follow-up to the HITL email notifications PR (#1141), for the review threads left open when it merged.

- **Docs and Javadoc named the wrong thing.** The `email.notify` audit entry carries `recipientPseudonym` (the GDPR pseudonym token: keyed with the audit ledger's key, salted per deployment when it has none, and an unsalted hash only when a deployment has neither) next to `recipientDomain`, and the rate-limit WARN names the recipient by that same pseudonym. [`hitl.md`](../hitl.md), [`configuration-reference.md`](../configuration-reference.md) and the `HitlEmailNotifier` class Javadoc still said "SHA-256 hash" / "by hash", which would send an operator looking for a value that is never written.
- **Manager: off then on again lost the typed recipients.** Switching email notifications off clears the stored list, and the effect that resyncs the field to the stored list then emptied the draft, so switching back on (before saving) came back empty. The typed list is now kept when the user switches it off.
- **Manager: internationalised domains were rejected.** The server converts the domain to ASCII (`AddressNormalizer`, `IDN.toASCII`) and accepts `ops@bücher.example`; the Manager's check was ASCII-only and marked such an agent invalid. The domain is now converted the same way before it is checked (the local part must still be ASCII, as on the server), and the Unicode and punycode spellings of one domain count as a duplicate. [`hitl.md`](../hitl.md) now says so. Two further gaps CodeRabbit found in that conversion are closed: URL syntax after the domain (`/path`, `?q`, `#f`, `:25`, a backslash, `%`) is refused instead of being parsed away, and a domain containing ß, ς or a zero-width (non-)joiner is refused, because the platform's IDNA processing keeps those and the server's `IDN.toASCII` maps or drops them, so the two would normalise the domain differently (the punycode spelling stays valid).
- **No change needed:** the metrics dashboard panel ids are unique and the split dashboard parts are in sync with the source (`sync-monitoring-assets.py` produces no diff); the collision the thread described was renumbered after the review.

### Design decisions

- The Manager still sends the addresses as typed; normalisation stays the server's job.
- Browser IDNA conversion (`new URL`) stands in for `IDN.toASCII`, with the known divergent characters refused outright; the backend remains the authority and answers 400 for anything the two still disagree on.
