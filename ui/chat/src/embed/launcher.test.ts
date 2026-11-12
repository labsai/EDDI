import { describe, it, expect, vi, afterEach, beforeEach } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  DEFAULT_LABELS,
  MSG_READY,
  MSG_TOKEN,
  MSG_TOKEN_REQUEST,
  isPlausibleToken,
  isSafeColor,
  mountLauncher,
  readConfig,
  widgetLocale,
  widgetUrl,
  type Launcher,
  type LauncherConfig,
} from "./launcher";
import { isSafeCssColor } from "@/components/ChatWidget";

/** T2.9 — `/chat/embed.js`: the floating button that opens the widget on a host page. */

const EDDI = "https://eddi.example.com";

function script(data: Record<string, string>, src = `${EDDI}/chat/embed.js`) {
  return { src, dataset: data as DOMStringMap };
}

function config(over: Partial<LauncherConfig> = {}): LauncherConfig {
  return {
    eddiOrigin: EDDI,
    agentId: "agent-1",
    environment: "production",
    position: "right",
    color: null,
    label: "Chat",
    closeLabel: "Close chat",
    open: false,
    lang: null,
    theme: null,
    tokenProvider: null,
    ...over,
  };
}

let launcher: Launcher | null = null;
function mount(over: Partial<LauncherConfig> = {}) {
  launcher = mountLauncher(config(over), window);
  if (!launcher) throw new Error("not mounted");
  const q = (id: string) => launcher!.root.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  return {
    launcher,
    button: q("launcher")!,
    panel: q("panel")!,
    close: q("close")!,
    frame: () => q("frame") as HTMLIFrameElement | null,
    q,
  };
}

/** Deliver a message to the page as if `source` at `origin` posted it. */
function deliver(data: unknown, origin: string, source: MessageEventSource | null) {
  window.dispatchEvent(new MessageEvent("message", { data, origin, source }));
}

const flush = () => new Promise((r) => setTimeout(r, 0));

beforeEach(() => {
  vi.spyOn(console, "warn").mockImplementation(() => {});
});

afterEach(() => {
  launcher?.destroy();
  launcher = null;
  delete (window as unknown as Record<string, unknown>).getEddiToken;
  vi.restoreAllMocks();
});

describe("readConfig", () => {
  const win = { navigator: { language: "en-US" }, document: { documentElement: { lang: "" } } } as never;

  it("takes EDDI's origin from the script's own URL, and the settings from data- attributes", () => {
    const cfg = readConfig(
      script({
        agentId: "abc-123",
        environment: "test",
        position: "bottom-left",
        color: "#ff0000",
        label: "Ask us",
        open: "true",
        lang: "de-AT",
        theme: "dark",
        tokenProvider: "getEddiToken",
      }),
      win,
    );
    expect(cfg).toEqual({
      eddiOrigin: EDDI,
      agentId: "abc-123",
      environment: "test",
      position: "left",
      color: "#ff0000",
      label: "Ask us",
      closeLabel: "Chat schließen",
      open: true,
      lang: "de",
      theme: "dark",
      tokenProvider: "getEddiToken",
    });
  });

  it("defaults to production, the right corner, the page's language and closed", () => {
    const cfg = readConfig(script({ agentId: "a" }), {
      navigator: { language: "en-US" },
      document: { documentElement: { lang: "fr" } },
    } as never)!;
    expect(cfg).toMatchObject({ environment: "production", position: "right", open: false, label: "Discuter", lang: null });
  });

  it("draws nothing without a usable agent id or script URL", () => {
    expect(readConfig(script({}), win)).toBeNull();
    expect(readConfig(script({ agentId: "../admin" }), win)).toBeNull();
    expect(readConfig(script({ agentId: "a" }, "javascript:alert(1)"), win)).toBeNull();
    expect(readConfig(script({ agentId: "a" }, "not a url"), win)).toBeNull();
  });

  it("drops what is not a plain colour, a theme, an environment or a function name", () => {
    const cfg = readConfig(
      script({
        agentId: "a",
        color: "url(https://tracker/x)",
        theme: "neon",
        environment: "../x",
        tokenProvider: "alert(1)",
      }),
      win,
    )!;
    expect(cfg).toMatchObject({ color: null, theme: null, environment: "production", tokenProvider: null });
  });

  it("checks colours exactly as the widget does", () => {
    for (const c of ["#fff", "#11223344", "rgb(1, 2, 3)", "hsl(10deg 20% 30%)", "rebeccapurple", "url(x)", "red;x", "", "#12", "var(--x)", "a".repeat(70)]) {
      expect(isSafeColor(c), c).toBe(isSafeCssColor(c));
    }
  });

  it("knows a default label for each of the widget's eleven locales", () => {
    expect(Object.keys(DEFAULT_LABELS).sort()).toEqual(["ar", "de", "en", "es", "fr", "hi", "ja", "ko", "pt", "th", "zh"]);
    expect(widgetLocale("pt-BR")).toBe("pt");
    expect(widgetLocale("xx")).toBeNull();
  });
});

describe("widgetUrl", () => {
  it("frames the agent's widget with its display settings", () => {
    const url = new URL(widgetUrl(config({ color: "#00f", lang: "ar", theme: "light" }), "https://shop.example", false));
    expect(url.origin + url.pathname).toBe(`${EDDI}/chat/production/agent-1`);
    expect(url.searchParams.get("accentColor")).toBe("#00f");
    expect(url.searchParams.get("lang")).toBe("ar");
    expect(url.searchParams.get("theme")).toBe("light");
    expect(url.searchParams.has("tokenOrigin")).toBe(false);
  });

  it("switches the token hand-off on only with a provider, for this page's exact origin", () => {
    expect(new URL(widgetUrl(config(), "https://shop.example", true)).searchParams.get("tokenOrigin")).toBe("https://shop.example");
    expect(new URL(widgetUrl(config(), "null", true)).searchParams.has("tokenOrigin")).toBe(false);
  });
});

describe("mountLauncher — the button and the panel", () => {
  it("draws one labelled button, closed, and no iframe until it is opened", () => {
    const { button, panel, frame } = mount();
    expect(button.tagName).toBe("BUTTON");
    expect(button).toHaveTextContent("Chat");
    expect(button).toHaveAttribute("aria-expanded", "false");
    expect(button).toHaveAttribute("aria-controls", panel.id);
    expect(panel.hidden).toBe(true);
    expect(frame()).toBeNull();
    // A second copy of the script draws no second button.
    expect(mountLauncher(config(), window)).toBeNull();
  });

  it("opens the panel as a labelled dialog that takes focus, with the widget in it", () => {
    const { button, panel, frame, launcher: l } = mount({ label: "Ask us" });
    button.click();
    expect(panel.hidden).toBe(false);
    expect(button).toHaveAttribute("aria-expanded", "true");
    expect(panel).toHaveAttribute("role", "dialog");
    expect(panel).toHaveAttribute("aria-label", "Ask us");
    expect(l.root.activeElement).toBe(panel);
    expect(frame()!.src).toBe(`${EDDI}/chat/production/agent-1`);
    expect(frame()).toHaveAttribute("title", "Ask us");
  });

  it("closes on Esc and gives focus back to the button", () => {
    const { button, panel, launcher: l } = mount();
    button.click();
    panel.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
    expect(panel.hidden).toBe(true);
    expect(button).toHaveAttribute("aria-expanded", "false");
    expect(l.root.activeElement).toBe(button);
  });

  it("closes from its close button, named for the locale, and gives focus back", () => {
    const { button, panel, close, launcher: l } = mount();
    button.click();
    expect(close).toHaveAttribute("aria-label", "Close chat");
    close.click();
    expect(panel.hidden).toBe(true);
    expect(l.root.activeElement).toBe(button);
  });

  it("keeps Tab inside the open dialog: past the end back to the close button, before the start to the widget", () => {
    const { button, close, frame, q, launcher: l } = mount();
    button.click();
    q("guard-end")!.focus();
    expect(l.root.activeElement).toBe(close);
    q("guard-start")!.focus();
    expect(l.root.activeElement).toBe(frame());
  });

  it("opens by default without taking the page's focus", () => {
    const outside = document.createElement("button");
    document.body.appendChild(outside);
    outside.focus();
    const { panel } = mount({ open: true });
    expect(panel.hidden).toBe(false);
    expect(document.activeElement).toBe(outside);
    outside.remove();
  });

  it("stays closed by default on a phone, where the panel covers the page", () => {
    const mm = vi.spyOn(window, "matchMedia").mockImplementation(
      (q: string) => ({ matches: q.includes("max-width"), media: q }) as MediaQueryList,
    );
    const { panel } = mount({ open: true });
    expect(panel.hidden).toBe(true);
    mm.mockRestore();
  });

  it("sizes for phones and respects reduced motion in its stylesheet", () => {
    const { q, launcher: l } = mount();
    void q;
    const css = l.root.querySelector("style")!.textContent!;
    expect(css).toMatch(/@media \(max-width:480px\)\{\.panel\{inset:0/);
    expect(css).toMatch(/@media \(prefers-reduced-motion:no-preference\)\{\.panel\{animation/);
    expect(css).not.toMatch(/\.panel\{[^}]*transition/);
  });
});

describe("mountLauncher — the token hand-off", () => {
  async function openWithProvider(provider: () => unknown) {
    (window as unknown as Record<string, unknown>).getEddiToken = provider;
    const parts = mount({ tokenProvider: "getEddiToken" });
    parts.button.click();
    const frame = parts.frame()!;
    expect(new URL(frame.src).searchParams.get("tokenOrigin")).toBe(window.location.origin);
    const post = vi.spyOn(frame.contentWindow!, "postMessage");
    return { ...parts, frameWin: frame.contentWindow!, post };
  }

  it("answers the widget's ready and token-request with the host's token, posted to EDDI's exact origin", async () => {
    const { frameWin, post } = await openWithProvider(() => Promise.resolve("tok.en.1"));
    deliver({ type: MSG_READY, protocol: 1 }, EDDI, frameWin);
    await flush();
    expect(post).toHaveBeenCalledWith({ type: MSG_TOKEN, token: "tok.en.1" }, EDDI);
    deliver({ type: MSG_TOKEN_REQUEST, protocol: 1 }, EDDI, frameWin);
    await flush();
    expect(post).toHaveBeenCalledTimes(2);
    expect(post.mock.calls.every((call) => (call as unknown[])[1] === EDDI)).toBe(true);
  });

  it("ignores a message from another origin, even from its own frame", async () => {
    const provider = vi.fn(() => "tok");
    const { frameWin, post } = await openWithProvider(provider);
    deliver({ type: MSG_READY }, "https://evil.example", frameWin);
    deliver({ type: MSG_READY }, `${EDDI}.evil.example`, frameWin);
    deliver({ type: MSG_READY }, "null", frameWin);
    await flush();
    expect(provider).not.toHaveBeenCalled();
    expect(post).not.toHaveBeenCalled();
  });

  it("ignores EDDI's origin from any window but its own frame", async () => {
    const provider = vi.fn(() => "tok");
    const { post } = await openWithProvider(provider);
    const other = document.createElement("iframe");
    document.body.appendChild(other);
    deliver({ type: MSG_READY }, EDDI, other.contentWindow);
    deliver({ type: MSG_READY }, EDDI, window);
    deliver({ type: MSG_READY }, EDDI, null);
    await flush();
    expect(provider).not.toHaveBeenCalled();
    expect(post).not.toHaveBeenCalled();
    other.remove();
  });

  it("ignores other message types and sends nothing that is not a plausible token", async () => {
    const { frameWin, post } = await openWithProvider(() => "has space");
    deliver({ type: "eddi-chat-token" }, EDDI, frameWin);
    deliver("eddi-chat-ready", EDDI, frameWin);
    deliver({ type: MSG_READY }, EDDI, frameWin);
    await flush();
    expect(post).not.toHaveBeenCalled();
  });

  it("frames the widget without the hand-off, and answers nothing, when no provider is given", () => {
    const { button, frame } = mount();
    button.click();
    expect(new URL(frame()!.src).searchParams.has("tokenOrigin")).toBe(false);
    const post = vi.spyOn(frame()!.contentWindow!, "postMessage");
    deliver({ type: MSG_READY }, EDDI, frame()!.contentWindow);
    expect(post).not.toHaveBeenCalled();
  });

  it("takes a provider set through the API before the panel first opens", async () => {
    const { launcher: l, button, frame } = mount();
    l.setTokenProvider(() => "api-token");
    button.click();
    const post = vi.spyOn(frame()!.contentWindow!, "postMessage");
    deliver({ type: MSG_TOKEN_REQUEST }, EDDI, frame()!.contentWindow);
    await flush();
    expect(post).toHaveBeenCalledWith({ type: MSG_TOKEN, token: "api-token" }, EDDI);
  });

  it("accepts tokens as the widget does", () => {
    expect(isPlausibleToken("a.b.c")).toBe(true);
    expect(isPlausibleToken("")).toBe(false);
    expect(isPlausibleToken("a b")).toBe(false);
    expect(isPlausibleToken("x".repeat(16_385))).toBe(false);
    expect(isPlausibleToken(42)).toBe(false);
  });
});

describe("the launcher's source", () => {
  const source = ["launcher.ts", "embed.ts"]
    .map((f) => readFileSync(resolve(import.meta.dirname, f), "utf8"))
    .join("\n")
    // Comments may name what the code must never do.
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/\/\/.*$/gm, "");

  it("never reads cookies or storage, never evaluates strings, never writes HTML", () => {
    for (const banned of ["document.cookie", "localStorage", "sessionStorage", "eval(", "new Function", "innerHTML", "outerHTML", "insertAdjacentHTML", "document.write"]) {
      expect(source, banned).not.toContain(banned);
    }
  });

  it("never posts to a wildcard target", () => {
    expect(source).not.toMatch(/postMessage\([^)]*["']\*["']/);
  });

  it("imports nothing: it is one standalone script", () => {
    const imports = [...source.matchAll(/^import .* from ["'](.+)["']/gm)].map((m) => m[1]);
    expect(imports.filter((i) => i !== "./launcher")).toEqual([]);
  });
});
