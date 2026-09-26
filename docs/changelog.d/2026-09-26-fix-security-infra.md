## 🔒 fix(infra): close default-open deployment surfaces across compose, installer, GCP, Helm, Kustomize and CI (2026-09-26)

**Repo:** EDDI (`fix/security-infra`)

### What changed and why

A pass over the deployment / IaC / CI-CD surfaces found the `AuthStartupGuard` /
`HighValueSurfaceGuard` protections were undercut by shipped defaults that
pre-set the unauthenticated opt-outs and bound services to all interfaces, so the
guards never protected a default deployment. Fixes, config/script/YAML only:

- **Compose bind to loopback.** [`docker-compose.yml`](../../docker-compose.yml)
  and [`docker-compose.postgres-only.yml`](../../docker-compose.postgres-only.yml)
  now publish EDDI on `127.0.0.1` by default with an `EDDI_BIND` override;
  exposing an unauthenticated instance off-box is now an explicit choice. Add-on
  overlays ([`chroma`](../../docker-compose.chroma.yml),
  [`ollama`](../../docker-compose.ollama.yml), [`nats`](../../docker-compose.nats.yml),
  [`monitoring`](../../docker-compose.monitoring.yml),
  [`openwebui`](../../docker-compose.openwebui.yml)) bind their published ports to
  `127.0.0.1`; Prometheus drops `--web.enable-lifecycle`; Grafana admin creds are
  overridable (dev default documented); the Open WebUI image is pinned off the
  rolling `:main`. `Dockerfile.demo` now runs as a non-root user.

- **GCP provisioner** ([`gcp/provision-vm.sh`](../../gcp/provision-vm.sh)):
  firewall rules are scoped to the caller's detected IP by default
  (`--source-ranges` to override, `--i-understand-public` to open to `0.0.0.0/0`);
  `create` refuses an open-access VM unless `--with-auth` or `--i-understand-public`
  is given; a random `KC_BOOTSTRAP_ADMIN_PASSWORD` is generated per VM (printed
  once) instead of `admin/admin`; the public Keycloak vhost blocks `/admin`; the
  success banner no longer prints stale `eddi/eddi` credentials.

- **Installer** ([`install.sh`](../../install.sh)): `.env`/`.eddi-config` created
  mode `600` before the vault key is written; `EDDI_BRANCH` derives from a pinned
  `EDDI_VERSION` (release tag) rather than always `main`, in the installer and the
  generated `eddi update` CLI; auth wizard clarifies the localhost-only default.

- **CI/CD** ([`.github/workflows/ci.yml`](../../.github/workflows/ci.yml)): a
  release tag build now fails unless the tagged commit is an ancestor of
  `origin/main`; cosign signs the pushed image **digest** rather than a tag; the
  cosign identity regex is tightened from `tags/.+` to the release-tag pattern
  (also in NOTES.txt, [`docs/release-signing.md`](../../docs/release-signing.md),
  [`docs/build-reproducibility.md`](../../docs/build-reproducibility.md)).
  [`.github/workflows/auto-approve-copilot.yml`](../../.github/workflows/auto-approve-copilot.yml)
  pins the auto-approval to the reviewed commit (`commit_id`) and skips PRs that
  touch `.github/**`.

- **Helm** ([`helm/eddi`](../../helm/eddi)): the chart refuses to render when
  `ingress.enabled && !oidc.enabled` unless `eddi.security.allowUnauthenticated`
  is set explicitly; `EDDI_MCP_ALLOW_UNAUTHENTICATED` /
  `EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED` are no longer derived from OIDC being
  off (default `false`, require explicit opt-in); NOTES.txt warns when
  `ingress.tls` is empty. In-chart MongoDB now runs with `--auth` and a required
  `mongodb.rootPassword`, its credentialed connection string moving to the
  projected Secret; NetworkPolicies isolate the MongoDB / PostgreSQL / NATS /
  Keycloak pods to the EDDI pod.

- **Kustomize**: PKCE `S256` added to the `eddi-frontend` client across all three
  realm copies ([compose](../../keycloak/eddi-realm.json),
  [helm](../../helm/eddi/files/eddi-realm.json),
  [k8s](../../k8s/overlays/auth/eddi-realm.json)); NetworkPolicies isolate the
  [MongoDB](../../k8s/overlays/mongodb/mongodb-networkpolicy.yaml) and
  [NATS](../../k8s/overlays/nats/nats-networkpolicy.yaml) overlays.

- **Container image** ([`src/main/docker/Dockerfile`](../../src/main/docker/Dockerfile)):
  application files are copied `--chown=root:0` so the runtime user (185) cannot
  overwrite its own jars; `USER 185` and the digest-pinned base are unchanged.

### Deliberately deferred / not changed (verified against current files)

- Keycloak Helm `KC_BOOTSTRAP_ADMIN_PASSWORD` stays `value: {{ required … }}` — a
  deliberate two-path design (`secretKeyRef` on kustomize, `required` value on
  Helm) that `DeploymentManifestsTest` enforces.
- Seeded `viewer/viewer` and `user/user` and `eddi-frontend` direct-access-grant
  are kept: the Auth E2E tier (`ui/manager/scripts/make-test-realm.mjs`,
  `e2e/auth`) depends on both, and the realm-drift tests require one shared realm.

```decision-log
| 2026-09-26 | Bind EDDI to 127.0.0.1 by default in compose, opt-in `EDDI_BIND` to expose | An unauthenticated default must not be network-reachable out of the box; MongoDB was already loopback-bound, EDDI was not |
| 2026-09-26 | Helm: require explicit opt-in for /mcp and /secretstore when OIDC is off | Deriving the opt-outs from oidc.enabled silently opened the two highest-value surfaces, defeating HighValueSurfaceGuard |
| 2026-09-26 | CI: sign the image digest and require the release tag to be an ancestor of main | A tag on any commit could publish and sign :latest, bypassing review; a digest signature cannot be moved by a later tag push |
```

## 🔒 fix(infra): review follow-ups on the loopback default and demo image (2026-09-26)

**Repo:** EDDI (`fix/security-infra`)

### What changed and why

Follow-ups from adversarial review of the change above:

- **The loopback default broke the GCP remote-deploy path.** With EDDI bound to
  `127.0.0.1`, the provisioner's health poll against `http://<external-ip>:7070`
  never succeeded and the advertised dashboard/API/MCP were unreachable on the
  VM. [`gcp/provision-vm.sh`](../../gcp/provision-vm.sh) now exports
  `EDDI_BIND=0.0.0.0` into the VM startup script (off-box exposure stays governed
  by the IP-scoped firewall and the auth/`--i-understand-public` gate);
  [`install.sh`](../../install.sh) exports and persists `EDDI_BIND` to `.env` so
  `eddi restart` keeps it. Monitoring (Grafana/Prometheus) stays loopback-bound on
  the VM and the success banner now says SSH-tunnel/localhost instead of falsely
  advertising the external IP; the pointless 3000/9090 firewall rule is dropped.
- **`Dockerfile.demo`** now pre-creates `/opt/eddi/data` (`chown 185:0`) before
  `USER 185`, mirroring the production image — a non-root process cannot create
  the audit dead-letter directory at runtime, and the miss silently drops audit
  entries.
- **Helm `NOTES.txt`** warns that the shipped realm seeds `viewer/viewer` and
  `user/user` with known passwords and that they must be disabled/re-passworded
  for production (removing the seed users stays deferred — the Auth E2E tier needs
  them; the tracked follow-up is to derive the E2E realm from a passwordless
  shipped realm and disable ROPC on `eddi-frontend`).
- **Cross-branch dependency:** the secrets branch adds a vault master-key strength
  gate that rejects the placeholder key
  [`docker-compose.openwebui.yml`](../../docker-compose.openwebui.yml) defaults
  to; that file now sets `EDDI_VAULT_ALLOW_WEAK_MASTER_KEY=true` (an opt-out that
  branch is adding; harmless as an unknown env var until it merges) so the demo
  keeps booting.
