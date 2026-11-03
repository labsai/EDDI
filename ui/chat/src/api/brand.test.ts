import { afterEach, describe, expect, it } from "vitest";
import {
  brandConfigLayer,
  brandDocumentTitle,
  brandLogoUrl,
  brandTitle,
  DEFAULT_BRAND,
  getBrand,
  readBrand,
  UNBRANDED_LOGO_URL,
} from "./brand";
import { DEFAULT_BRAND_SCRIPT } from "../../scripts/vite-plugin-brand.mjs";

const ZEBRA = {
  branded: true,
  revision: 7,
  productName: "Zebra Labs",
  shortName: "Zebra",
  tagline: "Stripes for teams",
  poweredBy: false,
  showUpdateCheck: false,
  chat: { accent: "#0f766e" },
  assets: { logo: true, logoDark: false, icon: true, favicon: false, ogImage: false },
};

afterEach(() => {
  delete window.__EDDI_BRAND__;
});

describe("brand defaults", () => {
  it("equal what an unbranded EDDI serves as __brand__.js", () => {
    // The plugin's script is the one BrandDeliveryTest keeps equal to the
    // backend's, so this closes the chain: Java = plugin = brand.ts.
    const sandbox: { __EDDI_BRAND__?: unknown } = {};
    new Function("window", DEFAULT_BRAND_SCRIPT)(sandbox);
    expect(sandbox.__EDDI_BRAND__).toEqual(DEFAULT_BRAND);
    expect(readBrand(sandbox.__EDDI_BRAND__)).toEqual(DEFAULT_BRAND);
  });

  it("apply when no brand script ran", () => {
    expect(getBrand()).toEqual(DEFAULT_BRAND);
  });
});

describe("readBrand", () => {
  it("takes a complete brand as it is", () => {
    expect(readBrand(ZEBRA)).toEqual(ZEBRA);
  });

  it("fills what an older or broken script lacks from the EDDI brand", () => {
    const brand = readBrand({ branded: true, productName: "  ", revision: "x", chat: "no", assets: null });
    expect(brand.productName).toBe("EDDI");
    expect(brand.shortName).toBe("EDDI");
    expect(brand.revision).toBe(0);
    expect(brand.chat.accent).toBeNull();
    expect(brand.assets.logo).toBe(false);
  });

  it("reads the page global", () => {
    window.__EDDI_BRAND__ = ZEBRA;
    expect(getBrand().productName).toBe("Zebra Labs");
  });
});

describe("what the brand decides", () => {
  const unbranded = DEFAULT_BRAND;
  const namedOnly = readBrand({ ...ZEBRA, assets: { ...ZEBRA.assets, logo: false } });
  const zebra = readBrand(ZEBRA);

  it("title: the product name when branded, else EDDI", () => {
    expect(brandTitle(unbranded)).toBe("EDDI");
    expect(brandTitle(zebra)).toBe("Zebra Labs");
  });

  it("logo: the uploaded one, cache-busted; text when branded without one; else EDDI's", () => {
    expect(brandLogoUrl(zebra)).toBe("/branding/logo?r=7");
    expect(brandLogoUrl(namedOnly)).toBeNull();
    expect(brandLogoUrl(unbranded)).toBe(UNBRANDED_LOGO_URL);
  });

  it("tab title: unbranded exactly as before; branded names the product", () => {
    expect(brandDocumentTitle("Helper", unbranded)).toBe("Helper — EDDI Chat");
    expect(brandDocumentTitle(null, unbranded)).toBe("EDDI Chat");
    expect(brandDocumentTitle("Helper", zebra)).toBe("Helper — Zebra Labs");
    expect(brandDocumentTitle(null, zebra)).toBe("Zebra Labs");
  });

  it("config layer: empty when unbranded, title and accent when branded", () => {
    expect(brandConfigLayer(unbranded)).toEqual({});
    expect(brandConfigLayer(zebra)).toEqual({ title: "Zebra Labs", accentColor: "#0f766e" });
    expect(brandConfigLayer(readBrand({ ...ZEBRA, chat: { accent: null } }))).toEqual({ title: "Zebra Labs" });
  });
});
