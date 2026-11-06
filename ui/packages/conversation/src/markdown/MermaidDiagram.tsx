/* A ```mermaid block as a diagram — itself a lazy chunk of the renderer
   (Markdown.tsx), so nothing of it, let alone Mermaid, is in a first load. */

import { useEffect, useState, type ReactNode } from "react";
import { CopyButton } from "./Markdown";
import { useConversationText } from "../react/hooks";

type MermaidRenderer = (source: string, dark: boolean) => Promise<string>;
let mermaidPromise: Promise<MermaidRenderer> | null = null;
function loadMermaid(): Promise<MermaidRenderer> {
  mermaidPromise ??= import("./rich-mermaid").then((m) => m.renderMermaid);
  return mermaidPromise;
}

/** Whether the page is in its dark theme (the apps' class, the widget's attribute). */
function isDark(): boolean {
  const root = document.documentElement;
  return root.classList.contains("dark") || root.getAttribute("data-theme") === "dark";
}

/** How long a diagram's source must stay unchanged before it is drawn (a streaming reply grows). */
const DIAGRAM_SETTLE_MS = 400;

/**
 * The code block shows until Mermaid has loaded and drawn the diagram, and
 * stays when the source does not parse — a reply still streaming its diagram,
 * or a model's broken one.
 */
export function MermaidDiagram({ source, fallback }: { source: string; fallback: ReactNode }) {
  const t = useConversationText();
  const [svg, setSvg] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    const timer = setTimeout(() => {
      loadMermaid()
        .then((render) => render(source, isDark()))
        .then((markup) => {
          if (live) setSvg(markup);
        })
        .catch(() => {
          if (live) setSvg(null);
          // A failed chunk load may succeed next time.
          mermaidPromise = null;
        });
    }, DIAGRAM_SETTLE_MS);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [source]);
  if (!svg) return <>{fallback}</>;
  return (
    <div className="code-block code-block--diagram" data-testid="mermaid-diagram">
      <div
        className="mermaid-diagram"
        role="img"
        aria-label={t("conversation.diagram.label")}
        // Mermaid's own output under securityLevel "strict": labels sanitised
        // (DOMPurify), no scripts, links or click handlers.
        dangerouslySetInnerHTML={{ __html: svg }}
      />
      <CopyButton getText={() => source} label={t("conversation.copy.code")} className="code-block__copy" />
    </div>
  );
}
