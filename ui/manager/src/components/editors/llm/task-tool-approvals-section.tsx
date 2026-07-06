import { useTranslation } from "react-i18next";
import { Wrench } from "lucide-react";
import { EditorSection } from "../editor-section";
import { ToolApprovalsEditor } from "../tool-approvals-editor";
import type { ToolApprovalsConfig } from "@/lib/api/hitl";
import type { TaskSectionProps } from "./task-section-props";

/**
 * Per-task tool-approval override (tool-level HITL). A per-task block FULLY
 * REPLACES the agent-level `hitlConfig.toolApprovals` for this task — the shared
 * {@link ToolApprovalsEditor} carries the same validation as the agent-level
 * editor.
 */
export function TaskToolApprovalsSection({ task, onChange, readOnly }: TaskSectionProps) {
  const { t } = useTranslation();
  const enabled = !!task.toolApprovals;

  return (
    <EditorSection
      label={t("llmEditor.toolApprovals", "Tool Approval Gating (per-task)")}
      icon={Wrench}
      accent="text-amber-500"
      defaultOpen={enabled}
    >
      <div className="space-y-3" data-testid="task-tool-approvals-section">
        <p className="text-[10px] text-muted-foreground leading-relaxed">
          {t(
            "llmEditor.toolApprovalsDesc",
            "Overrides the agent-level tool approvals for THIS task only — a full replace, not a merge.",
          )}
        </p>
        <label className="inline-flex items-center gap-2 text-xs font-medium text-foreground">
          <input
            type="checkbox"
            checked={enabled}
            onChange={(e) => onChange({ ...task, toolApprovals: e.target.checked ? {} : null })}
            disabled={readOnly}
            className="h-3.5 w-3.5 rounded border-input accent-primary"
            data-testid="task-tool-approvals-enabled"
          />
          {t("llmEditor.toolApprovalsEnable", "Override tool approvals for this task")}
        </label>
        {task.toolApprovals && (
          <ToolApprovalsEditor
            value={task.toolApprovals}
            disabled={readOnly}
            idPrefix="task-tool"
            onChange={(u) =>
              onChange({
                ...task,
                toolApprovals: { ...(task.toolApprovals as ToolApprovalsConfig), ...u },
              })
            }
          />
        )}
      </div>
    </EditorSection>
  );
}
