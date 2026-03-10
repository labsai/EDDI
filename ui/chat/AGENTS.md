# EDDI Chat UI — AI Agent Guidelines

> **This file is loaded by AI coding assistants. Follow ALL rules below.**

## 1. Project Context

**eddi-chat-ui** is a standalone React 19 chat widget for [EDDI](https://github.com/labsai/EDDI) bots. Built with Vite + TypeScript 5.7, vanilla CSS with CSS custom properties, and `react-markdown` for rich message rendering.

### Tech Stack

| Technology     | Version | Purpose                        |
| -------------- | ------- | ------------------------------ |
| React          | 19      | UI framework                   |
| TypeScript     | 5.7     | Type safety                    |
| Vite           | 6       | Build tool + dev server        |
| Vitest         | 3.x     | Unit testing (jsdom)           |
| react-markdown | 9.x     | Markdown rendering in messages |

### Ecosystem

- **EDDI backend** — Quarkus REST API at `/bots/{env}/{botId}`, serves chat UI from `META-INF/resources/`
- **EDDI Manager** — Admin UI with embedded chat panel, shares API patterns
- All repos at `c:\dev\git\`

---

## 2. Project Structure

```
src/
├── api/            # API layer
│   ├── chat-api.ts     # Pure fetch functions (start, read, send, stream, undo, redo)
│   └── demo-api.ts     # Mock API for demo mode
├── components/     # UI components
│   ├── ChatWidget.tsx      # Main orchestrator (lifecycle, SSE, query params)
│   ├── ChatHeader.tsx      # Logo/title, undo/redo, theme toggle, new conversation
│   ├── MessageBubble.tsx   # User/bot messages with markdown
│   ├── ChatInput.tsx       # Auto-grow textarea, Enter/Shift+Enter
│   ├── QuickReplies.tsx    # Pill buttons for suggested replies
│   ├── Indicators.tsx      # Typing (dots) + Thinking (brain) indicators
│   └── ScrollToBottom.tsx  # Floating scroll button
├── hooks/
│   └── useTheme.ts     # Dark/light/system theme with localStorage
├── store/
│   └── chat-store.tsx  # Context + useReducer state management
├── styles/
│   ├── variables.css   # CSS custom properties (dark/light tokens)
│   └── chat.css        # All component styles (BEM naming)
└── types.ts            # Shared TypeScript types
```

---

## 3. Key Conventions

1. **CSS** — BEM naming: `.chat-header__logo`, `.message--user`, `.quick-replies__btn`. No Tailwind.
2. **State** — `ChatProvider` → `useChatState()` / `useChatDispatch()`. No prop drilling.
3. **API** — Pure `fetch` in `chat-api.ts`. SSE streaming uses `AsyncGenerator`.
4. **Testing** — Wrap components in `<ChatProvider>`. Mock `window.matchMedia` in `test-setup.ts`.
5. **Demo mode** — `/chat/demo/showcase` uses `demo-api.ts`. Check with `isDemoMode()`.
6. **Query params** — `hideUndo`, `hideRedo`, `hideNewConversation`, `hideLogo`, `theme`, `title`.

---

## 4. Build & Deploy

Production build goes to **EDDI backend** at `src/main/resources/META-INF/resources/`:

```bash
npm run build    # Outputs to EDDI backend resources
```

---

## 5. Mandatory Workflow

1. **Before work**: `git status`, read this file + any `changelog.md`
2. **During work**: Commit with `feat(chat-ui):` / `fix(chat-ui):`. Each commit must build.
3. **After work**: Update `changelog.md`

### DO NOT

- Add external state libraries (zustand, redux) — use Context + useReducer
- Add CSS frameworks — use CSS custom properties
- Duplicate logic from `demo-api.ts` into components
