import { setBrand } from "./brand";

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
      setBrand(doc.defaultView?.__EDDI_BRAND__);
      resolve();
    };
    script.onerror = () => {
      script.remove();
      reject(new Error("The brand script could not be loaded."));
    };
    doc.head.appendChild(script);
  });
}
