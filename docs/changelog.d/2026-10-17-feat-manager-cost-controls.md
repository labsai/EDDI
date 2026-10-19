## 💰 feat(manager): cost controls — per-conversation ceiling and a legible tenant budget (2026-10-17)

**Repo:** EDDI (`feat/manager-cost-controls`) — Manager UI for the backend in the tenancy cost-metering PR (`feat/tenancy-cost-metering`)

### What changed and why

Two cost settings shipped on the backend without a screen, and the tenant budget was still presented as a plain number.

- **Agent detail → new "Limits" section** ([`agent-config-sections.tsx`](../../ui/manager/src/components/editors/agent-config-sections.tsx)) with `maxCostPerConversationUsd`. Empty means unlimited and **removes the field** from the document (never `null` or `-1`); `0` is kept as a real ceiling meaning no priced spend at all; a negative or half-typed value is flagged and not written. The section says that only priced models count. Parsing lives in [`cost-ceiling.ts`](../../ui/manager/src/lib/cost-ceiling.ts).
- **Tenant quotas page.** The monthly-cost field now says what it counts (priced spend only), and the usage card gains a *Monthly cost budget* status that names which of four states the tenant is in: no budget, budget set but enforcement off, budget reached (models are serving fallback answers, tool calls refused), or spend versus remaining. It lists what is not metered and points at the `eddi.llm.spend.refused` metric — there is no REST endpoint for recent refusals, so none is shown.
- All 14 new strings are in all 11 locales.

### Design decisions

- A new section rather than a field inside an existing one: the ceiling bounds the whole agent whatever its memory, session or approval settings are.
- The budget block states the situation instead of leaving it to be inferred from a progress bar that turns red at 90%.

**Not done:** recent spend refusals on the quotas page need a backend endpoint; today they are only a metric and a per-step marker (`llm:spend_refused:<taskId>`).
