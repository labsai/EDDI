import type { Components } from "react-markdown";
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
 *    DOMPurify; its defaults still allow `<form>`, form controls and inline
 *    `style`. {@link SAFE_HTML_SANITIZE_OPTIONS} forbids those so agent output
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
 * elements and inline style. None of these fall back to a CSP directive, so they
 * must be denied at sanitize time.
 */
export const SAFE_HTML_SANITIZE_OPTIONS: DomPurifyConfig = {
  FORBID_TAGS: ["form", "input", "button", "textarea", "select"],
  FORBID_ATTR: ["style"],
};
