## Email mail log, Message-ID lookup and daily owner digest (2026-11-18)

**Repo:** EDDI · **Branch:** `feat/email-mail-log`

Phase 2 of the email channel v2 plan: before EDDI starts ignoring mail it was not addressed in (Phase 3,
`actWhen: addressed`), an admin must be able to see why any mail was or was not acted on.

**What changed**

- `IEmailMessageLedger` gains `findByIntegration(integrationId, LedgerQuery)` — one integration's rows, newest first,
  keyset-paginated on `(createdAt desc, id desc)` with an opaque cursor — and `countInboundByOutcome(integrationId,
  since, until)`. Both twins: MongoDB (index `idx_email_ledger_integration_created`) and PostgreSQL (`CREATE INDEX IF
  NOT EXISTS idx_email_ledger_integration_created`, ids compared `COLLATE "C"` so ties order byte-wise as in MongoDB);
  the in-memory fake follows them. New `EmailLedgerLogContract`, run by both ledger ITs and by
  `InMemoryEmailLedgerLogTest` (so the fake cannot drift), covers ordering, paging over equal timestamps, every
  filter, the literal reason prefix and the outcome counts.
- Signature marker rows (`lastError = signature_seen`, written by the replay guard of the open inbound hardening PR)
  are dedup records, not mails: the log hides them by default and the counts never include them.
- REST, beside the suppression endpoints and with the same check (edit access, 404 for a non-email channel):
  `GET /channelstore/channels/{id}/email-log` (filters `direction`, `kind`, `state`, `reason` prefix, `since`,
  `until`, `limit` (default 50, clamped to 1–200), `after` cursor; a bad value or cursor is a 400) and `POST …/email-log/lookup`
  (`{"messageId": …}` → the row(s) of that Message-ID, inbound first, or 404; POST keeps the id out of access logs).
- New common key `ownerDigest` (`off` default | `daily`): from 00:05 UTC the inbound sweep has
  `EmailOwnerNotifier.sendDigestIfDue` send the previous UTC day's inbound counts by outcome to `ownerNotifyAddresses`
  inside `eddi.mail.notify.allowed-recipient-domains`, once per day cluster-wide (`claimNotice`), counts only, nothing
  for a quiet day. No new scheduler.
- `docs/email-integration.md`: "Troubleshooting: why didn't EDDI act on a mail?" with the reason table, both endpoints
  and the digest; `ownerDigest` in the keys table.
- Manager: a **Mail log** section on an email integration's page (`#mail-log`): filters by direction, state and
  reason category, "load more" on the cursor, and a "Look up Message-ID" box (`email-mail-log.tsx`,
  `use-email-log.ts`, `lib/api/email-log.ts`); a "Daily mail digest" select in the email settings. i18n in all 11
  locales, MSW handlers (`MOCK_EMAIL_LOG`), the two operations added to `openapi-operations.json` by hand in the
  generator's order (regenerating needs a backend built from this branch), tests in `email-mail-log.test.tsx`.
- Platform Operator: `GET /channelstore/channels/{id}/email-log` on the read allow-list and a "why didn't the
  platform answer my mail?" paragraph in the prompt; revision 81 → 82. The `POST …/lookup` is deliberately not
  granted: it only reads, but the gate decides by method and `READ_ENDPOINTS` means GETs — the listing's filters
  find the same row.

**Design decisions**

- The Manager link is `/manage/channels/<id>#mail-log` (a section of the channel page, which has no tabs) rather than
  the plan's `?tab=mail-log`.
- Outcome keys: the state in lower case, `refused:<category>` for a refusal, `deferred:<category>` for a
  `RECEIVED` row that carries a reason.
