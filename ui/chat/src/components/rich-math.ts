/* ──────────────────────────────────────────────
   KaTeX math — loaded on demand (see markdown-plugins.ts)
   ────────────────────────────────────────────── */

import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import "katex/dist/katex.min.css";

export default { remarkMath, rehypeKatex };
