## chore(ci): gitleaks ignore entries for five made-up keys in Manager test fixtures (2026-11-18)

**Repo:** EDDI (`chore/gitleaksignore-manager-editor-fixtures`)

### What changed and why

`Secret Scanning` fails on PR #1470 (`fix/manager-pages-and-editors`) with five findings of the
`generic-api-key` rule. All five are made-up values in Manager tests added by commit `eb9c4afa65` of
that branch: the literal typed into `SecretKeyPicker` in `secret-key-picker-literal.test.tsx` (four
lines) and the `apiKey` of the fixture document in `mask-credentials.test.ts` (one line). They exist
so that the tests have a plaintext literal for the picker to warn about and for the diff mask to hide.

A later commit of the same branch already rebuilt those fixtures so the current tree does not match
the rule. That does not clear the check: gitleaks scans every commit of the pull request's range, and
`ci.yml` restores `.gitleaksignore` from the base branch on a pull request, so a PR cannot allowlist
its own findings. The entries therefore have to reach `main` first, in their own change.

`.gitleaksignore` gains the five fingerprints with a comment saying what they are. The fingerprints
were produced by gitleaks 8.30.1 (the version `ci.yml` pins) over the range of #1470, not written by
hand; with them the same scan reports no leaks.

### Design decisions

- Fingerprints name the commit, file, rule and line, so they ignore exactly these five occurrences
  and nothing else in those files or commits.
- No `.gitleaks.toml` path allowlist: a path rule would also hide a real credential pasted into a
  test later.

### What's next

After this is merged, #1470 needs one more push (or a re-run on a new event) so that its scan reads
the new base; a re-run of the old event keeps the old base and stays red.
