/* ──────────────────────────────────────────────
   Math and code-highlighting plugins, loaded on demand

   KaTeX and highlight.js together roughly triple the widget's bundle, and most
   conversations never show a formula or a code block. They are split into
   their own chunks and fetched the first time a message looks like it needs
   them; until then the message renders as plain markdown.
   ────────────────────────────────────────────── */

import { useEffect, useState } from "react";
import type { Options } from "react-markdown";

type Pluggable = NonNullable<Options["rehypePlugins"]>[number];

export interface MathPlugins {
  remarkMath: Pluggable;
  rehypeKatex: Pluggable;
}

export interface HighlightPlugins {
  rehypeHighlight: Pluggable;
}

let mathLoaded: MathPlugins | null = null;
let highlightLoaded: HighlightPlugins | null = null;
let mathPromise: Promise<MathPlugins> | null = null;
let highlightPromise: Promise<HighlightPlugins> | null = null;

function loadMath(): Promise<MathPlugins> {
  mathPromise ??= import("./rich-math").then((m) => (mathLoaded = m.default));
  return mathPromise;
}

function loadHighlight(): Promise<HighlightPlugins> {
  highlightPromise ??= import("./rich-highlight").then(
    (m) => (highlightLoaded = m.default),
  );
  return highlightPromise;
}

/** A `$…$` / `$$…$$` span, or a `\(…\)` / `\[…\]` one. Cheap and permissive. */
export function looksLikeMath(text: string): boolean {
  return /\$[^$\s][^$]*\$|\\\(|\\\[/.test(text);
}

/** A fenced code block. Inline code is not highlighted, so it does not count. */
export function looksLikeCode(text: string): boolean {
  return /(^|\n)\s*(```|~~~)/.test(text);
}

/**
 * The math and highlight plugins this text needs, once they have loaded; null
 * for either until then (and whenever it is not needed or is disabled).
 */
export function useRichPlugins(
  text: string,
  enableMath: boolean,
  enableCodeHighlight: boolean,
): { math: MathPlugins | null; highlight: HighlightPlugins | null } {
  const needsMath = enableMath && looksLikeMath(text);
  const needsCode = enableCodeHighlight && looksLikeCode(text);
  const [math, setMath] = useState<MathPlugins | null>(mathLoaded);
  const [highlight, setHighlight] = useState<HighlightPlugins | null>(highlightLoaded);

  useEffect(() => {
    if (!needsMath || math) return;
    let live = true;
    loadMath()
      .then((m) => {
        if (live) setMath(m);
      })
      .catch(() => {
        // A failed chunk load leaves the message as plain markdown.
        mathPromise = null;
      });
    return () => {
      live = false;
    };
  }, [needsMath, math]);

  useEffect(() => {
    if (!needsCode || highlight) return;
    let live = true;
    loadHighlight()
      .then((m) => {
        if (live) setHighlight(m);
      })
      .catch(() => {
        highlightPromise = null;
      });
    return () => {
      live = false;
    };
  }, [needsCode, highlight]);

  return {
    math: needsMath ? math : null,
    highlight: needsCode ? highlight : null,
  };
}
