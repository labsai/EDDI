---
description: >-
  Multi-Agent Orchestration Middleware for Conversational AI. Coordinates multiple AI agents, business systems, and conversation flows. Developed in Java, powered by Quarkus, provided with Docker, and orchestrated with Kubernetes or Openshift.
---

# E.D.D.I Documentation

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

[![Codacy Badge](https://app.codacy.com/project/badge/Grade/2c5d183d4bd24dbaa77427cfbf5d4074)](https://app.codacy.com/organizations/gh/labsai/dashboard?utm_source=github.com&utm_medium=referral&utm_content=labsai/EDDI&utm_campaign=Badge_Grade) [![CircleCI](https://circleci.com/gh/labsai/EDDI/tree/main.svg?style=svg)](https://circleci.com/gh/labsai/EDDI/tree/main)

![alt text](https://eddi.labs.ai/EDDI-landing-page-image.png)

## Intro

E.D.D.I is a **multi-agent orchestration middleware** for conversational AI systems. It sits between your application and multiple AI agents (LLMs, APIs, services), intelligently routing requests, coordinating responses, and maintaining conversation state.

**EDDI as Orchestration Middleware:**

- **Agent Coordination**: Manage multiple AI agents (OpenAI, Claude, Gemini, etc.) from a single interface
- **Intelligent Routing**: Direct conversations to appropriate agents based on context, rules, and intent
- **Business Integration**: Connect AI agents with your existing systems (CRMs, databases, APIs)
- **Conversation Management**: Maintain stateful, context-aware conversations across agent interactions
- **Behavior Control**: Define complex orchestration logic without coding

**Architecture:**

- **Lifecycle Pipeline**: Configurable processing pipeline (Input → Parse → Route → Agents → Aggregate → Output)
- **Composable Agents**: Agents are assembled from reusable, version-controlled configurations
- **Stateful Orchestration**: Complete conversation history maintained across agent interactions
- **Asynchronous Processing**: Non-blocking architecture handles thousands of concurrent conversations

**Key Capabilities:**

- **Multi-Agent Orchestration**: Coordinate multiple AI agents in a single conversation flow
- **Conditional Agent Invocation**: Decide which agents to call based on business rules
- **Agent Response Aggregation**: Combine outputs from multiple agents intelligently
- **Seamless API Integration**: Connect agents with traditional REST APIs
- **Pattern-Based Input Processing**: Route requests based on vocabulary and patterns
- **Dynamic Output Generation**: Template-based responses using agent outputs and business data
- **Composable Agent Model**: Agents assembled from version-controlled packages and extensions (Agent → Workflow → Extension)
- Support for multiple chatagents, including multiple versions of the same agent, running concurrently
- Support for Major AI API integrations via langchain4j: OpenAI, Hugging Face (text only), Claude, Gemini, Ollama (and more to come)

## Documentation

Start with these guides to understand EDDI:

- **[Getting Started](getting-started.md)** - Setup and installation
- **[Developer Quickstart Guide](developer-quickstart.md)** - Build your first agent in 5 minutes
- **[Architecture Overview](architecture.md)** - Deep dive into how EDDI works internally
- **[Creating Your First Chatagent](creating-your-first-chatagent/)** - Step-by-step tutorial
- **[Behavior Rules](behavior-rules.md)** - Configure agent logic and decision-making
- **[LangChain Integration](langchain.md)** - Connect to LLM APIs (OpenAI, Claude, etc.)
- **[Security](security.md)** - SSRF protection, sandboxed evaluation, and tool hardening
- **[HTTP Calls](httpcalls.md)** - Integrate with external REST APIs
- **[Agent Father Deep Dive](agent-father-deep-dive.md)** - Real-world orchestration example

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
