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
