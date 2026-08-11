import { useState, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Cable, Plus, Search, ExternalLink } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { ViewToggle } from "@/components/shared/view-toggle";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import {
  getStoredViewMode,
  setStoredViewMode,
  type ViewMode,
} from "@/components/shared/view-mode";
import { ChannelCard } from "@/components/channels/channel-card";
import { CreateChannelDialog } from "@/components/channels/create-channel-dialog";
import {
  useEnrichedChannelDescriptors,
  useDeleteChannel,
  useDuplicateChannel,
} from "@/hooks/use-channels";
import { useNavigate } from "react-router-dom";

export function ChannelsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [search, setSearch] = useState("");
  const [view, setView] = useState<ViewMode>(() => getStoredViewMode("channels"));
  const [createOpen, setCreateOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<{
    id: string;
    version: number;
  } | null>(null);

  const {
    data: channels,
    isLoading,
    isError,
    refetch,
  } = useEnrichedChannelDescriptors();
  const deleteMutation = useDeleteChannel();
  const duplicateMutation = useDuplicateChannel();

  const filtered = useMemo(() => {
    if (!channels) return [];
    if (!search.trim()) return channels;
    const q = search.toLowerCase();
    return channels.filter(
      (ch) =>
        ch.name?.toLowerCase().includes(q) ||
        ch.channelId?.toLowerCase().includes(q) ||
        ch.channelType?.toLowerCase().includes(q),
    );
  }, [channels, search]);

  const handleDelete = (id: string, version: number) => {
    setDeleteTarget({ id, version });
  };

  const confirmDelete = async () => {
    if (!deleteTarget) return;
    await deleteMutation.mutateAsync({
      id: deleteTarget.id,
      version: deleteTarget.version,
    });
    setDeleteTarget(null);
  };

  const handleDuplicate = (id: string, version: number) => {
    duplicateMutation.mutate({ id, version });
  };

  const handleViewChange = (mode: ViewMode) => {
    setView(mode);
    setStoredViewMode("channels", mode);
  };

  return (
    <div className="flex flex-col gap-6">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight flex items-center gap-2">
            <Cable className="h-6 w-6 text-primary" />
            {t("pages.channels.title", "Channels")}
          </h1>
          <p className="text-sm text-muted-foreground mt-1">
            {t(
              "pages.channels.subtitle",
              "Connect agents and groups to messaging platforms like Slack",
            )}
          </p>
        </div>
        <Button
          onClick={() => setCreateOpen(true)}
          data-testid="create-channel-btn"
          className="gap-2"
        >
          <Plus className="h-4 w-4" />
          {t("channels.create", "Create Channel")}
        </Button>
      </div>

      {/* Slack setup banner */}
      <div className="rounded-xl border border-primary/20 bg-primary/5 p-4">
        <div className="flex items-start gap-3">
          <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-[#4A154B] text-white">
            <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5">
              <path d="M5.042 15.165a2.528 2.528 0 0 1-2.52 2.523A2.528 2.528 0 0 1 0 15.165a2.527 2.527 0 0 1 2.522-2.52h2.52v2.52zm1.271 0a2.527 2.527 0 0 1 2.521-2.52 2.527 2.527 0 0 1 2.521 2.52v6.313A2.528 2.528 0 0 1 8.834 24a2.528 2.528 0 0 1-2.521-2.522v-6.313zM8.834 5.042a2.528 2.528 0 0 1-2.521-2.52A2.528 2.528 0 0 1 8.834 0a2.528 2.528 0 0 1 2.521 2.522v2.52H8.834zm0 1.271a2.528 2.528 0 0 1 2.521 2.521 2.528 2.528 0 0 1-2.521 2.521H2.522A2.528 2.528 0 0 1 0 8.834a2.528 2.528 0 0 1 2.522-2.521h6.312zM18.956 8.834a2.528 2.528 0 0 1 2.522-2.521A2.528 2.528 0 0 1 24 8.834a2.528 2.528 0 0 1-2.522 2.521h-2.522V8.834zm-1.27 0a2.528 2.528 0 0 1-2.523 2.521 2.527 2.527 0 0 1-2.52-2.521V2.522A2.527 2.527 0 0 1 15.163 0a2.528 2.528 0 0 1 2.523 2.522v6.312zM15.163 18.956a2.528 2.528 0 0 1 2.523 2.522A2.528 2.528 0 0 1 15.163 24a2.527 2.527 0 0 1-2.52-2.522v-2.522h2.52zm0-1.27a2.527 2.527 0 0 1-2.52-2.523 2.527 2.527 0 0 1 2.52-2.52h6.315A2.528 2.528 0 0 1 24 15.163a2.528 2.528 0 0 1-2.522 2.523h-6.315z" />
            </svg>
          </div>
          <div className="flex-1 min-w-0">
            <h3 className="font-semibold text-sm">
              {t("channels.slackSetupGuide", "Slack Setup Guide")}
            </h3>
            <p className="text-xs text-muted-foreground mt-0.5">
              {t(
                "channels.slackSetupSummary",
                "Create a Slack App, configure Bot Token Scopes (app_mentions:read, chat:write, im:history, channels:history, groups:history), then paste credentials here.",
              )}
            </p>
            <a
              href="https://api.slack.com/apps"
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-xs text-primary hover:underline mt-1"
            >
              {t("channels.openSlackApps", "Open Slack App Dashboard")}
              <ExternalLink className="h-3 w-3" />
            </a>
          </div>
        </div>
      </div>

      {/* Search & filters */}
      <div className="flex items-center gap-3">
        <div className="relative flex-1 max-w-sm">
          <Search className="absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            data-testid="channel-search"
            className="ps-9"
            placeholder={t("channels.searchPlaceholder", "Search channels...")}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        {channels && (
          <Badge variant="secondary" className="text-xs">
            {filtered.length}{" "}
            {filtered.length === 1
              ? t("channels.channel", "channel")
              : t("channels.channelsPlural", "channels")}
          </Badge>
        )}
        <div className="ms-auto">
          <ViewToggle view={view} onChange={handleViewChange} />
        </div>
      </div>

      {/* Channel grid */}
      {isLoading ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="h-40 rounded-xl border border-border/50 bg-card animate-pulse" />
          ))}
        </div>
      ) : isError ? (
        <ErrorState
          message={t("common.error", "Something went wrong")}
          onRetry={() => refetch()}
          retryLabel={t("common.retry", "Retry")}
        />
      ) : filtered.length === 0 ? (
        <EmptyState
          icon={Cable}
          title={search ? t("common.noResults") : t("channels.empty", "No channels yet")}
          description={
            !search
              ? t(
                  "channels.emptyDesc",
                  "Create a channel integration to connect agents to Slack.",
                )
              : undefined
          }
          actionLabel={!search ? t("channels.create", "Create Channel") : undefined}
          onAction={!search ? () => setCreateOpen(true) : undefined}
        />
      ) : view === "card" ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {filtered.map((ch) => (
            <ChannelCard key={ch.id} channel={ch} onDelete={handleDelete} onDuplicate={handleDuplicate} />
          ))}
        </div>
      ) : (
        <div className="rounded-xl border border-border/50 overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b bg-muted/50">
                <th className="text-start px-4 py-3 font-medium">{t("channels.name", "Name")}</th>
                <th className="text-start px-4 py-3 font-medium">{t("channels.type", "Type")}</th>
                <th className="text-start px-4 py-3 font-medium">{t("channels.channelIdCol", "Channel ID")}</th>
                <th className="text-start px-4 py-3 font-medium">{t("channels.targetsCol", "Targets")}</th>
                <th className="text-end px-4 py-3 font-medium">{t("channels.version", "Version")}</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((ch) => (
                <tr key={ch.id} className="border-b border-border/30 hover:bg-muted/30 cursor-pointer transition-colors"
                  onClick={() => navigate(`/manage/channels/${ch.id}?version=${ch.version}`)} data-testid={`channel-row-${ch.id}`}>
                  <td className="px-4 py-3 font-medium">{ch.name}</td>
                  <td className="px-4 py-3"><Badge variant="outline" className="text-xs">{ch.channelType}</Badge></td>
                  <td className="px-4 py-3 font-mono text-xs text-muted-foreground">{ch.channelId ?? "—"}</td>
                  <td className="px-4 py-3">{ch.targetCount}</td>
                  <td className="px-4 py-3 text-end">v{ch.version}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <CreateChannelDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(id) => navigate(`/manage/channels/${id}?version=1`)}
      />

      <AlertDialog
        open={!!deleteTarget}
        onOpenChange={() => setDeleteTarget(null)}
        title={t("channels.confirmDelete", "Delete channel?")}
        description={t("channels.confirmDeleteDesc", "This will permanently remove this channel integration.")}
        onConfirm={confirmDelete}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        isPending={deleteMutation.isPending}
      />
    </div>
  );
}
