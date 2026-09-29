/* ──────────────────────────────────────────────
   ChatWidget — regressions from the 2026-09-25 UI review
   ────────────────────────────────────────────── */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter, Routes, Route, useLocation } from "react-router-dom";
import { ChatWidget } from "./ChatWidget";
import { ChatProvider } from "@/store/chat-store";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.restoreAllMocks();
});

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
});

interface Call {
  url: string;
  method: string;
  headers: Headers;
  body?: string;
}

interface BackendOptions {
  /** SSE frames the stream endpoint answers with. */
  frames?: string[];
  /** Snapshot returned by every GET of a conversation. */
  snapshot?: Record<string, unknown>;
  /** Status for POST …/start. */
  startStatus?: number;
  /** Delay the start response, to leave a window for a second click. */
  startDelayMs?: number;
}

function mockBackend(options: BackendOptions = {}) {
  const calls: Call[] = [];
  let started = 0;
  globalThis.fetch = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    calls.push({
      url,
      method,
      headers: new Headers(init?.headers),
      body: typeof init?.body === "string" ? init.body : undefined,
    });
    if (url.includes("/start")) {
      started += 1;
      if (options.startDelayMs) {
        await new Promise((r) => setTimeout(r, options.startDelayMs));
      }
      if (options.startStatus && options.startStatus >= 400) {
        return new Response("not found", { status: options.startStatus });
      }
      return new Response(null, {
        status: 201,
        headers: { Location: `/agents/conv-${started}` },
      });
    }
    if (url.includes("/descriptorstore/")) {
      return new Response(JSON.stringify({ name: "Support Bot" }), { status: 200 });
    }
    if (url.includes("/stream")) {
      const enc = new TextEncoder();
      return new Response(
        new ReadableStream({
          start(c) {
            for (const f of options.frames ?? []) c.enqueue(enc.encode(f));
            c.close();
          },
        }),
        { status: 200, headers: { "Content-Type": "text/event-stream" } },
      );
    }
    if (url.includes("/endConversation")) {
      return new Response(null, { status: 200 });
    }
    return new Response(
      JSON.stringify(
        options.snapshot ?? {
          agentId: "agent-1",
          agentVersion: 3,
          conversationState: "READY",
          conversationSteps: [],
        },
      ),
      { status: 200 },
    );
  }) as typeof fetch;
  return calls;
}

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location">{location.pathname + location.search}</span>;
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ChatProvider>
        <Routes>
          <Route path="/chat/managed/:intent/:userId" element={<ChatWidget />} />
          <Route path="/chat/:environment/:agentId" element={<ChatWidget />} />
        </Routes>
        <LocationProbe />
      </ChatProvider>
    </MemoryRouter>,
  );
}

async function send(text: string) {
  const input = await screen.findByTestId("chat-input");
  await waitFor(() => expect(input).not.toBeDisabled());
  fireEvent.change(input, { target: { value: text } });
  fireEvent.keyDown(input, { key: "Enter", shiftKey: false });
}

describe("environment in the route (UI review High 13)", () => {
  it("starts the conversation in the environment the route names", async () => {
    const calls = mockBackend();
    renderAt("/chat/test/agent-1");

    await waitFor(() => expect(calls.some((c) => c.url.includes("/start"))).toBe(true));
    const start = calls.find((c) => c.url.includes("/start"))!;
    expect(start.url).toContain("/agents/agent-1/start?environment=test");
  });

  it("says why when the agent cannot be started, instead of hanging", async () => {
    mockBackend({ startStatus: 404 });
    renderAt("/chat/test/agent-1");

    expect(
      await screen.findByText(/not available in the "test" environment/i),
    ).toBeInTheDocument();
    expect(screen.queryByText(/Starting conversation/)).not.toBeInTheDocument();
  });
});

describe("a password field requested on the streaming path (UI review High 6)", () => {
  const done = JSON.stringify({
    conversationState: "READY",
    conversationOutputs: [
      {
        output: [
          { type: "text", text: "Paste your API key" },
          { type: "inputField", subType: "password", label: "API Key" },
        ],
      },
    ],
  });

  it("does not re-raise a prompt from an earlier, already-answered turn", async () => {
    mockBackend({
      snapshot: {
        agentId: "agent-1",
        agentVersion: 1,
        conversationState: "READY",
        conversationOutputs: [
          { output: [{ type: "inputField", subType: "password", label: "API Key" }] },
          { input: "<secret input>", output: [{ type: "text", text: "Key saved." }] },
        ],
      },
    });
    renderAt("/chat/production/agent-1");

    expect(await screen.findByText("Key saved.")).toBeInTheDocument();
    expect(screen.queryByTestId("secret-input-field")).not.toBeInTheDocument();
    expect(screen.getByTestId("chat-input")).toBeInTheDocument();
  });

  it("shows the masked field and sends the key as secret", async () => {
    const calls = mockBackend({ frames: [`event: done\ndata: ${done}\n\n`] });
    renderAt("/chat/production/agent-1");

    await send("set up my agent");

    const field = (await screen.findByTestId("secret-input-field")) as HTMLInputElement;
    expect(field.type).toBe("password");
    expect(screen.getByTestId("secret-input-label")).toHaveTextContent("API Key");

    fireEvent.change(field, { target: { value: "sk-live-123" } });
    fireEvent.keyDown(field, { key: "Enter", shiftKey: false });

    await waitFor(() =>
      expect(calls.filter((c) => c.url.includes("/stream"))).toHaveLength(2),
    );
    const second = calls.filter((c) => c.url.includes("/stream"))[1];
    const body = JSON.parse(second.body!);
    expect(body.input).toBe("sk-live-123");
    expect(body.context.secretInput).toEqual({ type: "string", value: "true" });
    // Never shown in clear.
    expect(screen.queryByText("sk-live-123")).not.toBeInTheDocument();
  });
});

describe("a streamed refusal (UI review Medium: streaming error codes)", () => {
  it("withdraws the unsent message and hands the draft back", async () => {
    mockBackend({
      frames: [
        'event: error\ndata: {"message":"Conversation is awaiting approval","code":"awaiting_approval"}\n\n',
      ],
    });
    renderAt("/chat/production/agent-1");

    await send("please do the thing");

    expect(await screen.findByText(/was not sent/i)).toBeInTheDocument();
    // No user bubble for a message the agent never received…
    expect(
      screen.queryByText("please do the thing", { selector: ".message__bubble p" }),
    ).not.toBeInTheDocument();
    // …and the text is back in the composer.
    await waitFor(() =>
      expect((screen.getByTestId("chat-input") as HTMLTextAreaElement).value).toBe(
        "please do the thing",
      ),
    );
  });

  it("keeps a mid-turn failure (no code) as an error on the sent message", async () => {
    mockBackend({
      frames: ['event: error\ndata: {"message":"LLM provider unavailable"}\n\n'],
    });
    renderAt("/chat/production/agent-1");

    await send("hello");

    expect(await screen.findByText(/LLM provider unavailable/)).toBeInTheDocument();
    expect(screen.getByText("hello")).toBeInTheDocument();
  });
});

describe("streamed text (UI review High 12)", () => {
  it("does not add a space per token", async () => {
    // padDataLines writes "data: " + token; a token's own leading space follows.
    mockBackend({
      frames: [
        "event: token\ndata: quota\n\n",
        "event: token\ndata: tion\n\n",
        "event: token\ndata:  works\n\n",
        `event: done\ndata: ${JSON.stringify({ conversationState: "READY" })}\n\n`,
      ],
    });
    renderAt("/chat/production/agent-1");

    await send("hi");

    expect(await screen.findByText("quotation works")).toBeInTheDocument();
  });
});

describe("New conversation", () => {
  it("ends the managed conversation before loading, so it really is new", async () => {
    const calls = mockBackend({
      snapshot: {
        conversationId: "managed-1",
        agentId: "agent-1",
        agentVersion: 1,
        conversationState: "READY",
        conversationSteps: [],
      },
    });
    renderAt("/chat/managed/support/user-7");

    fireEvent.click(await screen.findByTestId("restart-btn"));

    await waitFor(() =>
      expect(calls.some((c) => c.url.endsWith("/agents/managed/support/user-7/endConversation"))).toBe(true),
    );
    const endIndex = calls.findIndex((c) => c.url.includes("/endConversation"));
    const reloadIndex = calls.findIndex(
      (c, i) => i > endIndex && c.url.includes("/agents/managed/support/user-7?"),
    );
    expect(reloadIndex).toBeGreaterThan(endIndex);
  });

  it("does not graft the first conversation's slow greeting onto the new one", async () => {
    // The first load had no generation check: a restart while its welcome read
    // was still in flight got conv-1's greeting (and id) in conv-2's transcript.
    let releaseFirstRead: () => void = () => {};
    let started = 0;
    globalThis.fetch = vi.fn(async (input: string | URL | Request) => {
      const url = String(input);
      if (url.includes("/start")) {
        started += 1;
        return new Response(null, { status: 201, headers: { Location: `/agents/conv-${started}` } });
      }
      if (url.includes("/descriptorstore/")) return new Response("{}", { status: 200 });
      const which = url.includes("/agents/conv-1?") ? 1 : 2;
      if (which === 1) {
        await new Promise<void>((r) => {
          releaseFirstRead = r;
        });
      }
      return new Response(
        JSON.stringify({
          conversationState: "READY",
          conversationOutputs: [{ output: [{ type: "text", text: `Welcome from conv-${which}` }] }],
        }),
        { status: 200 },
      );
    }) as typeof fetch;
    renderAt("/chat/production/agent-1");

    // The action bar appears as soon as conv-1's id is known, before its read.
    fireEvent.click(await screen.findByTestId("restart-btn"));
    expect(await screen.findByText("Welcome from conv-2")).toBeInTheDocument();

    releaseFirstRead();
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByText("Welcome from conv-1")).not.toBeInTheDocument();
  });
});

describe("the ?token= parameter", () => {
  it("is used for auth and then removed from the address", async () => {
    const calls = mockBackend();
    renderAt("/chat/production/agent-1?token=abc123&theme=light");

    await waitFor(() => expect(calls.some((c) => c.url.includes("/start"))).toBe(true));
    const start = calls.find((c) => c.url.includes("/start"))!;
    expect(start.headers.get("Authorization")).toBe("Bearer abc123");

    await waitFor(() =>
      expect(screen.getByTestId("location")).toHaveTextContent("/chat/production/agent-1?theme=light"),
    );
    // Still authenticated after the URL changed.
    await waitFor(() => expect(calls.some((c) => c.url.includes("/agents/conv-1?"))).toBe(true));
    const read = calls.find((c) => c.url.includes("/agents/conv-1?"))!;
    expect(read.headers.get("Authorization")).toBe("Bearer abc123");
  });
});

describe("the agent name", () => {
  it("is read from the versioned descriptor, with the caller's token", async () => {
    const calls = mockBackend();
    renderAt("/chat/production/agent-1?token=t0k");

    expect(await screen.findByText("Support Bot")).toBeInTheDocument();
    const read = calls.find((c) => c.url.includes("/descriptorstore/"))!;
    expect(read.url).toContain("/descriptorstore/descriptors/agent-1/simple?version=3");
    expect(read.headers.get("Authorization")).toBe("Bearer t0k");
  });
});

describe("the transcript", () => {
  it("is a live log so replies are announced", async () => {
    mockBackend();
    renderAt("/chat/production/agent-1");

    const log = await screen.findByTestId("chat-transcript");
    expect(log).toHaveAttribute("role", "log");
    expect(log).toHaveAttribute("aria-live", "polite");
  });
});

describe("image output items", () => {
  it("renders a configured image from the done snapshot", async () => {
    mockBackend({
      frames: [
        `event: done\ndata: ${JSON.stringify({
          conversationState: "READY",
          conversationOutputs: [
            { output: [{ type: "image", uri: "https://cdn.example/a.png", alt: "Diagram" }] },
          ],
        })}\n\n`,
      ],
    });
    renderAt("/chat/production/agent-1");

    await send("show me");

    expect(await screen.findByAltText("Diagram")).toHaveAttribute(
      "src",
      "https://cdn.example/a.png",
    );
  });
});
