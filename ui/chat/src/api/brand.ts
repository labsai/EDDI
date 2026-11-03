/* ──────────────────────────────────────────────
   EDDI Chat — the deployment's brand
   EDDI serves `/branding/__brand__.js`, which chat.html loads before the bundle,
   and that script sets `window.__EDDI_BRAND__` (BrandDelivery). The Vite dev
   server serves the unbranded script (scripts/vite-plugin-brand.mjs); in Vitest
   and in a stand-alone bundle nothing sets the global. Either way DEFAULT_BRAND
   applies, which is exactly the EDDI brand, so an unbranded deployment looks as
   it always has. `brand.test.ts` keeps DEFAULT_BRAND equal to the plugin's
   DEFAULT_BRAND_SCRIPT, which BrandDeliveryTest keeps equal to the backend's.

   The same reader as the Manager's src/lib/brand.ts, deliberately not shared:
   the two UIs never import from each other. The Chat has no settings page, so
   there is no live update — the global is read when asked.

   Precedence of the widget's appearance, highest first (white-label plan §4.5):
     1. URL parameters (`?title=`, `?accentColor=` …) — ChatWidget dispatches
        them over the initial config on mount
     2. the embedder's `<ChatProvider config={…}>`
     3. (later) the agent's own appearance — the agentAppearanceLayer() seam
     4. the deployment brand — brandConfigLayer()
     5. the built-in defaults — chat-store's defaultConfig
   ────────────────────────────────────────────── */

import type { ChatConfig } from "@/types";

export interface ChatBrand {
  /** Whether anything differs from the EDDI brand. */
  branded: boolean;
  /** The stored brand document's revision; 0 when none exists. Busts image caches. */
  revision: number;
  /** Never empty; "EDDI" by default. */
  productName: string;
  /** Never empty; the product name when unset. */
  shortName: string;
  tagline: string | null;
  poweredBy: boolean;
  showUpdateCheck: boolean;
  /** The Chat's accent: the brand's chat accent, else its primary colour, else null. */
  chat: { accent: string | null };
  /**
   * Whether each image slot has an image of its own. `/branding/logo` always
   * answers an image — the bundled EDDI one when this is false — so the header
   * reads this to show the product name instead of EDDI's logo.
   */
  assets: { logo: boolean; logoDark: boolean; icon: boolean; favicon: boolean; ogImage: boolean };
}

declare global {
  interface Window {
    /** Set by `/branding/__brand__.js`; absent in tests and stand-alone bundles. */
    __EDDI_BRAND__?: unknown;
  }
}

/** What EDDI's `__brand__.js` answers when nothing is branded. */
export const DEFAULT_BRAND: Readonly<ChatBrand> = Object.freeze({
  branded: false,
  revision: 0,
  productName: "EDDI",
  shortName: "EDDI",
  tagline: null,
  poweredBy: true,
  showUpdateCheck: false,
  chat: Object.freeze({ accent: null }),
  assets: Object.freeze({ logo: false, logoDark: false, icon: false, favicon: false, ogImage: false }),
});

/** The unbranded header logo, bundled with the widget. */
export const UNBRANDED_LOGO_URL = "/img/logo_eddi.png";

/** The unbranded tab title, and its suffix after an agent's name. Kept exactly as before branding. */
export const UNBRANDED_DOCUMENT_TITLE = "EDDI Chat";

/**
 * SEAM — the agent's own appearance (`appearance {displayName, avatarUrl,
 * accentColor}` from `GET /agents/{id}/profile`, white-label plan §4.6), which
 * slots in above the brand and below the embedder's config and the URL. Not
 * built: it always answers nothing. The profile arrives after mount, so when it
 * is built it must fill only keys that neither the URL nor the embedder set — a
 * plain SET_CONFIG of it would override them.
 */
export function agentAppearanceLayer(): Partial<ChatConfig> {
  return {};
}

function str(value: unknown): string | null {
  return typeof value === "string" && value.trim() !== "" ? value : null;
}

function bool(value: unknown, fallback: boolean): boolean {
  return typeof value === "boolean" ? value : fallback;
}

/**
 * Normalise whatever `window.__EDDI_BRAND__` holds, field by field, so a script
 * from an older or newer server still yields a complete brand.
 */
export function readBrand(raw: unknown): ChatBrand {
  const source = raw && typeof raw === "object" ? (raw as Record<string, unknown>) : {};
  const chat = source.chat && typeof source.chat === "object" ? (source.chat as Record<string, unknown>) : {};
  const assets =
    source.assets && typeof source.assets === "object" ? (source.assets as Record<string, unknown>) : {};
  const productName = str(source.productName) ?? DEFAULT_BRAND.productName;
  return {
    branded: bool(source.branded, DEFAULT_BRAND.branded),
    revision:
      typeof source.revision === "number" && Number.isFinite(source.revision)
        ? source.revision
        : DEFAULT_BRAND.revision,
    productName,
    shortName: str(source.shortName) ?? productName,
    tagline: str(source.tagline),
    poweredBy: bool(source.poweredBy, DEFAULT_BRAND.poweredBy),
    showUpdateCheck: bool(source.showUpdateCheck, DEFAULT_BRAND.showUpdateCheck),
    chat: { accent: str(chat.accent) },
    assets: {
      logo: bool(assets.logo, false),
      logoDark: bool(assets.logoDark, false),
      icon: bool(assets.icon, false),
      favicon: bool(assets.favicon, false),
      ogImage: bool(assets.ogImage, false),
    },
  };
}

/** The brand in effect, read from the page's global each time. */
export function getBrand(win: Pick<Window, "__EDDI_BRAND__"> | undefined = typeof window === "undefined" ? undefined : window): ChatBrand {
  return readBrand(win?.__EDDI_BRAND__);
}

/** The header title nobody overrode: the product name when branded, else "EDDI". */
export function brandTitle(brand: ChatBrand = getBrand()): string {
  return brand.branded ? brand.productName : DEFAULT_BRAND.productName;
}

/**
 * The header logo nobody overrode. The brand's own logo (cache-busted by
 * revision) when it has one; `null` when branded without one, meaning "show
 * the product name as text" — never EDDI's logo under another brand; EDDI's
 * logo otherwise.
 */
export function brandLogoUrl(brand: ChatBrand = getBrand()): string | null {
  if (brand.assets.logo) return `/branding/logo?r=${brand.revision}`;
  return brand.branded ? null : UNBRANDED_LOGO_URL;
}

/**
 * The tab title. Unbranded it stays exactly what it always was
 * (`"<agent> — EDDI Chat"` / `"EDDI Chat"`); branded it is
 * `"<agent> — <productName>"` / `"<productName>"`.
 */
export function brandDocumentTitle(agentName: string | null, brand: ChatBrand = getBrand()): string {
  const suffix = brand.branded ? brand.productName : UNBRANDED_DOCUMENT_TITLE;
  return agentName ? `${agentName} — ${suffix}` : suffix;
}

/**
 * The deployment brand's layer of the widget config (precedence 4 above).
 * Empty when unbranded, so the built-in defaults apply untouched. `logoUrl` is
 * left out when the brand has no logo: the header resolves the fallback itself
 * (brandLogoUrl), so an embedder's own `logoUrl` still wins.
 */
export function brandConfigLayer(brand: ChatBrand = getBrand()): Partial<ChatConfig> {
  if (!brand.branded) return {};
  const layer: Partial<ChatConfig> = { title: brand.productName };
  if (brand.chat.accent) layer.accentColor = brand.chat.accent;
  return layer;
}
