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
