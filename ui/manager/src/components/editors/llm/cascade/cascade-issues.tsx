import { useTranslation } from "react-i18next";
import { AlertTriangle, Info } from "lucide-react";
import type { CascadeIssue } from "./cascade-validation";

/**
 * Renders cascade validation issues as inline callouts — red for errors
 * (the backend would reject the deploy), amber for warnings (degraded at
 * runtime). Each issue's translated message is looked up by code, falling
 * back to the English default baked into the issue.
 */
export function CascadeIssues({
  issues,
  className,
}: {
  issues: CascadeIssue[];
  className?: string;
}) {
  const { t } = useTranslation();
  if (issues.length === 0) return null;

  return (
    <div className={`space-y-1.5 ${className ?? ""}`} data-testid="cascade-issues">
      {issues.map((issue, i) => {
        const isError = issue.level === "error";
        const Icon = isError ? AlertTriangle : Info;
        return (
          <div
            key={`${issue.code}-${issue.stepIndex ?? "x"}-${i}`}
            className={`flex items-start gap-2 rounded-md border p-2 ${
              isError
                ? "border-red-400/40 bg-red-50 dark:border-red-700/40 dark:bg-red-900/15"
                : "border-amber-400/30 bg-amber-50 dark:border-amber-700/30 dark:bg-amber-900/15"
            }`}
            data-testid={`cascade-issue-${issue.code}`}
          >
            <Icon
              className={`mt-0.5 h-3.5 w-3.5 shrink-0 ${
                isError
                  ? "text-red-600 dark:text-red-400"
                  : "text-amber-600 dark:text-amber-400"
              }`}
            />
            <p
              className={`text-[10px] leading-relaxed ${
                isError
                  ? "text-red-800 dark:text-red-300"
                  : "text-amber-800 dark:text-amber-300"
              }`}
            >
              {t(`llmEditor.cascadeIssues.${issue.code}`, issue.message, issue.params)}
            </p>
          </div>
        );
      })}
    </div>
  );
}
