# EDDI documentation — from GitBook to an Astro + Starlight site on Vercel

Status: **revision 2, phase 1 done** (2026-10-26). Roadmap §3 item 14 ("Website — Astro
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

### 3.1 Domain — **open, needs coordination**

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

- New Vercel project on `labsai/EDDI`, **Root Directory `docs-site`**, with "include files
  outside the root directory" enabled (the build reads `../docs` and `../PRIVACY.md`).
- `ignoreCommand` in `docs-site/vercel.json` skips the build unless `docs/`, `docs-site/` or
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
  vercel.json               # phase 3/4: headers, redirects, ignoreCommand
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

`legacy-urls.txt` is the union of today's sitemap, the Wayback list (112 URLs), the `.md`
variants of every sitemap URL, and the URLs this repo and the website link to. Each line maps
to a target page; the map is mostly mechanical:

- `/<section>/<slug>`, `/<section>/<parent>/<slug>` and their `.md` forms → `/<slug>/`
  (`.md` → `/<slug>.md`).
- Flat old URLs (`/docker`, `/metrics`, …) already *are* pages under §3.2.
- Bot-era and removed pages get a hand-written target: `/creating-your-first-chatbot*` and
  `/your-first-bot*` → `/creating-your-first-agent/`, `/bot-manager-gui` →
  `/agent-manager-gui/`, `/import-export-a-chatbot` → `/import-export-an-agent/`,
  `/deployement-management-of-chatbots` → `/deployment-management-of-agents/`,
  `/advanced-concepts/*-father-*` → `/getting-started/`; pages with no successor
  (`/facebook-channel-setup`, `/http2-and-ssl`, `/git-support`, `/deployment-and-infrastructure/tasks`,
  `/deployment-and-infrastructure/git-commit-guide`) → the closest section's page or `/`.
- Section roots (`/getting-started`, `/deployment-and-infrastructure`, …) → that section's first page.

Redirects are generated into `vercel.json` as explicit 301s (a regex catch-all like
`/:section/:slug` would capture `/changelog/2026-10`). With domain option A, the docs.labs.ai
host is attached to the same Vercel project with one host-scoped rule
`https://docs.labs.ai/(.*)` → `https://docs.eddi.technology/$1`, so the legacy map applies
after it.

`sync-docs.test.mjs` asserts every line of `legacy-urls.txt` resolves to a page or a redirect,
so the map cannot rot. The cutover script (phase 5) checks the same list against the live host.

## 6. CI and repo guards

- New workflow `.github/workflows/docs-site.yml`, path-filtered to `docs/**`, `docs-site/**`,
  `PRIVACY.md`: `npm ci && npm test && npm run build`. No secrets, so it runs for fork PRs too.
  Replaces the `GitBook (docs)` check. Actions pinned by SHA, minimal `permissions:` (Scorecard).
- `.github/dependabot.yml`: an `npm` entry for `/docs-site`, grouped like the UI entries.
- Java guard in `DocumentationLinksTest` (or a sibling): every `docs.labs.ai/<path>` (and
  `docs.eddi.technology/<path>`) literal in `src/main/java`, `ui/`, `README.md`,
  `CONTRIBUTING.md`, `integrations/`, `.github/` must name a page in SUMMARY or a line of
  `legacy-urls.txt`. That is the check that would have caught the three 404s in §2.
- Not deployed by Actions — Vercel's Git integration deploys, as for the website. Actions only
  validates.

## 7. Phases

| # | Work | Done when |
| --- | --- | --- |
| 0 | Decide domain (§3.1); confirm the Vercel plan (§3.4) | Answered |
| 1 ✅ | Scaffold `docs-site/`, theme from the website, `sync-docs.mjs` + tests, sidebar from SUMMARY, `.md` endpoints, llms.txt | `npm run build` renders every SUMMARY page locally, validator clean — done 2026-10-26 (94 pages) |
| 2 | Fidelity pass: every page next to its GitBook version — tables, mermaid, long code blocks, the changelog archives | Every page checked |
| 3 | `legacy-urls.txt` + redirect generation + test; Java URL guard; CSP | Tests green |
| 4 | `docs-site.yml`, dependabot entry; Vercel project; preview deployment on a PR | Preview reachable; search works under the CSP |
| 5 | Cutover: add the domain(s) in Vercel; switch DNS for docs.labs.ai (and add docs.eddi.technology) at the registrar; run the legacy-URL script against the live host; disconnect GitBook Git Sync | All `legacy-urls.txt` lines 200/301 live |
| 6 | Cleanup (§8), changelog fragment; GitBook space kept intact but unpublished for 30 days, then cancelled | — |

Phases 1–4 are one PR here, perhaps two (scaffold; then CI + redirects). Nothing reader-visible
changes until the DNS switch in phase 5. **Rollback:** point the docs.labs.ai CNAME back at
GitBook — which is why the space stays intact for 30 days. Lower the record's TTL a day ahead.

Rough size: 3–5 days, most of it phases 2 and 3.

## 8. Cleanup after cutover

In this repo:

- URL literals (only if domain option A): `VaultStartupBanner`, `ComplianceStartupChecks`,
  `ui/manager` `sidebar.tsx` and `coordinator.tsx`, `README.md`, `CONTRIBUTING.md`,
  `plugin.json`, `.github/ISSUE_TEMPLATE/config.yml`. (With option B they already work under the
  flat scheme — nothing to change.)
- `AGENTS.md`: ecosystem row (the website is Astro + Tailwind on Vercel at `eddi.technology`;
  the docs are built by `docs-site/`), the "Build & Test" section gains `docs-site`, roadmap
  item 14 done.
- `.claude/skills/ship-pr/SKILL.md`: `GitBook (docs)` → the new check.
- `planning/monorepo-migration-plan.md` §9.4: the GitBook paragraph becomes a pointer here.
- `CONTRIBUTING.md`: "adding a doc page = file + SUMMARY entry; preview with
  `npm run dev` in `docs-site/`".
- The Platform Operator (AGENTS.md §4.6 item 6): check its system prompt for docs URLs; bump
  `operator-revision.json` only if it changes.
- Historical mentions of GitBook stay (changelog archives, `DocumentationLinksTest` javadoc,
  the two "GitBook-hosted Postman collections" notes).

In the website repo (separate PR there): rewrite the `docsLinks` to the new URLs (the 301s
cover the gap), and fix its AGENTS.md "Docs: published separately at docs.labs.ai".

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
