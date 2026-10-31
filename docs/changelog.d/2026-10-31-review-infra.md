## Deployment and script hardening from the code review (2026-10-31)

Repo: EDDI, branch `fix/review-infra`. Low-severity findings in operator scripts and manifests.

- `install.sh`: every Keycloak admin call now sends its bearer token through a 0600 curl config file (`kc_authed_curl`) instead of `-H` on the command line, so it no longer shows in `/proc/<pid>/cmdline`. The already-running repair path verifies the downloaded `keycloak/eddi-realm.json` against the release `SHA256SUMS` before posting scope definitions to Keycloak, and no longer sends stored Keycloak/Grafana admin passwords to a localhost port unless Docker reports the matching compose service as running.
- `gcp/provision-vm.sh`: `--vault-key` is removed. The key was appended to the installer flags, uploaded as startup-script instance metadata and echoed into `/var/log/eddi-install.log`; the VM now generates its own master key (in `~/eddi/.env`), like the Keycloak admin password. Passing the flag is an error that says why.
- `docker-compose.yml`, `docker-compose.cluster.yml`, `docker-compose.postgres-only.yml`: the bind guard fails closed. Only `false`/`0`/`no`/`off` (any case) count as "authentication on"; `TRUE`, `1`, `yes`... no longer slip past the refusal.
- `docker-compose.auth.yml`: `keycloak-admin-rotate` writes the `.eddi-admin-verified` marker only after a successful login with the configured credentials. A renamed `KC_BOOTSTRAP_ADMIN_USERNAME` on an existing volume now fails instead of certifying a volume whose `admin/admin` may remain.
- Helm: `nats.externalUrl` containing `@` is refused (it lands in a ConfigMap); new `nats.externalUsername` / `nats.externalPassword` go to the chart Secret. `docs/kubernetes.md` updated.
- Kustomize: warnings in the ingress component (plain HTTP as shipped) and in the auth overlay (remove the localhost redirect URIs outside dev). `k8s/create-secrets.ps1` creates the temporary key file empty and `chmod 600` before writing the key on Linux/macOS.
- `docs-site/scripts/check-live.mjs`: redirects are followed only to the configured origins (parsed origin comparison, not string prefix).
- `scripts/cluster-demo/mock_llm.py`: 10 s socket timeout, 1 MiB body cap, `MOCK_LLM_BIND` to bind loopback on Docker Desktop. `scripts/loadtest/loadtest.py`: startup state moved from the world-writable `results/` to `scripts/loadtest/.state/` (0700), written atomically, symlink ignored on read. `src/main/docker/seed-demo-agent.sh`: the provider key goes to curl on stdin, not `-d`.

Decision: the shipped k8s base (unauthenticated quickstart), the Prometheus overlay and the other BY_DESIGN findings are unchanged.
