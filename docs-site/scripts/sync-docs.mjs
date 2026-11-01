// Turns docs/ into Starlight content. docs/ stays plain GitHub Markdown — it is
// what GitHub renders, what McpDocResources serves raw to MCP clients, and what
// DocumentationLinksTest checks — so everything the site needs is added here, on
// a copy, never in docs/ itself:
//
//   - docs/SUMMARY.md decides what is published and becomes the sidebar.
//   - Each page gets a flat slug from its file name (secrets-vault.md ->
//     /secrets-vault/), so moving a page between SUMMARY sections never changes
//     its URL. The section-prefixed GitBook URLs broke on every such move.
//   - Frontmatter (title, slug, editUrl) is injected and the leading H1 dropped,
//     because Starlight renders the title itself.
//   - Relative links are rewritten: a published page becomes its route, anything
//     else in the repository (source files, unpublished pages, configs) becomes a
//     GitHub link, so nothing on the site points at a file it does not serve.
//   - Every page is also written as raw Markdown to <slug>.md in the public
//     directory, the per-page .md endpoint GitBook offered and the website links to.
//
// Everything generated lives under .generated/ (content, public, sidebar, and
// Astro's build output): one gitignored directory, which the repository's
// Markdown guards (DocumentationLinksTest and friends) skip by name, so a local
// build never feeds them a second copy of docs/ with site-relative links.
//
// Planning is pure (planSite) and writing is separate (writeSite), so the tests
// can run the whole transform against the real docs/ without touching disk.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import GithubSlugger from 'github-slugger';
import YAML from 'yaml';

export const SITE = 'https://docs.eddi.technology';
export const GITHUB = 'https://github.com/labsai/EDDI';
const BLOB = `${GITHUB}/blob/main/`;
const TREE = `${GITHUB}/tree/main/`;
const EDIT = `${GITHUB}/edit/main/`;

/** Pages whose file name does not make a good URL. Keys are repository-relative. */
const SLUG_OVERRIDES = new Map([
  ['docs/how-to....md', 'faq'],
  ['PRIVACY.md', 'privacy'],
  ['docs/changelog.d/README.md', 'changelog/pending'],
]);

/** Directories whose pages keep the directory as a URL prefix instead of being flattened. */
const PREFIXED_DIRS = new Set(['changelog']);

// ---------------------------------------------------------------------------
// SUMMARY.md
// ---------------------------------------------------------------------------

const SUMMARY_ITEM = /^(\s*)[-*]\s+\[([^\]]+)\]\(([^)]+)\)\s*$/;

/**
 * Parses docs/SUMMARY.md into sidebar groups. Items before the first `## `
 * heading are top-level; an item indented under another becomes its child.
 * Anything else (the `# Table of contents` title, prose under a heading) is
 * ignored, as GitBook ignored it.
 *
 * @returns {{ groups: Array<{label: string|null, items: Array<{label: string, target: string, children: Array}>}> }}
 */
export function parseSummary(text) {
  const groups = [{ label: null, items: [] }];
  let parent = null;
  for (const line of text.split(/\r?\n/)) {
    const heading = /^##\s+(.+?)\s*$/.exec(line);
    if (heading) {
      groups.push({ label: heading[1], items: [] });
      parent = null;
      continue;
    }
    const item = SUMMARY_ITEM.exec(line);
    if (!item) continue;
    const [, indent, label, target] = item;
    const entry = { label: label.trim(), target: target.trim(), children: [] };
    if (indent.length > 0 && parent) {
      parent.children.push(entry);
    } else {
      groups.at(-1).items.push(entry);
      parent = entry;
    }
  }
  return { groups: groups.filter((g) => g.items.length > 0) };
}

/** All SUMMARY entries in reading order, children after their parent. */
export function flattenSummary(summary) {
  const out = [];
  const walk = (items) => {
    for (const item of items) {
      out.push(item);
      walk(item.children);
    }
  };
  for (const group of summary.groups) walk(group.items);
  return out;
}

// ---------------------------------------------------------------------------
// Slugs and routes
// ---------------------------------------------------------------------------

/** Repository-relative path (posix) of a SUMMARY target. */
export function summaryTargetToRepoPath(target) {
  return path.posix.normalize(path.posix.join('docs', decodeURI(target.split('#')[0])));
}

/** '' for the home page, otherwise e.g. 'secrets-vault' or 'changelog/2026-10'. */
export function pageSlug(repoPath) {
  if (SLUG_OVERRIDES.has(repoPath)) return SLUG_OVERRIDES.get(repoPath);
  if (!repoPath.startsWith('docs/') || !repoPath.endsWith('.md')) {
    throw new Error(`${repoPath}: only Markdown files under docs/ can be published (or add a slug override)`);
  }
  const segments = repoPath.slice('docs/'.length).split('/');
  let name = segments.pop();
  if (name === 'README.md') {
    if (segments.length === 0) return '';
    name = segments.pop();
  } else {
    name = name.slice(0, -'.md'.length);
  }
  const dir = segments[0];
  return dir && PREFIXED_DIRS.has(dir) ? `${dir}/${name}` : name;
}

export const routeOf = (slug) => (slug === '' ? '/' : `/${slug}/`);

// ---------------------------------------------------------------------------
// Markdown transforms
// ---------------------------------------------------------------------------

// Any indentation, inside any blockquote: fences nested in list items are indented past
// the CommonMark three spaces, and the docs quote code in callouts (> ```json).
const FENCE_OPEN = /^(?:\s*>)*\s*(`{3,}|~{3,})/;
const FENCE_CLOSE = /^(?:\s*>)*\s*(`{3,}|~{3,})\s*$/;

/**
 * Calls fn(line, index) for every line outside fenced code blocks and returns
 * the lines with fn's results substituted. Fenced code is examples, not links
 * or headings — the same exclusion DocumentationLinksTest makes.
 */
function mapProseLines(lines, fn) {
  let fence = null;
  return lines.map((line, i) => {
    const marker = FENCE_OPEN.exec(line);
    if (fence) {
      // A fence closes on a line holding only the same character, at least as many times.
      const closes = marker && marker[1][0] === fence[0] && marker[1].length >= fence.length && FENCE_CLOSE.test(line);
      if (closes) fence = null;
      return line;
    }
    if (marker) {
      fence = marker[1];
      return line;
    }
    return fn(line, i);
  });
}

/** Splits YAML frontmatter off the top of a page. */
export function splitFrontmatter(text) {
  const m = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(text);
  if (!m) return { data: {}, body: text };
  return { data: YAML.parse(m[1]) ?? {}, body: text.slice(m[0].length) };
}

/**
 * Removes the page's H1 when it is the first heading — Starlight renders the
 * title from frontmatter, so keeping it would show the title twice. A page whose
 * first heading is not an H1 is left alone.
 */
export function dropLeadingH1(body) {
  const lines = body.split('\n');
  let done = false;
  let drop = -1;
  mapProseLines(lines, (line, i) => {
    if (done) return line;
    if (/^#{2,6}\s/.test(line)) done = true;
    else if (/^#\s+\S/.test(line)) {
      drop = i;
      done = true;
    }
    return line;
  });
  if (drop < 0) return body;
  lines.splice(drop, lines[drop + 1]?.trim() === '' ? 2 : 1);
  return lines.join('\n');
}

const INLINE_LINK = /(\]\(\s*)(<[^>\n]*>|[^)\s]+)((?:\s+"[^"\n]*")?\s*\))/g;
const REF_DEFINITION = /^(\s{0,3}\[[^\]]+\]:\s*)(<[^>\n]*>|\S+)(.*)$/;
const CODE_SPAN = /(`+)[\s\S]*?\1/g;

/** Applies fn to the parts of a line that are not inline code spans. */
function outsideCodeSpans(line, fn) {
  let out = '';
  let last = 0;
  for (const m of line.matchAll(CODE_SPAN)) {
    out += fn(line.slice(last, m.index)) + m[0];
    last = m.index + m[0].length;
  }
  return out + fn(line.slice(last));
}

/** Rewrites every link target in a Markdown body with rewrite(target) (outside code). */
export function rewriteLinks(body, rewrite) {
  const lines = body.split('\n');
  return mapProseLines(lines, (line) => {
    const ref = REF_DEFINITION.exec(line);
    if (ref) return ref[1] + wrapAngle(ref[2], rewrite) + ref[3];
    return outsideCodeSpans(line, (part) =>
      part.replace(INLINE_LINK, (_, open, target, close) => open + wrapAngle(target, rewrite) + close),
    );
  }).join('\n');
}

function wrapAngle(target, rewrite) {
  if (target.startsWith('<') && target.endsWith('>')) return `<${rewrite(target.slice(1, -1))}>`;
  return rewrite(target);
}

// ---------------------------------------------------------------------------
// Anchors
// ---------------------------------------------------------------------------
//
// Checked here, against the source, rather than by starlight-links-validator:
// under Astro 7's satteri Markdown processor its plugin runs before Astro
// assigns heading ids, so it sees none and reports every #anchor as broken.
// Astro's heading-ids step is github-slugger over the heading's text — the same
// slugs GitHub gives — so computing them from the Markdown is exact for the
// headings these docs use.

// Both allow a blockquote prefix: the docs put headings in callouts (> ### ⚠️ ...).
const ATX_HEADING = /^(?:\s{0,3}>)*\s{0,3}#{1,6}\s+(.*?)(?:\s+#+)?\s*$/;
const SETEXT_UNDERLINE = /^(?:\s{0,3}>)*\s{0,3}(?:=+|-+)\s*$/;
const BLOCK_START = /^(?:\s{0,3}>)*\s{0,3}(?:[-*+]\s|\d+[.)]\s|#|\||<|$)/;
const HTML_ANCHOR = /<a\s[^>]*?\b(?:id|name)\s*=\s*["']([^"']+)["']/gi;
const NAMED_ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ' };

/** The text a heading renders to: markup gone, link and code text kept. */
export function headingText(markdown) {
  const spans = [];
  let text = markdown.replace(/(`+)([\s\S]*?)\1/g, (_, __, code) => {
    spans.push(code.trim());
    return `\u0000${spans.length - 1}\u0000`;
  });
  text = text
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/<[^>]+>/g, '')
    .replace(/(\*\*|\*|~~)(?=\S)(.*?\S)\1/g, '$2')
    // Underscores only emphasise at word boundaries: max_tokens_per stays as it is.
    .replace(/(?<![\p{L}\p{N}])(__|_)(?=\S)(.*?\S)\1(?![\p{L}\p{N}])/gu, '$2')
    .replace(/\\([!-/:-@[-`{-~])/g, '$1')
    // GitBook left '&#x20;' at the start of some headings; the renderer decodes it.
    .replace(/&#x([0-9a-f]+);/gi, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, dec) => String.fromCodePoint(Number(dec)))
    .replace(/&([a-z]+);/gi, (entity, name) => NAMED_ENTITIES[name.toLowerCase()] ?? entity);
  return text.replace(/\u0000(\d+)\u0000/g, (_, i) => spans[Number(i)]);
}

/**
 * Every anchor a page has on the site: one per heading — ATX (## Title) and
 * setext (a line underlined with === or ---) — plus explicit <a id/name>.
 */
export function anchorsOf(content) {
  const slugger = new GithubSlugger();
  const anchors = new Set();
  let previous = null; // the previous prose line, if it could be a setext heading's text
  mapProseLines(content.split('\n'), (line) => {
    const heading = ATX_HEADING.exec(line);
    if (heading) {
      anchors.add(slugger.slug(headingText(heading[1])));
    } else if (previous !== null && SETEXT_UNDERLINE.test(line)) {
      anchors.add(slugger.slug(headingText(previous.replace(/^(?:\s{0,3}>)*\s*/, '').trim())));
    }
    for (const m of line.matchAll(HTML_ANCHOR)) anchors.add(m[1]);
    previous = heading || BLOCK_START.test(line) || SETEXT_UNDERLINE.test(line) ? null : line;
    return line;
  });
  return anchors;
}

function hasAnchor(anchors, fragment) {
  if (fragment === '' || fragment === '_top') return true;
  let decoded = fragment;
  try {
    decoded = decodeURIComponent(fragment);
  } catch {
    // keep it as written; it will simply not match
  }
  return anchors.has(decoded);
}

// ---------------------------------------------------------------------------
// Link resolution
// ---------------------------------------------------------------------------

const SCHEME = /^[a-z][a-z0-9+.-]*:/i;

/**
 * Resolves one link target written in fromRepoPath. A link into a published
 * page also returns the anchor it needs, so the caller can check it exists.
 *
 * @param {object} ctx
 * @param {Map<string, {slug: string}>} ctx.pages published pages by repository path
 * @param {(repoPath: string) => 'file'|'dir'|null} ctx.stat
 * @param {string} ctx.base prefix for page routes: '' on the site, SITE in raw .md
 * @returns {{ url: string, anchor?: {repoPath: string, fragment: string} } | { error: string }}
 */
export function resolveLink(target, fromRepoPath, ctx) {
  if (target.startsWith('#')) {
    return { url: target, anchor: { repoPath: fromRepoPath, fragment: target.slice(1) } };
  }
  if (SCHEME.test(target) || target.startsWith('/') || target === '') {
    return { url: target };
  }
  const hash = target.indexOf('#');
  const pathPart = hash < 0 ? target : target.slice(0, hash);
  const fragment = hash < 0 ? '' : target.slice(hash);
  let decoded;
  try {
    decoded = decodeURI(pathPart);
  } catch {
    return { error: `malformed link target '${target}'` };
  }
  const resolved = path.posix.normalize(path.posix.join(path.posix.dirname(fromRepoPath), decoded)).replace(/\/$/, '');
  if (resolved.startsWith('..')) return { error: `'${target}' points outside the repository` };

  const pagePath = ctx.pages.has(resolved) ? resolved : `${resolved}/README.md`;
  const page = ctx.pages.get(pagePath);
  if (page) {
    const url = ctx.base + routeOf(page.slug) + fragment;
    return fragment ? { url, anchor: { repoPath: pagePath, fragment: fragment.slice(1) } } : { url };
  }

  const kind = ctx.stat(resolved);
  if (kind === 'file') return { url: BLOB + encodeURI(resolved) + fragment };
  if (kind === 'dir') return { url: TREE + encodeURI(resolved) + fragment };
  return { error: `'${target}' does not exist (resolved to ${resolved})` };
}

// ---------------------------------------------------------------------------
// Planning and writing
// ---------------------------------------------------------------------------

/**
 * Computes every output file without writing anything.
 *
 * @param {object} io
 * @param {(repoPath: string) => string} io.read
 * @param {(repoPath: string) => 'file'|'dir'|null} io.stat
 * @returns {{ files: Array<{path: string, content: string}>, sidebar: Array, pages: Map, errors: string[] }}
 */
export function planSite(io) {
  const errors = [];
  const summary = parseSummary(io.read('docs/SUMMARY.md'));

  /** @type {Map<string, {slug: string, title: string}>} */
  const pages = new Map();
  const bySlug = new Map();
  for (const entry of flattenSummary(summary)) {
    const repoPath = summaryTargetToRepoPath(entry.target);
    entry.repoPath = repoPath;
    if (io.stat(repoPath) !== 'file') {
      errors.push(`docs/SUMMARY.md lists ${entry.target}, which does not exist`);
      continue;
    }
    if (pages.has(repoPath)) {
      errors.push(`docs/SUMMARY.md lists ${entry.target} twice`);
      continue;
    }
    let slug;
    try {
      slug = pageSlug(repoPath);
    } catch (e) {
      errors.push(e.message);
      continue;
    }
    if (bySlug.has(slug)) {
      errors.push(`${repoPath} and ${bySlug.get(slug)} would both be published at ${routeOf(slug)}`);
      continue;
    }
    bySlug.set(slug, repoPath);
    pages.set(repoPath, { slug, title: entry.label });
  }

  // Every page's body and anchors first, so a link can be checked against the
  // anchors of a page that comes later in SUMMARY.
  const sources = new Map();
  for (const repoPath of pages.keys()) {
    const { data, body } = splitFrontmatter(io.read(repoPath));
    const content = dropLeadingH1(body);
    sources.set(repoPath, { data, content, anchors: anchorsOf(content) });
  }

  const files = [];
  for (const [repoPath, { slug, title }] of pages) {
    const { data, content } = sources.get(repoPath);
    const linkErrors = new Set();
    const rewriteFor = (base) => (target) => {
      const r = resolveLink(target, repoPath, { pages, stat: io.stat, base });
      if (r.error) {
        linkErrors.add(`${repoPath}: ${r.error}`);
        return target;
      }
      if (r.anchor && !hasAnchor(sources.get(r.anchor.repoPath).anchors, r.anchor.fragment)) {
        linkErrors.add(`${repoPath}: '${target}' — ${r.anchor.repoPath} has no heading or anchor '#${r.anchor.fragment}'`);
      }
      return r.url;
    };

    const frontmatter = { title, slug: slug === '' ? undefined : slug, editUrl: EDIT + repoPath };
    if (data.description) frontmatter.description = String(data.description).trim();
    files.push({
      path: `content/${slug === '' ? 'index' : slug}.md`,
      content: `---\n${YAML.stringify(frontmatter).trimEnd()}\n---\n\n${rewriteLinks(content, rewriteFor('')).trimStart()}`,
    });
    files.push({
      path: `public/${slug === '' ? 'index' : slug}.md`,
      content: `# ${title}\n\n${rewriteLinks(content, rewriteFor(SITE)).trimStart()}`,
    });
    errors.push(...linkErrors);
  }

  const sidebar = summary.groups.flatMap((group) => {
    const items = group.items.filter((i) => pages.has(i.repoPath)).map((i) => sidebarItem(i, pages));
    return group.label ? [{ label: group.label, items }] : items;
  });

  return { files, sidebar, pages, errors };
}

function sidebarItem(item, pages) {
  const link = { label: item.label, link: routeOf(pages.get(item.repoPath).slug) };
  const children = item.children.filter((c) => pages.has(c.repoPath));
  if (children.length === 0) return link;
  return {
    label: item.label,
    collapsed: children.length > 3,
    items: [link, ...children.map((c) => sidebarItem(c, pages))],
  };
}

/**
 * Replaces the generated content, public directory and sidebar under outDir
 * (Astro's own build output next to them is left to Astro). The public
 * directory is the committed static/ plus the raw .md pages.
 */
export function writeSite(outDir, staticDir, plan) {
  for (const dir of ['content', 'public']) fs.rmSync(path.join(outDir, dir), { recursive: true, force: true });
  fs.mkdirSync(path.join(outDir, 'public'), { recursive: true });
  fs.cpSync(staticDir, path.join(outDir, 'public'), { recursive: true });
  for (const file of plan.files) {
    const target = path.join(outDir, file.path);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, file.content);
  }
  fs.writeFileSync(path.join(outDir, 'sidebar.json'), `${JSON.stringify(plan.sidebar, null, 2)}\n`);
}

/** File-system io rooted at the repository. */
export function repoIo(repoRoot) {
  return {
    read: (p) => fs.readFileSync(path.join(repoRoot, p), 'utf8'),
    stat: (p) => {
      try {
        const s = fs.statSync(path.join(repoRoot, p));
        return s.isDirectory() ? 'dir' : 'file';
      } catch {
        return null;
      }
    },
  };
}

const siteDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const plan = planSite(repoIo(path.resolve(siteDir, '..')));
  if (plan.errors.length > 0) {
    console.error(`sync-docs: ${plan.errors.length} problem(s) in docs/ — nothing written:`);
    for (const e of plan.errors) console.error(`  - ${e}`);
    process.exit(1);
  }
  writeSite(path.join(siteDir, '.generated'), path.join(siteDir, 'static'), plan);
  console.log(`sync-docs: ${plan.pages.size} pages from docs/SUMMARY.md`);
}
