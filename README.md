![EDDI Banner Image](/screenshots/EDDI-landing-page-image.png)

# E.D.D.I — Multi-Agent Orchestration Middleware for Conversational AI

[![CI](https://github.com/labsai/EDDI/actions/workflows/ci.yml/badge.svg)](https://github.com/labsai/EDDI/actions/workflows/ci.yml) [![CodeQL](https://github.com/labsai/EDDI/actions/workflows/codeql.yml/badge.svg)](https://github.com/labsai/EDDI/actions/workflows/codeql.yml) [![Codacy Badge](https://app.codacy.com/project/badge/Grade/2c5d183d4bd24dbaa77427cfbf5d4074)](https://app.codacy.com/organizations/gh/labsai/dashboard?utm_source=github.com&utm_medium=referral&utm_content=labsai/EDDI&utm_campaign=Badge_Grade) [![Docker Pulls](https://img.shields.io/docker/pulls/labsai/eddi)](https://hub.docker.com/r/labsai/eddi)

**E.D.D.I** (Enhanced Dialog Driven Interface) is a production-grade **multi-agent orchestration middleware** that coordinates between users, AI agents (LLMs), and business systems. It provides intelligent routing, conversation management, and API orchestration for building sophisticated AI-powered applications — all through configuration, not code.

Built with **Java 25** and **Quarkus**. Ships as a **Red Hat-certified Docker image**. Deploy on Docker, Kubernetes, or OpenShift.

**Latest version: 6.0.0-RC1** · [Website](https://eddi.labs.ai/) · [Documentation](https://docs.labs.ai/) · License: Apache 2.0

---

## 🏗️ Standards & Protocol Compliance

EDDI implements industry standards and emerging AI interoperability protocols — not proprietary APIs:

| Standard / Protocol | Support | Description |
|---|---|---|
| **[MCP](https://modelcontextprotocol.io/)** (Model Context Protocol) | ✅ Server (48+ tools) + Client | Full EDDI control from Claude Desktop, IDE plugins, or any MCP client. Agents can also consume external MCP tool servers |
| **[A2A](https://google.github.io/A2A/)** (Agent-to-Agent Protocol) | ✅ Full | Google's peer communication protocol — Agent Cards, skill discovery, cross-platform agent interop |
| **[OpenAPI](https://www.openapis.org/)** 3.1 | ✅ Native | Auto-generated spec at `/q/openapi`. Paste any OpenAPI spec → get a fully deployed API-calling agent |
| **OAuth 2.0 / OIDC** | ✅ Native | Keycloak-backed authentication, authorization, and tenant isolation |
| **SSE** (Server-Sent Events) | ✅ Native | Real-time streaming for chat responses, group discussions, and log feeds |
| **[EU AI Act](https://artificialintelligenceact.eu/)** | ✅ Compliant | Immutable HMAC-SHA256 audit ledger, decision traceability, risk classification guidance |
| **[GDPR](https://gdpr.eu/)** / **[CCPA](https://oag.ca.gov/privacy/ccpa)** | ✅ Built-in | Data erasure, portability, restriction of processing (Art. 18), per-category retention, pseudonymization |
| **[HIPAA](https://www.hhs.gov/hipaa/)** | ✅ Ready | Deployment guide, BAA template, encryption guidance, LLM provider BAA matrix |

---

## ✨ Features at a Glance

### 🤖 Multi-Agent Orchestration

- 🔀 **Intelligent Routing** — Direct conversations to different agents based on context, rules, and intent
- 🗣️ **Group Conversations** — Multi-agent debates with 5 built-in styles (Round Table, Peer Review, Devil's Advocate, Delphi, Debate)
- 🪆 **Nested Groups** — Compose groups of groups for tournament brackets, red-team vs blue-team, and panel reviews
- 👥 **Managed Conversations** — Intent-based auto-routing with one conversation per user per intent
- 🧙 **Agent Father** — Meta-agent that creates other agents through conversation (ships out of the box)
- 🎯 **Capability Matching** — A2A-style discovery and soft-routing via agent skill attributes and confidence scores

### 🧠 LLM Provider Support (12 Providers)

- 🟢 **OpenAI** · **Anthropic Claude** · **Google Gemini** · **Mistral AI**
- ☁️ **Azure OpenAI** · **Amazon Bedrock** · **Oracle GenAI** · **Google Vertex AI**
- 🏠 **Ollama** · **Jlama** · **Hugging Face** — Run models locally or in your own cloud
- 🔗 **OpenAI-compatible endpoints** — DeepSeek, Cohere, and any compatible API via `baseUrl`

### 🔗 Protocol & Interoperability

- 🧩 **MCP Server** (48+ tools) — Full EDDI control from Claude Desktop, IDE plugins, or any MCP client
- 🧩 **MCP Client** — Connect agents to external MCP tool servers with governance (READ_ONLY / STATE_CHANGING classification)
- 🤝 **A2A Protocol** — Agent-to-Agent peer communication with skill discovery and Agent Cards
- 📋 **OpenAPI-to-Agent** — Paste an OpenAPI spec, get a fully deployed API-calling agent

### 💭 Memory & Context

- 💾 **Persistent User Memory** — Agents remember facts, preferences, and context across conversations
- 💤 **Dream Consolidation** — Background memory maintenance: stale entry pruning, contradiction detection, and summarization (inspired by [Claude's memory architecture](https://www.anthropic.com/engineering/claude-code-best-practices))
- 🪟 **Token-Aware Windowing** — Intelligent context packing with anchored opening steps and per-model tokenizer support
- 📝 **Rolling Summary** — Incremental LLM-powered summarization of older turns, with a **Conversation Recall Tool** for drill-back
- 🔧 **Property Extraction** — Config-driven slot-filling for structured data capture with `longTerm` / `conversation` / `step` scoping
- 🔄 **Conversation State** — Full history with undo/redo support

### 📈 Smart Model Cascading

- 📉 **Cost Optimization** — Try cheap/fast models first, escalate to powerful models only when needed
- 📊 **4 Confidence Strategies** — Structured output, heuristic, judge model, or none
- 💰 **Per-Conversation Budgets** — Automatic cost tracking with budget caps
- 🏢 **Tenant Cost Ceilings** — Monthly cost budgets per tenant with automatic enforcement

### 📚 RAG (Retrieval-Augmented Generation)

- 📦 **7 Embedding Providers** — OpenAI, Ollama, Azure OpenAI, Mistral, Bedrock, Cohere, Vertex AI
- 🗄️ **5 Vector Stores** — pgvector, In-Memory, MongoDB Atlas, Elasticsearch, Qdrant
- 🌐 **httpCall RAG** — Zero-infrastructure RAG via any search API
- 📥 **REST Ingestion API** — Async document ingestion with status tracking

### 🛠️ Built-In AI Agent Tools

- 🔍 **Web Search** — DuckDuckGo or Google Custom Search
- 🧮 **Calculator** — Sandboxed math parser (no code injection)
- 🌐 **Web Scraper** · 📄 **PDF Reader** — SSRF-protected content extraction
- ☁️ **Weather** · 🕐 **DateTime** · 📊 **Data Formatter** · 📝 **Text Summarizer**
- 🔌 **HTTP Calls as Tools** — Expose your own APIs as LLM-callable tools with full security sandboxing
- 🧠 **Memory Tools** — LLM-callable tools for reading/writing persistent user memory
- 🔙 **Conversation Recall** — Tool for LLMs to drill back into summarized conversation history
- 📎 **Multimodal Attachments** — Image, PDF, audio, and video attachment pipeline with MIME-based routing

### ⏰ Scheduled Execution & Heartbeats

- 🫀 **Heartbeat Triggers** — Periodic agent wake-ups at configurable intervals (inspired by [OpenClaw's](https://github.com/openclaw) proactive agent patterns)
- ⏲️ **Cron Scheduling** — Standard cron expressions for timed agent execution
- 🔄 **Conversation Strategies** — `persistent` (reuse same conversation) or `new` (fresh context each fire)
- 📊 **Fire Logging** — Complete execution history with status, duration, cost, and retry tracking
- 🌙 **Dream Cycles** — Scheduled background memory consolidation (pruning, contradiction detection, summarization)

### 🔐 Enterprise Security & Compliance

- 🏦 **Secrets Vault** — Envelope encryption (PBKDF2 + AES) for API keys, never plaintext in DB. DEK/KEK rotation, tenant-scoped
- 🛡️ **SSRF Protection** — URL validation blocks private/internal addresses on all tools
- 🔒 **Sandboxed Evaluation** — No `eval()`, no script engines — safe recursive-descent math parser
- 🔑 **OAuth 2.0 / Keycloak** — Authentication, authorization, and tenant isolation
- 📜 **Immutable Audit Ledger** — Write-once trail with HMAC-SHA256 integrity for EU AI Act compliance
- ✍️ **Agent Signing** — Ed25519 cryptographic identity per agent, audit entries signed with agent private keys
- 🚫 **No Dynamic Code Execution** — Custom logic runs in external MCP servers, outside the EDDI perimeter
- 🗑️ **GDPR Compliance Suite** — Cascading user data erasure, data portability exports, Art. 18 restriction of processing, per-category retention policies
- 🏥 **HIPAA Ready** — Deployment guide, BAA template, LLM provider BAA matrix, session timeout guidance
- 🔍 **Compliance Startup Checks** — Advisory warnings on boot for TLS and database encryption gaps

### ⚙️ Configuration-Driven Architecture

- 📄 **JSON Configs, Not Code** — Agent behavior defined in versioned JSON documents
- 🔧 **Lifecycle Pipeline** — Pluggable task pipeline: Input → Parse → Rules → API/LLM → Output
- 📦 **Composable Agents** — Agents assembled from reusable, version-controlled workflows and extensions
- 🧪 **Behavior Rules** — IF-THEN logic engine for routing, orchestration, and business logic
- 📤 **Import / Export** — Agents are portable as ZIP files with secret scrubbing on export
- 📝 **Prompt Snippets** — Reusable, versioned system prompt building blocks (`{{snippets.safety_instructions}}`)
- 📎 **Content Type Routing** — MIME-based behavior rule conditions for multimodal attachment routing

### 🚀 Cloud-Native & Observable

- 🐳 **One-Command Install** — Interactive wizard sets up EDDI + database + starter agent via Docker
- ☸️ **Kubernetes / OpenShift** — Kustomize overlays, Helm charts, HPA, PDB, NetworkPolicy
- 📊 **Prometheus & Grafana** — Built-in Micrometer instrumentation at `/q/metrics`
- 🩺 **Health Checks** — Liveness & readiness probes at `/q/health/live` and `/q/health/ready`
- 🔄 **NATS JetStream** — Async event bus for distributed processing
- ⚡ **Virtual Threads** — Java 25 virtual threads for true concurrency, no GIL
- 🗃️ **DB-Agnostic** — Choose MongoDB or PostgreSQL, switch with one env var. Single Docker image for both
- 🏗️ **Red Hat Certified** — Container certification with automated preflight checks in CI

### 🖥️ Manager Dashboard & Chat UI

- 🎨 **React 19 Manager UI** — Modern admin dashboard for agent building, testing, deployment, and monitoring
- 💬 **Chat Widget** — Embeddable React chat UI with SSE streaming and Keycloak auth
- 🔍 **Audit Trail Viewer** — Timeline-based compliance and debugging UI
- 📋 **Logs Panel** — Live SSE log streaming + searchable history
- 🔑 **Secrets Manager** — Write-only UI for vault entries with copy-reference support

---

## 🏁 Quick Start

The fastest way to get EDDI running is the **one-command installer**. It sets up EDDI + your choice of database via Docker Compose, deploys the [Agent Father](docs/agent-father-deep-dive.md) starter agent, and walks you through creating your first AI agent.

**Linux / macOS / WSL2:**

```bash
curl -fsSL https://raw.githubusercontent.com/labsai/EDDI/main/install.sh | bash
```

**Windows (PowerShell):**

```powershell
iwr -useb https://raw.githubusercontent.com/labsai/EDDI/main/install.ps1 | iex
```

> **Note:** If your Antivirus blocks this command as "malicious content", securely download and run it instead:
>
> ```powershell
> Invoke-WebRequest -Uri "https://raw.githubusercontent.com/labsai/EDDI/main/install.ps1" -OutFile "install.ps1"
> Unblock-File .\install.ps1
> .\install.ps1
> ```

Requires [Docker](https://docs.docker.com/get-docker/). The wizard auto-generates a unique vault encryption key for secret management.

<details>
<summary><strong>🔧 Installer options</strong></summary>

```bash
bash install.sh --defaults                 # All defaults, no prompts
bash install.sh --db=postgres --with-auth  # PostgreSQL + Keycloak
bash install.sh --full                     # Everything enabled (DB + auth + monitoring)
bash install.sh --local                    # Build Docker image from local source
```

The `--local` flag is for contributors testing pre-release builds:

```bash
./mvnw package -DskipTests    # Build the Java app
bash install.sh --local        # Build Docker image + start containers
```

</details>

### 🔄 Updating

The installer creates an `eddi` CLI wrapper that makes updating easy:

```bash
eddi update
```

This pulls the latest Docker image from the registry and restarts the containers. It works even when the same tag (e.g. `6.0.0-RC1`) was re-published — Docker always checks the remote digest for changes.

> **`eddi` command not found?** The CLI lives at `~/.eddi/eddi` (Linux/macOS) or `~/.eddi/eddi.cmd` (Windows). Either restart your terminal so the PATH takes effect, or use the full path:
>
> ```bash
> # Linux / macOS
> ~/.eddi/eddi update
>
> # Windows (PowerShell)
> & "$HOME\.eddi\eddi.cmd" update
> ```

<details>
<summary><strong>Manual update (without the CLI)</strong></summary>

If the `eddi` CLI isn't available, run the equivalent docker commands from your install directory (`~/.eddi` by default):

```bash
cd ~/.eddi
docker compose --env-file .env -f docker-compose.yml pull
docker compose --env-file .env -f docker-compose.yml up -d
```

Adjust the `-f` flags to match your setup (e.g. add `-f docker-compose.auth.yml` if using Keycloak).

</details>

### 🐳 Docker Compose (Manual)

If you prefer manual control over Docker Compose:

```bash
# Default (EDDI + MongoDB)
docker compose up

# PostgreSQL instead of MongoDB
EDDI_DATASTORE_TYPE=postgres docker compose -f docker-compose.yml -f docker-compose.postgres.yml up

# With Keycloak authentication
docker compose -f docker-compose.yml -f docker-compose.auth.yml up

# With Prometheus + Grafana monitoring
docker compose -f docker-compose.yml -f docker-compose.monitoring.yml up

# Full stack (all overlays)
docker compose -f docker-compose.yml -f docker-compose.auth.yml \
  -f docker-compose.monitoring.yml -f docker-compose.nats.yml up
```

Available compose overlays: `docker-compose.auth.yml` (Keycloak), `docker-compose.monitoring.yml` (Prometheus+Grafana), `docker-compose.nats.yml` (NATS JetStream), `docker-compose.postgres.yml` / `docker-compose.postgres-only.yml`, `docker-compose.local.yml` (build from source), `docker-compose.testing.yml` (integration tests).

```bash
docker pull labsai/eddi    # Pull latest from Docker Hub
```

→ [hub.docker.com/r/labsai/eddi](https://hub.docker.com/r/labsai/eddi)

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

| Guide                                                        | Description                                |
| ------------------------------------------------------------ | ------------------------------------------ |
| **[Getting Started](docs/getting-started.md)**               | Setup and first steps                      |
| **[Developer Quickstart](docs/developer-quickstart.md)**     | Build your first agent in 5 minutes        |
| **[Architecture](docs/architecture.md)**                     | Deep dive into EDDI's design and pipeline  |
| **[LLM Configuration](docs/langchain.md)**                   | Connecting to 12 LLM providers             |
| **[Behavior Rules](docs/behavior-rules.md)**                 | Configuring agent routing logic            |
| **[HTTP Calls](docs/httpcalls.md)**                          | External API integration                   |
| **[RAG](docs/rag.md)**                                       | Knowledge base retrieval setup             |
| **[MCP Server](docs/mcp-server.md)**                         | 48+ tools for AI-assisted agent management |
| **[A2A Protocol](docs/a2a-protocol.md)**                     | Agent-to-Agent peer communication          |
| **[Group Conversations](docs/group-conversations.md)**       | Multi-agent debate orchestration           |
| **[User Memory](docs/user-memory.md)**                       | Cross-conversation fact retention          |
| **[Model Cascading](docs/model-cascade.md)**                 | Cost-optimized multi-model routing         |
| **[Prompt Snippets](docs/prompt-snippets-guide.md)**         | Reusable system prompt building blocks     |
| **[Attachments](docs/attachments-guide.md)**                 | Multimodal attachment pipeline             |
| **[Capability Matching](docs/capability-match-guide.md)**    | A2A skill discovery and routing            |
| **[Security](docs/security.md)**                             | SSRF protection, sandboxing, and hardening |
| **[Secrets Vault](docs/secrets-vault.md)**                   | Envelope encryption for sensitive data     |
| **[Audit Ledger](docs/audit-ledger.md)**                     | EU AI Act-compliant audit trail            |
| **[Kubernetes](docs/kubernetes.md)**                         | Deploy with Kustomize or Helm              |
| **[Red Hat OpenShift](docs/redhat-openshift.md)**            | Certified container, automated release     |
| **[Agent Father Deep Dive](docs/agent-father-deep-dive.md)** | How the meta-agent works                   |
| **[Full Documentation](https://docs.labs.ai/)**              | Complete documentation site                |

---

## 🏗️ Development

### Prerequisites

- Java 25
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

### ☸️ Kubernetes

```bash
# Quickstart (one-file deployment)
kubectl apply -f https://raw.githubusercontent.com/labsai/EDDI/main/k8s/quickstart.yaml

# Kustomize overlays
kubectl apply -k k8s/overlays/mongodb/     # MongoDB backend
kubectl apply -k k8s/overlays/postgres/    # PostgreSQL backend

# Helm
helm install eddi ./helm/eddi --namespace eddi --create-namespace
```

Includes overlays for auth (Keycloak), monitoring (Prometheus/Grafana), NATS messaging, Ingress, and production hardening (HPA, PDB, NetworkPolicy).
See the [Kubernetes Guide](docs/kubernetes.md) for details.

---

## 🤝 Contributing

We welcome contributions! Please read our [Contributing Guide](CONTRIBUTING.md) for details on setting up your development environment, code style, commit conventions, and the pull request process.

Every PR is automatically checked by CI (build + tests), CodeQL (security), dependency review, and AI-powered code review.

## 🔒 Security

Please report security vulnerabilities privately — see our [Security Policy](SECURITY.md).

## 📋 Compliance & Privacy

EDDI provides built-in infrastructure for regulatory compliance:

| Guide | Covers |
|---|---|
| **[GDPR / CCPA](docs/gdpr-compliance.md)** | Data erasure, export, Art. 18 restriction of processing, per-category retention, and consent guidance |
| **[HIPAA](docs/hipaa-compliance.md)** | Healthcare deployment guide — encryption, BAAs, LLM provider matrix, session management |
| **[EU AI Act](docs/eu-ai-act-compliance.md)** | AI risk classification, decision traceability, immutable audit ledger |
| **[Privacy & Data Processing](PRIVACY.md)** | Data flows, LLM provider matrix, international regulations (PIPEDA, LGPD, APPI, POPIA, PDPA) |
| **[Compliance Data Flow](docs/compliance-data-flow.md)** | Single-page data flow diagram for auditors |
| **[Incident Response](docs/incident-response.md)** | Breach response runbook (GDPR, CCPA, HIPAA timelines) |

## 📜 Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md).
