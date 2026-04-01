import { useState, useCallback, useMemo, useEffect } from "react";
import { useOnboarding } from "@/hooks/use-onboarding";
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
  Info,
  AlertTriangle,
  ExternalLink,
  Copy,
  RefreshCw,
  FileText,
  Bot,
} from "lucide-react";
import {
  useSecrets,
  useStoreSecret,
  useDeleteSecret,
  useVaultHealth,
} from "@/hooks/use-secrets";
import type { SecretMetadata } from "@/lib/api/secrets";

const DEFAULT_TENANT = "default";

export function SecretsPage() {
  const { t } = useTranslation();

  const maybeAutoStart = useOnboarding((s) => s.maybeAutoStart);
  useEffect(() => { const t = setTimeout(() => maybeAutoStart("secrets"), 500); return () => clearTimeout(t); }, [maybeAutoStart]);

  /* ─── Namespace state ─── */
  const [tenantId, setTenantId] = useState(DEFAULT_TENANT);

  /* ─── Dialog state ─── */
  const [showCreate, setShowCreate] = useState(false);
  const [newKeyName, setNewKeyName] = useState("");
  const [newValue, setNewValue] = useState("");
  const [newDescription, setNewDescription] = useState("");
  const [valueVisible, setValueVisible] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<SecretMetadata | null>(null);
  const [newAllowedAgents, setNewAllowedAgents] = useState<string[]>([]);

  /* ─── Queries ─── */
  const { data: secrets, isLoading } = useSecrets(tenantId);
  const { data: vaultHealth } = useVaultHealth();
  const storeMut = useStoreSecret();
  const deleteMut = useDeleteSecret();

  const vaultDown = vaultHealth?.available === false;

  /* ─── Copy vault reference ─── */
  const copyRef = useCallback(
    (keyName: string) => {
      const ref =
        tenantId === DEFAULT_TENANT
          ? `\${eddivault:${keyName}}`
          : `\${eddivault:${tenantId}/${keyName}}`;
      navigator.clipboard.writeText(ref).then(() => {
        toast.success(
          t("secrets.refCopied", {
            ref,
            defaultValue: `Copied: ${ref}`,
          }),
        );
      });
    },
    [tenantId, t],
  );

  /* ─── Handlers ─── */
  const handleCreate = useCallback(() => {
    if (!newKeyName.trim() || !newValue.trim()) return;
    storeMut.mutate(
      {
        tenantId,
        keyName: newKeyName.trim(),
        value: newValue.trim(),
        description: newDescription.trim() || undefined,
        allowedAgents: newAllowedAgents.length > 0 ? newAllowedAgents : undefined,
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
          setNewDescription("");
          setValueVisible(false);
          setNewAllowedAgents([]);
        },
        onError: (err) =>
          toast.error(err instanceof Error ? err.message : String(err)),
      },
    );
  }, [tenantId, newKeyName, newValue, newDescription, newAllowedAgents, storeMut, t]);

  const handleDelete = useCallback(() => {
    if (!deleteTarget) return;
    deleteMut.mutate(
      {
        tenantId: deleteTarget.tenantId,
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

  /** Build the short-form or full-form vault reference string for display */
  const refString = useMemo(
    () => (keyName: string) =>
      tenantId === DEFAULT_TENANT
        ? `\${eddivault:${keyName}}`
        : `\${eddivault:${tenantId}/${keyName}}`,
    [tenantId],
  );

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
              "Manage encrypted secrets shared across all agents in a tenant. Values are never exposed.",
            )}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={() => setShowCreate(true)}
            disabled={vaultDown}
            title={
              vaultDown
                ? t(
                    "secrets.vaultNotConfigured",
                    "Secrets vault is not configured",
                  )
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
        <div
          className="space-y-2 rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-4"
          data-testid="vault-not-configured"
        >
          <div className="flex items-center gap-2">
            <AlertTriangle className="h-4 w-4 shrink-0 text-destructive" />
            <span className="text-sm font-semibold text-destructive">
              {vaultHealth?.error ||
                t(
                  "secrets.vaultNotConfigured",
                  "Secrets Vault is not configured",
                )}
            </span>
          </div>
          {vaultHealth?.reason && (
            <p className="text-xs text-muted-foreground">
              {vaultHealth.reason}
            </p>
          )}
          {vaultHealth?.action && (
            <div className="rounded-md bg-muted/50 px-3 py-2">
              <code className="break-all text-xs text-foreground">
                {vaultHealth.action}
              </code>
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
        <div className="flex items-start gap-2.5 rounded-lg border border-primary/20 bg-primary/5 px-4 py-3" data-tour="secrets-info">
          <Info className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
          <div className="space-y-1">
            <p className="text-xs text-muted-foreground">
              {t(
                "secrets.scopeExplanation",
                "Secrets are scoped per tenant and shared across all agents. Reference them in configs with ${eddivault:keyName}. Access is controlled by configuration authorship — only admins who write the config decide which secrets to use.",
              )}
            </p>
          </div>
        </div>
      )}

      {/* Tenant selector */}
      <div className="flex flex-wrap items-end gap-4">
        <div className="flex flex-col gap-1.5">
          <label
            htmlFor="secrets-tenant-input"
            className="text-xs font-medium text-muted-foreground"
          >
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
        {isLoading && (
          <div className="flex h-9 items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            {t("common.loading", "Loading…")}
          </div>
        )}
      </div>

      {/* Secrets table */}
      {secrets && secrets.length > 0 && (
        <div className="rounded-xl border border-border bg-card shadow-sm">
          <div className="border-b border-border px-4 py-3">
            <h2 className="text-sm font-semibold text-foreground">
              {t("secrets.tableTitle", {
                tenant: tenantId,
                count: secrets.length,
                defaultValue: `${secrets.length} secret${secrets.length === 1 ? "" : "s"} in tenant "${tenantId}"`,
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
                      <FileText className="h-3.5 w-3.5" />
                      {t("secrets.descriptionCol", "Description")}
                    </span>
                  </th>
                  <th className="px-4 py-3 text-start font-semibold text-foreground">
                    {t("secrets.reference", "Reference")}
                  </th>
                  <th className="px-4 py-3 text-start font-semibold text-foreground">
                    <span className="inline-flex items-center gap-1.5">
                      <Clock className="h-3.5 w-3.5" />
                      {t("secrets.created", "Created")}
                    </span>
                  </th>
                  <th className="px-4 py-3 text-start font-semibold text-foreground">
                    <span className="inline-flex items-center gap-1.5">
                      <RefreshCw className="h-3.5 w-3.5" />
                      {t("secrets.lastRotated", "Last Rotated")}
                    </span>
                  </th>
                  <th className="px-4 py-3 text-start font-semibold text-foreground">
                    <span className="inline-flex items-center gap-1.5">
                      <Hash className="h-3.5 w-3.5" />
                      {t("secrets.checksum", "Checksum")}
                    </span>
                  </th>
                  <th className="px-4 py-3 text-start font-semibold text-foreground">
                    <span className="inline-flex items-center gap-1.5">
                      <Bot className="h-3.5 w-3.5" />
                      {t("secrets.allowedAgents", "Allowed Agents")}
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
                    key={`${s.tenantId}-${s.keyName}`}
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
                    <td className="max-w-48 px-4 py-3 text-muted-foreground">
                      <span className="line-clamp-1">
                        {s.description || "—"}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <button
                        onClick={() => copyRef(s.keyName)}
                        className="inline-flex items-center gap-1.5 rounded-md bg-muted/50 px-2 py-1 font-mono text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
                        title={t("secrets.copyRef", "Copy vault reference")}
                        data-testid={`copy-ref-${s.keyName}`}
                      >
                        <Copy className="h-3 w-3" />
                        {refString(s.keyName)}
                      </button>
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">
                      {formatDate(s.createdAt)}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">
                      {formatDate(s.lastRotatedAt)}
                    </td>
                    <td className="px-4 py-3">
                      <span className="font-mono text-xs text-muted-foreground">
                        {s.checksum
                          ? s.checksum.substring(0, 12) + "…"
                          : "—"}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex flex-wrap gap-1">
                        {(s.allowedAgents ?? ["*"]).map((a) => (
                          <span
                            key={a}
                            className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-medium ${
                              a === "*"
                                ? "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400"
                                : "bg-primary/10 text-primary"
                            }`}
                          >
                            {a === "*" ? (
                              <>{t("secrets.allAgents", "All agents")}</>
                            ) : (
                              <><Bot className="h-2.5 w-2.5" />{a.length > 16 ? a.slice(0, 16) + "…" : a}</>
                            )}
                          </span>
                        ))}
                      </div>
                    </td>
                    <td className="px-4 py-3 text-end">
                      <button
                        onClick={() => setDeleteTarget(s)}
                        className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium text-destructive transition-colors hover:bg-destructive/10"
                        data-testid={`delete-${s.keyName}`}
                        aria-label={t("secrets.deleteKey", {
                          key: s.keyName,
                          defaultValue: `Delete ${s.keyName}`,
                        })}
                      >
                        <Trash2
                          className="h-3.5 w-3.5"
                          aria-hidden="true"
                        />
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

      {/* Empty state */}
      {secrets && secrets.length === 0 && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-border p-12 text-center">
          <KeyRound className="h-12 w-12 text-muted-foreground/30" />
          <p className="mt-4 text-lg font-medium text-foreground">
            {t("secrets.empty", "No secrets found")}
          </p>
          <p className="mt-1 text-sm text-muted-foreground">
            {t("secrets.emptyHint", {
              tenant: tenantId,
              defaultValue: `No secrets stored for tenant "${tenantId}" yet. Click "Add Secret" to create one.`,
            })}
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
            setNewDescription("");
            setValueVisible(false);
            setNewAllowedAgents([]);
          }}
          onKeyDown={(e) => {
            if (e.key === "Escape") {
              setShowCreate(false);
              setNewKeyName("");
              setNewValue("");
              setNewDescription("");
              setValueVisible(false);
              setNewAllowedAgents([]);
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
              <h2
                id="create-secret-title"
                className="text-lg font-semibold text-foreground"
              >
                {t("secrets.createTitle", "Add Secret")}
              </h2>
              <button
                onClick={() => {
                  setShowCreate(false);
                  setNewKeyName("");
                  setNewValue("");
                  setNewDescription("");
                  setValueVisible(false);
                  setNewAllowedAgents([]);
                }}
                className="rounded-lg p-1.5 text-muted-foreground hover:bg-muted"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            {/* Tenant context badge */}
            <div className="mt-3 flex items-center gap-2 rounded-lg bg-muted/50 px-3 py-2 text-xs text-muted-foreground">
              <KeyRound className="h-3.5 w-3.5 shrink-0" />
              <span>
                {t("secrets.creatingFor", {
                  tenant: tenantId,
                  defaultValue: `Storing in tenant "${tenantId}" — available to all agents`,
                })}
              </span>
            </div>

            <div className="mt-4 space-y-4">
              <div>
                <label
                  htmlFor="new-key-name"
                  className="mb-1.5 block text-xs font-medium text-muted-foreground"
                >
                  {t("secrets.keyNameLabel", "Key Name")}
                </label>
                <input
                  id="new-key-name"
                  type="text"
                  value={newKeyName}
                  onChange={(e) => setNewKeyName(e.target.value)}
                  placeholder={t(
                    "secrets.keyNamePlaceholder",
                    "e.g. openaiKey, dbPassword",
                  )}
                  className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                  data-testid="new-key-input"
                  autoComplete="off"
                  autoFocus
                />
                {newKeyName.trim() && (
                  <p className="mt-1 font-mono text-xs text-muted-foreground">
                    {t("secrets.willUseRef", "Reference:")}{" "}
                    <code className="rounded bg-muted px-1 py-0.5">
                      {refString(newKeyName.trim())}
                    </code>
                  </p>
                )}
              </div>
              <div>
                <label
                  htmlFor="new-secret-value"
                  className="mb-1.5 block text-xs font-medium text-muted-foreground"
                >
                  {t("secrets.valueLabel", "Secret Value")}
                </label>
                <div className="relative">
                  <input
                    id="new-secret-value"
                    type={valueVisible ? "text" : "password"}
                    value={newValue}
                    onChange={(e) => setNewValue(e.target.value)}
                    placeholder={t(
                      "secrets.valuePlaceholder",
                      "Enter secret value…",
                    )}
                    className="h-9 w-full rounded-lg border border-input bg-background pe-10 ps-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                    data-testid="new-value-input"
                    autoComplete="new-password"
                  />
                  <button
                    type="button"
                    onClick={() => setValueVisible(!valueVisible)}
                    className="absolute inset-e-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                    aria-label={
                      valueVisible
                        ? t("secrets.hideValue", "Hide value")
                        : t("secrets.showValue", "Show value")
                    }
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
              <div>
                <label
                  htmlFor="new-description"
                  className="mb-1.5 block text-xs font-medium text-muted-foreground"
                >
                  {t("secrets.descriptionLabel", "Description (optional)")}
                </label>
                <input
                  id="new-description"
                  type="text"
                  value={newDescription}
                  onChange={(e) => setNewDescription(e.target.value)}
                  placeholder={t(
                    "secrets.descriptionPlaceholder",
                    "e.g. OpenAI API key for production",
                  )}
                  className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                  data-testid="new-description-input"
                  autoComplete="off"
                />
              </div>
              {/* Allowed Agents */}
              <div>
                <label
                  htmlFor="new-allowed-agents"
                  className="mb-1.5 block text-xs font-medium text-muted-foreground"
                >
                  {t("secrets.allowedAgentsLabel", "Allowed Agents (optional)")}
                </label>
                <div className="space-y-1.5">
                  {newAllowedAgents.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {newAllowedAgents.map((agentId) => (
                        <span
                          key={agentId}
                          className="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary"
                        >
                          <Bot className="h-2.5 w-2.5" />
                          {agentId.length > 20 ? agentId.slice(0, 20) + "…" : agentId}
                          <button
                            type="button"
                            onClick={() => setNewAllowedAgents((prev) => prev.filter((a) => a !== agentId))}
                            className="ms-0.5 rounded-full p-0.5 text-primary/60 hover:text-primary transition-colors"
                          >
                            <X className="h-2.5 w-2.5" />
                          </button>
                        </span>
                      ))}
                    </div>
                  )}
                  <input
                    id="new-allowed-agents"
                    type="text"
                    placeholder={t("secrets.allowedAgentsPlaceholder", "Type agent ID and press Enter (empty = all agents)")}
                    className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                    data-testid="new-allowed-agents-input"
                    autoComplete="off"
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === ",") {
                        e.preventDefault();
                        const input = e.currentTarget;
                        const val = input.value.trim().replace(/,$/,"");
                        if (val && !newAllowedAgents.includes(val)) {
                          setNewAllowedAgents((prev) => [...prev, val]);
                          input.value = "";
                        }
                      }
                    }}
                  />
                  <p className="text-[10px] text-muted-foreground">
                    {t("secrets.allowedAgentsHint", "Leave empty for all agents. Adding agent IDs helps track which agents use this secret.")}
                  </p>
                </div>
              </div>
              <p className="text-xs text-muted-foreground">
                {t(
                  "secrets.storeWarning",
                  "The value will be encrypted with AES-256-GCM. It cannot be retrieved once stored — only replaced.",
                )}
              </p>
            </div>

            <div className="mt-6 flex justify-end gap-2">
              <button
                onClick={() => {
                  setShowCreate(false);
                  setNewKeyName("");
                  setNewValue("");
                  setNewDescription("");
                  setValueVisible(false);
                  setNewAllowedAgents([]);
                }}
                className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted"
              >
                {t("common.cancel", "Cancel")}
              </button>
              <button
                onClick={handleCreate}
                disabled={
                  !newKeyName.trim() ||
                  !newValue.trim() ||
                  storeMut.isPending
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
            <h2
              id="delete-secret-title"
              className="text-lg font-semibold text-foreground"
            >
              {t("secrets.confirmDeleteTitle", "Delete Secret")}
            </h2>
            <p
              id="delete-secret-desc"
              className="mt-2 text-sm text-muted-foreground"
            >
              {t("secrets.confirmDeleteMessage", {
                key: deleteTarget.keyName,
                defaultValue: `Are you sure you want to permanently delete "${deleteTarget.keyName}"? Any agent configs using this secret will fail to resolve. This cannot be undone.`,
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
