## 🔀 fix(docs-site): docs.labs.ai redirects with a 301, kept forever; repair the docs build on main (2026-10-27)

**Repo:** EDDI (`fix/docs-site-301-redirects`)

### What changed and why

**301, not 308.** The decision for the docs move is now final: `docs.eddi.technology` is canonical, and `docs.labs.ai` answers every path with a **301** to the same path there, for good (released images print docs.labs.ai links forever). The generated redirects used Vercel's `permanent: true`, which sends a 308. That is just as permanent, but 301 is what was decided and what every crawler and HTTP client has always read as a move. [`redirects.mjs`](../../docs-site/scripts/redirects.mjs) now writes an explicit `statusCode: 301` on all 434 rules (`REDIRECT_STATUS`), and a test fails if any rule loses it or uses `permanent` again. Vercel's own `trailingSlash` redirect (`/x` → `/x/`) stays a 308, because it cannot be configured; [`check-live.mjs`](../../docs-site/scripts/check-live.mjs) therefore checks the status of the hop that *leaves* docs.labs.ai and requires it to be the 301, whichever order Vercel runs the two in.

**`main`'s Docs Site check was red.** #1136 (conversation ratings) linked `mcp-server.md` to `conversation-ratings.md#mcp-tools-and-the-platform-operator`, a section that page does not have, so the docs build has failed on every push to `main` since. The link now points to the section that describes the same scoping — "Analyzing and triaging (the people running the agent)". The ratings page's status note says it covers the MCP rating tools, but it has no section on them; adding one is for the ratings work, not this fix.

### Verification

- `npm test` in `docs-site/`: 55 tests, including the new 301 test.
- `check-live` against a local stand-in for Vercel that applies `vercel.json`, with a second port posing as docs.labs.ai: every legacy URL ends on the right page on the canonical host, leaving the legacy host with a 301. With the host rule switched back to `permanent` (308), the legacy checks fail.

### Next

The cutover itself: phase 5 of [`planning/docs-site-migration-plan.md`](../../planning/docs-site-migration-plan.md).

```decision-log
| 2026-10-27 | docs.labs.ai redirects to docs.eddi.technology with an explicit 301 (not Vercel's default 308), on every path, kept forever | Domain decision for the GitBook replacement; released images print docs.labs.ai links indefinitely | `permanent: true` (308) — equally permanent, but not the decided code |
```
