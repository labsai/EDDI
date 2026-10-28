import { useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { ArrowDown, ArrowUp, Plus, Send, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { AgentPicker } from "@/components/shared/agent-picker";
import { OutputMatchBuilder } from "./output-match-builder";
import { alertErrorCode, useSaveAlertRule, useTestAlertRule } from "@/hooks/use-alerts";
import { isApiError } from "@/lib/api-client";
import { ruleProblems, throttleProblems } from "@/lib/alert-rules";
import {
  ALERT_LIMITS,
  defaultCondition,
  ERROR_KINDS,
  MASKED_URL_SUFFIX,
  type AlertRule,
  type AlertSource,
  type DeliveryTarget,
  type DigestMode,
  type ErrorKind,
  type TestDelivery,
} from "@/lib/api/alerts";

const selectClass =
  "h-10 rounded-lg border border-input bg-background px-3 text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-ring";

interface AlertRuleEditorProps {
  initial: AlertRule;
  /** Administrators may scope a rule to every agent and add webhook targets. */
  isAdmin: boolean;
  onDone: () => void;
}

/**
 * Creates or edits one alert rule: source, scope, condition, delivery targets
 * (with "Send test alert"), throttle and digest. The Manager inbox is always a
 * target and needs no entry.
 */
export function AlertRuleEditor({ initial, isAdmin, onDone }: AlertRuleEditorProps) {
  const { t } = useTranslation();
  const [rule, setRule] = useState<AlertRule>(initial);
  const save = useSaveAlertRule();
  const test = useTestAlertRule();
  const [testResults, setTestResults] = useState<TestDelivery[] | null>(null);
  const problems = ruleProblems(rule);
  const throttleInvalid = throttleProblems(rule.throttle);

  const set = (patch: Partial<AlertRule>) => setRule((r) => ({ ...r, ...patch }));
  const setCondition = (patch: Partial<AlertRule["condition"]>) => setRule((r) => ({ ...r, condition: { ...r.condition, ...patch } }));
  const setTarget = (i: number, patch: Partial<DeliveryTarget>) =>
    setRule((r) => ({ ...r, delivery: r.delivery.map((d, j) => (j === i ? { ...d, ...patch } : d)) }));

  // Targets move and leave as whole objects, so each keeps its server-assigned
  // targetId — the only thing a masked URL is restored by.
  const moveTarget = (i: number, by: -1 | 1) =>
    setRule((r) => {
      const j = i + by;
      if (j < 0 || j >= r.delivery.length) return r;
      const delivery = [...r.delivery];
      const [moved] = delivery.splice(i, 1);
      delivery.splice(j, 0, moved!);
      return { ...r, delivery };
    });

  const changeSource = (source: AlertSource) =>
    set({ source, condition: defaultCondition(source) });

  const toggleKind = (kind: ErrorKind) => {
    const kinds = new Set(rule.condition.errorKinds ?? []);
    if (kinds.has(kind)) kinds.delete(kind);
    else kinds.add(kind);
    setCondition({ errorKinds: ERROR_KINDS.filter((k) => kinds.has(k)) });
  };

  const explain = (err: unknown) => {
    const code = alertErrorCode(err);
    if (code === "ADMIN_SCOPE_REQUIRED") return t("alerts.errorAdminScope", "Only an administrator can watch every agent or several agents.");
    if (code === "WEBHOOK_ADMIN_ONLY") return t("alerts.errorWebhookAdmin", "Only an administrator can add a webhook target.");
    if (code === "INVALID_RULE" && isApiError(err)) return err.message;
    return t("alerts.saveFailed", "The rule could not be saved.");
  };

  const doSave = async () => {
    try {
      await save.mutateAsync(rule);
      toast.success(t("alerts.saved", "Alert rule saved."));
      onDone();
    } catch (err) {
      toast.error(explain(err));
    }
  };

  const doTest = async () => {
    try {
      setTestResults(await test.mutateAsync(rule));
    } catch (err) {
      toast.error(explain(err));
    }
  };

  const agentId = rule.scope.agentIds[0] ?? "";

  return (
    <div className="space-y-5 rounded-xl border border-border bg-card p-5" data-testid="alert-rule-editor">
      <div className="grid gap-4 sm:grid-cols-2">
        <label className="space-y-1 text-sm">
          <span className="text-muted-foreground">{t("alerts.ruleName", "Name")}</span>
          <Input
            value={rule.name}
            maxLength={ALERT_LIMITS.maxNameLength}
            onChange={(e) => set({ name: e.target.value })}
            data-testid="alert-rule-name"
          />
        </label>
        <label className="space-y-1 text-sm">
          <span className="text-muted-foreground">{t("alerts.ruleSource", "When")}</span>
          <select
            className={`${selectClass} w-full`}
            value={rule.source}
            onChange={(e) => changeSource(e.target.value as AlertSource)}
            data-testid="alert-rule-source"
          >
            <option value="RATING">{t("alerts.sourceRating", "A rating is given or changed")}</option>
            <option value="CONVERSATION_ERROR">{t("alerts.sourceError", "A turn fails")}</option>
            <option value="OUTPUT_MATCH">{t("alerts.sourceOutput", "A turn's result matches")}</option>
          </select>
        </label>
      </div>

      <fieldset className="space-y-2">
        <legend className="text-sm font-medium">{t("alerts.ruleScope", "Agents")}</legend>
        {isAdmin && (
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              className="h-4 w-4 accent-primary"
              checked={rule.scope.allAgents}
              onChange={(e) => set({ scope: e.target.checked ? { allAgents: true, agentIds: [] } : { allAgents: false, agentIds: [] } })}
              data-testid="alert-rule-all-agents"
            />
            {t("alerts.allAgents", "Every agent")}
          </label>
        )}
        {!rule.scope.allAgents && (
          <div className="max-w-sm" data-testid="alert-rule-agent">
            <AgentPicker
              value={agentId}
              onChange={(id) => set({ scope: { allAgents: false, agentIds: id ? [id] : [] } })}
              placeholder={t("feedback.pickAgent", "Pick an agent")}
            />
          </div>
        )}
      </fieldset>

      <fieldset className="space-y-2" data-testid="alert-rule-condition">
        <legend className="text-sm font-medium">{t("alerts.ruleCondition", "Condition")}</legend>
        {rule.source === "OUTPUT_MATCH" ? (
          <OutputMatchBuilder condition={rule.condition} onChange={setCondition} />
        ) : rule.source === "RATING" ? (
          <div className="grid gap-3 sm:grid-cols-3">
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground">{t("alerts.maxScore", "Score at or below (0–1)")}</span>
              <Input
                type="number"
                min={0}
                max={1}
                step={0.05}
                value={rule.condition.maxNormalizedScore ?? ""}
                onChange={(e) => setCondition({ maxNormalizedScore: e.target.value === "" ? null : Number(e.target.value) })}
                data-testid="alert-rule-max-score"
              />
            </label>
            <label className="flex items-center gap-2 pt-6 text-sm">
              <input
                type="checkbox"
                className="h-4 w-4 accent-primary"
                checked={rule.condition.onComment === true}
                onChange={(e) => setCondition({ onComment: e.target.checked })}
                data-testid="alert-rule-on-comment"
              />
              {t("alerts.onComment", "Any rating with a comment")}
            </label>
            <label className="space-y-1 text-sm">
              <span className="text-muted-foreground">{t("alerts.tags", "Reason tags (comma-separated)")}</span>
              <Input
                value={(rule.condition.tags ?? []).join(", ")}
                onChange={(e) =>
                  setCondition({
                    tags: e.target.value
                      .split(",")
                      .map((s) => s.trim())
                      .filter(Boolean)
                      .slice(0, ALERT_LIMITS.maxTags),
                  })
                }
                data-testid="alert-rule-tags"
              />
            </label>
          </div>
        ) : (
          <div className="flex flex-wrap gap-4">
            {ERROR_KINDS.map((kind) => (
              <label key={kind} className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  className="h-4 w-4 accent-primary"
                  checked={rule.condition.errorKinds?.includes(kind) ?? false}
                  onChange={() => toggleKind(kind)}
                  data-testid={`alert-rule-kind-${kind}`}
                />
                {t(`alerts.kind.${kind}`, kind)}
              </label>
            ))}
          </div>
        )}
      </fieldset>

      <fieldset className="space-y-2">
        <legend className="text-sm font-medium">{t("alerts.ruleDelivery", "Deliver to")}</legend>
        <p className="text-xs text-muted-foreground">{t("alerts.inboxAlways", "Every alert lands in this inbox. Add Slack or a webhook as well:")}</p>
        {rule.delivery.map((target, i) => (
          <div key={target.targetId ?? `new-${i}`} className="flex flex-wrap items-end gap-2" data-testid={`alert-rule-target-${i}`}>
            <select
              className={selectClass}
              value={target.type}
              onChange={(e) =>
                setTarget(i, e.target.value === "SLACK" ? { type: "SLACK", url: null, signingSecret: null } : { type: "WEBHOOK", channelId: null })
              }
              aria-label={t("alerts.targetType", "Target type")}
              data-testid={`alert-rule-target-type-${i}`}
            >
              <option value="SLACK">Slack</option>
              {isAdmin && <option value="WEBHOOK">{t("alerts.webhook", "Webhook")}</option>}
            </select>
            {target.type === "SLACK" ? (
              <Input
                className="max-w-xs"
                placeholder="C0123456789"
                value={target.channelId ?? ""}
                onChange={(e) => setTarget(i, { channelId: e.target.value.trim() })}
                aria-label={t("alerts.slackChannel", "Slack channel id")}
                data-testid={`alert-rule-target-channel-${i}`}
              />
            ) : (
              <>
                <div className="max-w-sm flex-1">
                  <Input
                    placeholder="https://hooks.example.com/eddi — or ${vault:alert-hook-url}"
                    value={target.url ?? ""}
                    onChange={(e) => setTarget(i, { url: e.target.value.trim(), urlConfigured: null })}
                    aria-label={t("alerts.webhookUrl", "Webhook URL")}
                    data-testid={`alert-rule-target-url-${i}`}
                  />
                  {target.urlConfigured && target.url?.endsWith(MASKED_URL_SUFFIX) && (
                    <p className="mt-1 text-xs text-muted-foreground" data-testid={`alert-rule-target-url-kept-${i}`}>
                      {t("alerts.urlKept", "The stored URL is hidden. Leave it as it is to keep it, or enter a new one.")}
                    </p>
                  )}
                </div>
                <Input
                  className="max-w-xs"
                  placeholder="${vault:alert-signing-secret}"
                  value={target.signingSecret ?? ""}
                  onChange={(e) => setTarget(i, { signingSecret: e.target.value.trim() })}
                  aria-label={t("alerts.signingSecret", "Signing secret (vault reference)")}
                  data-testid={`alert-rule-target-secret-${i}`}
                />
              </>
            )}
            <Button
              size="sm"
              variant="ghost"
              disabled={i === 0}
              onClick={() => moveTarget(i, -1)}
              aria-label={t("alerts.moveTargetUp", "Move target up")}
              data-testid={`alert-rule-target-up-${i}`}
            >
              <ArrowUp className="h-4 w-4" />
            </Button>
            <Button
              size="sm"
              variant="ghost"
              disabled={i === rule.delivery.length - 1}
              onClick={() => moveTarget(i, 1)}
              aria-label={t("alerts.moveTargetDown", "Move target down")}
              data-testid={`alert-rule-target-down-${i}`}
            >
              <ArrowDown className="h-4 w-4" />
            </Button>
            <Button
              size="sm"
              variant="ghost"
              onClick={() => set({ delivery: rule.delivery.filter((_, j) => j !== i) })}
              aria-label={t("alerts.removeTarget", "Remove target")}
              data-testid={`alert-rule-target-remove-${i}`}
            >
              <Trash2 className="h-4 w-4" />
            </Button>
          </div>
        ))}
        {rule.delivery.length < ALERT_LIMITS.maxTargets && (
          <Button size="sm" variant="outline" onClick={() => set({ delivery: [...rule.delivery, { type: "SLACK", channelId: "" }] })} data-testid="alert-rule-add-target">
            <Plus className="h-4 w-4" />
            {t("alerts.addTarget", "Add target")}
          </Button>
        )}
      </fieldset>

      <div className="grid gap-4 sm:grid-cols-3">
        <label className="space-y-1 text-sm">
          <span className="text-muted-foreground">{t("alerts.window", "Window (minutes)")}</span>
          <Input
            type="number"
            min={1}
            max={ALERT_LIMITS.maxWindowMinutes}
            value={rule.throttle?.windowMinutes ?? ALERT_LIMITS.defaultWindowMinutes}
            onChange={(e) =>
              set({ throttle: { windowMinutes: Number(e.target.value), maxPerWindow: rule.throttle?.maxPerWindow ?? ALERT_LIMITS.defaultMaxPerWindow } })
            }
            aria-invalid={throttleInvalid.includes("windowMinutes") ? true : undefined}
            data-testid="alert-rule-window"
          />
          {throttleInvalid.includes("windowMinutes") && (
            <p className="text-xs text-destructive" data-testid="alert-rule-window-error">
              {t("alerts.throttleRange", "A whole number from 1 to {{max}}.", { max: ALERT_LIMITS.maxWindowMinutes })}
            </p>
          )}
        </label>
        <label className="space-y-1 text-sm">
          <span className="text-muted-foreground">{t("alerts.maxPerWindow", "At most per window")}</span>
          <Input
            type="number"
            min={1}
            max={ALERT_LIMITS.maxPerWindow}
            value={rule.throttle?.maxPerWindow ?? ALERT_LIMITS.defaultMaxPerWindow}
            onChange={(e) =>
              set({ throttle: { windowMinutes: rule.throttle?.windowMinutes ?? ALERT_LIMITS.defaultWindowMinutes, maxPerWindow: Number(e.target.value) } })
            }
            aria-invalid={throttleInvalid.includes("maxPerWindow") ? true : undefined}
            data-testid="alert-rule-max-per-window"
          />
          {throttleInvalid.includes("maxPerWindow") && (
            <p className="text-xs text-destructive" data-testid="alert-rule-max-per-window-error">
              {t("alerts.throttleRange", "A whole number from 1 to {{max}}.", { max: ALERT_LIMITS.maxPerWindow })}
            </p>
          )}
        </label>
        <label className="space-y-1 text-sm">
          <span className="text-muted-foreground">{t("alerts.digestLabel", "Beyond that, send a digest")}</span>
          <select
            className={`${selectClass} w-full`}
            value={rule.digest ?? "HOURLY"}
            onChange={(e) => set({ digest: e.target.value as DigestMode })}
            data-testid="alert-rule-digest"
          >
            <option value="HOURLY">{t("alerts.digestHourly", "Hourly")}</option>
            <option value="DAILY">{t("alerts.digestDaily", "Daily")}</option>
            <option value="NONE">{t("alerts.digestNone", "Never (inbox only)")}</option>
          </select>
        </label>
      </div>

      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          className="h-4 w-4 accent-primary"
          checked={rule.enabled}
          onChange={(e) => set({ enabled: e.target.checked })}
          data-testid="alert-rule-enabled"
        />
        {t("alerts.enabled", "Enabled")}
      </label>

      {testResults && (
        <ul className="space-y-1 text-sm" data-testid="alert-rule-test-results">
          {testResults.length === 0 && <li className="text-muted-foreground">{t("alerts.testNoTargets", "No targets to test — add Slack or a webhook.")}</li>}
          {testResults.map((r, i) => (
            <li key={`${i}-${r.label}`} className={r.delivered ? "text-foreground" : "text-destructive"}>
              {r.label}: {r.delivered ? t("alerts.testDelivered", "delivered") : (r.error ?? t("alerts.testFailed", "failed"))}
            </li>
          ))}
        </ul>
      )}

      <div className="flex flex-wrap justify-end gap-2">
        <Button variant="ghost" onClick={onDone} data-testid="alert-rule-cancel">
          {t("common.cancel", "Cancel")}
        </Button>
        <Button variant="outline" onClick={() => void doTest()} disabled={problems.length > 0 || test.isPending} data-testid="alert-rule-test">
          <Send className="h-4 w-4" />
          {t("alerts.sendTest", "Send test alert")}
        </Button>
        <Button onClick={() => void doSave()} disabled={problems.length > 0 || save.isPending} data-testid="alert-rule-save">
          {t("common.save", "Save")}
        </Button>
      </div>
    </div>
  );
}
