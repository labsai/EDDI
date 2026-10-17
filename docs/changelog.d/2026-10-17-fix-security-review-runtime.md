## 🔒 fix(deploy): runtime and deployment findings of the 2026-10-17 security review (2026-10-17)

**Repo:** EDDI (`fix/security-review-runtime`)

### What changed and why

The runtime and deployment workstream of the 2026-10-17 security review — M6, M7,
L7, L8 and the small L12/L13 items. One commit per finding.

- **M6 — the authenticated PostgreSQL install could not start, and two stacks had no
  bind-guard.** `docker-compose.auth.yml` overrides `bind-guard`, which only
  `docker-compose.yml` declared, so `-f docker-compose.postgres-only.yml -f
  docker-compose.auth.yml` — exactly what `install.sh --db=postgres --with-auth` and
  `install.ps1` compose — failed with "service bind-guard has neither an image nor a
  build context". Separately `docker-compose.postgres-only.yml` and
  `docker-compose.cluster.yml` publish on `${EDDI_BIND:-127.0.0.1}` with the three
  `*_ALLOW_UNAUTHENTICATED` defaults `true` and no guard, so `EDDI_BIND=0.0.0.0`
  published an open EDDI, MCP server and vault there. Both stacks now carry the same
  guard (on the database image each already pulls; in the cluster the load balancer
  waits for it). Design: the guard stays in every base rather than moving to an
  overlay — a base used on its own must still refuse — and `ComposeStackTest` makes
  the duplication safe: the copies must be byte-identical in script and inputs, every
  stack that publishes on `EDDI_BIND` must have one, every combination the installers
  can assemble (base × `--local` × `--with-auth` × `--with-monitoring`, read back out
  of both installers) and every multi-file command README/`docs/` print is checked
  structurally (every service and `depends_on` target resolves) and, where a Docker CLI
  exists, rendered with `docker compose config`. That sweep also found
  `docs/mcp-server.md` telling users to run `docker compose -f docker-compose.auth.yml
  down -v` on its own, which compose refuses; fixed.

**Files:** [`docker-compose.postgres-only.yml`](../../docker-compose.postgres-only.yml),
[`docker-compose.cluster.yml`](../../docker-compose.cluster.yml),
[`docker-compose.auth.yml`](../../docker-compose.auth.yml),
[`ComposeStackTest.java`](../../src/test/java/ai/labs/eddi/ComposeStackTest.java),
[`docker.md`](../docker.md), [`mcp-server.md`](../mcp-server.md)

- **M7 — the unauthenticated quick start had no `Host`/`Origin` check.** Loopback binding
  stops the network, not the browser: a DNS-rebinding page (`Host: evil.example` resolved to
  `127.0.0.1`) was same-origin with the whole API, MCP and `/secretstore`, and without
  rebinding a page could still send blind CORS "simple" `POST`s. The new
  `UnauthenticatedRequestGuard` (a Vert.x route filter registered like `HttpMethodGuard`,
  and only while `authorization.enabled=false`) answers **421** to a `Host` that is not
  `localhost`/`*.localhost`, an IP literal or an entry of the new
  `eddi.security.unauthenticated.allowed-hosts`, and **403** to a
  `POST`/`PUT`/`PATCH`/`DELETE` whose `Origin` is neither the request's own nor in
  `quarkus.http.cors.origins` (`null` included) or, without an `Origin`, whose
  `Sec-Fetch-Site` is `cross-site`/`same-site`. `/q/health` is exempt; one `[SECURITY]` WARN
  at boot states the policy. Design decision, deviating from the review's "loopback only":
  **IP literals are always answered**. Rebinding needs a DNS name the attacker controls, so an
  address cannot be its product, and kubelet probes, Prometheus scrapes (pod IPs) and
  `eddi.self.base-url` (`http://127.0.0.1:…` or a pod IP) address EDDI that way; the
  cross-site check still covers a page that posts to an address. The raw `Host` header is
  read, never a forwarded one — a rebound page is same-origin and may set
  `X-Forwarded-Host`. The compose stacks default the allow-list to `eddi` (Prometheus and
  Open WebUI reach EDDI by service name), the kustomize base and quickstart to the Service's
  in-cluster names, and the Helm chart (when OIDC is off) to the Service names plus any
  acknowledged ingress host. Chart `2.8.0` → `2.9.0`.

**Files:** [`UnauthenticatedRequestGuard.java`](../../src/main/java/ai/labs/eddi/engine/security/UnauthenticatedRequestGuard.java),
[`UnauthenticatedRequestGuardTest.java`](../../src/test/java/ai/labs/eddi/engine/security/UnauthenticatedRequestGuardTest.java),
[`security.md`](../security.md#running-without-authentication),
[`configuration-reference.md`](../configuration-reference.md)

- **L7 — runtime defaults.** Done:
  - **HSTS.** The GCP provisioner's nginx sends `Strict-Transport-Security: max-age=31536000`
    on both https servers (no `includeSubDomains` — sslip.io names are not ours), and EDDI
    gains an opt-in `HstsHeaderFilter` (`eddi.security.hsts.enabled`, default `false`;
    `eddi.security.hsts.max-age`). The Helm chart sets it when `ingress.enabled` and
    `ingress.tls` are both set.
  - **`/q/health` detail.** The cluster readiness check reports its details (mode, NATS
    state, node id, member count, drain state, `degradedSince`) only with the new
    `eddi.health.verbose=true`; by default the public endpoint carries the status alone.
    `GET /administration/cluster/overview` still has everything, authenticated. (Gated in
    `ClusterHealthCheck` itself, so the readiness data is gone, not merely moved behind a
    path.) `agentsInErrorCount` stays — it is a count, documented as deliberately public.
  - **Keycloak realm** (all three copies, kept in step): `passwordPolicy` (`length(12) and
    maxLength(128) and notUsername and notEmail and passwordHistory(3)` — the installers'
    generated one-time passwords are 32 characters), login and admin events on (30 days,
    admin event bodies off), `eddi-mcp` with `consentRequired: true` and
    `fullScopeAllowed: false` plus a `scopeMappings` entry for exactly the `eddi-*` roles,
    seeded users `emailVerified: false`. Verified by importing the realm into
    `quay.io/keycloak/keycloak:26.8.0` and generating an example `eddi-mcp` access token
    (roles and `aud` still present). Realm import is one-shot: existing realms keep their
    old settings (documented in security.md).
  - **GCP nginx** returns 404 for `/realms/master/` (the provisioner reaches it on
    localhost:8180, not through nginx).
  - **Kubernetes.** The production NetworkPolicy's ingress-controller rule is pinned to the
    `ingress-nginx` namespace by `kubernetes.io/metadata.name` instead of admitting a
    controller-labelled pod from any namespace. MongoDB, PostgreSQL and Keycloak pods (Helm,
    kustomize overlays, quickstart) run as their image's own uid from the start
    (Mongo 999, Postgres alpine 70 — Helm `postgres.runAsUser` — Keycloak 1000) with
    `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]` and the RuntimeDefault
    seccomp profile; both databases were started that way in Docker
    (`--user … --cap-drop ALL --security-opt no-new-privileges`) to confirm their
    entrypoints still initialise.
  - `quarkus.swagger-ui.oauth2-redirect-url` is no longer pinned to
    `http://localhost:7070`; Swagger UI derives it from the page's origin.
  - `docker-compose.yml`'s MongoDB healthcheck no longer carries the root password on its
    command line (`docker inspect`, host process list): `ping` needs no authentication.

  Deliberately **not** done here: Keycloak `start-dev` and the `admin/admin` compose fallback
  (the installers and the GCP provisioner already generate one; a production Keycloak mode
  needs a database and hostname design of its own), EDDI connecting to MongoDB as root,
  plaintext service-to-service traffic, NATS without auth and the unauthenticated MongoDB in
  the kustomize quickstart, and `quarkus.log.console.level=DEBUG` (the handler level only;
  categories stay at INFO).

**Files:** [`HstsHeaderFilter.java`](../../src/main/java/ai/labs/eddi/engine/security/HstsHeaderFilter.java),
[`ClusterHealthCheck.java`](../../src/main/java/ai/labs/eddi/engine/cluster/ClusterHealthCheck.java),
[`eddi-realm.json`](../../keycloak/eddi-realm.json),
[`provision-vm.sh`](../../gcp/provision-vm.sh),
[`network-policy.yaml`](../../k8s/overlays/production/network-policy.yaml)
