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

Latest stable version: 5.6.0

License: Apache License 2.0

Project website: [here](https://eddi.labs.ai/)

Documentation: [here](https://docs.labs.ai/)

[![Codacy Badge](https://app.codacy.com/project/badge/Grade/2c5d183d4bd24dbaa77427cfbf5d4074)](https://app.codacy.com/organizations/gh/labsai/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=labsai/EDDI&amp;utm_campaign=Badge_Grade) [![CircleCI](https://circleci.com/gh/labsai/EDDI/tree/main.svg?style=svg)](https://circleci.com/gh/labsai/EDDI/tree/main)

EDDI Dashboard:
![EDDI Screenshot Dashboard](/screenshots/EDDI-Screenshot-Dashboard-Interface.png)

EDDI Chat:
![EDDI Screenshot Chat](/screenshots/EDDI-Screenshot-Chat-Interface.png)

EDDI Manager:
![EDDI Screenshot Manager](/screenshots/EDDI-Screenshot-Manager-Interface.png)

## Overview

E.D.D.I is a high performance **middleware orchestration service** for conversational AI. Unlike standalone chatbots or LLMs, 
EDDI acts as an intelligent layer between your application and backend AI services (OpenAI, Claude, Gemini, etc.), 
providing sophisticated conversation management, configurable behavior rules, and API orchestration.

Built with Java and Quarkus, EDDI is designed for cloud-native environments (Docker, Kubernetes, OpenShift), 
offering fast startup times, low memory footprint, and horizontal scalability. It manages conversations through a unique 
**Lifecycle Pipeline** architecture, where bot behavior is defined through composable, version-controlled JSON configurations 
rather than hard-coded logic.

Key architectural features:

* **Middleware, Not a Chatbot**: EDDI orchestrates between users, business logic, APIs, and LLM services
* **Lifecycle Pipeline**: Configurable, sequential processing pipeline (Input → Parsing → Rules → API/LLM → Output)
* **Composable Bots**: Bots are assembled from reusable packages and extensions
* **Stateful Conversations**: Complete conversation history maintained in `IConversationMemory`
* **Asynchronous Processing**: Non-blocking architecture handles thousands of concurrent conversations

Notable features include:

* **Lifecycle Pipeline Architecture**: Configurable, pluggable task pipeline for processing conversations
* **LLM Orchestration**: Decide when and how to invoke LLMs through behavior rules, not just direct forwarding
* **AI Agent Tooling**: Built-in tools (calculator, web search, weather, datetime, etc.) that AI agents can invoke autonomously
* **Seamless integration with conversational or traditional REST APIs**
* **Configurable Behavior Rules**: Complex IF-THEN logic to orchestrate LLM involvement and business logic
* **Composable Bot Model**: Bots assembled from version-controlled packages and extensions (Bot → Package → Extension)
* **Multiple Bot Support**: Run multiple chatbots and versions concurrently with smooth transitions
* **Major AI API integrations** via langchain4j: OpenAI, Hugging Face (text only), Claude, Gemini, Ollama, Jlama

## Documentation

* **[Getting Started Guide](docs/getting-started.md)** - Setup and first steps
* **[Developer Quickstart](docs/developer-quickstart.md)** - Build your first bot in 5 minutes
* **[Architecture Overview](docs/architecture.md)** - Deep dive into EDDI's design and components
* **[Behavior Rules](docs/behavior-rules.md)** - Configuring bot logic
* **[LangChain Integration](docs/langchain.md)** - Connecting to LLM APIs
* **[LangChain Tools Guide](docs/bot-father-langchain-tools-guide.md)** - Built-in AI agent tools configuration
* **[Security](docs/security.md)** - SSRF protection, sandboxed evaluation, and tool hardening
* **[HTTP Calls](docs/httpcalls.md)** - External API integration
* **[Bot Father Deep Dive](docs/bot-father-deep-dive.md)** - Real-world orchestration example
* **[Complete Documentation](https://docs.labs.ai/)** - Full documentation site

## AI Agent Tools

EDDI includes built-in tools that AI agents can invoke autonomously during conversations:

| Tool | Description |
|------|-------------|
| **Calculator** | Safe expression evaluator (no code injection; recursive-descent parser) |
| **DateTime** | Get current date, time, and timezone conversions |
| **Web Search** | Search the web via DuckDuckGo or Google Custom Search |
| **Weather** | Get weather information (requires OpenWeatherMap API key) |
| **Data Formatter** | Format and convert data (JSON, CSV, XML) |
| **Web Scraper** | Extract content from web pages (SSRF-protected) |
| **Text Summarizer** | Summarize long text content |
| **PDF Reader** | Extract text from PDF URLs (SSRF-protected, http/https only) |
| **HTTP Calls** | Execute pre-configured API calls (see below) |

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

See the [LangChain Tools Guide](docs/bot-father-langchain-tools-guide.md) and [Security](docs/security.md) for details.

Technical specifications:

* Resource-/REST-oriented architecture
* Java Quarkus framework
* JAX-RS
* Dependency Injection
* Prometheus integration (Metrics endpoint)
* Kubernetes integration (Liveness/Readiness endpoint)
* MongoDB for storing bot configurations and conversation logs
* OAuth 2.0 (Keycloak) for authentication and user management
* HTML, CSS, Javascript (Dashboard)
* React (Basic Chat UI)

## Prerequisites

* Java 21
* Maven 3.8.4
* MongoDB >= 6.0

## How to run the project

1. Setup a local mongodb \(&gt; v5.0\)
2. On a terminal, under project root folder, run the following command:

```shell script
./mvnw compile quarkus:dev
```

3. Go to Browser --&gt; [http://localhost:7070](http://localhost:7070)

Note: If running locally inside an IDE you need _lombok_ to be enabled \(otherwise you will get compile errors
complaining about missing constructors\). Either download as plugin \(e.g. inside Intellij\) or follow instructions
here [https://projectlombok.org/](https://projectlombok.org/)

## Build App & Docker image

```bash
./mvnw clean package '-Dquarkus.container-image.build=true'
```

## Download from Docker hub registry

```bash
docker pull labsai/eddi
```

[https://hub.docker.com/r/labsai/eddi](https://hub.docker.com/r/labsai/eddi)

## Run Docker image

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
