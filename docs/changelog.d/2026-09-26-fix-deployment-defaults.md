## 🔒 fix(deploy): no shipped deployment logs straight in or reaches a database from any pod (2026-09-26)

**Repo:** EDDI (`fix/deployment-defaults`) — fix-plan item 16

### What changed and why

Every delivery path — compose, the installers, the Kustomize overlays, the Helm
chart — shipped at least one credential that worked on a fresh install, and the
in-cluster datastores trusted every pod in the cluster.

**Identity (C5a, C5b, NEW dead `eddi`/`eddi`).**
- The realm (all three copies) seeded `viewer`/`viewer` and `user`/`user`, which
  logged straight in (`temporary: true` is ignored on import), and the public
  `eddi-frontend` client allowed the password grant — one `curl` returned a token
  that could run LLM turns. No account ships a credential now, and the grant is
  off on `eddi-frontend`. The Manager uses the code flow and never needed it.
  The auth E2E tier (`make-test-realm.mjs`) supplies every fixture password and
  turns the grant back on in its generated realm only, and fails if the shipped
  realm regresses.
- `docker-compose.auth.yml` hard-coded the Keycloak bootstrap admin as
  `admin`/`admin` on `0.0.0.0`. It now needs `KEYCLOAK_ADMIN_PASSWORD`
  (`${…:?}` — compose refuses to start and says what to set) and publishes on
  `127.0.0.1` (`KEYCLOAK_BIND_ADDRESS` to change it).
- `install.sh` / `install.ps1` generate the admin password and keep it in `.env`.
  They rotate a legacy `admin`/`admin` login to it on re-run, and give `eddi` a
  one-time password (Keycloak forces a change at first login) instead of printing
  the `eddi / eddi` the realm stopped seeding. `--demo-users` / `-DemoUsers` does
  the same for `viewer` and `user`. On every path — fresh, re-run on a stopped
  stack, or re-run while EDDI is running — both installers also turn the password
  grant off on existing realms. `gcp/provision-vm.sh` reads the generated password,
  stops opening 8180/3000/9090 to the internet, and prints SSH tunnels instead.
  Both installers' Admin-API code was run against a real Keycloak 26.0.8: a
  fresh realm import, the legacy rotation, the first-login password (verified
  `requiredActions: ["UPDATE_PASSWORD"]`), idempotence on re-run, and
  `unauthorized_client` for the password grant. The PowerShell run found that
  PS 7's `Invoke-RestMethod` emits a JSON array as one object, so an empty
  credentials list counted 1; that is fixed.

**Datastores (C5c, C5d).**
- Helm (chart **3.0.0**): the in-chart MongoDB authenticates. `mongodb.auth.password`
  is required. The credentialed connection string reaches EDDI as a mounted file
  from the `<release>-mongodb` Secret. The ConfigMap's credential-less entry now
  renders only for the explicit `mongodb.auth.enabled=false`: a file listed in
  `QUARKUS_CONFIG_LOCATIONS` inherits that env var's ordinal (300, confirmed in
  SmallRye's `AbstractLocationConfigSourceFactory`), so the two would tie.
  `networkPolicy.datastores.enabled` (default on) admits only the EDDI pod to
  MongoDB, PostgreSQL and NATS.
- Kustomize: `overlays/mongodb` authenticates from an operator-created
  `mongodb-secrets`, and removes the base's credential-less connection string.
  `overlays/mongodb` and `overlays/postgres` each ship an EDDI-only NetworkPolicy.
  `postgres-secret.yaml` (committed `eddi`/`eddi`, applied by `postgres-ha`) became
  `postgres-secret.yaml.example`.
- `quickstart.yaml` keeps its unauthenticated evaluation MongoDB, so the
  one-file contract holds, but gains the same policy and a clear banner.
- Compose dev overlays (NATS, Chroma, Ollama, Prometheus, Grafana, Jaeger/OTLP)
  publish on `127.0.0.1`. Grafana's admin password needs `GRAFANA_ADMIN_PASSWORD`
  (compose) or a `grafana-admin` Secret (k8s).

**Cluster hygiene (NEW, L-F2).** Prometheus gets a namespaced Role instead of a
ClusterRole: it only ever discovers its own namespace. No pod except Prometheus
mounts a service-account token (Helm: `serviceAccount.automountToken`, default false).

**Browser policy (M-F3, M-F4).**
- `/chat` has its own CSP filter, with `frame-ancestors` taken from
  `eddi.chat.frame-ancestors` (default `'none'`). `X-Frame-Options: DENY` moved
  from a global header onto the default and Swagger filters, so the documented
  iframe embed can be enabled without opening the Manager or the API to framing.
- `img-src` allows `blob:`, so staged attachment previews render.
- `connect-src` appends `eddi.csp.extra-connect-sources` (empty by default)
  for the wizard's fetch-spec-by-URL.

**Metrics (M-F5).** `/q/metrics` has an explicit rule driven by
`eddi.metrics.http-policy` (default `authenticated`; Helm `eddi.metrics.httpPolicy`).
The monitoring guide's new "Scraping with authentication on" section documents
the oauth2 client-credentials scrape. Both scrape configs carry it commented,
and the installers warn when auth and monitoring are combined.

**Docs and messages (A4, A6, A7, B1).**
- A4: why the relative RFC 9728 resource should be absolute in production;
  Helm gets `eddi.oidc.resourceMetadata.resource`.
- A6: the `offline_access` advice was checked against Keycloak — the scope and
  role exist after import, but clients request only `scopes_supported`. The doc
  now gives the two ways that work.
- A7: `AuthStartupGuard` named the workspaces claim as quarkus-oidc's default
  claim. It now names `groups`, and reports a collision only when there is one.
- B1: `Dockerfile.demo` copies `ui/`, so `mvn package` can build the UIs the
  demo relies on.

### What this breaks — upgrade paths

| Change | Who is affected | Upgrade |
|---|---|---|
| Realm fixtures have no password; `eddi-frontend` refuses the password grant | New imports only (import is one-shot) | Set passwords in the console, or re-run the installer (`--demo-users`). Existing realms keep `viewer`/`viewer` and `user`/`user` until changed; without `--demo-users` the installers leave them alone but **warn** when either still has a credential. Both installers close the grant on every re-run path. A script that used the password grant on `eddi-frontend` must switch to code flow, or re-enable it deliberately. |
| `KEYCLOAK_ADMIN_PASSWORD` / `GRAFANA_ADMIN_PASSWORD` required by compose | Everyone running those overlays, including through `eddi update` | Re-run the installer. It rotates a service that still accepts `admin`/`admin` to a generated password and only then records it in `.env`, on the running and the stopped path. If the service no longer accepts `admin`/`admin`, the password is the operator's: a running-stack re-run stops with the exact `.env` line to add, a stopped-stack re-run warns that the generated value must be replaced. The Linux/macOS `eddi update` refuses to restart a legacy install and says to re-run the installer; on Windows compose stops with a message naming the variable. Nothing is restarted in either case. |
| Infrastructure ports bind `127.0.0.1` | Anyone reaching Keycloak/Grafana/Prometheus/NATS/Chroma/Ollama from another machine | SSH tunnel or reverse proxy. `KEYCLOAK_BIND_ADDRESS=0.0.0.0` for Keycloak, behind TLS. |
| Helm 3.0.0: `mongodb.auth.password` required | Every `helm upgrade` of an install with the in-chart MongoDB — **including `--reuse-values`**, which carries no `mongodb.auth` from 2.x: a missing switch counts as enabled | Rendering fails first, so nothing changes by accident. Generate the password once into a file, run `db.createUser` in the running `<fullname>-mongodb` pod, then upgrade with `--set-file` (the exact sequence is under `mongodb.auth` in values.yaml). Or set `mongodb.auth.enabled=false` explicitly. |
| Helm datastore NetworkPolicies on by default (also when the key is missing, as on `--reuse-values`) | Other workloads that connect to the in-chart databases (backup jobs) | Add a policy admitting them, or `networkPolicy.datastores.enabled=false`. |
| Kustomize `overlays/mongodb` authenticates from `mongodb-secrets` | Existing installs re-applying the overlay | Create the user in the running pod first, then the Secret, then apply. The overlay header has the commands; the localhost exception lets you recover if applied first. |
| `postgres-secret.yaml` no longer a resource | New installs | Create `postgres-secrets` from the `.example`. Existing installs keep theirs, since `apply -k` does not prune. |
| Grafana `grafana-admin` Secret required (k8s) | Monitoring component users | `kubectl create secret generic grafana-admin …` (header + docs). |
| Prometheus Role instead of ClusterRole | Monitoring component users | Delete the leftover `ClusterRole`/`ClusterRoleBinding` `eddi-prometheus`. |
| No service-account token in pods | Anything added to these pods that calls the API | Helm `serviceAccount.automountToken=true`, or a manifest edit. |
| `/chat` CSP split; `X-Frame-Options` no longer global | Nobody by default (`/chat` still `'none'`) | Set `eddi.chat.frame-ancestors` to embed. |
| GCP script opens no 8180/3000/9090 firewall rules | Existing VMs keep their old rules | Delete `allow-eddi-keycloak` / `allow-eddi-grafana`. |

### Review follow-up (same day)

- **Upgrade command:** the MongoDB command in values.yaml had lost its line continuation. As copied it ran a bare `mongosh admin`, exited 0 and created no user. It also named `<release>-eddi-mongodb`, which no release creates. It is now a multi-line sequence against `<fullname>-mongodb`. The password is generated once, with `printf`, because `--set-file` keeps a trailing newline as part of the value. The sequence was extracted, checked with `bash -n`, and run against stub `kubectl`/`helm`. `helmMongoUpgradeCommandIsWellFormed` pins it.
- **`--reuse-values`:** the new switches were read as `(… | default dict).enabled`, so a missing key — exactly what `helm upgrade --reuse-values` from 2.x produces — meant off. The upgrade exited 0 with MongoDB still open and no datastore policy. Both switches now go through `eddi.enabledUnlessFalse`, which turns a switch off only on an explicit false. Verified by rendering the 3.0.0 templates with the 2.1.0 values.yaml: refused. CI gained two `expect_failure` cases and a positive `networkPolicy.datastores=null` render. `helmSecuritySwitchesDefaultOnWhenAbsent` pins the wiring.
- **Installers:**
  - The admin passwords are now carried forward in `.env` whatever the flags, and `.env` is written under `umask 077`.
  - A legacy `.env` on the already-running path is handled explicitly (rotate then record, or stop with guidance). Both installers close the password grant on every path and warn about legacy `viewer`/`user` credentials.
  - Without a TTY, the one-time password goes to a 0600 `first-login.txt` instead of the log. The GCP script points there.
  - Checked against Keycloak 26.0.8 (bash on the jq and python3 paths, and pwsh 7.4.6), with the realm made legacy on purpose.
- **Docs:** Helm installs generate `eddi-secrets.yaml` once, behind an `[ -e ]` guard, and reuse it for every upgrade. The Kustomize `mongodb-secrets` snippets skip creation when the Secret exists and warn against re-running.
- **HTTP-level CSP check:** `InfrastructureIT.chatAndManagerFramingHeaders` asserts that `/chat` gets one CSP header and no `X-Frame-Options`, and that `/manage` and `/q/health/ready` keep `DENY`. It runs in CI's Integration Tests job; it was not run locally.
- **Nits:**
  - The "ordinal 400" text is corrected to 300 (a tie) in the chart, the CI comment and the test.
  - The datastore NetworkPolicy comment now says that NATS probes are HTTP on 8222. They are deliberately not admitted: the NetworkPolicy spec lets node-originated kubelet probes through, so the probes pass while other pods still cannot read the endpoint.
  - The monitoring stack names `EDDI_METRICS_HTTP_POLICY`.
  - `ui/manager/docker-compose.keycloak.yml` requires `KEYCLOAK_ADMIN_PASSWORD`. The CI-only `docker-compose.integration-keycloak.yml` keeps `admin`/`admin` as a documented test-tier exception.

### Deferred / partial

- M-F3's second half: the Chat UI never strips `?token=` from the URL. That is a
  `ui/chat` change, left to the UI branches.
- M-F4: the wizard's fetch-spec-by-URL still needs `eddi.csp.extra-connect-sources`
  set; the default stays strict, and File/Paste work. A friendlier UI message is a
  `ui/manager` follow-up.
- M-F5: the installers do not create a Keycloak client for Prometheus. They
  document the scrape and warn.
- C5c: `quickstart.yaml`'s MongoDB is isolated but still unauthenticated, to keep
  the one-command evaluation contract.
- The Windows `eddi.cmd update` has no pre-flight check for the new admin
  passwords; compose's own error names the missing variable.

**Files:** realms, [`docker-compose.auth.yml`](../../docker-compose.auth.yml), [`docker-compose.monitoring.yml`](../../docker-compose.monitoring.yml), [`install.sh`](../../install.sh), [`install.ps1`](../../install.ps1), [`helm/eddi/`](../../helm/eddi/Chart.yaml), `k8s/overlays/{mongodb,postgres,monitoring}`, [`application.properties`](../../src/main/resources/application.properties), [`AuthStartupGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/AuthStartupGuard.java), [`Dockerfile.demo`](../../src/main/docker/Dockerfile.demo); tests in `DeploymentManifestsTest`, `ComposeStackTest`, `CspPolicyTest`, `MetricsHttpPolicyConfigTest`, `AuthStartupGuardTest`, `DemoImageDockerfileTest`.

```decision-log
| 2026-09-26 | The in-chart and Kustomize MongoDB authenticate by default, and Helm refuses to render without a password | C5c: any pod could read and rewrite all EDDI data | NetworkPolicy only (inert without an enforcing CNI); auth opt-in (leaves the default open) |
| 2026-09-26 | The /chat framing allow-list is an operator property (default 'none'), in its own CSP filter | M-F3: the global DENY blocked the documented embed | Relax frame-ancestors for every path; XFO ALLOW-FROM (unsupported) |
| 2026-09-26 | The wizard's spec-by-URL needs eddi.csp.extra-connect-sources; connect-src stays strict by default | M-F4: the browser blocked the fetch | Allow https: in connect-src by default (opens exfiltration for any injected script) |
```
