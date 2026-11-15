import { useTranslation } from "react-i18next";
import { FileText } from "lucide-react";
import type { ApprovalContextView } from "@/lib/api/hitl";

/**
 * The designer's `approvalContext` for a pause — the draft, its sources, the
 * claims it makes — shown next to the decision it informs.
 *
 * It was rendered once when the pause was committed and is served unchanged on
 * every surface, so what is read here is what the Slack approver saw. The text is
 * conversation data (a model may have written it): it is always rendered as a
 * plain text node in a `whitespace-pre-wrap` block — never as markdown or HTML —
 * so markup, links and mentions in it stay inert.
 */
export function ApprovalContext({ context }: { context?: ApprovalContextView | null }) {
  const { t } = useTranslation();
  const items = context?.items ?? [];
  if (items.length === 0) return null;

  return (
    <section
      className="mb-3 min-w-0 space-y-2 rounded-lg border border-border bg-background/60 p-3"
      data-testid="approval-context"
      aria-label={t("approvalContext.heading", "Context for this decision")}
    >
      <div className="flex items-start gap-2">
        <FileText className="mt-0.5 h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
        <div className="min-w-0">
          <p className="text-xs font-medium text-muted-foreground">
            {t("approvalContext.heading", "Context for this decision")}
          </p>
          <p className="text-[11px] text-muted-foreground">
            {t("approvalContext.notVerified", "Rendered from conversation data at pause time — not verified by {{productName}}.")}
          </p>
        </div>
      </div>
      {items.map((item, index) => (
        <div key={`${index}:${item.label}`} className="min-w-0" data-testid="approval-context-item">
          <h4 className="text-xs font-semibold text-foreground">{item.label}</h4>
          {item.error ? (
            <p className="mt-0.5 text-xs text-destructive" data-testid="approval-context-error">
              {item.error === "too_large"
                ? t("approvalContext.errorTooLarge", "This block was too large to render.")
                : t("approvalContext.errorRenderFailed", "This block could not be rendered.")}
            </p>
          ) : (
            <pre
              className="mt-0.5 max-h-72 min-w-0 max-w-full overflow-auto whitespace-pre-wrap break-words rounded-md bg-muted/40 p-2 font-sans text-xs text-foreground"
              data-testid="approval-context-text"
            >
              {item.text}
            </pre>
          )}
          {item.truncated && (
            <p className="mt-0.5 text-[11px] text-muted-foreground">{t("approvalContext.cutShort", "Cut short.")}</p>
          )}
        </div>
      ))}
      {context?.truncated && (
        <p className="text-[11px] text-muted-foreground" data-testid="approval-context-truncated">
          {t("approvalContext.leftOut", "Some context was left out to keep this card short.")}
        </p>
      )}
    </section>
  );
}
