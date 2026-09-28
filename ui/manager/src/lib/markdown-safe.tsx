import type { Components } from "react-markdown";
import DOMPurify from "dompurify";
import type { Config as DomPurifyConfig } from "dompurify";

/**
 * Shared hardening for rendering untrusted LLM / agent output as markdown or
 * sanitized HTML.
 *
 * Two zero-click risks are closed here, both independent of any CSP:
 *
 * 1. **Image beacons.** A markdown image `![](https://attacker/pixel.png)` (or a
 *    raw `<img>`) is fetched the instant the message renders — a beacon to an
 *    arbitrary host and an exfil channel for anything reflected into the reply.
 *    {@link markdownImageAsLink} renders every image as a plain link instead, so
 *    the URL stays visible and reachable but nothing loads until a human clicks.
 *
 * 2. **Injected forms / inline style.** The opt-in "render HTML" path runs
 *    DOMPurify; its defaults still allow `<form>`, form controls, inline
 *    `style` and `<style>` elements. {@link SAFE_HTML_SANITIZE_OPTIONS} forbids those so agent output
 *    cannot draw a fake form or restyle the page.
 */

/**
 * react-markdown component overrides: render `img` as a link, never a live
 * `<img>`. Mirrors the override previously local to update-check-card.
 */
export const markdownImageAsLink: Components = {
  img: ({ src, alt, title }) => {
    const href = typeof src === "string" ? src : undefined;
    const label = (alt && alt.trim()) || href || "image";
    if (!href) return <span className="text-muted-foreground italic">{label}</span>;
    return (
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        title={title ?? href}
        className="text-primary underline underline-offset-2"
        data-testid="markdown-image-link"
      >
        {label}
      </a>
    );
  },
};

/**
 * DOMPurify options for the opt-in HTML render path: forbid interactive form
 * elements, inline style (attribute AND `<style>` element — DOMPurify keeps the
 * element by default, and a nested stylesheet restyles the whole Manager), and
 * the media / legacy attributes that fetch a URL on render (`<video>`,
 * `<audio>`, `<source>`, `<track>`, `<picture>`, `srcset`, `background`). None
 * of these fall back to a CSP directive, so they must be denied at sanitize
 * time. `<img>` is kept here only so {@link sanitizeAgentHtml} can turn it into
 * a link — never pass these options to DOMPurify directly for insertion.
 */
export const SAFE_HTML_SANITIZE_OPTIONS: DomPurifyConfig = {
  FORBID_TAGS: [
    "form",
    "input",
    "button",
    "textarea",
    "select",
    "style",
    "video",
    "audio",
    "source",
    "track",
    "picture",
  ],
  FORBID_ATTR: ["style", "srcset", "background"],
};

/**
 * Sanitizes untrusted agent HTML for `dangerouslySetInnerHTML`: DOMPurify with
 * {@link SAFE_HTML_SANITIZE_OPTIONS}, then every `<img>` rewritten to a plain
 * link — the HTML-path counterpart of {@link markdownImageAsLink}, so a raw
 * `<img src="https://attacker/pixel">` is not fetched until a human clicks.
 * Only http(s) sources become links; anything else is reduced to its label.
 */
export function sanitizeAgentHtml(html: string): string {
  const fragment = DOMPurify.sanitize(html, {
    ...SAFE_HTML_SANITIZE_OPTIONS,
    RETURN_DOM_FRAGMENT: true,
  });
  fragment.querySelectorAll("img").forEach((img) => {
    const src = img.getAttribute("src") ?? "";
    const label = img.getAttribute("alt")?.trim() || src || "image";
    let replacement: HTMLElement;
    if (/^https?:\/\//i.test(src)) {
      const link = document.createElement("a");
      link.href = src;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      link.title = src;
      link.className = "text-primary underline underline-offset-2";
      link.dataset.testid = "markdown-image-link";
      link.textContent = label;
      replacement = link;
    } else {
      replacement = document.createElement("span");
      replacement.className = "text-muted-foreground italic";
      replacement.textContent = label;
    }
    img.replaceWith(replacement);
  });
  const container = document.createElement("div");
  container.appendChild(fragment);
  return container.innerHTML;
}
