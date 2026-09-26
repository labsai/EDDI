/* ──────────────────────────────────────────────
   MessageBubble — Single chat message
   ────────────────────────────────────────────── */

import { memo, useMemo, type ComponentPropsWithoutRef } from "react";
import ReactMarkdown, { type Components, type Options } from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeRaw from "rehype-raw";
import rehypeSanitize, { defaultSchema } from "rehype-sanitize";
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
 * GitHub's schema without `<img>`. An image in MODEL output is a request to a
 * URL the model chose — a tracking pixel, or a way to carry conversation data
 * out in a query string — and only the CSP stopped it when EDDI served the
 * page, not when the widget is embedded elsewhere. Images an agent designer
 * configures arrive as `image` output items and are rendered separately.
 */
const SANITIZE_SCHEMA = {
  ...defaultSchema,
  tagNames: (defaultSchema.tagNames ?? []).filter((tag) => tag !== "img"),
};

const BASE_REHYPE: PluggableList = [rehypeRaw, [rehypeSanitize, SANITIZE_SCHEMA]];
const REMARK_PLAIN: PluggableList = [remarkGfm];

/**
 * Links open in a new tab. A plain link navigated the widget itself away —
 * inside an iframe that is the whole chat, and the conversation was lost.
 */
function MarkdownLink(props: ComponentPropsWithoutRef<"a">) {
  return <a {...props} target="_blank" rel="noopener noreferrer" />;
}
const COMPONENTS: Components = { a: MarkdownLink };

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
