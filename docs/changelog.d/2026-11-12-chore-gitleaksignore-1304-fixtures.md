## 🔒 chore(security): allowlist #1304's dummy PEM fixtures in .gitleaksignore (2026-11-12)

**Repo:** EDDI (`chore/gitleaksignore-1304-fixtures`)

### What changed and why

PR #1304 (OAuth2 JWT-bearer and `private_key_jwt`) tripped the Gitleaks `Secret Scanning` check with `private-key` findings in one early commit. They are PEM armour lines written as literals in a Javadoc comment, a constant and some tests, with empty, placeholder or runtime-generated bodies. Gitleaks scans every commit of a PR range and reads `.gitleaksignore` from the base branch, so the PR cannot allowlist itself, and the branch history cannot be rewritten (no force-push). The commit's fingerprints are therefore added here, in the established per-PR block. Later commits on that branch already build the armour at runtime. No key material is involved and nothing authenticates against anything.

**Files:** [`.gitleaksignore`](../../.gitleaksignore)
