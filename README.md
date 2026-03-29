![EDDI Banner Image](/screenshots/EDDI-landing-page-image.png)

# E.D.D.I — Multi-Agent Orchestration Middleware for Conversational AI

[![CI](https://github.com/labsai/EDDI/actions/workflows/ci.yml/badge.svg)](https://github.com/labsai/EDDI/actions/workflows/ci.yml) [![CodeQL](https://github.com/labsai/EDDI/actions/workflows/codeql.yml/badge.svg)](https://github.com/labsai/EDDI/actions/workflows/codeql.yml) [![Codacy Badge](https://app.codacy.com/project/badge/Grade/2c5d183d4bd24dbaa77427cfbf5d4074)](https://app.codacy.com/organizations/gh/labsai/dashboard?utm_source=github.com&utm_medium=referral&utm_content=labsai/EDDI&utm_campaign=Badge_Grade)

**E.D.D.I** (Enhanced Dialog Driven Interface) is a production-grade **multi-agent orchestration middleware** that coordinates between users, AI agents (LLMs), and business systems. It provides intelligent routing, conversation management, and API orchestration for building sophisticated AI-powered applications — all through configuration, not code.

Built with **Java 25** and **Quarkus**. Ships as a **Red Hat-certified Docker image**. Deploy on Docker, Kubernetes, or OpenShift.

**Latest version: 6.0.0** · [Website](https://eddi.labs.ai/) · [Documentation](https://docs.labs.ai/) · License: Apache 2.0

---

## ✨ Features at a Glance

### 🤖 Multi-Agent Orchestration
- 🔀 **Intelligent Routing** — Direct conversations to different agents based on context, rules, and intent
- 🗣️ **Group Conversations** — Multi-agent debates with 5 built-in styles (Round Table, Peer Review, Devil's Advocate, Delphi, Debate)
- 🪆 **Nested Groups** — Compose groups of groups for tournament brackets, red-team vs blue-team, and panel reviews
- 👥 **Managed Conversations** — Intent-based auto-routing with one conversation per user per intent

### 🧠 LLM Provider Support (12 Providers)
- 🟢 **OpenAI** · **Anthropic Claude** · **Google Gemini** · **Mistral AI**
- ☁️ **Azure OpenAI** · **Amazon Bedrock** · **Oracle GenAI** · **Google Vertex AI**
- 🏠 **Ollama** · **Jlama** · **Hugging Face** — Run models locally or in your own cloud
- 🔗 **OpenAI-compatible endpoints** — DeepSeek, Cohere, and any compatible API via `baseUrl`

### 🔄 Smart Model Cascading
- 📉 **Cost Optimization** — Try cheap/fast models first, escalate to powerful models only when needed
- 📊 **4 Confidence Strategies** — Structured output, heuristic, judge model, or none
- 💰 **Per-Conversation Budgets** — Automatic cost tracking with budget caps

### 🛠️ Built-In AI Agent Tools
- 🔍 **Web Search** — DuckDuckGo or Google Custom Search
- 🧮 **Calculator** — Sandboxed math parser (no code injection)
- 🌐 **Web Scraper** · 📄 **PDF Reader** — SSRF-protected content extraction
- ☁️ **Weather** · 🕐 **DateTime** · 📊 **Data Formatter** · 📝 **Text Summarizer**
- 🔌 **HTTP Calls as Tools** — Expose your own APIs as LLM-callable tools with full security sandboxing

### 📚 RAG (Retrieval-Augmented Generation)
- 📦 **7 Embedding Providers** — OpenAI, Ollama, Azure OpenAI, Mistral, Bedrock, Cohere, Vertex AI
- 🗄️ **5 Vector Stores** — pgvector, In-Memory, MongoDB Atlas, Elasticsearch, Qdrant
- 🌐 **httpCall RAG** — Zero-infrastructure RAG via any search API
- 📥 **REST Ingestion API** — Async document ingestion with status tracking

### 🔗 Protocol & Interoperability
- 🧩 **MCP Server** (48+ tools) — Full EDDI control from Claude Desktop, IDE plugins, or any MCP client
- 🧩 **MCP Client** — Connect agents to external MCP tool servers
- 🤝 **A2A Protocol** — Agent-to-Agent peer communication with skill discovery and Agent Cards
- 📋 **OpenAPI-to-Agent** — Paste an OpenAPI spec, get a fully deployed API-calling agent

### 🧠 Memory & Context
- 💾 **Persistent User Memory** — Agents remember facts, preferences, and context across conversations
- 🪟 **Token-Aware Windowing** — Intelligent context packing with anchored opening steps
- 📝 **Property Extraction** — Config-driven slot-filling for structured data capture
- 🔄 **Conversation State** — Full history with undo/redo support

### 🔐 Enterprise Security
- 🏦 **Secrets Vault** — Envelope encryption (PBKDF2 + AES) for API keys, never plaintext in DB
- 🛡️ **SSRF Protection** — URL validation blocks private/internal addresses on all tools
- 🔒 **Sandboxed Evaluation** — No `eval()`, no script engines — safe recursive-descent math parser
- 🔑 **OAuth 2.0 / Keycloak** — Authentication, authorization, and tenant isolation
- 📜 **Immutable Audit Ledger** — Write-once trail with HMAC integrity for EU AI Act compliance
- 🚫 **No Dynamic Code Execution** — Custom logic runs in external MCP servers, outside the EDDI perimeter

### ⚙️ Configuration-Driven Architecture
- 📄 **JSON Configs, Not Code** — Agent behavior defined in versioned JSON documents
- 🔧 **Lifecycle Pipeline** — Pluggable task pipeline: Input → Parse → Rules → API/LLM → Output
- 📦 **Composable Agents** — Agents assembled from reusable, version-controlled workflows and extensions
- 🧪 **Behavior Rules** — IF-THEN logic engine for routing, orchestration, and business logic

### 🚀 Cloud-Native & Observable
- 🐳 **One-Command Install** — Interactive wizard sets up EDDI + database + starter agent via Docker
- ☸️ **Kubernetes / OpenShift** — Kustomize overlays, Helm charts, HPA, PDB, NetworkPolicy
- 📊 **Prometheus Metrics** — Built-in Micrometer instrumentation at `/q/metrics`
- 🩺 **Health Checks** — Liveness & readiness probes at `/q/health/live` and `/q/health/ready`
- 🔄 **NATS JetStream** — Async event bus for distributed processing
- ⚡ **Virtual Threads** — Java 25 virtual threads for true concurrency, no GIL

### 🖥️ Manager Dashboard & Chat UI
- 🎨 **React 19 Manager UI** — Modern admin dashboard for building, testing, and deploying agents
- 💬 **Chat Widget** — Embeddable React chat UI with SSE streaming and Keycloak auth
- 🗃️ **DB-Agnostic** — Choose MongoDB or PostgreSQL, switch without code changes

---

## 🏁 Quick Start

**Linux / macOS / WSL2:**

```bash
curl -fsSL https://raw.githubusercontent.com/labsai/EDDI/main/install.sh | bash
```

**Windows (PowerShell):**

```powershell
iwr -useb https://raw.githubusercontent.com/labsai/EDDI/main/install.ps1 | iex
```

This starts an interactive wizard that sets up EDDI + your choice of database via Docker, deploys the **Agent Father** starter agent, and guides you through creating your first AI agent. Requires [Docker](https://docs.docker.com/get-docker/).

**Options:**

```bash
bash install.sh --defaults                 # All defaults, no prompts
bash install.sh --db=postgres --with-auth  # PostgreSQL + Keycloak
bash install.sh --full                     # Everything enabled
bash install.sh --local                    # Build from local source
```

---

## 🧩 Quarkus SDK

Building a Quarkus app that talks to EDDI? Use the **[quarkus-eddi](https://github.com/quarkiverse/quarkus-eddi)** extension:

```xml
<dependency>
    <groupId>io.quarkiverse.eddi</groupId>
    <artifactId>quarkus-eddi</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```java
@Inject EddiClient eddi;

String answer = eddi.chat("my-agent", "Hello!");
```

Features: Dev Services (auto-starts EDDI in dev mode), fluent API, SSE streaming, `@EddiAgent` endpoint wiring, `@EddiTool` MCP bridge. See the [quarkus-eddi README](https://github.com/quarkiverse/quarkus-eddi) for full docs.

---

## 📖 Documentation

| Guide | Description |
|-------|-------------|
| **[Getting Started](docs/getting-started.md)** | Setup and first steps |
| **[Developer Quickstart](docs/developer-quickstart.md)** | Build your first agent in 5 minutes |
| **[Architecture](docs/architecture.md)** | Deep dive into EDDI's design and pipeline |
| **[LangChain Integration](docs/langchain.md)** | Connecting to 12 LLM providers |
| **[Behavior Rules](docs/behavior-rules.md)** | Configuring agent routing logic |
| **[HTTP Calls](docs/httpcalls.md)** | External API integration |
| **[RAG](docs/rag.md)** | Knowledge base retrieval setup |
| **[MCP Server](docs/mcp-server.md)** | 48+ tools for AI-assisted agent management |
| **[A2A Protocol](docs/a2a-protocol.md)** | Agent-to-Agent peer communication |
| **[Group Conversations](docs/group-conversations.md)** | Multi-agent debate orchestration |
| **[User Memory](docs/user-memory.md)** | Cross-conversation fact retention |
| **[Model Cascading](docs/model-cascade.md)** | Cost-optimized multi-model routing |
| **[Security](docs/security.md)** | SSRF protection, sandboxing, and hardening |
| **[Secrets Vault](docs/secrets-vault.md)** | Envelope encryption for sensitive data |
| **[Audit Ledger](docs/audit-ledger.md)** | EU AI Act-compliant audit trail |
| **[Kubernetes](docs/kubernetes.md)** | Deploy with Kustomize or Helm |
| **[Red Hat OpenShift](docs/redhat-openshift.md)** | Certified container, automated release |
| **[Full Documentation](https://docs.labs.ai/)** | Complete documentation site |

---

## 🏗️ Development

### Prerequisites

- Java 25 ([Eclipse Temurin](https://adoptium.net/) recommended)
- Maven 3.9+ (bundled via `mvnw` wrapper)
- MongoDB ≥ 6.0 or PostgreSQL

### Run Locally

```bash
./mvnw compile quarkus:dev
```

Then open [http://localhost:7070](http://localhost:7070).

> **💡 Secrets Vault:** To use the secrets vault (storing API keys encrypted), set the master key before starting:
>
> ```bash
> # Linux/macOS
> export EDDI_VAULT_MASTER_KEY=my-dev-passphrase
>
> # Windows (PowerShell)
> $env:EDDI_VAULT_MASTER_KEY = "my-dev-passphrase"
>
> # Or in a .env file (already in .gitignore)
> echo "EDDI_VAULT_MASTER_KEY=my-dev-passphrase" > .env
> ```
>
> Without this, the vault is disabled and secret management returns HTTP 503. Any passphrase works for local development. See [Secrets Vault](docs/secrets-vault.md) for production setup.

### Build & Docker

```bash
# Build app + Docker image
./mvnw clean package '-Dquarkus.container-image.build=true'

# Build without container (for install.sh --local)
./mvnw package -DskipTests

# Generate third-party licenses (Red Hat certification)
./mvnw package -Plicense-gen -DskipTests
```

### Docker Hub

```bash
docker pull labsai/eddi
```

→ [hub.docker.com/r/labsai/eddi](https://hub.docker.com/r/labsai/eddi)

### Docker Compose

```bash
docker-compose up                                                                    # Default (MongoDB)
docker-compose -f docker-compose.yml -f docker-compose.local.yml up                  # Local build
docker-compose -f docker-compose.yml -f docker-compose.auth.yml -f docker-compose.monitoring.yml up  # Auth + monitoring
```

### Kubernetes

```bash
# Quickstart
kubectl apply -f https://raw.githubusercontent.com/labsai/EDDI/main/k8s/quickstart.yaml

# Kustomize overlays
kubectl apply -k k8s/overlays/mongodb/     # MongoDB backend
kubectl apply -k k8s/overlays/postgres/    # PostgreSQL backend

# Helm
helm install eddi ./helm/eddi --namespace eddi --create-namespace
```

Includes overlays for auth (Keycloak), monitoring (Prometheus/Grafana), NATS messaging, Ingress, and production hardening.
See the [Kubernetes Guide](docs/kubernetes.md) for details.

---

## 🤝 Contributing

We welcome contributions! Please read our [Contributing Guide](CONTRIBUTING.md) for details on setting up your development environment, code style, commit conventions, and the pull request process.

Every PR is automatically checked by CI (build + tests), CodeQL (security), dependency review, and AI-powered code review.

## 🔒 Security

Please report security vulnerabilities privately — see our [Security Policy](SECURITY.md).

## 📜 Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md).
