# docs-site

Builds [`docs/`](../docs/) into the EDDI documentation site — Astro + Starlight, published at
`https://docs.eddi.technology` and replacing GitBook at docs.labs.ai, which then redirects
here permanently. The plan, and why, is
[`planning/docs-site-migration-plan.md`](../planning/docs-site-migration-plan.md).

**There is no content here.** `docs/` is the source and stays plain GitHub Markdown.
[`scripts/sync-docs.mjs`](scripts/sync-docs.mjs) reads it on every `dev` and `build` and writes
everything the site needs into `.generated/` (gitignored): the Starlight pages, a raw Markdown
copy of each page, the sidebar, and Astro's build output.

- **What is published:** exactly the pages listed in [`docs/SUMMARY.md`](../docs/SUMMARY.md),
  in that order, which is also the sidebar. A page that is not listed there is not on the site.
- **URLs:** flat, from the file name — `docs/secrets-vault.md` → `/secrets-vault/`, and its
  Markdown at `/secrets-vault.md`. A `README.md` takes its directory's name, the monthly
  archives keep `/changelog/`, and the few exceptions are `SLUG_OVERRIDES` in the script.
  Moving a page between SUMMARY sections never changes its URL.
- **Links:** a link to a published page becomes its route; a link to anything else in the
  repository (source files, unpublished pages, configs) becomes a GitHub link. The sync fails on
  a SUMMARY entry without a file, a link to nothing, an `#anchor` that is not there, or two pages
  that would share a URL — and then `starlight-links-validator` checks every page link in the
  result.
- **Old URLs:** [`legacy-urls.txt`](legacy-urls.txt) lists every URL docs.labs.ai has served.
  [`scripts/redirects.mjs`](scripts/redirects.mjs) turns it into the `redirects` of
  [`vercel.json`](vercel.json); the tests fail if a line resolves to nothing or `vercel.json`
  is stale, and if any docs link in the backend, the UIs, README or the plugin does not land
  on a page.

## Reading layout

On desktop both sidebars can be **resized** — drag the edge, or focus it and use ← → (Home/End for
the limits, double-click to reset) — and **hidden** with the buttons in the header: before the logo
for the navigation, before "Website" for the table of contents. The choice is remembered per
browser (`localStorage`, key `eddi-docs-layout`) and applied before first paint, so pages do not
jump. Phones keep Starlight's own menu. The pure logic and its tests are
[`src/lib/sidebar-layout.mjs`](src/lib/sidebar-layout.mjs); the browser side is
[`src/components/LayoutToggle.astro`](src/components/LayoutToggle.astro).

## Commands

Node 24 (`.nvmrc`, the same version the UIs build with; Astro needs at least 22.23.4).

```bash
npm ci
npm test            # sync over the real docs/, anchors, redirects, docs links across the repo
npm run dev         # http://localhost:4321 — restart it after editing docs/
npm run build       # the static site in .generated/dist/, fails on any broken link
npm run redirects   # after editing legacy-urls.txt: regenerate vercel.json's redirects
npm run check-live -- https://<deployment>   # every page, .md copy and legacy URL, over HTTP
```

**No `sharp`.** Astro's default image service needs it, and its libvips binaries are LGPL-3.0,
which the repository's licence policy (`dependency-review.yml`) denies. The site has no raster
images, so it uses the pass-through image service, and `package.json` overrides `sharp` with the
stub in [`stubs/sharp`](stubs/sharp/package.json). Adding a raster image that needs resizing
means revisiting that, not removing the override.

Not part of the Maven build: `./mvnw package` neither builds nor ships this site. CI runs
[`docs-site.yml`](../.github/workflows/docs-site.yml): the tests on every pull request, the build
when `docs/` or this directory changed.

## Deployment

Vercel's Git integration deploys it, as it does the marketing site: a Vercel project on
`labsai/EDDI` with **Root Directory `docs-site`**. Everything else — install and build command,
output directory, headers, redirects, and the `ignoreCommand` that skips deployments for commits
that do not touch the docs — is in [`vercel.json`](vercel.json). The project needs "Include
source files outside of the Root Directory" enabled (the build reads `../docs`), and both
domains: `docs.eddi.technology` and `docs.labs.ai`, whose requests the first redirect rule sends
to the same path on the new domain.
