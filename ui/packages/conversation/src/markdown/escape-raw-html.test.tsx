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

describe("MarkdownContent — protocol-relative links", () => {
  it("shows the text of a //host link instead of linking it", () => {
    const { container } = render(<MarkdownContent>{"[click](//evil.example/x) and [ok](https://ok.example/)"}</MarkdownContent>);
    expect(container.textContent).toContain("click");
    expect(container.querySelector('a[href^="//"]')).toBeNull();
    expect(container.querySelector('a[href="https://ok.example/"]')).not.toBeNull();
  });
});
