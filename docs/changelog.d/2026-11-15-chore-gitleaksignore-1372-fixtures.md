## 🔒 chore(security): allowlist #1372's dummy echo fixtures in .gitleaksignore (2026-11-15)

**Repo:** EDDI (`feat/rag-document-delete`, carried by PR #1299)

### What changed and why

PR #1372 (redacting encoded echoes of a substituted secret from API-call responses) reports three Gitleaks `generic-api-key` findings in its first commit, all in `ApiCallExecutorEncodedEchoRedactionTest`: the base64 form of a hex-word placeholder secret, held in a constant named after a secret, and the base64 of the text "fake-token" under a `token` key, once in a body string and once in a map. Every value decodes to placeholder text and nothing authenticates against anything. A later commit of that PR renamed two of them, but Gitleaks scans the whole commit range and reads `.gitleaksignore` from the base branch, so the PR cannot allowlist itself and its history cannot be rewritten (no force-push). The three fingerprints are added in a `# PR #1372` block that does not quote the values.

The findings were located by replaying Gitleaks' default `generic-api-key` rule (regex plus the 3.5 entropy threshold; the repo has no `.gitleaks.toml`) over the PR's non-merge commits, because the CI log redacts them. The replay finds exactly three, matching the CI count.

### Design decisions

- The repository owner decided to fold these entries, together with #1410's entries for #1304, into PR #1299, which is already approved and merges next, because #1410's reviewer is away. #1410 is closed as superseded once #1299 merges.

**Files:** [`.gitleaksignore`](../../.gitleaksignore)
