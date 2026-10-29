// Checks a deployed copy of the site from outside: every page, its .md copy and
// its no-slash form, the llms files, and every URL in legacy-urls.txt — each
// followed hop by hop to where it must land.
//
//   node scripts/check-live.mjs https://<preview>.vercel.app
//   node scripts/check-live.mjs https://docs.eddi.technology --legacy-base https://docs.labs.ai
//
// With --legacy-base, every legacy path is also requested on that host and must
// end on the same page of the first one. Run it against a preview deployment
// before the DNS switch, and against both domains after it.

import { buildRedirects, loadAll, REDIRECT_STATUS } from './redirects.mjs';
import { routeOf } from './sync-docs.mjs';

const args = process.argv.slice(2);
const base = args[0]?.replace(/\/$/, '');
const legacyFlag = args.indexOf('--legacy-base');
const legacyBase = legacyFlag >= 0 ? args[legacyFlag + 1]?.replace(/\/$/, '') : null;
if (!base || !/^https?:\/\//.test(base)) {
  console.error('usage: node scripts/check-live.mjs <base-url> [--legacy-base <url>]');
  process.exit(2);
}

const { plan, index, resolved, errors } = loadAll();
if (errors.length) {
  console.error(errors.join('\n'));
  process.exit(1);
}

/**
 * Follows redirects by hand, so each hop's status is visible. leftLegacyWith is the status
 * of the hop that took the request off the legacy host — the one that must be a 301.
 */
async function land(url) {
  const hops = [];
  let current = url;
  let leftLegacyWith = null;
  for (let i = 0; i < 6; i++) {
    const res = await fetch(current, { redirect: 'manual' });
    hops.push(res.status);
    const location = res.headers.get('location');
    if (res.status >= 300 && res.status < 400 && location) {
      const next = new URL(location, current).href;
      if (legacyBase && current.startsWith(legacyBase) && !next.startsWith(legacyBase)) leftLegacyWith = res.status;
      current = next;
      if (!current.startsWith(base) && !(legacyBase && current.startsWith(legacyBase))) {
        return { url: current, status: res.status, hops, leftLegacyWith, external: true };
      }
      continue;
    }
    return { url: current, status: res.status, hops, leftLegacyWith, type: res.headers.get('content-type') ?? '' };
  }
  return { url: current, status: 0, hops, leftLegacyWith, error: 'too many redirects' };
}

const checks = [];
const expect = (from, target, opts = {}) => checks.push({ from, target, ...opts });

for (const { slug } of plan.pages.values()) {
  const route = routeOf(slug);
  expect(base + route, route);
  if (route !== '/') expect(base + route.slice(0, -1), route);
  expect(base + (route === '/' ? '/index.md' : `${route.slice(0, -1)}.md`), null, { markdown: true });
}
for (const f of ['/llms.txt', '/llms-full.txt', '/llms-small.txt']) expect(base + f, f);

// Every redirect source in vercel.json, generated from the same list. Through the legacy host
// the request must also END on the canonical host: without the host rule, the path redirects
// alone would land on the right path on docs.labs.ai and look like a pass.
for (const rule of buildRedirects(resolved, index)) {
  if (rule.has || rule.source.includes(':')) continue;
  expect(base + rule.source, rule.destination);
  if (legacyBase) expect(legacyBase + rule.source, rule.destination, { canonical: true });
}
// The per-section fallback (/<section>/:slug, its slash and .md forms) has parameterised
// sources, so the loop above skips it: probe each section with a page no explicit rule
// covers under that section.
const staticSources = new Set(buildRedirects(resolved, index).map((r) => r.source));
for (const section of index.sections.keys()) {
  if (index.slugs.has(section)) continue;
  const slug = [...index.slugs].find((s) => s && !s.includes('.') && !s.includes('/') && !staticSources.has(`/${section}/${s}`));
  if (!slug) continue;
  expect(`${base}/${section}/${slug}`, `/${slug}/`);
  expect(`${base}/${section}/${slug}/`, `/${slug}/`);
  expect(`${base}/${section}/${slug}.md`, `/${slug}.md`);
}

// And paths no static redirect covers, which only the host rule can move.
if (legacyBase) {
  for (const p of ['/', '/secrets-vault/', '/secrets-vault.md', '/llms.txt']) expect(legacyBase + p, p, { canonical: true });
}

let failures = 0;
const queue = [...checks];
async function worker() {
  for (let c = queue.shift(); c; c = queue.shift()) {
    const r = await land(c.from);
    let problem = null;
    if (r.error) problem = r.error;
    else if (r.external) {
      if (r.url !== c.target) problem = `left the site for ${r.url}, expected ${c.target}`;
    } else if (r.status !== 200) problem = `ended with HTTP ${r.status} at ${r.url}`;
    else if (c.markdown && !r.type.includes('markdown')) problem = `served as ${r.type}, not Markdown`;
    else if (c.canonical && c.target?.startsWith('/') && new URL(r.url).origin !== new URL(base).origin) {
      problem = `stayed on ${new URL(r.url).origin} instead of moving to ${base}`;
    } else if (c.canonical && r.leftLegacyWith !== REDIRECT_STATUS) {
      problem = `left ${legacyBase} with HTTP ${r.leftLegacyWith}, expected ${REDIRECT_STATUS}`;
    }
    else if (c.target && c.target.startsWith('/') && new URL(r.url).pathname !== c.target) {
      problem = `landed on ${new URL(r.url).pathname}, expected ${c.target}`;
    }
    if (problem) {
      failures++;
      console.log(`FAIL ${c.from}: ${problem} (hops ${r.hops.join(' -> ')})`);
    }
  }
}
await Promise.all(Array.from({ length: 8 }, worker));
console.log(`${checks.length} URLs checked against ${base}${legacyBase ? ` and ${legacyBase}` : ''}: ${failures} failed`);
process.exit(failures ? 1 : 0);
