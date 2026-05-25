import { useState, useEffect, useCallback } from "react";
import { useParams, useSearchParams, useNavigate } from "react-router-dom";
import { parseChannelResourceUri } from "@/lib/api/channels";
import { useTranslation } from "react-i18next";
import {
  Cable, Save, Trash2, ArrowLeft, Plus, X, Copy, Check,
  Bot, Users, ChevronDown, ChevronUp, Hash,
  ExternalLink, Star,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { SecretKeyPicker } from "@/components/shared/secret-key-picker";
import { useChannel, useUpdateChannel, useDeleteChannel } from "@/hooks/use-channels";
import {
  CHANNEL_TYPES,
  type ChannelIntegrationConfiguration, type ChannelTarget,
} from "@/lib/api/channels";

// NOTE: ObserveConfigForm removed — backend defers observeMode implementation
// (RestChannelIntegrationStore rejects observeMode=true). Re-add when backend
// ships observe mode support. See git history for the full component.

/* ─── Target Card ─────────────────────────────────────────────── */

function TargetCard({
  target, index, isDefault, onUpdate, onRemove, onSetDefault,
}: {
  target: ChannelTarget; index: number; isDefault: boolean;
  onUpdate: (t: ChannelTarget) => void; onRemove: () => void; onSetDefault: () => void;
}) {
  const { t } = useTranslation();
  const [triggerInput, setTriggerInput] = useState("");
  const [expanded, setExpanded] = useState(true);

  const addTrigger = () => {
    const tr = triggerInput.trim().toLowerCase();
    if (tr && !target.triggers.includes(tr) && tr !== "help") {
      onUpdate({ ...target, triggers: [...target.triggers, tr] });
      setTriggerInput("");
    }
  };

  return (
    <div data-testid={`target-card-${index}`} className="rounded-xl border border-border/50 bg-card overflow-hidden">
      <div className="flex items-center gap-3 px-4 py-3 bg-muted/30 cursor-pointer" onClick={() => setExpanded(!expanded)}>
        <div className={`flex h-8 w-8 shrink-0 items-center justify-center rounded-lg ${target.type === "GROUP" ? "bg-violet-100 text-violet-600 dark:bg-violet-900/30 dark:text-violet-400" : "bg-primary/10 text-primary"}`}>
          {target.type === "GROUP" ? <Users className="h-4 w-4" /> : <Bot className="h-4 w-4" />}
        </div>
        <div className="flex-1 min-w-0">
          <span className="font-medium text-sm truncate">{target.name || `Target ${index + 1}`}</span>
        </div>
        <div className="flex items-center gap-1.5">
          {isDefault && <Badge className="text-xs bg-primary/10 text-primary border-primary/20">{t("channelDetail.isDefault", "default")}</Badge>}

          {target.triggers.length > 0 && <Badge variant="secondary" className="text-xs">{target.triggers.length} {t("channelDetail.triggers", "triggers")}</Badge>}
          {expanded ? <ChevronUp className="h-4 w-4 text-muted-foreground" /> : <ChevronDown className="h-4 w-4 text-muted-foreground" />}
        </div>
      </div>

      {expanded && (
        <div className="p-4 space-y-4 border-t border-border/30">
          <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1">
              <label className="text-xs font-medium">{t("channelDetail.targetName", "Name")}</label>
              <Input data-testid={`target-name-${index}`} className="h-8 text-sm" value={target.name} onChange={(e) => onUpdate({ ...target, name: e.target.value })} placeholder="e.g. support" />
            </div>
            <div className="space-y-1">
              <label className="text-xs font-medium">{t("channelDetail.targetType", "Type")}</label>
              <select className="flex h-8 w-full rounded-lg border border-border bg-background px-2 text-sm" value={target.type} onChange={(e) => onUpdate({ ...target, type: e.target.value as "AGENT" | "GROUP" })}>
                <option value="AGENT">Agent</option>
                <option value="GROUP">Group</option>
              </select>
            </div>
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium">{target.type === "GROUP" ? t("channelDetail.groupId", "Group ID") : t("channelDetail.agentId", "Agent ID")}</label>
            <Input data-testid={`target-id-${index}`} className="h-8 text-sm" value={target.targetId} onChange={(e) => onUpdate({ ...target, targetId: e.target.value })} />
          </div>

          <div className="space-y-1">
            <label className="text-xs font-medium">{t("channelDetail.triggerKeywords", "Trigger Keywords")}</label>
            <p className="text-xs text-muted-foreground">{t("channelDetail.triggerHint", 'Users type "keyword: message" to route to this target')}</p>
            <div className="flex gap-1.5 flex-wrap">
              {target.triggers.map((tr) => (
                <Badge key={tr} variant="secondary" className="text-xs gap-1 font-mono">{tr}:<button aria-label={`Remove ${tr}`} onClick={() => onUpdate({ ...target, triggers: target.triggers.filter((x) => x !== tr) })} className="hover:text-destructive"><X className="h-3 w-3" /></button></Badge>
              ))}
            </div>
            <div className="flex gap-1">
              <Input className="h-7 text-xs" value={triggerInput} onChange={(e) => setTriggerInput(e.target.value)} placeholder={t("channelDetail.addTrigger", "Add trigger...")} onKeyDown={(e) => e.key === "Enter" && (e.preventDefault(), addTrigger())} />
              <Button size="sm" variant="outline" className="h-7 text-xs" onClick={addTrigger}>{t("common.add", "Add")}</Button>
            </div>
          </div>

          {/* Observe Mode — hidden until backend implements support */}

          <div className="flex items-center gap-2 pt-2 border-t border-border/30">
            {!isDefault && (
              <Button variant="outline" size="sm" className="text-xs gap-1" onClick={onSetDefault}>
                <Star className="h-3 w-3" />{t("channelDetail.setDefault", "Set as Default")}
              </Button>
            )}
            <Button variant="ghost" size="sm" className="text-xs text-destructive hover:text-destructive ms-auto gap-1" onClick={onRemove}>
              <Trash2 className="h-3 w-3" />{t("channelDetail.removeTarget", "Remove")}
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

/* ─── Main Page ───────────────────────────────────────────────── */

export function ChannelDetailPage() {
  const { t } = useTranslation();
  const { id } = useParams<{ id: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const navigate = useNavigate();
  const parsedVersion = Number(searchParams.get("version") ?? "1");
  const version = Number.isFinite(parsedVersion) ? parsedVersion : 1;

  const { data: config, isLoading } = useChannel(id!, version);
  const updateMutation = useUpdateChannel();
  const deleteMutation = useDeleteChannel();

  const [draft, setDraft] = useState<ChannelIntegrationConfiguration | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [rawOpen, setRawOpen] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => { if (config) setDraft({ ...config }); }, [config]);

  const webhookUrl = `${window.location.origin}/integrations/slack/events`;

  const handleSave = async () => {
    if (!draft || !id) return;
    const result = await updateMutation.mutateAsync({ id, version, config: draft });
    // Update URL to new version so subsequent saves don't conflict
    const location = (result as { location?: string })?.location;
    if (location) {
      const { version: newVersion } = parseChannelResourceUri(location);
      setSearchParams({ version: String(newVersion) }, { replace: true });
    }
  };
  const handleDelete = async () => { if (!id) return; await deleteMutation.mutateAsync({ id, version }); navigate("/manage/channels"); };

  const updateTarget = useCallback((index: number, target: ChannelTarget) => {
    setDraft((prev) => { if (!prev) return prev; const targets = [...prev.targets]; targets[index] = target; return { ...prev, targets }; });
  }, []);

  const removeTarget = useCallback((index: number) => {
    setDraft((prev) => {
      if (!prev) return prev;
      const removed = prev.targets[index];
      const remaining = prev.targets.filter((_, i) => i !== index);
      // If removing the default target, fall back to first remaining target
      let { defaultTargetName } = prev;
      if (removed && removed.name === defaultTargetName) {
        defaultTargetName = remaining[0]?.name ?? "";
      }
      return { ...prev, targets: remaining, defaultTargetName };
    });
  }, []);

  const setDefaultTarget = useCallback((name: string) => {
    setDraft((prev) => prev ? { ...prev, defaultTargetName: name } : prev);
  }, []);

  const addTarget = useCallback(() => {
    setDraft((prev) => {
      if (!prev) return prev;
      const newTarget: ChannelTarget = {
        name: "",
        type: "AGENT",
        targetId: "",
        triggers: [],
        observeMode: false,
        observeConfig: null,
      };
      return { ...prev, targets: [...prev.targets, newTarget] };
    });
  }, []);

  const copyWebhookUrl = async () => {
    await navigator.clipboard.writeText(webhookUrl);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  if (isLoading || !draft) {
    return (
      <div className="p-6 space-y-6">
        <div className="h-8 w-48 bg-muted rounded animate-pulse" />
        <div className="h-64 bg-muted rounded-xl animate-pulse" />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-6 p-6 max-w-4xl">
      {/* Header */}
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="icon" onClick={() => navigate("/manage/channels")}><ArrowLeft className="h-4 w-4" /></Button>
        <div className="flex-1 min-w-0">
          <h1 className="text-xl font-bold tracking-tight truncate">{draft.name || "Untitled Channel"}</h1>
          <p className="text-xs text-muted-foreground">v{version} · {draft.channelType}</p>
        </div>
        <Button variant="outline" size="sm" className="text-destructive" onClick={() => setDeleteOpen(true)} data-testid="delete-channel-btn">
          <Trash2 className="h-4 w-4 me-1" /> {t("common.delete")}
        </Button>
        <Button size="sm" onClick={handleSave} disabled={updateMutation.isPending} data-testid="save-channel-btn">
          <Save className="h-4 w-4 me-1" /> {updateMutation.isPending ? t("common.saving") : t("common.save")}
        </Button>
      </div>

      {/* General */}
      <section className="rounded-xl border border-border/50 p-5 space-y-4">
        <h2 className="text-sm font-semibold flex items-center gap-2"><Cable className="h-4 w-4 text-primary" />{t("channelDetail.general", "General")}</h2>
        <div className="grid grid-cols-2 gap-4">
          <div className="space-y-1">
            <label className="text-xs font-medium">{t("channelDetail.name", "Name")}</label>
            <Input data-testid="channel-name-input" value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
          </div>
          <div className="space-y-1">
            <label className="text-xs font-medium">{t("channelDetail.type", "Channel Type")}</label>
            <select data-testid="channel-type-select" className="flex h-9 w-full rounded-lg border border-border bg-background px-3 text-sm" value={draft.channelType} onChange={(e) => setDraft({ ...draft, channelType: e.target.value })}>
              {CHANNEL_TYPES.map((ct) => (<option key={ct} value={ct}>{ct.charAt(0).toUpperCase() + ct.slice(1)}</option>))}
            </select>
          </div>
        </div>
      </section>

      {/* Platform Config */}
      <section className="rounded-xl border border-border/50 p-5 space-y-4">
        <h2 className="text-sm font-semibold flex items-center gap-2"><Hash className="h-4 w-4 text-primary" />{t("channelDetail.platformConfig", "Platform Configuration")}</h2>
        <div className="space-y-3">
          <div className="space-y-1">
            <label className="text-xs font-medium">{t("channelDetail.channelId", "Slack Channel ID")}</label>
            <Input data-testid="channel-id-input" value={draft.platformConfig.channelId ?? ""} onChange={(e) => setDraft({ ...draft, platformConfig: { ...draft.platformConfig, channelId: e.target.value } })} placeholder="C0123ABCDEF" />
          </div>
          <div className="space-y-1">
            <label className="text-xs font-medium">{t("channelDetail.botToken", "Bot Token")}</label>
            <SecretKeyPicker value={draft.platformConfig.botToken ?? ""} onChange={(v) => setDraft({ ...draft, platformConfig: { ...draft.platformConfig, botToken: v } })} placeholder="xoxb-… or ${vault:slack-bot-token}" />
          </div>
          <div className="space-y-1">
            <label className="text-xs font-medium">{t("channelDetail.signingSecret", "Signing Secret")}</label>
            <SecretKeyPicker value={draft.platformConfig.signingSecret ?? ""} onChange={(v) => setDraft({ ...draft, platformConfig: { ...draft.platformConfig, signingSecret: v } })} placeholder="${vault:slack-signing-secret}" />
          </div>
        </div>
      </section>

      {/* Webhook URL */}
      <section className="rounded-xl border border-primary/20 bg-primary/5 p-5 space-y-3">
        <h2 className="text-sm font-semibold flex items-center gap-2"><ExternalLink className="h-4 w-4 text-primary" />{t("channelDetail.webhookUrl", "Webhook URL")}</h2>
        <p className="text-xs text-muted-foreground">{t("channelDetail.webhookHint", "Paste this URL into your Slack App's Event Subscriptions → Request URL field.")}</p>
        <div className="flex items-center gap-2">
          <code className="flex-1 rounded-lg bg-background border border-border px-3 py-2 text-xs font-mono truncate">{webhookUrl}</code>
          <Button size="sm" variant="outline" className="gap-1 shrink-0" onClick={copyWebhookUrl}>
            {copied ? <Check className="h-3.5 w-3.5 text-green-500" /> : <Copy className="h-3.5 w-3.5" />}
            {copied ? t("common.copied") : t("common.copy")}
          </Button>
        </div>
        <div className="text-xs text-muted-foreground space-y-1">
          <p className="font-medium">{t("channelDetail.requiredScopes", "Required Bot Token Scopes:")}</p>
          <div className="flex flex-wrap gap-1">
            {["app_mentions:read", "chat:write", "im:history", "channels:history", "groups:history"].map((s) => (
              <Badge key={s} variant="outline" className="text-xs font-mono">{s}</Badge>
            ))}
          </div>
          <p className="font-medium mt-2">{t("channelDetail.requiredEvents", "Required Event Subscriptions:")}</p>
          <div className="flex flex-wrap gap-1">
            {["app_mention", "message.channels", "message.groups", "message.im"].map((e) => (
              <Badge key={e} variant="outline" className="text-xs font-mono">{e}</Badge>
            ))}
          </div>
        </div>
      </section>

      {/* Targets */}
      <section className="space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-semibold flex items-center gap-2"><Bot className="h-4 w-4 text-primary" />{t("channelDetail.targets", "Message Routing Targets")}</h2>
          <Button size="sm" variant="outline" className="gap-1" onClick={addTarget} data-testid="add-target-btn">
            <Plus className="h-3.5 w-3.5" /> {t("channelDetail.addTarget", "Add Target")}
          </Button>
        </div>
        <div className="space-y-3">
          {draft.targets.map((target, i) => (
            <TargetCard key={i} target={target} index={i} isDefault={draft.defaultTargetName === target.name}
              onUpdate={(t) => updateTarget(i, t)} onRemove={() => removeTarget(i)} onSetDefault={() => { if (target.name) setDefaultTarget(target.name); }} />
          ))}
        </div>
      </section>

      {/* Raw JSON */}
      <section className="rounded-xl border border-border/50 overflow-hidden">
        <button className="w-full flex items-center justify-between px-5 py-3 text-sm font-medium hover:bg-muted/30 transition-colors" onClick={() => setRawOpen(!rawOpen)}>
          {t("channelDetail.rawConfig", "Raw Configuration")}
          {rawOpen ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
        </button>
        {rawOpen && (
          <div className="border-t border-border/30 p-4">
            <pre className="text-xs font-mono bg-muted/30 p-4 rounded-lg overflow-auto max-h-96">{JSON.stringify(draft, null, 2)}</pre>
          </div>
        )}
      </section>

      {/* Delete confirm */}
      <AlertDialog
        open={deleteOpen}
        onOpenChange={setDeleteOpen}
        title={t("channels.confirmDelete", "Delete channel?")}
        description={t("channels.confirmDeleteDesc", "This will permanently remove this channel integration.")}
        onConfirm={handleDelete}
        confirmLabel={t("common.delete")}
        cancelLabel={t("common.cancel")}
        isPending={deleteMutation.isPending}
      />
    </div>
  );
}
