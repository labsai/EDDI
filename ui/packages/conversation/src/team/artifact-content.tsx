import type { ArtifactType } from "@eddi/core/groups";
import { formatJsonSafely } from "./group-utils";
import { ConversationMarkdown } from "../i18next/conversation-markdown";

/**
 * One artifact's content, by its type — the one rendering policy every artifact
 * view uses. Content is what member agents wrote, so it is model output:
 *
 * - `MARKDOWN` goes through the one markdown renderer with raw HTML escaped
 *   (`escapeRawHtml`), so a `<script>` or `<img onerror>` in it is text;
 * - `JSON` is pretty-printed into a `<pre>` (a read-only view needs no editor);
 * - `TEXT`, and any type this build does not know, is a plain `<pre>`.
 */
export function ArtifactContent({
  type,
  content,
  testId,
}: {
  type: ArtifactType | string;
  content: string;
  testId?: string;
}) {
  if (type === "JSON") {
    return (
      <pre
        className="max-h-[60vh] overflow-auto rounded-md bg-background p-2.5 font-mono text-[11px] leading-relaxed text-foreground"
        data-testid={testId}
      >
        {formatJsonSafely(content)}
      </pre>
    );
  }
  if (type === "MARKDOWN") {
    return (
      <div className="prose prose-sm dark:prose-invert max-w-none rounded-md bg-background p-2.5 text-xs" data-testid={testId}>
        <ConversationMarkdown escapeRawHtml>{content}</ConversationMarkdown>
      </div>
    );
  }
  return (
    <pre
      className="max-h-[60vh] overflow-auto whitespace-pre-wrap break-words rounded-md bg-background p-2.5 text-xs text-foreground"
      data-testid={testId}
    >
      {content}
    </pre>
  );
}
