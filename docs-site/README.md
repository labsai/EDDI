# docs-site

Builds [`docs/`](../docs/) into the EDDI documentation site — Astro + Starlight, published at
`https://docs.eddi.technology` (replacing GitBook at docs.labs.ai; see
[`planning/docs-site-migration-plan.md`](../planning/docs-site-migration-plan.md)).

**There is no content here.** `docs/` is the source and stays plain GitHub Markdown.
[`scripts/sync-docs.mjs`](scripts/sync-docs.mjs) reads it on every `dev` and `build` and writes
the Starlight copy into `src/content/docs/`, the sidebar into `src/generated/`, and a raw
Markdown copy of each page into `public/<slug>.md`. All three are gitignored.

- **What is published:** exactly the pages listed in [`docs/SUMMARY.md`](../docs/SUMMARY.md),
  in that order, which is also the sidebar. A page that is not listed there is not on the site.
- **URLs:** flat, from the file name — `docs/secrets-vault.md` → `/secrets-vault/`. A
  `README.md` takes its directory's name, the monthly archives keep `/changelog/`, and the few
  exceptions are `SLUG_OVERRIDES` in the script. Moving a page between SUMMARY sections never
  changes its URL.
- **Links:** a link to a published page becomes its route; a link to anything else in the
  repository (source files, unpublished pages, configs) becomes a GitHub link. The build fails
  on a SUMMARY entry without a file, a link to nothing, or two pages that would share a URL —
  and then `starlight-links-validator` checks every internal link and anchor in the result.

## Commands

Node ≥ 22.23.4 (Astro's floor; `.nvmrc` pins the Node 24 the UIs build with).

```bash
npm ci
npm test          # unit tests for the sync, including a full run over the real docs/
npm run dev       # http://localhost:4321, re-run after editing docs/ (sync runs at start)
npm run build     # sync + static site in dist/, fails on any broken link
```

Not part of the Maven build: `./mvnw package` neither builds nor ships this site.
