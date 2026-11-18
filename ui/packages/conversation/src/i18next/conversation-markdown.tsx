import { useCallback, useMemo, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ConversationTextProvider } from "../react/hooks";
import { MarkdownContent, type MarkdownContentProps } from "../markdown/Markdown";
import type { Translate } from "../i18n/text";
import { formatMarkdownText } from "../team/group-utils";
import { STREAM_RENDER_INTERVAL_MS, useThrottledValue } from "../react/use-throttled-value";

/**
 * The Manager's translator for the shared conversation components, following
 * the language as it changes (a new `t` re-renders them).
 */
export function ManagerConversationText({ children }: { children: ReactNode }) {
  const { t, i18n } = useTranslation();
  const translate = useCallback<Translate>(
    (key, params) => i18n.t(key, params ?? {}),
    // `t` changes identity with the language; that is what re-renders.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [i18n, t],
  );
  return <ConversationTextProvider t={translate}>{children}</ConversationTextProvider>;
}

/**
 * An agent's reply through the one markdown renderer (@eddi/conversation,
 * frontend unification plan §2.5): images in model text as links, raw HTML only
 * sanitised, KaTeX and highlight.js loaded when a reply needs them. The
 * `eddi-markdown` wrapper picks up the renderer's own styles
 * (`@eddi/conversation/markdown.css`, imported by index.css).
 */
export function ConversationMarkdown({ children, ...options }: MarkdownContentProps) {
  return (
    <ManagerConversationText>
      <div className="eddi-markdown">
        <MarkdownContent {...options}>{children}</MarkdownContent>
      </div>
    </ManagerConversationText>
  );
}

export interface FormattedConversationMarkdownProps extends Omit<MarkdownContentProps, "children"> {
  /** The agent's text, before the light repair of `formatMarkdownText`. */
  children: string;
  /** The text is still arriving: repair and parse it at a capped rate. */
  streaming?: boolean;
}

/**
 * {@link ConversationMarkdown} for text that is repaired first
 * (`formatMarkdownText`). The repair and the Markdown parse are memoised by
 * text and, while the reply streams, run at most once per
 * `STREAM_RENDER_INTERVAL_MS`; when the stream ends the final text renders at
 * once and in full.
 */
export function FormattedConversationMarkdown({
  children,
  streaming = false,
  ...options
}: FormattedConversationMarkdownProps) {
  const shown = useThrottledValue(children, STREAM_RENDER_INTERVAL_MS, streaming);
  const formatted = useMemo(() => formatMarkdownText(shown), [shown]);
  return <ConversationMarkdown {...options}>{formatted}</ConversationMarkdown>;
}
