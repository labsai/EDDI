import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { Cable, Copy, Trash2, Users, Bot, Hash } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import type { EnrichedChannelDescriptor } from "@/lib/api/channels";

function SlackIcon({ className }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M5.042 15.165a2.528 2.528 0 0 1-2.52 2.523A2.528 2.528 0 0 1 0 15.165a2.527 2.527 0 0 1 2.522-2.52h2.52v2.52zm1.271 0a2.527 2.527 0 0 1 2.521-2.52 2.527 2.527 0 0 1 2.521 2.52v6.313A2.528 2.528 0 0 1 8.834 24a2.528 2.528 0 0 1-2.521-2.522v-6.313zM8.834 5.042a2.528 2.528 0 0 1-2.521-2.52A2.528 2.528 0 0 1 8.834 0a2.528 2.528 0 0 1 2.521 2.522v2.52H8.834zm0 1.271a2.528 2.528 0 0 1 2.521 2.521 2.528 2.528 0 0 1-2.521 2.521H2.522A2.528 2.528 0 0 1 0 8.834a2.528 2.528 0 0 1 2.522-2.521h6.312zM18.956 8.834a2.528 2.528 0 0 1 2.522-2.521A2.528 2.528 0 0 1 24 8.834a2.528 2.528 0 0 1-2.522 2.521h-2.522V8.834zm-1.27 0a2.528 2.528 0 0 1-2.523 2.521 2.527 2.527 0 0 1-2.52-2.521V2.522A2.527 2.527 0 0 1 15.163 0a2.528 2.528 0 0 1 2.523 2.522v6.312zM15.163 18.956a2.528 2.528 0 0 1 2.523 2.522A2.528 2.528 0 0 1 15.163 24a2.527 2.527 0 0 1-2.52-2.522v-2.522h2.52zm0-1.27a2.527 2.527 0 0 1-2.52-2.523 2.527 2.527 0 0 1 2.52-2.52h6.315A2.528 2.528 0 0 1 24 15.163a2.528 2.528 0 0 1-2.522 2.523h-6.315z" />
    </svg>
  );
}

const TYPE_ICONS: Record<string, React.ReactNode> = {
  slack: <SlackIcon className="h-4 w-4" />,
};

const TYPE_COLORS: Record<string, string> = {
  slack: "bg-[#4A154B]/10 text-[#4A154B] dark:bg-[#E01E5A]/10 dark:text-[#E01E5A]",
};

interface ChannelCardProps {
  channel: EnrichedChannelDescriptor;
  onDelete: (id: string, version: number) => void;
  onDuplicate: (id: string, version: number) => void;
}

export function ChannelCard({ channel, onDelete, onDuplicate }: ChannelCardProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const typeLabel = channel.channelType
    ? channel.channelType.charAt(0).toUpperCase() + channel.channelType.slice(1)
    : "Unknown";

  return (
    <div
      data-testid={`channel-card-${channel.id}`}
      className="group relative flex flex-col gap-3 rounded-xl border border-border/50 bg-card p-5 transition-all hover:border-primary/30 hover:shadow-lg hover:shadow-primary/5 cursor-pointer"
      onClick={() => navigate(`/manage/channels/${channel.id}?version=${channel.version}`)}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          navigate(`/manage/channels/${channel.id}?version=${channel.version}`);
        }
      }}
    >
      {/* Header */}
      <div className="flex items-start justify-between gap-2">
        <div className="flex items-center gap-3 min-w-0">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
            {TYPE_ICONS[channel.channelType] ?? <Cable className="h-5 w-5" />}
          </div>
          <div className="min-w-0">
            <h3 className="font-semibold text-foreground truncate leading-tight">
              {channel.name || t("channels.unnamed", "Unnamed Channel")}
            </h3>
            {channel.channelId && (
              <div className="flex items-center gap-1 text-xs text-muted-foreground mt-0.5">
                <Hash className="h-3 w-3" />
                <span className="font-mono truncate">{channel.channelId}</span>
              </div>
            )}
          </div>
        </div>
        <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
          <Button variant="ghost" size="icon" className="h-7 w-7"
            onClick={(e) => { e.stopPropagation(); onDuplicate(channel.id, channel.version); }}
            title={t("common.duplicate")}>
            <Copy className="h-3.5 w-3.5" />
          </Button>
          <Button variant="ghost" size="icon" className="h-7 w-7 text-destructive hover:text-destructive"
            onClick={(e) => { e.stopPropagation(); onDelete(channel.id, channel.version); }}
            title={t("common.delete")}>
            <Trash2 className="h-3.5 w-3.5" />
          </Button>
        </div>
      </div>

      {/* Badges */}
      <div className="flex flex-wrap items-center gap-1.5">
        <Badge variant="outline" className={`text-xs ${TYPE_COLORS[channel.channelType] ?? ""}`}>
          {TYPE_ICONS[channel.channelType] && (
            <span className="me-1 inline-flex">{TYPE_ICONS[channel.channelType]}</span>
          )}
          {typeLabel}
        </Badge>
        <Badge variant="secondary" className="text-xs gap-1">
          <Bot className="h-3 w-3" />
          {channel.targetCount}{" "}
          {channel.targetCount === 1 ? t("channels.target", "target") : t("channels.targets", "targets")}
        </Badge>
      </div>

      {/* Footer */}
      <div className="flex items-center justify-between text-xs text-muted-foreground pt-1 border-t border-border/30">
        <span className="flex items-center gap-1">
          <Users className="h-3 w-3" />
          v{channel.version}
        </span>
        <span>{new Date(channel.lastModifiedOn).toLocaleDateString()}</span>
      </div>
    </div>
  );
}
