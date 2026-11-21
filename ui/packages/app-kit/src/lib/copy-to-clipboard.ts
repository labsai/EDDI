/**
 * Copies `text` to the clipboard and says whether it worked.
 *
 * `navigator.clipboard` exists only in a secure context (https or localhost) and
 * its promise rejects when the page is not focused or permission is refused. A
 * caller that fired `writeText` and showed "Copied" straight away was reporting
 * success for a copy that had not happened — over plain http, which is how many
 * internal deployments run, it never did. This awaits the promise, falls back to
 * the legacy `execCommand("copy")` route for a page without the async API, and
 * resolves `false` — never throws — when neither worked, so the caller can show
 * the failure instead.
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    if (typeof navigator !== "undefined" && navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    // Fall through to the legacy route: the async API refused (permission, focus).
  }
  return legacyCopy(text);
}

function legacyCopy(text: string): boolean {
  if (typeof document === "undefined" || typeof document.execCommand !== "function") return false;
  const area = document.createElement("textarea");
  area.value = text;
  area.setAttribute("readonly", "");
  area.style.position = "fixed";
  area.style.insetBlockStart = "0";
  area.style.opacity = "0";
  document.body.appendChild(area);
  const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  try {
    area.select();
    return document.execCommand("copy");
  } catch {
    return false;
  } finally {
    area.remove();
    previous?.focus();
  }
}
