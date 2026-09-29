/* ──────────────────────────────────────────────
   MessageBubble — Single chat message
   ────────────────────────────────────────────── */

import { memo } from "react";
import ReactMarkdown from "react-markdown";
import type { Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeRaw from "rehype-raw";
import rehypeSanitize from "rehype-sanitize";
import type { ChatMessage } from "@/types";
import { useChatState } from "@/store/chat-store";

interface MessageBubbleProps {
  message: ChatMessage;
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
 * dangerous attributes (onerror, srcset, …) before this runs.
 */
const MARKDOWN_COMPONENTS: Components = {
  img: ({ src, alt, title }) => {
    const href = typeof src === "string" ? src : undefined;
    const label = (alt && alt.trim()) || href || "image";
    if (!href) return <span style={{ fontStyle: "italic", opacity: 0.7 }}>{label}</span>;
    return (
      <a href={href} target="_blank" rel="noopener noreferrer" title={title ?? href}>
        {label}
      </a>
    );
  },
};

export const MessageBubble = memo(function MessageBubble({
  message,
}: MessageBubbleProps) {
  const { config } = useChatState();
  const isUser = message.role === "user";

  return (
    <div className={`message message--${message.role}`}>
      <div className="message__avatar">
        {isUser ? "U" : "E"}
      </div>
      <div className="message__bubble">
        {isUser ? (
          <p style={{ whiteSpace: "pre-wrap", margin: 0 }}>{message.content}</p>
        ) : config.enableMarkdown ? (
          <div className="markdown-body">
            {message.content ? (
              <ReactMarkdown
                remarkPlugins={[remarkGfm]}
                rehypePlugins={[rehypeRaw, rehypeSanitize]}
                components={MARKDOWN_COMPONENTS}
              >
                {message.content}
              </ReactMarkdown>
            ) : message.isStreaming ? (
              <span style={{ opacity: 0.5, fontStyle: "italic" }}>…</span>
            ) : (
              <p style={{ opacity: 0.5, fontStyle: "italic" }}>No response</p>
            )}
          </div>
        ) : (
          <p style={{ whiteSpace: "pre-wrap", margin: 0 }}>
            {message.content || (message.isStreaming ? "…" : "No response")}
          </p>
        )}
      </div>
    </div>
  );
});
