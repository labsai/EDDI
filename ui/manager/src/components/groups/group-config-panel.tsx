import { useTranslation } from "react-i18next";
import { Users, Settings2, ArrowRight } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { cn, hashColor, getInitials } from "@/lib/utils";
import type { AgentGroupConfiguration } from "@/lib/api/groups";
import { STYLE_INFO } from "@/lib/api/groups";

interface GroupConfigPanelProps {
  config: AgentGroupConfiguration;
  className?: string;
}


export function GroupConfigPanel({ config, className }: GroupConfigPanelProps) {
  const { t } = useTranslation();
  const styleInfo = STYLE_INFO[config.style] || STYLE_INFO.ROUND_TABLE;

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

      {/* Discussion style */}
      <div>
        <h4 className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground mb-1.5">
          {t("groups.discussionStyle", "Discussion Style")}
        </h4>
        <div className="rounded-lg border border-border bg-secondary/30 p-2.5">
          <div className="flex items-center gap-2 mb-1">
            <span className="text-base">{styleInfo.icon}</span>
            <span className="text-sm font-semibold">{styleInfo.label}</span>
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
            <InfoRow label="Timeout" value={`${config.protocol.agentTimeoutSeconds}s`} />
            <InfoRow label="On Failure" value={config.protocol.onAgentFailure} />
            <InfoRow label="Max Retries" value={String(config.protocol.maxRetries)} />
            <InfoRow label="Unavailable" value={config.protocol.onMemberUnavailable} />
            <InfoRow label="Max Rounds" value={String(config.maxRounds)} />
          </div>
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
