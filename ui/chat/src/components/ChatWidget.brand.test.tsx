/* ──────────────────────────────────────────────
   The deployment brand in the widget (white-label plan §4.5): header title,
   logo or text wordmark, tab title, accent precedence and the demo route.
   ────────────────────────────────────────────── */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { ChatRoute } from "./ChatRoute";
import { ChatProvider } from "@/store/chat-store";
import type { ChatConfig } from "@/types";

const originalFetch = globalThis.fetch;

const ZEBRA = {
  branded: true,
  revision: 7,
  productName: "Zebra Labs",
  shortName: "Zebra",
  tagline: null,
  poweredBy: false,
  showUpdateCheck: false,
  chat: { accent: "#0f766e" },
  assets: { logo: true, logoDark: false, icon: false, favicon: false, ogImage: false },
};
const ZEBRA_NO_LOGO = { ...ZEBRA, assets: { ...ZEBRA.assets, logo: false } };

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  globalThis.fetch = vi.fn(async (input: string | URL | Request) => {
    const url = String(input);
    if (url.includes("/start")) {
      return new Response(null, { status: 201, headers: { Location: "/agents/conv-1" } });
    }
    if (url.includes("/profile")) {
      return new Response(JSON.stringify({ name: "Helper" }), { status: 200 });
    }
    return new Response(
      JSON.stringify({
        conversationState: "READY",
        conversationSteps: [],
        conversationOutputs: [{ output: [{ type: "text", text: "Hello there" }] }],
      }),
      { status: 200 },
    );
  }) as typeof fetch;
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  vi.restoreAllMocks();
  delete window.__EDDI_BRAND__;
  document.title = "";
  document.documentElement.removeAttribute("style");
});

function renderAt(path: string, config?: Partial<ChatConfig>) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ChatProvider config={config}>
        <Routes>
          <Route path="/chat/:environment/:agentId" element={<ChatRoute />} />
        </Routes>
      </ChatProvider>
    </MemoryRouter>,
  );
}

const accent = () => document.documentElement.style.getPropertyValue("--chat-accent");
const header = () => document.querySelector(".chat-header") as HTMLElement;

describe("unbranded", () => {
  it("keeps the EDDI logo, title and tab title exactly as before", async () => {
    renderAt("/chat/production/agent-1");
    await screen.findByText("Hello there");
    const logo = header().querySelector("img.chat-header__logo") as HTMLImageElement;
    expect(logo.getAttribute("src")).toBe("/img/logo_eddi.png");
    expect(screen.queryByTestId("chat-title")).toBeNull();
    expect(document.title).toMatch(/(^| — )EDDI Chat$/);
    // No accent is applied: the stylesheet's own tokens stay in charge.
    expect(accent()).toBe("");
  });
});

describe("branded", () => {
  it("shows the brand's logo, cache-busted, with the product name as alt text", async () => {
    window.__EDDI_BRAND__ = ZEBRA;
    renderAt("/chat/production/agent-1");
    await screen.findByText("Hello there");
    const logo = header().querySelector("img.chat-header__logo") as HTMLImageElement;
    expect(logo.getAttribute("src")).toBe("/branding/logo?r=7");
    expect(logo.getAttribute("alt")).toBe("Zebra Labs");
    expect(header().textContent).not.toMatch(/\bEDDI\b/);
  });

  it("shows the product name as text, never EDDI's logo, when the brand has no logo", async () => {
    window.__EDDI_BRAND__ = ZEBRA_NO_LOGO;
    renderAt("/chat/production/agent-1");
    await screen.findByText("Hello there");
    expect(header().querySelector("img")).toBeNull();
    expect(screen.getByTestId("chat-title")).toHaveTextContent("Zebra Labs");
    expect(screen.getByTestId("chat-title")).toHaveClass("chat-header__title--wordmark");
  });

  it("names the product in the tab title", async () => {
    window.__EDDI_BRAND__ = ZEBRA;
    renderAt("/chat/production/agent-1");
    await screen.findByText("Hello there");
    expect(document.title).toMatch(/^(Helper — )?Zebra Labs$/);
  });

  it("still lets ?title= and an embedder's logoUrl override the brand", async () => {
    window.__EDDI_BRAND__ = ZEBRA;
    renderAt("/chat/production/agent-1?title=Support%20Desk", { logoUrl: "/custom.png" });
    await screen.findByText("Hello there");
    expect(screen.getByTestId("chat-title")).toHaveTextContent("Support Desk");
    expect(header().querySelector("img")?.getAttribute("src")).toBe("/custom.png");
    expect(document.title).toBe("Support Desk");
  });
});

describe("accent precedence", () => {
  it("applies the brand's accent when nothing overrides it", async () => {
    window.__EDDI_BRAND__ = ZEBRA;
    renderAt("/chat/production/agent-1");
    await screen.findByText("Hello there");
    expect(accent()).toBe("#0f766e");
    expect(document.documentElement.style.getPropertyValue("--chat-user-bg")).toBe("#0f766e");
  });

  it("lets ?accentColor= beat the brand", async () => {
    window.__EDDI_BRAND__ = ZEBRA;
    renderAt("/chat/production/agent-1?accentColor=%23ff0088");
    await screen.findByText("Hello there");
    expect(accent()).toBe("#ff0088");
  });

  it("lets the embedder's config.accentColor beat the brand — it used to be stored but never applied", async () => {
    window.__EDDI_BRAND__ = ZEBRA;
    renderAt("/chat/production/agent-1", { accentColor: "#123456" });
    await screen.findByText("Hello there");
    expect(accent()).toBe("#123456");
    expect(document.documentElement.style.getPropertyValue("--chat-accent-soft")).toContain(
      "color-mix(in srgb, #123456 13%",
    );
  });

  it("applies config.accentColor without a brand too, and ignores an unsafe one", async () => {
    renderAt("/chat/production/agent-1", { accentColor: "url(https://tracker.example/x)" });
    await screen.findByText("Hello there");
    expect(accent()).toBe("");
  });

  it("keeps ?userBg= above the user-bubble colour an accent derives", async () => {
    window.__EDDI_BRAND__ = ZEBRA;
    renderAt("/chat/production/agent-1?userBg=%23222222");
    await screen.findByText("Hello there");
    expect(accent()).toBe("#0f766e");
    expect(document.documentElement.style.getPropertyValue("--chat-user-bg")).toBe("#222222");
  });
});

describe("demo route", () => {
  it("shows a neutral not-available state under a brand, never EDDI's showcase", async () => {
    window.__EDDI_BRAND__ = ZEBRA;
    renderAt("/chat/demo/showcase");
    expect(await screen.findByTestId("demo-unavailable")).toHaveTextContent(/not available/i);
    expect(document.body.textContent).not.toMatch(/\bEDDI\b/);
    expect(globalThis.fetch).not.toHaveBeenCalled();
  });

  it("is unchanged unbranded", async () => {
    renderAt("/chat/demo/showcase");
    expect(await screen.findByText(/Welcome to EDDI/)).toBeInTheDocument();
    expect(screen.queryByTestId("demo-unavailable")).toBeNull();
  });
});
