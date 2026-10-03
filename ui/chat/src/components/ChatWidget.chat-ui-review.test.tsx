/* ──────────────────────────────────────────────
   ChatWidget — regressions from the 2026-10-02 review (Chat UI row):
   the generation guard on refused turns, the embedding token hand-off, and
   the ?theme= / colour parameters.
   ────────────────────────────────────────────── */

import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import {
  ChatWidget,
  applyColorOverrides,
  isSafeCssColor,
  isSafeFontFamily,
  parseConfigFromQuery,
} from "./ChatWidget";
import { ChatProvider } from "@/store/chat-store";
import { setAuthToken } from "@/api/http";
import { MSG_READY, MSG_TOKEN, MSG_TOKEN_REQUEST } from "@/api/embed-auth";

const originalFetch = globalThis.fetch;

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  vi.spyOn(console, "warn").mockImplementation(() => {});
  setAuthToken(null);
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.restoreAllMocks();
  setAuthToken(null);
  document.documentElement.removeAttribute("style");
});

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ChatProvider>
        <Routes>
          <Route path="/chat/:environment/:agentId" element={<ChatWidget />} />
        </Routes>
      </ChatProvider>
    </MemoryRouter>,
  );
}

const READY = JSON.stringify({ conversationState: "READY", conversationSteps: [] });

/**
 * A backend whose first turn hangs until released, then answers `status`.
 * Starts number conversations conv-1, conv-2, …
 */
function backendWithHeldTurn(status: number, body: string) {
  let release: () => void = () => {};
  let started = 0;
  globalThis.fetch = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/start")) {
      started += 1;
      return new Response(null, { status: 201, headers: { Location: `/agents/conv-${started}` } });
    }
    if (url.includes("/stream") && (init?.method ?? "GET") === "POST") {
      await new Promise<void>((r) => {
        release = r;
      });
      return new Response(body, { status });
    }
    return new Response(READY, { status: 200 });
  }) as typeof fetch;
  return { release: () => release() };
}

async function send(text: string) {
  const input = await screen.findByTestId("chat-input");
  await waitFor(() => expect(input).not.toBeDisabled());
  fireEvent.change(input, { target: { value: text } });
  fireEvent.keyDown(input, { key: "Enter", shiftKey: false });
}

describe("a refused turn that lands after New conversation", () => {
  // The 409 / 401 / 403 branches withdrew the bubble, restored the draft and
  // posted a warning without checking the generation, so a refusal for the
  // abandoned turn was written into the conversation that replaced it.
  it.each([
    [409, "a reviewer must resolve the pending approval", /reviewer must resolve/],
    [401, "", /not allowed to continue/],
    [403, "", /not allowed to continue/],
  ])("does not write a %i into the new conversation", async (status, body, warning) => {
    const held = backendWithHeldTurn(status, body);
    renderAt("/chat/production/agent-1");

    await send("first conversation's message");
    await waitFor(() =>
      expect(globalThis.fetch).toHaveBeenCalledWith(
        expect.stringContaining("/agents/conv-1/stream"),
        expect.anything(),
      ),
    );
    fireEvent.click(screen.getByTestId("restart-btn"));
    await waitFor(() =>
      expect(globalThis.fetch).toHaveBeenCalledWith(
        expect.stringContaining("/agent-1/start"),
        expect.anything(),
      ),
    );
    await waitFor(() => expect(screen.getByTestId("chat-input")).not.toBeDisabled());

    held.release();
    await new Promise((r) => setTimeout(r, 30));

    expect(screen.queryByText(warning)).not.toBeInTheDocument();
    // The withdrawn turn's text is not handed back to the new composer.
    expect(screen.getByTestId("chat-input")).toHaveValue("");
  });
});

describe("a Stop whose cancel lands after New conversation", () => {
  it("does not mark the new conversation interrupted", async () => {
    let releaseCancel: () => void = () => {};
    let started = 0;
    globalThis.fetch = vi.fn(async (input: string | URL | Request) => {
      const url = String(input);
      if (url.includes("/start")) {
        started += 1;
        return new Response(null, { status: 201, headers: { Location: `/agents/conv-${started}` } });
      }
      if (url.includes("/stream")) {
        // A stream that never ends on its own.
        return new Response(new ReadableStream({ start() {} }), {
          status: 200,
          headers: { "Content-Type": "text/event-stream" },
        });
      }
      if (url.includes("/cancel")) {
        await new Promise<void>((r) => {
          releaseCancel = r;
        });
        return new Response(null, { status: 200 });
      }
      return new Response(READY, { status: 200 });
    }) as typeof fetch;
    renderAt("/chat/production/agent-1");

    await send("long question");
    fireEvent.click(await screen.findByTestId("chat-stop"));
    fireEvent.click(screen.getByTestId("restart-btn"));
    await waitFor(() => expect(started).toBe(2));
    await waitFor(() => expect(screen.getByTestId("chat-input")).not.toBeDisabled());

    releaseCancel();
    await new Promise((r) => setTimeout(r, 30));

    expect(screen.queryByTestId("recovery-banner")).not.toBeInTheDocument();
  });
});

describe("the embedding token hand-off (?tokenOrigin=)", () => {
  const HOST = "https://portal.example";
  let posted: Array<{ data: { type: string }; origin: string }>;
  let fakeParent: Window;

  beforeEach(() => {
    posted = [];
    fakeParent = {
      postMessage: (data: { type: string }, origin: string) => posted.push({ data, origin }),
    } as unknown as Window;
    Object.defineProperty(window, "parent", { value: fakeParent, configurable: true });
  });

  afterEach(() => {
    Object.defineProperty(window, "parent", { value: window, configurable: true });
  });

  function hostSends(data: unknown, origin = HOST) {
    const ev = new MessageEvent("message", { data, origin });
    Object.defineProperty(ev, "source", { value: fakeParent });
    window.dispatchEvent(ev);
  }

  it("starts the conversation with the host's token, even though the first attempt beat it", async () => {
    // With auth on, the widget's first request used to go out anonymously, be
    // refused, and end in "not allowed" — the token the host posted a moment
    // later was installed but never used for the start.
    const auths: Array<string | null> = [];
    globalThis.fetch = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const url = String(input);
      const auth = new Headers(init?.headers).get("Authorization");
      if (url.includes("/start")) auths.push(auth);
      if (auth !== "Bearer host-token") return new Response("", { status: 401 });
      if (url.includes("/start")) {
        return new Response(null, { status: 201, headers: { Location: "/agents/conv-1" } });
      }
      return new Response(
        JSON.stringify({
          conversationState: "READY",
          conversationOutputs: [{ output: [{ type: "text", text: "Hello, signed-in user" }] }],
        }),
        { status: 200 },
      );
    }) as typeof fetch;

    renderAt(`/chat/production/agent-1?tokenOrigin=${encodeURIComponent(HOST)}`);

    await waitFor(() => expect(posted.some((p) => p.data.type === MSG_READY)).toBe(true));
    expect(posted.every((p) => p.origin === HOST)).toBe(true);
    await waitFor(() =>
      expect(posted.some((p) => p.data.type === MSG_TOKEN_REQUEST)).toBe(true),
    );

    hostSends({ type: MSG_TOKEN, token: "host-token" });

    expect(await screen.findByText("Hello, signed-in user")).toBeInTheDocument();
    expect(auths).toEqual([null, "Bearer host-token"]);
    expect(screen.queryByText(/not allowed/)).not.toBeInTheDocument();
  });

  it("ignores a token posted from an origin that is not allow-listed", async () => {
    const auths: Array<string | null> = [];
    globalThis.fetch = vi.fn(async (_input: string | URL | Request, init?: RequestInit) => {
      auths.push(new Headers(init?.headers).get("Authorization"));
      return new Response(READY, { status: 200 });
    }) as typeof fetch;
    renderAt(`/chat/production/agent-1?tokenOrigin=${encodeURIComponent(HOST)}`);
    await waitFor(() => expect(posted.some((p) => p.data.type === MSG_READY)).toBe(true));

    hostSends({ type: MSG_TOKEN, token: "evil-token" }, "https://evil.example");
    await new Promise((r) => setTimeout(r, 20));

    expect(auths.length).toBeGreaterThan(0);
    expect(auths).not.toContain("Bearer evil-token");
  });
});

describe("?theme= and the colour parameters", () => {
  it("accepts only the three theme modes", () => {
    const theme = (v: string) => parseConfigFromQuery(new URLSearchParams({ theme: v })).theme;
    expect(theme("light")).toBe("light");
    expect(theme("system")).toBe("system");
    expect(theme("neon")).toBeUndefined();
    expect(theme('dark"] body{')).toBeUndefined();
  });

  it("accepts a valid accentColor and drops anything else", () => {
    const accent = (v: string) =>
      parseConfigFromQuery(new URLSearchParams({ accentColor: v })).accentColor;
    expect(accent("#00ff88")).toBe("#00ff88");
    expect(accent("url(https://tracker.example/x)")).toBeUndefined();
  });

  it("recognises plain colours and refuses URLs, functions and injection", () => {
    for (const ok of ["#fff", "#ffff", "#00ff88", "#00ff8880", "rgb(0, 128, 255)", "hsla(120 50% 50% / 0.5)", "rebeccapurple", "transparent"]) {
      expect(isSafeCssColor(ok)).toBe(true);
    }
    for (const bad of [
      "url(https://tracker.example/x.png)",
      "red; background: url(//x)",
      "var(--chat-accent)",
      "rgb(calc(1),0,0)",
      "#12345",
      "image-set(\"x.png\" 1x)",
      "",
    ]) {
      expect(isSafeCssColor(bad)).toBe(false);
    }
    expect(isSafeFontFamily("'Noto Sans', sans-serif")).toBe(true);
    expect(isSafeFontFamily("x, url(https://tracker.example/f.woff2)")).toBe(false);
  });

  it("applies a valid colour and ignores an unsafe one", () => {
    applyColorOverrides(
      new URLSearchParams({
        accentColor: "#123",
        bgColor: "url(https://tracker.example/x.png)",
        fontFamily: "Inter, sans-serif",
      }),
    );
    const style = document.documentElement.style;
    expect(style.getPropertyValue("--chat-accent")).toBe("#123");
    // Derived variants are built from the expanded six-digit form.
    expect(style.getPropertyValue("--chat-accent-soft")).toBe("#11223322");
    expect(style.getPropertyValue("--chat-bg")).toBe("");
    expect(style.getPropertyValue("--chat-font")).toBe("Inter, sans-serif");
  });

  it("derives no alpha variants from a non-hex accent", () => {
    applyColorOverrides(new URLSearchParams({ accentColor: "rebeccapurple" }));
    const style = document.documentElement.style;
    expect(style.getPropertyValue("--chat-accent")).toBe("rebeccapurple");
    expect(style.getPropertyValue("--chat-accent-soft")).toBe("");
  });
});
