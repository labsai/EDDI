## fix(security): the four leftovers of the 2026-10-17 security review (2026-10-18)

**Repo:** EDDI · **Branch:** `fix/security-review-leftovers`

The 2026-10-17 review was remediated in PRs #1079–#1094. Four items were left on purpose and
are closed here, one commit each.

### 1. Keycloak refuses the admin/admin fallback off loopback

[`docker-compose.auth.yml`](../../docker-compose.auth.yml) falls back to `admin`/`admin` for the
Keycloak bootstrap admin so the loopback quick start needs no `.env`. That was safe only while
`KEYCLOAK_BIND` stayed on loopback, and nothing enforced it. A new one-shot `keycloak-guard`
service, the same pattern as `docker-compose.yml`'s `bind-guard`, now exits 1 (and Keycloak,
which waits for it with `service_completed_successfully`, never starts) when `KEYCLOAK_BIND` is
not loopback while `KC_BOOTSTRAP_ADMIN_PASSWORD` is unset or `admin`. It reads both values from its
own environment with the defaults the keycloak service uses. It runs on the Keycloak image the overlay pulls anyway, so it adds no image to
either base stack (`docker-compose.postgres-only.yml`'s own guard avoids the mongo image for the
same reason). The installers and
`gcp/provision-vm.sh` already generate a password, so their paths pass unchanged.

- `ComposeStackTest` pins the wiring (dependency condition, same password expression, same bind
  variable as the published port) and runs the guard script itself through `sh` for five
  bind/password combinations; that test is skipped only where no POSIX `sh` is on `PATH`.
  `adminPasswordsAreOverridable` now checks that each admin-password variable appears at least
  once, since the guard repeats `KC_BOOTSTRAP_ADMIN_PASSWORD`.
- Verified by hand with `docker compose run keycloak-guard` against loopback, `0.0.0.0` with no
  password, with `admin`, with an empty one and with a real one.

### 3. The release signs `SHA256SUMS`, and the installers verify it

PR #1088 made the release publish a `SHA256SUMS` that `install.sh` / `install.ps1` check every
download against, but the list itself was unsigned, so whoever could replace one release asset
could replace both. The `release` job in [`ci.yml`](../../.github/workflows/ci.yml) now gets
`id-token: write`, installs cosign with the same pinned installer as the image job, signs the
list keyless (`cosign sign-blob --yes --bundle SHA256SUMS.sigstore.json SHA256SUMS`), verifies
the bundle against the exact identity the installers demand, and attaches it to the release.

Both installers, when `cosign` is on `PATH`, fetch the bundle from the same release and run
`cosign verify-blob` with `--certificate-identity
https://github.com/labsai/EDDI/.github/workflows/ci.yml@refs/tags/<tag>` (exact, not a regexp:
the certificate must name this release's own tag) and `--certificate-oidc-issuer
https://token.actions.githubusercontent.com`, BEFORE any hash in the list is trusted. A list that
fails stops the install. Without cosign they print one warning line, and a release from before
the bundle existed is used unsigned with a warning. Releases cut before this change keep working.

- Tests: `InstallerReleaseRefTest.checksumListIsSignedAndVerified` checks the release job and both
  installers as text, including that verification precedes trust.
  `shellVerifiesTheChecksumSignature` runs install.sh's own functions with a stub curl and a stub
  cosign: one warning without cosign; the exact identity, issuer and bundle URL with it; refusal on
  a failed verification; the unsigned fallback when there is no bundle. It was mutation-checked
  by forcing verification to pass. The PowerShell function was run the same way by hand, with a
  stub `cosign.cmd`, in all four cases.
- Not verified end to end: keyless signing needs the GitHub OIDC token, so the first real
  signature appears on the next release tag. The job verifies it before publishing.
- Docs: `docs/security.md`, "Installer Downloads", with the manual `cosign verify-blob` command.

### 4. `read_audit_trail` no longer shows a USE-only owner the agent's prompt

Finding: yes, it did. `LlmTask` writes `audit:compiled_prompt` as the compiled system message,
`---`, and the processed prompt template; `LifecycleManager.buildAuditEntry` copies it into
`AuditEntry.llmDetail.compiledPrompt`, and the MCP `read_audit_trail` tool returned entries as
stored to any `eddi-viewer` who owned the conversation. Under enforced workspaces an agent shared
with `USE` lets the recipient chat with it but not read its configuration, so the audit trail of
their own conversation was a way around that. (`/auditstore` over REST and the GDPR export are
`eddi-admin` only and unaffected.)

[`McpConversationTools`](../../src/main/java/ai/labs/eddi/engine/mcp/McpConversationTools.java)
now replaces `compiledPrompt` with a redaction marker unless
`ResourceAccessGuard.hasAccess(agentId, VIEW)` holds. That answers OWN for an admin and whenever
workspaces are not enforced, so nothing changes there. VIEW on the agent rather than on each LLM
config: sharing materialises the agent's level onto its config graph (`ConfigGraphResolver`), so
it is the same decision without a graph walk per entry. Model name, token usage, cost and the
model's reply stay; the access lookup is made once per agent per call and skipped for entries
with no prompt. Tests in `McpConversationToolsOwnershipTest` pin both halves (USE-only owner gets
the marker and no prompt text; VIEW passes the entry through untouched), mutation-checked by
reverting the call.

### 2. EDDI connects to MongoDB as its own user, not as root

Compose, Helm and the kustomize MongoDB overlay connected EDDI as the MongoDB root user with
`authSource=admin`, so anything that could read EDDI's connection string held the whole server.
EDDI now connects as `eddi_app`, defined in the `eddi` database with `readWrite` and `dbAdmin`
there and nothing elsewhere (`dbAdmin` because `MongoResourceStorage` un-hides an index with
`collMod`, which `readWrite` lacks).

- **Not an initdb script, deliberately.** The mongo image runs `/docker-entrypoint-initdb.d`
  only on an empty volume, so every existing deployment would have pointed EDDI at a user that
  does not exist. Instead a helper with the root credentials creates the user, or resets its
  password and roles, on every start: a one-shot `mongodb-app-user` service that `eddi` waits for
  in [`docker-compose.yml`](../../docker-compose.yml), `docker-compose.openwebui.yml` and
  `docker-compose.cluster.yml`, and an `ensure-mongodb-user` init container (restricted-PSS
  security context, bounded wait for MongoDB) in the Helm Deployment and in
  `k8s/overlays/mongodb`. Existing Compose and Helm installs migrate on the next `up` /
  `helm upgrade`, and changing the app password rotates it.
- **Defaults that do not break an existing install.** Compose's `MONGO_APP_PASSWORD` falls back to
  the root password (documented as "set a distinct one"). Helm's `mongodb.appPassword` falls back
  to `sha256("eddi-app:" + rootPassword)`: stable across renders without `lookup`, distinct from
  and not reversible to the root password. Kustomize's operator-created Secret gets two new keys
  in the documented command; for an old Secret the `MONGO_APP_*` references are optional and the
  init container does nothing, so EDDI keeps working as root until the documented patch is
  applied.
- The `mongodb` healthchecks no longer carry the root password (`ping` needs no auth).
- Helm chart `2.9.0` → `2.10.0` (new values `mongodb.appUsername`, `mongodb.appPassword`).
- Docs: `docs/upgrading-from-6.5.md` (new "MongoDB least-privilege user" section, plus the
  Keycloak guard from item 1), `docs/docker.md`, `docs/kubernetes.md`, `docs/troubleshooting.md`,
  `.env.example`.
- Tests: `ComposeStackTest.eddiDoesNotConnectAsMongoRoot` and
  `DeploymentManifestsTest.helmEddiDoesNotConnectAsMongoRoot` / `kustomizeEddiDoesNotConnectAsMongoRoot`
  (both mutation-checked); the CI manifest-lint step now checks the app password is URL-encoded
  and that the root password never reaches EDDI's connection string.
- Verified in Docker with `labsai/eddi:6.5.0`: a volume written by the old root-connected stack,
  restarted on the new compose file, keeps its data, EDDI writes as `eddi_app`, a second `up`
  updates rather than fails, `eddi_app` is refused on `admin`, and a fresh volume works. The init
  container's script was run as uid 185 on a read-only root filesystem with all capabilities
  dropped, against a MongoDB still starting up. `helm template`/`helm lint` (default,
  `existingSecret`, PostgreSQL-only, special-character password) and `kubectl kustomize` of every
  overlay and example render.
- Not changed: `scripts/loadtest/docker-compose.loadtest.yml` (a load-test harness, still root) and
  `k8s/quickstart.yaml` (its MongoDB runs without authentication at all).
