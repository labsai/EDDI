# Getting started

[![Version](https://img.shields.io/github/v/release/labsai/EDDI?label=version&color=blue)](https://github.com/labsai/EDDI/releases)

Welcome to **EDDI**!

This article will help you to get started with **EDDI**.

## What You're Installing

EDDI is a **middleware orchestration service** for conversational AI. When you run EDDI, you're starting:

1. **The EDDI Service**: A Java/Quarkus application that exposes REST APIs for agent management and conversations
2. **A database**: MongoDB (default) or PostgreSQL, storing agent configurations, workflows, and conversation history
3. **The Manager dashboard**: A web UI bundled into the same service — no separate deployment. `http://localhost:7070/` redirects to the `/welcome` chooser, the admin dashboard is at `/manage`, and `/workforce` is the group-conversation workspace

Once running, you can:

- Create and configure agents through the API or dashboard
- Integrate agents into your applications via REST API
- Connect to LLM services (OpenAI, Claude, Gemini, etc.)
- Build complex conversation flows with behavior rules
- Call external APIs from your agent logic

## Verifying It Works

Whichever option you pick below, these three checks tell you the install
succeeded. They are the same ones CI runs against every published image, so if
they pass, the service is genuinely up — not merely listening.

```bash
# 1. Readiness — the only check that also proves the database is reachable
curl -sf http://localhost:7070/q/health/ready
# → {"status":"UP","checks":[...]}

# 2. The REST API is serving its own contract
curl -sf -o /dev/null -w '%{http_code}' http://localhost:7070/openapi
# → 200

# 3. No agents yet — an empty list is the correct answer on a fresh install
curl -sf http://localhost:7070/agentstore/agents/descriptors
# → []
```

Step 3 assumes authentication is off, which is the default
(`quarkus.oidc.tenant-enabled=false`). If you chose Keycloak in the install
wizard, that call returns `401` until you send a bearer token — a `401` there
still means the service is healthy. Steps 1 and 2 are always unauthenticated.

If step 1 returns `DOWN` or refuses the connection, the service is running but
the database is not — check `docker compose logs eddi`. A fresh install has **no
agents deployed**; see [Creating your first Agent](creating-your-first-agent/README.md)
or activate the Platform Operator at `/manage/operator`.

## Installation Options

### Option 0 - One-Command Install (Recommended)

**Linux / macOS / WSL2:**

```bash
curl -fsSL https://raw.githubusercontent.com/labsai/EDDI/main/install.sh | bash
```

**Windows (PowerShell):**

```powershell
Invoke-WebRequest -UseBasicParsing -Uri "https://raw.githubusercontent.com/labsai/EDDI/main/install.ps1" -OutFile "install.ps1"
Unblock-File .\install.ps1
.\install.ps1
```

The wizard guides you through choosing a database (MongoDB or PostgreSQL), optional authentication (Keycloak), and monitoring (Grafana). EDDI starts with no agents deployed — to create your first one, activate the **Platform Operator** at `/manage/operator` and describe the agent you want, or fill in the form-based wizard at `/manage/agents/wizard`.

### Option 1 - EDDI with Docker (Manual)

There are two ways to use `Docker` with **EDDI**, either with **`docker compose`** or launch the container manually.

_**Prerequisite**: You need an up and running `Docker` environment. (For references, see:_ [https://docs.docker.com/learn/](https://docs.docker.com/learn/))

### Use docker-compose (recommended)

1. `Checkout` the `docker compose` file from `Github`:[`https://github.com/labsai/EDDI/blob/main/docker-compose.yml`](https://github.com/labsai/EDDI/blob/main/docker-compose.yml)
2. Run Docker Command:

   ```
    docker compose up
   ```

### Use launch docker containers manually

1.  Create a shared network

    ```
    docker network create eddi-network
    ```

2.  Start a `MongoDB` instance using the `MongoDB` `Docker` image:

    ```
    docker run --name mongodb --network=eddi-network -d mongo
    ```

3.  Start **EDDI** :

    ```
    docker run --name eddi --network=eddi-network -p 7070:7070 -d labsai/eddi
    ```

## Option 2 - Deploy on Kubernetes

EDDI runs natively on any Kubernetes cluster (minikube, kind, GKE, EKS, AKS).

**Quickstart (all-in-one):**

```bash
kubectl apply -f https://raw.githubusercontent.com/labsai/EDDI/main/k8s/quickstart.yaml
```

The manifest deploys EDDI with an **empty** vault master key, so it starts but
cannot encrypt secrets. Generate the key next. `create-secrets.sh` lives in the
repository, so this step needs a checkout:

```bash
git clone https://github.com/labsai/EDDI.git && cd EDDI
bash k8s/create-secrets.sh --auto
kubectl rollout restart deployment/eddi -n eddi
```

Without a checkout, do the same thing by hand — EDDI reads secrets from a
mounted *file*, not environment variables, because env is readable through
`/proc/<pid>/environ` and leaks into crash dumps:

```bash
printf 'eddi.vault.master-key=%s
' "$(openssl rand -base64 24)" > /tmp/application-secrets.properties
kubectl create secret generic eddi-secrets -n eddi --from-file=/tmp/application-secrets.properties
shred -u /tmp/application-secrets.properties
kubectl rollout restart deployment/eddi -n eddi
```

**Using Kustomize overlays:**

```bash
kubectl apply -k k8s/overlays/mongodb/    # MongoDB backend
kubectl apply -k k8s/overlays/postgres/   # PostgreSQL backend
```

**Using Helm:**

```bash
helm install eddi ./helm/eddi --namespace eddi --create-namespace
```

See the [Kubernetes Deployment Guide](kubernetes.md) for full details including auth, monitoring, NATS, Ingress, and production hardening.

## Option 3 - Run from Source

#### _Prerequisites:_

- Java 25
- MongoDB ≥ 6.0 (or PostgreSQL)
- Docker, if you want Dev Services to start the database for you

No local Maven install is needed — the repository ships the Maven wrapper
(`./mvnw`, or `.\mvnw.cmd` on Windows), which downloads the pinned version on
first use.

### How to run the project

Setup a local MongoDB (≥ 6.0) or PostgreSQL instance.

> **Note:** If no database instance is available, Quarkus Dev Services will try to start a container automatically (requires Docker running on the host).

On a terminal, under project root folder, run the following command:

```shell
./mvnw compile quarkus:dev
```

1. Go to Browser --> [http://localhost:7070](http://localhost:7070)

### Build App & Docker image

```bash
./mvnw clean package '-Dquarkus.container-image.build=true'
```

### Download from Docker hub registry

```bash
docker pull labsai/eddi
```

[https://hub.docker.com/r/labsai/eddi](https://hub.docker.com/r/labsai/eddi)

### Run Docker image

For production, launch standalone mongodb and then start an eddi instance as defined in the docker-compose file

```bash
docker compose up
```

For development, use

```bash
docker compose -f docker-compose.yml -f docker-compose.local.yml up
```

For integration testing run

```bash
./mvnw verify -DskipITs=false
```

This uses Testcontainers to automatically start EDDI + MongoDB/PostgreSQL in Docker containers for E2E testing. Requires Docker to be running.
