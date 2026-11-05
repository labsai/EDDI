/* The raw-HTML parser loads lazily (markdown-plugins.ts), and GFM is parsed
   without its serialiser (remark-gfm-parse.ts). That is only safe if
   leaving it out changes nothing for a message without HTML, and if every
   message WITH HTML is detected — these pin both. */

import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeRaw from "rehype-raw";
import rehypeSanitize from "rehype-sanitize";

import { looksLikeHtml } from "./markdown-plugins";
import { Message as MessageBubble } from "../components/Message";
import { remarkGfmParse } from "./remark-gfm-parse";

const WITHOUT_HTML = [
  "Plain text.",
  "**bold**, _em_, `code`, ~~strike~~ and a [link](https://example.com).",
  "An autolink https://example.com and an email jane@example.com.",
  "Entities: &amp; &lt;b&gt; &copy; 5 > 3 and 2 < 3.",
  "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |",
  "- one\n- two\n  - nested\n\n1. first\n2. second",
  "- [x] done\n- [ ] open",
  "> quoted\n>\n> twice",
  "```js\nconst a = 1 < 2;\n```",
  "Footnote[^1].\n\n[^1]: The note.",
  "# H1\n\n## H2\n\n---\n\n![alt](https://example.com/x.png)",
  "Line one  \nline two",
];

const WITH_HTML = [
  "<b>bold</b>",
  "<div>\nblock\n</div>",
  "text <br/> text",
  "<!-- a comment -->",
  "<?php echo 1; ?>",
  "<!DOCTYPE html>",
  "<![CDATA[x]]>",
  "a </span> b",
  '<img src="https://evil.example/p.png" onerror="alert(1)">',
];

function html(text: string, rehype: Parameters<typeof ReactMarkdown>[0]["rehypePlugins"]) {
  return renderToStaticMarkup(
    <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={rehype}>
      {text}
    </ReactMarkdown>,
  );
}

/**
 * The markup with the two differences the parser's round trip makes and a
 * browser does not show taken out:
 *
 *  - parse5 foster-parents the newline text nodes between table rows to just
 *    before the `<table>`. Whitespace between blocks collapses (the bubble is
 *    not `pre-wrap`), so nothing renders there.
 *  - it serialises GFM's boolean `data-footnote-ref` / `data-footnotes` as `""`
 *    rather than `"true"`. Both are present, which is all `[data-…]` selectors
 *    and assistive tech read.
 */
function rendered(markup: string): string {
  return markup.replace(/>\s+</g, "><").replace(/^\s+/, "").replace(/(data-footnotes?(-ref)?)="true"/g, '$1=""');
}

describe("raw-HTML parser is loaded only for HTML", () => {
  it.each(WITHOUT_HTML)("needs no parser, and renders the same without it: %s", (text) => {
    expect(looksLikeHtml(text)).toBe(false);
    expect(rendered(html(text, [rehypeSanitize]))).toBe(rendered(html(text, [rehypeRaw, rehypeSanitize])));
  });

  it.each(WITH_HTML)("is detected as HTML: %s", (text) => {
    expect(looksLikeHtml(text)).toBe(true);
  });

  it("renders a reply's HTML exactly as before once the parser is in", async () => {
    const text = "Hello <b data-x=1>world</b> <script>alert(1)</script>";
    const { container } = render(
      <MessageBubble message={{ id: "m1", role: "agent", content: text, timestamp: 0 }} />,
    );
    const bold = await screen.findByText("world", { selector: "b" });
    // Sanitised as always: the script and the unknown attribute are gone.
    expect(bold.getAttribute("data-x")).toBeNull();
    expect(container.querySelector("script")).toBeNull();
    const body = container.querySelector(".markdown-body")!.innerHTML;
    const expected = renderToStaticMarkup(
      <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeRaw, rehypeSanitize]}>
        {text}
      </ReactMarkdown>,
    );
    expect(body).toBe(expected);
  });
});

describe("GFM without the serialiser", () => {
  it.each([...WITHOUT_HTML, ...WITH_HTML])("renders exactly as remark-gfm: %s", (text) => {
    const withParse = renderToStaticMarkup(
      <ReactMarkdown remarkPlugins={[remarkGfmParse]} rehypePlugins={[rehypeRaw, rehypeSanitize]}>
        {text}
      </ReactMarkdown>,
    );
    expect(withParse).toBe(html(text, [rehypeRaw, rehypeSanitize]));
  });
});
