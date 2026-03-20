# Getting started

**Version: ≥5.0.x**

Welcome to **EDDI**!

This article will help you to get started with **EDDI**.

## What You're Installing

EDDI is a **middleware orchestration service** for conversational AI. When you run EDDI, you're starting:

1. **The EDDI Service**: A Java/Quarkus application that exposes REST APIs for bot management and conversations
2. **MongoDB**: A database that stores bot configurations, packages, and conversation history
3. **Optional UI**: A web-based dashboard for managing bots (accessible at http://localhost:7070)

Once running, you can:
- Create and configure bots through the API or dashboard
- Integrate bots into your applications via REST API
- Connect to LLM services (OpenAI, Claude, Gemini, etc.)
- Build complex conversation flows with behavior rules
- Call external APIs from your bot logic

## Installation Options

### Option 0 - One-Command Install (Recommended)

**Linux / macOS / WSL2:**
```bash
curl -fsSL https://raw.githubusercontent.com/labsai/EDDI/main/install.sh | bash
```

**Windows (PowerShell):**
```powershell
iwr -useb https://raw.githubusercontent.com/labsai/EDDI/main/install.ps1 | iex
```

The wizard guides you through choosing a database (MongoDB or PostgreSQL), optional authentication (Keycloak), and monitoring (Grafana). After setup, Bot Father is deployed automatically to help you create your first AI bot.

### Option 1 - EDDI with Docker (Manual)

There are two ways to use `Docker` with **EDDI**, either with **`docker-compose`** or launch the container manually.

_**Prerequisite**: You need an up and running `Docker` environment. (For references, see:_ [https://docs.docker.com/learn/](https://docs.docker.com/learn/))

### Use docker-compose (recommended)

1. `Checkout` the `docker-compose` file from `Github`:[`https://github.com/labsai/EDDI/blob/master/docker-compose.yml`](https://github.com/labsai/EDDI/blob/master/docker-compose.yml)
2.  Run Docker Command:

    ```
     docker-compose up
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

## Option 2 - Run from Source

#### _Prerequisites:_

* Java 21
* Maven 3.8.4
* MongoDB > 4.0

### How to run the project

Setup a local mongodb (> v4.0)

{% hint style="info" %}
If no mongodb instance is available on the give host, quarkus will try to run a mongodb container on startup, given the host has a docker running server
{% endhint %}

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
docker-compose up
```

For development, use

```bash
docker-compose -f docker-compose.yml -f docker-compose.local.yml up
```

For integration testing run

```bash
./integration-tests.sh
```

or

```bash
docker-compose -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.testing.yml -p ci up -d
```

