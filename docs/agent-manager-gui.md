# Agent Manager Dashboard

**Version: 6.0.0**

## Overview

The EDDI Manager is a modern **React 19 single-page application** for building, testing, deploying, and monitoring EDDI agents. It is served directly from the EDDI backend — no separate deployment needed.

## Access

Open your browser to the EDDI root URL:

```
http://localhost:7070
```

The Manager is the default landing page. No `apiUrl` query parameter is needed — the Manager automatically connects to the backend that serves it.

## Features

### Agent Management
- **Agent List** — Browse all agents with search, version, deployment status, and last-modified date
- **Agent Editor** — Edit agent name, description, and package references with a form-based UI
- **Version Picker** — Switch between agent versions; compare configurations across versions
- **Deploy / Undeploy** — One-click deployment with status badges
- **Duplicate** — Clone an agent and all its packages/extensions in one operation

### Pipeline Builder
- **Drag-and-Drop** — Visually compose packages by dragging workflow extensions (behavior rules, HTTP calls, LangChain, output, etc.) into a pipeline
- **Extension Editors** — Form-based editors for all 8 resource types:

  | Resource Type | Editor |
  |---|---|
  | Behavior Rules | Rule groups, conditions (input/action/context match), actions |
  | HTTP Calls | URL, method, headers, body, pre/post property instructions |
  | LangChain (LLM) | Provider, model, system prompt, tools, RAG, cascade |
  | Output | Action-based output sets with text, quick replies, and delays |
  | Property Setter | Property instructions with scope and visibility |
  | Dictionary | Words, phrases, and expression mappings |
  | RAG | Embedding provider, vector store, chunk settings, document ingestion |
  | MCP Calls | External MCP server connections |

- **JSON Editor** — Monaco-based JSON editor with syntax highlighting for any resource
- **Version History** — Every save creates a new version; switch and compare at will

### Chat Panel
- **Embedded Chat** — Test conversations with any deployed agent directly in the Manager
- **SSE Streaming** — Real-time response streaming
- **Secret Input** — Password field mode for entering API keys securely
- **Undo / Redo** — Time-travel through conversation steps

### Secrets Administration
- **Secrets Page** (`/manage/secrets`) — Manage vault entries through the UI
- **Write-Only** — Secret values can be stored but never retrieved (API returns metadata only)
- **Vault Health** — Live status badge showing vault online/offline state

### Observability
- **Logs Panel** — Live server-side log streaming via SSE with level filtering and search
- **Audit Trail** — Per-conversation timeline of pipeline execution (tasks, LLM details, tool calls, costs)

### Additional Features
- **Dark / Light Theme** — System-aware with manual toggle
- **11 Locales** — English, German, French, Spanish, Arabic, Chinese, Thai, Japanese, Korean, Portuguese, Hindi
- **RTL Support** — Full right-to-left layout for Arabic
- **Responsive Layout** — Collapsible sidebar, mobile-friendly

## Technology Stack

| Layer | Technology |
|---|---|
| **Framework** | React 19 + TypeScript 5 |
| **Build** | Vite 6 |
| **Styling** | Tailwind CSS v4 with CSS variables |
| **State (server)** | TanStack Query v5 |
| **State (UI)** | Zustand (chat/debug), `useState` elsewhere |
| **Routing** | React Router v7 |
| **i18n** | react-i18next |
| **Editor** | Monaco (@monaco-editor/react) |
| **DnD** | @dnd-kit |
| **Testing** | Vitest + React Testing Library + MSW |

## Authentication

When Keycloak is enabled (`QUARKUS_OIDC_TENANT_ENABLED=true`), the Manager uses `keycloak-js` for login:

- Automatic token refresh (every 30s before expiry)
- Role-based UI (admin sees deploy/delete, viewer sees read-only)
- Graceful degradation when auth is disabled (open access)

## Source Code

The Manager is developed in the [EDDI-Manager](https://github.com/labsai/EDDI-Manager) repository and bundled into the EDDI Docker image at build time.
