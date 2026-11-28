/* Same-page links must land on the ids rehype-sanitize leaves behind: it
   prefixes every id (clobber protection) but not the hrefs pointing at them. */

import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";

import { MarkdownContent } from "./Markdown";

function fragmentLinks(container: HTMLElement): HTMLAnchorElement[] {
  return [...container.querySelectorAll<HTMLAnchorElement>('a[href^="#"]')];
}

function target(container: HTMLElement, link: HTMLAnchorElement): Element | null {
  const id = decodeURIComponent(link.getAttribute("href")!.slice(1));
  return [...container.querySelectorAll("[id]")].find((el) => el.id === id) ?? null;
}

describe("MarkdownContent fragment links", () => {
  it("points every footnote link at an element that exists", () => {
    const { container } = render(<MarkdownContent>{"Footnote[^1] and another[^note].\n\n[^1]: The note.\n\n[^note]: Second."}</MarkdownContent>);
    const links = fragmentLinks(container);
    // Two references and two back-references.
    expect(links.length).toBe(4);
    for (const link of links) expect(target(container, link), link.outerHTML).not.toBeNull();
  });

  it("keeps the sanitizer's prefix on the ids themselves", () => {
    const { container } = render(<MarkdownContent>{"Footnote[^1].\n\n[^1]: The note."}</MarkdownContent>);
    for (const el of container.querySelectorAll("[id]")) expect(el.id.startsWith("user-content-")).toBe(true);
  });

  it("leaves off-page links opening in a new tab", () => {
    const { container } = render(<MarkdownContent>{"[x](https://example.com/#frag)"}</MarkdownContent>);
    const a = container.querySelector("a")!;
    expect(a.getAttribute("href")).toBe("https://example.com/#frag");
    expect(a.getAttribute("target")).toBe("_blank");
  });
});
