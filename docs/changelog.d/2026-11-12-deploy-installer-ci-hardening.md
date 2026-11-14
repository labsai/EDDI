## 🔒 fix(deploy): deployment, installer and CI hardening (2026-11-12)

**Repo:** EDDI (`fix/deploy-installer-ci-hardening`)

### What changed and why

A security review of the deployment, installer and CI files turned up thirteen
findings. Twelve are fixed here; the OpenTelemetry advisory is not (below).

**Kubernetes.** `k8s/quickstart.yaml` and `k8s/base` ship EDDI with all three
`*_ALLOW_UNAUTHENTICATED` flags on, so every pod in the cluster was an
administrator. Both now carry an `eddi-ingress` NetworkPolicy admitting only the
pods of EDDI's own namespace on port 7070; the `ingress` component adds
`eddi-ingress-controller` for a controller in another namespace. Their comments
and [`docs/kubernetes.md`](../kubernetes.md) say that every admitted pod is still an
unauthenticated administrator and that NetworkPolicy needs an enforcing CNI. The
one-file quickstart MongoDB stays unauthenticated: the authenticated pattern in
`overlays/mongodb` needs a separately created Secret, an init container and a
second config file, which does not fit one `kubectl apply -f`.
`create-secrets.sh` and `create-secrets.ps1` print the generated vault master key
only to a terminal; with output redirected they name the Secret instead of
putting the key in a CI log.

**SSRF protection.** The application default stays off (compose service names and
a local Ollama are private addresses). The Helm chart now has
`eddi.security.ssrfProtection.enabled` (empty follows the install: on when
`eddi.oidc.enabled` or `ingress.enabled`) and `.allowedHosts` (default
`127.0.0.1:7070`, the Platform Operator's self-call); `k8s/overlays/production`
sets both properties; the compose files and `.env.example` carry commented
`EDDI_SECURITY_SSRF_PROTECTION_ENABLED` and `EDDI_SECURITY_SSRF_PROTECTION_ALLOWED_HOSTS` lines. Helm chart version 2.15.0.

**Helm TLS.** An OIDC-enabled Ingress without `ingress.tls` fails the render unless
`ingress.allowPlainHttp=true` acknowledges TLS terminated elsewhere; the kustomize
ingress overlay says the same next to its commented `tls:` block.

**Installers.** `eddi update` discarded the installer's verification: it re-downloaded
every compose file and monitoring asset with plain curl, so a moved tag or the
`main` fallback silently replaced the files that run with the vault key and the
database credentials. The bash CLI now fetches `SHA256SUMS` (and verifies its cosign
signature when cosign is installed) and compares every file before moving any
into place; `eddi.cmd` hands the download to a generated `eddi-fetch.ps1` that embeds
the installer's own functions. An install pinned to a release refuses to fall back
to `main` when the release lookup fails (`EDDI_BRANCH=main eddi update` opts in).
`install.ps1` also gained what `install.sh` already had: Keycloak and Grafana
credentials go to `127.0.0.1` and only after Docker confirms this project's
container publishes the port, and `.env`, `.eddi-config` and `first-login.txt` are
created owner-only before they are written under pwsh on Linux and macOS.

**GCP.** With `--https` the provisioner opens no direct EDDI firewall rule, binds
EDDI to the VM's loopback and drops the plain-http links; readiness is polled over
HTTPS. The VM gets `--no-service-account --no-scopes`. `--eddi-version` and
`--eddi-branch` are validated with the installer's patterns before being spliced
into root scripts, and the startup script checks `install.sh` against the release's
`SHA256SUMS` (the release job in `ci.yml` now lists it) before running it as root.
The `eddi-frontend` client loses its loopback redirects and `+` web origin when a
public domain is configured, as the Helm chart does.

**CI.** `docker-pull-notify.yml` coerces cache-restored values to integers and
passes them to the delta step through `env`; `base-image-check.yml` does the same for
values parsed from the Dockerfile. `ci.yml` restores the Maven cache by exact key
only on tag builds. `auto-approve-copilot.yml` protects the rest of the trust
boundary (URL validation, integrations, mail, HITL, templating, REST and MCP
surface, export/import, resources, tests, the Platform Operator, the assistant
instructions, docs-site, docker, `.env.example`); `AutoApproveCopilotPolicyTest`
moved `src/main/resources/application-test.properties` and `src/test/**` from the
"ordinary" list to the protected one.

**Docs.** `.env.example` no longer claims the compose file supplies a demo vault key:
an empty `EDDI_VAULT_MASTER_KEY` disables the vault, and the compose files say so.

### Review follow-ups

`eddi update` (bash and Windows) now stages the compose files **and** the monitoring
assets, verifies both, and only then replaces anything; the recorded `EDDI_VERSION` and
`EDDI_BRANCH` change after the files are in place, and a directory at an asset path is
removed only when empty and only after verification. A signed release is refused when no
SHA-256 utility exists (installer and CLI). `install.ps1` stops when `chmod 600` fails.
The Helm chart rejects a non-boolean `ingress.allowPlainHttp` and an unrecognised
`eddi.security.ssrfProtection.enabled` (a quoted "false" is truthy to a Go template); the
SSRF allow-list default gains `localhost:7070` (exact host text; the reference agent's
httpCalls use it). The secret generators print the key only with `--show-key` / `-ShowKey`
(a TTY or `IsOutputRedirected` test cannot tell a log from a screen). On the GCP VM the
public nginx site is enabled only after the Keycloak client update returned 204 (otherwise
the setup stops with 443 closed and says how to retry), health probes have `--max-time`,
and the HTTPS banner shows tunnel commands for a VM whose HTTPS is not up.

Second review round: every file `eddi update` fetches is required. If a compose file or a
monitoring asset cannot be downloaded the update is refused with nothing replaced and
nothing recorded (before, the old file was kept while the new version and ref were
written, starting the new image on an old compose file); `install.ps1` and the generated
`eddi-fetch.ps1` follow the same rule. The refusal hint uses `--eddi-version=<tag>`,
`eddi.cmd` trims a trailing space from `EDDI_BRANCH`, and the docs give a cmd and a
PowerShell form scoped to one run. The Windows secret helper prints a PowerShell backup
command. The GCP HTTPS-failure banner no longer promises a tunnelled login (the public
issuer and redirects make it impossible) and gives the log, certbot, re-run and health
steps instead. The batch kubectl stub keeps spaces in the key path, and a run that
created the Secret without a captured key now fails the test.

Third round: the generated `eddi-fetch.ps1` keeps each replaced file aside as
`<name>.eddi-old` and restores every file already replaced if a later move fails, so the
Windows update is all-or-nothing; the GCP wait loops cap each probe and each sleep to the
time left in their 120 s / 900 s budgets; the Windows `EDDI_BRANCH` examples (docs and the
text `eddi.cmd` prints) use `set "EDDI_BRANCH=main"` and show how to clear the variable.

Fourth round: a rollback that cannot restore a file is reported (the file is named, its
`<name>.eddi-old` original is kept, exit code 4 and a distinct `eddi.cmd` message) instead of
being called restored; `Initialize-SensitiveFile` protects an existing `.env` before emptying
it; the cluster dashboard is a monitoring asset in both installers (a test pins parity with
the compose bind mounts); `eddi update --local` keeps the checkout's `docker-compose.local.yml`
when the ref does not carry it (the only optional file); the PowerShell `EDDI_BRANCH` example
restores the previous value; the 120 s update wait recomputes the time left for each probe.

Fifth round: the required monitoring assets are no longer a fixed list (which made an older
release such as 6.5.0 uninstallable once the cluster dashboard was added). Fresh installs and
`eddi update`, on both platforms, read them from the selected ref's own
`docker-compose.monitoring.yml` after it was fetched and verified; every regular file it mounts
stays required, `..` and absolute paths are ignored. `eddi-fetch.ps1` refuses to start (exit 5,
its own `eddi.cmd` message) while a `<name>.eddi-old` from a failed rollback exists, so it can
no longer overwrite the preserved original. In `eddi update` (bash) a directory at an asset path
is removed before the first replacement.

### Not changed

`io.opentelemetry:opentelemetry-api` resolves to 1.62.0 through the Quarkus platform
BOM (GHSA-ccrj-gw8r-854m, fixed in 1.67.0). Quarkus platform 3.40.1.2, the newest
patch of the line, still manages 1.62.0, and importing `opentelemetry-bom` 1.67.0
alone would split the API from the `opentelemetry-api-incubator` and SDK artifacts
the Quarkus extension is compiled against, which cannot be validated without the
container tests. Tracing ships off (`quarkus.otel.sdk.disabled=true`); keep it off
on an untrusted network until a platform release carries 1.67.0.

### Design decisions

- The auto-approve header comment (approval is additional to a human review, `main`
  must require two) matches `docs/code-review-standards.md` and was left as written;
  the setting itself is not something a file can read.
- Per-finding policy for non-breaking defaults: SSRF protection follows the install
  (on only where OIDC or an Ingress makes the pod reachable by others) rather than
  flipping the application default.

**Files:** [`helm/eddi/templates/configmap.yaml`](../../helm/eddi/templates/configmap.yaml),
[`install.sh`](../../install.sh), [`install.ps1`](../../install.ps1),
[`gcp/provision-vm.sh`](../../gcp/provision-vm.sh),
[`k8s/base/eddi-networkpolicy.yaml`](../../k8s/base/eddi-networkpolicy.yaml)
