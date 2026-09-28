## 🧪 test(manager): no sonner timer outlives a test (2026-09-28)

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
  shared `afterEach` in `setup.ts` before `cleanup()`. `drainToasts()` does four things:
  - It first lets a pending insertion land. CodeRabbit caught this case on the PR: a toast created
    in a test's last moments is in sonner's store but not yet in the DOM.
  - It dismisses every toast inside `act`.
  - It waits until sonner has removed them from the DOM, which happens only after the removal timer
    has fired.
  - Under fake timers it advances the clock instead, because RTL's `waitFor` would otherwise poll a
    frozen clock.

  The gate is sonner's own store, which `toast()` updates synchronously, together with the DOM. A
  test that never toasted therefore pays nothing. The drain also clears the module-global store
  between tests.
- [`src/test/__tests__/drain-toasts.test.tsx`](../../ui/manager/src/test/__tests__/drain-toasts.test.tsx)
  covers three cases:
  - a toast created synchronously right before the drain;
  - a toast already on screen;
  - toasts in the store with no Toaster mounted.

  Each checks that nothing changes after the drain returns. Reverting to the first version of the
  fix (DOM-only gate, no insertion flush) fails 2 of the 3.
- `resource-detail-save-not-live.test.tsx`: its own undrained `toast.dismiss()` is gone.

This covers all four files that render `<Toaster>` (`resource-detail-save-not-live`,
`channel-create-error`, `channel-detail`, `studio-save-flow`), and any later one.

### Verification

- **Probe, the same file:** 1 pending removal timer at teardown before the fix, 0 after, over 3
  runs each. The three sibling files also end with 0. The probe was never committed.
- **Full `npx vitest run --coverage`, as CI runs it:** 427 files and 6,861 tests pass, with no unhandled errors.
  `npm run lint` and `npm run typecheck` pass.
