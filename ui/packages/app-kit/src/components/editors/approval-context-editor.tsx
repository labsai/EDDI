import { useTranslation } from "react-i18next";
import { Plus, Trash2 } from "lucide-react";
import type { ApprovalContextBlock } from "../../lib/api/hitl";
import {
  DEFAULT_APPROVAL_CONTEXT_CHARS,
  MAX_APPROVAL_CONTEXT_BLOCKS,
  MAX_APPROVAL_CONTEXT_CHARS,
  MAX_APPROVAL_CONTEXT_LABEL,
  MAX_APPROVAL_CONTEXT_TEMPLATE,
  MIN_APPROVAL_CONTEXT_CHARS,
  validateApprovalContextBlock,
  type ApprovalContextBlockError,
  type ApprovalContextScope,
} from "../../lib/hitl-approval-context";

/**
 * Editor for a list of `approvalContext` blocks — what the approver sees next to
 * the decision (the draft, its sources, the claims it makes). Shared by the agent
 * (`hitlConfig.approvalContext`), the tool-approval rule (`rules[].context`) and
 * the group (`hitlConfig.approvalContext`) editors.
 *
 * An empty list is saved as ABSENT (`null`): nothing is rendered or stored for
 * the pause and the card is unchanged. Mistakes are shown as the author types;
 * the backend still refuses a bad template with a 400 that names the block.
 */
export function ApprovalContextEditor({
  value,
  scope,
  disabled,
  onChange,
  idPrefix = "approval-context",
}: {
  value: ApprovalContextBlock[] | null | undefined;
  scope: ApprovalContextScope;
  disabled?: boolean;
  onChange: (next: ApprovalContextBlock[] | null) => void;
  idPrefix?: string;
}) {
  const { t } = useTranslation();
  const blocks = value ?? [];
  const max = MAX_APPROVAL_CONTEXT_BLOCKS[scope];
  const atLimit = blocks.length >= max;

  const commit = (next: ApprovalContextBlock[]) => onChange(next.length > 0 ? next : null);
  const patch = (index: number, updates: Partial<ApprovalContextBlock>) =>
    commit(blocks.map((b, i) => (i === index ? { ...b, ...updates } : b)));

  const errorText = (error: ApprovalContextBlockError | null): string | null => {
    switch (error) {
      case "label":
        return t("approvalContext.errorLabel", "Give the block a label.");
      case "template":
        return t("approvalContext.errorTemplate", "Write a template.");
      case "maxChars":
        return t("approvalContext.errorMaxChars", "Max characters must be between {{min}} and {{max}}.", {
          min: MIN_APPROVAL_CONTEXT_CHARS,
          max: MAX_APPROVAL_CONTEXT_CHARS,
        });
      case "secretNamespace":
        return t(
          "approvalContext.errorSecretNamespace",
          "A template cannot use vault:, eddivault:, connection: or caller: references.",
        );
      case "callOutsideRule":
        return t(
          "approvalContext.errorCallOutsideRule",
          "call.* is only available in a tool-approval rule's context blocks.",
        );
      default:
        return null;
    }
  };

  const inputCls =
    "w-full rounded-md border bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring";

  return (
    <div className="space-y-2 border-t border-border pt-3" data-testid={`${idPrefix}-editor`}>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-xs font-medium text-foreground">{t("approvalContext.editorTitle", "Approval context")}</p>
          <p className="text-[10px] leading-relaxed text-muted-foreground">
            {t(
              "approvalContext.editorDescription",
              "Show the approver what they are deciding on — the draft, its sources, the claims it makes. Each block is a template over this conversation's data, rendered once when the pause starts.",
            )}
          </p>
        </div>
        <span className="shrink-0 text-[10px] text-muted-foreground" data-testid={`${idPrefix}-count`}>
          {t("approvalContext.editorCount", "{{used}} of {{max}}", { used: blocks.length, max })}
        </span>
      </div>

      {blocks.map((block, index) => {
        const problem = validateApprovalContextBlock(block, scope);
        const message = errorText(problem);
        return (
          <div
            key={index}
            className="space-y-1.5 rounded-md border border-border bg-background/60 p-2"
            data-testid={`${idPrefix}-block-${index}`}
          >
            <div className="flex items-end gap-2">
              <div className="min-w-0 flex-1">
                <label className="mb-1 block text-[10px] text-muted-foreground" htmlFor={`${idPrefix}-label-${index}`}>
                  {t("approvalContext.blockLabel", "Label")}
                </label>
                <input
                  id={`${idPrefix}-label-${index}`}
                  type="text"
                  value={block.label}
                  maxLength={MAX_APPROVAL_CONTEXT_LABEL}
                  disabled={disabled}
                  onChange={(e) => patch(index, { label: e.target.value })}
                  placeholder={t("approvalContext.blockLabelPlaceholder", "e.g. Draft")}
                  className={`h-8 ${inputCls} ${problem === "label" ? "border-destructive" : "border-input"}`}
                  data-testid={`${idPrefix}-label-${index}`}
                />
              </div>
              <div className="w-28 shrink-0">
                <label className="mb-1 block text-[10px] text-muted-foreground" htmlFor={`${idPrefix}-max-${index}`}>
                  {t("approvalContext.blockMaxChars", "Max characters")}
                </label>
                <input
                  id={`${idPrefix}-max-${index}`}
                  type="number"
                  min={MIN_APPROVAL_CONTEXT_CHARS}
                  max={MAX_APPROVAL_CONTEXT_CHARS}
                  step={50}
                  value={block.maxChars ?? ""}
                  disabled={disabled}
                  onChange={(e) => {
                    const raw = e.currentTarget.value;
                    patch(index, { maxChars: raw === "" ? null : Number(raw) });
                  }}
                  placeholder={String(DEFAULT_APPROVAL_CONTEXT_CHARS)}
                  className={`h-8 ${inputCls} ${problem === "maxChars" ? "border-destructive" : "border-input"}`}
                  data-testid={`${idPrefix}-max-${index}`}
                />
              </div>
              <button
                type="button"
                onClick={() => commit(blocks.filter((_, i) => i !== index))}
                disabled={disabled}
                className="mb-0.5 rounded-md p-1.5 text-muted-foreground hover:bg-muted hover:text-destructive disabled:opacity-50"
                aria-label={t("approvalContext.removeBlock", "Remove block")}
                data-testid={`${idPrefix}-remove-${index}`}
              >
                <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
              </button>
            </div>
            <div>
              <label className="mb-1 block text-[10px] text-muted-foreground" htmlFor={`${idPrefix}-template-${index}`}>
                {t("approvalContext.blockTemplate", "Template")}
              </label>
              <textarea
                id={`${idPrefix}-template-${index}`}
                value={block.template}
                maxLength={MAX_APPROVAL_CONTEXT_TEMPLATE}
                rows={3}
                disabled={disabled}
                onChange={(e) => patch(index, { template: e.target.value })}
                placeholder={
                  scope === "rule"
                    ? "{call.args.text}"
                    : scope === "group"
                      ? "{group.lastByRole.DEVIL_ADVOCATE.content}"
                      : "{properties.gtm.draft}"
                }
                className={`py-1 font-mono ${inputCls} ${problem === "template" || problem === "secretNamespace" || problem === "callOutsideRule" ? "border-destructive" : "border-input"}`}
                data-testid={`${idPrefix}-template-${index}`}
              />
            </div>
            {message && (
              <p className="text-[10px] text-destructive" data-testid={`${idPrefix}-error-${index}`}>
                {message}
              </p>
            )}
          </div>
        );
      })}

      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={() => commit([...blocks, { label: "", template: "", maxChars: null }])}
          disabled={disabled || atLimit}
          className="inline-flex items-center gap-1 rounded-md border border-input px-2 py-1 text-xs text-foreground hover:bg-muted disabled:opacity-50"
          data-testid={`${idPrefix}-add`}
        >
          <Plus className="h-3 w-3" aria-hidden="true" />
          {t("approvalContext.addBlock", "Add block")}
        </button>
        {atLimit && (
          <span className="text-[10px] text-muted-foreground" data-testid={`${idPrefix}-limit`}>
            {t("approvalContext.limitReached", "At most {{max}} blocks.", { max })}
          </span>
        )}
      </div>

      <p className="text-[10px] leading-relaxed text-muted-foreground">
        {scope === "group"
          ? t(
              "approvalContext.hintGroup",
              "Available: group.lastByRole.ROLE.content, group.artifacts.NAME.content, group.tasksAwaitingApproval, group.transcript, group.question.",
            )
          : scope === "rule"
            ? t(
                "approvalContext.hintRule",
                "Available: call.args.NAME (this call's redacted arguments), call.name, plus properties.*, context.* and memory.*.",
              )
            : t(
                "approvalContext.hintAgent",
                "Available: properties.*, context.*, memory.*, hitl.reason and hitl.toolCalls (redacted arguments).",
              )}{" "}
        {t(
          "approvalContext.hintOnce",
          "Rendered once when the pause starts — changing this does not change approvals already waiting. Slack shows it only when the integration sets hitlApprovalContext to true.",
        )}
      </p>
    </div>
  );
}
