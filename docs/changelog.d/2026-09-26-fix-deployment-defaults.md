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
  the same for `viewer` and `user`. On re-run, `install.sh` also turns the password
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
| Realm fixtures have no password; `eddi-frontend` refuses the password grant | New imports only (import is one-shot) | Set passwords in the console, or re-run the installer (`--demo-users`). Existing realms keep `viewer`/`viewer` and `user`/`user` until changed. `install.sh` closes the grant on re-run. A script that used the password grant on `eddi-frontend` must switch to code flow, or re-enable it deliberately. |
| `KEYCLOAK_ADMIN_PASSWORD` / `GRAFANA_ADMIN_PASSWORD` required by compose | Everyone running those overlays, including through `eddi update` | Re-run the installer (it generates the values and rotates a legacy `admin`/`admin`), or add them to `.env`. On an existing volume the service keeps its old password until changed. Compose refuses to start with a message naming the variable, and leaves running containers untouched. |
| Infrastructure ports bind `127.0.0.1` | Anyone reaching Keycloak/Grafana/Prometheus/NATS/Chroma/Ollama from another machine | SSH tunnel or reverse proxy. `KEYCLOAK_BIND_ADDRESS=0.0.0.0` for Keycloak, behind TLS. |
| Helm 3.0.0: `mongodb.auth.password` required | Every `helm upgrade` of an install with the in-chart MongoDB | Rendering fails first, so nothing changes by accident. Run `db.createUser` in the running pod first, then upgrade with that password (values.yaml, docs/kubernetes.md). Or set `mongodb.auth.enabled=false` explicitly. |
| Helm datastore NetworkPolicies on by default | Other workloads that connect to the in-chart databases (backup jobs) | Add a policy admitting them, or `networkPolicy.datastores.enabled=false`. |
| Kustomize `overlays/mongodb` authenticates from `mongodb-secrets` | Existing installs re-applying the overlay | Create the user in the running pod first, then the Secret, then apply. The overlay header has the commands; the localhost exception lets you recover if applied first. |
| `postgres-secret.yaml` no longer a resource | New installs | Create `postgres-secrets` from the `.example`. Existing installs keep theirs, since `apply -k` does not prune. |
| Grafana `grafana-admin` Secret required (k8s) | Monitoring component users | `kubectl create secret generic grafana-admin …` (header + docs). |
| Prometheus Role instead of ClusterRole | Monitoring component users | Delete the leftover `ClusterRole`/`ClusterRoleBinding` `eddi-prometheus`. |
| No service-account token in pods | Anything added to these pods that calls the API | Helm `serviceAccount.automountToken=true`, or a manifest edit. |
| `/chat` CSP split; `X-Frame-Options` no longer global | Nobody by default (`/chat` still `'none'`) | Set `eddi.chat.frame-ancestors` to embed. |
| GCP script opens no 8180/3000/9090 firewall rules | Existing VMs keep their old rules | Delete `allow-eddi-keycloak` / `allow-eddi-grafana`. |

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

**Files:** realms, [`docker-compose.auth.yml`](../../docker-compose.auth.yml), [`docker-compose.monitoring.yml`](../../docker-compose.monitoring.yml), [`install.sh`](../../install.sh), [`install.ps1`](../../install.ps1), [`helm/eddi/`](../../helm/eddi/Chart.yaml), `k8s/overlays/{mongodb,postgres,monitoring}`, [`application.properties`](../../src/main/resources/application.properties), [`AuthStartupGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/AuthStartupGuard.java), [`Dockerfile.demo`](../../src/main/docker/Dockerfile.demo); tests in `DeploymentManifestsTest`, `ComposeStackTest`, `CspPolicyTest`, `MetricsHttpPolicyConfigTest`, `AuthStartupGuardTest`, `DemoImageDockerfileTest`.

```decision-log
| 2026-09-26 | The in-chart and Kustomize MongoDB authenticate by default, and Helm refuses to render without a password | C5c: any pod could read and rewrite all EDDI data | NetworkPolicy only (inert without an enforcing CNI); auth opt-in (leaves the default open) |
| 2026-09-26 | The /chat framing allow-list is an operator property (default 'none'), in its own CSP filter | M-F3: the global DENY blocked the documented embed | Relax frame-ancestors for every path; XFO ALLOW-FROM (unsupported) |
| 2026-09-26 | The wizard's spec-by-URL needs eddi.csp.extra-connect-sources; connect-src stays strict by default | M-F4: the browser blocked the fetch | Allow https: in connect-src by default (opens exfiltration for any injected script) |
```
