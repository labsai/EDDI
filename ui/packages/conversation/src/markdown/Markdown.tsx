/* ──────────────────────────────────────────────
   The one markdown renderer (frontend unification plan §2.5)

   Every reply an agent writes goes through here, on every surface:

   - Images in the TEXT become links. LLM output is untrusted, and a markdown
     image (or a raw `<img>`) is a request to a URL the model chose — a
     tracking pixel, or a way to carry conversation data out in a query string.
     Images an agent designer configured arrive as `image` output items and are
     rendered by `Message`, not here.
   - Raw HTML only through the sanitizer. The parser for it (rehype-raw → parse5)
     loads the first time a reply contains HTML. Whether an agent's HTML is
     rendered at all becomes a per-agent setting (D9, `conversationControls
     .rawHtml`, phase 4); until then it is, for every agent.
   - Math (`$$…$$` only; single dollars are prices) through KaTeX with
     `trust: false`, and fenced code through highlight.js — each its own chunk,
     loaded the first time a reply needs it.
   - Links open in a new tab, except same-page anchors (GFM footnotes); wide
     tables scroll inside the reply; a fenced block has its own copy button.
   ────────────────────────────────────────────── */

import { memo, useCallback, useEffect, useMemo, useRef, useState, type ComponentPropsWithoutRef } from "react";
import { Check, Copy, X } from "lucide-react";
import ReactMarkdown, { type Components, type Options } from "react-markdown";
import { remarkGfmParse as remarkGfm } from "./remark-gfm-parse";
import rehypeSanitize from "rehype-sanitize";
import { useRichPlugins } from "./markdown-plugins";
import { useConversationText } from "../react/hooks";

type PluggableList = NonNullable<Options["rehypePlugins"]>;

/** Write text to the clipboard; false when the browser refuses (insecure frame, no permission). */
async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

/** A copy button that confirms for a moment, visually and to screen readers. */
export function CopyButton({
  getText,
  label,
  className,
}: {
  getText: () => string;
  label: string;
  className: string;
}) {
  const t = useConversationText();
  // Last outcome, shown for a moment: a refused copy must not look like a
  // button that did nothing.
  const [status, setStatus] = useState<"idle" | "done" | "failed">("idle");
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );
  const onClick = useCallback(async () => {
    const ok = await copyText(getText());
    setStatus(ok ? "done" : "failed");
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setStatus("idle"), 1500);
  }, [getText]);
  const shown =
    status === "done" ? t("conversation.copy.done") : status === "failed" ? t("conversation.copy.failed") : label;
  return (
    <button
      type="button"
      className={className}
      onClick={onClick}
      aria-label={shown}
      title={shown}
      data-copy-status={status}
      data-testid="copy-button"
    >
      {status === "done" ? <Check size="1em" /> : status === "failed" ? <X size="1em" /> : <Copy size="1em" />}
    </button>
  );
}

/** A fenced code block with its own copy button. */
function CodeBlock({ node: _node, children, ...props }: ComponentPropsWithoutRef<"pre"> & { node?: unknown }) {
  const t = useConversationText();
  const preRef = useRef<HTMLPreElement>(null);
  const getText = useCallback(() => preRef.current?.textContent ?? "", []);
  return (
    <div className="code-block">
      <pre {...props} ref={preRef}>
        {children}
      </pre>
      <CopyButton getText={getText} label={t("conversation.copy.code")} className="code-block__copy" />
    </div>
  );
}

/**
 * GitHub's default sanitize schema. It keeps `<img>` so that the component
 * override below can see it. rehype-raw runs in front of it only for a reply
 * that contains HTML, once its chunk has loaded; without HTML in the text it
 * has nothing to parse, so leaving it out changes nothing but the bundle.
 */
const BASE_REHYPE: PluggableList = [rehypeSanitize];
const REMARK_PLAIN: PluggableList = [remarkGfm];

interface MdastNode {
  type: string;
  value?: string;
  children?: MdastNode[];
}

/**
 * Raw HTML as the text it is (`escapeRawHtml`): every `html` node becomes a
 * `text` node, so `<conversationId>` in third-party notes reads as written
 * instead of vanishing as an unknown tag. Without it, raw HTML is either
 * sanitised and rendered (`enableRawHtml`) or dropped.
 */
function remarkHtmlAsText() {
  const visit = (node: MdastNode): void => {
    if (node.type === "html") node.type = "text";
    node.children?.forEach(visit);
  };
  return (tree: MdastNode) => visit(tree);
}

const REMARK_ESCAPED: PluggableList = [remarkGfm, remarkHtmlAsText];

/**
 * Links open in a new tab. A plain link navigated the page itself away —
 * inside the widget's iframe that is the whole chat, and the conversation was
 * lost. Same-page anchors (`#…`, GFM footnote references) stay in the page.
 * `node` is react-markdown's AST node, not an attribute.
 */
function MarkdownLink({ node: _node, ...props }: ComponentPropsWithoutRef<"a"> & { node?: unknown }) {
  if (props.href?.startsWith("#")) return <a {...props} />;
  return <a {...props} target="_blank" rel="noopener noreferrer" />;
}

/**
 * Every image node as a plain link instead of a live `<img>`: both a markdown
 * image and a raw `<img>` (the same hast node after sanitizing) would otherwise
 * be fetched the instant the reply renders — a zero-click beacon to an
 * arbitrary host. The URL stays visible and reachable; nothing is fetched until
 * a human clicks. rehype-sanitize still strips onerror, srcset and the rest.
 */
function MarkdownImage({ src, alt, title }: ComponentPropsWithoutRef<"img"> & { node?: unknown }) {
  const href = typeof src === "string" ? src : undefined;
  const label = (alt && alt.trim()) || href || "image";
  if (!href) return <span style={{ fontStyle: "italic", opacity: 0.7 }}>{label}</span>;
  return (
    <a href={href} target="_blank" rel="noopener noreferrer" title={title ?? href} data-testid="markdown-image-link">
      {label}
    </a>
  );
}

/** Wide tables scroll inside the reply instead of widening the transcript. */
function ScrollableTable({ node: _node, ...props }: ComponentPropsWithoutRef<"table"> & { node?: unknown }) {
  return (
    <div className="table-scroll">
      <table {...props} />
    </div>
  );
}

const COMPONENTS: Components = {
  a: MarkdownLink,
  img: MarkdownImage,
  pre: CodeBlock,
  table: ScrollableTable,
};

export interface MarkdownContentProps {
  /** The markdown an agent wrote. */
  children: string;
  /** KaTeX for `$$…$$`. Default on. */
  enableMath?: boolean;
  /** highlight.js for fenced code. Default on. */
  enableCodeHighlight?: boolean;
  /** Sanitised raw HTML (D9 makes this per agent). Default on. */
  enableRawHtml?: boolean;
  /**
   * Show raw HTML as text instead of rendering or dropping it — for
   * third-party text whose angle brackets are prose (release notes). Implies
   * `enableRawHtml={false}`.
   */
  escapeRawHtml?: boolean;
}

/** Render an agent's markdown under the one policy above. */
export const MarkdownContent = memo(function MarkdownContent({
  children,
  enableMath = true,
  enableCodeHighlight = true,
  enableRawHtml = true,
  escapeRawHtml = false,
}: MarkdownContentProps) {
  const rich = useRichPlugins(children, enableMath, enableCodeHighlight, enableRawHtml && !escapeRawHtml);
  // Sanitising runs FIRST, on the model's markup. KaTeX and highlight.js then
  // build their own markup from the sanitised text, so their classes and spans
  // are neither subject to nor stripped by the schema.
  const remark = useMemo<PluggableList>(
    () => {
      const base = escapeRawHtml ? REMARK_ESCAPED : REMARK_PLAIN;
      return rich.math ? [...base, rich.math.remarkMath] : base;
    },
    [rich.math, escapeRawHtml],
  );
  const rehype = useMemo<PluggableList>(() => {
    if (!rich.math && !rich.highlight && !rich.raw) return BASE_REHYPE;
    const plugins: PluggableList = rich.raw ? [rich.raw.rehypeRaw, ...BASE_REHYPE] : [...BASE_REHYPE];
    if (rich.math) plugins.push(rich.math.rehypeKatex);
    if (rich.highlight) plugins.push(rich.highlight.rehypeHighlight);
    return plugins;
  }, [rich.math, rich.highlight, rich.raw]);
  return (
    <ReactMarkdown remarkPlugins={remark} rehypePlugins={rehype} components={COMPONENTS}>
      {children}
    </ReactMarkdown>
  );
});
