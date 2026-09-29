## 🔒 chore(security): allowlist the known gitleaks false positives of PRs #835, #838, #840 and #847 (2026-09-26)

**Repo:** EDDI (`chore/gitleaksignore-fix-prs`)

### What

The Secret Scanning job restores [`.gitleaksignore`](../../.gitleaksignore) from the base
branch before it scans a pull request, so a PR cannot allowlist its own false positives: the
entry only takes effect once it is on `main`. Four open PRs were red for that reason alone.
This adds their fingerprints to `main` so their scan passes when re-run:

- **#835** (`fix/outbound-http-hardening`): a stand-in OpenWeatherMap key in
  `WeatherToolExtendedTest`, used to prove the operator's key never reaches the model or the log.
- **#838** (`fix/secret-scope-vault`): one fixture token in `PrePostUtilsSecretScopeTest` and
  `PropertySetterTaskSecretScopePathsTest`, standing in for a user-entered secret-scope value.
- **#840** (`fix/workspace-authz-scoping`): a 32-hex stand-in for an export archive key's random
  part in `RestExportServiceTest`. The block is byte-identical to the one #840 carries and sits at
  the end of the file, so the two merge without a conflict; the other groups are inserted above
  the PR #750 block for the same reason.
- **#847** (`fix/deployment-defaults`): four `curl-auth-user` hits in `install.sh`, where the
  installer probes Grafana with its factory default admin login in order to rotate it to a
  generated password.

Each flagged line was read and confirmed to be a test fixture or Grafana's published default,
never a real credential. Local gitleaks, run per branch over `origin/main..<branch>` with the new
ignore file, reports 0 findings for all four.
