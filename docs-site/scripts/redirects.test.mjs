import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  buildRedirects,
  defaultTarget,
  docsUrlsInRepo,
  loadAll,
  parseLegacy,
  pathWorks,
  resolveLegacy,
  sectionSlug,
  servedRoute,
} from './redirects.mjs';
import { anchorsOf, dropLeadingH1, splitFrontmatter } from './sync-docs.mjs';

const siteDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = path.resolve(siteDir, '..');

const index = {
  slugs: new Set(['', 'secrets-vault', 'llm-parameters', 'langchain', 'upgrading-from-6.4', 'changelog', 'changelog/2026-10', 'getting-started']),
  sections: new Map([
    ['security-and-compliance', '/secrets-vault/'],
    ['agent-configuration', '/langchain/'],
    ['getting-started', '/getting-started/'],
  ]),
};

describe('parseLegacy', () => {
  it('reads paths with optional targets and skips comments', () => {
    assert.deepEqual(parseLegacy('# c\n/a\n\n/b /c/\n'), [
      { path: '/a', target: null },
      { path: '/b', target: '/c/' },
    ]);
  });

  it('refuses a line it cannot read', () => {
    assert.throws(() => parseLegacy('/a /b /c'), /cannot read/);
    assert.throws(() => parseLegacy('a'), /cannot read/);
  });
});

describe('sectionSlug', () => {
  it('matches the URL segment GitBook gave a SUMMARY heading', () => {
    assert.equal(sectionSlug('Security & Compliance'), 'security-and-compliance');
    assert.equal(sectionSlug('Getting Started'), 'getting-started');
  });
});

describe('defaultTarget', () => {
  it('takes the longest trailing part that is a page', () => {
    assert.equal(defaultTarget('/security-and-compliance/secrets-vault', index), '/secrets-vault/');
    assert.equal(defaultTarget('/agent-configuration/langchain/llm-parameters', index), '/llm-parameters/');
    assert.equal(defaultTarget('/reference/changelog/2026-10', index), '/changelog/2026-10/');
  });

  it('keeps the .md form', () => {
    assert.equal(defaultTarget('/security-and-compliance/secrets-vault.md', index), '/secrets-vault.md');
  });

  it("sends a section root to the section's first page, unless it is a page itself", () => {
    assert.equal(defaultTarget('/agent-configuration', index), '/langchain/');
    assert.equal(servedRoute('/getting-started', index), '/getting-started/');
  });

  it('gives up rather than guessing', () => {
    assert.equal(defaultTarget('/advanced-concepts/agent-father-deep-dive', index), null);
  });
});

describe('resolveLegacy', () => {
  it('reports a path with no page and a target that does not exist', () => {
    const { errors } = resolveLegacy(parseLegacy('/gone\n/old /not-a-page/\n/ok https://github.com/x'), index);
    assert.equal(errors.length, 2, errors.join('\n'));
    assert.match(errors[0], /no page for \/gone/);
    assert.match(errors[1], /\/old -> \/not-a-page\//);
  });

  it('marks a path that is a page as needing no redirect', () => {
    const { resolved } = resolveLegacy(parseLegacy('/secrets-vault'), index);
    assert.deepEqual(resolved, [{ path: '/secrets-vault', target: '/secrets-vault/', redirect: false }]);
  });
});

describe('buildRedirects', () => {
  const rules = (text) => buildRedirects(resolveLegacy(parseLegacy(text), index).resolved, index);
  const find = (all, source) => all.find((r) => r.source === source)?.destination;

  it('puts the docs.labs.ai host rule first', () => {
    const [first] = rules('');
    assert.deepEqual(first.has, [{ type: 'host', value: 'docs.labs.ai' }]);
    assert.equal(first.destination, 'https://docs.eddi.technology/:path*');
  });

  it('redirects the plain, trailing-slash and .md forms of a legacy path', () => {
    const all = rules('/security-and-compliance/secrets-vault');
    assert.equal(find(all, '/security-and-compliance/secrets-vault'), '/secrets-vault/');
    assert.equal(find(all, '/security-and-compliance/secrets-vault/'), '/secrets-vault/');
    assert.equal(find(all, '/security-and-compliance/secrets-vault.md'), '/secrets-vault.md');
  });

  it('leaves an external target alone in every form, .md included', () => {
    const all = rules('/old https://github.com/labsai/EDDI/blob/main/CONTRIBUTING.md');
    for (const source of ['/old', '/old/', '/old.md']) {
      assert.equal(find(all, source), 'https://github.com/labsai/EDDI/blob/main/CONTRIBUTING.md');
    }
  });

  it('adds the slash for page slugs with a dot, which trailingSlash treats as files', () => {
    assert.equal(find(rules(''), '/upgrading-from-6.4'), '/upgrading-from-6.4/');
  });

  it('redirects section roots and has a per-section fallback after the explicit rules', () => {
    const all = rules('/security-and-compliance/secrets-vault');
    assert.equal(find(all, '/agent-configuration'), '/langchain/');
    const fallback = all.findIndex((r) => r.source === '/security-and-compliance/:slug([^/.]+)');
    assert.ok(fallback > all.findIndex((r) => r.source === '/security-and-compliance/secrets-vault'));
    assert.equal(all[fallback].destination, '/:slug/');
    assert.equal(find(all, '/security-and-compliance/:slug([^/.]+)/'), '/:slug/');
  });
});

describe('pathWorks', () => {
  const resolved = resolveLegacy(parseLegacy('/old-name /secrets-vault/'), index).resolved;

  it('accepts pages in any form, legacy paths, section roots and section deep links', () => {
    for (const p of ['/', '/secrets-vault', '/secrets-vault/', '/secrets-vault.md', '/old-name', '/old-name.md', '/agent-configuration', '/security-and-compliance/secrets-vault', '/llms.txt']) {
      assert.ok(pathWorks(p, index, resolved), p);
    }
  });

  it('rejects anything else', () => {
    for (const p of ['/nope', '/security-and-compliance/nope', '/nope/secrets-vault/deeper']) {
      assert.ok(!pathWorks(p, index, resolved), p);
    }
  });

  // Only what the fallback rule can actually serve: no dotted slug, no section that is a page.
  it('rejects deep links the per-section fallback does not redirect', () => {
    assert.ok(!pathWorks('/agent-configuration/upgrading-from-6.4', index, resolved));
    assert.ok(!pathWorks('/getting-started/secrets-vault', index, resolved));
  });
});

describe('the real site', () => {
  const { plan, index: realIndex, resolved, errors } = loadAll(repoRoot);

  it('resolves every line of legacy-urls.txt', () => {
    assert.deepEqual(errors, []);
  });

  it('has vercel.json redirects generated from the current list (run `npm run redirects`)', () => {
    const committed = JSON.parse(fs.readFileSync(path.join(siteDir, 'vercel.json'), 'utf8')).redirects;
    assert.deepEqual(committed, buildRedirects(resolved, realIndex));
  });

  // The backend and the Manager print docs links (startup banners, the coordinator page), and
  // released images keep printing them forever: each must land on a page.
  it('serves every docs link written in the code, UIs, README, CONTRIBUTING, AGENTS and plugin', () => {
    const urls = docsUrlsInRepo(repoRoot);
    assert.ok(
      urls.some((u) => u.file.endsWith('VaultStartupBanner.java') && u.path === '/secrets-vault'),
      'the scan no longer finds the vault banner link — it is not looking where it should',
    );
    const broken = urls.filter((u) => !pathWorks(u.path, realIndex, resolved)).map((u) => `${u.file}: ${u.url}`);
    assert.deepEqual(broken, []);
  });

  it('has every #anchor in those links on the page it lands on', () => {
    const anchorsBySlug = new Map();
    for (const [repoPath, { slug }] of plan.pages) {
      const body = splitFrontmatter(fs.readFileSync(path.join(repoRoot, repoPath), 'utf8')).body;
      anchorsBySlug.set(slug, anchorsOf(dropLeadingH1(body)));
    }
    const missing = [];
    for (const u of docsUrlsInRepo(repoRoot)) {
      const [p, fragment] = u.path.split('#');
      if (!fragment) continue;
      const route = servedRoute(p, realIndex) ?? resolved.find((r) => r.path === p.replace(/\/+$/, ''))?.target;
      const slug = route?.replace(/^\/|\/$/g, '');
      if (!anchorsBySlug.get(slug)?.has(decodeURIComponent(fragment))) missing.push(`${u.file}: ${u.url}`);
    }
    assert.deepEqual(missing, []);
  });
});
