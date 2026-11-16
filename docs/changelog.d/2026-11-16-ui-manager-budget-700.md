## Manager first-load budget raised to 700 KB (2026-11-16)

**Repo / branch:** labsai/EDDI, carried by `feat/ui-eddi-conversation` (#1280).

**What changed:** in `ui/bundle-budget.json`, the first-load budget of the Manager pages (`manage.html`, `workforce.html`, `welcome.html`) goes from 660 KB to 700 KB gzip, by the owner's decision. The chat widget's budget stays at 170 KB.

**Why:** #1280 moves `@eddi/conversation` into the Manager bundle and measured 660.3 KB, hours after the budget had been raised to 660 KB. The PRs stacked on it (#1283 to #1333) keep adding Workforce features, so the owner chose one raise covering the stack instead of one per PR.

**Design decisions:** 700 KB is still about 44 KB below the 743.8 KB phase-0 baseline. Bringing the first load back down (lazy-loading what grew it) stays with the frontend-unification plan, and is worth doing once the stack has landed.
