/* ──────────────────────────────────────────────
   MessageBubble — Single chat message
   ────────────────────────────────────────────── */

import { memo, useMemo, type ComponentPropsWithoutRef } from "react";
import ReactMarkdown, { type Components, type Options } from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeRaw from "rehype-raw";
import rehypeSanitize from "rehype-sanitize";
import type { ChatMessage } from "@/types";
import { useRichPlugins } from "./markdown-plugins";

type PluggableList = NonNullable<Options["rehypePlugins"]>;

interface MessageBubbleProps {
  message: ChatMessage;
  /**
   * Passed in rather than read from the store. The bubble is memoised, but a
   * store subscription re-rendered EVERY bubble on every token — re-parsing
   * the whole transcript's markdown through rehype-raw and sanitize per token.
   */
  enableMarkdown?: boolean;
  enableMath?: boolean;
  enableCodeHighlight?: boolean;
}

/**
 * GitHub's default sanitize schema. It keeps `<img>` so that the component
 * override below can see it: an image in MODEL output is a request to a URL
 * the model chose — a tracking pixel, or a way to carry conversation data out
 * in a query string — and only the CSP stopped it when EDDI served the page,
 * not when the widget is embedded elsewhere.
 */
const BASE_REHYPE: PluggableList = [rehypeRaw, rehypeSanitize];
const REMARK_PLAIN: PluggableList = [remarkGfm];

/**
 * Links open in a new tab. A plain link navigated the widget itself away —
 * inside an iframe that is the whole chat, and the conversation was lost.
 *
 * Same-page anchors (`#…`, which is what GFM footnote references are) stay in
 * the page: in a new tab they booted the widget again and started a second
 * conversation. `node` is react-markdown's AST node, not an attribute; spread
 * onto the element it rendered as node="[object Object]".
 */
function MarkdownLink({
  node: _node,
  ...props
}: ComponentPropsWithoutRef<"a"> & { node?: unknown }) {
  if (props.href?.startsWith("#")) return <a {...props} />;
  return <a {...props} target="_blank" rel="noopener noreferrer" />;
}

/**
 * Render every image node as a plain link instead of a live `<img>`.
 *
 * LLM output is untrusted, and this renderer runs rehype-raw, so both a
 * markdown image `![](https://attacker/pixel.png)` and a raw `<img>` would
 * otherwise be fetched by the browser the instant the message renders — a
 * zero-click beacon to an arbitrary host, and an exfil channel for anything
 * reflected into the reply. This override catches both (they land as the same
 * hast `img` node after sanitization) and renders a link: the URL stays visible
 * and reachable, but nothing is fetched until a human clicks. Mirrors the
 * override in the Manager's update-check-card. rehype-sanitize still strips
 * dangerous attributes (onerror, srcset, …) before this runs. Images an agent
 * designer configures arrive as `image` output items and are rendered
 * separately, below the text.
 */
function MarkdownImage({ src, alt, title }: ComponentPropsWithoutRef<"img"> & { node?: unknown }) {
  const href = typeof src === "string" ? src : undefined;
  const label = (alt && alt.trim()) || href || "image";
  if (!href) return <span style={{ fontStyle: "italic", opacity: 0.7 }}>{label}</span>;
  return (
    <a href={href} target="_blank" rel="noopener noreferrer" title={title ?? href}>
      {label}
    </a>
  );
}

const COMPONENTS: Components = { a: MarkdownLink, img: MarkdownImage };

export const MessageBubble = memo(function MessageBubble({
  message,
  enableMarkdown = true,
  enableMath = true,
  enableCodeHighlight = true,
}: MessageBubbleProps) {
  const isUser = message.role === "user";
  const images = message.images ?? [];
  const hasContent = !!message.content;
  const rich = useRichPlugins(
    isUser || !enableMarkdown ? "" : message.content,
    enableMath,
    enableCodeHighlight,
  );
  // Sanitising runs FIRST, on the model's markup. KaTeX and highlight.js then
  // build their own markup from the sanitised text, so their classes and
  // spans are neither subject to nor stripped by the schema.
  const remark = useMemo<PluggableList>(
    () => (rich.math ? [remarkGfm, rich.math.remarkMath] : REMARK_PLAIN),
    [rich.math],
  );
  const rehype = useMemo<PluggableList>(() => {
    if (!rich.math && !rich.highlight) return BASE_REHYPE;
    const plugins: PluggableList = [...BASE_REHYPE];
    if (rich.math) plugins.push(rich.math.rehypeKatex);
    if (rich.highlight) plugins.push(rich.highlight.rehypeHighlight);
    return plugins;
  }, [rich.math, rich.highlight]);

  return (
    <div className={`message message--${message.role}`}>
      <div className="message__avatar">
        {isUser ? "U" : "E"}
      </div>
      <div className="message__bubble">
        {isUser ? (
          <p style={{ whiteSpace: "pre-wrap", margin: 0 }}>{message.content}</p>
        ) : enableMarkdown ? (
          <div className="markdown-body">
            {hasContent ? (
              <ReactMarkdown
                remarkPlugins={remark}
                rehypePlugins={rehype}
                components={COMPONENTS}
              >
                {message.content}
              </ReactMarkdown>
            ) : images.length ? null : message.isStreaming ? (
              <span style={{ opacity: 0.5, fontStyle: "italic" }}>…</span>
            ) : (
              <p style={{ opacity: 0.5, fontStyle: "italic" }}>No response</p>
            )}
          </div>
        ) : hasContent || !images.length ? (
          <p style={{ whiteSpace: "pre-wrap", margin: 0 }}>
            {message.content || (message.isStreaming ? "…" : "No response")}
          </p>
        ) : null}
        {images.length > 0 && (
          <div className="message__images" data-testid="message-images">
            {images.map((image, i) => (
              <img
                key={`${image.uri}-${i}`}
                className="message__image"
                src={image.uri}
                alt={image.alt ?? ""}
                loading="lazy"
                referrerPolicy="no-referrer"
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
});
