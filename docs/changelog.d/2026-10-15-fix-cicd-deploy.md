## 🔒 fix(deploy): close the CI/CD, Helm, compose and installer findings of the 2026-10-15 security review (2026-10-15)

**Repo:** EDDI (`fix/cicd-deploy-hardening`)

Findings M4, M5, L4 and L7 of the full security review run against `origin/main`
d49e855 on 2026-10-15. Config, workflows, manifests, installers and docs only; the
one Java change is to the two tests that read those files.

### What changed and why

**M4 — `auto-approve-copilot.yml` stood in for a human reviewer.**
`COLLABORATOR` is no longer a trusted author association (only `OWNER` and
`MEMBER`): a collaborator is anyone granted repository access, including an
outside account invited for one task. `PROTECTED_GLOBS` now also covers the
security-critical code — `engine/security/**`, `secrets/**`,
`engine/httpclient/**`, `connections/**`, `application.properties` and every
`*Guard*.java` — because a change there stays green on every check and can
still turn a control off. The workflow header states the assumption it runs
under: branch protection on `main` still requires one *human* approval, and the
bot only ever satisfies the second of two. That count is a repository setting
the workflow cannot read, so [`code-review-standards.md`](../code-review-standards.md)
now says so under *Merge Policy*. `AutoApproveCopilotPolicyTest` moved
`application.properties` from its "ordinary" list to the protected one and
added the new paths. The review also named `.github/workflows/**`, `helm/**`,
`k8s/**`, `docker-compose*.yml` and the installers; those were already covered
by `.github/**`, `helm/**`, `k8s/**`, `**/docker-compose*.yml`, `install.sh`
and `install.ps1`.

**M5 — Helm put the Keycloak bootstrap admin password in a plain pod env value.**
[`keycloak.yaml`](../../helm/eddi/templates/keycloak.yaml) renders it into a
`<release>-eddi-keycloak` Secret (key `password`, the kustomize overlay's shape)
and the StatefulSet reads it through `secretKeyRef` without `optional`;
`required` moved to the Secret, so the chart still ships no default. Every
credential the chart renders can now come from a Secret the operator manages:
`eddi.existingSecret`, `mongodb.existingSecret`, `postgres.existingSecret` and
`keycloak.existingSecret` each skip the chart's own Secret and point the
consumer at the named one (the keys each must carry are in
[`values.yaml`](../../helm/eddi/values.yaml) and
[`kubernetes.md`](../kubernetes.md#bringing-your-own-secrets-helm)).
`mongodb.existingSecret` requires `eddi.existingSecret` alongside, because the
chart cannot build EDDI's connection string without the root password — it
refuses to render otherwise rather than booting EDDI against a database it
cannot authenticate to. The chart also refuses `keycloak.enabled=true` together
with `ingress.enabled=true` unless `keycloak.devModeAcknowledged=true`, since
the in-chart Keycloak runs `start-dev`. NOTES.txt describes all of it, names
the Secret the password landed in, and carries the `start-dev` warning.
`DeploymentManifestsTest` asserts the new shape (secretKeyRef, no `value:`, no
`optional`, `required` on the Secret). Chart version 2.7.0 → 2.8.0.

**L4 — defaults that were safe only on a laptop.**
[`docker-compose.postgres-only.yml`](../../docker-compose.postgres-only.yml)
requires `POSTGRES_PASSWORD` (it hard-coded `eddi`/`eddi`);
[`docker-compose.cluster.yml`](../../docker-compose.cluster.yml) requires
`MONGO_PASSWORD` the way it already required `NATS_PASSWORD` (it defaulted to
`changeme`). Both installers generate `POSTGRES_PASSWORD` into `.env` for
`--db=postgres` and carry a stored value forward, like the Keycloak password,
because the postgres image reads it at initdb only. [`docker-compose.yml`](../../docker-compose.yml)
keeps its laptop defaults but gains a one-shot `bind-guard` service that `eddi`
depends on: it exits 1 when `EDDI_BIND` is not loopback while
`EDDI_SECURITY_ALLOW_UNAUTHENTICATED` is `true`, unless
`EDDI_I_UNDERSTAND_PUBLIC=true`. It runs on the `mongo` image the stack pulls
anyway, so it costs no extra image and works offline and with the local build
overlay; `install.sh` performs the same check before pulling images, and the GCP
provisioner passes the acknowledgement for `--i-understand-public`. Documented in
[`docker.md`](../docker.md#publishing-eddi-on-the-network). `k8s/quickstart.yaml`
carries the same loud banner the vault key has. **Its three
`*_ALLOW_UNAUTHENTICATED` flags stay `"true"`, deliberately:** the review asked
for the MCP and secret-store opt-outs to be `false` "like Helm does", but with
OIDC off `HighValueSurfaceGuard` refuses to boot unless both are opted in — Helm
renders `false` *and then refuses to render that release*. `false` in the
quickstart is not a safer manifest, it is a CrashLoopBackOff; the banner says
so and tells the reader that turning OIDC on is the only way to close them.

**L7 — CI and docs.** In [`ci.yml`](../../.github/workflows/ci.yml) the `:latest`
Docker alias and the GitHub release's `make_latest` follow `is-stable`, and a
pre-release tag is published with `prerelease: true`; a `6.4.0-RC1` tag used to
move both. [`base-image-check.yml`](../../.github/workflows/base-image-check.yml)
opens its PR with `BASE_IMAGE_BOT_TOKEN || CHANGELOG_BOT_TOKEN || GITHUB_TOKEN`
and carries the sibling workflows' PR-body note, because a PR opened with the
default token never runs `Build & Test` — the one check a base-image bump needs.
[`httpcalls.md`](../httpcalls.md#running-behind-a-reverse-proxy) adds
`quarkus.http.proxy.trusted-proxies` to the proxy guidance: without it every
peer may send forwarded headers, which is the origin check `${caller:token}`
relies on. With an Ingress and OIDC on, the chart defaults
`QUARKUS_OIDC_RESOURCE_METADATA_RESOURCE` to `<scheme>://<ingress.hosts[0].host>/mcp`
instead of trusting each request's `Host`. Setting `keycloak.publicOrigin`
strips the `http(s)://localhost:*` redirect URIs and the `+` wildcard web origin
from the realm's `eddi-frontend` client (the realm is parsed and re-serialised
rather than string-edited; the `eddi-mcp` client keeps its loopback redirects).
`install.ps1`'s `Protect-SensitiveFile` fails with the `icacls` fix instead of
warning when the `.env` ACL cannot be set. `SECURITY.md`'s cosign regex uses the
strict tag pattern CI and `release-signing.md` use.

### Verification

`helm lint` and `helm template` (dockerised `alpine/helm:3.17.3`, helm is not on
PATH here) for the default, the CI PostgreSQL+Keycloak shape, Keycloak+Ingress
with and without the acknowledgement, every `existingSecret` combination and the
`mongodb.existingSecret`-alone refusal; `docker compose config` for every edited
compose file plus the auth and local overlays, and the bind guard run under each
environment combination; `python -c "import yaml"` over every edited workflow
and manifest; `bash -n` on the shell installers and a parser check on
`install.ps1`; the deploy and docs unit tests via `./mvnw test`.

```decision-log
| 2026-10-15 | `k8s/quickstart.yaml` keeps all three `*_ALLOW_UNAUTHENTICATED` flags `true` behind a loud banner | Review L4 asked for the MCP/secret-store opt-outs to be `false` like Helm | Setting them `false` with OIDC off: `HighValueSurfaceGuard` refuses to boot, so the quickstart would crash-loop; Helm's `false` is paired with a render-time refusal, which a static manifest cannot express |
| 2026-10-15 | `docker-compose.yml` enforces "no non-loopback bind while unauthenticated" with a one-shot `bind-guard` service on the `mongo` image | Review L4; the contract was only a comment | A busybox/alpine init image (extra pull, breaks offline); an entrypoint wrapper on `eddi` (intrusive); installer-only check (does not cover `docker compose up` by hand) |
| 2026-10-15 | `mongodb.existingSecret` requires `eddi.existingSecret` | The chart builds `mongodb.connectionString` from `mongodb.rootPassword` | Reading the password back out of the operator's Secret (`lookup` is empty under `helm template` and dry runs, so the render would differ from the install) |
```
