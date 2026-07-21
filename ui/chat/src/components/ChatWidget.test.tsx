/* ──────────────────────────────────────────────
   ChatWidget — integration smoke tests

   These exist partly as a guard: nothing imported ChatWidget before, so a
   syntax error in the largest file in the project could survive a fully green
   suite. Mounting it here means the build breaks the tests too.
   ────────────────────────────────────────────── */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router-dom";
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

/** Route the widget the way the app does: /chat/:environment/:agentId */
function renderWidget() {
  return render(
    <MemoryRouter initialEntries={["/chat/production/agent-1"]}>
      <ChatProvider>
        <Routes>
          <Route path="/chat/:environment/:agentId" element={<ChatWidget />} />
        </Routes>
      </ChatProvider>
    </MemoryRouter>,
  );
}

/** Minimal backend: start → Location header, then a snapshot read. */
function mockBackend(snapshot: Record<string, unknown>) {
  globalThis.fetch = vi.fn(async (url: string | URL | Request) => {
    const href = String(url);
    if (href.includes("/start")) {
      return new Response(null, {
        status: 201,
        headers: { Location: "/agents/conv-1" },
      });
    }
    if (href.includes("/approval-status")) {
      return new Response(
        JSON.stringify({
          conversationId: "conv-1",
          state: "AWAITING_HUMAN",
          pausedAt: "2026-07-21T10:00:00Z",
          pauseReason: "manager approval required",
          timeoutPolicy: "AUTO_REJECT",
          approvalTimeout: "PT15M",
          pauseDetails: null,
        }),
        { status: 200 },
      );
    }
    if (href.includes("/agentstore/")) {
      return new Response("{}", { status: 200 });
    }
    return new Response(JSON.stringify(snapshot), { status: 200 });
  }) as typeof fetch;
}

describe("ChatWidget", () => {
  it("mounts and starts a conversation", async () => {
    mockBackend({
      conversationState: "READY",
      conversationSteps: [{ output: "Hello!" }],
    });

    renderWidget();

    expect(await screen.findByText("Hello!")).toBeInTheDocument();
  });

  it("renders bare-string output items instead of dropping them", async () => {
    // HITL writes its placeholder as a raw String in output[].
    mockBackend({
      conversationState: "READY",
      conversationOutputs: [
        { output: ["Waiting for approval of send_email."], quickReplies: [] },
      ],
    });

    renderWidget();

    expect(
      await screen.findByText("Waiting for approval of send_email."),
    ).toBeInTheDocument();
  });

  it("shows the paused card and hides the composer while awaiting approval", async () => {
    mockBackend({
      conversationState: "AWAITING_HUMAN",
      conversationOutputs: [{ output: [], quickReplies: [] }],
    });

    renderWidget();

    const card = await screen.findByTestId("paused-card");
    expect(card).toHaveTextContent(/reviewer must approve/i);

    await waitFor(() => {
      expect(screen.getByTestId("chat-input")).toBeDisabled();
    });
  });

  it("does not offer approve or reject to the end user", async () => {
    mockBackend({
      conversationState: "AWAITING_HUMAN",
      conversationOutputs: [{ output: [], quickReplies: [] }],
    });

    renderWidget();
    await screen.findByTestId("paused-card");

    expect(screen.queryByRole("button", { name: /approve/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /reject/i })).toBeNull();
  });
});

describe("ChatWidget — stop generating", () => {
  /** Backend whose stream opens and then never produces a `done`. */
  function mockHangingStream(onCancel: () => void) {
    globalThis.fetch = vi.fn(async (url: string | URL | Request) => {
      const href = String(url);
      if (href.includes("/start")) {
        return new Response(null, { status: 201, headers: { Location: "/agents/conv-1" } });
      }
      if (href.includes("/cancel")) {
        onCancel();
        return new Response(null, { status: 200 });
      }
      if (href.includes("/agentstore/")) return new Response("{}", { status: 200 });
      if (href.includes("/stream")) {
        // Opens, emits one token, then stays open indefinitely.
        return new Response(
          new ReadableStream({
            start(c) {
              c.enqueue(new TextEncoder().encode("event: token\ndata: thinking…\n\n"));
            },
          }),
          { status: 200, headers: { "Content-Type": "text/event-stream" } },
        );
      }
      return new Response(
        JSON.stringify({ conversationState: "READY", conversationSteps: [] }),
        { status: 200 },
      );
    }) as typeof fetch;
  }

  it("offers a stop control while the agent is responding, and cancels server-side", async () => {
    // An unbounded SSE reader with no stop control means a runaway turn can
    // only be escaped by reloading the page.
    let cancelled = false;
    mockHangingStream(() => {
      cancelled = true;
    });

    renderWidget();
    const input = await screen.findByTestId("chat-input");

    fireEvent.change(input, { target: { value: "hello" } });
    fireEvent.keyDown(input, { key: "Enter", shiftKey: false });

    const stop = await screen.findByTestId("chat-stop");
    fireEvent.click(stop);

    await waitFor(() => expect(cancelled).toBe(true));
  });
});

describe("ChatWidget — a turn that pauses vs a turn that is dropped", () => {
  function mockStreamingBackend(donePayload: string, initialState = "READY") {
    globalThis.fetch = vi.fn(async (url: string | URL | Request) => {
      const href = String(url);
      if (href.includes("/start")) {
        return new Response(null, { status: 201, headers: { Location: "/agents/conv-1" } });
      }
      if (href.includes("/agentstore/")) return new Response("{}", { status: 200 });
      if (href.includes("/approval-status")) {
        return new Response(
          JSON.stringify({
            conversationId: "conv-1",
            state: "AWAITING_HUMAN",
            pausedAt: "2026-07-21T10:00:00Z",
            pauseReason: "manager approval required",
            timeoutPolicy: "AUTO_REJECT",
            approvalTimeout: "PT15M",
            pauseDetails: null,
          }),
          { status: 200 },
        );
      }
      if (href.includes("/stream")) {
        return new Response(
          new ReadableStream({
            start(c) {
              c.enqueue(new TextEncoder().encode(`event: done\ndata: ${donePayload}\n\n`));
              c.close();
            },
          }),
          { status: 200, headers: { "Content-Type": "text/event-stream" } },
        );
      }
      return new Response(
        JSON.stringify({ conversationState: initialState, conversationSteps: [] }),
        { status: 200 },
      );
    }) as typeof fetch;
  }

  async function send(text: string) {
    const input = await screen.findByTestId("chat-input");
    fireEvent.change(input, { target: { value: text } });
    fireEvent.keyDown(input, { key: "Enter", shiftKey: false });
  }

  it("shows the approval placeholder when THIS turn pauses, not 'was not sent'", async () => {
    // The turn was accepted and then gated. Claiming it was not sent is a lie,
    // and the placeholder arrives as a BARE STRING in the done payload.
    mockStreamingBackend(
      JSON.stringify({
        conversationState: "AWAITING_HUMAN",
        conversationOutputs: [
          { output: ["Waiting for approval to send the email."], quickReplies: [] },
        ],
      }),
    );

    renderWidget();
    await send("email bob");

    expect(
      await screen.findByText("Waiting for approval to send the email."),
    ).toBeInTheDocument();
    expect(screen.queryByText(/was not sent/i)).toBeNull();
  });

  it("replaces streamed text that responseValidation superseded", async () => {
    globalThis.fetch = vi.fn(async (url: string | URL | Request) => {
      const href = String(url);
      if (href.includes("/start")) {
        return new Response(null, { status: 201, headers: { Location: "/agents/conv-1" } });
      }
      if (href.includes("/agentstore/")) return new Response("{}", { status: 200 });
      if (href.includes("/stream")) {
        return new Response(
          new ReadableStream({
            start(c) {
              const enc = new TextEncoder();
              c.enqueue(enc.encode("event: token\ndata: secret leaked text\n\n"));
              c.enqueue(
                enc.encode(
                  `event: done\ndata: ${JSON.stringify({
                    conversationState: "READY",
                    conversationOutputs: [
                      { output: [{ type: "text", text: "I could not complete that." }], quickReplies: [] },
                    ],
                  })}\n\n`,
                ),
              );
              c.close();
            },
          }),
          { status: 200, headers: { "Content-Type": "text/event-stream" } },
        );
      }
      return new Response(
        JSON.stringify({ conversationState: "READY", conversationSteps: [] }),
        { status: 200 },
      );
    }) as typeof fetch;

    renderWidget();
    await send("tell me a secret");

    expect(await screen.findByText("I could not complete that.")).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.queryByText("secret leaked text")).toBeNull();
    });
  });
});

describe("ChatWidget — recovering from a stuck conversation", () => {
  function mockStateBackend(conversationState: string, onRerun?: () => void) {
    globalThis.fetch = vi.fn(async (url: string | URL | Request) => {
      const href = String(url);
      if (href.includes("/start")) {
        return new Response(null, { status: 201, headers: { Location: "/agents/conv-1" } });
      }
      if (href.includes("/agentstore/")) return new Response("{}", { status: 200 });
      if (href.includes("/rerun")) {
        onRerun?.();
        return new Response(
          JSON.stringify({ conversationState: "READY", conversationSteps: [] }),
          { status: 200 },
        );
      }
      return new Response(
        JSON.stringify({ conversationState, conversationSteps: [] }),
        { status: 200 },
      );
    }) as typeof fetch;
  }

  it.each(["ERROR", "EXECUTION_INTERRUPTED"])(
    "offers a way out of %s instead of leaving a dead end",
    async (conversationState) => {
      mockStateBackend(conversationState);

      renderWidget();

      expect(await screen.findByTestId("recovery-banner")).toBeInTheDocument();
      expect(screen.getByTestId("recovery-retry")).toBeInTheDocument();
    },
  );

  it("retries the failed step when the user asks", async () => {
    let retried = false;
    mockStateBackend("ERROR", () => {
      retried = true;
    });

    renderWidget();
    fireEvent.click(await screen.findByTestId("recovery-retry"));

    await waitFor(() => expect(retried).toBe(true));
  });

  it("shows no recovery banner for a healthy conversation", async () => {
    mockStateBackend("READY");

    renderWidget();
    await screen.findByTestId("chat-input");

    expect(screen.queryByTestId("recovery-banner")).toBeNull();
  });
});

describe("ChatWidget — managed-agent route", () => {
  function renderManaged() {
    return render(
      <MemoryRouter initialEntries={["/chat/managed/support/user-7"]}>
        <ChatProvider>
          <Routes>
            <Route path="/chat/managed/:intent/:userId" element={<ChatWidget />} />
          </Routes>
        </ChatProvider>
      </MemoryRouter>,
    );
  }

  it("adopts the conversationId the snapshot carries", async () => {
    // Managed mode never calls startConversation, so without this the widget
    // has no conversationId — which disables HITL polling, cancel, retry and
    // attachments for the whole managed route.
    globalThis.fetch = vi.fn(async (url: string | URL | Request) => {
      if (String(url).includes("/agentstore/")) return new Response("{}", { status: 200 });
      return new Response(
        JSON.stringify({
          conversationId: "managed-conv-9",
          conversationState: "READY",
          conversationSteps: [{ output: "Hi from managed" }],
        }),
        { status: 200 },
      );
    }) as typeof fetch;

    renderManaged();

    await screen.findByText("Hi from managed");
    // The action bar renders only once a conversationId is known.
    await waitFor(() => {
      expect(screen.getByTestId("restart-btn")).toBeInTheDocument();
    });
    // And the attach button is no longer inert.
    expect(screen.getByTestId("chat-attach-btn")).not.toBeDisabled();
  });
});

describe("ChatWidget — round-3 regressions", () => {
  it("shows the recovery banner after a failed STREAMING turn", async () => {
    // A failed stream sends `error` then closes — no `done`. Nothing else on
    // this path learned the conversation was now ERROR, so the Try again
    // button was unreachable on the default transport.
    let reads = 0;
    globalThis.fetch = vi.fn(async (url: string | URL | Request) => {
      const href = String(url);
      if (href.includes("/start")) {
        return new Response(null, { status: 201, headers: { Location: "/agents/conv-1" } });
      }
      if (href.includes("/agentstore/")) return new Response("{}", { status: 200 });
      if (href.includes("/stream")) {
        return new Response(
          new ReadableStream({
            start(c) {
              c.enqueue(
                new TextEncoder().encode(
                  'event: error\ndata: {"message":"LLM provider unavailable"}\n\n',
                ),
              );
              c.close();
            },
          }),
          { status: 200, headers: { "Content-Type": "text/event-stream" } },
        );
      }
      reads += 1;
      return new Response(
        JSON.stringify({
          conversationState: reads === 1 ? "READY" : "ERROR",
          conversationSteps: [],
        }),
        { status: 200 },
      );
    }) as typeof fetch;

    renderWidget();
    const input = await screen.findByTestId("chat-input");
    fireEvent.change(input, { target: { value: "hello" } });
    fireEvent.keyDown(input, { key: "Enter", shiftKey: false });

    expect(await screen.findByTestId("recovery-banner")).toBeInTheDocument();
  });

  it("sends attachments on the managed-agent route", async () => {
    // The managed branch dropped `context` entirely, so the file never reached
    // the model while the transcript claimed it had been sent.
    const bodies: string[] = [];
    globalThis.fetch = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const href = String(url);
      if (href.includes("/agentstore/")) return new Response("{}", { status: 200 });
      if (href.includes("/attachments")) {
        return new Response(
          '{"storageRef":"ref-1","fileName":"a.pdf","mimeType":"application/pdf","sizeBytes":3}',
          { status: 201 },
        );
      }
      if (init?.method === "POST") bodies.push(String(init.body));
      return new Response(
        JSON.stringify({
          conversationId: "managed-conv-9",
          conversationState: "READY",
          conversationSteps: [],
        }),
        { status: 200 },
      );
    }) as typeof fetch;

    render(
      <MemoryRouter initialEntries={["/chat/managed/support/user-7"]}>
        <ChatProvider>
          <Routes>
            <Route path="/chat/managed/:intent/:userId" element={<ChatWidget />} />
          </Routes>
        </ChatProvider>
      </MemoryRouter>,
    );

    const fileInput = await screen.findByTestId("chat-file-input");
    fireEvent.change(fileInput, {
      target: { files: [new File(["abc"], "a.pdf", { type: "application/pdf" })] },
    });
    await screen.findByTestId("attachment-chip");

    fireEvent.click(screen.getByTestId("chat-send"));

    await waitFor(() => expect(bodies.length).toBeGreaterThan(0));
    expect(JSON.parse(bodies[0]).context.attachment_0.value.storageRef).toBe("ref-1");
  });

  it("does not put a secret back into the unmasked composer after a 409", async () => {
    globalThis.fetch = vi.fn(async (url: string | URL | Request) => {
      const href = String(url);
      if (href.includes("/start")) {
        return new Response(null, { status: 201, headers: { Location: "/agents/conv-1" } });
      }
      if (href.includes("/agentstore/")) return new Response("{}", { status: 200 });
      if (href.includes("/stream")) {
        return new Response("a reviewer must resolve the pending approval", { status: 409 });
      }
      return new Response(
        JSON.stringify({ conversationState: "READY", conversationSteps: [] }),
        { status: 200 },
      );
    }) as typeof fetch;

    renderWidget();
    // Turn on secret mode, then send.
    fireEvent.click(await screen.findByTestId("chat-secret-toggle"));
    const input = await screen.findByTestId("chat-input");
    fireEvent.change(input, { target: { value: "hunter2" } });
    fireEvent.keyDown(input, { key: "Enter", shiftKey: false });

    await screen.findByText(/reviewer must resolve/i);
    // Let the restore effect run before asserting — checking immediately after
    // the error text appears races it, and the assertion passes vacuously.
    await waitFor(() => {});

    const composer = screen.getByTestId("chat-input") as HTMLTextAreaElement;
    expect(composer.value).toBe("");
    expect(screen.queryByText("hunter2")).toBeNull();
  });
});

describe("ChatWidget — round-4 regressions", () => {
  /** Non-streaming backend returning a scripted reply per POST. */
  function mockNonStreaming(replies: string[]) {
    let turn = 0;
    globalThis.fetch = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const href = String(url);
      if (href.includes("/start")) {
        return new Response(null, { status: 201, headers: { Location: "/agents/conv-1" } });
      }
      if (href.includes("/agentstore/")) return new Response("{}", { status: 200 });
      if (init?.method === "POST") {
        const text = replies[Math.min(turn, replies.length - 1)];
        turn += 1;
        return new Response(
          JSON.stringify({
            conversationState: "READY",
            conversationOutputs: [{ output: [text], quickReplies: [] }],
          }),
          { status: 200 },
        );
      }
      return new Response(
        JSON.stringify({ conversationState: "READY", conversationSteps: [] }),
        { status: 200 },
      );
    }) as typeof fetch;
  }

  function renderNonStreaming() {
    return render(
      <MemoryRouter initialEntries={["/chat/production/agent-1?hideStreaming=true"]}>
        <ChatProvider>
          <Routes>
            <Route path="/chat/:environment/:agentId" element={<ChatWidget />} />
          </Routes>
        </ChatProvider>
      </MemoryRouter>,
    );
  }

  async function send(text: string) {
    const input = await screen.findByTestId("chat-input");
    fireEvent.change(input, { target: { value: text } });
    fireEvent.keyDown(input, { key: "Enter", shiftKey: false });
  }

  it("renders a repeated agent reply on every turn, not just the first", async () => {
    // returnCurrentStepOnly=true pins outputIndex to 0, so the dedupe key
    // degenerated to the reply text. A repeated fallback — the single most
    // common agent utterance — was silently swallowed from the second turn on.
    mockNonStreaming(["Sorry, I did not understand that."]);

    renderNonStreaming();
    await send("asdf");
    await screen.findByText("Sorry, I did not understand that.");

    await send("qwer");

    await waitFor(() => {
      expect(screen.getAllByText("Sorry, I did not understand that.")).toHaveLength(2);
    });
  });

  it("renders a second, different reply that shares a long prefix with the first", async () => {
    const a = "Thank you for your question. Based on the information available, I found 3 results.";
    const b = "Thank you for your question. Based on the information available, I found 7 results.";
    mockNonStreaming([a, b]);

    renderNonStreaming();
    await send("one");
    await screen.findByText(a);

    await send("two");

    expect(await screen.findByText(b)).toBeInTheDocument();
  });
});
