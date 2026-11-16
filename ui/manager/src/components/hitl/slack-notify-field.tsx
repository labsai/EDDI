import { useEffect, useId, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { MAX_NOTIFY_INTEGRATION_NAME } from "@/lib/api/hitl";
import { slackIntegrationProblem } from "@/lib/hitl-notify";

/**
 * `hitlConfig.notify.slack.integration` — the Slack channel integration that
 * posts the approval card when this agent or group pauses, wherever the
 * conversation or discussion was started (schedule, team cadence, webhook,
 * REST, MCP). The integration's own settings decide the channel, the approvers
 * and the bot; this only names it.
 *
 * Like the email recipients, an unsavable name is never handed to `onChange`:
 * the field shows what is typed, says what is wrong, and reports it through
 * `onInvalidChange` so the page can withhold the save.
 */
export function SlackNotifyField({
  value,
  onChange,
  onInvalidChange,
  disabled = false,
  subject,
}: {
  /** The saved integration name, or null when none is set. */
  value: string | null;
  /** A valid, trimmed name, or null to remove the override (the deployment default, if any, then applies). */
  onChange: (integration: string | null) => void;
  onInvalidChange?: (invalid: boolean) => void;
  disabled?: boolean;
  /** Only changes the wording. */
  subject: "agent" | "group";
}) {
  const { t } = useTranslation();
  const inputId = useId();
  const [on, setOn] = useState(!!value);
  const [draft, setDraft] = useState(value ?? "");
  // A switch-off by the user keeps the typed name, so switching back on restores it.
  const switchedOffByUser = useRef(false);

  useEffect(() => {
    if (value) {
      switchedOffByUser.current = false;
      setOn(true);
      setDraft((current) => (current.trim() === value ? current : value));
    } else if (!switchedOffByUser.current) {
      // The parent cleared the saved name (a reset, a discard) rather than the user
      // switching it off here: show the field off and empty.
      setOn(false);
      setDraft("");
    }
  }, [value]);

  const problem = on ? slackIntegrationProblem(draft) : null;
  useEffect(() => {
    onInvalidChange?.(problem !== null);
    // No cleanup: when a collapsing section unmounts this field its typed draft is
    // lost but the owner still holds the earlier name, so the owner keeps the
    // "unsavable" flag (and drops it itself when HITL is switched off) instead of
    // letting the save persist that earlier name unannounced.
  }, [problem, onInvalidChange]);

  function commit(next: string) {
    setDraft(next);
    if (slackIntegrationProblem(next) === null && next.trim() !== value) onChange(next.trim());
  }

  function toggle(next: boolean) {
    setOn(next);
    if (!next) {
      switchedOffByUser.current = true;
      if (value) onChange(null);
      return;
    }
    if (slackIntegrationProblem(draft) === null) onChange(draft.trim());
  }

  function problemText(): string | null {
    switch (problem) {
      case "empty":
        return t("hitl.slackNotifyEmpty", "Name a Slack integration, or switch this off to use the deployment default (if one is set).");
      case "pipe":
        return t("hitl.slackNotifyPipe", "The name contains '|', which approval buttons cannot carry — rename the integration.");
      case "tooLong":
        return t("hitl.slackNotifyTooLong", "At most {{max}} characters.", { max: MAX_NOTIFY_INTEGRATION_NAME });
      default:
        return null;
    }
  }
  const error = problemText();

  return (
    <div data-testid="hitl-notify-slack">
      <label className="inline-flex items-center gap-2 text-xs font-medium text-foreground">
        <input
          type="checkbox"
          checked={on}
          onChange={(e) => toggle(e.target.checked)}
          disabled={disabled}
          className="h-3.5 w-3.5 rounded border-input accent-primary"
          data-testid="hitl-notify-slack-enabled"
        />
        {subject === "group"
          ? t("hitl.slackNotifyEnableGroup", "Use a specific Slack integration for this group's approval cards")
          : t("hitl.slackNotifyEnableAgent", "Use a specific Slack integration for this agent's approval cards")}
      </label>
      {on && (
        <div className="mt-3">
          <label htmlFor={inputId} className="mb-1 block text-[10px] text-muted-foreground">
            {t("hitl.slackNotifyIntegration", "Slack integration name")}
          </label>
          <input
            id={inputId}
            value={draft}
            onChange={(e) => commit(e.target.value)}
            disabled={disabled}
            maxLength={MAX_NOTIFY_INTEGRATION_NAME + 1}
            placeholder={t("hitl.slackNotifyPlaceholder", "ops-slack")}
            className={`h-8 w-full rounded-md border bg-background px-2 text-xs font-mono text-foreground focus:outline-none focus:ring-1 focus:ring-ring ${error ? "border-destructive" : "border-input"}`}
            data-testid="hitl-notify-slack-integration"
          />
          <p
            className={`mt-1 text-[10px] leading-relaxed ${error ? "text-destructive" : "text-muted-foreground"}`}
            data-testid="hitl-notify-slack-hint"
          >
            {error ??
              t(
                "hitl.slackNotifyHint",
                "The named Slack channel integration posts the same approval card a Slack-started pause gets — also for scheduled runs, team cadences, webhooks, REST and MCP. Its own settings decide the channel (hitlApprovalChannel), who may press the buttons (hitlApproverUserIds) and whether the approval context is shown. The card is updated when the request is decided anywhere else. With this off, the deployment default integration (eddi.slack.hitl.notify.default-integration) applies; without one, no card is posted.",
              )}
          </p>
        </div>
      )}
    </div>
  );
}
