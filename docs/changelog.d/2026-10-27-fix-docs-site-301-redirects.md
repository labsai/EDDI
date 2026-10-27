## 🔀 fix(docs-site): docs.labs.ai redirects with a 301, kept forever; repair the docs build on main (2026-10-27)

**Repo:** EDDI (`fix/docs-site-301-redirects`)

### What changed and why

**301, not 308.** The decision for the docs move is now final: `docs.eddi.technology` is canonical, and `docs.labs.ai` answers every path with a **301** to the same path there, for good (released images print docs.labs.ai links forever). The generated redirects used Vercel's `permanent: true`, which sends a 308. That is just as permanent, but 301 is what was decided and what every crawler and HTTP client has always read as a move. [`redirects.mjs`](../../docs-site/scripts/redirects.mjs) now writes an explicit `statusCode: 301` on all 434 rules (`REDIRECT_STATUS`), and a test fails if any rule loses it or uses `permanent` again. Vercel's own `trailingSlash` redirect (`/x` → `/x/`) stays a 308, because it cannot be configured; [`check-live.mjs`](../../docs-site/scripts/check-live.mjs) therefore checks the status of the hop that *leaves* docs.labs.ai and requires it to be the 301, whichever order Vercel runs the two in.

**The Vercel project now builds from the repository root.** The `eddi-docs` project was created with Root Directory `docs-site`, and Vercel checks that the Root Directory exists *before* any ignore step runs — so every push to a branch without `docs-site/` failed, with a failure email: the orphan `metrics` data branch that `docker-pull-notify.yml` writes every 10–35 minutes, and every branch cut before the site existed. The Root Directory is now the repository root (it exists on every branch), `vercel.json` moves to the repository root (Vercel reads it from the Root Directory) with `cd docs-site && …` commands and output `docs-site/.generated/dist`, and the ignore step `test ! -d docs-site || git diff --quiet HEAD^ HEAD -- docs-site docs PRIVACY.md vercel.json` skips a branch without the site and any commit that does not touch the docs. The project settings carry the same commands, for branches without the root `vercel.json` (`main` until this merges). The Docs Site workflow now also watches `vercel.json`, and a test pins the layout.

**Only `main` deploys.** Decided the same day, after branches that had merged a broken `main` (the ratings anchor below) started failing their previews and emailing: the ignore step now begins with `[ "$VERCEL_ENV" != production ] ||`, so every non-production deployment — every branch and pull request — is skipped. Pull requests keep the `Docs Site` check, which builds the site and validates every link; the project setting carries the same command. On `main`, "did the docs change?" is asked against the last successful deployment (`$VERCEL_GIT_PREVIOUS_SHA`), not `HEAD^`, so a docs build Vercel cancelled is still published by the next commit; without a previous deployment in the clone, it builds. (Vercel caps the project setting at 256 characters, so the command is written compactly; a test holds it under the cap.)

**`main`'s Docs Site check was red.** #1136 (conversation ratings) linked `mcp-server.md` to `conversation-ratings.md#mcp-tools-and-the-platform-operator`, a section that page does not have, so the docs build has failed on every push to `main` since. The link now points to the section that describes the same scoping — "Analyzing and triaging (the people running the agent)". The ratings page's status note says it covers the MCP rating tools, but it has no section on them; adding one is for the ratings work, not this fix.

### Verification

- `npm test` in `docs-site/`: 55 tests, including the new 301 test.
- `check-live` against a local stand-in for Vercel that applies `vercel.json`, with a second port posing as docs.labs.ai: every legacy URL ends on the right page on the canonical host, leaving the legacy host with a 301. With the host rule switched back to `permanent` (308), the legacy checks fail.

### Next

The cutover itself: phase 5 of [`planning/docs-site-migration-plan.md`](../../planning/docs-site-migration-plan.md).

```decision-log
| 2026-10-27 | docs.labs.ai redirects to docs.eddi.technology with an explicit 301 (not Vercel's default 308), on every path, kept forever | Domain decision for the GitBook replacement; released images print docs.labs.ai links indefinitely | `permanent: true` (308) — equally permanent, but not the decided code |
```
