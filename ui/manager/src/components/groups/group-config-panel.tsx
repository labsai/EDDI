import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Users, Settings2, ArrowRight, Trash2, AlertTriangle, RefreshCw } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn, hashColor, getInitials } from "@/lib/utils";
import type { AgentGroupConfiguration, DiscussionStyle } from "@/lib/api/groups";
import { STYLE_INFO } from "@/lib/api/groups";
import { toast } from "sonner";
import { useDeleteGroupWithMembers } from "@/hooks/use-groups";
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
  CUSTOM: { bg: "bg-secondary/20", border: "border-border", text: "text-foreground" },
};

export function GroupConfigPanel({ config, groupId, groupVersion, className }: GroupConfigPanelProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const styleInfo = STYLE_INFO[config.style] || STYLE_INFO.ROUND_TABLE;
  const styleColors = PANEL_STYLE_COLORS[config.style as DiscussionStyle] || PANEL_STYLE_COLORS.ROUND_TABLE;
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const deleteWithMembersMutation = useDeleteGroupWithMembers();

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
          <p className="mt-1 text-xs text-muted-foreground line-clamp-3">
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
          <StyleFlowPreview flow={styleInfo.flow} />
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
                <p className="text-xs font-medium text-foreground truncate">
                  {member.displayName}
                </p>
                {member.agentId && (
                  <p className="text-[10px] text-muted-foreground font-mono truncate">
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
                    <Users className="h-2 w-2 me-0.5" /> Group
                  </Badge>
                )}
                {config.moderatorAgentId === member.agentId && (
                  <Badge variant="default" className="text-[9px] px-1 py-0">
                    ⭐ Mod
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
            <InfoRow label={t("groups.protocolOnFailure", "On Failure")} value={config.protocol.onAgentFailure} />
            <InfoRow label={t("groups.protocolMaxRetries", "Max Retries")} value={String(config.protocol.maxRetries)} />
            <InfoRow label={t("groups.protocolUnavailable", "Unavailable")} value={config.protocol.onMemberUnavailable} />
            <InfoRow label={t("groups.protocolMaxRounds", "Max Rounds")} value={String(config.maxRounds)} />
          </div>
        </div>
      )}

      {/* Delete group + all agents */}
      {groupId && groupVersion != null && (
        <div className="mt-auto pt-4 border-t border-border">
          {!showDeleteConfirm ? (
            <Button
              variant="outline"
              size="sm"
              className="w-full text-destructive border-destructive/30 hover:bg-destructive/10 hover:text-destructive"
              onClick={() => setShowDeleteConfirm(true)}
            >
              <Trash2 className="h-3.5 w-3.5 me-1.5" />
              {t("groups.deleteGroupAndAgents", "Delete Group + All Agents")}
            </Button>
          ) : (
            <div className="space-y-2">
              <div className="flex items-start gap-2 rounded-lg border border-amber-500/30 bg-amber-500/5 p-2">
                <AlertTriangle className="h-3.5 w-3.5 text-amber-500 shrink-0 mt-0.5" />
                <p className="text-[10px] text-muted-foreground leading-relaxed">
                  {t("groups.deleteWithMembersWarning", "This will soft-delete the group and all {{count}} member agents. They can be recovered.", { count: config.members.length })}
                </p>
              </div>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  className="flex-1"
                  onClick={() => setShowDeleteConfirm(false)}
                >
                  {t("common.cancel", "Cancel")}
                </Button>
                <Button
                  variant="destructive"
                  size="sm"
                  className="flex-1"
                  onClick={handleDeleteWithMembers}
                  disabled={deleteWithMembersMutation.isPending}
                >
                  {deleteWithMembersMutation.isPending ? (
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

function StyleFlowPreview({ flow }: { flow: string }) {
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
