# Docker

## Quick Start

### Without Authentication (default)

```bash
docker-compose up
```

This starts EDDI on port `7070` and MongoDB. No login required.

### With Keycloak Authentication

The EDDI-Manager repo provides a full-stack docker-compose with Keycloak:

```bash
# From the EDDI-Manager repo
docker compose -f docker-compose.keycloak.yml up
```

This starts:

- **Keycloak 26** on port `8180` (admin console: `http://localhost:8180`, login `admin`/`admin`)
- **EDDI** on port `7070` with OIDC auth enabled
- **MongoDB** for data storage

Pre-configured test users:
| Username | Password | Role |
|----------|----------|------|
| `eddi` | `eddi` | admin |
| `viewer` | `viewer` | viewer (read-only) |

### Manual Docker Setup

Start MongoDB:

```bash
docker run --name mongodb -d mongo:6.0
```

Start EDDI (without auth):

```bash
docker run --name eddi --link mongodb:mongodb -p 7070:7070 -d labsai/eddi:latest
```

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
