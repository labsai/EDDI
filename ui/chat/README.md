# EDDI Chat UI

> **This directory is part of [labsai/EDDI](https://github.com/labsai/EDDI).** It was the separate `labsai/EDDI-Chat-UI` repository until 2026-09-15; its full history was imported here (`git log -- ui/chat`). Issues and pull requests go to `labsai/EDDI`. The UI is built into the EDDI jar by Maven from the repository root — see the root `AGENTS.md` (Build & Test Commands).

> Embeddable chat widget for [**EDDI**](https://github.com/labsai/EDDI) — the open-source multi-agent orchestration middleware for conversational AI.

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://github.com/labsai/EDDI/blob/main/LICENSE) ![Tests](https://img.shields.io/badge/tests-420%2B-brightgreen)

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
- 🧮 **LaTeX Math** — KaTeX rendering for mathematical expressions (`$$…$$`, inline or on its own lines; a single `$` stays a dollar sign)
- 🎨 **Syntax Highlighting** — Code blocks with language-aware highlighting via `rehype-highlight`
- 🌗 **Dark / Light Themes** — Toggle via UI button, URL parameter, or system preference
- ⚡ **Quick Replies** — Pill buttons for suggested responses returned by the agent
- ↩️ **Undo / Redo** — Step through conversation history with backend state sync
- 🔒 **Password Fields** — Masked input support when agents request sensitive data
- 🔧 **Fully Configurable** — Every feature togglable via URL query parameters or typed `ChatConfig`
- 📱 **Responsive** — Mobile-first design with adaptive breakpoints
- 🎭 **Demo Mode** — Full showcase without a running backend (`/chat/demo/showcase`)
- 🌍 **Translated** — English, German, French and Spanish widget text, chosen by `?lang=` or the browser language (see [Language](#-language))
- 📋 **Copy** — a copy button on every agent message and code block
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
# Prerequisites: Node.js ≥ 22.12 (what Vitest 5 requires; the build pins 24.21.0), EDDI backend on localhost:7070
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
| `theme`               | `?theme=light`              | Set the theme (`dark`/`light`/`system`). The URL wins over a theme the visitor chose earlier; the header toggle cycles dark, light, system |
| `title`               | `?title=My%20Agent`         | Header title text and browser tab title (replaces the agent name in the header) |
| `lang`                | `?lang=de`                  | Widget language (`en`, `de`, `fr`, `es`); default: the browser language. Also sets `<html lang>` |
| `accentColor`         | `?accentColor=%23cc3366`    | Accent colour — any CSS colour works (`%23hex`, `rgb(...)`, a name); the tints are derived with `color-mix()` |
| `tokenOrigin`         | `?tokenOrigin=https://app.example.com` | Allow that origin to send the bearer token by `postMessage` (comma-separated list); see [Passing a token](#passing-a-token-from-the-embedding-page) |
| `hideUndo`            | `?hideUndo=true`            | Hide undo button                   |
| `hideRedo`            | `?hideRedo=true`            | Hide redo button                   |
| `hideNewConversation` | `?hideNewConversation=true` | Hide restart button                |
| `hideLogo`            | `?hideLogo=true`            | Show the text title (default "EDDI") instead of the logo |
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

The chat UI can be embedded in any HTML page via iframe — **once EDDI is told which pages may do it.** By default `/chat` answers with `Content-Security-Policy: frame-ancestors 'none'`, and the browser refuses to render it in anyone's iframe. List the embedding origins, space-separated:

```properties
# application.properties, or EDDI_CHAT_FRAME_ANCESTORS in the environment
# (Helm: eddi.chat.frameAncestors)
eddi.chat.frame-ancestors=https://www.example.com https://*.example.org
```

Only `/chat` takes this setting; the Manager and the API always refuse to be framed.

```html
<iframe
  src="https://your-eddi-server/chat/production/your-agent-id?hideNewConversation=true&theme=dark"
  style="width: 400px; height: 600px; border: none; border-radius: 12px;"
></iframe>
```

### Passing a token from the embedding page

A page that signs the user in itself hands the widget its bearer token with `postMessage`, so it never appears in the address bar, history or `Referer`. Name the embedding origin in `?tokenOrigin=` (exact `scheme://host[:port]`, comma-separated for several); with no value no token is accepted.

The iframe loads **before** the host can know it is ready, so there is a handshake, and the widget waits for it before it starts the conversation:

1. The widget posts `{ "type": "eddi-chat-ready" }` to its parent, once to **each** allowed origin (the `targetOrigin` is that origin, never `*`), and repeats it every second while it waits.
2. The host answers with `{ "type": "eddi-chat-token", "token": "<jwt>" }` sent to the iframe's own origin. The widget only accepts it from its own parent window and from an allowed origin.
3. The widget then starts the conversation. If no token arrives within **5 seconds** it starts anyway, without one, so a parent that never answers cannot leave the widget blank; an agent that needs a login then shows its "Please sign in" message.

```js
const frame = document.getElementById("chat");
window.addEventListener("message", (event) => {
  if (event.origin !== "https://your-eddi-server") return;
  if (event.source === frame.contentWindow && event.data?.type === "eddi-chat-ready") {
    frame.contentWindow.postMessage(
      { type: "eddi-chat-token", token: currentAccessToken() },
      "https://your-eddi-server",
    );
  }
});
```

Register the `message` listener **before** the iframe is added to the page. Send a fresh token the same way whenever yours is refreshed.

### Language

The widget's own text (buttons, notices, hints, screen-reader labels) is available in English, German, French and Spanish. `?lang=de` picks one; without it the browser's language list is used, falling back to English. `<html lang>` follows. What an agent says is never translated. To add a language, add a table to `src/i18n.ts`: any key you leave out shows in English.

Combine query parameters to create a minimal, focused chat experience:

```
?hideUndo=true&hideRedo=true&hideNewConversation=true&hideLogo=true&title=Support%20Agent&theme=light&lang=de
```

---

## 🏗️ Development

```bash
npm run dev          # Dev server (port 5174) with proxy to EDDI backend
npm run build        # Production build
npm run test         # Run tests (Vitest unit/component tests)
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
| Build     | Vite 8                                                 |
| UI        | React 19 + TypeScript 5.9 (strict)                     |
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
