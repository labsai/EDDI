/* ──────────────────────────────────────────────
   KaTeX math — loaded on demand (see markdown-plugins.ts)
   ────────────────────────────────────────────── */

import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import "katex/dist/katex.min.css";
import type { MathPlugins } from "./plugin-types";

const plugins: MathPlugins = {
  // Only `$$…$$` is math. With single-dollar math on (the default), any reply
  // quoting two prices — "$5 and $10" — rendered the span between them as a
  // formula and garbled the text.
  remarkMath: [remarkMath, { singleDollarTextMath: false }],
  // `trust: false` (KaTeX's default, stated): no \href, \url or \includegraphics
  // from model output.
  rehypeKatex: [rehypeKatex, { trust: false }],
};

export default plugins;
