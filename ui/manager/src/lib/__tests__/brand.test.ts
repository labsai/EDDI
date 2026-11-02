import { describe, it, expect, afterEach } from "vitest";
import i18n from "@/i18n/config";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import {
  brandAssetUrl,
  DEFAULT_BRAND,
  UPDATES_PATH,
  getBrand,
  isUpdateUiVisible,
  readBrand,
  setBrand,
  subscribeBrand,
} from "@/lib/brand";
import { DEFAULT_BRAND_SCRIPT } from "../../../scripts/vite-plugin-brand.mjs";

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

afterEach(() => setBrand(undefined));

describe("brand defaults", () => {
  it("equal what an unbranded EDDI serves as __brand__.js", () => {
    // The plugin's script is the one BrandDeliveryTest keeps equal to the
    // backend's, so this closes the chain: Java = plugin = brand.ts.
    const sandbox: { __EDDI_BRAND__?: unknown } = {};
    new Function("window", DEFAULT_BRAND_SCRIPT)(sandbox);
    expect(sandbox.__EDDI_BRAND__).toEqual(DEFAULT_BRAND);
    expect(readBrand(sandbox.__EDDI_BRAND__)).toEqual(DEFAULT_BRAND);
  });

  it("apply when no brand script ran (tests, a missing script)", () => {
    expect(readBrand(undefined)).toEqual(DEFAULT_BRAND);
    expect(getBrand()).toEqual(DEFAULT_BRAND);
  });
});

describe("readBrand", () => {
  it("takes a complete brand as it is", () => {
    expect(readBrand(ZEBRA)).toEqual(ZEBRA);
  });

  it("falls back field by field, and the short name follows the product name", () => {
    expect(readBrand({ branded: true, productName: "Acme", revision: "3", poweredBy: "no" })).toEqual({
      ...DEFAULT_BRAND,
      branded: true,
      productName: "Acme",
      shortName: "Acme",
    });
  });

  it("treats an empty or blank name as unset", () => {
    expect(readBrand({ productName: "  ", shortName: "" }).productName).toBe("EDDI");
    expect(readBrand({ productName: "  ", shortName: "" }).shortName).toBe("EDDI");
  });
});

describe("setBrand", () => {
  it("replaces the brand and notifies subscribers until they unsubscribe", () => {
    const seen: string[] = [];
    const unsubscribe = subscribeBrand(() => seen.push(getBrand().productName));
    setBrand(ZEBRA);
    unsubscribe();
    setBrand(undefined);
    expect(seen).toEqual(["Zebra Labs"]);
    expect(getBrand()).toEqual(DEFAULT_BRAND);
  });

  it("reaches {{productName}} in every translated string", () => {
    expect(i18n.t("app.title")).toBe("EDDI Manager");
    setBrand(ZEBRA);
    expect(i18n.t("app.title")).toBe("Zebra Labs Manager");
    expect(i18n.t("landing.title")).toBe("Welcome to Zebra Labs");
    // An explicit value still wins over the default variable.
    expect(i18n.t("app.title", { productName: "Other" })).toBe("Other Manager");
  });
});

describe("isUpdateUiVisible", () => {
  it("shows the update UI unbranded, hides it branded unless the brand opts in", () => {
    expect(isUpdateUiVisible(DEFAULT_BRAND)).toBe(true);
    expect(isUpdateUiVisible(readBrand(ZEBRA))).toBe(false);
    expect(isUpdateUiVisible(readBrand({ ...ZEBRA, showUpdateCheck: true }))).toBe(true);
  });
});

describe("asset flags", () => {
  it("default to no image of the brand's own, and ignore non-boolean values", () => {
    expect(readBrand({ branded: true }).assets).toEqual(DEFAULT_BRAND.assets);
    expect(readBrand({ assets: { logo: "yes", icon: true } }).assets).toEqual({
      ...DEFAULT_BRAND.assets,
      icon: true,
    });
  });
});

describe("brandAssetUrl", () => {
  it("keeps the shells' plain URL until a brand document exists", () => {
    expect(brandAssetUrl("logo", DEFAULT_BRAND)).toBe("/branding/logo");
  });

  it("busts the five-minute image cache with the revision", () => {
    expect(brandAssetUrl("logo-dark", readBrand(ZEBRA))).toBe("/branding/logo-dark?r=7");
  });
});

describe("UPDATES_PATH", () => {
  it("is the Updates route app.tsx declares literally (route-integrity reads it as text)", () => {
    expect(UPDATES_PATH).toBe("/manage/updates");
    const app = readFileSync(join(__dirname, "../../app.tsx"), "utf8");
    expect(app).toContain(`path="${UPDATES_PATH}"`);
  });
});
