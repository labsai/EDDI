# Docker

## Quick Start

### Without Authentication (default)

```bash
docker compose up
```

This starts EDDI on port `7070` and MongoDB, both published on `127.0.0.1` only. No login required: `docker-compose.yml` sets the three authentication opt-outs described below, which is why it does not publish EDDI on other interfaces unless you set `EDDI_BIND` — and refuses to do even that while authentication is off, see [Publishing EDDI on the network](#publishing-eddi-on-the-network).

Loopback keeps the network out, not a web page in your own browser. So while
authentication is off EDDI answers only requests addressed to `localhost`, an IP
address or a host in `EDDI_SECURITY_UNAUTHENTICATED_ALLOWED_HOSTS` (default
`eddi`, the service name other containers use) — anything else gets `421` — and
refuses cross-site `POST`/`PUT`/`PATCH`/`DELETE` from origins not in
`QUARKUS_HTTP_CORS_ORIGINS` with `403`. Browsing to `http://localhost:7070` works
as before; if you reach EDDI under another name, add it to the list. See
[Running without authentication](security.md#running-without-authentication).

The stack also publishes `7443` (`EDDI_HTTPS_PORT`), Quarkus' HTTPS port. Nothing
listens there until you configure a TLS certificate
([TLS Directly in Quarkus](security.md#option-2-tls-directly-in-quarkus)); until then a
connection to it is refused.

### Publishing EDDI on the network

`EDDI_BIND` is the host interface EDDI is published on (`127.0.0.1` by default,
so a laptop install is never reachable from the LAN by accident). Setting it to
`0.0.0.0` or a LAN address is allowed only once authentication is on: every
whole stack that publishes on `EDDI_BIND` — `docker-compose.yml`,
`docker-compose.postgres-only.yml` and `docker-compose.cluster.yml` — carries
the same one-shot `bind-guard` service that the published service depends on,
and it exits 1 — so EDDI never starts — when `EDDI_BIND` is not a loopback
address while `EDDI_SECURITY_ALLOW_UNAUTHENTICATED` is still `true`. The
message it prints names the way out:

```bash
# in .env — then bring the Keycloak overlay up
EDDI_BIND=0.0.0.0
EDDI_SECURITY_ALLOW_UNAUTHENTICATED=false
QUARKUS_OIDC_TENANT_ENABLED=true
docker compose -f docker-compose.yml -f docker-compose.auth.yml up
```

The guard runs on a database image the stack pulls anyway (`mongo`, or
`postgres` in the PostgreSQL stack), so it costs no extra image, works offline
and works with `docker-compose.local.yml`. It checks the two variables as
compose resolves them, so a value set in `.env`, in the shell or by an overlay
all count. `install.sh` performs the same check before it pulls images.
`docker-compose.auth.yml` tells the guard that authentication is on, so it
layers onto either base stack.

`ComposeStackTest` keeps the copies identical and renders every combination
the installers assemble and the documentation prints with
`docker compose config` (where Docker is installed; otherwise it checks that
every service an overlay overrides exists in each base it is layered on):

| Base stack | Overlays that layer on it |
| --- | --- |
| `docker-compose.yml` | `docker-compose.auth.yml`, `docker-compose.monitoring.yml` (alone or together), `docker-compose.local.yml`, `docker-compose.nats.yml`, `docker-compose.ollama.yml` (+ `docker-compose.ollama-nvidia.yml`), `docker-compose.chroma.yml`, `docker-compose.mcp-sidecar.yml` |
| `docker-compose.postgres-only.yml` | `docker-compose.auth.yml`, `docker-compose.monitoring.yml` (alone or together), `docker-compose.local.yml` |
| `docker-compose.cluster.yml` | none — a whole stack on its own |
| `docker-compose.openwebui.yml` | none — a whole stack on its own |

To expose an **unauthenticated** instance on purpose — an isolated network
only you can reach — set `EDDI_I_UNDERSTAND_PUBLIC=true` as well; the GCP
provisioner's `--i-understand-public` does exactly that. Every API endpoint,
the secrets vault and the MCP server are then open to whoever can reach the
port, and EDDI's own `AuthStartupGuard` says so in its log every hour.

### With Keycloak Authentication

This repository ships a Keycloak overlay. Layer it on the base stack:

```bash
docker compose -f docker-compose.yml -f docker-compose.auth.yml up
```

This starts:

- **Keycloak 26** on `127.0.0.1:8180` (admin console: `http://localhost:8180/admin`, login `admin` / `KC_BOOTSTRAP_ADMIN_PASSWORD` — `admin` unless you set it; the installers generate one into `.env`)
- **`keycloak-guard`**, a one-shot check Keycloak waits for: it exits 1, so Keycloak never starts, when `KEYCLOAK_BIND` is not a loopback address while `KC_BOOTSTRAP_ADMIN_PASSWORD` is unset or `admin`. The loopback quick start needs no `.env`; to publish Keycloak on another interface, set `KC_BOOTSTRAP_ADMIN_PASSWORD` first (the installers and the GCP provisioner already do). Keycloak reads that password only when it first creates the master realm, so on an existing `keycloak-data` volume put the console's current admin password there
- **EDDI** on port `7070` with OIDC auth enabled
- **MongoDB** for data storage

The realm seeds three accounts, **none of them with a password**:

| Username | Role |
|----------|------|
| `eddi` | `eddi-admin`, `eddi-editor`, `eddi-viewer` |
| `viewer` | `eddi-viewer` (read-only) |
| `user` | `eddi-user` |

Set a password for the one you need in the admin console (Users → *name* →
Credentials → Set password). `install.sh --with-auth` does it for `eddi` and
prints a one-time password; add `--demo-users` for the other two. They used to
ship as `eddi`/`eddi`, `viewer`/`viewer` and `user`/`user`.

### Manual Docker Setup

Create a network and start MongoDB on it (the same `mongo:7.0.43` image `docker-compose.yml` pins). EDDI's default connection string points at a host named `mongodb`, so the container name matters:

```bash
docker network create eddi
docker run --name mongodb --network eddi -d mongo:7.0.43
```

Start EDDI (without auth) — **local development only:**

> ⚠️ These three opt-outs disable authentication on *every* endpoint, including
> `/secretstore` (the vault) and `/mcp` (agent CRUD). The port is bound to
> `127.0.0.1` below so the container is not reachable from the network. Do not
> publish it on `0.0.0.0`, and do not use this form on a shared or cloud host.
> For anything beyond your own machine, use the authenticated example below.

```bash
docker run --name eddi \
  --network eddi \
  -p 127.0.0.1:7070:7070 \
  -e EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true \
  -e EDDI_MCP_ALLOW_UNAUTHENTICATED=true \
  -e EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED=true \
  -d labsai/eddi:latest
```

> **Note:** The image runs in production mode, where `AuthStartupGuard` and `HighValueSurfaceGuard` refuse to boot while OIDC is off. Without these three opt-outs the container exits at startup. This is exactly what `docker-compose.yml` sets for you.

Start EDDI (with auth):

```bash
docker run --name eddi \
  --network eddi \
  -p 7070:7070 \
  -e EDDI_VAULT_MASTER_KEY="$EDDI_VAULT_MASTER_KEY" \
  -e QUARKUS_OIDC_TENANT_ENABLED=true \
  -e QUARKUS_OIDC_AUTH_SERVER_URL=http://your-keycloak:8080/realms/eddi \
  -e QUARKUS_OIDC_CLIENT_ID=eddi-backend \
  -d labsai/eddi:latest
```

## Environment Variables

### Authentication

| Variable                       | Default                             | Description                  |
| ------------------------------ | ----------------------------------- | ---------------------------- |
| `QUARKUS_OIDC_TENANT_ENABLED`  | `false`                             | Enable/disable Keycloak auth |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | `http://localhost:8180/realms/eddi` | Keycloak realm URL           |
| `QUARKUS_OIDC_CLIENT_ID`       | `eddi-backend`                      | OIDC client ID               |
| `QUARKUS_HTTP_CORS_ORIGINS`    | `http://localhost:3000,...`         | Allowed CORS origins         |
| `EDDI_SECURITY_ALLOW_UNAUTHENTICATED` | `false`                      | Opt out of the production auth requirement. Required to boot with OIDC disabled |
| `EDDI_MCP_ALLOW_UNAUTHENTICATED` | `false`                           | Knowingly expose `/mcp` without authentication. Required to boot with OIDC disabled |
| `EDDI_SECRETSTORE_ALLOW_UNAUTHENTICATED` | `false`                   | Knowingly expose `/secretstore` without authentication. Required to boot with OIDC disabled |
| `EDDI_SECURITY_UNAUTHENTICATED_ALLOWED_HOSTS` | *(unset; `eddi` in the compose stacks)* | With authentication off, the host names besides `localhost`, IP addresses and `host.docker.internal` / `gateway.docker.internal` / `host.containers.internal` EDDI answers to (`host` or `host:port`, comma-separated). Other hosts get `421` — the DNS-rebinding defence. See [Running without authentication](security.md#running-without-authentication) |

> **Note:** `QUARKUS_OIDC_TENANT_ENABLED` is a **runtime** toggle. No rebuild needed to enable/disable auth.

### Storage and Secrets

| Variable                   | Default                                       | Description |
| -------------------------- | --------------------------------------------- | ----------- |
| `MONGODB_CONNECTIONSTRING` | `mongodb://mongodb:27017/eddi?...`            | MongoDB connection string |
| `EDDI_DATASTORE_TYPE`      | `mongodb`                                     | `mongodb` or `postgres` |
| `EDDI_VAULT_MASTER_KEY`    | *(empty)*                                     | Enables the [secrets vault](secrets-vault.md). Without it EDDI starts, but the vault is disabled, and the audit ledger is unsigned unless the independent `EDDI_AUDIT_HMAC_KEY` (`eddi.audit.hmac-key`) is set. Production refuses a weak key (under 16 characters or a known default) |
| `POSTGRES_PASSWORD`        | *(required)*                                  | `docker-compose.postgres-only.yml` only: the database password for both PostgreSQL and EDDI. No default — the file used to ship `eddi`/`eddi`. The postgres image reads it at first init only, so keep it stable; `install.sh --db=postgres` generates one into `.env` |
| `MONGO_INITDB_ROOT_USERNAME` / `MONGO_INITDB_ROOT_PASSWORD` | `eddi` / `changeme` | MongoDB's root user, created on first init. Only `mongod` and the one-shot `mongodb-app-user` service see it; EDDI does not connect with it |
| `MONGO_APP_USERNAME`       | `eddi_app`                                    | EDDI's own MongoDB user: `readWrite` + `dbAdmin` on `eddi`, nothing else. `mongodb-app-user` creates it, or resets its password, on every `up` — including on a volume from before it existed, so no manual migration is needed |
| `MONGO_APP_PASSWORD`       | the root password                             | Password of that user. Set a distinct one (`openssl rand -hex 24`): left unset, EDDI's credential is still the root password under another name. URL-safe characters only — it is placed into the connection string unencoded. See [Upgrading from 6.5](upgrading-from-6.5.md#mongodb-least-privilege-user) |
| `MONGO_PASSWORD`           | *(required)*                                  | `docker-compose.cluster.yml` only: the MongoDB root password, required like `NATS_PASSWORD` (it used to default to `changeme`). Read by the mongo image at first init only. The replicas connect as `MONGO_APP_USERNAME` / `MONGO_APP_PASSWORD` (falling back to this password), never as root |
| `EDDI_BIND`                | `127.0.0.1`                                   | Host interface EDDI is published on. Anything but loopback is refused while `EDDI_SECURITY_ALLOW_UNAUTHENTICATED` is `true` — see [Publishing EDDI on the network](#publishing-eddi-on-the-network) |
| `EDDI_I_UNDERSTAND_PUBLIC` | `false`                                       | Acknowledges a deliberately unauthenticated, non-loopback `EDDI_BIND`; lifts the refusal above |

Set configuration through environment variables like these rather than by overriding `JAVA_OPTS_APPEND`: the image sets `JAVA_OPTS_APPEND` itself, and a runtime value replaces it instead of adding to it.

### AI Tools

```bash
-e EDDI_TOOLS_WEBSEARCH_PROVIDER=google \
-e EDDI_TOOLS_WEBSEARCH_GOOGLE_API_KEY=your_key \
-e EDDI_TOOLS_WEBSEARCH_GOOGLE_CX=your_cx
```

```bash
-e EDDI_TOOLS_WEATHER_OPENWEATHERMAP_API_KEY=your_key
```

### Full Example

```bash
docker run --name eddi \
  --network eddi \
  -p 7070:7070 \
  -e EDDI_VAULT_MASTER_KEY="$EDDI_VAULT_MASTER_KEY" \
  -e QUARKUS_OIDC_TENANT_ENABLED=true \
  -e QUARKUS_OIDC_AUTH_SERVER_URL=http://keycloak:8080/realms/eddi \
  -e EDDI_TOOLS_WEBSEARCH_PROVIDER=google \
  -e EDDI_TOOLS_WEBSEARCH_GOOGLE_API_KEY=YOUR_KEY \
  -d labsai/eddi:latest
```
