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

### Not done (follow-ups)

- Group and Workforce wizards still deploy and create their members one at a
  time. Running them in parallel needs care around the per-target grant dialog.
- HITL human-input submit still waits for two refetches; making it optimistic
  needs a cache patch of the group conversation's state.
- Analytics and the skill registry still fire one request per group or skill;
  both need batch endpoints on the backend.
- The Chat UI has no idle watchdog for a stalled stream, and HITL polling does
  not pause in a hidden tab.

### Tests

New: [`responsiveness-review.test.tsx`](../../ui/chat/src/components/responsiveness-review.test.tsx)
and a Scroll-to-bottom regression in `ChatWidget.ux.test.tsx` (Chat), and
[`optimistic-mutations.test.tsx`](../../ui/manager/src/hooks/__tests__/optimistic-mutations.test.tsx)
(Manager). The scroll test was mutation-checked: restoring the old handler fails
it.
