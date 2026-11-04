## Workforce dashboard shows every agent, ordered by your last use (2026-11-03)

**Repo:** EDDI, `ui/manager`

`WorkforceSection` in `pages/workforce/workforce-dashboard.tsx` cut the grouped agent list with `.slice(0, 10)`, so an eleventh agent was silently missing from "Your Digital Workforce" and the counter read "10 experts". Now:

- every agent is listed: the section pages through all descriptors with `useAllAgentDescriptors` (descriptors are per version, so a single page can hold fewer distinct agents than its size);
- if a descriptor page fails to load, the agents already loaded stay visible with an inline "Some experts could not be loaded" notice and a Retry button (`workforce.loadIncomplete`);
- above 12 agents the section collapses to the first 12 with a "Show all N" toggle, and expands into a height-capped scroll area (`workforce.showAll`, `workforce.showLess`, all 11 locales);
- order is most recently used by this user first (new `use-recent-agents.ts`, per browser in `localStorage`, recorded by `workforce-chat.tsx` once the chat has resolved which agent is open), then last modified for agents never used. The Manager's agent list keeps its last-modified order.

The Workforce sidebar keeps its own compact top-10.
