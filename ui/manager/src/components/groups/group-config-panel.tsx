import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Users, Settings2, ArrowRight, Trash2, AlertTriangle, RefreshCw, ClipboardList, Bot, Link2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn, hashColor, getInitials } from "@/lib/utils";
import type { AgentGroupConfiguration, DiscussionStyle, DiscussionPhase } from "@/lib/api/groups";
import { STYLE_INFO } from "@/lib/api/groups";
import { toast } from "sonner";
import { useDeleteGroup, useDeleteGroupWithMembers } from "@/hooks/use-groups";
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
  const styleInfo = STYLE_INFO[config.style] || STYLE_INFO.ROUND_TABLE;
  const styleColors = PANEL_STYLE_COLORS[config.style as DiscussionStyle] || PANEL_STYLE_COLORS.ROUND_TABLE;
  const [showDeleteConfirm, setShowDeleteConfirm] = useState<"group" | "all" | null>(null);
  const deleteGroupMutation = useDeleteGroup();
  const deleteWithMembersMutation = useDeleteGroupWithMembers();

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
          </div>
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
              <InfoRow
                label={t("groups.dynamicDelegation", "Delegation")}
                value={t("groups.dynamicMaxPerTask", "✓ (max {{count}}/task)", { count: config.dynamicAgents.maxDelegationsPerTask })}
              />
            )}
            <InfoRow
              label={t("groups.lifecyclePolicy", "Lifecycle")}
              value={t(`groups.lifecycle.${config.dynamicAgents.lifecyclePolicy}`, config.dynamicAgents.lifecyclePolicy.replace(/_/g, " ").toLowerCase())}
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
