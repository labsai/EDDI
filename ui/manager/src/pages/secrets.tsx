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
  ShieldAlert,
  KeySquare,
  RotateCw,
} from "lucide-react";
import {
  useSecrets,
  useStoreSecret,
  useDeleteSecret,
  useVaultHealth,
  useRotateSecret,
  useRotateDek,
  useRotateKek,
  useResetTenant,
} from "@/hooks/use-secrets";
import type { SecretMetadata } from "@/lib/api/secrets";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { getErrorMessage } from "@/lib/api-client";

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
  const [rotateTarget, setRotateTarget] = useState<SecretMetadata | null>(null);
  const [rotateValue, setRotateValue] = useState("");
  const [rotateVisible, setRotateVisible] = useState(false);
  const [newAllowedAgents, setNewAllowedAgents] = useState<string[]>([]);
  const [newAgentInput, setNewAgentInput] = useState("");

  /* ─── Key-lifecycle (danger zone) state ─── */
  const [showRotateDek, setShowRotateDek] = useState(false);
  const [showRotateKek, setShowRotateKek] = useState(false);
  const [showResetTenant, setShowResetTenant] = useState(false);
  const [kekOldKey, setKekOldKey] = useState("");
  const [kekNewKey, setKekNewKey] = useState("");
  const [kekOldVisible, setKekOldVisible] = useState(false);
  const [kekNewVisible, setKekNewVisible] = useState(false);
  const [resetConfirm, setResetConfirm] = useState("");
  const [kekResult, setKekResult] = useState<number | null>(null);

  /* ─── Queries ─── */
  const {
    data: secrets,
    isLoading,
    isError,
    error,
    refetch,
  } = useSecrets(tenantId);
  const { data: vaultHealth } = useVaultHealth();
  const storeMut = useStoreSecret();
  const deleteMut = useDeleteSecret();
  const rotateMut = useRotateSecret();
  const rotateDekMut = useRotateDek();
  const rotateKekMut = useRotateKek();
  const resetTenantMut = useResetTenant();

  const vaultDown = vaultHealth?.available === false;
  const secretCount = secrets?.length ?? 0;
  const kekValid = kekOldKey.trim().length > 0 && kekNewKey.length >= 8;

  /* The KEK-rotation result is tenant-agnostic advice about the running instance,
   * but leaving it up while switching tenants is confusing. Clear it on switch. */
  useEffect(() => {
    setKekResult(null);
  }, [tenantId]);

  /* Drop any pending (un-committed) allowed-agent text once the create dialog
   * closes so it doesn't reappear the next time the dialog is opened. */
  useEffect(() => {
    if (!showCreate) setNewAgentInput("");
  }, [showCreate]);

  /* ─── Copy vault reference ─── */
  const copyRef = useCallback(
    (keyName: string) => {
      const ref =
        tenantId === DEFAULT_TENANT
          ? `\${vault:${keyName}}`
          : `\${vault:${tenantId}/${keyName}}`;
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
    // Flush any typed-but-not-Entered agent so it isn't silently dropped
    // (which would leave the secret unrestricted / available to ALL agents).
    const pendingAgent = newAgentInput.trim().replace(/,$/, "");
    const finalAllowedAgents =
      pendingAgent && !newAllowedAgents.includes(pendingAgent)
        ? [...newAllowedAgents, pendingAgent]
        : newAllowedAgents;
    storeMut.mutate(
      {
        tenantId,
        keyName: newKeyName.trim(),
        value: newValue.trim(),
        description: newDescription.trim() || undefined,
        allowedAgents:
          finalAllowedAgents.length > 0 ? finalAllowedAgents : undefined,
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
          setNewAgentInput("");
        },
        onError: (err) =>
          toast.error(err instanceof Error ? err.message : String(err)),
      },
    );
  }, [tenantId, newKeyName, newValue, newDescription, newAllowedAgents, newAgentInput, storeMut, t]);

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

  /* ─── Key-lifecycle handlers ─── */
  const handleRotateDek = useCallback(() => {
    rotateDekMut.mutate(
      { tenantId },
      {
        onSuccess: (data) => {
          toast.success(
            t("secrets.rotateDekSuccess", {
              count: data.secretsReEncrypted,
              defaultValue: `Encryption key rotated — ${data.secretsReEncrypted} secret(s) re-encrypted`,
            }),
          );
          setShowRotateDek(false);
        },
        onError: (err) => toast.error(getErrorMessage(err)),
      },
    );
  }, [tenantId, rotateDekMut, t]);

  const handleRotateKek = useCallback(() => {
    if (!kekValid) return;
    rotateKekMut.mutate(
      { oldKey: kekOldKey, newKey: kekNewKey },
      {
        onSuccess: (data) => {
          setKekResult(data.deksReEncrypted);
          toast.success(
            t("secrets.rotateKekSuccess", {
              count: data.deksReEncrypted,
              defaultValue: `Master key rotated — ${data.deksReEncrypted} DEK(s) re-encrypted. Update EDDI_VAULT_MASTER_KEY and restart.`,
            }),
          );
          setShowRotateKek(false);
          setKekOldKey("");
          setKekNewKey("");
          setKekOldVisible(false);
          setKekNewVisible(false);
        },
        onError: (err) => toast.error(getErrorMessage(err)),
      },
    );
  }, [kekValid, kekOldKey, kekNewKey, rotateKekMut, t]);

  const handleResetTenant = useCallback(() => {
    if (resetConfirm !== tenantId) return;
    resetTenantMut.mutate(
      { tenantId },
      {
        onSuccess: (data) => {
          toast.success(
            t("secrets.resetTenantSuccess", {
              count: data.secretsDeleted,
              tenant: tenantId,
              defaultValue: `Vault reset for "${tenantId}" — ${data.secretsDeleted} secret(s) permanently deleted`,
            }),
          );
          setShowResetTenant(false);
          setResetConfirm("");
        },
        onError: (err) => toast.error(getErrorMessage(err)),
      },
    );
  }, [resetConfirm, tenantId, resetTenantMut, t]);

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
        ? `\${vault:${keyName}}`
        : `\${vault:${tenantId}/${keyName}}`,
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
                "Secrets are scoped per tenant and shared across all agents. Reference them in configs with ${vault:keyName}. Access is controlled by configuration authorship — only admins who write the config decide which secrets to use.",
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
                      <div className="flex items-center justify-end gap-1">
                        <button
                          onClick={() => { setRotateTarget(s); setRotateValue(""); setRotateVisible(false); }}
                          className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-medium text-amber-600 dark:text-amber-400 transition-colors hover:bg-amber-500/10"
                          data-testid={`rotate-${s.keyName}`}
                          aria-label={t("secrets.rotateKey", { key: s.keyName, defaultValue: `Rotate ${s.keyName}` })}
                        >
                          <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
                          {t("secrets.rotate", "Rotate")}
                        </button>
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
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Error state — distinct from the empty state so a 500/503/403 is not
          mistaken for an empty vault. */}
      {isError && !vaultDown && (
        <div
          className="space-y-3 rounded-xl border border-destructive/30 bg-destructive/5 p-6 text-center"
          data-testid="secrets-error"
        >
          <AlertTriangle className="mx-auto h-10 w-10 text-destructive" />
          <p className="text-lg font-medium text-foreground">
            {t("secrets.loadError", "Couldn't load secrets")}
          </p>
          <p className="text-sm text-muted-foreground">
            {getErrorMessage(error)}
          </p>
          <button
            onClick={() => refetch()}
            className="inline-flex items-center gap-2 rounded-lg border border-input bg-background px-4 py-2 text-sm font-medium text-foreground transition-colors hover:bg-muted"
            data-testid="secrets-error-retry"
          >
            <RefreshCw className="h-4 w-4" />
            {t("common.retry", "Retry")}
          </button>
        </div>
      )}

      {/* Empty state */}
      {!isError && secrets && secrets.length === 0 && (
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

      {/* ─── Key lifecycle / Danger zone ─── */}
      {!vaultDown && (
        <div
          className="space-y-4 rounded-xl border border-destructive/30 bg-destructive/5 p-5"
          data-testid="key-lifecycle-danger-zone"
        >
          <div className="flex items-start gap-2.5">
            <ShieldAlert className="mt-0.5 h-5 w-5 shrink-0 text-destructive" />
            <div>
              <h2 className="text-sm font-semibold text-foreground">
                {t("secrets.dangerZoneTitle", "Encryption key lifecycle")}
              </h2>
              <p className="mt-1 text-xs text-muted-foreground">
                {t(
                  "secrets.dangerZoneDesc",
                  "Operational recovery actions for the vault's cryptographic keys. Use these to rotate keys or recover access after the master key changed. Distinct from rotating an individual secret's value above.",
                )}
              </p>
            </div>
          </div>

          {/* KEK rotation success — unmissable restart instruction */}
          {kekResult !== null && (
            <div
              className="relative space-y-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-4 py-3 pe-10"
              data-testid="kek-rotation-result"
              role="status"
            >
              <button
                type="button"
                onClick={() => setKekResult(null)}
                className="absolute inset-e-2 top-2 rounded-md p-1 text-amber-700 hover:bg-amber-500/20 dark:text-amber-300"
                aria-label={t("common.dismiss", "Dismiss")}
                data-testid="dismiss-kek-result"
              >
                <X className="h-4 w-4" />
              </button>
              <p className="flex items-center gap-2 text-sm font-semibold text-amber-700 dark:text-amber-300">
                <AlertTriangle className="h-4 w-4 shrink-0" />
                {t("secrets.kekResultTitle", {
                  count: kekResult,
                  defaultValue: `Master key rotated — ${kekResult} DEK(s) re-encrypted`,
                })}
              </p>
              <p className="text-xs text-foreground">
                {t(
                  "secrets.kekResultInstruction",
                  "Action required: set the EDDI_VAULT_MASTER_KEY environment variable to the NEW master key and restart EDDI. Until you do, the running instance still uses the old key in memory.",
                )}
              </p>
            </div>
          )}

          <div className="grid gap-3 sm:grid-cols-3">
            {/* Rotate DEK */}
            <div className="flex flex-col gap-2 rounded-lg border border-border bg-card p-3">
              <div className="flex items-center gap-2">
                <RotateCw className="h-4 w-4 text-emerald-600 dark:text-emerald-400" />
                <span className="text-sm font-medium text-foreground">
                  {t("secrets.rotateDekTitle", "Rotate encryption key (DEK)")}
                </span>
              </div>
              <p className="text-xs text-muted-foreground">
                {t(
                  "secrets.rotateDekBlurb",
                  "Safe — no restart. Generates a new per-tenant data encryption key and re-encrypts every secret for this tenant.",
                )}
              </p>
              <button
                onClick={() => setShowRotateDek(true)}
                className="mt-auto inline-flex items-center justify-center gap-2 rounded-lg border border-emerald-500/40 bg-emerald-500/10 px-3 py-2 text-xs font-medium text-emerald-700 dark:text-emerald-300 transition-colors hover:bg-emerald-500/20"
                data-testid="open-rotate-dek"
              >
                <RotateCw className="h-3.5 w-3.5" />
                {t("secrets.rotateDekAction", "Rotate DEK")}
              </button>
            </div>

            {/* Rotate KEK */}
            <div className="flex flex-col gap-2 rounded-lg border border-border bg-card p-3">
              <div className="flex items-center gap-2">
                <KeySquare className="h-4 w-4 text-amber-600 dark:text-amber-400" />
                <span className="text-sm font-medium text-foreground">
                  {t("secrets.rotateKekTitle", "Rotate master key (KEK)")}
                </span>
              </div>
              <p className="text-xs text-muted-foreground">
                {t(
                  "secrets.rotateKekBlurb",
                  "Re-encrypts all tenant DEKs with a new master key. Requires updating EDDI_VAULT_MASTER_KEY and restarting afterwards.",
                )}
              </p>
              <button
                onClick={() => setShowRotateKek(true)}
                className="mt-auto inline-flex items-center justify-center gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs font-medium text-amber-700 dark:text-amber-300 transition-colors hover:bg-amber-500/20"
                data-testid="open-rotate-kek"
              >
                <KeySquare className="h-3.5 w-3.5" />
                {t("secrets.rotateKekAction", "Rotate master key")}
              </button>
            </div>

            {/* Reset tenant */}
            <div className="flex flex-col gap-2 rounded-lg border border-destructive/40 bg-card p-3">
              <div className="flex items-center gap-2">
                <Trash2 className="h-4 w-4 text-destructive" />
                <span className="text-sm font-medium text-foreground">
                  {t("secrets.resetTenantTitle", "Reset vault for this tenant")}
                </span>
              </div>
              <p className="text-xs text-muted-foreground">
                {t(
                  "secrets.resetTenantBlurb",
                  "Destructive last resort. Permanently deletes all secrets and the DEK so the vault can start fresh — use after a lost or changed master key.",
                )}
              </p>
              <button
                onClick={() => setShowResetTenant(true)}
                className="mt-auto inline-flex items-center justify-center gap-2 rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-2 text-xs font-medium text-destructive transition-colors hover:bg-destructive/20"
                data-testid="open-reset-tenant"
              >
                <Trash2 className="h-3.5 w-3.5" />
                {t("secrets.resetTenantAction", "Reset vault")}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ─── Rotate DEK confirmation ─── */}
      <AlertDialog
        open={showRotateDek}
        onOpenChange={(open) => setShowRotateDek(open)}
        variant="warning"
        title={t("secrets.rotateDekConfirmTitle", "Rotate encryption key (DEK)?")}
        description={t("secrets.rotateDekConfirmDesc", {
          tenant: tenantId,
          count: secretCount,
          defaultValue: `This generates a new data encryption key for tenant "${tenantId}" and re-encrypts its ${secretCount} secret(s). This is safe and takes effect immediately — no restart is required.`,
        })}
        confirmLabel={t("secrets.rotateDekConfirm", "Rotate DEK now")}
        cancelLabel={t("common.cancel", "Cancel")}
        onConfirm={handleRotateDek}
        isPending={rotateDekMut.isPending}
      />

      {/* ─── Rotate KEK dialog ─── */}
      <AlertDialog
        open={showRotateKek}
        onOpenChange={(open) => {
          setShowRotateKek(open);
          if (!open) {
            setKekOldKey("");
            setKekNewKey("");
            setKekOldVisible(false);
            setKekNewVisible(false);
          }
        }}
        variant="warning"
        title={t("secrets.rotateKekConfirmTitle", "Rotate master key (KEK)?")}
        description={t(
          "secrets.rotateKekConfirmDesc",
          "Re-encrypts every tenant's data encryption key with a new master key. The secret values themselves are unchanged.",
        )}
        confirmLabel={t("secrets.rotateKekConfirm", "Rotate master key now")}
        cancelLabel={t("common.cancel", "Cancel")}
        onConfirm={handleRotateKek}
        isPending={rotateKekMut.isPending}
        confirmDisabled={!kekValid}
      >
        <div className="space-y-3">
          <div className="flex items-start gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
            <p className="text-xs text-amber-800 dark:text-amber-200">
              {t(
                "secrets.kekTlsWarning",
                "Both keys are sent in the request body. Only perform this over a TLS (HTTPS) connection so the keys are not exposed in transit.",
              )}
            </p>
          </div>
          <div>
            <label
              htmlFor="kek-old-key"
              className="mb-1.5 block text-xs font-medium text-muted-foreground"
            >
              {t("secrets.kekOldKeyLabel", "Current master key")}
            </label>
            <div className="relative">
              <input
                id="kek-old-key"
                type={kekOldVisible ? "text" : "password"}
                value={kekOldKey}
                onChange={(e) => setKekOldKey(e.target.value)}
                placeholder={t("secrets.kekOldKeyPlaceholder", "Current EDDI_VAULT_MASTER_KEY")}
                className="h-9 w-full rounded-lg border border-input bg-background pe-10 ps-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                data-testid="kek-old-key-input"
                autoComplete="off"
              />
              <button
                type="button"
                onClick={() => setKekOldVisible(!kekOldVisible)}
                className="absolute inset-e-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                aria-label={
                  kekOldVisible
                    ? t("secrets.hideValue", "Hide value")
                    : t("secrets.showValue", "Show value")
                }
                data-testid="kek-old-key-eye"
              >
                {kekOldVisible ? <Eye className="h-4 w-4" /> : <EyeOff className="h-4 w-4" />}
              </button>
            </div>
          </div>
          <div>
            <label
              htmlFor="kek-new-key"
              className="mb-1.5 block text-xs font-medium text-muted-foreground"
            >
              {t("secrets.kekNewKeyLabel", "New master key")}
            </label>
            <div className="relative">
              <input
                id="kek-new-key"
                type={kekNewVisible ? "text" : "password"}
                value={kekNewKey}
                onChange={(e) => setKekNewKey(e.target.value)}
                placeholder={t("secrets.kekNewKeyPlaceholder", "New key — at least 8 characters")}
                className="h-9 w-full rounded-lg border border-input bg-background pe-10 ps-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                data-testid="kek-new-key-input"
                autoComplete="new-password"
              />
              <button
                type="button"
                onClick={() => setKekNewVisible(!kekNewVisible)}
                className="absolute inset-e-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                aria-label={
                  kekNewVisible
                    ? t("secrets.hideValue", "Hide value")
                    : t("secrets.showValue", "Show value")
                }
                data-testid="kek-new-key-eye"
              >
                {kekNewVisible ? <Eye className="h-4 w-4" /> : <EyeOff className="h-4 w-4" />}
              </button>
            </div>
            {kekNewKey.length > 0 && kekNewKey.length < 8 && (
              <p className="mt-1 text-xs text-destructive" data-testid="kek-new-key-error">
                {t("secrets.kekNewKeyTooShort", "New master key must be at least 8 characters.")}
              </p>
            )}
          </div>
          <p className="text-xs text-muted-foreground">
            {t(
              "secrets.kekRestartHint",
              "After rotation you must update EDDI_VAULT_MASTER_KEY to the new key and restart EDDI.",
            )}
          </p>
        </div>
      </AlertDialog>

      {/* ─── Reset tenant dialog (type-to-confirm) ─── */}
      <AlertDialog
        open={showResetTenant}
        onOpenChange={(open) => {
          setShowResetTenant(open);
          if (!open) setResetConfirm("");
        }}
        variant="destructive"
        title={t("secrets.resetTenantConfirmTitle", "Reset vault for this tenant?")}
        description={t("secrets.resetTenantConfirmDesc", {
          tenant: tenantId,
          count: secretCount,
          defaultValue: `This permanently deletes all ${secretCount} secret(s) and the data encryption key for tenant "${tenantId}". Use this as the recovery step when the master key was lost or changed and the old key is unavailable. This cannot be undone.`,
        })}
        confirmLabel={t("secrets.resetTenantConfirm", "Reset vault permanently")}
        cancelLabel={t("common.cancel", "Cancel")}
        onConfirm={handleResetTenant}
        isPending={resetTenantMut.isPending}
        confirmDisabled={resetConfirm !== tenantId}
      >
        <div className="space-y-2">
          <div className="flex items-center gap-2 rounded-lg bg-destructive/10 px-3 py-2 text-xs text-destructive">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            <span data-testid="reset-secret-count">
              {t("secrets.resetSecretCount", {
                count: secretCount,
                defaultValue: `${secretCount} secret(s) will be permanently lost`,
              })}
            </span>
          </div>
          <label
            htmlFor="reset-confirm"
            className="block text-xs font-medium text-muted-foreground"
          >
            {t("secrets.resetConfirmLabel", {
              tenant: tenantId,
              defaultValue: `Type the tenant name "${tenantId}" to confirm`,
            })}
          </label>
          <input
            id="reset-confirm"
            type="text"
            value={resetConfirm}
            onChange={(e) => setResetConfirm(e.target.value)}
            placeholder={tenantId}
            className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-destructive/50"
            data-testid="reset-confirm-input"
            autoComplete="off"
          />
        </div>
      </AlertDialog>

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
                data-testid="close-create-dialog"
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
                    value={newAgentInput}
                    onChange={(e) => setNewAgentInput(e.target.value)}
                    placeholder={t("secrets.allowedAgentsPlaceholder", "Type agent ID and press Enter (empty = all agents)")}
                    className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                    data-testid="new-allowed-agents-input"
                    autoComplete="off"
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === ",") {
                        e.preventDefault();
                        const val = newAgentInput.trim().replace(/,$/,"");
                        if (val && !newAllowedAgents.includes(val)) {
                          setNewAllowedAgents((prev) => [...prev, val]);
                        }
                        setNewAgentInput("");
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

      {/* ─── Rotate secret dialog ─── */}
      {rotateTarget && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
          onClick={() => setRotateTarget(null)}
          onKeyDown={(e) => {
            if (e.key === "Escape") setRotateTarget(null);
          }}
        >
          <div
            className="w-full max-w-sm rounded-xl border border-border bg-card p-6 shadow-2xl"
            onClick={(e) => e.stopPropagation()}
            role="dialog"
            aria-modal="true"
            aria-labelledby="rotate-secret-title"
          >
            <h2
              id="rotate-secret-title"
              className="flex items-center gap-2 text-lg font-semibold text-foreground"
            >
              <RefreshCw className="h-5 w-5 text-amber-500" />
              {t("secrets.rotateTitle", { key: rotateTarget.keyName, defaultValue: `Rotate "${rotateTarget.keyName}"` })}
            </h2>
            <p className="mt-2 text-sm text-muted-foreground">
              {t("secrets.rotateDesc", "Enter the new value. The existing references will continue to resolve to the updated value.")}
            </p>
            <div className="relative mt-4">
              <input
                type={rotateVisible ? "text" : "password"}
                value={rotateValue}
                onChange={(e) => setRotateValue(e.target.value)}
                placeholder={t("secrets.newValuePlaceholder", "New secret value…")}
                className="h-9 w-full rounded-lg border border-input bg-background pe-10 ps-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                data-testid="rotate-value-input"
                autoComplete="new-password"
                autoFocus
              />
              <button
                type="button"
                onClick={() => setRotateVisible(!rotateVisible)}
                className="absolute inset-e-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                data-testid="rotate-value-eye"
              >
                {rotateVisible ? <Eye className="h-4 w-4" /> : <EyeOff className="h-4 w-4" />}
              </button>
            </div>
            <div className="mt-6 flex justify-end gap-2">
              <button
                onClick={() => setRotateTarget(null)}
                className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted"
              >
                {t("common.cancel", "Cancel")}
              </button>
              <button
                onClick={() => {
                  if (!rotateValue.trim()) return;
                  rotateMut.mutate(
                    { tenantId, keyName: rotateTarget.keyName, newValue: rotateValue.trim() },
                    {
                      onSuccess: () => {
                        toast.success(t("secrets.rotateSuccess", { key: rotateTarget.keyName, defaultValue: `Secret "${rotateTarget.keyName}" rotated` }));
                        setRotateTarget(null);
                        setRotateValue("");
                      },
                      onError: (err) => toast.error(err.message),
                    },
                  );
                }}
                disabled={!rotateValue.trim() || rotateMut.isPending}
                className="inline-flex items-center gap-2 rounded-lg bg-amber-500 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-amber-600 disabled:opacity-50"
                data-testid="confirm-rotate-button"
              >
                {rotateMut.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
                {t("secrets.rotate", "Rotate")}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
