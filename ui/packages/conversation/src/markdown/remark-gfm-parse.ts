/* ──────────────────────────────────────────────
   GFM for reading only

   remark-gfm registers three extensions: the tokenizer (micromark), the tree
   builder (from-markdown) and the SERIALISER (to-markdown). A renderer never
   turns a tree back into markdown, but the serialiser — mdast-util-to-markdown,
   markdown-table and the to-markdown half of every GFM utility — still landed
   in the widget's first load, about 4 KB of gzip. This is remark-gfm without
   it: the same tables, footnotes, autolinks, strikethrough and task lists.
   ────────────────────────────────────────────── */

import { gfmFromMarkdown } from "mdast-util-gfm";
import { gfm } from "micromark-extension-gfm";

interface ParserData {
  micromarkExtensions?: unknown[];
  fromMarkdownExtensions?: unknown[];
}

/** A unified plugin: GFM parsing, as `remark-gfm` with its defaults, without serialising. */
export function remarkGfmParse(this: { data(): object }): void {
  // unified's Data is declared empty and extended by each utility's types.
  const data = this.data() as ParserData;
  (data.micromarkExtensions ??= []).push(gfm());
  (data.fromMarkdownExtensions ??= []).push(gfmFromMarkdown());
}
