// Keeps every URL docs.labs.ai has ever served working on the new site.
//
// legacy-urls.txt lists them; this turns the list into the "redirects" block of the
// repository-root vercel.json (`npm run redirects`), and answers "does this path work on the new
// site?" for the tests and for the guard over docs links in the repository.
//
// Vercel evaluates redirects in order, before the file system, so the order is:
//   1. docs.labs.ai (any path)  -> the same path on docs.eddi.technology (301, kept forever)
//   2. each legacy path, in its plain, trailing-slash and .md forms -> its page
//   3. pages whose slug contains a dot, without their trailing slash (see below)
//   4. a fallback per SUMMARY section, /<section>/<slug> -> /<slug>/, for any
//      GitBook deep link the list does not know about.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import GithubSlugger from 'github-slugger';
import { planSite, repoIo, routeOf, SITE } from './sync-docs.mjs';

export const LEGACY_HOST = 'docs.labs.ai';

/**
 * 301 Moved Permanently, set explicitly. Vercel's `permanent: true` sends 308, which is
 * just as permanent, but 301 is the decision for docs.labs.ai (kept forever) and the code
 * every crawler and HTTP client has always treated as a move. Vercel's own trailingSlash
 * redirect (/x -> /x/) is a 308 and not configurable; check-live.mjs verifies that the hop
 * which leaves docs.labs.ai is the 301, whichever order Vercel applies the two in.
 */
export const REDIRECT_STATUS = 301;

/** Parses legacy-urls.txt into [{ path, target }] (target null when the default rule applies). */
export function parseLegacy(text) {
  const entries = [];
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === '' || line.startsWith('#')) continue;
    const [p, target, extra] = line.split(/\s+/);
    if (extra !== undefined || !p.startsWith('/')) throw new Error(`legacy-urls.txt: cannot read '${line}'`);
    entries.push({ path: p, target: target ?? null });
  }
  return entries;
}

/** GitBook's URL segment for a SUMMARY heading: "Security & Compliance" -> security-and-compliance. */
export function sectionSlug(label) {
  return new GithubSlugger().slug(label.replace(/&/g, 'and'));
}

/**
 * What the site serves: page slugs, and each SUMMARY section's slug with its
 * first page. Built from the same plan the sync writes, so it cannot drift.
 */
export function siteIndex(plan) {
  const slugs = new Set([...plan.pages.values()].map((p) => p.slug));
  const firstLink = (items) => {
    for (const item of items) {
      if (item.link) return item.link;
      const nested = firstLink(item.items);
      if (nested) return nested;
    }
    return null;
  };
  const sections = new Map();
  for (const entry of plan.sidebar) {
    if (entry.items) sections.set(sectionSlug(entry.label), firstLink(entry.items));
  }
  return { slugs, sections };
}

/** Splits '/a/b.md/' into { segments: ['a','b'], md: true }. */
function splitPath(p) {
  let rest = p.replace(/\/+$/, '');
  const md = rest.endsWith('.md');
  if (md) rest = rest.slice(0, -'.md'.length);
  return { segments: rest.split('/').filter(Boolean), md };
}

/** The route a page slug's Markdown copy is served at. */
const mdRoute = (route) => (route === '/' ? '/index.md' : `${route.replace(/\/$/, '')}.md`);

/**
 * Where a path ends up on the new site, without any redirect file: a page
 * ('/slug/'), its Markdown copy ('/slug.md'), or null when only a redirect can
 * save it. The site also answers every page route without the trailing slash.
 */
export function servedRoute(p, index) {
  const { segments, md } = splitPath(p);
  const slug = segments.join('/');
  if (!index.slugs.has(slug)) return null;
  const route = routeOf(slug);
  return md ? mdRoute(route) : route;
}

/**
 * Where a legacy path should go, by the default rule: its section's first page
 * for a section root, else the longest trailing part that is a page slug.
 */
export function defaultTarget(p, index) {
  const { segments, md } = splitPath(p);
  if (segments.length === 1 && index.sections.has(segments[0]) && !index.slugs.has(segments[0])) {
    const route = index.sections.get(segments[0]);
    return md ? mdRoute(route) : route;
  }
  for (let i = 0; i < segments.length; i++) {
    const slug = segments.slice(i).join('/');
    if (index.slugs.has(slug)) return md ? mdRoute(routeOf(slug)) : routeOf(slug);
  }
  return null;
}

/** Checks an explicit target names something that exists. */
function validTarget(target, index) {
  return target.startsWith('https://') || servedRoute(target, index) !== null;
}

/**
 * Every legacy entry resolved: { path, target, redirect } where redirect is
 * false for a path that is a page in its own right.
 */
export function resolveLegacy(entries, index) {
  const errors = [];
  const resolved = [];
  for (const { path: p, target } of entries) {
    if (target !== null) {
      if (!validTarget(target, index)) errors.push(`legacy-urls.txt: ${p} -> ${target}, which is not a page or an https URL`);
      else resolved.push({ path: p, target, redirect: true });
      continue;
    }
    const served = servedRoute(p, index);
    if (served) {
      resolved.push({ path: p, target: served, redirect: false });
      continue;
    }
    const t = defaultTarget(p, index);
    if (t) resolved.push({ path: p, target: t, redirect: true });
    else errors.push(`legacy-urls.txt: no page for ${p} — give it a target`);
  }
  return { resolved, errors };
}

/** The "redirects" block for vercel.json. */
export function buildRedirects(resolved, index) {
  const out = [
    {
      source: '/:path*',
      has: [{ type: 'host', value: LEGACY_HOST }],
      destination: `${SITE}/:path*`,
      statusCode: REDIRECT_STATUS,
    },
  ];
  const seen = new Set();
  const add = (source, destination) => {
    if (seen.has(source) || source === destination) return;
    seen.add(source);
    out.push({ source, destination, statusCode: REDIRECT_STATUS });
  };
  const sectionRoots = [...index.sections].filter(([s]) => !index.slugs.has(s)).map(([s, route]) => ({ path: `/${s}`, target: route }));
  for (const { path: p, target } of [...resolved.filter((r) => r.redirect), ...sectionRoots]) {
    const base = p === '/' ? '' : p.replace(/\/+$/, '').replace(/\.md$/, '');
    // A route target has a page form and a .md form; an external URL is the same for both.
    const isRoute = target.startsWith('/');
    const pageTarget = isRoute && target.endsWith('.md') ? target.replace(/\.md$/, '/').replace(/^\/index\/$/, '/') : target;
    const markdownTarget = isRoute ? (target.endsWith('.md') ? target : mdRoute(target)) : target;
    if (base === '') continue;
    add(base, pageTarget);
    add(`${base}/`, pageTarget);
    add(`${base}.md`, markdownTarget);
  }
  // trailingSlash: true adds the slash only to paths without a file extension, and
  // /upgrading-from-6.4 looks like a file with extension ".4" — so without these it 404s.
  for (const slug of [...index.slugs].sort()) {
    if (/\.[^/]*$/.test(slug)) add(`/${slug}`, routeOf(slug));
  }
  for (const section of [...index.sections.keys()].sort()) {
    if (index.slugs.has(section)) continue;
    // Slugs without a dot only: '.md' is handled by the second rule, and the few slugs
    // with dots (upgrading-from-6.4, ...) are in legacy-urls.txt explicitly.
    // The slash form too: with trailingSlash: true the request may arrive as /<section>/<slug>/.
    out.push({ source: `/${section}/:slug([^/.]+)`, destination: '/:slug/', statusCode: REDIRECT_STATUS });
    out.push({ source: `/${section}/:slug([^/.]+)/`, destination: '/:slug/', statusCode: REDIRECT_STATUS });
    out.push({ source: `/${section}/:slug([^/.]+).md`, destination: '/:slug.md', statusCode: REDIRECT_STATUS });
  }
  return out;
}

/**
 * Whether a path works on the new site — served directly, or redirected to
 * something that is. Mirrors buildRedirects, for the guard over repository links.
 */
export function pathWorks(p, index, resolved) {
  const decoded = decodeURI(p.split('#')[0].split('?')[0]) || '/';
  if (decoded === '/' || decoded === '/llms.txt' || decoded === '/llms-full.txt' || decoded === '/llms-small.txt') return true;
  if (servedRoute(decoded, index)) return true;
  const key = decoded.replace(/\/+$/, '').replace(/\.md$/, '');
  if (resolved.some((r) => r.path.replace(/\/+$/, '') === key)) return true;
  const { segments } = splitPath(decoded);
  if (segments.length === 1 && index.sections.has(segments[0])) return true;
  // Exactly what the per-section fallback in buildRedirects matches: a section that is not
  // itself a page, and a page slug without a dot ([^/.]+).
  const [section, slug] = segments;
  return segments.length === 2 && index.sections.has(section) && !index.slugs.has(section) && !slug.includes('.') && index.slugs.has(slug);
}

const DOCS_URL = /https?:\/\/docs\.(?:labs\.ai|eddi\.technology)(\/[^\s"'`)<>\]|,;]*)?/g;

/**
 * Every docs URL written in the places that ship or point users at the docs —
 * backend, UIs, README, CONTRIBUTING, AGENTS, plugin and GitHub templates — with
 * where it was found. Tests, the changelog and generated output are skipped:
 * they quote URLs, they do not send anyone there.
 */
export function docsUrlsInRepo(repoRoot) {
  const roots = ['src/main', 'ui/manager/src', 'ui/chat/src', 'integrations', '.github', 'README.md', 'CONTRIBUTING.md', 'AGENTS.md'];
  const skip = /(^|\/)(node_modules|\.generated|dist|__tests__|test|tests)(\/|$)|\.test\.[jt]sx?$|\.spec\.[jt]sx?$/;
  const found = [];
  const visit = (rel) => {
    const full = path.join(repoRoot, rel);
    if (!fs.existsSync(full) || skip.test(rel)) return;
    if (fs.statSync(full).isDirectory()) {
      for (const name of fs.readdirSync(full)) visit(rel ? `${rel}/${name}` : name);
      return;
    }
    if (!/\.(java|ts|tsx|js|mjs|json|md|ya?ml|properties|html)$/.test(rel)) return;
    const text = fs.readFileSync(full, 'utf8');
    for (const m of text.matchAll(DOCS_URL)) {
      found.push({ file: rel, url: m[0], path: (m[1] ?? '/').replace(/[.]+$/, (dots) => (dots.length > 1 ? dots : '')) });
    }
  };
  for (const r of roots) visit(r);
  return found;
}

const siteDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** Loads everything from disk: the plan, the index and the resolved legacy list. */
export function loadAll(repoRoot = path.resolve(siteDir, '..')) {
  const plan = planSite(repoIo(repoRoot));
  const index = siteIndex(plan);
  const entries = parseLegacy(fs.readFileSync(path.join(siteDir, 'legacy-urls.txt'), 'utf8'));
  const { resolved, errors } = resolveLegacy(entries, index);
  return { plan, index, resolved, errors: [...plan.errors, ...errors] };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { index, resolved, errors } = loadAll();
  if (errors.length > 0) {
    console.error(`redirects: ${errors.length} problem(s):`);
    for (const e of errors) console.error(`  - ${e}`);
    process.exit(1);
  }
  // At the repository root, not here: Vercel reads vercel.json from the project's Root
  // Directory, which is the repository root (docs-site/README.md, Deployment, says why).
  const file = path.join(siteDir, '..', 'vercel.json');
  const config = JSON.parse(fs.readFileSync(file, 'utf8'));
  config.redirects = buildRedirects(resolved, index);
  fs.writeFileSync(file, `${JSON.stringify(config, null, 2)}\n`);
  console.log(`redirects: ${config.redirects.length} rules written to vercel.json`);
}
