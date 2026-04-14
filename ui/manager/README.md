# EDDI Manager

> Admin dashboard for [**EDDI**](https://github.com/labsai/EDDI) — the open-source multi-agent orchestration middleware for conversational AI.

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

EDDI Manager is a modern React SPA that ships **inside** the EDDI Docker image. It provides a visual workspace for building, testing, deploying, and monitoring AI agents — no code required.

**🌐 Website:** [eddi.labs.ai](https://eddi.labs.ai/) · **📖 Docs:** [docs.labs.ai](https://docs.labs.ai/) · **🐳 Docker:** [hub.docker.com/r/labsai/eddi](https://hub.docker.com/r/labsai/eddi)

---

## What It Does

- **Agent Builder** — Create and configure agents with a drag-and-drop workflow pipeline
- **Agent Studio** — 3-panel workspace with live chat, debug drawer, and pipeline inspector
- **Group Discussions** — Orchestrate multi-agent conversations with 5 discussion styles
- **LLM Configuration** — Connect to 12 providers (OpenAI, Anthropic, Gemini, Ollama, etc.)
- **Resource Editors** — Form-based editors for rules, API calls, LLM configs, dictionaries, RAG, MCP, and more
- **Secrets Vault** — Manage encrypted API keys with vault references
- **Audit Trail** — Timeline-based compliance and debugging viewer
- **11 Languages** — English, German, French, Spanish, Portuguese, Chinese, Japanese, Korean, Arabic (RTL), Hindi, Thai

## Quick Start

The easiest way to use EDDI Manager is via the main EDDI project:

```bash
# One-command installer (includes Manager)
curl -fsSL https://raw.githubusercontent.com/labsai/EDDI/main/install.sh | bash
```

Then open [http://localhost:7070/manage](http://localhost:7070/manage).

See the [EDDI README](https://github.com/labsai/EDDI#-quick-start) for full setup instructions.

## Development

If you want to develop the Manager UI itself:

```bash
# Prerequisites: Node.js ≥ 20, EDDI backend on localhost:7070
npm install
npm run dev          # Vite dev server on http://localhost:5173
```

The Vite dev proxy forwards API calls to the EDDI backend. If no backend is available, the Manager auto-starts in **standalone mode** with mock data (via [MSW](https://mswjs.io/)).

```bash
npm run test         # 540+ Vitest unit/component tests
npm run build        # Production build
```

## Tech Stack

| Layer      | Technology                                      |
| ---------- | ----------------------------------------------- |
| Build      | Vite 6                                          |
| UI         | React 19 + TypeScript 5 (strict)                |
| Styling    | Tailwind CSS v4                                 |
| State      | TanStack Query v5 + Zustand                     |
| Routing    | React Router v7                                 |
| Editor     | Monaco                                          |
| DnD        | @dnd-kit                                        |
| Auth       | Keycloak 26 (optional)                          |
| Tests      | Vitest + React Testing Library + MSW + Playwright |

## Related

- [**EDDI**](https://github.com/labsai/EDDI) — Backend engine (Java 25, Quarkus)
- [**eddi-chat-ui**](https://github.com/labsai/eddi-chat-ui) — Embeddable chat widget
- [**quarkus-eddi**](https://github.com/quarkiverse/quarkus-eddi) — Quarkus SDK

## License

[Apache 2.0](LICENSE)
