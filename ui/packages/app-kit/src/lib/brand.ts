/**
 * The deployment's brand — product name, toggles — as the Manager reads it.
 *
 * EDDI serves `/branding/__brand__.js`, which every shell loads before the
 * bundle, and that script sets `window.__EDDI_BRAND__` (BrandDelivery). Under
 * the Vite dev server and in MSW mock mode the brand-defaults plugin serves the
 * unbranded script (`scripts/vite-plugin-brand.mjs`); in Vitest nothing sets the
 * global at all. Either way {@link DEFAULT_BRAND} applies, which is exactly the
 * EDDI brand, so an unbranded deployment looks as it always has.
 * `brand.test.ts` keeps {@link DEFAULT_BRAND} equal to the plugin's
 * `DEFAULT_BRAND_SCRIPT`, which `BrandDeliveryTest` in turn keeps equal to the
 * backend's.
 *
 * One store, read once at module load: {@link getBrand} for plain code,
 * `useBrand()` (`src/hooks/use-brand.ts`) for components, and {@link setBrand}
 * for the settings page to apply a saved brand without a reload. i18next
 * subscribes too, so `{{productName}}` in every string follows a change
 * (`src/i18n/config.ts`).
 */

export interface Brand {
  /** Whether anything differs from the EDDI brand. */
  branded: boolean;
  /** The stored brand document's revision; 0 when none exists. */
  revision: number;
  /** Never empty; "EDDI" by default. */
  productName: string;
  /** Never empty; the product name when unset. */
  shortName: string;
  tagline: string | null;
  /** Show "Powered by EDDI" in the About dialog. On by default. */
  poweredBy: boolean;
  /** Keep the update check UI while branded. Off by default. */
  showUpdateCheck: boolean;
  chat: { accent: string | null };
  /**
   * Whether each image slot has an image of its own (uploaded, or from
   * `eddi.branding.assets-dir`). `/branding/<slot>` always answers an image —
   * the bundled EDDI one when this is false — so the logo components read this
   * to show the product name instead of EDDI's wordmark.
   */
  assets: BrandAssetFlags;
}

/** The five image slots, by their admin-API id. */
export const BRAND_ASSET_SLOTS = ["logo", "logoDark", "icon", "favicon", "ogImage"] as const;
export type BrandAssetSlot = (typeof BRAND_ASSET_SLOTS)[number];
export type BrandAssetFlags = Record<BrandAssetSlot, boolean>;

declare global {
  interface Window {
    /** Set by `/branding/__brand__.js`; absent in tests. */
    __EDDI_BRAND__?: unknown;
  }
}

/** What EDDI's `__brand__.js` answers when nothing is branded. */
export const DEFAULT_BRAND: Readonly<Brand> = Object.freeze({
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

function str(value: unknown): string | null {
  return typeof value === "string" && value.trim() !== "" ? value : null;
}

function bool(value: unknown, fallback: boolean): boolean {
  return typeof value === "boolean" ? value : fallback;
}

/**
 * Normalise whatever `window.__EDDI_BRAND__` holds. Field by field, so a script
 * from an older or newer server that lacks a field (or carries a wrong type)
 * still yields a complete brand: the missing part falls back to EDDI's.
 */
export function readBrand(raw: unknown): Brand {
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

let current: Brand = readBrand(typeof window === "undefined" ? undefined : window.__EDDI_BRAND__);
const listeners = new Set<() => void>();

/** The brand in effect. A stable object until {@link setBrand} replaces it. */
export function getBrand(): Brand {
  return current;
}

/**
 * Replace the brand in effect, e.g. after the settings page saved one, and tell
 * every subscriber. Accepts the raw `__EDDI_BRAND__` shape, so a caller can hand
 * over a freshly re-fetched global as it is.
 */
export function setBrand(next: unknown): void {
  current = readBrand(next);
  for (const listener of [...listeners]) listener();
}

/** Called after every {@link setBrand}. Returns the unsubscribe function. */
export function subscribeBrand(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * The values i18next substitutes into every string: `{{productName}}` and
 * `{{shortName}}`.
 */
export function brandVariables(brand: Brand): { productName: string; shortName: string } {
  return { productName: brand.productName, shortName: brand.shortName };
}

/**
 * Whether the update check UI is offered: the Updates page, its nav entry, the
 * banner and the automatic check. A branded deployment hides it unless the
 * brand asks for it, because it names EDDI and GitHub (white-label plan, R5).
 */
export function isUpdateUiVisible(brand: Brand): boolean {
  return !brand.branded || brand.showUpdateCheck;
}

/**
 * The URL of a `/branding/*` image, with the brand revision as a cache buster
 * once a brand document exists. The images are served with `max-age=300`, so
 * without it a logo replaced on the settings page would keep showing the old one
 * for up to five minutes; with it, `setBrand` after a save re-renders every logo
 * against a URL the browser has not cached. Revision 0 (nothing stored) keeps
 * the plain URL the HTML shells use.
 */
export function brandAssetUrl(path: "logo" | "logo-dark" | "icon" | "favicon.ico" | "og-image", brand: Brand): string {
  return brand.revision > 0 ? `/branding/${path}?r=${brand.revision}` : `/branding/${path}`;
}

/** The route of the Updates page, which {@link isUpdateUiVisible} gates. */
export const UPDATES_PATH = "/manage/updates";
