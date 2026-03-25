# EDDI Chat UI

A standalone, configurable chat widget for [EDDI](https://github.com/labsai/EDDI) conversational AI agents. Built with React 19, TypeScript, and Vite.

## Features

- 💬 **Rich markdown** — Tables, code blocks, bold/italic, links, lists
- 🌊 **SSE streaming** — Real-time token-by-token agent responses with thinking indicator
- 🎨 **Dark/Light themes** — Toggle via UI or query parameters
- ⚡ **Quick replies** — Pill buttons for suggested responses
- ↩↪ **Undo/Redo** — Step through conversation history
- 🔧 **Configurable** — All features togglable via URL query parameters
- 📱 **Responsive** — Mobile-first design with adaptive breakpoints
- 🎭 **Demo mode** — Full showcase without a running backend

## Quick Start

```bash
npm install
npm run dev        # http://localhost:5174
```

### URL Patterns

| URL                                   | Description                 |
| ------------------------------------- | --------------------------- |
| `/chat/:environment/:agentId`         | Connect to a specific agent |
| `/chat/demo/showcase`                 | Demo mode with mock data    |
| `/chat/managedagents/:intent/:userId` | Managed agent mode          |

### Query Parameters

| Parameter             | Example                     | Effect                             |
| --------------------- | --------------------------- | ---------------------------------- |
| `hideUndo`            | `?hideUndo=true`            | Hide undo button                   |
| `hideRedo`            | `?hideRedo=true`            | Hide redo button                   |
| `hideNewConversation` | `?hideNewConversation=true` | Hide restart button                |
| `hideLogo`            | `?hideLogo=true`            | Show text title instead of logo    |
| `hideQuickReplies`    | `?hideQuickReplies=true`    | Hide quick reply buttons           |
| `theme`               | `?theme=light`              | Set initial theme (`dark`/`light`) |
| `title`               | `?title=My%20Agent`         | Override header title              |

## Development

```bash
npm run dev          # Dev server (port 5174) with proxy to EDDI backend
npm run build        # Production build
npx vitest run       # Run tests (42 tests)
npx tsc --noEmit     # Type check
```

### Project Structure

```
src/
├── api/              # API layer (fetch + SSE streaming)
│   ├── chat-api.ts       # Real EDDI backend API
│   └── demo-api.ts       # Mock API for demo mode
├── components/       # React components
│   ├── ChatWidget.tsx    # Main orchestrator
│   ├── ChatHeader.tsx    # Logo, actions, theme toggle
│   ├── MessageBubble.tsx # Message rendering with Markdown
│   ├── ChatInput.tsx     # Auto-grow textarea + send
│   ├── QuickReplies.tsx  # Suggested reply pills
│   ├── Indicators.tsx    # Typing + Thinking indicators
│   └── ScrollToAgenttom.tsx
├── hooks/
│   └── useTheme.ts       # Theme management
├── store/
│   └── chat-store.tsx    # Context + useReducer
├── styles/
│   ├── variables.css     # CSS custom properties (design tokens)
│   └── chat.css          # Component styles (BEM naming)
└── types.ts              # Shared types
```

## Embedding

The chat UI can be embedded in any HTML page via iframe:

```html
<iframe
  src="https://your-eddi-server/chat/production/your-agent-id?hideNewConversation=true&theme=dark"
  style="width: 400px; height: 600px; border: none; border-radius: 12px;"
></iframe>
```

## Backend Integration

The production build is deployed into the EDDI Quarkus backend at:

```
EDDI/src/main/resources/META-INF/resources/
```

This makes the chat UI available at `http://your-eddi-server/chat.html`.

## License

Part of the EDDI project — see [EDDI License](https://github.com/labsai/EDDI/blob/master/LICENSE).
