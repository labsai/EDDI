## 🖱️ fix(manager): the Workforce onboarding page, confirm dialogs and the command palette scroll again (2026-10-02)

**Repo:** EDDI (`fix/workforce-onboarding-scroll`)

### What was broken

- **The Workforce landing page could not be scrolled** (reported against 6.5.0). `workforce-main` clips its
  content on purpose: every Workforce page brings its own scroll container (the scroll contract in
  `workforce-scroll-contract.test.tsx`). `WorkforceDashboard` wrapped its populated view in one, but returned the
  empty state, `<OnboardingHero />`, bare. The hero is 1406px tall, so on a 1280×640 window everything below the
  how-it-works cards was out of reach, including the templates a first-time user needs to create a task force.
  The contract test missed it twice: its mock always returns task forces, and an empty render was accepted as
  "cannot overflow". The loading and error states had no scroller either.
- **`AlertDialog`, the shared confirm dialog, had no height cap.** It is fixed and centred with a transform, so
  content taller than the window ran off both edges with nothing able to scroll it. In a 375px landscape-phone
  window the Undeploy prompt measured −40px to 416px: Close above the screen, Cancel and Undeploy cut off at the
  bottom. Every confirm dialog shares the primitive; the ones with extra content (undeploy, agent delete,
  conversations, groups, operator upgrade) hit it first.
- **The command palette's list was a fixed 360px** below a 15% offset, so on a window under about 470px tall its
  bottom ran past the screen edge, where scrolling the list cannot reach it.

### What changed

- [`workforce-dashboard.tsx`](../../ui/manager/src/pages/workforce/workforce-dashboard.tsx): the empty, loading and
  error states are each a `flex-1 min-h-0 overflow-y-auto` scroller, like the populated view.
- [`alert-dialog.tsx`](../../ui/manager/src/components/ui/alert-dialog.tsx): `max-h-[calc(100dvh-2rem)]
  overflow-y-auto`, the same cap `AccessibleDialog` already had.
- [`command-palette.tsx`](../../ui/manager/src/components/shared/command-palette.tsx): the list is capped at
  `min(360px, 60dvh)`.
- Tests: the scroll contract gains a case that renders the dashboard with **zero** task forces and requires the
  onboarding hero (new `data-testid="workforce-onboarding-hero"`) to sit inside a scroller. A new
  `alert-dialog.test.tsx` pins the dialog's cap and scroller. Each was mutation-checked: removing the fix fails it.

### How the rest was ruled out

A detector run in the browser against the 6.5.0 image, over all 48 Manager and Workforce routes at 1280×640,
375×812 and 1280×480, with seeded data (two agents, a group, a conversation). For every visible element below the
fold, it checks whether some scroll container, or the document, can bring it into view. The only page-level failure
was the onboarding hero, and the detector caught it as a positive control (65 unreachable elements under
`#workforce-main`). Dialogs and sheets were checked by reading the overlay primitives: `AccessibleDialog`, the
agent performance sheet, the mobile sidebar and the version diff already cap and scroll. Both fixes were then
confirmed live: the patched Manager ran against the same backend, the hero scrolled to its last template, and the
Undeploy dialog fit the 375px window and scrolled to its buttons.

Not covered: states the seed data does not produce, such as a Workforce board with a long discussion or analytics
with data. Those render inside scrollers the contract test already requires.
