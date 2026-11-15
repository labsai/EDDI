## ✨ feat(hitl): Slack approval cards for every pause, not only Slack-started ones (2026-11-15)

**Repo:** EDDI (`feat/slack-cards-for-all-pauses`)

### What changed and why

A Slack approval card was posted only for a conversation or group discussion that a Slack message started:
`SlackEventHandler.notifyApprovers` and `SlackGroupDiscussionListener` were the only posters. A pause from a
scheduled run, a team cadence, a webhook-triggered agent or a REST/MCP/Manager conversation reached nobody in Slack,
so someone had to poll the Manager's Approvals page. Now an agent or group names the Slack channel integration that
announces its pauses, and every committed pause gets the same card.

- **Config.** `hitlConfig.notify.slack.integration` on agents (beside `notify.email`) and — new — on groups
  (`AgentGroupConfiguration.HitlConfig.notify`; a group's `notify.email` is refused at save time, since group pauses
  are not mailed). Save-time validation: not blank, ≤ 255 characters, no `|`. Deployment default:
  `eddi.slack.hitl.notify.default-integration`; unset and unnamed = off, exactly as before.
- **Trigger.** [`SlackPauseNotifier`](../../src/main/java/ai/labs/eddi/integrations/slack/SlackPauseNotifier.java)
  observes `HitlPauseCommittedEvent` (as the e-mail notifier does) and the new
  [`GroupHitlPauseCommittedEvent`](../../src/main/java/ai/labs/eddi/engine/events/GroupHitlPauseCommittedEvent.java),
  fired by `GroupHitlCoordinator.commitPause` through `GroupConversationService.firePauseCommitted` once the group
  pause is durable. It re-reads the pause and posts nothing for one already decided.
- **Same card, same security.** Built from the stored pause like the Slack-started card (redacted tool-call preview,
  edit/reject modals, approval context only on `hitlApprovalContext` opt-in, unfurling off then). The integration's
  own `hitlApprovalChannel`, bot token, `hitlApproverUserIds` and `teamId` apply; the agent config chooses an
  integration, never a channel or a token. The approval record is written before the post with a fresh card id in
  the buttons, so `SlackInteractivityHandler`'s card, pause, approver and workspace binding hold unchanged — tested end
  to end (a click on the posted card resumes; the card cannot approve a later pause; a non-approver is refused). The
  record is also the "one card per pause and integration" marker, so the Slack thread path and this one never post
  twice through the same integration. A failed post gives the record back; a record store failure posts without
  buttons (fail closed).
- **Resolution updates.** `ISlackApprovalRecordStore` keeps the card's `messageTs` (`markPosted`, `findPosted`;
  Mongo field / Postgres `message_ts` column added with `ADD COLUMN IF NOT EXISTS`; a replaced expired record clears
  it). On `HitlResumeCompletedEvent` the conversation's card gets the verdict text; a sweep
  (`eddi.slack.hitl.notify.sweep-interval`, default 60 s) finalizes every other tracked card whose subject left that
  pause — group decisions, cancels, timeouts, replaced pauses, deleted subjects — and removes the record. Unreadable
  subjects wait for the next sweep. Cards from the Slack thread path are not tracked (unchanged behaviour).
- **Metric** `eddi_slack_hitl_notify_total{outcome}`.
- **Manager.** A shared `SlackNotifyField` on the agent's and the group's Human-in-the-Loop settings, validation
  mirrored in `slackIntegrationProblem`, and `mergeNotify` so that switching email off no longer drops `notify` as a
  whole (it would have wiped the Slack card) and vice versa. 8 keys × 11 locales.
- **Platform Operator.** The prompt explains when a pause gets a Slack card and which settings decide it; revision
  69 → 70.

### Design decisions

- **Name an integration, not a channel.** The integration already holds the approval channel, the approvers, the
  workspace and the token, and the existing code refuses to post with one integration's token into a channel another
  setting chose. Pointing at an integration keeps that rule.
- **Sweep plus fast path for resolution.** Group pauses end on many paths (approve, reject, cancel, timeout, close)
  with no common event; one cheap periodic check of the tracked cards covers all of them, while conversations get an
  immediate update from the event that already exists. Card updates are idempotent, so every instance may sweep.
- **The deployment default does not skip Slack-started pauses.** Through the same integration the record
  de-duplicates them; through a different one the second card is what the configuration asked for.

### Verification

Backend: 461 tests pass in one run — every touched class (`SlackPauseNotifierTest` 30, Mongo/Postgres record-store
tests, validation, group coordinator and service, interactivity, Slack event handler, listener, resume observer, email
notifier) plus the repo-wide guards (`ImportStyleTest`, `BuildQualityGatesTest`, `Documentation*Test`,
`ChangelogFragmentTest`, `ConfigurationReferenceCoverageTest`, `MetricsDashboardCoverageTest`, `StrictBoundary*`).
13 of 13 mutants killed (pause re-check, record give-back, de-duplication, buttons without approvers, `markPosted`,
sweep resolution and its unreadable/live cases, deployment default, `|` and group-email validation, group event
firing). The new meter has a panel in the Full Metrics dashboard. Manager: hitl/notify/group/editor suites (619),
operator (555) and i18n (116) tests pass; `i18n:check`, `typecheck` and lint on the changed files clean.

### What's next

- Cards posted by the Slack thread path could be tracked the same way (one `markPosted` call each) so they, too,
  lose their buttons when the pause is decided in the Manager.
- `SlackEventHandler.notifyApprovers` and `SlackPauseNotifier.postCard` build the card along the same lines; folding
  them into one poster is a refactor worth doing separately.
