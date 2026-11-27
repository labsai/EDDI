/* The shapes of the lazily loaded renderer plugins (markdown-plugins.ts and
   the rich-*.ts chunks it imports), in a module of their own so the chunks do
   not import their loader back. */

import type { Options } from "react-markdown";

export type Pluggable = NonNullable<Options["rehypePlugins"]>[number];

export interface MathPlugins {
  remarkMath: Pluggable;
  rehypeKatex: Pluggable;
}

export interface HighlightPlugins {
  rehypeHighlight: Pluggable;
}

export interface RawHtmlPlugins {
  rehypeRaw: Pluggable;
}
