# Docker

## Quick Start

### Without Authentication (default)

```bash
docker compose up
```

This starts EDDI on port `7070` and MongoDB. No login required.

### With Keycloak Authentication

This repository ships a Keycloak overlay. Layer it on the base stack:

```bash
# The overlay has no default for the Keycloak admin password and refuses to
# start without one
echo "KEYCLOAK_ADMIN_PASSWORD=$(openssl rand -base64 24)" >> .env
docker compose -f docker-compose.yml -f docker-compose.auth.yml up
```

This starts:

- **Keycloak 26** on `127.0.0.1:8180` (admin console: `http://localhost:8180/admin`, login `admin` / your `KEYCLOAK_ADMIN_PASSWORD`)
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

Start MongoDB:

```bash
docker run --name mongodb -d mongo:6.0
```

Start EDDI (without auth) — **local development only:**

> ⚠️ These three opt-outs disable authentication on *every* endpoint, including
> `/secretstore` (the vault) and `/mcp` (agent CRUD). The port is bound to
> `127.0.0.1` below so the container is not reachable from the network. Do not
> publish it on `0.0.0.0`, and do not use this form on a shared or cloud host.
> For anything beyond your own machine, use the authenticated example below.

```bash
docker run --name eddi \
  --link mongodb:mongodb \
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
  --link mongodb:mongodb \
  -p 7070:7070 \
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

> **Note:** `QUARKUS_OIDC_TENANT_ENABLED` is a **runtime** toggle. No rebuild needed to enable/disable auth.

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
  --link mongodb:mongodb \
  -p 7070:7070 \
  -e QUARKUS_OIDC_TENANT_ENABLED=true \
  -e QUARKUS_OIDC_AUTH_SERVER_URL=http://keycloak:8080/realms/eddi \
  -e EDDI_TOOLS_WEBSEARCH_PROVIDER=google \
  -e EDDI_TOOLS_WEBSEARCH_GOOGLE_API_KEY=YOUR_KEY \
  -d labsai/eddi:latest
```
