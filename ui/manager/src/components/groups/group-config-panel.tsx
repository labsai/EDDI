import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Users, Settings2, ArrowRight, Trash2, AlertTriangle, RefreshCw, ClipboardList, Bot, Link2, HandMetal, Pencil, MessagesSquare, GitMerge, UserCheck, Gavel } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn, hashColor, getInitials, formatUsd } from "@/lib/utils";
import type { AgentGroupConfiguration, DiscussionStyle, DiscussionPhase } from "@/lib/api/groups";
import { normalizeLifecyclePolicy } from "@/lib/api/groups";
import { styleDisplay } from "@/lib/discussion-styles";
import {
  effectiveDelegationDepth,
  effectiveDelegationTimeout,
  moderatorlessPhaseNames,
  uncoveredRolePhases,
} from "@/lib/group-config";
import { timeoutPolicyLabel, granularityLabel, rejectionPolicyLabel } from "@/lib/hitl-labels";
import { formatIsoDuration } from "@/lib/hitl-config";
import { toast } from "sonner";
import { useDeleteGroup, useDeleteGroupWithMembers } from "@/hooks/use-groups";
import { GroupAdvancedEditor } from "./group-advanced-editor";
import { GroupHitlEditor } from "./group-hitl-editor";
import { GroupPhaseEditor } from "./group-phase-editor";
import { useNavigate } from "react-router-dom";

interface GroupConfigPanelProps {
  config: AgentGroupConfiguration;
  groupId?: string;
  groupVersion?: number;
  className?: string;
}

/** Style-specific accent colors for the config panel */
const PANEL_STYLE_COLORS: Record<DiscussionStyle, { bg: string; border: string; text: string }> = {
  ROUND_TABLE: { bg: "bg-amber-500/10", border: "border-amber-500/30", text: "text-amber-600 dark:text-amber-400" },
  PEER_REVIEW: { bg: "bg-teal-500/10", border: "border-teal-500/30", text: "text-teal-600 dark:text-teal-400" },
  DEVIL_ADVOCATE: { bg: "bg-rose-500/10", border: "border-rose-500/30", text: "text-rose-600 dark:text-rose-400" },
  DELPHI: { bg: "bg-violet-500/10", border: "border-violet-500/30", text: "text-violet-600 dark:text-violet-400" },
  DEBATE: { bg: "bg-indigo-500/10", border: "border-indigo-500/30", text: "text-indigo-600 dark:text-indigo-400" },
  TASK_FORCE: { bg: "bg-orange-500/10", border: "border-orange-500/30", text: "text-orange-600 dark:text-orange-400" },
  NEGOTIATION: { bg: "bg-emerald-500/10", border: "border-emerald-500/30", text: "text-emerald-600 dark:text-emerald-400" },
  CUSTOM: { bg: "bg-secondary/20", border: "border-border", text: "text-foreground" },
};

/** Fallback labels for ContextScope values (used as i18n defaults) */
const CONTEXT_SCOPE_FALLBACKS: Record<string, string> = {
  NONE: "independent",
  FULL: "full context",
  LAST_PHASE: "last phase",
  ANONYMOUS: "anonymous",
  OWN_FEEDBACK: "own feedback",
  TASK_ONLY: "task only",
  TASK_WITH_DEPS: "task + deps",
};

export function GroupConfigPanel({ config, groupId, groupVersion, className }: GroupConfigPanelProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const styleInfo = styleDisplay(config.style, t);
  const styleColors = PANEL_STYLE_COLORS[config.style as DiscussionStyle] || PANEL_STYLE_COLORS.ROUND_TABLE;
  const [showDeleteConfirm, setShowDeleteConfirm] = useState<"group" | "all" | null>(null);
  // Mutually exclusive on purpose. Both editors write the whole config from
  // their own snapshot at the same `groupVersion`, so having both open means the
  // second save either loses the first one's edit or 409s — neither is a state
  // worth letting a user assemble.
  const [editingHitl, setEditingHitl] = useState(false);
  const [editingPhases, setEditingPhases] = useState(false);
  const [editingAdvanced, setEditingAdvanced] = useState(false);

  /**
   * Whether the advanced block has anything to summarize. Kept separate from
   * whether the SECTION renders — the section shows regardless (so the features
   * are discoverable), this only picks summary vs empty state.
   */
  const hasAdvanced =
    !!config.humanMemberConfig ||
    !!config.retroConfig ||
    !!config.contextWindow?.enabled ||
    !!config.facilitator?.enabled ||
    !!config.artifactConfig?.allowArtifactTools ||
    config.taskListConfig?.assignmentMode === "BID";
  const canEditHitl = !!groupId && groupVersion != null;
  const deleteGroupMutation = useDeleteGroup();
  const deleteWithMembersMutation = useDeleteGroupWithMembers();

  const hitl = config.hitlConfig;
  const approvalPhaseNames = (config.phases ?? []).filter((p) => p.requiresApproval).map((p) => p.name);
  const hasHitl = !!hitl || approvalPhaseNames.length > 0;
  const moderatorlessPhases = useMemo(() => moderatorlessPhaseNames(config), [config]);
  const roleGaps = useMemo(() => uncoveredRolePhases(config), [config]);
  const canEditPhases = !!groupId && groupVersion != null;

  /**
   * One row per phase that has abstention or convergence turned on. Phases with
   * neither are omitted — listing "off" for every phase of a six-phase preset
   * buries the one that is configured.
   */
  const phaseBehaviourSummary = useMemo(() => {
    return (config.phases ?? [])
      .filter((p) => p.allowAbstention || p.convergence?.enabled)
      .map((p) => {
        const parts: string[] = [];
        if (p.allowAbstention) parts.push(t("groups.abstentionShort", "abstain"));
        if (p.convergence?.enabled) {
          parts.push(
            t("groups.convergenceShort", "converge ≥{{threshold}}", {
              threshold: p.convergence.threshold.toFixed(2),
            }),
          );
        }
        return { name: p.name, value: parts.join(" · ") };
      });
  }, [config.phases, t]);

  function handleDeleteGroupOnly() {
    if (!groupId || groupVersion == null) return;
    deleteGroupMutation.mutate(
      { id: groupId, version: groupVersion },
      {
        onSuccess: () => {
          toast.success(t("groups.deleteGroupOnlySuccess", "Group deleted (agents kept)"));
          navigate("/manage/groups");
        },
        onError: () => toast.error(t("common.error")),
      }
    );
  }

  function handleDeleteWithMembers() {
    if (!groupId || groupVersion == null) return;
    deleteWithMembersMutation.mutate(
      { groupId, version: groupVersion, config },
      {
        onSuccess: () => {
          toast.success(t("groups.deleteWithMembersSuccess", "Group and all member agents deleted (soft-delete)"));
          navigate("/manage/groups");
        },
        onError: () => toast.error(t("common.error")),
      }
    );
  }

  return (
    <div className={cn("flex flex-col gap-4 p-4 overflow-y-auto", className)}>
      {/* Group name & description */}
      <div>
        <h3 className="text-sm font-bold text-foreground">{config.name}</h3>
        {config.description && (
          <p className="mt-1 text-xs text-muted-foreground line-clamp-3" title={config.description}>
            {config.description}
          </p>
        )}
      </div>

      {/* Discussion style — color-coded */}
      <div>
        <h4 className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground mb-1.5">
          {t("groups.discussionStyle", "Discussion Style")}
        </h4>
        <div className={cn("rounded-lg border p-2.5", styleColors.border, styleColors.bg)}>
          <div className="flex items-center gap-2 mb-1">
            <span className="text-base">{styleInfo.icon}</span>
            <span className={cn("text-sm font-semibold", styleColors.text)}>{styleInfo.label}</span>
          </div>
          <PhaseFlowPreview flow={styleInfo.flow} phases={config.phases} />
        </div>
      </div>

      {/* Members */}
      <div>
        <h4 className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground mb-1.5">
          <Users className="inline h-3 w-3 me-1" />
          {t("groups.membersCount", { count: config.members.length, defaultValue: "{{count}} Members" })}
        </h4>
        <div className="space-y-1">
          {config.members.map((member, idx) => (
            <div
              key={`${member.agentId}-${idx}`}
              className="flex items-center gap-2 rounded-md px-2 py-1.5 hover:bg-secondary/30 transition-colors"
            >
              <div
                className={cn(
                  "flex h-6 w-6 items-center justify-center rounded-full text-[10px] font-bold text-white shrink-0",
                  hashColor(member.agentId || String(idx))
                )}
              >
                {getInitials(member.displayName || "?")}
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-1">
                  <p className="text-xs font-medium text-foreground truncate" title={member.displayName}>
                    {member.displayName}
                  </p>
                  {member.speakingOrder != null && (
                    <span
                      className="inline-flex items-center justify-center h-4 min-w-4 rounded-full bg-muted text-[9px] font-semibold text-muted-foreground px-1 shrink-0"
                      title={t("groups.speakingOrderTooltip", "Speaking order: #{{order}}", { order: member.speakingOrder })}
                    >
                      #{member.speakingOrder}
                    </span>
                  )}
                </div>
                {member.agentId && (
                  <p className="text-[10px] text-muted-foreground font-mono truncate" title={member.agentId}>
                    {member.agentId.slice(0, 12)}…
                  </p>
                )}
              </div>
              <div className="flex items-center gap-1 shrink-0">
                {member.role && (
                  <Badge variant="outline" className="text-[9px] px-1 py-0">
                    {member.role}
                  </Badge>
                )}
                {member.memberType === "GROUP" && (
                  <Badge variant="secondary" className="text-[9px] px-1 py-0">
                    <Users className="h-2 w-2 me-0.5" />
                    {t("groups.memberTypeGroup", "Group")}
                  </Badge>
                )}
                {member.memberType === "HUMAN" && (
                  <Badge variant="secondary" className="text-[9px] px-1 py-0">
                    <UserCheck className="h-2 w-2 me-0.5" />
                    {t("groups.memberTypeHuman", "Human")}
                  </Badge>
                )}
                {config.moderatorAgentId === member.agentId && (
                  <Badge variant="default" className="text-[9px] px-1 py-0">
                    {t("groups.moderatorBadge", "⭐ Mod")}
                  </Badge>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Protocol */}
      {config.protocol && (
        <div>
          <h4 className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground mb-1.5">
            <Settings2 className="inline h-3 w-3 me-1" />
            {t("groups.protocolSettings", "Protocol")}
          </h4>
          <div className="rounded-lg border border-border bg-secondary/30 p-2.5 space-y-1">
            <InfoRow label={t("groups.protocolTimeout", "Timeout")} value={`${config.protocol.agentTimeoutSeconds}s`} />
            <InfoRow label={t("groups.protocolOnFailure", "On Failure")} value={t(`groupWizard.policy${config.protocol.onAgentFailure.charAt(0) + config.protocol.onAgentFailure.slice(1).toLowerCase()}`, config.protocol.onAgentFailure.charAt(0) + config.protocol.onAgentFailure.slice(1).toLowerCase())} />
            <InfoRow label={t("groups.protocolMaxRetries", "Max Retries")} value={String(config.protocol.maxRetries)} />
            <InfoRow label={t("groups.protocolUnavailable", "Unavailable")} value={t(`groupWizard.policy${config.protocol.onMemberUnavailable.charAt(0) + config.protocol.onMemberUnavailable.slice(1).toLowerCase()}`, config.protocol.onMemberUnavailable.charAt(0) + config.protocol.onMemberUnavailable.slice(1).toLowerCase())} />
            <InfoRow label={t("groups.protocolMaxRounds", "Max Rounds")} value={String(config.maxRounds)} />
            {config.protocol.maxTurns != null && config.protocol.maxTurns > 0 && (
              <InfoRow label={t("groups.protocolMaxTurns", "Max Turns")} value={String(config.protocol.maxTurns)} />
            )}
            {config.protocol.maxCostPerDiscussion != null && config.protocol.maxCostPerDiscussion > 0 && (
              <>
                <InfoRow
                  label={t("groups.protocolMaxCost", "Cost Ceiling")}
                  value={formatUsd(config.protocol.maxCostPerDiscussion)}
                />
                <InfoRow
                  label={t("groups.protocolOnCostExceeded", "When hit")}
                  value={
                    (config.protocol.onCostExceeded ?? "SYNTHESIZE_NOW") === "ABORT"
                      ? t("groups.costAbort", "Abort")
                      : t("groups.costSynthesize", "Synthesize now")
                  }
                />
              </>
            )}
          </div>
        </div>
      )}

      {/* Per-phase behaviour — abstention and convergence, both of which live on
          the phase list and have no other editing surface. */}
      {canEditPhases && (
        <div>
          <h4 className="mb-1.5 flex items-center text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
            <GitMerge className="inline h-3 w-3 me-1" />
            {t("groups.phaseBehaviour", "Phase Behaviour")}
            {!editingPhases && (
              <button
                type="button"
                onClick={() => { setEditingHitl(false); setEditingAdvanced(false); setEditingPhases(true); }}
                className="ms-auto inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-medium normal-case text-primary transition-colors hover:bg-primary/10"
                data-testid="group-phase-edit"
              >
                <Pencil className="h-2.5 w-2.5" />
                {t("groups.hitlEdit", "Edit")}
              </button>
            )}
          </h4>
          {editingPhases ? (
            <GroupPhaseEditor
              config={config}
              groupId={groupId!}
              groupVersion={groupVersion!}
              onDone={() => setEditingPhases(false)}
            />
          ) : (
            <div className="space-y-1 rounded-lg border border-border bg-secondary/30 p-2.5">
              {phaseBehaviourSummary.length > 0 ? (
                phaseBehaviourSummary.map((row) => (
                  <InfoRow key={row.name} label={row.name} value={row.value} />
                ))
              ) : (
                <p className="text-[10px] text-muted-foreground">
                  {t(
                    "groups.phaseBehaviourNone",
                    "No abstention or convergence configured — click Edit to let members pass, or to stop a repeating phase once they agree.",
                  )}
                </p>
              )}
            </div>
          )}
        </div>
      )}

      {/* Deliberation quality — only shown when something is actually on, so an
          untouched group's panel does not grow two rows of "off". */}
      {(config.recordDissents || config.taskListConfig?.allowAgentTaskCreation) && (
        <div>
          <h4 className="mb-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
            <MessagesSquare className="inline h-3 w-3 me-1" />
            {t("groups.deliberationSection", "Deliberation")}
          </h4>
          <div className="space-y-1 rounded-lg border border-border bg-secondary/30 p-2.5">
            {config.recordDissents && (
              <InfoRow
                label={t("groups.recordDissents", "Minority report")}
                value={t("groups.enabled", "Enabled")}
              />
            )}
            {config.taskListConfig?.allowAgentTaskCreation && (
              <>
                <InfoRow
                  label={t("groups.agentFiledTasks", "Agent-filed tasks")}
                  value={t("groups.enabled", "Enabled")}
                />
                <InfoRow
                  label={t("groups.agentFiledCaps", "Caps")}
                  value={t("groups.agentFiledCapsValue", "{{perDiscussion}} / discussion · {{perTurn}} / turn", {
                    perDiscussion: config.taskListConfig.maxAgentAddedTasksPerDiscussion,
                    perTurn: config.taskListConfig.maxPerTurn,
                  })}
                />
              </>
            )}
          </div>
        </div>
      )}

      {/* A phase restricted to a moderator the group does not name cannot run as
          written — the engine substitutes the first member by speaking order. */}
      {moderatorlessPhases.length > 0 && (
        <div
          className="flex items-start gap-2 rounded-lg border border-amber-500/30 bg-amber-500/5 p-2.5"
          data-testid="group-moderatorless-warning"
        >
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-500" />
          <p className="text-[10px] leading-relaxed text-muted-foreground">
            {t(
              "groups.moderatorlessWarning",
              "Restricted to a moderator this group does not have: {{phases}}. The first member by speaking order will stand in.",
              { phases: moderatorlessPhases.join(", ") },
            )}
          </p>
        </div>
      )}

      {/* Phases addressed to a ROLE no member carries (a DEBATE with no CON,
          a DEVIL_ADVOCATE group without the advocate) — they would run with
          zero speakers and the backend never says so. */}
      {roleGaps.map((gap) => (
        <div
          key={gap.role}
          className="flex items-start gap-2 rounded-lg border border-amber-500/30 bg-amber-500/5 p-2.5"
          data-testid="group-role-coverage-warning"
        >
          <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-500" />
          <p className="text-[10px] leading-relaxed text-muted-foreground">
            {t(
              "groups.roleCoverageWarning",
              'No member carries the role "{{role}}" — {{phases}} would run with no speakers.',
              { role: gap.role, phases: gap.phaseNames.join(", ") },
            )}
          </p>
        </div>
      ))}

      {/* Human-in-the-Loop approval — read-only summary + inline editor */}
      {(hasHitl || canEditHitl) && (
        <div>
          <h4 className="mb-1.5 flex items-center text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
            <HandMetal className="inline h-3 w-3 me-1" />
            {t("groups.hitlSection", "Human Approval")}
            {canEditHitl && !editingHitl && (
              <button
                type="button"
                onClick={() => { setEditingPhases(false); setEditingAdvanced(false); setEditingHitl(true); }}
                className="ms-auto inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-medium normal-case text-primary transition-colors hover:bg-primary/10"
                data-testid="group-hitl-edit"
              >
                <Pencil className="h-2.5 w-2.5" />
                {t("groups.hitlEdit", "Edit")}
              </button>
            )}
          </h4>
          {editingHitl && groupId && groupVersion != null ? (
            <GroupHitlEditor
              config={config}
              groupId={groupId}
              groupVersion={groupVersion}
              onDone={() => setEditingHitl(false)}
            />
          ) : hasHitl ? (
            <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-2.5 space-y-1">
              {approvalPhaseNames.length > 0 && (
                <InfoRow
                  label={t("groups.hitlApprovalPoints", "Approval at")}
                  value={approvalPhaseNames.join(", ")}
                />
              )}
              {hitl?.timeoutPolicy && (
                <InfoRow
                  label={t("hitl.timeoutPolicy", "Timeout")}
                  value={
                    timeoutPolicyLabel(t, hitl.timeoutPolicy) +
                    (hitl.approvalTimeout ? ` (${formatIsoDuration(hitl.approvalTimeout)})` : "")
                  }
                />
              )}
              {hitl?.granularity && (
                <InfoRow
                  label={t("hitl.granularity", "Granularity")}
                  value={granularityLabel(t, hitl.granularity)}
                />
              )}
              {hitl?.granularity === "TASK" && hitl?.onTaskRejection && (
                <InfoRow
                  label={t("groups.hitlOnTaskRejection", "On rejection")}
                  value={rejectionPolicyLabel(t, hitl.onTaskRejection)}
                />
              )}
            </div>
          ) : (
            <p className="rounded-lg border border-border bg-secondary/20 p-2.5 text-[10px] text-muted-foreground">
              {t("groups.hitlNotConfigured", "No approval gates configured — click Edit to require human approval.")}
            </p>
          )}
        </div>
      )}

      {/* Pre-configured Tasks (TASK_FORCE) */}
      {config.tasks && config.tasks.length > 0 && (
        <div>
          <h4 className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground mb-1.5">
            <ClipboardList className="inline h-3 w-3 me-1" />
            {t("groups.preConfiguredTasksCount", "Pre-configured Tasks ({{count}})", { count: config.tasks.length })}
          </h4>
          <div className="space-y-1.5">
            {config.tasks.map((task, idx) => (
              <div
                key={`${task.subject}-${idx}`}
                className="rounded-lg border border-border bg-secondary/20 p-2 space-y-1"
              >
                <div className="flex items-center gap-1.5">
                  <span className="text-xs font-medium text-foreground">{task.subject}</span>
                  <Badge variant="outline" className="text-[9px] px-1 py-0 ms-auto">
                    P{task.priority}
                  </Badge>
                </div>
                {task.description && (
                  <p className="text-[10px] text-muted-foreground line-clamp-2" title={task.description}>{task.description}</p>
                )}
                <div className="flex items-center gap-2 text-[10px] text-muted-foreground">
                  <span>→ {task.assignToRole}</span>
                  {task.dependsOn && task.dependsOn.length > 0 && (
                    <span className="flex items-center gap-0.5">
                      <Link2 className="h-2.5 w-2.5" />
                      {task.dependsOn.join(", ")}
                    </span>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Dynamic Agents */}
      {config.dynamicAgents && config.dynamicAgents.enabled && (
        <div>
          <h4 className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground mb-1.5">
            <Bot className="inline h-3 w-3 me-1" />
            {t("groups.dynamicAgents", "Dynamic Agents")}
          </h4>
          <div className="rounded-lg border border-border bg-secondary/30 p-2.5 space-y-1">
            {config.dynamicAgents.allowCreation && (
              <InfoRow
                label={t("groups.dynamicCreation", "Creation")}
                value={t("groups.dynamicMax", "✓ (max {{count}})", { count: config.dynamicAgents.maxCreatedAgentsPerDiscussion })}
              />
            )}
            {config.dynamicAgents.allowRecruitment && (
              <InfoRow
                label={t("groups.dynamicRecruitment", "Recruitment")}
                value={t("groups.dynamicMax", "✓ (max {{count}})", { count: config.dynamicAgents.maxRecruitedAgentsPerDiscussion })}
              />
            )}
            {config.dynamicAgents.allowDelegation && (
              <>
                <InfoRow
                  label={t("groups.dynamicDelegation", "Delegation")}
                  value={t("groups.dynamicMaxPerTask", "✓ (max {{count}}/task)", { count: config.dynamicAgents.maxDelegationsPerTask })}
                />
                <InfoRow
                  label={t("groups.delegationDepth", "Max Depth")}
                  value={String(effectiveDelegationDepth(config.dynamicAgents.maxDelegationDepth))}
                />
                <InfoRow
                  label={t("groups.delegationTimeout", "Delegate Timeout")}
                  value={t("groups.secondsShort", "{{seconds}}s", {
                    seconds: effectiveDelegationTimeout(config.dynamicAgents.delegationTimeoutSeconds),
                  })}
                />
                {(config.dynamicAgents.allowedDelegationTargets?.length ?? 0) > 0 && (
                  <InfoRow
                    label={t("groups.delegationTargets", "Allow-list")}
                    value={config.dynamicAgents.allowedDelegationTargets!.join(", ")}
                  />
                )}
              </>
            )}
            <InfoRow
              label={t("groups.lifecyclePolicy", "Lifecycle")}
              value={(() => {
                // The backend writes this enum hyphenated and lower-case
                // (@JsonValue); the i18n keys are the canonical constants.
                const policy = normalizeLifecyclePolicy(config.dynamicAgents.lifecyclePolicy);
                return t(`groups.lifecycle.${policy}`, policy.replace(/_/g, " ").toLowerCase());
              })()}
            />
            {config.dynamicAgents.allowedProviders.length > 0 && (
              <InfoRow
                label={t("groups.allowedProviders", "Providers")}
                value={config.dynamicAgents.allowedProviders.join(", ")}
              />
            )}
            {Object.keys(config.dynamicAgents.allowedModels).length > 0 && (
              <InfoRow
                label={t("groups.allowedModels", "Models")}
                value={formatAllowedModels(config.dynamicAgents.allowedModels)}
              />
            )}
            <InfoRow
              label={t("groups.inheritParentModel", "Inherit Model")}
              value={config.dynamicAgents.inheritParentModel
                ? t("groups.enabled", "Enabled")
                : t("groups.disabled", "Disabled")}
            />
          </div>
        </div>
      )}

      {/* Advanced collaboration features (I6/I8/I9/I12/I17/I18) — summary plus
          inline editor, same shape as Phases/HITL above.
          Rendered whenever it is editable, NOT only when something is already
          set: hiding it on an unconfigured group meant hiding it on every group,
          so these features were unreachable and invisible at the same time. The
          "don't show a page of offs" rule still applies INSIDE the summary —
          each row appears only when its feature is on — but the section itself
          keeps an empty state and an Edit affordance, like Human Approval. */}
      {(hasAdvanced || canEditHitl) && (
        <div>
          <h4 className="mb-1.5 flex items-center text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
            <Gavel className="inline h-3 w-3 me-1" />
            {t("groups.advancedSection", "Advanced Collaboration")}
            {canEditHitl && !editingAdvanced && (
              <button
                type="button"
                onClick={() => { setEditingPhases(false); setEditingHitl(false); setEditingAdvanced(true); }}
                className="ms-auto inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[10px] font-medium normal-case text-primary transition-colors hover:bg-primary/10"
                data-testid="group-advanced-edit"
              >
                <Pencil className="h-2.5 w-2.5" />
                {t("groups.hitlEdit", "Edit")}
              </button>
            )}
          </h4>
          {editingAdvanced && groupId && groupVersion != null ? (
            <GroupAdvancedEditor
              config={config}
              groupId={groupId}
              groupVersion={groupVersion}
              onDone={() => setEditingAdvanced(false)}
            />
          ) : !hasAdvanced ? (
            <p
              className="rounded-lg border border-border bg-secondary/30 p-2.5 text-[11px] text-muted-foreground"
              data-testid="group-advanced-none"
            >
              {t(
                "groups.advancedNotConfigured",
                "No transcript window, retro memory, shared artifacts or facilitator configured — click Edit to turn them on.",
              )}
            </p>
          ) : (
          <div className="space-y-1 rounded-lg border border-border bg-secondary/30 p-2.5">
            {config.humanMemberConfig && (
              <InfoRow
                label={t("groups.humanMemberConfigLabel", "Human turn timeout")}
                value={
                  config.humanMemberConfig.turnTimeout
                    ? `${formatIsoDuration(config.humanMemberConfig.turnTimeout)} → ${
                        config.humanMemberConfig.onTimeout === "ABORT"
                          ? t("groupWizard.humanOnTimeoutAbort", "Abort the discussion")
                          : t("groupWizard.humanOnTimeoutSkip", "Skip their turn and continue")
                      }`
                    : t("groups.waitIndefinitely", "Wait indefinitely")
                }
              />
            )}
            {config.retroConfig && (
              <InfoRow
                label={t("groups.retroConfigLabel", "Retro lessons")}
                value={t("groups.retroConfigValue", "{{perRun}}/run, {{stored}} stored max", {
                  perRun: config.retroConfig.maxLessonsPerRun,
                  stored: config.retroConfig.maxStoredLessons,
                })}
              />
            )}
            {config.contextWindow?.enabled && (
              <InfoRow
                label={t("groups.contextWindowLabel", "Transcript window")}
                value={t("groups.contextWindowValue", "last {{count}} entries{{summarized}}", {
                  count: config.contextWindow.maxRecentEntries,
                  summarized:
                    config.contextWindow.summarizeOverflow === false
                      ? ""
                      : ` (${t("groups.summarized", "summarized")})`,
                })}
              />
            )}
            {config.facilitator?.enabled && (
              <>
                <InfoRow
                  label={t("groups.facilitatorLabel", "Facilitator")}
                  value={config.facilitator.agentId || t("groups.notSet", "not set")}
                />
                <InfoRow
                  label={t("groups.facilitatorMoves", "Allowed moves")}
                  value={config.facilitator.allowedMoves.join(", ")}
                />
              </>
            )}
            {config.artifactConfig?.allowArtifactTools && (
              <InfoRow
                label={t("groups.artifactConfigLabel", "Shared artifacts")}
                value={t("groups.artifactConfigValue", "max {{count}}/discussion", {
                  count: config.artifactConfig.maxArtifactsPerDiscussion,
                })}
              />
            )}
            {/* Only BID is worth a row — ROLE is the default every group already has. */}
            {config.taskListConfig?.assignmentMode === "BID" && (
              <InfoRow
                label={t("groups.assignmentModeLabel", "Task assignment")}
                value={t("groups.assignmentModeBidShort", "By bid")}
              />
            )}
          </div>
          )}
        </div>
      )}

      {/* Delete group + all agents */}
      {groupId && groupVersion != null && (
        <div className="mt-auto pt-4 pb-4 border-t border-border space-y-2">
          {!showDeleteConfirm ? (
            <>
              <Button
                variant="outline"
                size="sm"
                className="w-full text-muted-foreground border-border hover:bg-secondary/50"
                onClick={() => setShowDeleteConfirm("group")}
                disabled={deleteGroupMutation.isPending || deleteWithMembersMutation.isPending}
              >
                <Trash2 className="h-3.5 w-3.5 me-1.5" />
                {t("groups.deleteGroupOnly", "Delete Group Only")}
              </Button>
              <Button
                variant="outline"
                size="sm"
                className="w-full text-destructive border-destructive/30 hover:bg-destructive/10 hover:text-destructive"
                onClick={() => setShowDeleteConfirm("all")}
                disabled={deleteGroupMutation.isPending || deleteWithMembersMutation.isPending}
              >
                <Trash2 className="h-3.5 w-3.5 me-1.5" />
                {t("groups.deleteGroupAndAgents", "Delete Group + All Agents")}
              </Button>
            </>
          ) : (
            <div className="space-y-2">
              <div className="flex items-start gap-2 rounded-lg border border-amber-500/30 bg-amber-500/5 p-2">
                <AlertTriangle className="h-3.5 w-3.5 text-amber-500 shrink-0 mt-0.5" />
                <p className="text-[10px] text-muted-foreground leading-relaxed">
                  {showDeleteConfirm === "all"
                    ? t("groups.deleteWithMembersWarning", "This will soft-delete the group and all {{count}} member agents. They can be recovered.", { count: config.members.length })
                    : t("groups.deleteGroupOnlyWarning", "This will delete the group. All member agents will be kept.")}
                </p>
              </div>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  className="flex-1"
                  onClick={() => setShowDeleteConfirm(null)}
                >
                  {t("common.cancel", "Cancel")}
                </Button>
                <Button
                  variant="destructive"
                  size="sm"
                  className="flex-1"
                  onClick={showDeleteConfirm === "all" ? handleDeleteWithMembers : handleDeleteGroupOnly}
                  disabled={showDeleteConfirm === "all" ? deleteWithMembersMutation.isPending : deleteGroupMutation.isPending}
                >
                  {(showDeleteConfirm === "all" ? deleteWithMembersMutation.isPending : deleteGroupMutation.isPending) ? (
                    <RefreshCw className="h-3 w-3 animate-spin me-1" />
                  ) : (
                    <Trash2 className="h-3 w-3 me-1" />
                  )}
                  {t("common.confirm", "Confirm")}
                </Button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between text-xs">
      <span className="text-muted-foreground">{label}</span>
      <span className="font-medium text-foreground">{value}</span>
    </div>
  );
}

/** Format allowedModels Record<string, string[]> into a compact display string */
function formatAllowedModels(allowedModels: Record<string, string[]>): string {
  const parts: string[] = [];
  for (const [provider, models] of Object.entries(allowedModels)) {
    for (const model of models) {
      parts.push(`${provider}:${model}`);
    }
  }
  return parts.join(", ") || "—";
}

/**
 * Enhanced flow preview that shows richer phase info when phase data is available.
 * Falls back to the simple text flow (from STYLE_INFO) when no phases are configured.
 */
function PhaseFlowPreview({ flow, phases }: { flow: string; phases: DiscussionPhase[] | null }) {
  const { t } = useTranslation();

  // If we have real phase data, render the enhanced version
  if (phases && phases.length > 0) {
    return (
      <div className="flex flex-wrap items-start gap-1 mt-1">
        {phases.map((phase, idx) => {
          const turnIcon = phase.turnOrder === "PARALLEL" ? "⇉" : "⟳";
          const turnLabel = phase.turnOrder === "PARALLEL"
            ? t("groups.turnOrderParallel", "parallel")
            : t("groups.turnOrderSequential", "sequential");
          const scopeLabel = t(`groups.contextScope.${phase.contextScope}`, CONTEXT_SCOPE_FALLBACKS[phase.contextScope] || phase.contextScope.toLowerCase());

          return (
            <span key={idx} className="flex items-center gap-1">
              <span
                className="group relative text-[10px] font-medium text-foreground/80 rounded-md bg-background px-1.5 py-0.5 border border-border cursor-default"
                title={`${turnLabel} · ${scopeLabel}${phase.requiresApproval ? ` · ${t("groups.requiresApproval", "requires approval")}` : ""}`}
              >
                <div className="flex flex-col items-center gap-0">
                  <span className="flex items-center gap-0.5">
                    <span className="opacity-60" aria-label={turnLabel}>{turnIcon}</span>
                    {phase.name}
                  </span>
                  <span className="text-[8px] text-muted-foreground font-normal leading-none">
                    {scopeLabel}
                  </span>
                </div>
              </span>
              {idx < phases.length - 1 && (
                <ArrowRight className="h-2.5 w-2.5 text-muted-foreground shrink-0" />
              )}
            </span>
          );
        })}
      </div>
    );
  }

  // Fallback: simple text-based flow preview (no phase data available)
  const steps = flow.split(" → ");
  return (
    <div className="flex flex-wrap items-center gap-1 mt-1">
      {steps.map((step, idx) => (
        <span key={idx} className="flex items-center gap-1">
          <span className="text-[10px] font-medium text-foreground/80 rounded-md bg-background px-1.5 py-0.5 border border-border">
            {step}
          </span>
          {idx < steps.length - 1 && (
            <ArrowRight className="h-2.5 w-2.5 text-muted-foreground" />
          )}
        </span>
      ))}
    </div>
  );
}
