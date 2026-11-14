## 🐛 fix(email): the clear-suppression log line no longer names the address by an unkeyed hash (2026-11-14)

**Repo:** EDDI (`fix/email-suppression-log-pseudonym`)

### What leaked

#1350 replaced the unkeyed SHA-256 of an email address with the keyed `GdprPseudonymizer` token in the audit
entries and the bounce log line, but missed one log line: when an administrator clears a suppression,
`EmailChannelSupport.clearSuppression` still logged `IEmailSuppressionStore.hash(address)`, an unkeyed
`sha256:<hex>` of the address. Anyone who can read the logs could confirm a guessed address by hashing it.

### Fix

- `EmailChannelSupport` names the address by `GdprPseudonymizer.pseudonymFor(lower-cased address)`, the token
  the bounce line in `EmailReplySender` logs. The pseudonymizer is field-injected like the suppression store;
  without one, the line names no address at all.
- A sweep of every log call, audit entry and metric tag under `integrations/email/` found no other place an
  address or its unkeyed hash reaches a log. The suppression store still keys its rows by the unkeyed hash;
  that is storage, not a log, and unchanged.
- Javadoc on `EmailInboundProcessor.audit`, `EmailReplySender.recipientPseudonym` and
  `HitlEmailNotifier.recipientPseudonym` claimed the token is the one GDPR erasure writes for that address. It
  is not: erasure pseudonymises the user id (for a per-sender identity `email:<integrationId>:<address>`), not
  the bare address. Corrected; behaviour unchanged.

### Tests

- `EmailChannelSupportTest`: the clear-suppression log line carries the pseudonym and neither the address nor
  the hex of its unkeyed SHA-256; without a pseudonymizer it still logs, naming no address.
- `EmailReplySenderTest.boundedCache` also asserts that the group-reply cache holds at most
  `MAX_CACHED_GROUP_REPLIES` after `cleanUp()`, so it fails when nothing is evicted.
