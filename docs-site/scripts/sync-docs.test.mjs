import assert from 'node:assert/strict';
import path from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  anchorsOf,
  dropLeadingH1,
  pageSlug,
  parseSummary,
  planSite,
  repoIo,
  resolveLink,
  rewriteLinks,
  SITE,
} from './sync-docs.mjs';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/** In-memory repository: { 'docs/a.md': '...', 'src/Foo.java': '', 'src/': null-ish dirs implied }. */
function memoryIo(files) {
  const dirs = new Set();
  for (const f of Object.keys(files)) {
    const parts = f.split('/');
    for (let i = 1; i < parts.length; i++) dirs.add(parts.slice(0, i).join('/'));
  }
  return {
    read: (p) => {
      if (!(p in files)) throw new Error(`no such file ${p}`);
      return files[p];
    },
    stat: (p) => (p in files ? 'file' : dirs.has(p) ? 'dir' : null),
  };
}

describe('parseSummary', () => {
  it('groups items under ## headings, nests indented items and ignores prose', () => {
    const { groups } = parseSummary(
      [
        '# Table of contents',
        '',
        '- [Home](README.md)',
        '',
        '## Guides',
        '',
        'Some prose GitBook ignored.',
        '- [LLM](langchain.md)',
        '  - [Parameters](llm-parameters.md)',
        '- [RAG](rag.md)',
      ].join('\n'),
    );
    assert.deepEqual(
      groups.map((g) => [g.label, g.items.map((i) => [i.label, i.children.map((c) => c.label)])]),
      [
        [null, [['Home', []]]],
        ['Guides', [['LLM', ['Parameters']], ['RAG', []]]],
      ],
    );
  });
});

describe('pageSlug', () => {
  it('flattens pages to their file name', () => {
    assert.equal(pageSlug('docs/secrets-vault.md'), 'secrets-vault');
    assert.equal(pageSlug('docs/monitoring/monitoring-guide.md'), 'monitoring-guide');
    assert.equal(pageSlug('docs/upgrading-from-6.4.md'), 'upgrading-from-6.4');
  });

  it('uses the directory for a README, and the root README is the home page', () => {
    assert.equal(pageSlug('docs/README.md'), '');
    assert.equal(pageSlug('docs/creating-your-first-agent/README.md'), 'creating-your-first-agent');
  });

  it('keeps the changelog/ prefix for the monthly archives', () => {
    assert.equal(pageSlug('docs/changelog/2026-10.md'), 'changelog/2026-10');
  });

  it('applies the overrides for names that make bad URLs', () => {
    assert.equal(pageSlug('docs/how-to....md'), 'faq');
    assert.equal(pageSlug('PRIVACY.md'), 'privacy');
  });

  it('refuses a page outside docs/ without an override', () => {
    assert.throws(() => pageSlug('SECURITY.md'), /only Markdown files under docs/);
  });
});

describe('dropLeadingH1', () => {
  it('drops the first H1 and the blank line after it', () => {
    assert.equal(dropLeadingH1('# Title\n\nBody\n'), 'Body\n');
  });

  it('keeps content that comes before the H1', () => {
    assert.equal(dropLeadingH1('<img src="x">\n# Title\n\nBody'), '<img src="x">\nBody');
  });

  it('leaves the page alone when an H2 comes first', () => {
    const body = '## Section\n\n# Late H1\n';
    assert.equal(dropLeadingH1(body), body);
  });

  it('ignores a "# comment" inside a code fence', () => {
    const body = '```bash\n# Linux / macOS\n```\n\n# Title\n\nBody';
    assert.equal(dropLeadingH1(body), '```bash\n# Linux / macOS\n```\n\nBody');
  });
});

describe('resolveLink', () => {
  const pages = new Map([
    ['docs/README.md', { slug: '' }],
    ['docs/hitl.md', { slug: 'hitl' }],
    ['docs/creating-your-first-agent/README.md', { slug: 'creating-your-first-agent' }],
  ]);
  const { stat } = memoryIo({
    'docs/README.md': '',
    'docs/hitl.md': '',
    'docs/changelog.md': '',
    'docs/creating-your-first-agent/README.md': '',
    'src/main/java/Foo.java': '',
  });
  const ctx = { pages, stat, base: '' };

  it('turns a published page into its route, keeping the anchor', () => {
    assert.deepEqual(resolveLink('hitl.md#resume', 'docs/security.md', ctx), {
      url: '/hitl/#resume',
      anchor: { repoPath: 'docs/hitl.md', fragment: 'resume' },
    });
    assert.deepEqual(resolveLink('../hitl.md', 'docs/monitoring/guide.md', ctx), { url: '/hitl/' });
    assert.deepEqual(resolveLink('README.md', 'docs/hitl.md', ctx), { url: '/' });
  });

  it('accepts a link to a published README by its directory', () => {
    assert.deepEqual(resolveLink('creating-your-first-agent/', 'docs/hitl.md', ctx), {
      url: '/creating-your-first-agent/',
    });
  });

  it('sends unpublished pages, source files and directories to GitHub', () => {
    assert.deepEqual(resolveLink('changelog.md', 'docs/hitl.md', ctx), {
      url: 'https://github.com/labsai/EDDI/blob/main/docs/changelog.md',
    });
    assert.deepEqual(resolveLink('../src/main/java/Foo.java#L10', 'docs/hitl.md', ctx), {
      url: 'https://github.com/labsai/EDDI/blob/main/src/main/java/Foo.java#L10',
    });
    assert.deepEqual(resolveLink('../src/main', 'docs/hitl.md', ctx), {
      url: 'https://github.com/labsai/EDDI/tree/main/src/main',
    });
  });

  it('prefixes page routes with the site in raw Markdown', () => {
    assert.deepEqual(resolveLink('hitl.md', 'docs/x.md', { ...ctx, base: SITE }), { url: `${SITE}/hitl/` });
  });

  it('leaves absolute URLs and root paths untouched', () => {
    for (const t of ['https://example.com/a.md', 'mailto:a@b.c', '/q/swagger-ui']) {
      assert.deepEqual(resolveLink(t, 'docs/hitl.md', ctx), { url: t });
    }
  });

  it('keeps a same-page anchor and asks for it to be checked on this page', () => {
    assert.deepEqual(resolveLink('#section', 'docs/hitl.md', ctx), {
      url: '#section',
      anchor: { repoPath: 'docs/hitl.md', fragment: 'section' },
    });
  });

  it('reports a missing target and a path that leaves the repository', () => {
    assert.match(resolveLink('nope.md', 'docs/hitl.md', ctx).error, /does not exist/);
    assert.match(resolveLink('../../outside.md', 'docs/hitl.md', ctx).error, /outside the repository/);
  });
});

describe('rewriteLinks', () => {
  const upper = (t) => t.toUpperCase();

  it('rewrites inline links, links with titles, angle-bracket targets and reference definitions', () => {
    const body = ['[a](x.md) and [b](y.md "Title") and [c](<z z.md>)', '[ref]: w.md'].join('\n');
    assert.equal(rewriteLinks(body, upper), ['[a](X.MD) and [b](Y.MD "Title") and [c](<Z Z.MD>)', '[ref]: W.MD'].join('\n'));
  });

  it('leaves fenced code and inline code alone', () => {
    const body = ['```md', '[a](x.md)', '```', '`[b](y.md)` [c](z.md)'].join('\n');
    assert.equal(rewriteLinks(body, upper), ['```md', '[a](x.md)', '```', '`[b](y.md)` [c](Z.MD)'].join('\n'));
  });

  it('treats a longer fence as closed only by a fence at least as long', () => {
    const body = ['````md', '```', '[a](x.md)', '```', '````', '[b](y.md)'].join('\n');
    assert.equal(rewriteLinks(body, upper), ['````md', '```', '[a](x.md)', '```', '````', '[b](Y.MD)'].join('\n'));
  });
});

describe('anchorsOf', () => {
  const anchors = (md) => [...anchorsOf(md)];

  it('slugs headings as GitHub and Astro do, numbering duplicates', () => {
    assert.deepEqual(anchors('## Retry Configuration\n## Retry Configuration\n### Private / Internal IP Blocking'), [
      'retry-configuration',
      'retry-configuration-1',
      'private--internal-ip-blocking',
    ]);
  });

  it('uses the rendered text: code and link text kept, emphasis and HTML gone', () => {
    assert.deepEqual(anchors('## The `max_tokens` **field** and [its docs](x.md) <sup>new</sup>'), [
      'the-max_tokens-field-and-its-docs-new',
    ]);
  });

  it('keeps intra-word underscores, which are not emphasis', () => {
    assert.deepEqual(anchors('## snake_case_name and _emphasis_'), ['snake_case_name-and-emphasis']);
  });

  it('finds headings inside blockquotes, keeping the emoji variation selector GitHub keeps', () => {
    assert.deepEqual(anchors('> ### ⚠️ Release tags are **not** `v`-prefixed'), ['️-release-tags-are-not-v-prefixed']);
  });

  it('decodes HTML entities the way the renderer does', () => {
    assert.deepEqual(anchors('### &#x20;Extensions REST API Endpoint'), ['-extensions-rest-api-endpoint']);
  });

  it('counts setext headings but not a thematic break after a blank line', () => {
    assert.deepEqual(anchors('Underlined Title\n---\n\nText\n\n---\n'), ['underlined-title']);
  });

  it('ignores "# comments" in code and adds explicit HTML anchors', () => {
    assert.deepEqual(anchors('```bash\n# not a heading\n```\n<a id="custom"></a>\n> ```\n> # quoted code\n> ```'), [
      'custom',
    ]);
  });
});

describe('planSite', () => {
  it('reports an anchor that does not exist, on another page or the same one', () => {
    const io = memoryIo({
      'docs/SUMMARY.md': '- [A](a.md)\n- [B](b.md)\n',
      'docs/a.md': '# A\n\n## Here\n\n[ok](#here) [ok](b.md#there) [bad](b.md#nowhere) [bad](#gone) [top](#_top)\n',
      'docs/b.md': '# B\n\n## There\n',
    });
    const { errors } = planSite(io);
    assert.equal(errors.length, 2, errors.join('\n'));
    assert.match(errors[0], /'b\.md#nowhere' — docs\/b\.md has no heading or anchor '#nowhere'/);
    assert.match(errors[1], /'#gone' — docs\/a\.md has no heading or anchor '#gone'/);
  });

  it('does not count the dropped H1 as an anchor, since the site does not render it', () => {
    const io = memoryIo({ 'docs/SUMMARY.md': '- [A](a.md)\n', 'docs/a.md': '# Title\n\n[self](#title)\n' });
    assert.match(planSite(io).errors.join('\n'), /no heading or anchor '#title'/);
  });

  it('fails on a SUMMARY entry without a file, a broken link and a slug collision', () => {
    const io = memoryIo({
      'docs/SUMMARY.md': '- [A](a.md)\n- [Gone](gone.md)\n- [B](b.md)\n- [B again](sub/b.md)\n',
      'docs/a.md': '# A\n\n[broken](missing.md)\n',
      'docs/b.md': '# B\n',
      'docs/sub/b.md': '# B\n',
    });
    const { errors } = planSite(io);
    assert.equal(errors.length, 3, errors.join('\n'));
    assert.match(errors.join('\n'), /lists gone\.md, which does not exist/);
    assert.match(errors.join('\n'), /docs\/a\.md: 'missing\.md' does not exist/);
    assert.match(errors.join('\n'), /would both be published at \/b\//);
  });

  it('writes Starlight content with frontmatter and a raw .md copy with absolute links', () => {
    const io = memoryIo({
      'docs/SUMMARY.md': '- [Home](README.md)\n\n## Guides\n\n- [The HITL Guide](hitl.md)\n',
      'docs/README.md': '---\ndescription: >-\n  Short\n  summary.\n---\n\n# EDDI\n\nSee [HITL](hitl.md).\n',
      'docs/hitl.md': '# HITL\n\nBack [home](README.md).\n',
    });
    const plan = planSite(io);
    assert.deepEqual(plan.errors, []);
    const file = (p) => plan.files.find((f) => f.path === p)?.content;

    assert.equal(
      file('content/index.md'),
      '---\ntitle: Home\neditUrl: https://github.com/labsai/EDDI/edit/main/docs/README.md\ndescription: Short summary.\n---\n\nSee [HITL](/hitl/).\n',
    );
    assert.equal(
      file('content/hitl.md'),
      '---\ntitle: The HITL Guide\nslug: hitl\neditUrl: https://github.com/labsai/EDDI/edit/main/docs/hitl.md\n---\n\nBack [home](/).\n',
    );
    assert.equal(file('public/hitl.md'), `# The HITL Guide\n\nBack [home](${SITE}/).\n`);
    assert.deepEqual(plan.sidebar, [
      { label: 'Home', link: '/' },
      { label: 'Guides', items: [{ label: 'The HITL Guide', link: '/hitl/' }] },
    ]);
  });

  // The real thing: every page listed in docs/SUMMARY.md, every link in it.
  it('builds the real docs/ without a single problem', () => {
    const plan = planSite(repoIo(repoRoot));
    assert.deepEqual(plan.errors, []);
    assert.ok(plan.pages.size > 80, `only ${plan.pages.size} pages`);
  });
});
