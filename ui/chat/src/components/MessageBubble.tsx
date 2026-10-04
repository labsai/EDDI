/* ──────────────────────────────────────────────
   MessageBubble — Single chat message
   ────────────────────────────────────────────── */

import {
  memo,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ComponentPropsWithoutRef,
} from "react";
import { Check, Copy } from "lucide-react";
import ReactMarkdown, { type Components, type Options } from "react-markdown";
import remarkGfm from "remark-gfm";
import rehypeRaw from "rehype-raw";
import rehypeSanitize from "rehype-sanitize";
import type { ChatMessage } from "@/types";
import { useRichPlugins } from "./markdown-plugins";
import { t } from "@/i18n";

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
  /** Who is speaking, for screen readers. Defaults to a generic "Assistant". */
  agentName?: string | null;
}

/** How often a streaming bubble re-parses its markdown, at most. */
const STREAM_RENDER_INTERVAL_MS = 50;

/**
 * The value, refreshed at most once per `ms` while `active`. A streaming reply
 * grows by a token at a time, and parsing the whole markdown document (raw
 * HTML, sanitise, highlight) per token is quadratic over the reply. When it
 * stops being active the real value is returned at once, so the final text
 * never lags.
 */
function useThrottledValue<T>(value: T, ms: number, active: boolean): T {
  const [shown, setShown] = useState(value);
  const [shownAt, setShownAt] = useState(0);
  // Leading edge, during render: the first token after a quiet spell shows in
  // the same pass instead of a frame later (React re-renders at once when a
  // component sets its own state while rendering).
  if (active && value !== shown && Date.now() - shownAt >= ms) {
    setShown(value);
    setShownAt(Date.now());
  }
  // Trailing edge: whatever arrived inside the window is shown when it ends.
  useEffect(() => {
    if (!active || value === shown) return;
    const id = setTimeout(
      () => {
        setShown(value);
        setShownAt(Date.now());
      },
      Math.max(0, ms - (Date.now() - shownAt)),
    );
    return () => clearTimeout(id);
  }, [value, shown, shownAt, ms, active]);
  return active ? shown : value;
}

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
function CopyButton({
  getText,
  label,
  className,
}: {
  getText: () => string;
  label: string;
  className: string;
}) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (timer.current) clearTimeout(timer.current);
    },
    [],
  );
  const onClick = useCallback(async () => {
    if (!(await copyText(getText()))) return;
    setCopied(true);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setCopied(false), 1500);
  }, [getText]);
  return (
    <button
      type="button"
      className={className}
      onClick={onClick}
      aria-label={copied ? t("copy.done") : label}
      title={copied ? t("copy.done") : label}
      data-testid="copy-button"
    >
      {copied ? <Check size="1em" /> : <Copy size="1em" />}
    </button>
  );
}

/** A fenced code block with its own copy button. */
function CodeBlock({
  node: _node,
  children,
  ...props
}: ComponentPropsWithoutRef<"pre"> & { node?: unknown }) {
  const preRef = useRef<HTMLPreElement>(null);
  const getText = useCallback(() => preRef.current?.textContent ?? "", []);
  return (
    <div className="code-block">
      <pre {...props} ref={preRef}>
        {children}
      </pre>
      <CopyButton
        getText={getText}
        label={t("copy.code")}
        className="code-block__copy"
      />
    </div>
  );
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

const COMPONENTS: Components = { a: MarkdownLink, img: MarkdownImage, pre: CodeBlock };

export const MessageBubble = memo(function MessageBubble({
  message,
  enableMarkdown = true,
  enableMath = true,
  enableCodeHighlight = true,
  agentName,
}: MessageBubbleProps) {
  const isUser = message.role === "user";
  const images = message.images ?? [];
  const hasContent = !!message.content;
  // Streaming bubbles re-parse markdown at a capped rate; see useThrottledValue.
  const renderedContent = useThrottledValue(
    message.content,
    STREAM_RENDER_INTERVAL_MS,
    !!message.isStreaming,
  );
  const getMessageText = useCallback(() => message.content, [message.content]);
  const rich = useRichPlugins(
    isUser || !enableMarkdown ? "" : renderedContent,
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

  // A notice is the widget talking, not the agent: no avatar, no speaker.
  if (message.kind === "notice") {
    return (
      <div className="message message--notice" data-testid="message-notice">
        <div className="message__notice">{message.content}</div>
      </div>
    );
  }

  return (
    <div className={`message message--${message.role}`}>
      {/* Decorative initials. Who is speaking is said below, in words. */}
      <div className="message__avatar" aria-hidden="true">
        {isUser ? "U" : "E"}
      </div>
      <div className="message__bubble">
        <span className="chat-sr-only">
          {isUser
            ? t("speaker.you")
            : t("speaker.agent", {
                name: agentName?.trim() || t("speaker.agentDefault"),
              })}{" "}
        </span>
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
                {renderedContent}
              </ReactMarkdown>
            ) : images.length ? null : message.isStreaming ? (
              <span style={{ opacity: 0.5, fontStyle: "italic" }}>…</span>
            ) : (
              <p style={{ opacity: 0.5, fontStyle: "italic" }}>{t("message.noResponse")}</p>
            )}
          </div>
        ) : hasContent || !images.length ? (
          <p style={{ whiteSpace: "pre-wrap", margin: 0 }}>
            {message.content || (message.isStreaming ? "…" : t("message.noResponse"))}
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
        {!isUser && hasContent && !message.isStreaming && (
          <CopyButton
            getText={getMessageText}
            label={t("copy.message")}
            className="message__copy"
          />
        )}
      </div>
    </div>
  );
});
