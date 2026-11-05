import { useCallback, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ConversationTextProvider, MarkdownContent, type MarkdownContentProps, type Translate } from "@eddi/conversation";

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
