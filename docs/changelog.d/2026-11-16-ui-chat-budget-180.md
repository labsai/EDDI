## Chat widget first-load budget raised to 180 KB (2026-11-16)

**Repo / branch:** labsai/EDDI, carried by `refactor/manager-chat-on-conversation` (#1283).

**What changed:** in `ui/bundle-budget.json`, the chat widget's first-load budget (`chat.html`) goes from 170 KB to 180 KB gzip, by the owner's decision. The Manager pages stay at 700 KB.

**Why:** #1283 measured 170.9 KB with every other check green, and two PRs still stacked on it change the widget (#1308: streaming, edit, Mermaid; #1331: the embed launcher).

**Design decisions:** 180 KB is still about 45 KB below the 224.8 KB phase-0 baseline. Bringing the first load back down stays with the frontend-unification plan.
