---
description: >-
  Multi-Agent Orchestration Middleware for Conversational AI — coordinate
  multiple AI agents, business systems, and conversation flows through
  configuration, not code.
---

# E.D.D.I Documentation

Welcome to the official documentation for **E.D.D.I** (Enhanced Dialog Driven Interface) — open-source multi-agent orchestration middleware for conversational AI.

[![Version](https://img.shields.io/github/v/release/labsai/EDDI?label=version&color=blue)](https://github.com/labsai/EDDI/releases) · License: Apache 2.0 · [GitHub](https://github.com/labsai/EDDI) · [Website](https://eddi.labs.ai/)

---

## What Is EDDI?

EDDI coordinates between users, AI agents (LLMs), and business systems. It provides intelligent routing, conversation management, and API orchestration — all through **versioned JSON configurations**, not code.

Built with **Java 25** and **Quarkus**. Ships as a **Red Hat-certified Docker image**. Supports **MongoDB or PostgreSQL**. Deploy on Docker, Kubernetes, or OpenShift.

---

## Start Here

| Guide                                                     | Description                                                   |
| --------------------------------------------------------- | ------------------------------------------------------------- |
| **[Getting Started](getting-started.md)**                 | Install EDDI and create your first agent                      |
| **[Developer Quickstart](developer-quickstart.md)**       | Build a complete agent step by step through the REST API      |
| **[Manager Dashboard](agent-manager-gui.md)**             | A tour of the admin UI, including the Platform Operator       |
| **[Architecture Overview](architecture.md)**              | The lifecycle pipeline and the configuration model            |
| **[Putting It All Together](putting-it-all-together.md)** | A worked example: a hotel booking agent                       |

---

## Key Capabilities

### Multi-Agent Orchestration

- **19 LLM Providers** — OpenAI, Anthropic, Google Gemini, Mistral AI, Azure OpenAI, Amazon Bedrock, Oracle GenAI, Vertex AI, Ollama, Jlama, Hugging Face, xAI, DeepSeek, Kimi, Qwen, GLM, MiniMax, OpenRouter, Groq, plus any OpenAI-compatible endpoint
- **[Group Conversations](group-conversations.md)** — Multi-agent debates, voting, shared artifacts, and standing teams across 7 discussion styles (Round Table, Peer Review, Devil's Advocate, Delphi, Debate, Task Force, Negotiation)
- **[Managed Agents](managed-agents.md)** — Intent-based auto-routing with one conversation per user per intent
- **[Model Cascading](model-cascade.md)** — Cost-optimized multi-model routing with confidence-based escalation
- **[Platform Operator](agent-manager-gui.md)** — An opt-in agent that inspects and operates the deployment through an allow-list of EDDI's own REST endpoints, including creating other agents; every write it makes pauses for human approval. An admin activates it at `/manage/operator`; the form-based wizard at `/manage/agents/wizard` is the alternative for creating agents

### Protocols & Interoperability

- **[MCP Server](mcp-server.md)** — 80+ tools for managing agents and holding conversations from Claude Desktop, IDE plugins, or any MCP client
- **[MCP Client](mcp-client.md)** — Point an agent at somebody else's MCP server, including stdio servers via a bridge sidecar
- **[A2A Protocol](a2a-protocol.md)** — Agent-to-Agent peer communication with skill discovery
- **[Connections](connections.md)** — One credential model for every outbound call: static keys, HTTP Basic, OAuth service accounts, and per-end-user OAuth
- **[OpenAI-Compatible API](open-webui-integration.md)** — Deployed agents presented as OpenAI models for Open WebUI and OpenAI SDK clients
- **SSE Streaming** — Token-by-token responses, including most tool-enabled turns (a single-chunk fallback applies to cascade agents, providers without a streaming builder, and a few other configurations), plus a live `tool_call` event so clients can show "Using {tool}…" while the turn is still running

### LLMs, Knowledge & Memory

- **[LLM Integration](langchain.md)** — Connect any of 19 providers with agent mode and tool calling
- **[RAG](rag.md)** — 8 embedding providers, 6 vector stores, plus zero-infrastructure httpCall RAG
- **[Persistent User Memory](user-memory.md)** — Agents remember facts across conversations
- **[Properties](properties.md)** — Config-driven slot-filling and importance extraction

### Security & Compliance

- **[Secrets Vault](secrets-vault.md)** — Envelope encryption (AES-256-GCM + PBKDF2) for API keys
- **[Security](security.md)** — SSRF-safe outbound HTTP, a sandboxed math parser instead of script evaluation, Keycloak (OIDC) authentication
- **[Audit Ledger](audit-ledger.md)** — Append-only record of every pipeline task, signed with HMAC-SHA256 for tamper detection; supports the record-keeping described in [EU AI Act Compliance](eu-ai-act-compliance.md)
- **[Human-in-the-Loop](hitl.md)** — Turn-level and per-tool-call approval gates with timeout policies, plus Slack and MCP approval surfaces

---

## Agent Configuration

Build agent behavior by composing these extensions:

| Extension             | Purpose                                              | Guide                                     |
| --------------------- | ---------------------------------------------------- | ----------------------------------------- |
| **Behavior Rules**    | Decision-making logic — IF conditions THEN actions   | [→ Guide](behavior-rules.md)              |
| **HTTP Calls**        | Call external REST APIs with templated requests      | [→ Guide](httpcalls.md)                   |
| **LLM Integration**   | Chat, agent mode, tool calling with any provider     | [→ Guide](langchain.md)                   |
| **Output**            | Define what the agent says, with alternatives        | [→ Guide](output-configuration.md)        |
| **Output Templating** | Dynamic responses using Qute templates               | [→ Guide](output-templating.md)           |
| **Properties**        | Extract and store structured data from conversations | [→ Guide](properties.md)                  |
| **Semantic Parser**   | Map user input to expressions via dictionaries       | [→ Guide](semantic-parser.md)             |
| **Context**           | Inject external data from your application           | [→ Guide](passing-context-information.md) |

---

## Deployment & Operations

| Topic                   | Guide                                              |
| ----------------------- | -------------------------------------------------- |
| Configuration Reference | [→ Guide](configuration-reference.md)              |
| Docker                  | [→ Guide](docker.md)                               |
| Kubernetes & Helm       | [→ Guide](kubernetes.md)                           |
| Red Hat & OpenShift     | [→ Guide](redhat-openshift.md)                     |
| AWS + MongoDB Atlas     | [→ Guide](setup-eddi-on-aws-with-mongodb-atlas.md) |
| Metrics & Monitoring    | [→ Guide](metrics.md)                              |
| Log Administration      | [→ Guide](log-administration.md)                   |
| Release & Versioning    | [→ Guide](release-versioning.md)                   |

---

## Quick Start

All options need [Docker](https://docs.docker.com/get-docker/).

**Recommended: the installer.** An interactive wizard that sets up EDDI and a database with Docker Compose, generates a vault encryption key, and installs an `eddi` command for updates.

```bash
# Linux / macOS / WSL2
curl -fsSL https://raw.githubusercontent.com/labsai/EDDI/main/install.sh | bash
```

```powershell
# Windows (PowerShell)
Invoke-WebRequest -UseBasicParsing -Uri "https://raw.githubusercontent.com/labsai/EDDI/main/install.ps1" -OutFile "install.ps1"
Unblock-File .\install.ps1
.\install.ps1
```

**Without the installer:** download the Compose file into an empty directory and start it. It runs EDDI and MongoDB with authentication off, bound to `127.0.0.1` so they are reachable from this machine only.

```bash
curl -fsSLO https://raw.githubusercontent.com/labsai/EDDI/main/docker-compose.yml
docker compose up -d
```

Then open [http://localhost:7070](http://localhost:7070). A first visit lands on a chooser between the **Manager** (`/manage`) and the **Workforce** workspace (`/workforce`); the Chat UI is at `/chat`. A fresh install has no agents: create one with the Platform Operator or the agent wizard in the Manager.

See **[Getting Started](getting-started.md)** for every setup option, including PostgreSQL, Keycloak authentication and Kubernetes.

---

## Browse All Documentation

See the full **[Table of Contents](SUMMARY.md)** for the complete documentation index.

**Have a question?** Check the **[FAQs](how-to....md)** for common setup and configuration answers.
