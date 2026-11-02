## chore(ui): review cleanup — RTL icon mirroring, dead code, chat query keys (2026-10-31)

**Repo:** EDDI (`chore/review-ui-cleanup`) — Manager (`ui/manager`) and Chat UI (`ui/chat`) only. The behavioural fixes from the same review are on `fix/review-ui`.

### What changed and why

- **RTL:** directional arrows and chevrons carry `rtl:rotate-180` — 59 icons across the Manager plus the three page-local `BackLink`s (`agent-detail`, `conversation-detail`, `workflow-detail`). In Arabic they pointed the wrong way: "back" arrows pointed forward and collapsed chevrons pointed at the content. [`rtl-directional-icons.test.ts`](../../ui/manager/src/test/rtl-directional-icons.test.ts) scans the sources so a new left/right arrow or chevron without an `rtl:` variant fails the unit run.
- **Dead code removed:** `use-coordinator.ts` and `createCoordinatorEventSource`, `useUpdateAgentWorkflows`, `useDeployAgent`, `formatDateOnly`, `mapIfArray`, `debounced-inputs.tsx`, `cascadeHasErrors`, `EVENT_STATES`/`MATCH_OPS`, the Variables page's unreachable `copyRef` template branch, six unreferenced type aliases, and the Chat UI's `SUPPORTED_LOCALES`, with the tests that only exercised them.
- **Query keys:** `chatKeys` in [`query-keys.ts`](../../ui/manager/src/lib/query-keys.ts) replaces nine hand-typed `["chat","deployedAgents"]` keys, and the `agentKeys` header no longer claims `agentKeys.all` clears the detail, descriptor and prompt entries.

### Design decisions

- Not changed: the SSE payload interfaces in `hitl.ts`/`groups.ts` that nothing references (they document the wire format next to their used siblings), the remaining ~100 single-hook literal `queryKey` arrays (the header allows a key read and invalidated inside one hook), and `getDeadLetters`/`replayDeadLetter`/`discardDeadLetter`/`purgeDeadLetters` (still wrapped by tests).
- No behaviour change is intended: the branch is independent of `fix/review-ui` and merges cleanly with it in either order.

### Tests

`npm run typecheck`, `npm run lint`, `npm run i18n:check` and the full Vitest run in `ui/manager`; `npm run typecheck`, `npm run lint` and `vitest run` in `ui/chat`.
