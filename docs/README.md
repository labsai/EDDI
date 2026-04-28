---
description: >-
  Multi-Agent Orchestration Middleware for Conversational AI — coordinate
  multiple AI agents, business systems, and conversation flows through
  configuration, not code.
---

# E.D.D.I Documentation

Welcome to the official documentation for **E.D.D.I** (Enhanced Dialog Driven Interface) — a production-grade multi-agent orchestration middleware for conversational AI.

**Latest version: 6.0.0** · License: Apache 2.0 · [GitHub](https://github.com/labsai/EDDI) · [Website](https://eddi.labs.ai/)

---

## What Is EDDI?

EDDI coordinates between users, AI agents (LLMs), and business systems. It provides intelligent routing, conversation management, and API orchestration — all through **versioned JSON configurations**, not code.

Built with **Java 25** and **Quarkus**. Ships as a **Red Hat-certified Docker image**. Supports **MongoDB or PostgreSQL**. Deploy on Docker, Kubernetes, or OpenShift.

---

## Start Here

| Guide                                                        | Time   | Description                                        |
| ------------------------------------------------------------ | ------ | -------------------------------------------------- |
| 🚀 **[Getting Started](getting-started.md)**                 | 5 min  | Install EDDI and run your first agent              |
| ⚡ **[Developer Quickstart](developer-quickstart.md)**       | 10 min | Build a complete agent step-by-step via REST API   |
| 🏗️ **[Architecture Overview](architecture.md)**              | 15 min | Understand the lifecycle pipeline and config model |
| 🧩 **[Putting It All Together](putting-it-all-together.md)** | 20 min | Real-world hotel booking agent walkthrough         |

---

## Key Capabilities

### 🤖 Multi-Agent Orchestration

- **12 LLM Providers** — OpenAI, Anthropic, Google Gemini, Mistral AI, Azure OpenAI, Amazon Bedrock, Oracle GenAI, Vertex AI, Ollama, Jlama, Hugging Face, plus OpenAI-compatible endpoints
- **[Group Conversations](group-conversations.md)** — Multi-agent debates (Round Table, Peer Review, Devil's Advocate, Delphi, Debate)
- **[Managed Agents](managed-agents.md)** — Intent-based auto-routing with one conversation per user per intent
- **[Model Cascading](model-cascade.md)** — Cost-optimized multi-model routing with confidence-based escalation

### 🔗 Protocols & Interoperability

- **[MCP Server](mcp-server.md)** (48+ tools) — Full EDDI control from Claude Desktop, IDE plugins, or any MCP client
- **[A2A Protocol](a2a-protocol.md)** — Agent-to-Agent peer communication with skill discovery

### 🧠 Intelligence & Memory

- **[LLM Integration](langchain.md)** — Connect any of 12 providers with agent mode and tool calling
- **[RAG](rag.md)** — 8 embedding providers, 6 vector stores, plus zero-infrastructure httpCall RAG
- **[Persistent User Memory](user-memory.md)** — Agents remember facts across conversations
- **[Properties](properties.md)** — Config-driven slot-filling and importance extraction

### 🔐 Enterprise Security

- **[Secrets Vault](secrets-vault.md)** — Envelope encryption (AES-256-GCM + PBKDF2) for API keys
- **[Security](security.md)** — SSRF protection, sandboxed evaluation, Keycloak auth
- **[Audit Ledger](audit-ledger.md)** — Write-once trail with HMAC integrity for EU AI Act compliance

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
| 🐳 Docker               | [→ Guide](docker.md)                               |
| ☸️ Kubernetes & Helm    | [→ Guide](kubernetes.md)                           |
| 🔴 Red Hat & OpenShift  | [→ Guide](redhat-openshift.md)                     |
| ☁️ AWS + MongoDB Atlas  | [→ Guide](setup-eddi-on-aws-with-mongodb-atlas.md) |
| 📊 Metrics & Monitoring | [→ Guide](metrics.md)                              |
| 📋 Log Administration   | [→ Guide](log-administration.md)                   |
| 🔖 Release & Versioning | [→ Guide](release-versioning.md)                   |

---

## Quick Start

```bash
# One-command install (interactive wizard)
curl -fsSL https://raw.githubusercontent.com/labsai/EDDI/main/install.sh | bash

# Or pull and run directly
docker pull labsai/eddi:latest
docker compose up
```

Then open [http://localhost:7070](http://localhost:7070) to access the Manager Dashboard.

See **[Getting Started](getting-started.md)** for all setup options.

---

## Browse All Documentation

See the full **[Table of Contents](SUMMARY.md)** for the complete documentation index.

**Have a question?** Check the **[FAQs](how-to....md)** for common setup and configuration answers.
