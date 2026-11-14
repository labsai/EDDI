## Manager first-load budget raised to 650 KB (2026-11-14)

**Repo:** EDDI · **Branch:** `fix/alert-rule-cap` (#1357)

**What changed:** in `ui/bundle-budget.json`, the first-load budget of the Manager pages (`manage.html`, `workforce.html`, `welcome.html`) goes from 630 KB to 650 KB gzip. The chat widget's budget stays at 170 KB (it measures 160.6 KB).

**Why:** #1272 set 630 KB, measured against a `main` that did not yet include the UI PRs merged alongside it. Once all of them were in, the Manager pages measured 641.1 KB and the `Bundle budget` step in UI Manager Checks failed. Main's own CI never showed it, because its run stopped earlier, at the broken test compile. The budget file says raising a budget is a decision, not a fix; the repository owner made it, choosing to raise the budget rather than shrink the bundle now.

**Design decisions:** 650 KB leaves about 9 KB of headroom over the measured 641.1 KB, still roughly 94 KB below the 743.8 KB phase-0 baseline. Work to get back under 630 KB (lazy-loading whatever grew the first load) is left to the frontend-unification plan.
