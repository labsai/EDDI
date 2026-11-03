## Workforce dashboard shows every agent, ordered by your last use (2026-11-03)

**Repo:** EDDI, `ui/manager`

`WorkforceSection` in `pages/workforce/workforce-dashboard.tsx` cut the grouped agent list with `.slice(0, 10)`, so an eleventh agent was silently missing from "Your Digital Workforce" and the counter read "10 experts". Now:

- every agent is listed; the descriptor fetch is raised from 50 to 200 (descriptors are per version, so 50 rows could hold fewer than 50 agents);
- above 12 agents the section collapses to the first 12 with a "Show all N" toggle, and expands into a height-capped scroll area (`workforce.showAll`, `workforce.showLess`, all 11 locales);
- order is most recently used by this user first (new `use-recent-agents.ts`, per browser in `localStorage`, recorded when `workforce-chat.tsx` opens an agent), then last modified for agents never used. The Manager's agent list keeps its last-modified order.

The Workforce sidebar keeps its own compact top-10.
