## 🐛 fix(manager): one layout for action clusters — the pending-approvals Group row no longer wraps raggedly (2026-11-19)

**Repo:** EDDI (`fix/manager-row-actions-layout`, `ui/manager`, `ui/packages/app-kit`)

### The bug

On the global pending-approvals screen a Group row showed `Review ⌄ · Approve · Reject` on one line and `Cancel · View` on a second, with uneven gaps, a hand-rolled `View` link (different height and padding from the buttons) and a taller row than the Conversation rows above it. Cause: the Actions cell was a `flex-wrap gap-1` cluster in a `<td>` the table was free to squeeze, the five controls were a flat list in a different order per row type, and `View` was a bare `<Link>`/`<span>` styled by hand.

### What changed

- **New shared `RowActions` / `RowActionsGroup`** (`@eddi/app-kit/components/ui/row-actions`). `RowActions` is one end-aligned, wrapping row with a single `gap-2` whose children never shrink or break their label; `RowActionsGroup` keeps a set of buttons on one line at one height (`h-8`), so when space runs out *whole groups* wrap, never a lone button.
- **Approvals table** (`pages/approvals.tsx`): the secondary actions (Review, View) are one group and always come first, the decisions (Approve, Reject, Cancel) one group and always last, in the same order on every row type. `View` is now a real `Button` (`asChild` link, or a disabled `Button` that keeps `aria-disabled`). The cell is `w-px whitespace-nowrap`, so the table gives the column its natural width, and the Reason column is capped at 15rem. At 1400 px every row is a single line; from md to xl a group row wraps deliberately into two end-aligned lines; below md the rows are cards and the buttons start-align.
- **Audit of every other row-action cluster in the Manager**, same fix applied where it was broken: ratings table (`flex-wrap gap-1`, three labelled buttons wrapped raggedly), alert events (`gap-1`, no wrap), secrets, variables, schedules and the schedules failed-fires panel (`gap-1` / `gap-1.5`, inconsistent), and the main button row of the HITL approval banner (used by the per-agent and per-group approval surfaces; it had no wrap, so `Approve with edits` + `Reject` + `Cancel` overflowed in long-label languages).
- **Secrets**: the Actions column was off-screen behind the table's horizontal scroll at 1400 px. It is now `sticky end-0`, and its three buttons collapse to icons (with `title`/`aria-label`) below 2xl.
- Left alone on purpose: the icon-only clusters in agents, groups, workflows and resource lists (`inline-flex gap-1`, all the same `h-7 w-7` size, no label to wrap), the agent card footer (already wraps by item), and the per-call/per-task verdict toggles inside the approval banner.

### Verification

Run in the Manager's MSW mock mode (the mock data already had a Group pending approval) with a real browser at 1400 / 1024 / 900 / 375 px, in `en`, `de` and `ar` (RTL), light and dark: no cluster wraps at 1400 px except the intentional ratings/approvals group wrap; no mixed button heights inside a cluster; approvals, secrets, variables, schedules, alerts and feedback checked. Before/after screenshots are in the session notes folder (`row-actions-screenshots/`); the "before" of the Group row is the one in the bug report.

Tests: `approvals-row-actions-layout.test.tsx` (the `RowActions` class contract, and a Group row laid out as `[Review, View] + [Approve, Reject, Cancel]` like a Conversation row's `[Approve, Reject, Cancel]`), and the existing approvals, secrets, variables, schedules, alerts, feedback and HITL suites (852 tests) still pass. `npm run typecheck`, `npm run lint` and `npm run i18n:check` pass. No strings were added.

### Follow-up

The approvals, secrets, schedules and ratings tables are still wider than a tablet at 1024 px and scroll horizontally (the approvals table already did; it is deliberate and documented in the page). A column-priority/responsive-table primitive would be the next step.
