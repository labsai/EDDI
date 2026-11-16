## Manager first-load budget raised to 660 KB (2026-11-16)

**Repo / branch:** labsai/EDDI, carried by `feat/agent-signing-key-provisioning` (#1365) so the PRs waiting on it can merge.

**What changed:** in `ui/bundle-budget.json`, the first-load budget of the Manager pages (`manage.html`, `workforce.html`, `welcome.html`) goes from 650 KB to 660 KB gzip, by the owner's decision. The chat widget's budget stays at 170 KB.

**Why:** `main` sat just under 650 KB, so every PR adding a little UI or operator-prompt text crossed it: #1273 and #1365 measured 651.1 KB, #1375 650.9 KB and #1278 650.7 KB, each with everything else green.

**Design decisions:** 660 KB is still about 84 KB below the 743.8 KB phase-0 baseline. Getting the first load back down (lazy-loading what grew it) stays with the frontend-unification plan; this raise only buys headroom for the PRs already in flight.
