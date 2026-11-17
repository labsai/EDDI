/* Mermaid, in its own chunk: loaded the first time a reply holds a ```mermaid
   block, never in any app's first load. `securityLevel: "strict"` (§2.5): the
   diagram's labels are sanitised and its links and click handlers disabled —
   the source is model output. */

import mermaid from "mermaid";

let initialisedFor: "dark" | "default" | null = null;
let counter = 0;

/** Render one diagram's source to SVG markup; throws on a syntax error. */
export async function renderMermaid(source: string, dark: boolean): Promise<string> {
  const theme = dark ? "dark" : "default";
  if (initialisedFor !== theme) {
    // suppressErrorRendering: a source that does not parse (a reply still
    // streaming its diagram) throws, and MermaidDiagram keeps the code block;
    // without it Mermaid also appends its error diagram to document.body.
    mermaid.initialize({ startOnLoad: false, securityLevel: "strict", theme, suppressErrorRendering: true });
    initialisedFor = theme;
  }
  counter += 1;
  const { svg } = await mermaid.render(`eddi-mermaid-${counter}`, source);
  return svg;
}
