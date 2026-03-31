---
description: >-
  Multi-Agent Orchestration Middleware for Conversational AI. Coordinates multiple AI agents, business systems, and conversation flows. Developed in Java 25, powered by Quarkus, provided with Docker, and orchestrated with Kubernetes or OpenShift.
---

# E.D.D.I Documentation

E.D.D.I (Enhanced Dialog Driven Interface) is a **multi-agent orchestration middleware** that coordinates between users, AI agents (LLMs), and business systems. It provides intelligent routing, conversation management, and API orchestration for building sophisticated AI-powered applications — all through configuration, not code.

**Latest version: 6.0.0-RC1** · License: Apache 2.0 · [Website](https://eddi.labs.ai/)

[![CI](https://github.com/labsai/EDDI/actions/workflows/ci.yml/badge.svg)](https://github.com/labsai/EDDI/actions/workflows/ci.yml) [![Codacy Badge](https://app.codacy.com/project/badge/Grade/2c5d183d4bd24dbaa77427cfbf5d4074)](https://app.codacy.com/organizations/gh/labsai/dashboard?utm_source=github.com&utm_medium=referral&utm_content=labsai/EDDI&utm_campaign=Badge_Grade) [![Docker Pulls](https://img.shields.io/docker/pulls/labsai/eddi)](https://hub.docker.com/r/labsai/eddi)

## What EDDI Does

- **Orchestrates Multiple AI Agents** — Route conversations to different LLMs based on context, rules, and intent
- **Coordinates Business Logic** — Integrate AI agents with your APIs, databases, and services via HTTP Calls
- **Manages Conversations** — Maintain stateful, context-aware conversations with persistent user memory
- **Controls Agent Behavior** — Define when and how agents are invoked through configurable behavior rules

Developed in Java 25 using Quarkus. Ships as a **Red Hat-certified Docker image**. Deploys on Docker, Kubernetes, or OpenShift. Supports **MongoDB or PostgreSQL** as the storage backend.

## Key Capabilities

### Multi-Agent Orchestration
- **12 LLM Providers** — OpenAI, Anthropic Claude, Google Gemini, Mistral AI, Azure OpenAI, Amazon Bedrock, Oracle GenAI, Google Vertex AI, Ollama, Jlama, Hugging Face, and OpenAI-compatible endpoints (DeepSeek, Cohere, etc.)
- **Group Conversations** — Multi-agent debates with 6 styles (Round Table, Peer Review, Devil's Advocate, Delphi, Debate, Custom)
- **Managed Conversations** — Intent-based auto-routing with one conversation per user per intent
- **Agent Father** — Meta-agent that creates other agents through conversation

### Protocol & Interoperability
- **MCP Server** (48+ tools) — Full EDDI control from Claude Desktop, IDE plugins, or any MCP client
- **MCP Client** — Connect agents to external MCP tool servers
- **A2A Protocol** — Agent-to-Agent peer communication with skill discovery and Agent Cards
- **OpenAPI-to-Agent** — Paste an OpenAPI spec, get a fully deployed API-calling agent

### Intelligence & Memory
- **RAG** — 7 embedding providers, 5 vector stores, plus zero-infrastructure httpCall RAG
- **Persistent User Memory** — Agents remember facts across conversations with visibility scoping
- **Model Cascading** — Cost-optimized multi-model routing with confidence-based escalation
- **Token-Aware Windowing** — Intelligent conversation context management

### Enterprise Security
- **Secrets Vault** — Envelope encryption (AES-256-GCM + PBKDF2) for API keys
- **SSRF Protection** — URL validation blocks private/internal addresses on all tools
- **Sandboxed Evaluation** — No `eval()`, no script engines — safe recursive-descent math parser
- **OAuth 2.0 / Keycloak** — Authentication, authorization, and role-based access
- **Audit Ledger** — Write-once trail with HMAC integrity for EU AI Act compliance

### Cloud-Native
- **One-Command Install** — Interactive wizard via Docker Compose
- **Kubernetes / OpenShift** — Kustomize overlays, Helm charts, HPA, PDB, NetworkPolicy
- **Prometheus & Grafana** — Built-in Micrometer instrumentation with pre-built 45-panel dashboard
- **DB-Agnostic** — Choose MongoDB or PostgreSQL, switch with one env var
- **NATS JetStream** — Async event bus for distributed multi-replica processing

## Quick Start

```bash
docker pull labsai/eddi:latest
docker compose up
```

Then open [http://localhost:7070](http://localhost:7070).

See [Getting Started](getting-started.md) for full setup options including the one-command installer.

## Documentation

Start with these guides:

- **[Getting Started](getting-started.md)** — Setup and installation
- **[Developer Quickstart Guide](developer-quickstart.md)** — Build your first agent in 5 minutes
- **[Architecture Overview](architecture.md)** — Deep dive into how EDDI works internally
- **[LangChain Integration](langchain.md)** — Connect to 12 LLM providers
- **[Behavior Rules](behavior-rules.md)** — Configure agent logic and decision-making
- **[HTTP Calls](httpcalls.md)** — Integrate with external REST APIs
- **[Security](security.md)** — SSRF protection, sandboxed evaluation, Keycloak auth
- **[MCP Server](mcp-server.md)** — 48+ tools for AI-assisted management
- **[RAG](rag.md)** — Knowledge base retrieval setup
- **[Agent Father Deep Dive](agent-father-deep-dive.md)** — Real-world orchestration example

## Technical Stack

- Java 25, Quarkus framework (JAX-RS, CDI)
- MongoDB or PostgreSQL (DB-agnostic storage layer)
- Prometheus / Micrometer (metrics at `/q/metrics`)
- Kubernetes (liveness/readiness at `/q/health/live`, `/q/health/ready`)
- OAuth 2.0 via Keycloak (optional)
- React 19 Manager Dashboard (served from backend)
- React Chat Widget with SSE streaming
