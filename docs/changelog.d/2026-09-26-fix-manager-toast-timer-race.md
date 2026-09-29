## 🧪 fix(manager): a passing test file no longer fails UI Manager Checks on a late toast timer (2026-09-26)

**Repo:** EDDI (`fix/manager-toast-timer-race`) — `ui/manager/` test only

### The failure

`UI Manager Checks` sometimes exited 1 while every test passed (426 files, 6858 tests on
PR 847's run 36271130343), on one unhandled error:

```text
ReferenceError: window is not defined
 ❯ resolveUpdatePriority  react-dom-client.development.js
 ❯ dispatchSetState
 ❯ sonner/dist/index.mjs  (the Toast's unmount timer)
This error originated in "src/pages/__tests__/resource-detail-save-not-live.test.tsx"
```

### Cause

`resource-detail-save-not-live.test.tsx` renders `<Toaster duration={600_000} />` and calls
`toast.dismiss()` in `afterEach`, because sonner's toast store is module-global and a toast
left over from one test breaks the next. A dismissed toast is removed only after sonner's
`TIME_BEFORE_UNMOUNT` timer (200 ms), and that callback updates React state. Between tests
the next test keeps the environment alive long enough; after the file's **last** test
nothing waits for it, so whether it fires before or after jsdom is torn down depends on
runner timing. Fired afterwards, React's update-priority lookup reads `window` and throws.

Instrumenting sonner locally showed it plainly: before the fix the last test's timer was
still pending when the file finished (3 started, 2 fired); after it every timer fired with
`window` present (11 of 11, at the real 200 ms and at a widened 800 ms).

### Fix

`afterEach` still dismisses, then waits until no `[data-sonner-toast]` element remains —
which only happens once the unmount timer has run — so the state update lands while jsdom
is up. Nothing else changed. The three other test files that render `<Toaster>` never call
`toast.dismiss()`; their toasts unmount with the component, which starts no removal timer,
so they do not have this race.

### Verification

- The file run 20 times in a row: 20 passed, no "Unhandled Errors" section.
- `npx eslint` on the file and `npx tsc -b`: clean.
