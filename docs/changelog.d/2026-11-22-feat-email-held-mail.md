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
