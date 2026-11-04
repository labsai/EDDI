## 🐛 fix(loadtest): the load-test stack admits its plaintext mock LLM to the vault transport check (2026-11-03)

**Repo:** EDDI (`fix/loadtest-vault-plaintext`)

### What changed and why

The `Load Test DB Ops` workflow (check "DB ops per turn") went red on `main` from the 2026-11-03 nightly: `ci-gate-10vu-10t: 0 ok ... errors: app_error=100`. Every turn failed in the EDDI container with `VaultHostAllowlist$HostNotAllowedException: LLM provider 'openai' sends ${vault:...apiKey} to wiremock:8080 over unencrypted http`. #1175 added a separate plaintext-transport check, so a vault secret is no longer sent over `http://` to a non-loopback host unless `eddi.vault.allow-plaintext-remote-hosts=true`, even when the host is listed in `allowedHosts`. The load test points its OpenAI-compatible model at the plain-http mock `http://wiremock:8080/v1` and stores the API key as a vault secret, so every LLM call was refused before it left EDDI. The 2026-11-02 nightly ran before #1175 merged.

Both load-test compose files ([`docker-compose.loadtest.yml`](../../scripts/loadtest/docker-compose.loadtest.yml), [`docker-compose.cluster.yml`](../../scripts/loadtest/docker-compose.cluster.yml)) now set `EDDI_VAULT_ALLOW_PLAINTEXT_REMOTE_HOSTS=true`, the deployment-level opt-in the check documents. The setting still admits plaintext only to a host on the secret's own `allowedHosts`.

### Design decisions

- The opt-in goes on the throw-away benchmark stack, not into the driver: switching the mock to https would measure TLS rather than turns, and the check is correct in production.
