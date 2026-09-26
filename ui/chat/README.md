# EDDI Chat UI

> **This directory is part of [labsai/EDDI](https://github.com/labsai/EDDI).** It was the separate `labsai/EDDI-Chat-UI` repository until 2026-09-15; its full history was imported here (`git log -- ui/chat`). Issues and pull requests go to `labsai/EDDI`. The UI is built into the EDDI jar by Maven from the repository root — see the root `AGENTS.md` (Build & Test Commands).

> Embeddable chat widget for [**EDDI**](https://github.com/labsai/EDDI) — the open-source multi-agent orchestration middleware for conversational AI.

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://github.com/labsai/EDDI/blob/main/LICENSE) ![Tests](https://img.shields.io/badge/tests-46-brightgreen)

EDDI Chat UI is a standalone, themeable React chat widget that connects to any EDDI agent. It ships **inside** the EDDI Docker image and can also be embedded as an `<iframe>` in any web page. Supports SSE streaming, rich Markdown, LaTeX math, code highlighting, quick replies, and full conversation control — all configurable via URL parameters or a typed config object.

**🌐 Website:** [eddi.labs.ai](https://eddi.labs.ai/) · **📖 Docs:** [docs.labs.ai](https://docs.labs.ai/) · **🐳 Docker:** [hub.docker.com/r/labsai/eddi](https://hub.docker.com/r/labsai/eddi)

---

## 📑 Table of Contents

- [✨ Features](#-features)
- [🏁 Quick Start](#-quick-start)
- [🔗 URL Patterns](#-url-patterns)
- [⚙️ Configuration](#️-configuration)
- [📦 Embedding](#-embedding)
- [🏗️ Development](#️-development)
- [🧰 Tech Stack](#-tech-stack)
- [📁 Project Structure](#-project-structure)
- [🔌 Backend Integration](#-backend-integration)
- [🔗 Related](#-related)
- [📜 License](#-license)

---

## ✨ Features

- 💬 **Rich Markdown** — Tables, code blocks, bold/italic, links, lists, and raw HTML
- 🌊 **SSE Streaming** — Real-time token-by-token agent responses with thinking indicator
- 🧮 **LaTeX Math** — KaTeX rendering for mathematical expressions (`$inline$` and `$$block$$`)
- 🎨 **Syntax Highlighting** — Code blocks with language-aware highlighting via `rehype-highlight`
- 🌗 **Dark / Light Themes** — Toggle via UI button, URL parameter, or system preference
- ⚡ **Quick Replies** — Pill buttons for suggested responses returned by the agent
- ↩️ **Undo / Redo** — Step through conversation history with backend state sync
- 🔒 **Password Fields** — Masked input support when agents request sensitive data
- 🔧 **Fully Configurable** — Every feature togglable via URL query parameters or typed `ChatConfig`
- 📱 **Responsive** — Mobile-first design with adaptive breakpoints
- 🎭 **Demo Mode** — Full showcase without a running backend (`/chat/demo/showcase`)
- 🏷️ **Agent Name Display** — Auto-fetches and shows the agent's display name from the backend

---

## 🏁 Quick Start

The easiest way to use EDDI Chat UI is via the main EDDI project:

```bash
# One-command installer (includes Chat UI)
curl -fsSL https://raw.githubusercontent.com/labsai/EDDI/main/install.sh | bash
```

Then open [http://localhost:7070/chat.html](http://localhost:7070/chat.html).

See the [EDDI README](https://github.com/labsai/EDDI#-quick-start) for full setup instructions.

### Standalone Development

```bash
# Prerequisites: Node.js ≥ 22.12 (what Vitest 5 requires; the build pins 22.23.2), EDDI backend on localhost:7070
npm install
npm run dev        # Vite dev server on http://localhost:5174
```

The Vite dev proxy forwards API calls to the EDDI backend. If no backend is available, navigate to `/chat/demo/showcase` for the built-in demo mode.

---

## 🔗 URL Patterns

| URL                                   | Description                               |
| ------------------------------------- | ----------------------------------------- |
| `/chat/:environment/:agentId`         | Connect to a specific agent               |
| `/chat/:environment/:agentId/:userId` | Connect with explicit user ID             |
| `/chat/demo/showcase`                 | Demo mode with mock data (no backend)     |
| `/chat/managed/:intent/:userId`       | Managed agent mode (intent-based routing) |

---

## ⚙️ Configuration

All features can be toggled via **URL query parameters** — ideal for iframe embedding:

| Parameter             | Example                     | Effect                             |
| --------------------- | --------------------------- | ---------------------------------- |
| `theme`               | `?theme=light`              | Set initial theme (`dark`/`light`) |
| `title`               | `?title=My%20Agent`         | Override header title              |
| `hideUndo`            | `?hideUndo=true`            | Hide undo button                   |
| `hideRedo`            | `?hideRedo=true`            | Hide redo button                   |
| `hideNewConversation` | `?hideNewConversation=true` | Hide restart button                |
| `hideLogo`            | `?hideLogo=true`            | Show text title instead of logo    |
| `hideQuickReplies`    | `?hideQuickReplies=true`    | Hide quick reply buttons           |

<details>
<summary><strong>Programmatic configuration (ChatConfig)</strong></summary>

When integrating directly (not via iframe), you can pass a typed `ChatConfig` object:

```typescript
interface ChatConfig {
  apiBaseUrl?: string;          // Default: window.location.origin
  theme?: "dark" | "light" | "system";
  accentColor?: string;        // CSS value, default: "#113B92"
  showLogo?: boolean;          // Default: true
  logoUrl?: string;            // Default: /img/logo_eddi.png
  title?: string;              // Default: "EDDI"
  placeholder?: string;        // Default: "Type a message..."
  enableStreaming?: boolean;    // Default: true
  enableQuickReplies?: boolean; // Default: true
  enableMarkdown?: boolean;    // Default: true
  enableMath?: boolean;        // Default: true
  enableCodeHighlight?: boolean;// Default: true
  enableUndo?: boolean;        // Default: true
  enableRedo?: boolean;        // Default: true
  enableNewConversation?: boolean; // Default: true
  showAgentName?: boolean;     // Default: true
}
```

</details>

---

## 📦 Embedding

The chat UI can be embedded in any HTML page via iframe:

```html
<iframe
  src="https://your-eddi-server/chat/production/your-agent-id?hideNewConversation=true&theme=dark"
  style="width: 400px; height: 600px; border: none; border-radius: 12px;"
></iframe>
```

Combine query parameters to create a minimal, focused chat experience:

```
?hideUndo=true&hideRedo=true&hideNewConversation=true&hideLogo=true&title=Support%20Agent&theme=light
```

---

## 🏗️ Development

```bash
npm run dev          # Dev server (port 5174) with proxy to EDDI backend
npm run build        # Production build
npm run test         # Run tests (46 Vitest unit/component tests)
npm run typecheck    # TypeScript type checking (tsc -b --noEmit)
```

### CSS Convention

All styles use **vanilla CSS** with **BEM naming** and **CSS custom properties** (design tokens) for theming:

```css
.chat-header__logo    /* Block__Element */
.message--user        /* Block--Modifier */
```

Dark/light themes are controlled by `[data-theme]` attribute — no runtime style injection.

### State Management

- **Context + `useReducer`** via `ChatProvider` → `useChatState()` / `useChatDispatch()`
- No external state libraries (no Redux, no Zustand)

### Testing

- **Vitest** + **React Testing Library** + **jsdom**
- Wrap components in `<ChatProvider>` for tests
- `window.matchMedia` mocked in `test-setup.ts`

---

## 🧰 Tech Stack

| Layer     | Technology                                             |
| --------- | ------------------------------------------------------ |
| Build     | Vite 6                                                 |
| UI        | React 19 + TypeScript 5.7 (strict)                     |
| Styling   | Vanilla CSS with CSS custom properties (BEM naming)    |
| Markdown  | react-markdown 10 + remark-gfm + remark-math           |
| Math      | KaTeX 0.18 (loaded on first use)                       |
| Code      | rehype-highlight (loaded on first use)                 |
| Routing   | React Router v7                                        |
| Streaming | Native `fetch` + `ReadableStream` (SSE via AsyncGenerator) |
| Tests     | Vitest 5 + React Testing Library                       |

---

## 📁 Project Structure

```
src/
├── api/              # API layer (fetch + SSE streaming)
│   ├── chat-api.ts       # Real EDDI backend API
│   └── demo-api.ts       # Mock API for demo mode
├── components/       # React components
│   ├── ChatWidget.tsx    # Main orchestrator (lifecycle, SSE, query params)
│   ├── ChatHeader.tsx    # Logo/title, undo/redo, theme toggle, new conversation
│   ├── MessageBubble.tsx # Message rendering with Markdown + math + code
│   ├── ChatInput.tsx     # Auto-grow textarea, Enter/Shift+Enter
│   ├── QuickReplies.tsx  # Suggested reply pill buttons
│   ├── Indicators.tsx    # Typing (dots) + Thinking (brain) indicators
│   ├── SecretInput.tsx   # Masked password input for secret values
│   └── ScrollToBottom.tsx # Floating scroll button
├── hooks/
│   └── useTheme.ts       # Dark/light/system theme with localStorage
├── store/
│   └── chat-store.tsx    # Context + useReducer state management
├── styles/
│   ├── variables.css     # CSS custom properties (dark/light design tokens)
│   └── chat.css          # Component styles (BEM naming)
└── types.ts              # Shared TypeScript types
```

---

## 🔌 Backend Integration

`npm run build` writes to `dist/`. The EDDI Maven build (run from the repository root) builds this
directory and copies `dist/` into the backend jar, so nothing is copied into the backend source tree by
hand. The chat UI is then served by Quarkus at `http://your-eddi-server/chat` — no separate web server
required.

---

## 🔗 Related

- [**EDDI**](https://github.com/labsai/EDDI) — Backend engine (Java 25, Quarkus)
- [**EDDI Manager**](https://github.com/labsai/EDDI/tree/main/ui/manager) — Admin dashboard (React 19), `ui/manager` of the same repository
- [**quarkus-eddi**](https://github.com/quarkiverse/quarkus-eddi) — Quarkus SDK

---

## 📜 License

Part of the [EDDI](https://github.com/labsai/EDDI) project — [Apache 2.0](https://github.com/labsai/EDDI/blob/main/LICENSE).
