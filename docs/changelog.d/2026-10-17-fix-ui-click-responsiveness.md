## ⚡ fix(ui): clicks that never wait on I/O, and a Chat UI that holds up on phones (2026-10-17)

**Repo:** EDDI (`fix/ui-click-responsiveness`)

Half of a review of both UIs for click latency, every screen size and every
state. Four read-only reviews (click responsiveness, layout/RTL, Manager state
handling, Chat UI) produced about 100 verified findings. This PR holds the
Manager's click-latency fixes and the whole Chat UI; the Manager's layout, RTL
and state fixes are in the companion PR on `fix/manager-layout-a11y`. The
two share no file and build independently. Agent cards here carry the
`reveal-on-touch` class, which that PR defines; until it lands the class is
inert.

### Clicks no longer wait on I/O (Manager)

- **Optimistic updates.** New [`lib/optimistic.ts`](../../ui/manager/src/lib/optimistic.ts)
  (`patchQueries` / `restoreQueries`). The schedule enable toggle flips its row
  at once and rolls back on failure; only the clicked row is disabled, not
  every toggle on the page. Notification mark-read/dismiss update the badge and
  the list immediately, and busy state is per item.
- **Mutations no longer hold their pending state through a refetch.**
  `onSuccess`/`onSettled` arrows that *returned* `invalidateQueries(...)` made
  TanStack wait for the refetch before call-site callbacks ran (closing a
  dialog, navigating). Fixed in `use-cluster`, `use-group-templates`,
  `use-group-workspace` and `use-user-conversations`. The share dialog no longer
  awaits five invalidations one by one before re-enabling its controls.
- **Deploy no longer refetches every agent card.** Deploy and undeploy
  invalidated all of `agentKeys.all`, so every card's status query refired.
  `agentDeploymentQueries(agentIds)` in [`query-keys.ts`](../../ui/manager/src/lib/query-keys.ts)
  matches only the deployed agent's entries and the environment listings, and
  refreshes any sub-key it does not recognise.
- **Filters stop blanking the list.** Search on agents and workflows, the
  cluster leases and dead-letter filters, and the logs instance filter are
  debounced (250–300 ms) and use `placeholderData: keepPreviousData`.
- **Fewer sequential round trips.** The "where is this used" scan after a
  resource save fetches workflows and agents with bounded concurrency (8), and
  fetches each agent once rather than once per matching workflow. Grant dry
  runs run in parallel, and so do the agent editor sheet's post-save
  invalidations.

### Chat UI

- Layout: a long code line or a wide table widened the bubble and scrolled the
  whole transcript sideways (`min-width: 0`, plus a table scroller). Inputs are
  16px so iOS does not zoom on focus. The viewport meta adds `viewport-fit=cover`
  and `interactive-widget=resizes-content`, and the root height is `100dvh`, so
  the composer clears both the home indicator and the keyboard. Very wide
  screens cap line length. Lists, blockquotes and tables use logical properties,
  and messages carry `dir="auto"`.
- Scrolling: pressing Scroll to bottom during a reply turned following off again
  as soon as the smooth scroll began, because its own scroll events read as
  "left the bottom". Now only the reader moving up ends following. The view also
  re-pins for content that grows without a new message (the throttled trailing
  render, images, KaTeX, a shrinking viewport), and the button scrolls the
  transcript rather than the host page.
- Indicators: no more dots under a reply that is already streaming. Undo, redo,
  retry and cancel no longer show the typing dots or a Stop button. Pressing
  Stop then would have cancelled the conversation and raised a false
  "interrupted" banner.
- Focus and input: focus moves to the composer after a quick reply or Stop, and
  back to the New-conversation button when its confirmation is cancelled. Quick
  replies render disabled while a click would be dropped. On touch screens Enter
  inserts a newline. A refused draft regrows to its height. An agent-requested
  field no longer steals focus on page load.
- Undo and redo rebuild messages with position-derived ids, so unchanged
  bubbles keep their keys and are not re-animated and re-parsed. The status
  strings "Thinking…", "Using {tool}…" and the scroll button label are
  translated.

### Team setup, HITL and background polling

- **Group and Workforce wizards set their members up in parallel.**
  `runDeployManyWithGrants` deploys `DEPLOY_MANY_CONCURRENCY` (3) targets at a
  time instead of one after another, and both wizards create new agents three
  at a time. The grant dialog still asks one question at a time: a new request
  used to cancel the open one, which is why the loop was sequential, so the
  per-agent second-chance dialogs now go through `serializeGrantDecisions`.
  Every agent that was created is recorded before a failure is reported,
  because provisioning is not idempotent. The Workforce wizard raises one
  toast for the batch, and each failed row shows its own error.
- **HITL human-input submit is optimistic.** The cached group conversation
  leaves `AWAITING_HUMAN_INPUT` at once and is put back if the submit fails. It
  used to await two refetches, which kept the button pending for an extra round
  trip.
- **The Chat UI no longer polls approval status from a hidden tab.** The next
  read is parked and taken at once when the tab is visible again.

### Review fixes

- Optimistic rollbacks are scoped to their own operation. A failed schedule
  toggle undoes only its row, and a failed notification dismiss undoes only its
  items and its share of the badge, instead of restoring a whole snapshot over a
  later change. Every schedule row with a toggle in flight stays disabled
  (`useMutationState`), not just the latest one.
- The share dialog refreshes the agent's detail and descriptor queries as well
  as the lists. Those keys have their own roots, `agent` and
  `agent-descriptor`, not `agents`.
- Dead-letter paging is disabled while a page is still placeholder data, so a
  second click cannot push the same cursor twice.
- The logs instance box follows back/forward navigation.
- Schedule edits made through the trigger tabs, cron presets and the agent
  picker count as edits, so Escape or a backdrop click no longer discards them.
- The Chat composer takes a pending focus request only once the turn has
  finished, so the phone keyboard does not cover a reply as it arrives.

### Not done (follow-ups)

- A client-side idle watchdog for a stalled chat stream was considered and
  rejected. The conversation stream sends no keep-alives (only the log stream
  does), so a single slow tool or model call is legitimately silent for
  minutes, and a watchdog would cut real turns off as "connection lost". It
  needs a server-side heartbeat first.
- Workforce analytics and the skill registry still fire one request per group or
  skill; both need batch endpoints on the backend.

### Tests

New: [`responsiveness-review.test.tsx`](../../ui/chat/src/components/responsiveness-review.test.tsx)
and a Scroll-to-bottom regression in `ChatWidget.ux.test.tsx` (Chat), and
[`optimistic-mutations.test.tsx`](../../ui/manager/src/hooks/__tests__/optimistic-mutations.test.tsx)
(Manager), plus [`deploy-many-concurrency.test.ts`](../../ui/manager/src/hooks/__tests__/deploy-many-concurrency.test.ts)
and a hidden-tab case in `useHitlPolling.test.ts`. Mutation-checked: restoring
the old scroll handler, dropping the dialog queue (in the serializer or in its
use), dropping the HITL cache patch and ignoring visibility each fail a test.
