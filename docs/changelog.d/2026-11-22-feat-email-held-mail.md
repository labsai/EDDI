## ✉️ feat(email): held mail — review instead of drop, v3 phase C3 (2026-11-22)

**Repo:** EDDI · **Branch:** `feat/email-held-mail` (on `feat/email-approval-gate` = C1 + guard + C4, with
`feat/email-smime-backend` = C2 merged in)

Phase C3 of the email channel v3 control plan (`planning/email-channel-v3-control-plan.md` §7): mail EDDI would
refuse because the sender is not on the allowlist, or did not authenticate, can be held for a person who decides
whether it is processed at all. Documented in [email-integration.md](../email-integration.md#held-mail-review-instead-of-drop).

### What changed and why

- **Settings (§7.1)** on the typed `emailControl` (`EmailControl.OnRefused`, `EmailControl.Review`):
  `onRefused.notAllowlisted` / `unauthenticated` (`drop` default, `review`), `onRefused.notAddressed` (only `drop`:
  addressing, v2 phase 3, is not on this base), `review.expiry` (`P7D`, `PT1H`–`P90D`, and at most the ledger retention
  minus a day — checked in `EmailChannelSupport`), `review.maxHeldPerDay` (50), `review.maxHeldPerSenderDomainPerDay`
  (5), `review.reviewers` (user ids or `role:<name>`). Strings, so an unknown stored value reads as `drop`. `copy()`,
  `equals` and `hashCode` cover the new sections, so a sender-list edit keeps them. New save warning
  `email.anonymous-tools` (trigger mode with `requireAuthentication: false`).
- **Holding (§7.2).** `EmailInboundProcessor` evaluates both reviewable checks after the never-reviewable ones (loop,
  bounce, no single sender, blocklist/own address); when every failing one is set to `review` the row becomes the new
  ledger state **`HELD`** (`transitionReview`), with `review` (`held:not_allowlisted+unauthenticated`), the verdict in
  the new **`authVerdict`** field and the raw mail's SHA-256 — never a body. Defaults keep today's reasons and metrics.
  Caps refuse as `review_full` / `review_full:sender_domain`, counted by a durable marker (a discard frees no place).
  New `Disposition.HELD`: the poller marks it (`MailboxSession.markHeld` → sibling `Held` folder of the move folder)
  and the cursor advances. `HELD` is explicit in `State`, `isTerminal()` (an exhaustive switch) and `dispositionOf`.
- **Both stores.** Mongo fields `authVerdict`, `review` and index `idx_email_ledger_held`; Postgres columns
  `auth_verdict`, `review_state` (`ADD COLUMN IF NOT EXISTS`, in `CREATE TABLE` too) and an index. New methods
  `transitionReview`, `findHeld`, `countHeldSince`; `findDueResubmissions` also returns released rows without a
  conversation. Ledger contract tests in `EmailMessageLedgerContract`; `withApprovalOf` now carries the review
  fields, and the in-memory `transitionApproval` no longer drops `signed`.
- **Deciding (§7.3).** `EmailHeldMailReview` + REST `GET …/email-held`, `GET …/email-held/{rowId}` (live preview,
  re-read by Message-ID from the polled, move and Held folders, shown only when it hashes as the held mail) and
  `POST …/email-held/{rowId}/decision`, method-level `@RolesAllowed` admin/editor/approver; the resource then checks
  EDIT or `reviewers` (default `eddi-approver`). `runAndAllow` needs EDIT and the stored aligned pass, adds the row's
  own sender through C1's `updateEmailSenders` first and releases second (a release lost after the write is a 409
  saying so). Conditional transitions: decided once. Every decision is audited (`email.held`, pseudonymised sender).
- **Released mail.** Skips exactly the checks that held it; a newly failing reviewable check refuses it; the hash is
  checked on the run and on resubmission. Without an aligned pass (or when the live verdict disagrees) it runs as
  `email-anon:`, refused on a GROUP target, with the email gate armed even under `approvalPolicy: none` and the new
  `readOnlyOnly` flag (`EmailApprovalGate.Policy`, recorded on the conversation): `ToolLoopRunner` refuses every gated
  call instead of pausing. `EmailReplySender.enqueue` — the one door of every reply, notice and rejected reply —
  refuses a release without an aligned pass. A released mail still meets C4's `approvalPolicy`.
- **Expiry and notices.** `EmailInboundSweeper.expireHeld` (`review_expired`). `EmailOwnerNotifier.notifyHeld`: one
  notice per poll batch, at most hourly per integration, to `ownerNotifyAddresses` with a Manager link, never a
  sender or subject; `REVIEW_FULL` owner notice.
- **MCP** `list_held_email`, `decide_held_email` (tier `McpRoles.HELD_MAIL`; 125 tools). `decide_held_email` and
  the decision path were already on `EscalationGuard`'s lists; the decision endpoint refuses a tainted turn itself
  too. Skill `eddi-admin` updated.
- **Manager.** Approvals page: held mail as its own section with a decision dialog (live preview, the anonymous
  warning before the decision, run-and-allow only when offered). Channel detail: held-mail settings; new email
  integrations preselect `notAllowlisted: review`. 11 locales. Operator: `GET …/email-held` on the read allow-list
  (not the preview, not the decision — a person decides), prompt section, revision bump.

### Not done / next

- The mail log's state filter and the owner digest's "held" count: the mail log and digest (v2 phase 2) are not on
  this base. Reviewer principals' own addresses: EDDI cannot look them up, so notices go to `ownerNotifyAddresses`.
- `email.anonymous-tools` does not name the tools yet; Slack held-mail cards wait for #1438; the GDPR cascade over
  `email-anon:` conversations named by ledger rows (§13) is open.

## ✉️ fix(email): held-mail review follow-ups — v3 phase C3 (2026-11-22)

**Repo:** EDDI · **Branch:** `feat/email-held-mail`

Fixes from the independent review of the C3 commits.

- **A released mail's temporary-DNS grace counts from its release** (`EmailInboundProcessor.temporaryGraceStart`): the
  review value records the release time (`released:<decision>:<reasons>[:anon]:<epochSecond>`), so a DKIM tempfail on
  the run of a mail released days after arrival defers it instead of refusing it — and losing a `runAndAllow`.
- **Per-domain caps count only aligned-pass mail.** `countHeldSince(integration, domain, aligned, since)`: forged From
  addresses share one bucket and cannot use up a real domain's share. The caps count by the new `heldAt` (Mongo field,
  Postgres column `held_at`, `ADD COLUMN IF NOT EXISTS`), not by arrival.
- **Tainted turns cannot read held mail.** `list_held_email` is on `EscalationGuard.GUARDED_TOOLS`; the REST list,
  preview and cross-integration read refuse an `origin=email` turn.
- **`role:` reviewers** must name `eddi-admin`, `eddi-editor` or `eddi-approver` (validated at save): the held-mail
  endpoints admit only those roles, so any other would silently do nothing. Docs, UI hint (11 locales) and operator
  prompt say so (operator revision bumped).
- Smaller: the effective anonymity of a run is written onto the row (`:anon`), so the reply door refuses by the row
  alone; a release extends the row's retention; a mail held on resubmission is counted and announced; held-mail notices
  name hours for sub-day expiries and are re-tried on the next hour's poll when mail was held after this hour's notice
  (`IEmailMailboxStateStore.noticeClaimedAt`); a non-editor gets the same 403 for an unknown integration as for one they
  do not review; `runAndAllowOffered` considers the caller's EDIT right; the Manager polls held mail only for the roles
  that may read it and stops on a refusal.

**Rolling out:** older nodes cannot read `HELD` rows (an unknown state fails their row mapping). In a mixed-version
cluster, upgrade every node before setting any `emailControl.onRefused` value to `review`.
