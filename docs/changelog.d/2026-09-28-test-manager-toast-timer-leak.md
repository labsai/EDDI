## 🧪 test(manager): no sonner callback outlives a test (2026-09-28)

**Repo:** EDDI (`test/manager-toast-timer-leak`)

### What was wrong

CI's `UI Manager Checks` failed once (run 36418007466, on PR #873) with all 426 files and 6,858
tests passing, because Vitest caught one unhandled error: `ReferenceError: window is not defined`,
thrown from React's `dispatchSetState` inside a sonner timer, and attributed to
`resource-detail-save-not-live.test.tsx`.

The cause is in sonner 2.0.8 itself. It defers all three of its Toaster updates and cancels none of
them on unmount:

1. A **new** toast reaches the Toaster through `setTimeout(() => flushSync(...))`.
2. A **dismissal** is applied in a `requestAnimationFrame`.
3. A dismissed toast is **removed** by `setTimeout(removeToast, 200)` (`TIME_BEFORE_UNMOUNT`).

That test file dismissed its toasts in `afterEach` without waiting, so the shared `cleanup()`
unmounted the Toaster with the removal timer still pending. In the file's last test, the timer can
fire after Vitest has torn down the jsdom environment. React then reads `window` and throws.

It never reproduced in 12 isolated runs, because it needs a slow runner to lose the race. A
temporary probe counting sonner's 200 ms timers is deterministic, though: every run of the file
ended with **1** still pending at teardown.

### What changed

- New [`src/test/drain-toasts.ts`](../../ui/manager/src/test/drain-toasts.ts), awaited by the
  shared `afterEach` in `setup.ts` before `cleanup()`. Every deferred callback comes from a
  *mounted Toaster's* subscription, so `drainToasts()` gates on the Toaster, not on what it is
  showing.
  - **With a Toaster mounted, it drains all three paths:** one macrotask (queued insertions),
    `toast.dismiss()` inside `act`, one animation frame (dismissals), then a wait until no toast
    is left in the DOM (the 200 ms removals).
  - **A Toaster was mounted earlier in the test and the test unmounted it itself:** its
    subscription is gone, so there is nothing to dismiss. The callbacks it scheduled before
    unmounting are still pending, so the drain waits out the longest of them, the 200 ms removal.
    A `MutationObserver` records every Toaster the test mounts, and `takeRecords()` includes
    mounts that were removed again before the drain ran. CodeRabbit caught this case on the PR.
  - **No Toaster this test:** nothing ever subscribed, so nothing can be pending. The drain only
    clears sonner's module-global store, which it does in every case, so one test's toast cannot
    turn up in the next.
  - **Under fake timers** it advances the clock, because RTL's `waitFor` would otherwise poll a
    frozen clock.
  - **Why the Toaster, not the visible toast:** CodeRabbit found three cases on the PR where a
    callback is in flight with nothing visible. The first version of this fix gated on the DOM and
    missed all three:
    - a toast created in a test's last moments;
    - a toast created and dismissed in the same tick;
    - a toast aimed at another `toasterId`, which a Toaster never renders but still schedules a
      dismissal frame for.
- [`src/test/__tests__/drain-toasts.test.tsx`](../../ui/manager/src/test/__tests__/drain-toasts.test.tsx)
  tests the actual contract: no sonner callback outlives the drain. It wraps `setTimeout`,
  `clearTimeout`, `requestAnimationFrame` and `cancelAnimationFrame`, tracks the callbacks
  scheduled from sonner's code (by stack), and asserts none is still pending after
  `drainToasts()`. It covers:
  - each of the three cases above;
  - two where the test unmounts its own Toaster first (a pending insertion, and a dismissal
    queued just before the unmount);
  - a toast already on screen;
  - a store with no Toaster mounted;
  - that the Toaster selector still matches, so a sonner upgrade that changes the container fails
    loudly.

  Run against the previous version, the two newest cases fail, naming the leaked `setTimeout(0)`
  and `requestAnimationFrame`.
- `resource-detail-save-not-live.test.tsx`: its own undrained `toast.dismiss()` is gone.

This covers all four files that render `<Toaster>` (`resource-detail-save-not-live`,
`channel-create-error`, `channel-detail`, `studio-save-flow`), and any later one.

### Verification

- **Probe, the same file:** 1 pending removal timer at teardown before the fix, 0 after, over 3
  runs each. The three sibling files also end with 0. The probe was never committed.
- **Full `npx vitest run --coverage`, as CI runs it:** 427 files and 6,861 tests pass, with no unhandled errors.
  `npm run lint` and `npm run typecheck` pass.
