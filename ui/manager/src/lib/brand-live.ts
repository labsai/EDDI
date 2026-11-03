import { setBrand } from "./brand";

/**
 * Per document: how many calls there have been, and the newest brand whose
 * script has loaded but was held back. An older call's script that loads late must not overwrite
 * a newer call's brand, and if the newest call's script fails the page falls
 * back to that loaded brand rather than to nothing.
 */
interface ReloadState {
  calls: number;
  /** The newest brand whose script loaded but was held back because a newer call was pending. */
  held: { call: number; brand: unknown } | null;
}
const states = new WeakMap<Document, ReloadState>();

function stateOf(doc: Document): ReloadState {
  let state = states.get(doc);
  if (!state) {
    state = { calls: 0, held: null };
    states.set(doc, state);
  }
  return state;
}

/**
 * Apply a brand the settings page just saved, without a reload (white-label
 * plan §4.3 item 4).
 *
 * 1. `brand.css`: the shell's `<link>` gets `?r=<revision>`, so the browser
 *    fetches the new tokens. It is swapped in place, so it keeps its position
 *    after the bundle's stylesheet and still wins.
 * 2. The favicon `<link>` gets the same query, so the tab icon follows an upload.
 * 3. `__brand__.js` is loaded again through a fresh `<script src>` (never
 *    `eval`ed — the page's `script-src 'self'` CSP holds), which reassigns
 *    `window.__EDDI_BRAND__`; `setBrand` then hands it to every subscriber:
 *    `useBrand()` components, i18next's `{{productName}}`, the logo URLs
 *    (`brandAssetUrl` busts them with the same revision) and the page title.
 *
 * Other open tabs pick the brand up on their next load. Resolves once the
 * script ran; rejects when it could not be loaded, after leaving the brand in
 * effect untouched.
 */
export function applyBrandRevision(revision: number, doc: Document = document): Promise<void> {
  const state = stateOf(doc);
  const call = ++state.calls;
  const query = `?r=${encodeURIComponent(String(revision))}`;

  const stylesheet = doc.querySelector<HTMLLinkElement>('link[rel="stylesheet"][href^="/branding/brand.css"]');
  if (stylesheet) {
    stylesheet.setAttribute("href", `/branding/brand.css${query}`);
  } else {
    const link = doc.createElement("link");
    link.rel = "stylesheet";
    link.href = `/branding/brand.css${query}`;
    doc.head.appendChild(link);
  }

  for (const icon of doc.querySelectorAll<HTMLLinkElement>('link[rel~="icon"][href^="/branding/"]')) {
    const href = icon.getAttribute("href") ?? "";
    icon.setAttribute("href", `${href.split("?")[0]}${query}`);
  }

  return new Promise<void>((resolve, reject) => {
    const script = doc.createElement("script");
    script.src = `/branding/__brand__.js${query}`;
    script.async = true;
    script.dataset.brandReload = String(revision);
    script.onload = () => {
      script.remove();
      // Two saves in quick succession load two scripts, in no guaranteed order;
      // only the newest may reach the subscribers.
      const brand = doc.defaultView?.__EDDI_BRAND__;
      if (call === state.calls) {
        state.held = null;
        setBrand(brand);
      } else if (!state.held || call > state.held.call) {
        state.held = { call, brand };
      }
      resolve();
    };
    script.onerror = () => {
      script.remove();
      // The newest call failed: apply the newest brand that did load but was
      // held back for it, instead of applying nothing.
      if (call === state.calls && state.held) {
        const { brand } = state.held;
        state.held = null;
        setBrand(brand);
      }
      reject(new Error("The brand script could not be loaded."));
    };
    doc.head.appendChild(script);
  });
}
