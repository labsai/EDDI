/* ──────────────────────────────────────────────
   MessageBubble — Single chat message
   ────────────────────────────────────────────── */

import { memo } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeRaw from "rehype-raw";
import type { ChatMessage } from "@/types";
import { useChatState } from "@/store/chat-store";

interface MessageBubbleProps {
  message: ChatMessage;
}

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
                rehypePlugins={[rehypeRaw]}
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
