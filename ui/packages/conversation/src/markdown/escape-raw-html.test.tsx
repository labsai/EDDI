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
