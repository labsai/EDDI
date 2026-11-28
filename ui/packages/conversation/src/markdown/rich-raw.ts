/* ──────────────────────────────────────────────
   Raw HTML in agent replies — loaded on demand (see markdown-plugins.ts)

   rehype-raw re-parses the tree with parse5, an HTML5 parser about as large
   as the widget's own code. It only does anything for a reply that contains
   HTML, so it is fetched the first time one does. The output is unchanged:
   the markup still goes through rehype-sanitize right after.
   ────────────────────────────────────────────── */

import rehypeRaw from "rehype-raw";
import type { RawHtmlPlugins } from "./plugin-types";

const plugins: RawHtmlPlugins = { rehypeRaw };

export default plugins;
