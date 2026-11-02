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

  // The decision: docs.labs.ai -> 301, kept forever. Vercel's `permanent: true` would be a 308.
  it('makes every rule an explicit 301, never `permanent`', () => {
    const all = rules('/security-and-compliance/secrets-vault\n/old https://github.com/x');
    assert.ok(all.length > 5);
    for (const rule of all) {
      assert.equal(rule.statusCode, 301, rule.source);
      assert.equal('permanent' in rule, false, rule.source);
    }
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
    const committed = JSON.parse(fs.readFileSync(path.join(repoRoot, 'vercel.json'), 'utf8')).redirects;
    assert.deepEqual(committed, buildRedirects(resolved, realIndex));
  });

  // The project's Root Directory is the repository root because it exists on every branch.
  // With docs-site as the Root Directory, Vercel failed every push to a branch without it —
  // the orphan `metrics` data branch, every 10-35 minutes — before any ignore step could run.
  // And it deploys `main` only: every other branch is skipped (decided 2026-10-27). A branch that
  // merged a broken main, or predates the site, would otherwise fail and email on every push.
  it('builds main only, from the repository root, and skips branches that have no docs-site', () => {
    const config = JSON.parse(fs.readFileSync(path.join(repoRoot, 'vercel.json'), 'utf8'));
    assert.equal(config.installCommand, 'cd docs-site && npm ci');
    assert.equal(config.buildCommand, 'cd docs-site && npm run build');
    assert.equal(config.outputDirectory, 'docs-site/.generated/dist');
    assert.match(config.ignoreCommand, /^\[ "\$VERCEL_ENV" != production \] \|\| \[ ! -d docs-site \] \|\| /);
    // Against the last successful deployment, not HEAD^: a cancelled docs build followed by a
    // non-docs commit must still publish. No previous SHA, or one not in the clone, builds.
    assert.match(config.ignoreCommand, /P=\$VERCEL_GIT_PREVIOUS_SHA; \[ -n "\$P" \] && git cat-file -e .*git diff --quiet "\$P" HEAD -- /);
    assert.doesNotMatch(config.ignoreCommand, /HEAD\^/);
    // Vercel refuses a longer one in the project settings, which carry the same command.
    assert.ok(config.ignoreCommand.length <= 256, `ignoreCommand is ${config.ignoreCommand.length} characters`);
    for (const p of ['docs-site', 'docs', 'PRIVACY.md', 'vercel.json']) {
      // Whole word: followed by a space or the closing `;`.
      assert.match(config.ignoreCommand, new RegExp(`HEAD --.*\\s${p.replace('.', '\\.')}[\\s;]`), p);
    }
    assert.equal(fs.existsSync(path.join(siteDir, 'vercel.json')), false, 'docs-site/vercel.json would be ignored by Vercel');
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
