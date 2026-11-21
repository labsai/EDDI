/* `escapeRawHtml`: raw HTML shown as the text it is (third-party release
   notes, whose `<conversationId>` placeholders are prose). */

import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import { MarkdownContent } from "./Markdown";

describe("MarkdownContent — escapeRawHtml", () => {
  it("shows tags as text and renders none of them", () => {
    const { container } = render(
      <MarkdownContent escapeRawHtml>{'Use <conversationId> here. <img src=x onerror="alert(1)"> **bold**'}</MarkdownContent>,
    );
    expect(container.textContent).toContain("Use <conversationId> here.");
    expect(container.textContent).toContain('<img src=x onerror="alert(1)">');
    expect(container.querySelector("img")).toBeNull();
    // Markdown itself still renders.
    expect(container.querySelector("strong")?.textContent).toBe("bold");
  });

  it("drops raw HTML when it is neither rendered nor escaped", () => {
    const { container } = render(<MarkdownContent enableRawHtml={false}>{"a <b>b</b> c"}</MarkdownContent>);
    expect(container.querySelector("b")).toBeNull();
    expect(container.textContent).not.toContain("<b>");
  });
});

describe("MarkdownContent — raw HTML is off unless a host enables it", () => {
  it("drops raw HTML by default", () => {
    const { container } = render(<MarkdownContent>{"a <b>b</b> c"}</MarkdownContent>);
    expect(container.querySelector("b")).toBeNull();
  });

  it("renders it, sanitised, when the host enables it", async () => {
    const { container, findByText } = render(
      <MarkdownContent enableRawHtml>{"a <b>b</b> <script>alert(1)</script> c"}</MarkdownContent>,
    );
    expect((await findByText("b", { selector: "b" })).tagName).toBe("B");
    expect(container.querySelector("script")).toBeNull();
  });
});

/** A destination as a browser reads it: tabs and newlines dropped, leading whitespace and controls skipped, `\` as `/`. */
function browserReads(href: string): string {
  return href
    .replace(/[\t\n\r]/g, "")
    // eslint-disable-next-line no-control-regex
    .replace(/^[\u0000-\u0020]+/, "")
    .replace(/\\/g, "/");
}

describe("MarkdownContent — protocol-relative links", () => {
  it("shows the text of a //host link instead of linking it", () => {
    const { container } = render(<MarkdownContent>{"[click](//evil.example/x) and [ok](https://ok.example/)"}</MarkdownContent>);
    expect(container.textContent).toContain("click");
    expect(container.querySelector('a[href^="//"]')).toBeNull();
    expect(container.querySelector('a[href="https://ok.example/"]')).not.toBeNull();
  });

  // The Markdown parser percent-encodes a backslash, a space, a tab and a
  // control character in a destination, so none of these can reach the browser
  // as a protocol-relative URL. Pinned so a parser change cannot reopen it.
  it.each([
    ["slash then backslash", "[click](/\\evil.example/x)"],
    ["double backslash", "[click](\\\\evil.example/x)"],
    ["leading space in an angle destination", "[click](< //evil.example/x>)"],
    ["a tab between the slashes", "[click](</\t/evil.example/x>)"],
    ["leading control character", "[click](<\u0001//evil.example/x>)"],
  ])("never produces a host-relative link for %s", (_name, md) => {
    const { container } = render(<MarkdownContent>{md}</MarkdownContent>);
    for (const a of Array.from(container.querySelectorAll("a"))) {
      expect(browserReads(a.getAttribute("href") ?? "")).not.toMatch(/^\/\//);
    }
  });

  // Raw HTML keeps the attribute as written, so the component's own check is
  // what stops these (an agent with rawHtml enabled).
  it.each([
    ["backslash", '<a href="/\\evil.example/x">click</a>'],
    ["double backslash", '<a href="\\\\evil.example/x">click</a>'],
    ["leading space", '<a href=" //evil.example/x">click</a>'],
    ["tab between the slashes", '<a href="/\t/evil.example/x">click</a>'],
    ["leading control character", '<a href="\u0001//evil.example/x">click</a>'],
  ])("does not link raw HTML with %s", async (_name, html) => {
    const { container, findByText } = render(<MarkdownContent enableRawHtml>{html}</MarkdownContent>);
    await findByText("click");
    expect(container.querySelector("a")).toBeNull();
  });

  it("does not offer an image at //host as a link or load it; only its alt text shows", () => {
    const { container } = render(<MarkdownContent>{"![the logo](//evil.example/a.png)"}</MarkdownContent>);
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("a")).toBeNull();
    expect(container.textContent).toBe("the logo");
  });

  it("does not offer a raw-HTML image at /\\host either", async () => {
    const { container, findByText } = render(
      <MarkdownContent enableRawHtml>{'<img src="/\\evil.example/a.png" alt="logo">'}</MarkdownContent>,
    );
    await findByText("logo");
    expect(container.querySelector("a")).toBeNull();
    expect(container.querySelector("img")).toBeNull();
  });

  it("still links an ordinary image and a root-relative path", () => {
    const { container } = render(<MarkdownContent>{"![logo](https://ok.example/a.png) [p](/docs/page)"}</MarkdownContent>);
    expect(container.querySelector('a[href="https://ok.example/a.png"]')).not.toBeNull();
    expect(container.querySelector('a[href="/docs/page"]')).not.toBeNull();
  });
});
