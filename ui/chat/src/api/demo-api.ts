/* ──────────────────────────────────────────────
   Demo Mode — Mock API for testing all UI features
   without a running EDDI backend.

   Activate by visiting: /chat/demo/showcase
   ────────────────────────────────────────────── */

import type { ChatMessage, QuickReply, SSEEvent } from "@/types";

/** Simulated delay */
const delay = (ms: number) => new Promise((r) => setTimeout(r, ms));

/* ─── Welcome message ─────────────────────────── */

const WELCOME_MESSAGE = `👋 **Welcome to EDDI!**

I'm your AI assistant. Here's what I can do:

- Answer questions using **AI-powered reasoning**
- Render \`inline code\` and code blocks
- Display math: $E = mc^2$
- Format **bold**, *italic*, and ~~strikethrough~~ text
- Show [links](https://labs.ai) and lists

Try typing a message or use the quick replies below!`;

/* ─── Mock responses ──────────────────────────── */

interface MockResponse {
  text: string;
  quickReplies?: QuickReply[];
  thinkFirst?: boolean;
}

const RESPONSES: Record<string, MockResponse> = {
  "Tell me about EDDI": {
    text: `**EDDI** (Enhanced Dialog Driven Intelligence) is an open-source conversational AI platform.

### Key Features
| Feature | Description |
|---------|-------------|
| Multi-bot orchestration | Run multiple bots in parallel |
| Plugin architecture | Extensible with custom behaviors |
| SSE Streaming | Real-time token-by-token responses |
| REST API | Full conversation management |

EDDI supports **LLM integration**, behavior rules, HTTP callouts, and much more.`,
    quickReplies: [
      { value: "Show me code" },
      { value: "What about streaming?" },
    ],
  },

  "Show me code": {
    text: `Here's a simple example of using the EDDI API:

\`\`\`typescript
// Start a conversation
const response = await fetch('/bots/unrestricted/myBot', {
  method: 'POST'
});
const conversationId = response.headers.get('Location');

// Send a message
const result = await fetch(
  \`/bots/unrestricted/myBot/\${conversationId}\`,
  {
    method: 'POST',
    headers: { 'Content-Type': 'text/plain' },
    body: 'Hello, EDDI!'
  }
);
\`\`\`

The API returns a \`ConversationMemorySnapshot\` with the bot's response.`,
    quickReplies: [
      { value: "What about streaming?" },
      { value: "Tell me more" },
    ],
  },

  "What about streaming?": {
    text: `EDDI supports **Server-Sent Events (SSE)** for real-time streaming responses.

When streaming is enabled, the bot sends tokens one at a time:

1. 🧠 **Thinking phase** — the bot reasons about your input
2. ⚡ **Streaming phase** — tokens arrive incrementally
3. ✅ **Done** — the complete response is ready

This gives users immediate feedback instead of waiting for the entire response.`,
    thinkFirst: true,
    quickReplies: [
      { value: "Show me a list" },
      { value: "Tell me about EDDI" },
    ],
  },

  "Show me a list": {
    text: `Here's what EDDI v6 brings:

### New in v6
- ✅ Keycloak authentication integration
- ✅ PostgreSQL support (alongside MongoDB)
- ✅ MCP (Model Context Protocol) integration
- ✅ Improved chat UI with streaming
- ✅ Dark/light theme support
- 🔄 Multi-bot orchestration (coming soon)

### Architecture Improvements
1. **DB-agnostic storage** — choose PostgreSQL or MongoDB
2. **Message queue infrastructure** — NATS JetStream
3. **Decomposed engine** — modular pipeline stages
4. **Enhanced REST API** — consistent JSON responses`,
    quickReplies: [
      { value: "Tell me about EDDI" },
      { value: "Thanks!" },
    ],
  },

  "Thanks!": {
    text: `You're welcome! 😊

Feel free to ask me anything else, or try these:`,
    quickReplies: [
      { value: "Tell me about EDDI" },
      { value: "Show me code" },
      { value: "Show me a list" },
    ],
  },
};

function getResponse(input: string): MockResponse {
  // Case-insensitive match
  const key = Object.keys(RESPONSES).find(
    (k) => k.toLowerCase() === input.toLowerCase(),
  );
  if (key) return RESPONSES[key];

  // Default response with thinking
  return {
    text: `I received your message: *"${input}"*

This is a **demo mode** response. In production, this would be processed by the EDDI backend AI engine.

Try one of the suggested quick replies to see more features!`,
    thinkFirst: true,
    quickReplies: [
      { value: "Tell me about EDDI" },
      { value: "Show me code" },
      { value: "What about streaming?" },
      { value: "Show me a list" },
    ],
  };
}

/* ─── Demo API functions ──────────────────────── */

export function isDemoMode(environment?: string, botId?: string): boolean {
  return environment === "demo" && botId === "showcase";
}

export async function demoStartConversation(): Promise<{
  conversationId: string;
  welcomeMessage: ChatMessage;
  quickReplies: QuickReply[];
}> {
  await delay(400);
  return {
    conversationId: `demo-conv-${Date.now()}`,
    welcomeMessage: {
      id: `bot-welcome-${Date.now()}`,
      role: "bot",
      content: WELCOME_MESSAGE,
      timestamp: Date.now(),
    },
    quickReplies: [
      { value: "Tell me about EDDI" },
      { value: "Show me code" },
      { value: "What about streaming?" },
      { value: "Show me a list" },
    ],
  };
}

export async function* demoSendMessageStreaming(
  message: string,
): AsyncGenerator<SSEEvent> {
  const response = getResponse(message);

  // Simulate thinking phase
  if (response.thinkFirst) {
    yield { type: "thinking", data: "" };
    await delay(1500);
  }

  // Stream tokens word by word
  const words = response.text.split(/(\s+)/);
  for (const word of words) {
    yield { type: "token", data: word };
    await delay(25 + Math.random() * 35);
  }

  yield { type: "done", data: "" };
}

export function demoGetQuickReplies(message: string): QuickReply[] {
  const response = getResponse(message);
  return response.quickReplies ?? [];
}
