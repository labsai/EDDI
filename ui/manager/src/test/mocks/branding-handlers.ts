import { http, HttpResponse, passthrough } from "msw";
import type { BrandDocument, BrandingView, BrandUpdate, ResolvedBrand } from "@/lib/api/branding";
import { BRAND_ASSET_SLOTS, type BrandAssetSlot } from "@/lib/brand";
import { EMPTY_COLORS, renderBrandStylesheet, resolveBrandColors, type BrandColorValues } from "@/lib/brand-css";
import {
  checkBrandText,
  contrastRatio,
  isSafeBrandColor,
  MIN_CONTRAST,
  PRODUCT_NAME_MAX,
  SHORT_NAME_MAX,
  TAGLINE_MAX,
} from "@/lib/brand-validation";

/**
 * `/administration/branding*` and, for mock mode, the brand delivery files —
 * a stateful stand-in for `BrandingService` + `RestBrandingAdmin`.
 *
 * Mock mode works end to end: a save, upload or reset changes what the mocked
 * `/branding/__brand__.js`, `/branding/brand.css` and `/branding/<image>` answer
 * ({@link brandingDeliveryResolver}, wired in browser-handlers.ts in place of the
 * plain passthrough), so the settings page's live update can be seen without a
 * backend. Two variants:
 *
 * - **Locked:** `sessionStorage["eddi-mock-branding-locked"] = "true"` answers
 *   the brand as a locked, properties-sourced one and refuses every write (409).
 * - **Conflict:** saving with the tagline {@link MOCK_BRAND_CONFLICT_TRIGGER}
 *   behaves as if another admin had saved first: the revision moves on and the
 *   save answers 409 with the `currentRevision`.
 *
 * Module state, so `src/test/setup.ts` calls {@link resetBrandingMockState}
 * after every test.
 */

export const MOCK_BRAND_LOCKED_KEY = "eddi-mock-branding-locked";
export const MOCK_BRAND_CONFLICT_TRIGGER = "trigger a conflict";
const MAX_BYTES = 512 * 1024;

/** The brand a locked mock deployment pins through its properties. */
const LOCKED_BRAND: BrandDocument = {
  revision: 0,
  productName: "Zebra Labs",
  shortName: "Zebra",
  tagline: "AI teams for Zebra",
  colors: { primary: "#0f766e", primaryForeground: "#ffffff", darkPrimary: "#2dd4bf", darkPrimaryForeground: "#042f2e" },
  poweredBy: true,
  showUpdateCheck: false,
  assets: {},
};

interface StoredAsset {
  hash: string;
  type: string;
  bytes: ArrayBuffer;
}

let doc: BrandDocument = { revision: 0 };
let assets = new Map<BrandAssetSlot, StoredAsset>();

export function resetBrandingMockState(): void {
  doc = { revision: 0 };
  assets = new Map();
}

function isLocked(): boolean {
  try {
    return sessionStorage.getItem(MOCK_BRAND_LOCKED_KEY) === "true";
  } catch {
    return false;
  }
}

function hasValues(d: BrandDocument): boolean {
  const colors = d.colors ?? {};
  return (
    d.productName != null ||
    d.shortName != null ||
    d.tagline != null ||
    Object.values(colors).some((c) => c != null) ||
    d.poweredBy != null ||
    d.showUpdateCheck != null ||
    Object.values(d.assets ?? {}).some((a) => a != null)
  );
}

function colorsOf(d: BrandDocument): BrandColorValues {
  return { ...EMPTY_COLORS, ...Object.fromEntries(Object.entries(d.colors ?? {}).filter(([, v]) => v != null)) };
}

function resolved(d: BrandDocument, locked: boolean): ResolvedBrand {
  const branded = hasValues(d);
  const productName = d.productName ?? "EDDI";
  return {
    revision: locked ? 0 : d.revision,
    source: locked ? "properties" : branded ? "database" : "default",
    locked,
    branded,
    productName,
    shortName: d.shortName ?? productName,
    tagline: d.tagline ?? null,
    colors: hasValues({ revision: 0, colors: d.colors }) ? resolveBrandColors(colorsOf(d)) : { ...EMPTY_COLORS },
    poweredBy: d.poweredBy ?? true,
    showUpdateCheck: d.showUpdateCheck ?? false,
    assets: { ...(d.assets ?? {}) },
  };
}

function current(): { document: BrandDocument; effective: ResolvedBrand; locked: boolean } {
  const locked = isLocked();
  const document = locked ? LOCKED_BRAND : doc;
  return { document, effective: resolved(document, locked), locked };
}

function view(warnings: string[] = []): BrandingView {
  const { document, effective, locked } = current();
  return { document, effective, locked, source: effective.source ?? "default", warnings };
}

function lockedResponse() {
  return HttpResponse.json(
    { error: "The brand is locked by eddi.branding.locked=true; change the eddi.branding.* properties instead." },
    { status: 409 },
  );
}

function bump(next: Omit<BrandDocument, "revision">): void {
  doc = { ...next, revision: doc.revision + 1, updatedAt: new Date().toISOString(), updatedBy: "admin" };
}

function contrastWarnings(colors: BrandColorValues): string[] {
  const out: string[] = [];
  const light = contrastRatio(colors.primaryForeground, colors.primary);
  if (light !== null && light < MIN_CONTRAST) {
    out.push(`Contrast of primaryForeground on primary is ${light.toFixed(2)}:1, below the 4.5:1 that WCAG AA asks for normal text.`);
  }
  const darkDiffers = colors.darkPrimary !== colors.primary || colors.darkPrimaryForeground !== colors.primaryForeground;
  const dark = contrastRatio(colors.darkPrimaryForeground, colors.darkPrimary);
  if (darkDiffers && dark !== null && dark < MIN_CONTRAST) {
    out.push(
      `Contrast of darkPrimaryForeground on darkPrimary is ${dark.toFixed(2)}:1, below the 4.5:1 that WCAG AA asks for normal text.`,
    );
  }
  return out;
}

/** `BrandAssetValidator.detectType`, enough of it for the mock. */
function detectType(bytes: Uint8Array): string | null {
  const starts = (...sig: number[]) => sig.every((b, i) => bytes[i] === b);
  if (starts(0x89, 0x50, 0x4e, 0x47)) return "image/png";
  if (starts(0x52, 0x49, 0x46, 0x46) && String.fromCharCode(...bytes.slice(8, 12)) === "WEBP") return "image/webp";
  if (starts(0x00, 0x00, 0x01, 0x00)) return "image/x-icon";
  const head = new TextDecoder().decode(bytes.slice(0, 1024)).trimStart();
  if (/^(<\?xml[^>]*>\s*)?(<!--[\s\S]*?-->\s*)*<svg[\s>]/i.test(head)) return "image/svg+xml";
  return null;
}

const UNSAFE_SVG = /<\s*([\w-]+:)?(script|foreignObject|iframe|embed|object|animate|set)\b|\son[a-z]+\s*=|javascript:|<!ENTITY|&#/i;

export const brandingHandlers = [
  http.get("*/administration/branding", () => HttpResponse.json(view())),

  http.put("*/administration/branding", async ({ request }) => {
    if (isLocked()) return lockedResponse();
    const body = (await request.json()) as BrandUpdate;
    if (typeof body.revision !== "number") {
      return HttpResponse.json({ error: "The revision is required." }, { status: 400 });
    }
    if (body.tagline === MOCK_BRAND_CONFLICT_TRIGGER) {
      // Another admin saved first.
      bump({ ...doc });
    }
    if (body.revision !== doc.revision) {
      return HttpResponse.json(
        { error: `The brand changed since revision ${body.revision}; reload it and apply your change again.`, currentRevision: doc.revision },
        { status: 409 },
      );
    }
    const violations: { field: string; message: string }[] = [];
    const text = (field: "productName" | "shortName" | "tagline", max: number) => {
      const problem = checkBrandText(body[field] ?? null, max);
      if (problem) violations.push({ field, message: problem === "tooLong" ? `must be at most ${max} characters` : `is not allowed (${problem})` });
    };
    text("productName", PRODUCT_NAME_MAX);
    text("shortName", SHORT_NAME_MAX);
    text("tagline", TAGLINE_MAX);
    const colors = { ...EMPTY_COLORS, ...(body.colors ?? {}) };
    for (const [key, value] of Object.entries(colors)) {
      if (value != null && !isSafeBrandColor(value)) {
        violations.push({ field: `colors.${key}`, message: "must be a colour like #0f766e, #fff, rgb(15 118 110), hsl(…) or oklch(…) with plain numbers only" });
      }
    }
    if (violations.length > 0) {
      return HttpResponse.json({ error: "The brand has invalid values.", violations }, { status: 400 });
    }
    bump({
      productName: body.productName,
      shortName: body.shortName,
      tagline: body.tagline,
      colors,
      poweredBy: body.poweredBy,
      showUpdateCheck: body.showUpdateCheck,
      assets: doc.assets,
    });
    return HttpResponse.json(view(contrastWarnings(resolveBrandColors(colors))));
  }),

  http.delete("*/administration/branding", () => {
    if (isLocked()) return lockedResponse();
    assets = new Map();
    bump({});
    return HttpResponse.json(view());
  }),

  http.put("*/administration/branding/assets/:slot", async ({ request, params }) => {
    const slot = String(params.slot) as BrandAssetSlot;
    if (!BRAND_ASSET_SLOTS.includes(slot)) {
      return HttpResponse.json({ error: `No brand image slot '${slot}'.` }, { status: 404 });
    }
    if (isLocked()) return lockedResponse();
    const bytes = await request.arrayBuffer();
    if (bytes.byteLength > MAX_BYTES) {
      return HttpResponse.json({ error: `The image is larger than ${MAX_BYTES} bytes.` }, { status: 413 });
    }
    const view8 = new Uint8Array(bytes);
    const type = detectType(view8);
    if (!type || (type === "image/x-icon" && slot !== "favicon")) {
      return HttpResponse.json({ error: "Only PNG, WebP and SVG images are accepted (ICO for the favicon)." }, { status: 415 });
    }
    if (type === "image/svg+xml" && UNSAFE_SVG.test(new TextDecoder().decode(view8))) {
      return HttpResponse.json({ error: "The SVG contains script or an external reference." }, { status: 400 });
    }
    const hash = `sha256:mock${doc.revision + 1}${slot}`;
    assets.set(slot, { hash, type, bytes });
    bump({ ...doc, assets: { ...(doc.assets ?? {}), [slot]: hash } });
    return HttpResponse.json(view());
  }),

  http.delete("*/administration/branding/assets/:slot", ({ params }) => {
    const slot = String(params.slot) as BrandAssetSlot;
    if (!BRAND_ASSET_SLOTS.includes(slot)) {
      return HttpResponse.json({ error: `No brand image slot '${slot}'.` }, { status: 404 });
    }
    if (isLocked()) return lockedResponse();
    assets.delete(slot);
    bump({ ...doc, assets: { ...(doc.assets ?? {}), [slot]: null } });
    return HttpResponse.json(view());
  }),
];

/** `/branding/<name>` → the slot it serves, with the server's fallbacks. */
const PUBLIC_SLOTS: Record<string, BrandAssetSlot[]> = {
  logo: ["logo"],
  "logo-dark": ["logoDark", "logo"],
  icon: ["icon"],
  "favicon.ico": ["favicon", "icon"],
  "og-image": ["ogImage", "logo"],
};

/** What `BrandDelivery.script` would answer for the mock's brand. */
export function mockBrandScript(): string {
  const { effective } = current();
  const brand = {
    branded: effective.branded,
    revision: effective.revision,
    productName: effective.productName,
    shortName: effective.shortName,
    tagline: effective.tagline ?? null,
    poweredBy: effective.poweredBy,
    showUpdateCheck: effective.showUpdateCheck,
    chat: { accent: effective.colors?.chatAccent ?? null },
    assets: Object.fromEntries(BRAND_ASSET_SLOTS.map((s) => [s, !!effective.assets?.[s]])),
  };
  return `window.__EDDI_BRAND__=${JSON.stringify(brand).replace(/</g, "\\u003c")};`;
}

/**
 * The browser worker's handler for `/branding/*` in mock mode: the brand script
 * and stylesheet from the mock's state, an uploaded image when there is one, and
 * otherwise the dev server (the bundled EDDI images, `vite-plugin-brand.mjs`).
 */
export function brandingDeliveryResolver({ request }: { request: Request }) {
  const name = new URL(request.url).pathname.replace(/^.*\/branding\//, "");
  if (name === "__brand__.js") {
    return new HttpResponse(mockBrandScript(), {
      headers: { "Content-Type": "application/javascript", "Cache-Control": "no-cache" },
    });
  }
  if (name === "brand.css") {
    const { effective } = current();
    const css = renderBrandStylesheet({ ...EMPTY_COLORS, ...(effective.colors ?? {}) }, effective.revision);
    return new HttpResponse(css, { headers: { "Content-Type": "text/css", "Cache-Control": "no-cache" } });
  }
  for (const slot of PUBLIC_SLOTS[name] ?? []) {
    const asset = isLocked() ? undefined : assets.get(slot);
    if (asset) {
      return new HttpResponse(asset.bytes, { headers: { "Content-Type": asset.type, "Cache-Control": "no-cache" } });
    }
  }
  return passthrough();
}
