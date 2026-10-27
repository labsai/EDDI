import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { it } from 'node:test';
import { fileURLToPath } from 'node:url';

const siteDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

// The repository's licence policy lives in the Dependency Review workflow, which reads every
// lockfile on a pull request — this one included. Checking the same deny-list here fails
// `npm test` on the change that adds such a package, instead of a PR check after the push.
// (sharp's libvips binaries, LGPL-3.0, were the first hit: see stubs/sharp.)
it('has no package under a licence dependency-review.yml denies', () => {
  const workflow = fs.readFileSync(path.join(siteDir, '../.github/workflows/dependency-review.yml'), 'utf8');
  // The folded value is the indented lines after `>-`, up to the first comment line (the
  // comments below it are indented just as deep). [^\n] rather than '.': a Windows checkout
  // has CRLF line endings, and '.' stops at '\r'.
  const block = /deny-licenses:[ \t]*>-[ \t]*\r?\n((?:[ \t]{10,}[^#\s][^\n]*\n)+)/.exec(workflow);
  assert.ok(block, 'could not find deny-licenses in dependency-review.yml');
  const denied = new Set(block[1].split(/[,\s]+/).filter(Boolean));
  assert.ok(denied.has('LGPL-3.0-or-later') && denied.size === 15, `deny-list looks wrong: ${[...denied]}`);
  assert.ok(![...denied].some((id) => /^(Apache|MIT|BSD|ISC)/.test(id)), `deny-list parse ran into the comments: ${[...denied]}`);

  const lock = JSON.parse(fs.readFileSync(path.join(siteDir, 'package-lock.json'), 'utf8'));
  const hits = [];
  for (const [name, pkg] of Object.entries(lock.packages)) {
    const ids = String(pkg.license ?? '').match(/[A-Za-z0-9.+-]+/g) ?? [];
    const bad = ids.filter((id) => denied.has(id) || denied.has(`${id}-only`) || denied.has(id.replace(/\+$/, '-or-later')));
    if (bad.length) hits.push(`${name} (${pkg.license})`);
  }
  assert.deepEqual(hits, []);
});
