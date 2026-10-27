## 📚 feat(docs-site): build docs/ with Astro + Starlight, the replacement for GitBook (2026-10-26)

**Repo:** EDDI (`feat/docs-site-astro`)

### What changed and why

docs.labs.ai is GitBook, syncing `docs/` one way. The documentation moves to a site we build ourselves: Astro + Starlight, deployed on Vercel like the marketing site, at `docs.eddi.technology`, with docs.labs.ai redirecting there permanently. The plan, and the facts it rests on (the URLs GitBook has served over the years, the website's hosting, what GitBook provides besides pages), is [`planning/docs-site-migration-plan.md`](../../planning/docs-site-migration-plan.md). This is its phase 1: the site builds locally. Nothing is deployed and docs.labs.ai is unchanged.

- **`docs/` is untouched as a format.** It stays plain GitHub Markdown, because GitHub renders it, `McpDocResources` serves it raw to MCP clients and `DocumentationLinksTest` checks it. [`docs-site/scripts/sync-docs.mjs`](../../docs-site/scripts/sync-docs.mjs) reads it on every dev/build run and writes the Starlight copy. `docs/SUMMARY.md` still decides what is published and is now the sidebar too.
- **Flat URLs from file names** — `docs/secrets-vault.md` → `/secrets-vault/`. GitBook's URLs carried the SUMMARY section (`/security-and-compliance/secrets-vault`), so moving a page between sections changed its address; and the flat links the backend and the Manager already print (`VaultStartupBanner`, `ComplianceStartupChecks`, the coordinator page) 404 on GitBook today. They resolve on the new site.
- **Links are rewritten, and checked.** A link to a published page becomes its route; a link to anything else in the repository becomes a GitHub link. The sync fails on a SUMMARY entry without a file, a link to nothing, two pages that would share a URL, and an `#anchor` that does not exist. Anchors are checked against the source because `starlight-links-validator` 0.27 cannot check them under Astro 7: Astro's satteri processor runs plugins before it assigns heading ids, so the plugin sees none and reports every anchor as broken. The source-side check computes the ids with `github-slugger`, the same way Astro does, and matches all 5,235 heading ids of the built site, none missing and none extra. The plugin still checks every page link.
- **GitBook's machine-readable surface is kept:** every page also exists as `/<slug>.md` (the website links to GitBook's `.md` URLs), and `/llms.txt` lists every page, with `/llms-full.txt` and `/llms-small.txt` (no changelog) beside it.
- **Everything generated lives in `docs-site/.generated/`**, which is gitignored. `DocumentationLinksTest`, `DocumentationAccuracyTest` and `DocumentedRestPathsTest` skip it by name, so a local site build cannot feed them a second copy of `docs/` with site-relative links.
- The anchor check found one real broken link: two references in [`changelog/2026-08.md`](../changelog/2026-08.md) pointed at a heading that had been rotated into the July archive. They now link there.

### Verification

- `npm test` in `docs-site/`: 32 tests, including a full sync over the real `docs/`. Each guard was mutation-checked: disabling the anchor check, blockquote headings, setext headings, entity decoding, the intra-word underscore rule, fence-length and code-span handling, directory READMEs or the slug-collision check each fails a test.
- `npm run build`: 94 pages, link validator clean. Seen in a browser — search, Mermaid, dark and light theme, phone width, `.md` endpoints, the no-slash URLs the backend uses — with no console errors.

### Next

Phase 2 of the plan: a page-by-page comparison against GitBook. Then phase 3, the redirect map from every URL GitBook has served, and phase 4, the CI workflow and the Vercel project.

```decision-log
| 2026-10-26 | Docs move off GitBook to an Astro + Starlight build of `docs/` on Vercel at docs.eddi.technology; docs.labs.ai redirects permanently; flat URLs from file names | GitBook URLs carry the SUMMARY section and break when a page moves; the backend's flat docs links 404 there; the marketing site already moved to eddi.technology on Vercel | Keeping docs.labs.ai canonical (docs stay on the old brand domain); folding the docs into the website repo (plain Astro with 11 locales, and docs PRs could not be validated before merge); GitHub Pages (no real redirects) |
```

## 📚 feat(docs-site): redirects for every URL GitBook ever served, CI, and the cutover kit (2026-10-27)

**Repo:** EDDI (`feat/docs-site-astro`)

### What changed and why

Phases 2–4 of [`planning/docs-site-migration-plan.md`](../../planning/docs-site-migration-plan.md). After this, moving docs.labs.ai off GitBook is a Vercel project and a DNS record — the checklist is phase 5 of the plan.

- **Fidelity (phase 2), by script over all 94 pages.** Source against build: the same number of tables and code blocks, and no Markdown leaking into rendered text. GitBook against build: 98.4% mean word coverage, every gap GitBook's page chrome or text that changed on `main` after the branch was cut.
- **Every old URL keeps working (phase 3).** [`docs-site/legacy-urls.txt`](../../docs-site/legacy-urls.txt) lists every URL docs.labs.ai has served — today's sitemap, the Wayback Machine's older flat and chatbot-era URLs, the links in this repo and the website. [`scripts/redirects.mjs`](../../docs-site/scripts/redirects.mjs) generates `vercel.json`'s redirects from it: docs.labs.ai → the same path on docs.eddi.technology, then each legacy path in its plain, trailing-slash and `.md` forms, then a per-section fallback. Pages whose slug contains a dot (`upgrading-from-6.4`) get an explicit slash redirect, because `trailingSlash` takes `.4` for a file extension and would 404 them — found by the new checker.
- **A guard over docs links in the repository.** Every docs URL written in the backend, the UIs, README, CONTRIBUTING, AGENTS, the plugin and `.github/` must land on a page, anchor included. Released images print those links forever; three of them 404 on GitBook today.
- **[`scripts/check-live.mjs`](../../docs-site/scripts/check-live.mjs)** checks a deployment over HTTP — 693 URLs followed hop by hop. Run locally against a stand-in for Vercel's serving of `vercel.json`, it found the dotted-slug 404s and a generator bug that rewrote an external `…/CONTRIBUTING.md` target to `…/CONTRIBUTING/`; both are fixed and covered by tests.
- **Headers:** the website's security headers, with a CSP written for this site (`'wasm-unsafe-eval'` for the search index, shields.io and quay.io images). Search, Mermaid and badges were checked in a browser under it with no violations.
- **CI (phase 4):** [`docs-site.yml`](../../.github/workflows/docs-site.yml) — check *Docs Site* — runs the tests on every PR (they hold the repo-wide link guard) and the site build when docs changed. Dependabot watches `/docs-site`, because CI's Trivy scan reads its lockfile and an advisory there would fail every backend PR. `ci.yml`'s `backend` filter gains `docs-site/**/*.md`, and `BuildQualityGatesTest` holds `docs-site/.nvmrc` to the pom's Node.
- **Branding matched to the website and the Manager, value for value.** Tokens copied from the website's `global.css`; the header bar, its right side (Website, Get Started, GitHub) and the sun/moon toggle from its `Header.astro`, replacing Starlight's theme `<select>`; prose from its `GuidePage.astro`; code blocks in Astro's default `github-dark` theme with the same geometry; the website's favicons; the Manager's sidebar active/hover treatment and scrollbars. Checked against the live site by computed style (header, logo, CTA, nav link, headings, body text, links, code, tables: identical) and by screenshot in both themes and at phone width. Two deliberate differences: light-mode links use the website's own light accent token (accent-700) rather than accent-400, which is ~1.9:1 on its light background; and the docs render Outfit and JetBrains Mono for real, which the live website currently does not (it asks for `Outfit`, but `@fontsource-variable/outfit` registers `Outfit Variable`, so it falls back to the system font; inline code falls back to the browser's generic monospace).
- **No `sharp`.** Dependency Review failed the PR on the ten `@img/sharp-libvips-*` packages (LGPL-3.0), which `sharp` — Astro's optional default image optimiser — pulls in and the repository's licence policy denies. The site has no raster images, so it uses Astro's pass-through image service and `package.json` overrides `sharp` with a local stub (`docs-site/stubs/sharp`); the policy is unchanged. [`licenses.test.mjs`](../../docs-site/scripts/licenses.test.mjs) checks the lockfile against the deny-list read from `dependency-review.yml`, so this fails `npm test` next time rather than a PR check.
- AGENTS.md (ecosystem row: the website is Astro + Tailwind on Vercel at eddi.technology), CONTRIBUTING (how to add a page), the ship-pr skill (the new check) and the monorepo plan now describe the docs as they are built.

### Verification

`npm test` in `docs-site/`: 52 tests; the new guards were mutation-checked. `npm run build` clean; `check-live` 693/693 against the local stand-in. The Java guards touched here (`BuildQualityGatesTest`, `DocumentationLinksTest`, `DocumentationAccuracyTest`, `DocumentedRestPathsTest`, the changelog tests) pass.
