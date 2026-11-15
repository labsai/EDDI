## Manager: migrate to msw 3 (2026-11-01)

- Repo/branch: EDDI, `fix-1156` (Dependabot PR #1156).
- `onUnhandledRequest` is `onUnhandledFrame` in msw 3, and a callback now receives `{ frame, defaults }` instead of `(request, print)`. Renamed the option in `src/main.tsx`, `src/test/setup.ts` and the tests that call `server.listen`. Added `recordUnhandledApiFrame` (`src/test/mocks/unhandled-api-recorder.ts`), a thin adapter that unwraps HTTP frames and reuses `recordUnhandledApiRequest`.
- Regenerated `ui/manager/public/mockServiceWorker.js` with `npx msw init public --save`.
- Audit of the other v3 breaking changes: nothing in `src` or `e2e` uses `worker.stop()`, cookie reads in handlers, GraphQL, WebSocket handlers or `handleRequest`; `server.close()` stays synchronous in Node.
- Five tests relied on msw 2 timing or on `Request.referrerPolicy` and were fixed rather than skipped: `update-check-card` now asserts the `referrerPolicy` passed to `fetch` (msw 3 hands handlers a reconstructed Request without it); `feedback`, `alerts`, `agent-editor-unsaved` and `audit` now wait for the settled state instead of asserting on the first render that satisfies a `findBy`.
- Verified: `tsc -b`, lint and `npm run build` clean; the affected test files pass. The full vitest run is left to CI.
