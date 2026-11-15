/* ──────────────────────────────────────────────
   Math, code-highlighting and raw-HTML plugins, loaded on demand

   KaTeX and highlight.js together roughly triple the widget's bundle, and most
   conversations never show a formula or a code block. They are split into
   their own chunks and fetched the first time a message looks like it needs
   them; until then the message renders as plain markdown.

   The raw-HTML parser (rehype-raw, which brings parse5) is the same kind of
   weight for the same kind of message: most replies contain no HTML at all.
   It loads the first time one does. Until it is in, that message's HTML is
   left out (rehype-sanitize drops the unparsed nodes) and its text shows;
   once it is in, the message renders exactly as it always did. Whether an
   agent's HTML is rendered at all becomes a per-agent setting later
   (frontend unification plan, D9) — until then it always is.
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

export interface RawHtmlPlugins {
  rehypeRaw: Pluggable;
}

let mathLoaded: MathPlugins | null = null;
let highlightLoaded: HighlightPlugins | null = null;
let rawLoaded: RawHtmlPlugins | null = null;
let mathPromise: Promise<MathPlugins> | null = null;
let highlightPromise: Promise<HighlightPlugins> | null = null;
let rawPromise: Promise<RawHtmlPlugins> | null = null;

function loadRaw(): Promise<RawHtmlPlugins> {
  rawPromise ??= import("./rich-raw").then((m) => (rawLoaded = m.default));
  return rawPromise;
}

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

/**
 * A `$$…$$` span — the only math syntax enabled (see rich-math.ts). Single
 * dollars are prices far more often than formulas.
 */
export function looksLikeMath(text: string): boolean {
  return /\$\$[\s\S]*?\S[\s\S]*?\$\$/.test(text);
}

/** A fenced code block. Inline code is not highlighted, so it does not count. */
export function looksLikeCode(text: string): boolean {
  return /(^|\n)\s*(```|~~~)/.test(text);
}

/**
 * Anything markdown could parse as HTML. Every HTML construct CommonMark knows
 * — a block, an open or closing tag, a comment, a processing instruction, a
 * declaration, CDATA — starts with `<` and then a letter, `/`, `!` or `?`, so
 * text without that pair yields no HTML node and needs no parser. Erring the
 * other way (an autolink `<https://…>`, a comparison `a <b`) only loads the
 * chunk early; the output is the same either way.
 */
export function looksLikeHtml(text: string): boolean {
  return /<[A-Za-z/!?]/.test(text);
}

/**
 * The math, highlight and raw-HTML plugins this text needs, once they have
 * loaded; null for each until then (and whenever it is not needed or is
 * disabled).
 */
export function useRichPlugins(
  text: string,
  enableMath: boolean,
  enableCodeHighlight: boolean,
  enableRawHtml = true,
): {
  math: MathPlugins | null;
  highlight: HighlightPlugins | null;
  raw: RawHtmlPlugins | null;
} {
  const needsMath = enableMath && looksLikeMath(text);
  const needsCode = enableCodeHighlight && looksLikeCode(text);
  const needsRaw = enableRawHtml && looksLikeHtml(text);
  const [math, setMath] = useState<MathPlugins | null>(mathLoaded);
  const [highlight, setHighlight] = useState<HighlightPlugins | null>(highlightLoaded);
  const [raw, setRaw] = useState<RawHtmlPlugins | null>(rawLoaded);

  useEffect(() => {
    if (!needsRaw || raw) return;
    let live = true;
    loadRaw()
      .then((m) => {
        if (live) setRaw(m);
      })
      .catch(() => {
        // A failed chunk load leaves the HTML out and the text in.
        rawPromise = null;
      });
    return () => {
      live = false;
    };
  }, [needsRaw, raw]);

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
    raw: needsRaw ? raw : null,
  };
}
