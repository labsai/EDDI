import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { applyBrandRevision } from "@/lib/brand-live";
import { getBrand, setBrand } from "@/lib/brand";

/** The shell's own tags, as manage.html carries them. */
function mountShell() {
  document.head.innerHTML =
    '<link rel="icon" href="/branding/icon" />' +
    '<link rel="stylesheet" href="/assets/index.css" />' +
    '<link rel="stylesheet" href="/branding/brand.css" />';
}

function pendingScript(): HTMLScriptElement {
  const script = document.head.querySelector<HTMLScriptElement>("script[data-brand-reload]");
  if (!script) throw new Error("no brand script was inserted");
  return script;
}

beforeEach(mountShell);
afterEach(() => {
  document.head.innerHTML = "";
  delete window.__EDDI_BRAND__;
  setBrand(undefined);
});

describe("applyBrandRevision", () => {
  it("swaps brand.css and the favicon in place, re-runs __brand__.js and hands it to setBrand", async () => {
    const done = applyBrandRevision(8);

    const links = [...document.head.querySelectorAll("link")].map((l) => l.getAttribute("href"));
    // Same position: after the bundle's stylesheet, so the brand still wins.
    expect(links).toEqual(["/branding/icon?r=8", "/assets/index.css", "/branding/brand.css?r=8"]);

    const script = pendingScript();
    expect(script.getAttribute("src")).toBe("/branding/__brand__.js?r=8");

    // What the re-fetched script does when it runs.
    window.__EDDI_BRAND__ = { branded: true, revision: 8, productName: "Acme Assist" };
    script.dispatchEvent(new Event("load"));
    await done;

    expect(getBrand().productName).toBe("Acme Assist");
    expect(getBrand().revision).toBe(8);
    expect(document.head.querySelector("script[data-brand-reload]")).toBeNull();
  });

  it("leaves the brand in effect when the script fails to load", async () => {
    setBrand({ branded: true, revision: 2, productName: "Before" });
    const done = applyBrandRevision(3);
    pendingScript().dispatchEvent(new Event("error"));
    await expect(done).rejects.toThrow();
    expect(getBrand().productName).toBe("Before");
  });

  it("adds the stylesheet when the page has none (the dev entry without a shell)", async () => {
    document.head.innerHTML = "";
    const done = applyBrandRevision(1);
    expect(document.head.querySelector('link[rel="stylesheet"]')?.getAttribute("href")).toBe("/branding/brand.css?r=1");
    pendingScript().dispatchEvent(new Event("load"));
    await done;
  });
});
