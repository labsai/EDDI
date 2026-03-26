![EDDI Banner Image](/screenshots/EDDI-landing-page-image.png)

# E.D.D.I: Multi-Agent Orchestration Middleware for Conversational AI

E.D.D.I (Enhanced Dialog Driven Interface) is a **multi-agent orchestration middleware** that coordinates between users, AI agents (LLMs), and business systems. It provides intelligent routing, conversation management, and API orchestration for building sophisticated AI-powered applications.

**What EDDI Does:**

- **Orchestrates Multiple AI Agents**: Route conversations to different LLMs (OpenAI, Claude, Gemini, Ollama) based on context and rules
- **Coordinates Business Logic**: Integrate AI agents with your APIs, databases, and services
- **Manages Conversations**: Maintain stateful, context-aware conversations across multiple agents
- **Controls Agent Behavior**: Define when and how agents are invoked through configurable rules

Developed in Java using Quarkus, it is lean, RESTful, scalable, and cloud-native.
It comes as Docker container and can be orchestrated with Kubernetes or Openshift.
The Docker image has been certified by IBM/Red Hat.

Latest version: 6.0.0

License: Apache License 2.0

Project website: [here](https://eddi.labs.ai/)

Documentation: [here](https://docs.labs.ai/)

[![CI](https://github.com/labsai/EDDI/actions/workflows/ci.yml/badge.svg)](https://github.com/labsai/EDDI/actions/workflows/ci.yml) [![CodeQL](https://github.com/labsai/EDDI/actions/workflows/codeql.yml/badge.svg)](https://github.com/labsai/EDDI/actions/workflows/codeql.yml) [![Codacy Badge](https://app.codacy.com/project/badge/Grade/2c5d183d4bd24dbaa77427cfbf5d4074)](https://app.codacy.com/organizations/gh/labsai/dashboard?utm_source=github.com&utm_medium=referral&utm_content=labsai/EDDI&utm_campaign=Badge_Grade)

## Quarkus SDK

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

## Quick Start

**Linux / macOS / WSL2:**

```bash
curl -fsSL https://raw.githubusercontent.com/labsai/EDDI/main/install.sh | bash
```

**Windows (PowerShell):**

```powershell
iwr -useb https://raw.githubusercontent.com/labsai/EDDI/main/install.ps1 | iex
```

This starts an interactive wizard that sets up EDDI + database via Docker, deploys the Agent Father starter agent, and guides you through creating your first AI agent. Requires Docker ([install guide](https://docs.docker.com/get-docker/)).

**Options:**

```bash
bash install.sh --defaults                 # All defaults, no prompts
bash install.sh --db=postgres --with-auth  # PostgreSQL + Keycloak
bash install.sh --full                     # Everything enabled
bash install.sh --local                    # Build from local source (see below)
```

### Local Build (for contributors / pre-release)

If you're working on EDDI and want to test with a local build:

```bash
# 1. Build the Java app
./mvnw package -DskipTests

# 2. Run the install script with --local
bash install.sh --local           # Linux / macOS / WSL2
.\install.ps1 -Local              # Windows PowerShell
```

The `--local` flag includes `docker-compose.local.yml` as a compose overlay, which builds the Docker image from your local `target/quarkus-app/` output instead of pulling from Docker Hub. All wizard options (database, auth, monitoring) work normally with `--local`.

## Overview

E.D.D.I is a high performance **middleware orchestration service** for conversational AI. Unlike standalone agents or LLMs,
EDDI acts as an intelligent layer between your application and backend AI services (OpenAI, Claude, Gemini, etc.),
providing sophisticated conversation management, configurable behavior rules, and API orchestration.

Built with Java and Quarkus, EDDI is designed for cloud-native environments (Docker, Kubernetes, OpenShift),
offering fast startup times, low memory footprint, and horizontal scalability. It manages conversations through a unique
**Lifecycle Pipeline** architecture, where agent behavior is defined through composable, version-controlled JSON configurations
rather than hard-coded logic.

Key architectural features:

- **Middleware, Not a Chatbot**: EDDI orchestrates between users, business logic, APIs, and LLM services
- **Lifecycle Pipeline**: Configurable, sequential processing pipeline (Input → Parsing → Rules → API/LLM → Output)
- **Composable Agents**: Agents are assembled from reusable packages and extensions
- **Stateful Conversations**: Complete conversation history maintained in `IConversationMemory`
- **Asynchronous Processing**: Non-blocking architecture handles thousands of concurrent conversations

Notable features include:

- **Lifecycle Pipeline Architecture**: Configurable, pluggable task pipeline for processing conversations
- **LLM Orchestration**: Decide when and how to invoke LLMs through behavior rules, not just direct forwarding
- **AI Agent Tooling**: Built-in tools (calculator, web search, weather, datetime, etc.) that AI agents can invoke autonomously
- **Seamless integration with conversational or traditional REST APIs**
- **Configurable Behavior Rules**: Complex IF-THEN logic to orchestrate LLM involvement and business logic
- **Composable Agent Model**: Agents assembled from version-controlled packages and extensions (Agent → Workflow → Extension)
- **Multiple Agent Support**: Run multiple agents and versions concurrently with smooth transitions
- **Major AI API integrations** via langchain4j: OpenAI, Hugging Face (text only), Claude, Gemini, Ollama, Jlama

## Documentation

- **[Getting Started Guide](docs/getting-started.md)** - Setup and first steps
- **[Developer Quickstart](docs/developer-quickstart.md)** - Build your first agent in 5 minutes
- **[Architecture Overview](docs/architecture.md)** - Deep dive into EDDI's design and components
- **[Behavior Rules](docs/behavior-rules.md)** - Configuring agent logic
- **[LangChain Integration](docs/langchain.md)** - Connecting to LLM APIs
- **[LangChain Tools Guide](docs/agent-father-langchain-tools-guide.md)** - Built-in AI agent tools configuration
- **[Security](docs/security.md)** - SSRF protection, sandboxed evaluation, and tool hardening
- **[HTTP Calls](docs/httpcalls.md)** - External API integration
- **[Agent Father Deep Dive](docs/agent-father-deep-dive.md)** - Real-world orchestration example
- **[Complete Documentation](https://docs.labs.ai/)** - Full documentation site

## AI Agent Tools

EDDI includes built-in tools that AI agents can invoke autonomously during conversations:

| Tool                | Description                                                             |
| ------------------- | ----------------------------------------------------------------------- |
| **Calculator**      | Safe expression evaluator (no code injection; recursive-descent parser) |
| **DateTime**        | Get current date, time, and timezone conversions                        |
| **Web Search**      | Search the web via DuckDuckGo or Google Custom Search                   |
| **Weather**         | Get weather information (requires OpenWeatherMap API key)               |
| **Data Formatter**  | Format and convert data (JSON, CSV, XML)                                |
| **Web Scraper**     | Extract content from web pages (SSRF-protected)                         |
| **Text Summarizer** | Summarize long text content                                             |
| **PDF Reader**      | Extract text from PDF URLs (SSRF-protected, http/https only)            |
| **HTTP Calls**      | Execute pre-configured API calls (see below)                            |

### HTTP Calls as Secure Tools

In addition to built-in tools, EDDI allows you to **expose your own HTTP call configurations as tools** for AI agents. This provides a crucial **security layer**: instead of allowing agents to make arbitrary HTTP requests, they can only invoke APIs that have been explicitly configured and whitelisted in your httpcalls configuration.

**Benefits:**

- **Security**: Agents are sandboxed to pre-approved API endpoints only
- **Control**: Define exactly which parameters the agent can pass
- **Auditability**: All tool invocations go through EDDI's logging and tracking
- **Templating**: Use EDDI's templating engine to safely construct requests

See [HTTP Calls](docs/httpcalls.md) for configuration details.

### Tool Execution Pipeline

All tool invocations (both built-in and HTTP call tools) are routed through a unified **Tool Execution Service** that provides:

- **Rate Limiting** — Configurable per-tool token-bucket rate limits to prevent API abuse
- **Smart Caching** — Deduplicates identical calls with configurable TTL
- **Cost Tracking** — Per-conversation budget enforcement with automatic eviction of stale data
- **SSRF Protection** — URL validation blocks private/internal addresses for web-facing tools
- **Secure Evaluation** — Calculator uses a sandboxed math parser (no script engine / no code injection)

These controls are driven by your `langchain.json` configuration:

```json
{
  "enableRateLimiting": true,
  "defaultRateLimit": 100,
  "toolRateLimits": { "websearch": 30 },
  "enableToolCaching": true,
  "enableCostTracking": true,
  "maxBudgetPerConversation": 5.0
}
```

See the [LangChain Tools Guide](docs/agent-father-langchain-tools-guide.md) and [Security](docs/security.md) for details.

Technical specifications:

- Resource-/REST-oriented architecture
- Java Quarkus framework
- JAX-RS
- Dependency Injection
- Prometheus integration (Metrics endpoint)
- Kubernetes integration (Liveness/Readiness endpoint)
- MongoDB for storing agent configurations and conversation logs
- OAuth 2.0 (Keycloak) for authentication and user management
- HTML, CSS, Javascript (Dashboard)
- React (Basic Chat UI)

## Prerequisites

- Java 25 ([Eclipse Temurin](https://adoptium.net/) recommended)
- Maven 3.9+ (bundled via `mvnw` wrapper)
- MongoDB >= 6.0

## How to run the project

1. Setup a local mongodb \(&gt; v6.0\)
2. On a terminal, under project root folder, run the following command:

```shell script
./mvnw compile quarkus:dev
```

3. Go to Browser --&gt; [http://localhost:7070](http://localhost:7070)

## Build App & Docker image

```bash
./mvnw clean package '-Dquarkus.container-image.build=true'
```

Or build without the container image (for use with `install.sh --local`):

```bash
./mvnw package -DskipTests
```

## Download from Docker hub registry

```bash
docker pull labsai/eddi
```

[https://hub.docker.com/r/labsai/eddi](https://hub.docker.com/r/labsai/eddi)

## Run Docker image

The simplest way is using the install script (see [Quick Start](#quick-start)). For manual control:

```bash
# Production (pulls from Docker Hub)
docker-compose up

# Local build (from source)
docker-compose -f docker-compose.yml -f docker-compose.local.yml up

# With auth + monitoring
docker-compose -f docker-compose.yml -f docker-compose.auth.yml -f docker-compose.monitoring.yml up
```

For integration testing run

```bash
./integration-tests.sh
```

or

```bash
docker-compose -f docker-compose.yml -f docker-compose.local.yml -f docker-compose.testing.yml -p ci up -d
```

## prometheus/metrics integration

```bash
<eddi-instance>/q/metrics
```

## kubernetes integration

Liveness endpoint:

```bash
<eddi-instance>/q/health/live
```

Readiness endpoint:

```bash
<eddi-instance>/q/health/ready
```

## Contributing

We welcome contributions! Please read our [Contributing Guide](CONTRIBUTING.md) for details on:

- Setting up your development environment
- Code style and commit conventions
- The pull request process

Every PR is automatically checked by CI (build + tests), CodeQL (security), dependency review, and AI-powered code review.

## Security

Please report security vulnerabilities privately — see our [Security Policy](SECURITY.md) for details.

## Code of Conduct

This project follows the [Contributor Covenant Code of Conduct](CODE_OF_CONDUCT.md).
