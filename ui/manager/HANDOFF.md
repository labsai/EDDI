# EDDI Manager Project Handoff

## Current Status (v6.0.0 Feature Work)

### Completed Phases
- Triggers UI/UX Refactoring: Replaced text inputs with dynamic `AgentPicker` combobox using `useDebounce` and backing API calls. Solved 404 proxy issues inside Vite server.
- Triggers Table Refactoring: Enabled the list filter to filter agent deployments not just by `agentId` but also by resolved `name` leveraging `useAgentDescriptors` and `Map`. 
- Bugfix: Fixed TypeScript build error in `src/pages/triggers.tsx` where `AgentDescriptor` incorrectly accessed an undefined `id` property instead of relying on `parseResourceUri(a.resource).id`.

### Test Counts
- 61 Files passing
- 633 Tests passing (`npm run test`)

### Last Commit Focus
- `fix(triggers): use parseResourceUri to evaluate agent ID from descriptor`

