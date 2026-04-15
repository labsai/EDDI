import { useState, useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import {
  Zap,
  Plus,
  Trash2,
  Edit3,
  RefreshCw,
  X,
  Server,
  Bot,
  Search,
  ChevronDown,
  ChevronRight,
  AlertCircle,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { ENVIRONMENTS } from "@/lib/constants";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";
import {
  useTriggers,
  useCreateTrigger,
  useUpdateTrigger,
  useDeleteTrigger,
} from "@/hooks/use-triggers";
import { AgentPicker } from "@/components/shared/agent-picker";
import type { AgentTriggerConfiguration, AgentDeployment } from "@/lib/api/triggers";

import { useAgentDescriptors } from "@/hooks/use-agents";

export function TriggersPage() {
  const { t } = useTranslation();
  const { data: triggers, isLoading, isError, refetch } = useTriggers();
  const { data: rawAgents } = useAgentDescriptors(1000);
  const createTrigger = useCreateTrigger();
  const updateTrigger = useUpdateTrigger();
  const deleteTrigger = useDeleteTrigger();

  const [showCreate, setShowCreate] = useState(false);
  const [editTrigger, setEditTrigger] = useState<AgentTriggerConfiguration | null>(null);
  const [deleteIntent, setDeleteIntent] = useState<string | null>(null);
  const [search, setSearch] = useState("");

  const agentNameMap = useMemo(() => {
    const map = new Map<string, string>();
    if (rawAgents) {
      rawAgents.forEach((a) => {
        if (a.name) map.set(a.id, a.name.toLowerCase());
      });
    }
    return map;
  }, [rawAgents]);

  const filteredTriggers = useMemo(() => {
    if (!triggers) return [];
    if (!search.trim()) return triggers;
    const q = search.trim().toLowerCase();
    return triggers.filter((tr) => {
      if (tr.intent.toLowerCase().includes(q)) return true;
      return tr.agentDeployments.some((d) => {
        if (d.agentId.toLowerCase().includes(q)) return true;
        const name = agentNameMap.get(d.agentId);
        if (name && name.includes(q)) return true;
        return false;
      });
    });
  }, [triggers, search, agentNameMap]);

  return (
    <div className="space-y-6" data-testid="triggers-page">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="flex items-center gap-3 text-2xl font-bold text-foreground">
            <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-amber-500/10">
              <Zap className="h-5 w-5 text-amber-500" />
            </div>
            {t("triggers.title", "Agent Triggers")}
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {t("triggers.subtitle", "Map intents to agent deployments for automatic routing")}
          </p>
        </div>
        <Button onClick={() => setShowCreate(true)} className="gap-2" data-testid="create-trigger-btn">
          <Plus className="h-4 w-4" />
          {t("triggers.create", "New Trigger")}
        </Button>
      </div>

      {/* Search */}
      {triggers && triggers.length > 0 && (
        <div className="relative">
          <Search className="absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t("triggers.searchPlaceholder", "Filter by intent or agent ID...")}
            className="h-10 w-full rounded-lg border border-input bg-background ps-9 pe-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid="trigger-search"
          />
        </div>
      )}

      {/* Loading */}
      {isLoading && (
        <div className="flex items-center justify-center py-16">
          <RefreshCw className="h-8 w-8 animate-spin text-primary" />
        </div>
      )}

      {/* Error */}
      {isError && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-destructive/30 bg-destructive/5 py-12">
          <AlertCircle className="h-10 w-10 text-destructive" />
          <p className="mt-3 text-sm text-destructive">{t("common.error")}</p>
          <button onClick={() => refetch()} className="mt-3 text-xs text-primary hover:underline">{t("common.retry")}</button>
        </div>
      )}

      {/* Trigger list */}
      {!isLoading && !isError && triggers && (
        <div className="space-y-3" data-testid="trigger-list">
          {filteredTriggers.length === 0 ? (
            <div className="flex flex-col items-center justify-center rounded-xl border border-dashed py-20 text-muted-foreground">
              <Zap className="h-12 w-12 opacity-30" />
              <p className="mt-4 text-sm">
                {search.trim()
                  ? t("common.noResults")
                  : t("triggers.empty", "No triggers configured yet")}
              </p>
            </div>
          ) : (
            filteredTriggers.map((trigger) => (
              <TriggerCard
                key={trigger.intent}
                trigger={trigger}
                onEdit={() => setEditTrigger(trigger)}
                onDelete={() => setDeleteIntent(trigger.intent)}
              />
            ))
          )}
        </div>
      )}

      {/* Create / Edit Dialog */}
      {(showCreate || editTrigger) && (
        <TriggerDialog
          initial={editTrigger}
          onClose={() => { setShowCreate(false); setEditTrigger(null); }}
          onSave={(config) => {
            if (editTrigger) {
              updateTrigger.mutate(
                { intent: editTrigger.intent, config },
                {
                  onSuccess: () => {
                    toast.success(t("triggers.updated", "Trigger updated"));
                    setEditTrigger(null);
                  },
                  onError: (err) => toast.error(err.message),
                },
              );
            } else {
              createTrigger.mutate(config, {
                onSuccess: () => {
                  toast.success(t("triggers.created", "Trigger created"));
                  setShowCreate(false);
                },
                onError: (err) => toast.error(err.message),
              });
            }
          }}
          isPending={createTrigger.isPending || updateTrigger.isPending}
        />
      )}

      {/* Delete Confirm */}
      <AlertDialog
        open={!!deleteIntent}
        onOpenChange={() => setDeleteIntent(null)}
        title={t("triggers.deleteTitle", "Delete Trigger")}
        description={t("triggers.deleteDesc", 'Permanently delete the trigger for intent "{{intent}}"?', { intent: deleteIntent ?? "" })}
        confirmLabel={t("common.delete")}
        variant="destructive"
        onConfirm={() => {
          if (deleteIntent) {
            deleteTrigger.mutate(deleteIntent, {
              onSuccess: () => {
                toast.success(t("triggers.deleted", "Trigger deleted"));
                setDeleteIntent(null);
              },
              onError: (err) => toast.error(err.message),
            });
          }
        }}
        isPending={deleteTrigger.isPending}
      />
    </div>
  );
}

// ─── Sub-components ───

function TriggerCard({
  trigger,
  onEdit,
  onDelete,
}: {
  trigger: AgentTriggerConfiguration;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);

  return (
    <div className="rounded-xl border border-border bg-card" data-testid={`trigger-${trigger.intent}`}>
      <div className="flex items-center gap-3 px-5 py-4">
        <button
          type="button"
          className="flex flex-1 items-center gap-3 cursor-pointer text-start"
          onClick={() => setExpanded(!expanded)}
          aria-expanded={expanded}
          aria-controls={`trigger-content-${trigger.intent}`}
        >
          <span className="text-muted-foreground" aria-hidden="true">
            {expanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
          </span>
          <Zap className="h-4 w-4 text-amber-500 shrink-0" aria-hidden="true" />
          <span className="flex-1 font-semibold text-sm text-foreground font-mono">{trigger.intent}</span>
          <span className="rounded-full bg-primary/10 px-2 py-0.5 text-[10px] font-medium text-primary">
            {trigger.agentDeployments.length} {t("triggers.agents", "agents")}
          </span>
        </button>
        <button type="button" onClick={() => onEdit()} className="rounded p-1.5 text-muted-foreground hover:text-foreground transition-colors" title={t("common.edit")} aria-label={t("common.edit")}>
          <Edit3 className="h-3.5 w-3.5" aria-hidden="true" />
        </button>
        <button type="button" onClick={() => onDelete()} className="rounded p-1.5 text-muted-foreground hover:text-destructive transition-colors" title={t("common.delete")} aria-label={t("common.delete")}>
          <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
        </button>
      </div>

      {expanded && (
        <div id={`trigger-content-${trigger.intent}`} className="border-t border-border px-5 py-3 space-y-2">
          {trigger.agentDeployments.map((dep, i) => (
            <div key={i} className="flex items-center gap-3 rounded-lg bg-secondary/30 px-3 py-2">
              <Bot className="h-3.5 w-3.5 text-primary shrink-0" aria-hidden="true" />
              <span className="flex-1 text-xs font-mono text-foreground truncate">{dep.agentId}</span>
              <span className="inline-flex items-center gap-1 rounded-full bg-muted px-2 py-0.5 text-[10px] font-medium text-muted-foreground">
                <Server className="h-3 w-3" aria-hidden="true" />
                {dep.environment}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function TriggerDialog({
  initial,
  onClose,
  onSave,
  isPending,
}: {
  initial: AgentTriggerConfiguration | null;
  onClose: () => void;
  onSave: (config: AgentTriggerConfiguration) => void;
  isPending: boolean;
}) {
  const { t } = useTranslation();
  const [intent, setIntent] = useState(initial?.intent ?? "");
  const [deployments, setDeployments] = useState<AgentDeployment[]>(
    initial?.agentDeployments ?? [{ environment: "production", agentId: "" }],
  );

  const handleSave = useCallback(() => {
    if (!intent.trim()) return;
    const validDeps = deployments.filter((d) => d.agentId.trim());
    if (validDeps.length === 0) return;
    onSave({ intent: intent.trim(), agentDeployments: validDeps });
  }, [intent, deployments, onSave]);

  const dialogTitle = initial ? t("triggers.editTitle", "Edit Trigger") : t("triggers.createTitle", "Create Trigger");

  return (
    <AccessibleDialog
      open
      onClose={onClose}
      title={dialogTitle}
      testId="trigger-dialog"
      maxWidth="max-w-lg"
    >
      <div className="space-y-4 p-6">
        <div>
          <label htmlFor="trigger-intent" className="mb-1 block text-xs font-medium text-muted-foreground">{t("triggers.intent", "Intent")}</label>
          <input
            id="trigger-intent"
            type="text"
            value={intent}
            onChange={(e) => setIntent(e.target.value)}
            readOnly={!!initial}
            placeholder="e.g. booking_request"
            className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground font-mono placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid="trigger-intent-input"
            autoFocus
          />
        </div>

        <div className="space-y-2">
          <label className="text-xs font-medium text-muted-foreground">{t("triggers.deployments", "Agent Deployments")}</label>
          {deployments.map((dep, i) => (
            <div key={i} className="flex gap-2 items-center">
              <AgentPicker
                value={dep.agentId}
                onChange={(val) => {
                  const next = [...deployments];
                  next[i] = { ...dep, agentId: val };
                  setDeployments(next);
                }}
                placeholder={t("triggers.agentId", "Agent ID")}
              />
              <select
                value={dep.environment}
                onChange={(e) => {
                  const next = [...deployments];
                  next[i] = { ...dep, environment: e.target.value };
                  setDeployments(next);
                }}
                aria-label={`${t("triggers.environment", "Environment")} ${i + 1}`}
                className="h-9 rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              >
                {ENVIRONMENTS.map((env) => (
                  <option key={env} value={env}>{env}</option>
                ))}
              </select>
              <button
                type="button"
                onClick={() => setDeployments(deployments.filter((_, j) => j !== i))}
                className="text-muted-foreground hover:text-destructive"
                aria-label={t("common.delete")}
              >
                <X className="h-4 w-4" aria-hidden="true" />
              </button>
            </div>
          ))}
          <button
            type="button"
            onClick={() => setDeployments([...deployments, { environment: "production", agentId: "" }])}
            className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
          >
            <Plus className="h-3 w-3" aria-hidden="true" />
            {t("triggers.addDeployment", "Add Agent")}
          </button>
        </div>

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="secondary" onClick={onClose} size="sm">{t("common.cancel")}</Button>
          <Button onClick={handleSave} disabled={isPending || !intent.trim()} size="sm" data-testid="trigger-save-btn">
            {isPending ? t("common.saving", "Saving...") : t("common.save")}
          </Button>
        </div>
      </div>
    </AccessibleDialog>
  );
}
