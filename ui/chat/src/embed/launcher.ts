/* ──────────────────────────────────────────────
   EDDI Chat — the embed launcher (frontend unification plan, T2.9)

     <script src="https://eddi.example.com/chat/embed.js"
             data-agent-id="…" defer></script>

   draws a floating button on the host page that opens the `/chat` widget in
   an iframe panel. Framework-free and standalone: its own build output
   (`vite.embed.config.ts` → `dist/scripts/js/chat-ui-embed.js`), served by
   EDDI at `/chat/embed.js`, and nothing of the widget's bundle.

   Contract (docs/chat-ui-embedding.md, "The launcher"):
   - Config comes from `data-` attributes on its own <script>: agent id,
     environment, position, colour, label, open-by-default, language, theme,
     and the name of a host function that returns an access token.
   - The token hand-off is the widget's own protocol, relayed: the iframe is
     framed with `?tokenOrigin=<this page's origin>` only when the host gave a
     token provider, and a message is answered only when it comes from that
     iframe's window AND from EDDI's origin, with the token posted to EDDI's
     exact origin — never "*".
   - It never reads the host page's cookies or storage, never uses innerHTML,
     eval or new Function, and puts nothing in the host page's global scope but
     `window.EddiChat` (open, close, toggle, setTokenProvider).
   - Accessible: a labelled button with aria-expanded; the panel is a labelled
     dialog that takes focus when the person opens it, keeps Tab inside it,
     closes on Esc and gives focus back to the button. Full-screen on a phone,
     and no animation under prefers-reduced-motion.
   ────────────────────────────────────────────── */

export const MSG_READY = "eddi-chat-ready";
export const MSG_TOKEN_REQUEST = "eddi-chat-token-request";
export const MSG_TOKEN = "eddi-chat-token";

/** The default button and close labels, per widget locale; `data-label` overrides the first. */
export const DEFAULT_LABELS: Record<string, readonly [open: string, close: string]> = {
  en: ["Chat", "Close chat"],
  de: ["Chat", "Chat schließen"],
  fr: ["Discuter", "Fermer la discussion"],
  es: ["Chat", "Cerrar el chat"],
  pt: ["Chat", "Fechar o chat"],
  ar: ["دردشة", "إغلاق الدردشة"],
  zh: ["聊天", "关闭聊天"],
  ja: ["チャット", "チャットを閉じる"],
  ko: ["채팅", "채팅 닫기"],
  th: ["แชท", "ปิดแชท"],
  hi: ["चैट", "चैट बंद करें"],
};

export interface LauncherConfig {
  /** EDDI's origin: where the script was loaded from, and the only origin answered. */
  eddiOrigin: string;
  agentId: string;
  environment: string;
  position: "left" | "right";
  color: string | null;
  label: string;
  closeLabel: string;
  open: boolean;
  lang: string | null;
  theme: "light" | "dark" | "system" | null;
  /** The name of a host-page function that returns (a promise of) an access token. */
  tokenProvider: string | null;
}

/** The widget's own colour rule (`isSafeCssColor` in ChatWidget.tsx), kept in step by a test. */
export function isSafeColor(value: string): boolean {
  const v = value.trim();
  if (!v || v.length > 64) return false;
  if (/^#(?:[0-9a-f]{3,4}|[0-9a-f]{6}|[0-9a-f]{8})$/i.test(v)) return true;
  if (/^(?:rgb|rgba|hsl|hsla)\(\s*[-+0-9.%\s,/deg]+\)$/i.test(v)) return true;
  return /^[a-z]{3,30}$/i.test(v);
}

/** A plausible bearer token, as the widget accepts one: printable ASCII, no whitespace, at most 16 KB. */
export function isPlausibleToken(token: unknown): token is string {
  return typeof token === "string" && token.length > 0 && token.length <= 16_384 && /^[\x21-\x7e]+$/.test(token);
}

/** The widget locale for a language tag (`pt-BR` → `pt`), or null when it has none. */
export function widgetLocale(tag: string | null | undefined): string | null {
  const base = (tag ?? "").trim().toLowerCase().split(/[-_]/)[0] ?? "";
  return base in DEFAULT_LABELS ? base : null;
}

/** Read the launcher's settings from its <script>; null (with a console warning) when unusable. */
export function readConfig(
  script: Pick<HTMLScriptElement, "src" | "dataset">,
  win: Pick<Window, "navigator"> & { document: Pick<Document, "documentElement"> },
): LauncherConfig | null {
  const d = script.dataset;
  let eddiOrigin: string;
  try {
    const src = new URL(script.src);
    if (src.protocol !== "https:" && src.protocol !== "http:") throw new Error("scheme");
    eddiOrigin = src.origin;
  } catch {
    console.warn("[eddi-chat] embed.js must be loaded from the chat server by an http(s) URL.");
    return null;
  }
  const agentId = (d.agentId ?? "").trim();
  if (!/^[A-Za-z0-9._-]{1,128}$/.test(agentId)) {
    console.warn("[eddi-chat] embed.js needs data-agent-id: the id of a deployed agent.");
    return null;
  }
  const environment = (d.environment ?? "production").trim().toLowerCase();
  if (!/^[a-z]{1,32}$/.test(environment)) {
    console.warn("[eddi-chat] data-environment is not an environment name; using production.");
  }
  const color = d.color && isSafeColor(d.color) ? d.color.trim() : null;
  if (d.color && !color) console.warn("[eddi-chat] data-color is not a plain colour and is ignored.");
  const lang = widgetLocale(d.lang);
  const labels =
    DEFAULT_LABELS[lang ?? widgetLocale(win.document.documentElement.lang) ?? widgetLocale(win.navigator.language) ?? "en"] ??
    DEFAULT_LABELS.en!;
  const label = (d.label ?? "").trim().slice(0, 60) || labels[0];
  const theme = d.theme === "light" || d.theme === "dark" || d.theme === "system" ? d.theme : null;
  const tokenProvider = d.tokenProvider && /^[A-Za-z_$][\w$]{0,63}$/.test(d.tokenProvider) ? d.tokenProvider : null;
  if (d.tokenProvider && !tokenProvider) console.warn("[eddi-chat] data-token-provider must name a global function.");
  return {
    eddiOrigin,
    agentId,
    environment: /^[a-z]{1,32}$/.test(environment) ? environment : "production",
    position: /left/i.test(d.position ?? "") ? "left" : "right",
    color,
    label,
    closeLabel: labels[1],
    open: d.open === "true",
    lang,
    theme,
    tokenProvider,
  };
}

/** The widget URL the panel frames. `tokenOrigin` only when the host can answer for a token. */
export function widgetUrl(cfg: LauncherConfig, hostOrigin: string | null, withToken: boolean): string {
  const url = new URL(
    `/chat/${encodeURIComponent(cfg.environment)}/${encodeURIComponent(cfg.agentId)}`,
    cfg.eddiOrigin,
  );
  // A widget told to wait for a token it will never get holds its start for
  // five seconds, so the hand-off is switched on only with a provider.
  if (withToken && hostOrigin && hostOrigin !== "null") url.searchParams.set("tokenOrigin", hostOrigin);
  if (cfg.color) url.searchParams.set("accentColor", cfg.color);
  if (cfg.lang) url.searchParams.set("lang", cfg.lang);
  if (cfg.theme) url.searchParams.set("theme", cfg.theme);
  return url.toString();
}

type TokenProvider = () => unknown;

export interface Launcher {
  open(): void;
  close(): void;
  toggle(): void;
  setTokenProvider(provider: TokenProvider | null): void;
  /** The launcher's elements, for tests. */
  readonly root: ShadowRoot;
  destroy(): void;
}

const CSS = `
:host{all:initial}
.btn{position:fixed;bottom:20px;z-index:2147483000;display:inline-flex;align-items:center;gap:8px;min-height:48px;min-width:48px;padding:0 18px;border:0;border-radius:999px;background:var(--eddi-accent,#2563eb);color:#fff;font:600 15px/1 system-ui,-apple-system,"Segoe UI",sans-serif;box-shadow:0 6px 20px rgba(0,0,0,.25);cursor:pointer}
.btn:focus-visible,.close:focus-visible{outline:3px solid #fff;outline-offset:2px;box-shadow:0 0 0 6px var(--eddi-accent,#2563eb)}
.btn svg{width:22px;height:22px;flex:none}
.right{right:20px}.left{left:20px}
.panel{position:fixed;bottom:84px;z-index:2147483001;display:flex;flex-direction:column;width:380px;max-width:calc(100vw - 40px);height:min(640px,calc(100vh - 120px));background:#fff;border-radius:16px;overflow:hidden;box-shadow:0 12px 40px rgba(0,0,0,.3)}
.panel[hidden]{display:none}
.panel:focus{outline:none}
.head{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:6px 6px 6px 16px;background:var(--eddi-accent,#2563eb);color:#fff;font:600 15px/1.2 system-ui,-apple-system,"Segoe UI",sans-serif}
.close{min-width:44px;min-height:44px;border:0;border-radius:10px;background:transparent;color:inherit;font:400 26px/1 system-ui,sans-serif;cursor:pointer}
iframe{flex:1;width:100%;border:0;background:#fff}
.sr{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0)}
@media (max-width:480px){.panel{inset:0;width:100vw;max-width:none;height:100vh;height:100dvh;border-radius:0;padding:env(safe-area-inset-top) env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left);box-sizing:border-box}}
@media (prefers-reduced-motion:no-preference){.panel{animation:eddi-in .18s ease-out}@keyframes eddi-in{from{opacity:0;transform:translateY(12px)}to{opacity:1;transform:none}}}
`;

function chatIcon(doc: Document): SVGSVGElement {
  const ns = "http://www.w3.org/2000/svg";
  const svg = doc.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "2");
  const path = doc.createElementNS(ns, "path");
  path.setAttribute("d", "M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z");
  svg.appendChild(path);
  return svg;
}

function el<K extends keyof HTMLElementTagNameMap>(
  doc: Document,
  tag: K,
  props: Record<string, string> = {},
  text?: string,
): HTMLElementTagNameMap[K] {
  const node = doc.createElement(tag);
  for (const [k, v] of Object.entries(props)) node.setAttribute(k, v);
  if (text !== undefined) node.textContent = text;
  return node;
}

/**
 * Draw the launcher for `cfg` into `win`'s page. Returns null when the page
 * already has one, so a script included twice draws one button.
 */
export function mountLauncher(cfg: LauncherConfig, win: Window = window): Launcher | null {
  const doc = win.document;
  if (doc.querySelector("[data-eddi-chat-launcher]")) return null;

  const host = el(doc, "div", { "data-eddi-chat-launcher": "" });
  const root = host.attachShadow({ mode: "open" });
  const style = el(doc, "style", {}, CSS);
  if (cfg.color) host.style.setProperty("--eddi-accent", cfg.color);

  const button = el(doc, "button", {
    type: "button",
    class: `btn ${cfg.position}`,
    "aria-expanded": "false",
    "aria-controls": "eddi-chat-panel",
    "aria-haspopup": "dialog",
    "data-testid": "launcher",
  });
  button.append(chatIcon(doc), el(doc, "span", {}, cfg.label));

  const panel = el(doc, "div", {
    id: "eddi-chat-panel",
    class: `panel ${cfg.position}`,
    role: "dialog",
    "aria-modal": "true",
    "aria-label": cfg.label,
    tabindex: "-1",
    "data-testid": "panel",
  });
  panel.hidden = true;
  const startGuard = el(doc, "span", { tabindex: "0", class: "sr", "data-testid": "guard-start" });
  const endGuard = el(doc, "span", { tabindex: "0", class: "sr", "data-testid": "guard-end" });
  const head = el(doc, "div", { class: "head" });
  const close = el(doc, "button", {
    type: "button",
    class: "close",
    "aria-label": cfg.closeLabel,
    title: cfg.closeLabel,
    "data-testid": "close",
  }, "×");
  head.append(el(doc, "span", {}, cfg.label), close);
  panel.append(startGuard, head, endGuard);
  root.append(style, button, panel);

  let frame: HTMLIFrameElement | null = null;
  let provider: TokenProvider | null = null;
  /** Whether the iframe was framed with the hand-off on (it cannot be switched on later). */
  let framedWithToken = false;

  const namedProvider = (): TokenProvider | null => {
    if (provider) return provider;
    if (!cfg.tokenProvider) return null;
    const fn = (win as unknown as Record<string, unknown>)[cfg.tokenProvider];
    return typeof fn === "function" ? (fn as TokenProvider) : null;
  };

  const ensureFrame = () => {
    if (frame) return frame;
    framedWithToken = !!(provider || cfg.tokenProvider);
    frame = el(doc, "iframe", {
      title: cfg.label,
      src: widgetUrl(cfg, win.location.origin, framedWithToken),
      allow: "clipboard-write",
      referrerpolicy: "strict-origin-when-cross-origin",
      "data-testid": "frame",
    });
    panel.insertBefore(frame, endGuard);
    return frame;
  };

  const isOpen = () => !panel.hidden;

  const open = (moveFocus = true) => {
    ensureFrame();
    panel.hidden = false;
    button.setAttribute("aria-expanded", "true");
    if (moveFocus) panel.focus();
  };

  const closePanel = (returnFocus: boolean) => {
    if (!isOpen()) return;
    panel.hidden = true;
    button.setAttribute("aria-expanded", "false");
    if (returnFocus) button.focus();
  };

  /** Focus is in the panel: on one of its controls, or inside the widget's frame. */
  const focusInPanel = () => root.activeElement !== null && panel.contains(root.activeElement);

  button.addEventListener("click", () => (isOpen() ? closePanel(true) : open()));
  close.addEventListener("click", () => closePanel(true));
  panel.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation();
      closePanel(true);
    }
  });
  // Tab stays in the dialog: past the last stop it wraps to the first, and back.
  endGuard.addEventListener("focus", () => close.focus());
  startGuard.addEventListener("focus", () => (frame ?? close).focus());

  const onMessage = (event: MessageEvent) => {
    // Only EDDI's widget in OUR frame: the right window and the right origin.
    if (!frame || event.source !== frame.contentWindow || event.origin !== cfg.eddiOrigin) return;
    const type = (event.data as { type?: unknown } | null)?.type;
    if (type !== MSG_READY && type !== MSG_TOKEN_REQUEST) return;
    if (!framedWithToken) return;
    const fn = namedProvider();
    if (!fn) return;
    const target = frame.contentWindow;
    Promise.resolve()
      .then(() => fn())
      .then((token) => {
        if (!isPlausibleToken(token)) {
          console.warn("[eddi-chat] the token provider returned no usable access token.");
          return;
        }
        target?.postMessage({ type: MSG_TOKEN, token }, cfg.eddiOrigin);
      })
      .catch(() => console.warn("[eddi-chat] the token provider failed."));
  };
  win.addEventListener("message", onMessage);

  doc.body.appendChild(host);
  // Open by default only where the panel does not cover the page: a phone
  // shows it full-screen, so there the person opens it.
  if (cfg.open && !(win.matchMedia?.("(max-width: 480px)").matches ?? false)) open(false);

  return {
    open: () => open(),
    close: () => closePanel(focusInPanel()),
    toggle: () => (isOpen() ? closePanel(focusInPanel()) : open()),
    setTokenProvider: (fn) => {
      provider = typeof fn === "function" ? fn : null;
    },
    root,
    destroy: () => {
      win.removeEventListener("message", onMessage);
      host.remove();
    },
  };
}
