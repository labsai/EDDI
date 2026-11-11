import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import { Settings } from "lucide-react";
import { useOutcomeSuggestions } from "./use-agent-health";
import { AccessibleDialog, Badge, Skeleton, getErrorMessage } from "./deps";
import { enumLabel } from "./format";

/**
 * Which of the agent's actions end sessions, with the outcome each probably
 * means. Applying a mapping is out of scope here: it lives in the agent's
 * `healthConfig.outcomes`, which is versioned configuration — the dialog
 * explains that and links to the agent's configuration.
 */
export function OutcomeSuggestionsDialog({ open, onClose, agentId }: { open: boolean; onClose: () => void; agentId: string }) {
  const { t } = useTranslation();
  const suggestions = useOutcomeSuggestions(agentId, open);
  return (
    <AccessibleDialog
      open={open}
      onClose={onClose}
      title={t("health.suggestions.title", "Map actions to outcomes")}
      maxWidth="max-w-2xl"
      testId="health-suggestions-dialog"
    >
      <div className="space-y-3 text-sm">
        <p className="text-muted-foreground">
          {t(
            "health.suggestions.intro",
            "Resolution, escalation and abandonment can only be told when the agent says which of its actions mean what. The sessions below end with these actions most often.",
          )}
        </p>
        {suggestions.isLoading && <Skeleton className="h-20 w-full" />}
        {suggestions.isError && <p className="text-destructive" role="alert">{getErrorMessage(suggestions.error)}</p>}
        {suggestions.data && suggestions.data.items.length === 0 && (
          <p className="text-muted-foreground" data-testid="health-suggestions-empty">
            {t("health.suggestions.empty", "No candidates yet: not enough sessions end with a recognisable action.")}
          </p>
        )}
        {suggestions.data && suggestions.data.items.length > 0 && (
          <ul className="space-y-2" data-testid="health-suggestions">
            {suggestions.data.items.map((s) => (
              <li key={s.action} className="rounded-lg border border-border p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <code className="rounded bg-muted px-1.5 py-0.5 text-xs">{s.action}</code>
                  <span aria-hidden="true">→</span>
                  {s.suggested === null || s.suggested === undefined ? (
                    // The action's name matched no heuristic: nothing is suggested, the person decides.
                    <span className="text-muted-foreground" data-testid="health-suggestion-none">—</span>
                  ) : (
                    <Badge variant="secondary">{enumLabel(t, "outcome", s.suggested)}</Badge>
                  )}
                  <span className="ms-auto text-xs text-muted-foreground tabular-nums">
                    {t("health.count.sessions", { count: s.sessionsEndingWith, defaultValue: "{{count}} sessions" })}
                  </span>
                </div>
                <p className="mt-1 text-xs text-muted-foreground">{s.reason}</p>
              </li>
            ))}
          </ul>
        )}
        <div className="rounded-lg bg-muted/50 p-3 text-xs text-muted-foreground">
          <p className="mb-1 font-medium text-foreground">{t("health.suggestions.howTitle", "How to apply a mapping")}</p>
          <p>
            {t(
              "health.suggestions.how",
              "Add each action under healthConfig.outcomes in the agent's configuration, for example \"order_confirmed\": \"RESOLVED\", then save and deploy the new version.",
            )}
          </p>
          <Link to={`/manage/studio/${encodeURIComponent(agentId)}`} onClick={onClose} className="mt-2 inline-flex items-center gap-1 text-brand-700 hover:underline dark:text-brand-300" data-testid="health-suggestions-config-link">
            <Settings className="h-3 w-3" aria-hidden="true" />
            {t("health.suggestions.openConfig", "Open the agent in Agent Studio")}
          </Link>
        </div>
      </div>
    </AccessibleDialog>
  );
}
