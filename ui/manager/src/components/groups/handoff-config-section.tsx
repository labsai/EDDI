import { useId } from "react";
import { useTranslation } from "react-i18next";
import { Plus, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  HANDOFF_DECISION_KINDS,
  HANDOFF_MAX_INBOX_CEILING,
  HANDOFF_MAX_PER_DAY_CEILING,
  HANDOFF_MAX_TARGETS,
  HANDOFF_MAX_TARGET_DESCRIPTION,
  HANDOFF_MAX_TEMPLATE_CHARS,
  handoffProblems,
  type AcceptRule,
  type DecisionKind,
  type DecisionRule,
  type GroupHandoffConfig,
  type HandoffProblem,
  type HandoffTarget,
  type IntakeMode,
} from "@/lib/api/team-handoffs";

interface HandoffConfigSectionProps {
  /** `null`/absent = the group neither sends nor receives. */
  value: GroupHandoffConfig | null | undefined;
  onChange: (next: GroupHandoffConfig | null) => void;
}

/** One sentence per problem code; static `t()` calls so the i18n gate can read every key. */
function useProblemText() {
  const { t } = useTranslation();
  return (p: HandoffProblem): string => {
    const n = p.n ?? 0;
    switch (p.code) {
      case "alias":
        return t("teamHandoff.config.problemAlias", "Team {{n}}: the alias must be 1-40 characters of a-z, 0-9 or '-'.", { n });
      case "aliasTwice":
        return t("teamHandoff.config.problemAliasTwice", "Team {{n}}: the alias '{{alias}}' is used twice.", { n, alias: p.alias ?? "" });
      case "groupId":
        return t("teamHandoff.config.problemGroupId", "Team {{n}}: enter the id of the group it hands off to.", { n });
      case "tooManyTargets":
        return t("teamHandoff.config.problemTooManyTargets", "At most {{max}} teams can be listed.", { max: HANDOFF_MAX_TARGETS });
      case "role":
        return t("teamHandoff.config.problemRole", "Name the role that may hand off.");
      case "source":
        return t("teamHandoff.config.problemSource", "Accepted source {{n}} must be agent:<id> or group:<id> (an exact id, no wildcard).", { n });
      case "sourceTwice":
        return t("teamHandoff.config.problemSourceTwice", "Accepted source {{n}} is listed twice.", { n });
      case "ruleTarget":
        return t("teamHandoff.config.problemRuleTarget", "Decision rule {{n}}: pick one of the teams listed above.", { n });
      case "ruleTypes":
        return t("teamHandoff.config.problemRuleTypes", "Decision rule {{n}}: pick at least one decision type.", { n });
      case "ruleSubject":
        return t("teamHandoff.config.problemRuleSubject", "Decision rule {{n}}: the subject is required.", { n });
    }
  };
}

const inputCls =
  "h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring";

/**
 * Cross-team handoff, both halves (docs/team-handoff.md): the teams this group
 * may hand work to, the sources it accepts work from, and the decision rules
 * that file a handoff when a discussion completes.
 *
 * Absence is off, so unticking drops the whole block. Fields the form does not
 * show (the per-turn and per-discussion caps) ride through on the spread, so a
 * save never loses an operator-tuned number.
 */
export function HandoffConfigSection({ value, onChange }: HandoffConfigSectionProps) {
  const { t } = useTranslation();
  const uid = useId();
  const enabled = !!value;
  const targets = value?.targets ?? [];
  const accept = value?.accept ?? null;
  const rules = value?.onDecision ?? [];
  const participants = value?.toolParticipants ?? "MODERATOR";
  const participantKind = participants.startsWith("ROLE:") ? "ROLE" : participants;
  const problems = handoffProblems(value);
  const problemText = useProblemText();

  const patch = (next: Partial<GroupHandoffConfig>) => onChange({ ...(value ?? {}), ...next });
  const setTargets = (next: HandoffTarget[]) => patch({ targets: next });
  const setFrom = (from: AcceptRule[]) => patch({ accept: { ...(accept ?? { from: [] }), from } });
  const setRules = (next: DecisionRule[]) => patch({ onDecision: next });

  return (
    <section className="space-y-2 border-t border-border pt-2.5" data-testid="adv-handoff-section">
      <label className="flex items-center gap-2 text-xs font-medium text-foreground">
        <input
          type="checkbox"
          checked={enabled}
          onChange={(e) => onChange(e.target.checked ? { targets: [], accept: { from: [] } } : null)}
          data-testid="adv-handoff-enabled"
        />
        {t("teamHandoff.config.title", "Cross-team handoff")}
      </label>
      <p className="text-[10px] text-muted-foreground">
        {t(
          "teamHandoff.config.hint",
          "Hand work to other teams, and choose who may hand work to this one. Both teams must name each other; accepted work waits for review unless you set AUTO.",
        )}
      </p>

      {enabled && (
        <div className="space-y-3 ps-5">
          {/* ---- sending ---- */}
          <div className="space-y-1.5">
            <p className="text-xs font-medium text-foreground">{t("teamHandoff.config.targets", "Teams this group can hand off to")}</p>
            {targets.map((target, i) => (
              <div key={i} className="grid grid-cols-[8rem_1fr_auto] items-center gap-1.5" data-testid={`adv-handoff-target-${i}`}>
                <input
                  value={target.alias}
                  onChange={(e) => setTargets(targets.map((x, j) => (j === i ? { ...x, alias: e.target.value } : x)))}
                  placeholder={t("teamHandoff.config.aliasPlaceholder", "alias")}
                  aria-label={t("teamHandoff.config.aliasPlaceholder", "alias")}
                  className={inputCls}
                />
                <input
                  value={target.groupId}
                  onChange={(e) => setTargets(targets.map((x, j) => (j === i ? { ...x, groupId: e.target.value } : x)))}
                  placeholder={t("teamHandoff.config.groupIdPlaceholder", "target group id")}
                  aria-label={t("teamHandoff.config.groupIdPlaceholder", "target group id")}
                  className={inputCls}
                />
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setTargets(targets.filter((_, j) => j !== i))}
                  aria-label={t("common.remove", "Remove")}
                  className="h-7 w-7 p-0"
                >
                  <Trash2 className="h-3 w-3" />
                </Button>
                <input
                  value={target.description ?? ""}
                  onChange={(e) => setTargets(targets.map((x, j) => (j === i ? { ...x, description: e.target.value } : x)))}
                  placeholder={t("teamHandoff.config.descriptionPlaceholder", "when to hand off to this team (shown to the model)")}
                  aria-label={t("teamHandoff.config.descriptionPlaceholder", "when to hand off to this team (shown to the model)")}
                  maxLength={HANDOFF_MAX_TARGET_DESCRIPTION}
                  className={`${inputCls} col-span-3`}
                />
              </div>
            ))}
            <Button
              variant="outline"
              size="sm"
              className="h-7 text-xs"
              disabled={targets.length >= HANDOFF_MAX_TARGETS}
              onClick={() => setTargets([...targets, { alias: "", groupId: "", description: "" }])}
              data-testid="adv-handoff-add-target"
            >
              <Plus className="me-1 h-3 w-3" />
              {t("teamHandoff.config.addTarget", "Add team")}
            </Button>
          </div>

          {targets.length > 0 && (
            <div className="flex items-center gap-2">
              <label htmlFor={`${uid}-participants`} className="text-xs text-foreground">
                {t("teamHandoff.config.participants", "Who can hand off in a discussion")}
              </label>
              <select
                id={`${uid}-participants`}
                value={participantKind}
                onChange={(e) => patch({ toolParticipants: e.target.value === "ROLE" ? "ROLE:" : e.target.value })}
                className={`${inputCls} w-40`}
                data-testid="adv-handoff-participants"
              >
                <option value="MODERATOR">{t("teamHandoff.config.participantsModerator", "The moderator")}</option>
                <option value="ALL">{t("teamHandoff.config.participantsAll", "Every member")}</option>
                <option value="ROLE">{t("teamHandoff.config.participantsRole", "Members with a role")}</option>
              </select>
              {participantKind === "ROLE" && (
                <input
                  value={participants.slice(5)}
                  onChange={(e) => patch({ toolParticipants: `ROLE:${e.target.value}` })}
                  placeholder={t("teamHandoff.config.rolePlaceholder", "role")}
                  aria-label={t("teamHandoff.config.rolePlaceholder", "role")}
                  className={`${inputCls} w-32`}
                />
              )}
            </div>
          )}

          {/* ---- decision rules ---- */}
          {targets.length > 0 && (
            <div className="space-y-1.5">
              <p className="text-xs font-medium text-foreground">{t("teamHandoff.config.rules", "File a handoff when a discussion decides")}</p>
              {rules.map((rule, i) => (
                <div key={i} className="space-y-1 rounded-md border border-border p-1.5" data-testid={`adv-handoff-rule-${i}`}>
                  <div className="flex items-center gap-1.5">
                    <select
                      value={rule.target}
                      onChange={(e) => setRules(rules.map((x, j) => (j === i ? { ...x, target: e.target.value } : x)))}
                      aria-label={t("teamHandoff.config.ruleTargetPick", "Pick a team…")}
                      className={inputCls}
                    >
                      <option value="">{t("teamHandoff.config.ruleTargetPick", "Pick a team…")}</option>
                      {targets
                        .filter((x) => x.alias)
                        .map((x) => (
                          <option key={x.alias} value={x.alias}>
                            {x.alias}
                          </option>
                        ))}
                    </select>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => setRules(rules.filter((_, j) => j !== i))}
                      aria-label={t("common.remove", "Remove")}
                      className="h-7 w-7 p-0"
                    >
                      <Trash2 className="h-3 w-3" />
                    </Button>
                  </div>
                  <div className="flex flex-wrap items-center gap-3">
                    {HANDOFF_DECISION_KINDS.map((kind) => (
                      <label key={kind} className="flex items-center gap-1 text-[11px] text-foreground">
                        <input
                          type="checkbox"
                          checked={rule.decisionTypes.includes(kind)}
                          onChange={(e) =>
                            setRules(
                              rules.map((x, j) =>
                                j === i
                                  ? {
                                      ...x,
                                      decisionTypes: e.target.checked
                                        ? [...x.decisionTypes, kind as DecisionKind]
                                        : x.decisionTypes.filter((k) => k !== kind),
                                    }
                                  : x,
                              ),
                            )
                          }
                        />
                        {kind}
                      </label>
                    ))}
                    <label className="flex items-center gap-1 text-[11px] text-foreground">
                      <input
                        type="checkbox"
                        checked={rule.requireWinner !== false}
                        onChange={(e) => setRules(rules.map((x, j) => (j === i ? { ...x, requireWinner: e.target.checked } : x)))}
                      />
                      {t("teamHandoff.config.requireWinner", "Only when there is a winner")}
                    </label>
                  </div>
                  <input
                    value={rule.subjectTemplate}
                    onChange={(e) => setRules(rules.map((x, j) => (j === i ? { ...x, subjectTemplate: e.target.value } : x)))}
                    placeholder={t("teamHandoff.config.subjectTemplate", "Subject, e.g. Research account: {decision.winner}")}
                    aria-label={t("teamHandoff.config.subjectTemplate", "Subject, e.g. Research account: {decision.winner}")}
                    maxLength={HANDOFF_MAX_TEMPLATE_CHARS}
                    className={inputCls}
                  />
                  <input
                    value={rule.descriptionTemplate ?? ""}
                    onChange={(e) => setRules(rules.map((x, j) => (j === i ? { ...x, descriptionTemplate: e.target.value } : x)))}
                    placeholder={t("teamHandoff.config.descriptionTemplate", "Description, e.g. {decision.outcome}")}
                    aria-label={t("teamHandoff.config.descriptionTemplate", "Description, e.g. {decision.outcome}")}
                    maxLength={HANDOFF_MAX_TEMPLATE_CHARS}
                    className={inputCls}
                  />
                </div>
              ))}
              <Button
                variant="outline"
                size="sm"
                className="h-7 text-xs"
                onClick={() =>
                  setRules([
                    ...rules,
                    { target: targets[0]?.alias ?? "", decisionTypes: ["VOTE"], requireWinner: true, subjectTemplate: "", descriptionTemplate: "" },
                  ])
                }
                data-testid="adv-handoff-add-rule"
              >
                <Plus className="me-1 h-3 w-3" />
                {t("teamHandoff.config.addRule", "Add rule")}
              </Button>
            </div>
          )}

          {/* ---- receiving ---- */}
          <div className="space-y-1.5">
            <p className="text-xs font-medium text-foreground">{t("teamHandoff.config.accept", "Sources this group accepts work from")}</p>
            {(accept?.from ?? []).map((rule, i) => (
              <div key={i} className="flex items-center gap-1.5" data-testid={`adv-handoff-accept-${i}`}>
                <input
                  value={rule.source}
                  onChange={(e) => setFrom((accept?.from ?? []).map((x, j) => (j === i ? { ...x, source: e.target.value } : x)))}
                  placeholder={t("teamHandoff.config.sourcePlaceholder", "agent:<id> or group:<id>")}
                  aria-label={t("teamHandoff.config.sourcePlaceholder", "agent:<id> or group:<id>")}
                  className={inputCls}
                />
                <select
                  value={rule.mode ?? "REVIEW"}
                  onChange={(e) =>
                    setFrom((accept?.from ?? []).map((x, j) => (j === i ? { ...x, mode: e.target.value as IntakeMode } : x)))
                  }
                  aria-label={t("teamHandoff.config.mode", "How it lands")}
                  className={`${inputCls} w-40`}
                >
                  <option value="REVIEW">{t("teamHandoff.config.modeReview", "Review first")}</option>
                  <option value="AUTO">{t("teamHandoff.config.modeAuto", "Straight to the backlog")}</option>
                </select>
                <input
                  type="number"
                  min={1}
                  max={HANDOFF_MAX_PER_DAY_CEILING}
                  value={rule.maxPerDay ?? ""}
                  onChange={(e) =>
                    setFrom(
                      (accept?.from ?? []).map((x, j) =>
                        j === i ? { ...x, maxPerDay: e.target.value ? Number(e.target.value) : null } : x,
                      ),
                    )
                  }
                  placeholder={t("teamHandoff.config.perDay", "per day")}
                  aria-label={t("teamHandoff.config.perDay", "per day")}
                  className={`${inputCls} w-20`}
                />
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => setFrom((accept?.from ?? []).filter((_, j) => j !== i))}
                  aria-label={t("common.remove", "Remove")}
                  className="h-7 w-7 p-0"
                >
                  <Trash2 className="h-3 w-3" />
                </Button>
              </div>
            ))}
            <Button
              variant="outline"
              size="sm"
              className="h-7 text-xs"
              onClick={() => setFrom([...(accept?.from ?? []), { source: "", mode: "REVIEW" }])}
              data-testid="adv-handoff-add-accept"
            >
              <Plus className="me-1 h-3 w-3" />
              {t("teamHandoff.config.addSource", "Add source")}
            </Button>
            <p className="text-[10px] text-muted-foreground">
              {t(
                "teamHandoff.config.acceptHint",
                "Accepting agent:X trusts everyone who can edit that agent. Exact ids only; wildcards are refused.",
              )}
            </p>
            {(accept?.from.length ?? 0) > 0 && (
              <div className="flex items-center gap-3 text-xs text-foreground">
                <label className="flex items-center gap-1">
                  {t("teamHandoff.config.teamPerDay", "Team-wide per day")}
                  <input
                    type="number"
                    min={1}
                    max={HANDOFF_MAX_PER_DAY_CEILING}
                    value={accept?.maxPerDay ?? ""}
                    onChange={(e) => patch({ accept: { ...(accept ?? { from: [] }), maxPerDay: e.target.value ? Number(e.target.value) : undefined } })}
                    className={`${inputCls} w-20`}
                  />
                </label>
                <label className="flex items-center gap-1">
                  {t("teamHandoff.config.maxInbox", "Pending reviews held")}
                  <input
                    type="number"
                    min={1}
                    max={HANDOFF_MAX_INBOX_CEILING}
                    value={accept?.maxInbox ?? ""}
                    onChange={(e) => patch({ accept: { ...(accept ?? { from: [] }), maxInbox: e.target.value ? Number(e.target.value) : undefined } })}
                    className={`${inputCls} w-20`}
                  />
                </label>
              </div>
            )}
          </div>

          {problems.length > 0 && (
            <ul className="list-disc ps-4 text-[11px] text-destructive" data-testid="adv-handoff-problems">
              {problems.map((p) => (
                <li key={`${p.code}-${p.n ?? 0}`}>{problemText(p)}</li>
              ))}
            </ul>
          )}
        </div>
      )}
    </section>
  );
}
