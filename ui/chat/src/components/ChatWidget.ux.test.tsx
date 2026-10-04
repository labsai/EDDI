/* ──────────────────────────────────────────────
   ChatWidget — regressions from the 2026-10 UX review of the standalone chat
   ────────────────────────────────────────────── */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, fireEvent, act } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { ChatWidget } from "./ChatWidget";
import { ChatProvider } from "@/store/chat-store";
import { setAuthToken } from "@/api/http";
import { setLocale } from "@/i18n";

const originalFetch = globalThis.fetch;

afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.restoreAllMocks();
  vi.useRealTimers();
  setLocale("en");
  setAuthToken(null);
  document.title = "";
});

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
});

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ChatProvider>
        <Routes>
          <Route path="/chat/:environment/:agentId" element={<ChatWidget />} />
          <Route path="/chat/managed/:intent/:userId" element={<ChatWidget />} />
        </Routes>
      </ChatProvider>
    </MemoryRouter>,
  );
}

interface Backend {
  /** Every request, in order. */
  calls: { url: string; method: string; signal?: AbortSignal | null }[];
  /** Response for POST /agents/{id} (non-streaming send) and /stream. */
  send?: (url: string, init?: RequestInit) => Promise<Response> | Response;
  /** Snapshot for GET /agents/{id}. */
  snapshot?: Record<string, unknown>;
  /** Response for POST …/start (default 201). */
  start?: () => Promise<Response> | Response;
  /** Response for GET approval-status. */
  approval?: () => Response;
  /** Response for undo/redo. */
  history?: () => Response;
}

const READY_WITH_GREETING = {
  conversationState: "READY",
  conversationSteps: [],
  conversationOutputs: [{ output: [{ type: "text", text: "Hello there" }] }],
};

function install(b: Partial<Backend> = {}): Backend {
  const backend: Backend = { calls: [], ...b };
  let started = 0;
  globalThis.fetch = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    backend.calls.push({ url, method, signal: init?.signal });
    if (url.includes("/start")) {
      if (backend.start) return backend.start();
      started += 1;
      return new Response(null, {
        status: 201,
        headers: { Location: `/agents/conv-${started}` },
      });
    }
    if (url.includes("/agentstore/")) return new Response("{}", { status: 200 });
    if (url.includes("/approval-status")) {
      return backend.approval
        ? backend.approval()
        : new Response("nope", { status: 500 });
    }
    if (url.includes("/undo") || url.includes("/redo")) {
      return backend.history ? backend.history() : new Response(null, { status: 200 });
    }
    if (method === "POST" && (url.includes("/stream") || /\/agents\/[^/?]+\?/.test(url))) {
      if (backend.send) return backend.send(url, init);
    }
    return new Response(JSON.stringify(backend.snapshot ?? READY_WITH_GREETING), {
      status: 200,
    });
  }) as typeof fetch;
  return backend;
}

async function typeAndSend(text: string) {
  const input = await screen.findByTestId("chat-input");
  fireEvent.change(input, { target: { value: text } });
  fireEvent.keyDown(input, { key: "Enter", shiftKey: false });
}

describe("failed sends give the draft back", () => {
  it("restores the draft and withdraws the bubble when the stream fails before any token", async () => {
    install({ send: () => new Response("boom", { status: 500 }) });
    renderAt("/chat/production/agent-1");
    await screen.findByText("Hello there");

    await typeAndSend("my important question");

    await screen.findByText(/could not be sent/i);
    // The user bubble is gone and the text is back in the composer.
    expect(screen.queryByText("my important question", { selector: "p" })).toBeNull();
    await waitFor(() =>
      expect((screen.getByTestId("chat-input") as HTMLTextAreaElement).value).toBe(
        "my important question",
      ),
    );
  });

  it("keeps partial text, says the connection dropped, and re-reads the conversation", async () => {
    const backend = install({
      send: () => {
        const enc = new TextEncoder();
        return new Response(
          new ReadableStream({
            start(c) {
              c.enqueue(enc.encode("event: token\ndata: half an answ\n\n"));
              // Erroring at once would discard the queued token; drop the link after it.
              setTimeout(() => c.error(new TypeError("network error")), 20);
            },
          }),
          { status: 200, headers: { "Content-Type": "text/event-stream" } },
        );
      },
    });
    renderAt("/chat/production/agent-1");
    await screen.findByText("Hello there");
    const readsBefore = backend.calls.filter((c) => c.method === "GET").length;

    await typeAndSend("tell me more");

    await screen.findByText(/Connection lost/);
    expect(screen.getByText(/half an answ/)).toBeInTheDocument();
    // The user's message stays (it was consumed), and no draft is pushed back.
    expect(screen.getByText("tell me more", { selector: "p" })).toBeInTheDocument();
    await waitFor(() =>
      expect(backend.calls.filter((c) => c.method === "GET").length).toBeGreaterThan(
        readsBefore,
      ),
    );
    expect((screen.getByTestId("chat-input") as HTMLTextAreaElement).value).toBe("");
  });

  it.each([
    [404, /not ready to answer/i],
    [413, /too large/i],
    [429, /usage limit/i],
    [401, /session has expired/i],
    [403, /not allowed to continue/i],
  ])("a %i is explained and the draft restored", async (status, copy) => {
    install({ send: () => new Response("x", { status }) });
    renderAt("/chat/production/agent-1");
    await screen.findByText("Hello there");

    await typeAndSend("hello draft");

    await screen.findByText(copy);
    await waitFor(() =>
      expect((screen.getByTestId("chat-input") as HTMLTextAreaElement).value).toBe(
        "hello draft",
      ),
    );
  });

  it("a 410 ends the conversation and gives the text back", async () => {
    install({ send: () => new Response("gone", { status: 410 }) });
    renderAt("/chat/production/agent-1");
    await screen.findByText("Hello there");

    await typeAndSend("late message");

    await screen.findByTestId("chat-ended");
    expect(screen.queryByText("late message", { selector: "p" })).toBeNull();
  });

  it("works the same on the non-streaming transport", async () => {
    install({ send: () => new Response("{}", { status: 429 }) });
    renderAt("/chat/production/agent-1?hideStreaming=true");
    await screen.findByText("Hello there");

    await typeAndSend("plain draft");

    await screen.findByText(/usage limit/i);
    await waitFor(() =>
      expect((screen.getByTestId("chat-input") as HTMLTextAreaElement).value).toBe(
        "plain draft",
      ),
    );
  });
});

describe("Stop", () => {
  /** A send that answers only when the test says so; `rejectOnAbort` models fetch. */
  function slowSend(rejectOnAbort: boolean) {
    let release: (r: Response) => void = () => {};
    const backend = install({
      send: (_url, init) =>
        new Promise<Response>((resolve, reject) => {
          release = resolve;
          if (rejectOnAbort) {
            init?.signal?.addEventListener("abort", () =>
              reject(new DOMException("aborted", "AbortError")),
            );
          }
        }),
    });
    const reply = () =>
      release(
        new Response(
          JSON.stringify({
            conversationState: "READY",
            conversationOutputs: [{ output: [{ type: "text", text: "LATE REPLY" }] }],
          }),
          { status: 200 },
        ),
      );
    return { backend, reply };
  }

  it("aborts the request of a non-streaming turn", async () => {
    const { backend } = slowSend(true);
    renderAt("/chat/production/agent-1?hideStreaming=true");
    await screen.findByText("Hello there");

    await typeAndSend("slow one");
    fireEvent.click(await screen.findByTestId("chat-stop"));

    const post = backend.calls.find((c) => c.method === "POST" && !c.url.includes("/start"));
    expect(post?.signal?.aborted).toBe(true);
    // Stopping is not an error: no failure notice, and the composer is free.
    await new Promise((r) => setTimeout(r, 20));
    expect(screen.queryByTestId("message-notice")).toBeNull();
    expect(screen.queryByTestId("chat-stop")).toBeNull();
  });

  it("ignores a late reply that still arrives after Stop (non-streaming)", async () => {
    const { reply } = slowSend(false);
    renderAt("/chat/production/agent-1?hideStreaming=true");
    await screen.findByText("Hello there");

    await typeAndSend("slow one");
    fireEvent.click(await screen.findByTestId("chat-stop"));
    await act(async () => reply());

    expect(screen.queryByText("LATE REPLY")).toBeNull();
  });
});

describe("starting", () => {
  it("offers Try again after a failed start", async () => {
    let attempt = 0;
    install({
      start: () => {
        attempt += 1;
        return attempt === 1
          ? new Response("boom", { status: 500 })
          : new Response(null, { status: 201, headers: { Location: "/agents/conv-9" } });
      },
    });
    renderAt("/chat/production/agent-1");

    fireEvent.click(await screen.findByTestId("start-retry"));

    expect(await screen.findByText("Hello there")).toBeInTheDocument();
    expect(screen.queryByTestId("start-retry")).toBeNull();
  });

  it("says 'say hello' rather than 'starting' for an agent with no greeting", async () => {
    install({ snapshot: { conversationState: "READY", conversationSteps: [], conversationOutputs: [] } });
    renderAt("/chat/production/agent-1");

    expect(await screen.findByText(/Say hello to get started/)).toBeInTheDocument();
    expect(screen.queryByText(/Starting conversation/)).toBeNull();
  });

  it("explains a 401 and a 403 differently", async () => {
    install({ start: () => new Response("", { status: 401 }) });
    const { unmount } = renderAt("/chat/production/agent-1");
    expect(await screen.findByText(/sign in/i)).toBeInTheDocument();
    unmount();

    install({ start: () => new Response("", { status: 403 }) });
    renderAt("/chat/production/agent-1");
    expect(await screen.findByText(/Access to this agent was refused/)).toBeInTheDocument();
  });
});

describe("postMessage token handshake", () => {
  function fakeParent() {
    const iframe = document.createElement("iframe");
    document.body.appendChild(iframe);
    const parent = iframe.contentWindow!;
    const post = vi.fn();
    Object.defineProperty(parent, "postMessage", { value: post, configurable: true });
    const original = Object.getOwnPropertyDescriptor(window, "parent");
    Object.defineProperty(window, "parent", { value: parent, configurable: true });
    return {
      parent,
      post,
      restore() {
        if (original) Object.defineProperty(window, "parent", original);
        iframe.remove();
      },
    };
  }

  it("announces readiness to the allowed origin only and holds the start until the token arrives", async () => {
    const host = fakeParent();
    const backend = install();
    try {
      renderAt("/chat/production/agent-1?tokenOrigin=https://host.example");

      expect(host.post).toHaveBeenCalledWith({ type: "eddi-chat-ready" }, "https://host.example");
      expect(host.post.mock.calls.every(([, origin]) => origin !== "*")).toBe(true);
      // Nothing started yet.
      expect(backend.calls.some((c) => c.url.includes("/start"))).toBe(false);

      await act(async () => {
        window.dispatchEvent(
          new MessageEvent("message", {
            data: { type: "eddi-chat-token", token: "abc" },
            origin: "https://host.example",
            source: host.parent,
          }),
        );
      });

      expect(await screen.findByText("Hello there")).toBeInTheDocument();
      expect(backend.calls.some((c) => c.url.includes("/start"))).toBe(true);
    } finally {
      host.restore();
    }
  });

  it("gives up waiting and starts anyway, so a silent parent cannot hang the widget", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const host = fakeParent();
    const backend = install();
    try {
      renderAt("/chat/production/agent-1?tokenOrigin=https://host.example");
      expect(backend.calls.some((c) => c.url.includes("/start"))).toBe(false);

      await act(async () => {
        await vi.advanceTimersByTimeAsync(5100);
      });

      await waitFor(() => expect(backend.calls.some((c) => c.url.includes("/start"))).toBe(true));
    } finally {
      host.restore();
    }
  });

  it("starts at once when no tokenOrigin is configured", async () => {
    const backend = install();
    renderAt("/chat/production/agent-1");
    await screen.findByText("Hello there");
    expect(backend.calls.some((c) => c.url.includes("/start"))).toBe(true);
  });
});

describe("header title", () => {
  it("shows ?title= as text and sets the document title", async () => {
    install();
    renderAt("/chat/production/agent-1?title=Support%20Desk");
    await screen.findByText("Hello there");
    expect(screen.getByTestId("chat-title")).toHaveTextContent("Support Desk");
    expect(document.title).toBe("Support Desk");
  });
});

describe("New conversation confirmation", () => {
  it("asks before discarding a conversation that has messages, and can be cancelled", async () => {
    install({ send: () => new Response("", { status: 200, headers: { "Content-Type": "text/event-stream" } }) });
    renderAt("/chat/production/agent-1");
    await screen.findByText("Hello there");
    await typeAndSend("something worth keeping");
    await screen.findByText("something worth keeping", { selector: "p" });

    fireEvent.click(screen.getByTestId("restart-btn"));
    expect(await screen.findByTestId("restart-confirm")).toBeInTheDocument();

    fireEvent.click(screen.getByTestId("restart-confirm-cancel"));
    expect(screen.queryByTestId("restart-confirm")).toBeNull();
    expect(screen.getByText("something worth keeping", { selector: "p" })).toBeInTheDocument();
  });

  it("restarts straight away when there is nothing to lose", async () => {
    const backend = install();
    renderAt("/chat/production/agent-1");
    await screen.findByText("Hello there");

    fireEvent.click(screen.getByTestId("restart-btn"));

    expect(screen.queryByTestId("restart-confirm")).toBeNull();
    await waitFor(() =>
      expect(backend.calls.filter((c) => c.url.includes("/start")).length).toBe(2),
    );
  });
});

describe("agent-requested input field", () => {
  const withField = {
    conversationState: "READY",
    conversationSteps: [],
    conversationOutputs: [
      {
        output: [
          { type: "text", text: "Need your key" },
          { type: "inputField", subType: "password", label: "API key" },
        ],
      },
    ],
  };

  it("can be left for the ordinary composer", async () => {
    install({ snapshot: withField });
    renderAt("/chat/production/agent-1");
    await screen.findByTestId("secret-input");

    fireEvent.click(screen.getByTestId("secret-input-cancel"));

    expect(screen.queryByTestId("secret-input")).toBeNull();
    expect(await screen.findByTestId("chat-input")).toBeInTheDocument();
    // Focus lands in the composer, not on <body>.
    await waitFor(() => expect(screen.getByTestId("chat-input")).toHaveFocus());
  });
});

describe("transcript semantics", () => {
  it("hides the avatars and names the speaker in words", async () => {
    install();
    const { container } = renderAt("/chat/production/agent-1");
    await screen.findByText("Hello there");

    for (const avatar of container.querySelectorAll(".message__avatar")) {
      expect(avatar).toHaveAttribute("aria-hidden", "true");
    }
    expect(container.querySelector(".message--agent .chat-sr-only")).toHaveTextContent(
      "Assistant said:",
    );
  });

  it("renders local notices as notices, not agent bubbles", async () => {
    install({ send: () => new Response("x", { status: 429 }) });
    const { container } = renderAt("/chat/production/agent-1");
    await screen.findByText("Hello there");
    await typeAndSend("hi");

    const notice = await screen.findByTestId("message-notice");
    expect(notice).toHaveTextContent(/usage limit/i);
    expect(container.querySelector(".message--notice .message__avatar")).toBeNull();
  });
});

describe("auto-scroll", () => {
  function scrollMetrics(el: HTMLElement) {
    let top = 0;
    Object.defineProperty(el, "scrollHeight", { value: 1000, configurable: true });
    Object.defineProperty(el, "clientHeight", { value: 100, configurable: true });
    Object.defineProperty(el, "scrollTop", {
      get: () => top,
      set: (v: number) => {
        top = v;
      },
      configurable: true,
    });
  }

  it("follows a streaming reply only while the reader is at the bottom", async () => {
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const enc = new TextEncoder();
    install({
      send: () =>
        new Response(
          new ReadableStream({
            start(c) {
              controller = c;
            },
          }),
          { status: 200, headers: { "Content-Type": "text/event-stream" } },
        ),
    });
    renderAt("/chat/production/agent-1");
    await screen.findByText("Hello there");
    const transcript = screen.getByTestId("chat-transcript");
    scrollMetrics(transcript);

    await typeAndSend("go");
    await waitFor(() => expect(controller).toBeDefined());
    await act(async () => controller.enqueue(enc.encode("event: token\ndata: one \n\n")));
    // At the bottom: followed (instant jump to the end).
    await waitFor(() => expect(transcript.scrollTop).toBe(1000));

    // The reader scrolls up to re-read...
    transcript.scrollTop = 0;
    fireEvent.scroll(transcript);
    expect(screen.getByTestId("scroll-to-bottom")).toBeInTheDocument();

    // ...and further tokens no longer drag them back down.
    await act(async () => controller.enqueue(enc.encode("event: token\ndata: two\n\n")));
    await screen.findByText(/one\s+two/);
    expect(transcript.scrollTop).toBe(0);
  });
});

describe("undo", () => {
  it("re-derives quick replies and clears a stale input field from the rebuilt snapshot", async () => {
    // Initially: greeting with a quick reply and an input field.
    const initial = {
      conversationState: "READY",
      undoAvailable: true,
      conversationSteps: [],
      conversationOutputs: [
        {
          output: [{ type: "text", text: "Hello there" }, { type: "inputField", subType: "text", label: "Name" }],
          quickReplies: [{ value: "Stale choice" }],
        },
      ],
    };
    const backend = install({ snapshot: initial });
    renderAt("/chat/production/agent-1");
    expect(await screen.findByText("Stale choice")).toBeInTheDocument();
    expect(screen.getByTestId("secret-input")).toBeInTheDocument();
    // Leave the requested field so the undo button is reachable.
    fireEvent.click(screen.getByTestId("secret-input-cancel"));

    // After undo the server answers with a step that has other replies and no field.
    backend.snapshot = {
      conversationState: "READY",
      undoAvailable: false,
      redoAvailable: true,
      conversationSteps: [],
      conversationOutputs: [{ output: [{ type: "text", text: "Hello there" }], quickReplies: [{ value: "Fresh choice" }] }],
    };
    // The mock reads backend.snapshot on every GET.
    fireEvent.click(await screen.findByTestId("undo-btn"));

    expect(await screen.findByText("Fresh choice")).toBeInTheDocument();
    expect(screen.queryByText("Stale choice")).toBeNull();
    expect(screen.queryByTestId("secret-input")).toBeNull();
  });
});

describe("paused without a status", () => {
  it("shows an indicator while the approval status is unknown", async () => {
    install({
      snapshot: {
        conversationState: "AWAITING_HUMAN",
        conversationSteps: [],
        conversationOutputs: [{ output: [{ type: "text", text: "Hold on" }] }],
      },
    });
    renderAt("/chat/production/agent-1");

    expect(await screen.findByTestId("paused-pending")).toHaveTextContent(/Waiting for approval/);
  });
});

describe("accent colours", () => {
  it("derives the tints with color-mix so any CSS colour works", async () => {
    install();
    renderAt("/chat/production/agent-1?accentColor=rebeccapurple");
    await screen.findByText("Hello there");
    expect(document.documentElement.style.getPropertyValue("--chat-accent-soft")).toContain(
      "color-mix(in srgb, rebeccapurple",
    );
    document.documentElement.removeAttribute("style");
  });
});

describe("language", () => {
  it("renders widget copy in the language chosen", async () => {
    setLocale("de");
    install({ snapshot: { conversationState: "READY", conversationSteps: [], conversationOutputs: [] } });
    renderAt("/chat/production/agent-1");

    expect(await screen.findByText("Sagen Sie Hallo, um zu beginnen.")).toBeInTheDocument();
    expect(screen.getByTestId("chat-input")).toHaveAttribute("aria-label", "Nachricht");
  });
});
