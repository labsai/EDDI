import { useTranslation } from "react-i18next";
import { AlertTriangle } from "lucide-react";
import { groupSaveProblemMessage, type GroupSaveProblem } from "@/lib/group-config";

/**
 * The reasons the backend would refuse to create this group, shown on a create
 * flow's last step with the Create button held back until they are fixed.
 *
 * Rendered as an error, not a warning: unlike the role-coverage note these are
 * the backend's own hard rejections, and a flow that creates member agents first
 * would otherwise deploy them for a group that can never be saved.
 */
export function GroupSaveProblems({
  problems,
  testId = "group-save-problems",
}: {
  problems: GroupSaveProblem[];
  testId?: string;
}) {
  const { t } = useTranslation();
  if (problems.length === 0) return null;
  return (
    <div
      className="flex flex-col gap-1.5 rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-2.5"
      role="alert"
      data-testid={testId}
    >
      <p className="text-xs font-semibold text-destructive">
        {t("groups.saveProblem.title", "This group cannot be created yet:")}
      </p>
      <ul className="space-y-1">
        {problems.map((problem) => (
          <li key={problem.kind} className="flex items-start gap-2 text-xs text-foreground">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-destructive" aria-hidden="true" />
            <span>{groupSaveProblemMessage(t, problem)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
