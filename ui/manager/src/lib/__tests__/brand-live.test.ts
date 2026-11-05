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

  it("an older script that loads after a newer one does not overwrite the newer brand", async () => {
    const first = applyBrandRevision(10);
    const olderScript = pendingScript();
    const second = applyBrandRevision(11);
    const newerScript = [...document.head.querySelectorAll<HTMLScriptElement>("script[data-brand-reload]")].find(
      (s) => s.dataset.brandReload === "11",
    )!;

    window.__EDDI_BRAND__ = { branded: true, revision: 11, productName: "Newer" };
    newerScript.dispatchEvent(new Event("load"));
    await second;

    window.__EDDI_BRAND__ = { branded: true, revision: 10, productName: "Older" };
    olderScript.dispatchEvent(new Event("load"));
    await first;

    expect(getBrand().productName).toBe("Newer");
  });

  it("when the newest script fails, applies the older brand that had loaded", async () => {
    const older = applyBrandRevision(20);
    const olderScript = pendingScript();
    const newer = applyBrandRevision(21);
    const newerScript = [...document.head.querySelectorAll<HTMLScriptElement>("script[data-brand-reload]")].find(
      (s) => s.dataset.brandReload === "21",
    )!;

    window.__EDDI_BRAND__ = { branded: true, revision: 20, productName: "Older" };
    olderScript.dispatchEvent(new Event("load"));
    await older;
    expect(getBrand().productName).not.toBe("Older"); // held back: a newer call is pending

    newerScript.dispatchEvent(new Event("error"));
    await expect(newer).rejects.toThrow();
    expect(getBrand().productName).toBe("Older");
  });

  it("keeps calls on different documents apart", async () => {
    const other = document.implementation.createHTMLDocument("other");
    const first = applyBrandRevision(30);
    const firstScript = pendingScript();
    const second = applyBrandRevision(31, other);
    const secondScript = other.head.querySelector<HTMLScriptElement>("script[data-brand-reload]")!;

    // The other document's call must not count as "newer" for this one.
    window.__EDDI_BRAND__ = { branded: true, revision: 30, productName: "Mine" };
    firstScript.dispatchEvent(new Event("load"));
    await first;
    expect(getBrand().productName).toBe("Mine");

    secondScript.dispatchEvent(new Event("error"));
    await expect(second).rejects.toThrow();
  });

  /** The pending reload script for a revision. */
  function script(revision: number): HTMLScriptElement {
    const found = [...document.head.querySelectorAll<HTMLScriptElement>("script[data-brand-reload]")].find(
      (el) => el.dataset.brandReload === String(revision),
    );
    if (!found) throw new Error(`no script for revision ${revision}`);
    return found;
  }
  const load = (script: HTMLScriptElement, name: string, revision: number) => {
    window.__EDDI_BRAND__ = { branded: true, revision, productName: name };
    script.dispatchEvent(new Event("load"));
  };

  it("never applies a brand older than one already applied, even after a later failure", async () => {
    const r10 = applyBrandRevision(40);
    const r11 = applyBrandRevision(41);
    const r12 = applyBrandRevision(42);
    load(script(41), "Rev41", 41); // held: 42 is pending
    expect(getBrand().productName).not.toBe("Rev41");
    // 42 loads first and is applied, then 40 arrives late and must be discarded.
    load(script(42), "Rev42", 42);
    await r12;
    load(script(40), "Rev40", 40);
    await r10;
    await r11;
    expect(getBrand().productName).toBe("Rev42");

    // A later failing call must not resurrect the discarded brand.
    const r13 = applyBrandRevision(43);
    script(43).dispatchEvent(new Event("error"));
    await expect(r13).rejects.toThrow();
    expect(getBrand().productName).toBe("Rev42");
  });

  it("applies an older successful load that arrives after the newest call already failed", async () => {
    const older = applyBrandRevision(50);
    const newer = applyBrandRevision(51);
    script(51).dispatchEvent(new Event("error"));
    await expect(newer).rejects.toThrow();
    expect(getBrand().productName).not.toBe("Late");

    load(script(50), "Late", 50);
    await older;
    expect(getBrand().productName).toBe("Late");
  });
});
