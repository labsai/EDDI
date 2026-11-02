# EDDI documentation — from GitBook to an Astro + Starlight site on Vercel

Status: **revision 2, phases 1–4 done; 5 and 6 need the Vercel project and DNS** (2026-10-27). Roadmap §3 item 14 ("Website — Astro
+ Starlight documentation site"). Decided: domain option A (`docs.eddi.technology`, docs.labs.ai
redirects permanently).

## 1. Goal and invariants

GitBook syncs `docs/` from this repo one way (Git Sync, no `.gitbook.yaml`, no commits from
GitBook in history) and serves it at docs.labs.ai — a `labs.ai` subdomain we own, CNAMEd to
`hosting.gitbook.io`. We replace GitBook with an Astro + Starlight build of the same Markdown,
deployed on **Vercel, the same way the marketing site is**.

Invariants — the migration is wrong if it breaks any of these:

1. **`docs/` stays plain GitHub-flavoured Markdown and stays the only source.** GitHub renders
   it, `McpDocResources` serves the top-level pages raw to MCP clients (`eddi://docs/*`, copied
   by the Dockerfile `docs` stage), and `DocumentationLinksTest` checks it. No MDX, no Starlight
   frontmatter, no components in `docs/`. Everything site-specific happens in a build step that
   *reads* `docs/`.
2. **`docs/SUMMARY.md` stays the table of contents.** `rotate-changelog.py` and the nightly
   collate job rewrite it; "a page is published if and only if it is in SUMMARY" stays the rule.
3. **Every URL that has worked on docs.labs.ai keeps working, permanently.** Released EDDI
   images print docs.labs.ai links in startup banners forever, and the website links to it.
4. **The machine-readable surface survives**: GitBook serves `/llms.txt`, `/llms-full.txt` and
   a raw `.md` for every page (`/…/mcp-server.md`), and the website links to those `.md` URLs.
5. Authoring stays "edit a `.md` in a PR". No second place to write docs.

## 2. Facts this plan rests on (verified 2026-10-26)

**Hosting of the marketing site** (`labsai/EDDI-LABS-AI-Website`):

- Plain **Astro 7 + Tailwind 4**, *not* Starlight (AGENTS.md's ecosystem row is wrong on this).
- Served by **Vercel** via its Git integration at `eddi.technology`; `eddi.labs.ai` 301s there.
  Its `deploy.yml` (GitHub Pages) and `CNAME` are legacy — Pages has no custom domain.
- `vercel.json` carries the security headers (CSP, HSTS, …) that its AGENTS.md ties to the
  OpenSSF Gold badge. Analytics (Umami + GA4) load only after cookie-banner consent.
- Its AGENTS.md: "Do NOT change the domain without coordination", and guides must link to the
  docs for reference material (`docsLinks` frontmatter).

**The docs:**

| Fact | Consequence |
| --- | --- |
| 88 top-level files, ~95 pages in SUMMARY | Small; a page-by-page fidelity pass is feasible |
| ~1,200 relative `.md` links; ~1,000 `../` links leaving `docs/` (to `src/`, `PRIVACY.md`, …) | Link rewriting is the core of the build step |
| 517 in-doc anchor links, **0 broken** under GitHub heading slugs | Astro also slugs headings with `github-slugger`, so anchors carry over as they are |
| Title is the first `# H1`; there is no frontmatter | Build step injects `title` and drops the H1 |
| No GitBook syntax left: the `{% hint %}`, `{% file %}`, `{% raw %}` hits in `docs/changelog/2026-03.md` and `2026-04.md` are all inside inline code (the March cleanup that removed them) | Nothing to convert |
| Mermaid in 2 pages | Needs a mermaid integration |
| No local images | No asset migration |

**URLs in the wild:**

- Current sitemap: 96 URLs, all **section-prefixed** (`/security-and-compliance/secrets-vault`).
- Wayback Machine: 112 distinct URLs that once returned 200. Older ones are **flat**
  (`/docker`, `/metrics`, `/architecture`, `/httpcalls`) and older still are **bot-era**
  (`/creating-your-first-chatbot`, `/bot-manager-gui`, `/advanced-concepts/bot-father-*`,
  `/deployement-management-of-chatbots`, `/facebook-channel-setup`, …).
- The website links **38 times** to `.md` URLs (`/protocols-and-integration/mcp-server.md` ×30,
  `/deployment-and-infrastructure/kubernetes.md` ×13, …) and 26 times to
  `/security-and-compliance/secrets-vault`.
- This repo links to flat URLs that **404 today**: `VaultStartupBanner` → `/secrets-vault`,
  `ComplianceStartupChecks` → `/hipaa-compliance`, Manager `coordinator.tsx` → `/clustering`.
  Plus the root URL in `README.md`, `CONTRIBUTING.md`, the Manager sidebar,
  `integrations/claude-plugin/.claude-plugin/plugin.json` and `.github/ISSUE_TEMPLATE/config.yml`.

**Packages** (npm, current): `astro` 7.4.2, `@astrojs/starlight` 0.42.7 (peer `astro ^7.2.10`),
`starlight-links-validator` 0.27.0, `starlight-llms-txt` 0.12.0, `astro-mermaid` 2.2.0. All
compatible with each other and with the website's Astro 7.

## 3. Decisions

### 3.1 Domain — decided: A

docs.labs.ai's DNS has to move off GitBook in either case, so the choice costs the same:

| Option | For | Against |
| --- | --- | --- |
| **A. `docs.eddi.technology` canonical**, docs.labs.ai → 301 to it, kept forever | Matches the product domain the website already moved to (`eddi.labs.ai` → `eddi.technology`); one brand | Touches ~10 links in this repo and the website's `docsLinks`; the two-hop redirect for old deep links |
| B. Keep `docs.labs.ai` canonical | Zero link churn | Docs stay on the old brand domain while everything else moved |

Recommendation: **A**, done as part of the cutover, not later — changing the canonical domain
twice is worse than once. Either way the plan below is the same except for the hostname.

### 3.2 URL scheme — flat, by file name

`docs/secrets-vault.md` → `/secrets-vault/`. Sections live in the sidebar, not the URL, so
moving a page between SUMMARY sections never breaks a link — the fragility the section-prefixed
GitBook URLs have. It is also what the backend and Manager already assume, and it is what
docs.labs.ai itself used before the sections were introduced (Wayback), so the old flat URLs
come back to life for free.

Special cases: `README.md` → `/`; `creating-your-first-agent/README.md` →
`/creating-your-first-agent/`; `monitoring/monitoring-guide.md` → `/monitoring-guide/`;
`templates/baa-template.md` → `/baa-template/`; `how-to....md` → `/faq/`; `../PRIVACY.md` →
`/privacy/`; `changelog/2026-10.md` → `/changelog/2026-10/`. The build step **fails on a slug
collision**, so flattening can never silently shadow a page.

### 3.3 Where the source lives — this repo, `docs-site/`

A docs PR gets the site build and link validation in the same PR. Folding the docs into the
website repo was considered and rejected: that site is plain Astro with an 11-locale i18n
setup and its own layouts; bolting Starlight on would fight both, and a docs PR here could no
longer be validated before merge. `docs-site/` borrows the website's colours, fonts and logo
(Starlight custom CSS) and links back to it in the header, so the two read as one product.

`docs-site/` is a standalone npm project, **not** wired into Maven — `./mvnw package` and the
jar are unaffected.

### 3.4 Deployment — Vercel, a second project next to the website

- Vercel project **`eddi-docs`** (team `eddi`, created 2026-10-27) on `labsai/EDDI`. **Root Directory: the
  repository root**, not `docs-site` — revised 2026-10-27, see `docs-site/README.md` → Deployment: with
  `docs-site` as Root Directory, Vercel failed (and emailed) every push to the orphan `metrics` data
  branch before any ignore step could run. Originally planned with "include files
  outside the root directory" enabled (the build reads `../docs` and `../PRIVACY.md`).
- **Only `main` deploys** (decided 2026-10-27): the `ignoreCommand` in the root `vercel.json` (and
  the same command in the project settings) skips every non-production deployment, so branches
  and PRs get no Vercel build — the `Docs Site` check validates them instead. On `main` it skips
  the build unless `docs/`, `docs-site/` or
  `PRIVACY.md` changed — `labsai/EDDI` gets far more Java PRs than docs PRs.
- Vercel's PR comments switched off in the project settings; the deployment status alone is
  enough, and this repo's PRs already carry ~27 checks.
- Preview deployments for PRs from this repo; fork PRs need a team member's authorisation in
  Vercel, so their *validation* runs in GitHub Actions (§6), not on Vercel.
- **Plan check:** Vercel's Hobby tier is non-commercial. Confirm which plan the website project
  is on; a second project on the same team costs nothing extra on Pro.
- The output is plain static files — leaving Vercel later is a deploy change only.

## 4. The build

```
docs-site/
  astro.config.mjs          # Starlight; sidebar from .generated/sidebar.json
  (vercel.json lives at the repository root — see §3.4 — headers, redirects, ignoreCommand)
  scripts/sync-docs.mjs     # docs/ → .generated/{content,public,sidebar.json}
  scripts/sync-docs.test.mjs
  legacy-urls.txt           # phase 3: every URL that ever worked (§5)
  src/content.config.ts     # glob loader over .generated/content
  src/styles/eddi.css       # tokens from the website
  src/assets/, static/      # logos; favicon (copied into the public dir by the sync)
  package.json, package-lock.json   # exact versions, Node pinned via .nvmrc
```

All generated output — content, public directory, sidebar and Astro's `dist` — is in one
gitignored `.generated/`, which the repo's Markdown guards (`DocumentationLinksTest`,
`DocumentationAccuracyTest`, `DocumentedRestPathsTest`) skip by name. Otherwise a local build
would feed them a second copy of `docs/` with site-relative links.

**Anchors are checked by the sync, not by `starlight-links-validator`** (phase 1 finding):
under Astro 7's satteri Markdown processor the plugin runs before heading ids are assigned
(`@astrojs/markdown-satteri` pushes its heading-ids step after user plugins), so it reports
every `#anchor` as broken. The sync computes ids from source with `github-slugger`, exactly as
satteri does; on the real docs that matches all 5,235 rendered heading ids, none missing, none
extra. The plugin keeps checking page links, with `errorOnInvalidHashes: false`. Worth an
upstream issue; revisit when either side fixes the ordering.

`sync-docs.mjs` — pure and deterministic, tested with `node:test`; runs as `prebuild` and
`predev`:

1. Parse `docs/SUMMARY.md` → ordered pages + sidebar groups (`## Heading` → group, indented
   item → nested). Emit `sidebar.generated.json`.
2. For each listed page: slug (§3.2); frontmatter `title` (SUMMARY label), `editUrl` (GitHub
   edit link to the *original* `docs/…` path); drop the leading H1.
3. Rewrite links outside fenced code and inline code (the exclusions `DocumentationLinksTest`
   uses): `foo.md#x` → `/foo/#x`; an unpublished `.md`, a non-Markdown file or anything outside
   `docs/` → `https://github.com/labsai/EDDI/blob/main/<path>`.
4. *(dropped — there is no GitBook syntax to convert, see §2)*
5. Write each page's transformed Markdown (with absolute links) to `public/<slug>.md` — this is
   the per-page `.md` endpoint GitBook offers today.
6. Emit `redirects.generated.json` (§5).
7. **Fail** on: a SUMMARY entry whose file is missing; a link to something that is neither a
   published page nor a real file; a slug collision.

Starlight: built-in Pagefind search; `starlight-links-validator` (fails the build on a broken
internal link or anchor); `astro-mermaid`; `starlight-llms-txt` for `/llms.txt` and
`/llms-full.txt`. Pages stay `.md`, never `.mdx`, so the `{…}` in Qute examples is not parsed.

`/llms.txt` lists every page with a link to its `.md` copy (the plugin's `details`, built from
the sidebar), as GitBook's does. `/llms-full.txt` is 7.7 MB on GitBook today, almost all of it changelog. `starlight-llms-txt`
0.12's `exclude` applies to `/llms-small.txt` only, so the changelog is excluded there and
`/llms-full.txt` keeps it, as GitBook's does. If the full file should drop it too, that needs
a post-build filter or an upstream option — not worth blocking on.

### Security headers

Copy the website's `vercel.json` header set (HSTS, nosniff, frame-deny, referrer, permissions),
with a CSP written for this site rather than copied: Pagefind runs WebAssembly, so
`script-src` needs `'wasm-unsafe-eval'`. No HubSpot, so none of its hosts. Verify with the
browser console on the preview deployment before cutover — a CSP that blocks search fails
silently for readers.

### Analytics

GitBook gave us page analytics. If we want them: Umami only, behind the same consent model as
the website (off by default). Recommendation: launch **without** analytics, decide later — it
keeps the CSP minimal and the docs cookie-free.

## 5. Redirects

[`docs-site/legacy-urls.txt`](../docs-site/legacy-urls.txt) lists every URL docs.labs.ai has
served: today's sitemap (96), the Wayback Machine's record (older flat and chatbot-era URLs) and
the links in this repo and the website. A line is a path, or a path and a target where the
default rule cannot work. The default: a section root goes to its section's first page, anything
else to the longest trailing part that is a page slug
(`/agent-configuration/langchain/llm-parameters` → `/llm-parameters/`). Hand-written targets:
chatbot-era names → their agent-era pages, the Agent/Bot Father pages → `/agent-manager-gui/`
(the Platform Operator replaced it in 6.3.0), pages with no successor → the closest page or `/`.

[`scripts/redirects.mjs`](../docs-site/scripts/redirects.mjs) generates the `redirects` of
the root `vercel.json` (`npm run redirects`), in this order:

1. host `docs.labs.ai`, any path → the same path on `docs.eddi.technology`, **301** (one rule — the
   old domain stays attached to the project for good, because released images print its links);
2. each legacy path in its plain, trailing-slash and `.md` forms → its page or `.md` copy;
3. page slugs containing a dot, without the slash → with it (phase 3 finding: `trailingSlash`
   treats `/upgrading-from-6.4` as a file with extension `.4` and adds no slash, so it 404s);
4. a fallback per SUMMARY section, `/<section>/<slug>` → `/<slug>/`, for GitBook deep links
   nobody recorded. Explicit rules come first, so it cannot shadow them.

Every generated rule is an explicit `statusCode: 301` (decided 2026-10-27: docs.labs.ai → 301,
kept forever). Vercel's `permanent: true` would send 308, which is just as permanent, but 301 is
the code every crawler and client has always read as a move. Vercel's own `trailingSlash`
redirect (`/x` → `/x/`) is a 308 and cannot be configured; `check-live` requires the 301 on the
hop that leaves docs.labs.ai, whichever order the two run in.

The tests fail when a legacy line resolves to nothing, when `vercel.json` is stale, and when
any docs link written in the backend, the UIs, README, CONTRIBUTING, AGENTS, the plugin or
`.github/` does not land on a page — `#anchor` included. That last guard is the check that would
have caught the three 404s in §2. It lives in the docs-site tests rather than in Java, because
it needs the same slug rules and anchors the sync computes; the workflow runs those tests on
every pull request for that reason.

[`scripts/check-live.mjs`](../docs-site/scripts/check-live.mjs) checks a deployment over HTTP:
every page, its no-slash form and `.md` copy, the llms files and every redirect source, each
followed hop by hop to where it must land (693 URLs). Verified locally against a stand-in for
Vercel's static serving that applies `vercel.json` (redirects in order, host rules,
`trailingSlash`, headers): 0 failures — after it found the dotted-slug 404s above and a
generator bug that turned an external `…/CONTRIBUTING.md` target into `…/CONTRIBUTING/`.

## 6. CI and repo guards

- [`.github/workflows/docs-site.yml`](../.github/workflows/docs-site.yml), check **Docs Site**:
  `npm ci && npm test` on every pull request and push to `main` (the repo-wide docs-link guard
  is in those tests), and `npm run build` when `docs/**`, `PRIVACY.md`, `docs-site/**` or the
  workflow changed. No secrets, so fork PRs get it too. Actions pinned by SHA, `contents: read`,
  `NODE_VERSION` declared at the pom's value as `BuildQualityGatesTest` requires of every
  workflow.
- `.github/dependabot.yml`: an `npm` entry for `/docs-site`, with Astro, Starlight and the
  plugins grouped (the plugins declare peer ranges on both). This matters beyond the site: CI's
  Trivy filesystem scan reads every lockfile in the repository, so an unpatched advisory in
  `docs-site/package-lock.json` would fail every backend PR. `npm audit` is clean today.
- **Branding** matches the website and the Manager value for value (`docs-site/src/styles/eddi.css` names the source of each rule; `HeaderActions.astro` and `ThemeToggle.astro` are the website's header right side and toggle). Two deliberate differences: light-mode links use accent-700 (the website's accent-400 is ~1.9:1 there), and Outfit/JetBrains Mono actually load — the live website asks for `Outfit` while fontsource registers `Outfit Variable`, so it renders the system font. That is a one-line fix in the website repo.
- **No `sharp`** (found by Dependency Review on the PR): Astro's default image service needs it,
  and its libvips binaries are LGPL-3.0, which `dependency-review.yml` denies. The site has no
  raster images, so it uses the pass-through image service and `package.json` overrides `sharp`
  with the stub in `docs-site/stubs/sharp`; `scripts/licenses.test.mjs` checks the lockfile
  against the workflow's deny-list. A future raster image that needs resizing reopens this.
- `ci.yml`'s `backend` filter gains `docs-site/**/*.md` (DocumentationLinksTest grades
  `docs-site/README.md`, the same reason `ui/**/*.md` is there), and `BuildQualityGatesTest`
  now also holds `docs-site/.nvmrc` to the pom's `node.version`.
- Not deployed by Actions — Vercel's Git integration deploys, as for the website.

## 7. Phases

| # | Work | State |
| --- | --- | --- |
| 0 | Decide domain (§3.1) | ✅ option A |
| 1 | Scaffold `docs-site/`, theme, `sync-docs.mjs` + tests, sidebar from SUMMARY, `.md` copies, llms.txt | ✅ 2026-10-26 — 94 pages, validator clean |
| 2 | Fidelity against GitBook | ✅ 2026-10-27 — see below |
| 3 | `legacy-urls.txt`, redirect generation, tests; repo docs-link guard; headers and CSP | ✅ 2026-10-27 |
| 4 | `docs-site.yml`, Dependabot, CI filter | ✅ 2026-10-27 — the Vercel project itself is phase 5, step 1 |
| 5 | Cutover — **needs Vercel and DNS access**, checklist below | open |
| 6 | After cutover — below | open |

**Phase 2, how it was checked.** By script, not by eye, over all 94 pages: (a) source against
build — every page renders as many tables and code blocks as its Markdown has, and no Markdown
syntax leaks into the rendered text (unrendered links, bold, table rows, GitBook tags, escaped
HTML); the only two count differences were single-column tables and an indented code block
that the checker had missed and the renderer had not. (b) GitBook against build — the words of
each GitBook page's `<main>` against ours: mean coverage 98.4%, and every gap is GitBook's page
chrome ("For the complete documentation index, see llms.txt…", breadcrumbs) or text that changed
on `main` after the branch was cut. Then by eye in a browser: search, Mermaid, both themes,
phone width, the 404 page.

### Phase 5 — cutover checklist

1. ✅ **Vercel project** `eddi-docs` (team `eddi`, Pro) on `labsai/EDDI`, Root Directory = repository
   root, settings mirroring the root `vercel.json`. Done 2026-10-27.
2. **Preview:** open the project's latest deployment and run
   `npm run check-live -- https://<deployment-url>` in `docs-site/` — it must report 0 failures.
   Open a page with a Mermaid diagram and use search, with the console open: the CSP must not
   block anything (verified locally, not yet on Vercel).
3. **Domains:** add `docs.eddi.technology` (DNS at easyname — a CNAME to the target Vercel shows)
   and `docs.labs.ai` to the project. Lower the TTL of the docs.labs.ai record a day ahead, then
   point it from `hosting.gitbook.io` to Vercel.
4. **Verify live:** `npm run check-live -- https://docs.eddi.technology --legacy-base https://docs.labs.ai`.
   It also requires the hop that leaves docs.labs.ai to be a **301**.
5. **GitBook:** disconnect Git Sync (that removes the `GitBook (docs)` PR check); unpublish the
   space but keep it for 30 days. **Rollback** within that window: point the docs.labs.ai
   record back at `hosting.gitbook.io`.

### Phase 6 — after cutover

- Nothing in this repo *has* to change: docs.labs.ai redirects permanently, and the flat links
  the backend and the Manager print work there for the first time. Moving the literals to
  `docs.eddi.technology` (`VaultStartupBanner`, `ComplianceStartupChecks`, `RestSecretStore`,
  Manager `sidebar.tsx` and `coordinator.tsx`, `README.md`, `CONTRIBUTING.md`, `plugin.json`,
  `.github/ISSUE_TEMPLATE/config.yml`) saves a hop and can wait for a quiet moment; the
  docs-link guard covers both domains.
- `.claude/skills/ship-pr/SKILL.md`: drop `GitBook (docs)` from the checks list.
- The website repo: point its `docsLinks` at the new URLs (the redirects cover the gap) and fix
  its AGENTS.md "Docs: published separately at docs.labs.ai".
- After 30 days, cancel the GitBook space.
- Already done in the phase-1–4 PR: AGENTS.md (ecosystem row, roadmap, key files),
  CONTRIBUTING (adding a page), the ship-pr skill (`Docs Site` check), the monorepo plan pointer.
  The Platform Operator's prompt and allow-list mention no docs URL, so it needs no change.

## 8. Cleanup after cutover

Folded into phase 6 above.

## 9. Open questions worth a decision, not blocking

- **What is published.** GitBook publishes `code-review-standards.md`, `incident-response.md`
  and the changelog, while the Dockerfile `docs` stage strips exactly those from the image as
  internal-only. The migration keeps today's public set; whether they should stay public is a
  separate call.
- Ask-the-docs: GitBook's AI search goes away. An EDDI agent over `/llms.txt` on the docs site
  would be dogfooding — out of scope here.
- Versioned docs (GitBook does not version today either; `starlight-versions` if ever needed).

## 10. Not in scope

Rewriting or restructuring content, translation, moving `planning/` into the site.
