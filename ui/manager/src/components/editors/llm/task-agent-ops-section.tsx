import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Gauge } from "lucide-react";
import { EditorSection } from "../editor-section";
import { NumberInput } from "../number-input";
import type { TaskSectionProps } from "./task-section-props";
import { withOpsField, withOpsTool, type OpsBlock as Block } from "./agent-ops";
import { AGENT_OPS_SLUGS, type AgentOpsSlug } from "./types";

/** Server defaults, shown as placeholders only (they are never written out). */
const DEFAULTS = {
  weeks: 4,
  maxLessons: 5,
  proposalsPerTurn: 1,
  proposalsPerDay: 3,
  maxAmountUsd: 25,
  maxPending: 5,
  followUpsPerTurn: 2,
  horizonDays: 30,
  maxCostPerFireUsd: 0.5,
};

/**
 * The three agent-operations tools: a read-only team scorecard, a budget
 * proposal that a human administrator must approve, and one-off follow-up
 * scheduling. Each is off unless named in the built-in tool whitelist; the limits
 * below only tune them (every field has a server default).
 */
export function TaskAgentOpsSection({ task, onChange, readOnly }: TaskSectionProps) {
  const { t } = useTranslation();
  const uid = useId();
  const [groupText, setGroupText] = useState((task.agentOps?.scorecard?.groupIds ?? []).join(", "));
  const whitelist = task.builtInToolsWhitelist ?? [];
  const on = (slug: AgentOpsSlug) => !!task.enableBuiltInTools && whitelist.includes(slug);
  const anyOn = AGENT_OPS_SLUGS.some(on);
  const inputCls =
    "h-7 w-20 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring aria-[invalid=true]:border-destructive";

  const field = (
    block: Block,
    name: string,
    label: string,
    placeholder: number,
    opts: { integer?: boolean } = {},
  ) => {
    const id = `${uid}-${block}-${name}`;
    const current = (task.agentOps?.[block] as Record<string, unknown> | undefined)?.[name];
    return (
      <div className="flex items-center gap-2" key={id}>
        <label htmlFor={id} className="text-xs text-foreground whitespace-nowrap">
          {label}
        </label>
        <NumberInput
          id={id}
          integer={opts.integer}
          value={typeof current === "number" ? current : undefined}
          onChange={(v) => onChange(withOpsField(task, block, name, v))}
          readOnly={readOnly}
          placeholder={String(placeholder)}
          className={inputCls}
          data-testid={`agent-ops-${block}-${name}`}
        />
      </div>
    );
  };

  const toggle = (slug: AgentOpsSlug, label: string, desc: string) => (
    <label className="flex items-start gap-2 text-xs text-foreground" key={slug}>
      <input
        type="checkbox"
        checked={on(slug)}
        onChange={(e) => onChange(withOpsTool(task, slug, e.target.checked))}
        disabled={readOnly}
        className="mt-0.5 h-3.5 w-3.5 rounded border-input accent-primary"
        data-testid={`agent-ops-enable-${slug}`}
      />
      <span>
        <span className="font-medium">{label}</span>
        <span className="block text-[10px] text-muted-foreground leading-relaxed">{desc}</span>
      </span>
    </label>
  );

  return (
    <EditorSection
      label={t("llmEditor.agentOps", "Agent Operations Tools")}
      icon={Gauge}
      accent="text-warn-500"
      defaultOpen={anyOn || !!task.agentOps}
    >
      <div className="space-y-3" data-testid="task-agent-ops-section">
        <p className="text-[10px] text-muted-foreground leading-relaxed">
          {t(
            "llmEditor.agentOpsDesc",
            "Tools that let an agent read its team's numbers, ask for budget and book its own check-backs. Each is off unless ticked here, even when the agent has all built-in tools.",
          )}
        </p>

        {toggle(
          "team_scorecard",
          t("llmEditor.agentOpsScorecard", "Team scorecard (read-only)"),
          t(
            "llmEditor.agentOpsScorecardDesc",
            "Spend, budget left, tasks done and failed per week, approval outcomes with reason codes and recent retro lessons, for the agent's own team and the teams it oversees. Only teams the conversation's user may view.",
          ),
        )}
        {on("team_scorecard") && (
          <div className="ms-5 space-y-2" data-testid="agent-ops-scorecard-limits">
            {field("scorecard", "weeks", t("llmEditor.agentOpsWeeks", "Weeks of history"), DEFAULTS.weeks, { integer: true })}
            {field("scorecard", "maxLessons", t("llmEditor.agentOpsMaxLessons", "Retro lessons per team"), DEFAULTS.maxLessons, { integer: true })}
            <div className="flex items-center gap-2">
              <label htmlFor={`${uid}-groups`} className="text-xs text-foreground whitespace-nowrap">
                {t("llmEditor.agentOpsGroups", "Extra team ids")}
              </label>
              <input
                id={`${uid}-groups`}
                type="text"
                value={groupText}
                onChange={(e) => {
                  setGroupText(e.target.value);
                  const ids = e.target.value
                    .split(",")
                    .map((s) => s.trim())
                    .filter(Boolean);
                  onChange(withOpsField(task, "scorecard", "groupIds", ids));
                }}
                readOnly={readOnly}
                placeholder="groupId1, groupId2"
                className="h-7 flex-1 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                data-testid="agent-ops-scorecard-groupIds"
              />
            </div>
          </div>
        )}

        {toggle(
          "propose_team_budget",
          t("llmEditor.agentOpsBudget", "Budget proposal"),
          t(
            "llmEditor.agentOpsBudgetDesc",
            "Propose raising a team's cap or moving budget between teams. Applied only when an administrator approves the call. The tool is not offered unless Tool Approval Gating requires approval for propose_team_budget_*.",
          ),
        )}
        {on("propose_team_budget") && (
          <div className="ms-5 space-y-2" data-testid="agent-ops-budget-limits">
            <p className="text-[10px] text-warn-600 dark:text-warn-400 leading-relaxed" data-testid="agent-ops-budget-gate-hint">
              {t(
                "llmEditor.agentOpsBudgetGateHint",
                "Add propose_team_budget_* to Tool Approval Gating (agent or task level) or the agent will not get this tool.",
              )}
            </p>
            {field("budgetProposal", "maxPerTurn", t("llmEditor.agentOpsPerTurn", "Calls per turn"), DEFAULTS.proposalsPerTurn, { integer: true })}
            {field("budgetProposal", "maxPerDay", t("llmEditor.agentOpsPerDay", "Applied per day"), DEFAULTS.proposalsPerDay, { integer: true })}
            {field("budgetProposal", "maxAmountUsd", t("llmEditor.agentOpsMaxAmount", "Max amount per proposal ($)"), DEFAULTS.maxAmountUsd)}
          </div>
        )}

        {toggle(
          "schedule_followup",
          t("llmEditor.agentOpsFollowUp", "Follow-up scheduling"),
          t(
            "llmEditor.agentOpsFollowUpDesc",
            "Book, list and cancel one-off check-backs for the agent's own conversation or a fresh one. Every fire carries a cost ceiling.",
          ),
        )}
        {on("schedule_followup") && (
          <div className="ms-5 space-y-2" data-testid="agent-ops-followup-limits">
            {field("followUp", "maxPending", t("llmEditor.agentOpsMaxPending", "Waiting at once"), DEFAULTS.maxPending, { integer: true })}
            {field("followUp", "maxPerTurn", t("llmEditor.agentOpsPerTurn", "Calls per turn"), DEFAULTS.followUpsPerTurn, { integer: true })}
            {field("followUp", "maxHorizonDays", t("llmEditor.agentOpsHorizon", "Horizon (days, max 90)"), DEFAULTS.horizonDays, { integer: true })}
            {field("followUp", "maxCostPerFireUsd", t("llmEditor.agentOpsCostPerFire", "Cost ceiling per fire ($)"), DEFAULTS.maxCostPerFireUsd)}
          </div>
        )}
      </div>
    </EditorSection>
  );
}
