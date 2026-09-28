## 🧪 test(manager): no sonner removal timer outlives a test (2026-09-28)

**Repo:** EDDI (`test/manager-toast-timer-leak`)

### What was wrong

CI's `UI Manager Checks` failed once (run 36418007466, on PR #873) with all 426 files and 6,858
tests passing, because Vitest caught one unhandled error: `ReferenceError: window is not defined`,
thrown from React's `dispatchSetState` inside a sonner timer, and attributed to
`resource-detail-save-not-live.test.tsx`.

The cause is in sonner 2.0.8 itself. A toast that is dismissed, explicitly or when its `duration`
runs out, is removed by a bare `setTimeout(removeToast, 200)` (`TIME_BEFORE_UNMOUNT`), and nothing
cancels that timer on unmount. That test file dismissed its toasts in `afterEach` without waiting,
so the shared `cleanup()` unmounted the Toaster with the timer still pending. In the file's last
test, the timer can fire after Vitest has torn down the jsdom environment. React then reads
`window` and throws.

It never reproduced in 12 isolated runs, because it needs a slow runner to lose the race. A
temporary probe counting those 200 ms timers is deterministic, though: every run of the file ended
with **1** still pending at teardown.

### What changed

- [`src/test/setup.ts`](../../ui/manager/src/test/setup.ts): new `drainToasts()`, awaited in the
  shared `afterEach` before `cleanup()`.
  - If a sonner toast is on screen, it dismisses it inside `act` and waits for sonner to remove it
    from the DOM, which happens exactly when the removal timer has fired.
  - Under fake timers it advances the clock instead, since RTL's `waitFor` would otherwise poll a
    frozen clock.
  - A file that never renders a Toaster finds nothing and pays nothing.

  This covers all four files that render `<Toaster>` (`resource-detail-save-not-live`,
  `channel-create-error`, `channel-detail`, `studio-save-flow`), and any later one.
- `resource-detail-save-not-live.test.tsx`: its own undrained `toast.dismiss()` is gone. The
  shared hook now does the same thing and waits.

### Verification

- **Probe, the same file:** 1 pending timer at teardown before the fix, 0 after, over 3 runs
  each. The three sibling files also end with 0. The probe was never committed.
- **Full `npx vitest run --coverage`, as CI runs it:** 426 files and 6,858 tests pass, with no
  unhandled errors. `npm run lint` and `npm run typecheck` pass.
