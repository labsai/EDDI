import { useState, useCallback, useMemo, useEffect } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import {
  KeyRound,
  Plus,
  Trash2,
  Loader2,
  Clock,
  Hash,
  X,
  Eye,
  EyeOff,
  ChevronDown,
  Bot,
  Info,
  AlertTriangle,
  ExternalLink,
} from "lucide-react";
import {
  useSecrets,
  useStoreSecret,
  useDeleteSecret,
  useVaultHealth,
} from "@/hooks/use-secrets";
import { useAgentDescriptors } from "@/hooks/use-agents";
import { parseResourceUri } from "@/lib/api/agents";
import type { SecretMetadata } from "@/lib/api/secrets";

const DEFAULT_TENANT = "default";

export function SecretsPage() {
  const { t } = useTranslation();

  /* ─── Namespace state ─── */
  const [tenantId, setTenantId] = useState(DEFAULT_TENANT);
  const [agentId, setAgentId] = useState("");

  /* ─── Agent descriptors for the selector ─── */
  const { data: agentDescriptors } = useAgentDescriptors(100);
  const agents = useMemo(() => {
    if (!agentDescriptors) return [];
    return agentDescriptors.map((d) => {
      const { id } = parseResourceUri(d.resource);
      return { id, name: d.name || id };
    });
  }, [agentDescriptors]);

  // Auto-select first agent when agents load and none is selected
  useEffect(() => {
    if (!agentId && agents.length > 0) {
      setAgentId(agents[0]!.id);
    }
  }, [agents, agentId]);

  /* ─── Dialog state ─── */
  const [showCreate, setShowCreate] = useState(false);
  const [newKeyName, setNewKeyName] = useState("");
  const [newValue, setNewValue] = useState("");
  const [valueVisible, setValueVisible] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<SecretMetadata | null>(null);

  /* ─── Queries (auto-fetches when agentId changes) ─── */
  const {
    data: secrets,
    isLoading,
  } = useSecrets(tenantId, agentId);
  const { data: vaultHealth } = useVaultHealth();
  const storeMut = useStoreSecret();
  const deleteMut = useDeleteSecret();

  const vaultDown = vaultHealth?.available === false;

  /* ─── Handlers ─── */
  const handleCreate = useCallback(() => {
    if (!newKeyName.trim() || !newValue.trim()) return;
    storeMut.mutate(
      {
        tenantId,
        agentId,
        keyName: newKeyName.trim(),
        value: newValue.trim(),
      },
      {
        onSuccess: () => {
          toast.success(
            t("secrets.storeSuccess", {
              key: newKeyName,
              defaultValue: `Secret "${newKeyName}" stored`,
            }),
          );
          setShowCreate(false);
          setNewKeyName("");
          setNewValue("");
          setValueVisible(false);
        },
        onError: (err) =>
          toast.error(err instanceof Error ? err.message : String(err)),
      },
    );
  }, [tenantId, agentId, newKeyName, newValue, storeMut, t]);

  const handleDelete = useCallback(() => {
    if (!deleteTarget) return;
    deleteMut.mutate(
      {
        tenantId: deleteTarget.tenantId,
        agentId: deleteTarget.agentId,
        keyName: deleteTarget.keyName,
      },
      {
        onSuccess: () => {
          toast.success(
            t("secrets.deleteSuccess", {
              key: deleteTarget.keyName,
              defaultValue: `Secret "${deleteTarget.keyName}" deleted`,
            }),
          );
          setDeleteTarget(null);
        },
        onError: (err) =>
          toast.error(err instanceof Error ? err.message : String(err)),
      },
    );
  }, [deleteTarget, deleteMut, t]);

  const formatDate = (iso: string | null) => {
    if (!iso) return "—";
    try {
      return new Intl.DateTimeFormat(undefined, {
        dateStyle: "medium",
        timeStyle: "short",
      }).format(new Date(iso));
    } catch {
      return iso;
    }
  };

  const selectedAgentName = agents.find((a) => a.id === agentId)?.name;

  return (
    <div className="space-y-6 p-6" data-testid="secrets-page">
      {/* Header */}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h1 className="text-2xl font-bold text-foreground">
            {t("secrets.title", "Secrets Vault")}
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {t(
              "secrets.description",
              "Manage encrypted secrets stored in the vault. Values are never exposed.",
            )}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={() => setShowCreate(true)}
            disabled={!agentId || vaultDown}
            title={vaultDown
              ? t("secrets.vaultNotConfigured", "Secrets vault is not configured")
              : !agentId
                ? t("secrets.selectAgentFirst", "Select an agent first — secrets are scoped per agent")
                : undefined
            }
            className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-50"
            data-testid="create-secret-button"
          >
            <Plus className="h-4 w-4" />
            {t("secrets.create", "Add Secret")}
          </button>
        </div>
      </div>

      {/* Vault status banner */}
      {vaultDown ? (
        <div className="rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-4 space-y-2" data-testid="vault-not-configured">
          <div className="flex items-center gap-2">
            <AlertTriangle className="h-4 w-4 shrink-0 text-destructive" />
            <span className="text-sm font-semibold text-destructive">
              {vaultHealth?.error || t("secrets.vaultNotConfigured", "Secrets Vault is not configured")}
            </span>
          </div>
          {vaultHealth?.reason && (
            <p className="text-xs text-muted-foreground">{vaultHealth.reason}</p>
          )}
          {vaultHealth?.action && (
            <div className="rounded-md bg-muted/50 px-3 py-2">
              <code className="text-xs text-foreground break-all">{vaultHealth.action}</code>
            </div>
          )}
          {vaultHealth?.docs && (
            <a
              href={vaultHealth.docs}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-xs text-primary hover:underline"
            >
              <ExternalLink className="h-3 w-3" />
              {t("secrets.viewDocs", "View documentation")}
            </a>
          )}
        </div>
      ) : (
        <div className="flex items-start gap-2.5 rounded-lg border border-primary/20 bg-primary/5 px-4 py-3">
          <Info className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
          <p className="text-xs text-muted-foreground">
            {t(
              "secrets.scopeExplanation",
              "Secrets are scoped per agent — each agent has its own isolated vault namespace. Select an agent below to view and manage its secrets.",
            )}
          </p>
        </div>
      )}

      {/* Namespace selectors */}
      <div className="flex flex-wrap items-end gap-4">
        <div className="flex flex-col gap-1.5">
          <label htmlFor="secrets-tenant-input" className="text-xs font-medium text-muted-foreground">
            {t("secrets.tenantId", "Tenant ID")}
          </label>
          <input
            id="secrets-tenant-input"
            type="text"
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value)}
            placeholder="default"
            className="h-9 w-48 rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
            data-testid="tenant-input"
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="secrets-agent-select" className="text-xs font-medium text-muted-foreground">
            {t("secrets.agentId", "Agent ID")}
          </label>
          <div className="relative">
            <select
              id="secrets-agent-select"
              value={agentId}
              onChange={(e) => setAgentId(e.target.value)}
              className="h-9 w-72 appearance-none rounded-lg border border-input bg-background pe-8 ps-9 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
              data-testid="agent-id-input"
            >
              <option value="">{t("secrets.selectAgent", "Select an agent…")}</option>
              {agents.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name} ({a.id})
                </option>
              ))}
            </select>
            <Bot className="pointer-events-none absolute inset-s-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <ChevronDown className="pointer-events-none absolute inset-e-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
          </div>
        </div>
        {isLoading && (
          <div className="flex h-9 items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            {t("common.loading", "Loading…")}
          </div>
        )}
      </div>

      {/* Secrets table */}
      {agentId && secrets && secrets.length > 0 && (
        <div className="rounded-xl border border-border bg-card shadow-sm">
          <div className="border-b border-border px-4 py-3">
            <h2 className="text-sm font-semibold text-foreground">
              {t("secrets.tableTitle", {
                agent: selectedAgentName || agentId,
                defaultValue: `Secrets for ${selectedAgentName || agentId}`,
              })}
            </h2>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-start">
                  <th className="px-4 py-3 text-start font-semibold text-foreground">
                    {t("secrets.keyName", "Key Name")}
                  </th>
                  <th className="px-4 py-3 text-start font-semibold text-foreground">
                    <span className="inline-flex items-center gap-1.5">
                      <Clock className="h-3.5 w-3.5" />
                      {t("secrets.created", "Created")}
                    </span>
                  </th>
                  <th className="px-4 py-3 text-start font-semibold text-foreground">
                    <span className="inline-flex items-center gap-1.5">
                      <Clock className="h-3.5 w-3.5" />
                      {t("secrets.lastAccessed", "Last Accessed")}
                    </span>
                  </th>
                  <th className="px-4 py-3 text-start font-semibold text-foreground">
                    <span className="inline-flex items-center gap-1.5">
                      <Hash className="h-3.5 w-3.5" />
                      {t("secrets.checksum", "Checksum")}
                    </span>
                  </th>
                  <th className="px-4 py-3 text-end font-semibold text-foreground">
                    {t("secrets.actions", "Actions")}
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {secrets.map((s) => (
                  <tr
                    key={`${s.tenantId}-${s.agentId}-${s.keyName}`}
                    className="transition-colors hover:bg-muted/50"
                  >
                    <td className="px-4 py-3">
                      <span className="inline-flex items-center gap-2">
                        <KeyRound className="h-4 w-4 text-primary" />
                        <span className="font-medium text-foreground">
                          {s.keyName}
                        </span>
                      </span>
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">
                      {formatDate(s.createdAt)}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">
                      {formatDate(s.lastAccessedAt)}
                    </td>
                    <td className="px-4 py-3">
                      <span className="font-mono text-xs text-muted-foreground">
                        {s.checksum ? s.checksum.substring(0, 12) + "…" : "—"}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-end">
                      <button
                        onClick={() => setDeleteTarget(s)}
                        className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium text-destructive transition-colors hover:bg-destructive/10"
                        data-testid={`delete-${s.keyName}`}
                        aria-label={t("secrets.deleteKey", { key: s.keyName, defaultValue: `Delete ${s.keyName}` })}
                      >
                        <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
                        {t("common.delete", "Delete")}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Empty state — agent selected but no secrets */}
      {agentId && secrets && secrets.length === 0 && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border p-12 text-center">
          <KeyRound className="h-12 w-12 text-muted-foreground/30" />
          <p className="mt-4 text-lg font-medium text-foreground">
            {t("secrets.empty", "No secrets found")}
          </p>
          <p className="mt-1 text-sm text-muted-foreground">
            {t(
              "secrets.emptyHintAgent",
              {
                agent: selectedAgentName || agentId,
                defaultValue: `No secrets stored for ${selectedAgentName || agentId} yet. Click "Add Secret" to create one.`,
              },
            )}
          </p>
        </div>
      )}

      {/* Not loaded yet (no agentId) */}
      {!agentId && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border p-12 text-center">
          <KeyRound className="h-12 w-12 text-muted-foreground/30" />
          <p className="mt-4 text-lg font-medium text-foreground">
            {t("secrets.enterAgentId", "Enter an Agent ID")}
          </p>
          <p className="mt-1 text-sm text-muted-foreground">
            {t(
              "secrets.enterAgentIdHint",
              "Specify an Agent ID above to view and manage its secrets.",
            )}
          </p>
        </div>
      )}

      {/* ─── Create dialog ─── */}
      {showCreate && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          onClick={() => {
            setShowCreate(false);
            setNewKeyName("");
            setNewValue("");
            setValueVisible(false);
          }}
          onKeyDown={(e) => {
            if (e.key === "Escape") {
              setShowCreate(false);
              setNewKeyName("");
              setNewValue("");
              setValueVisible(false);
            }
          }}
        >
          <div
            className="w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-labelledby="create-secret-title"
          >
            <div className="flex items-center justify-between">
              <h2 id="create-secret-title" className="text-lg font-semibold text-foreground">
                {t("secrets.createTitle", "Add Secret")}
              </h2>
              <button
                onClick={() => {
                  setShowCreate(false);
                  setNewKeyName("");
                  setNewValue("");
                  setValueVisible(false);
                }}
                className="rounded-lg p-1.5 text-muted-foreground hover:bg-muted"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            {/* Show which agent the secret is for */}
            <div className="mt-3 flex items-center gap-2 rounded-lg bg-muted/50 px-3 py-2 text-xs text-muted-foreground">
              <Bot className="h-3.5 w-3.5 shrink-0" />
              <span>
                {t("secrets.creatingFor", {
                  tenant: tenantId,
                  agent: selectedAgentName || agentId,
                  defaultValue: `Creating secret for ${selectedAgentName || agentId} (tenant: ${tenantId})`,
                })}
              </span>
            </div>

            <div className="mt-4 space-y-4">
              <div>
                <label htmlFor="new-key-name" className="mb-1.5 block text-xs font-medium text-muted-foreground">
                  {t("secrets.keyNameLabel", "Key Name")}
                </label>
                <input
                  id="new-key-name"
                  type="text"
                  value={newKeyName}
                  onChange={(e) => setNewKeyName(e.target.value)}
                  placeholder={t(
                    "secrets.keyNamePlaceholder",
                    "e.g. apiKey, dbPassword",
                  )}
                  className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                  data-testid="new-key-input"
                  autoComplete="off"
                  autoFocus
                />
              </div>
              <div>
                <label htmlFor="new-secret-value" className="mb-1.5 block text-xs font-medium text-muted-foreground">
                  {t("secrets.valueLabel", "Secret Value")}
                </label>
                <div className="relative">
                  <input
                    id="new-secret-value"
                    type={valueVisible ? "text" : "password"}
                    value={newValue}
                    onChange={(e) => setNewValue(e.target.value)}
                    placeholder={t("secrets.valuePlaceholder", "Enter secret value…")}
                    className="h-9 w-full rounded-lg border border-input bg-background pe-10 ps-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                    data-testid="new-value-input"
                    autoComplete="new-password"
                  />
                  <button
                    type="button"
                    onClick={() => setValueVisible(!valueVisible)}
                    className="absolute inset-e-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                    aria-label={valueVisible ? t("secrets.hideValue", "Hide value") : t("secrets.showValue", "Show value")}
                    data-testid="new-value-eye"
                  >
                    {valueVisible ? (
                      <Eye className="h-4 w-4" aria-hidden="true" />
                    ) : (
                      <EyeOff className="h-4 w-4" aria-hidden="true" />
                    )}
                  </button>
                </div>
              </div>
              <p className="text-xs text-muted-foreground">
                {t(
                  "secrets.storeWarning",
                  "The value will be encrypted. It cannot be retrieved once stored.",
                )}
              </p>
            </div>

            <div className="mt-6 flex justify-end gap-2">
              <button
                onClick={() => {
                  setShowCreate(false);
                  setNewKeyName("");
                  setNewValue("");
                  setValueVisible(false);
                }}
                className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted"
              >
                {t("common.cancel", "Cancel")}
              </button>
              <button
                onClick={handleCreate}
                disabled={
                  !newKeyName.trim() || !newValue.trim() || storeMut.isPending
                }
                className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
                data-testid="confirm-create-button"
              >
                {storeMut.isPending && (
                  <Loader2 className="h-4 w-4 animate-spin" />
                )}
                {t("secrets.store", "Store Secret")}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ─── Delete confirmation dialog ─── */}
      {deleteTarget && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          onClick={() => setDeleteTarget(null)}
          onKeyDown={(e) => {
            if (e.key === "Escape") setDeleteTarget(null);
          }}
        >
          <div
            className="w-full max-w-sm rounded-xl border border-border bg-card p-6 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="delete-secret-title"
            aria-describedby="delete-secret-desc"
          >
            <h2 id="delete-secret-title" className="text-lg font-semibold text-foreground">
              {t("secrets.confirmDeleteTitle", "Delete Secret")}
            </h2>
            <p id="delete-secret-desc" className="mt-2 text-sm text-muted-foreground">
              {t("secrets.confirmDeleteMessage", {
                key: deleteTarget.keyName,
                defaultValue: `Are you sure you want to permanently delete "${deleteTarget.keyName}"? This cannot be undone.`,
              })}
            </p>
            <div className="mt-6 flex justify-end gap-2">
              <button
                onClick={() => setDeleteTarget(null)}
                className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted"
              >
                {t("common.cancel", "Cancel")}
              </button>
              <button
                onClick={handleDelete}
                disabled={deleteMut.isPending}
                className="inline-flex items-center gap-2 rounded-lg bg-destructive px-4 py-2 text-sm font-medium text-destructive-foreground transition-colors hover:bg-destructive/90 disabled:opacity-50"
                data-testid="confirm-delete-button"
              >
                {deleteMut.isPending && (
                  <Loader2 className="h-4 w-4 animate-spin" />
                )}
                {t("common.delete", "Delete")}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
