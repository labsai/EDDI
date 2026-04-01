import { useState, useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { KeyRound, Eye, EyeOff, ChevronDown, AlertTriangle, Lock, Plus, X, Loader2 } from "lucide-react";
import { useSecrets, useStoreSecret, useVaultHealth } from "@/hooks/use-secrets";
import { toast } from "sonner";
import { createPortal } from "react-dom";

// ─── Types ───────────────────────────────────────────────────────────────────

interface SecretKeyPickerProps {
  /** Current value — plain text or `vault:<keyName>` */
  value: string;
  /** Callback when value changes */
  onChange: (value: string) => void;
  /** Whether the field is read-only */
  readOnly?: boolean;
  /** Vault tenant ID (default: "default") */
  tenantId?: string;
  /** Placeholder text for the plain-text input */
  placeholder?: string;
  /** data-testid attribute */
  testId?: string;
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

const VAULT_PREFIX = "vault:";
const VAULT_EXPR_PREFIX = "${vault:";

/** Check if a value is a vault reference (either `vault:key` or `${vault:key}`) */
function isVaultRef(value: string): boolean {
  return value.startsWith(VAULT_PREFIX) || value.startsWith(VAULT_EXPR_PREFIX);
}

/** Extract the key name from a vault reference */
function extractVaultKey(value: string): string {
  if (value.startsWith(VAULT_EXPR_PREFIX)) {
    return value.slice(VAULT_EXPR_PREFIX.length, value.endsWith("}") ? -1 : undefined);
  }
  if (value.startsWith(VAULT_PREFIX)) {
    return value.slice(VAULT_PREFIX.length);
  }
  return value;
}

/** Create a vault reference string in the `${vault:...}` format */
function toVaultRef(keyName: string): string {
  return `\${vault:${keyName}}`;
}

// ─── Sub-Component ───────────────────────────────────────────────────────────

function CreateSecretModal({
  isOpen,
  onClose,
  tenantId,
  onSuccess,
}: {
  isOpen: boolean;
  onClose: () => void;
  tenantId: string;
  onSuccess: (keyName: string) => void;
}) {
  const { t } = useTranslation();
  const [newKeyName, setNewKeyName] = useState("");
  const [newValue, setNewValue] = useState("");
  const [newDescription, setNewDescription] = useState("");
  const [valueVisible, setValueVisible] = useState(false);
  const storeMut = useStoreSecret();

  // Reset form state whenever the modal closes (security: clear secret value from memory)
  const resetForm = useCallback(() => {
    setNewKeyName("");
    setNewValue("");
    setNewDescription("");
    setValueVisible(false);
  }, []);

  const handleClose = useCallback(() => {
    resetForm();
    onClose();
  }, [resetForm, onClose]);

  if (!isOpen) return null;

  const handleCreate = () => {
    if (!newKeyName.trim() || !newValue.trim()) return;
    storeMut.mutate(
      {
        tenantId,
        keyName: newKeyName.trim(),
        value: newValue.trim(),
        description: newDescription.trim() || undefined,
      },
      {
        onSuccess: () => {
          toast.success(
            t("secrets.storeSuccess", {
              key: newKeyName.trim(),
              defaultValue: `Secret "${newKeyName.trim()}" stored`,
            })
          );
          const savedKey = newKeyName.trim();
          resetForm();
          onSuccess(savedKey);
          onClose();
        },
        onError: (err) =>
          toast.error(err instanceof Error ? err.message : String(err)),
      }
    );
  };

  const modalContent = (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={handleClose}
      onKeyDown={(e) => {
        if (e.key === "Escape") handleClose();
      }}
    >
      <div
        className="w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-2xl"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
      >
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold text-foreground">
            {t("secrets.createTitle", "Add Secret")}
          </h2>
          <button
            onClick={handleClose}
            className="rounded-lg p-1.5 text-muted-foreground hover:bg-muted transition-colors"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="mt-4 space-y-4">
          <div>
            <label className="mb-1.5 block text-xs font-medium text-muted-foreground">
              {t("secrets.keyNameLabel", "Key Name")}
            </label>
            <input
              type="text"
              value={newKeyName}
              onChange={(e) => setNewKeyName(e.target.value)}
              placeholder={t("secrets.keyNamePlaceholder", "e.g. openaiKey")}
              className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
              autoFocus
              autoComplete="off"
            />
          </div>
          <div>
            <label className="mb-1.5 block text-xs font-medium text-muted-foreground">
              {t("secrets.valueLabel", "Secret Value")}
            </label>
            <div className="relative">
              <input
                type={valueVisible ? "text" : "password"}
                value={newValue}
                onChange={(e) => setNewValue(e.target.value)}
                placeholder={t("secrets.valuePlaceholder", "Enter secret value…")}
                className="h-9 w-full rounded-lg border border-input bg-background pe-10 ps-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
                autoComplete="off"
              />
              <button
                type="button"
                onClick={() => setValueVisible(!valueVisible)}
                className="absolute inset-e-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              >
                {valueVisible ? <Eye className="h-4 w-4" /> : <EyeOff className="h-4 w-4" />}
              </button>
            </div>
          </div>
          <div>
            <label className="mb-1.5 block text-xs font-medium text-muted-foreground">
              {t("secrets.descriptionLabel", "Description (optional)")}
            </label>
            <input
              type="text"
              value={newDescription}
              onChange={(e) => setNewDescription(e.target.value)}
              className="h-9 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/50"
              autoComplete="off"
            />
          </div>
        </div>

        <div className="mt-6 flex justify-end gap-2">
          <button
            onClick={handleClose}
            className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-muted"
          >
            {t("common.cancel", "Cancel")}
          </button>
          <button
            onClick={handleCreate}
            disabled={!newKeyName.trim() || !newValue.trim() || storeMut.isPending}
            className="inline-flex items-center gap-2 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90 disabled:opacity-50"
          >
            {storeMut.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
            {t("secrets.store", "Store Secret")}
          </button>
        </div>
      </div>
    </div>
  );

  return createPortal(modalContent, document.body);
}

// ─── Component ───────────────────────────────────────────────────────────────

/**
 * A combined password input / vault key selector.
 *
 * Two modes:
 * - **Direct**: password input with show/hide toggle
 * - **Vault**: dropdown listing secret keys from the vault
 *
 * When a vault key is selected → value becomes `${vault:<keyName>}`.
 * When a value starts with `vault:` or `${vault:` → auto-switches to vault mode.
 */
export function SecretKeyPicker({
  value,
  onChange,
  readOnly,
  tenantId = "default",
  placeholder,
  testId = "secret-key-picker",
}: SecretKeyPickerProps) {
  const { t } = useTranslation();

  // Auto-detect mode from initial value
  const [isVaultMode, setIsVaultMode] = useState(() => isVaultRef(value));
  const [showPassword, setShowPassword] = useState(false);
  const [showCreateDialog, setShowCreateDialog] = useState(false);

  // Fetch vault data
  const { data: secrets, isLoading: secretsLoading } = useSecrets(tenantId);
  const { data: vaultHealth } = useVaultHealth();

  const vaultAvailable = vaultHealth?.available !== false;
  const secretKeys = useMemo(
    () => secrets?.map((s) => s.keyName).sort() ?? [],
    [secrets],
  );

  // Current vault key (if in vault mode)
  const currentVaultKey = useMemo(
    () => (isVaultRef(value) ? extractVaultKey(value) : ""),
    [value],
  );

  const handleToggleMode = useCallback(() => {
    if (readOnly) return;
    setIsVaultMode((prev) => {
      if (!prev) {
        // Switching to vault mode — only allowed if vault is available
        if (!vaultAvailable) return prev;
        onChange("");
      } else {
        // Switching to direct mode — always allowed
        onChange("");
      }
      return !prev;
    });
  }, [readOnly, onChange, vaultAvailable]);

  const handleVaultSelect = useCallback(
    (keyName: string) => {
      if (keyName) {
        onChange(toVaultRef(keyName));
      } else {
        onChange("");
      }
    },
    [onChange],
  );

  const handleDirectChange = useCallback(
    (newValue: string) => {
      // Auto-detect if user manually types a vault reference
      if (isVaultRef(newValue)) {
        setIsVaultMode(true);
      }
      onChange(newValue);
    },
    [onChange],
  );

  return (
    <div className="relative flex items-center gap-0" data-testid={testId}>
      {/* Vault mode toggle button */}
      <button
        type="button"
        onClick={handleToggleMode}
        disabled={readOnly || (!isVaultMode && !vaultAvailable)}
        title={
          isVaultMode
            ? t("secretPicker.switchToDirect", "Switch to direct value")
            : vaultAvailable
              ? t("secretPicker.switchToVault", "Pick from vault")
              : t("secretPicker.vaultDown", "Vault is not configured — set up a secret provider in the EDDI backend")
        }
        className={`flex h-7 items-center gap-1 rounded-s-md border border-e-0 px-1.5 text-[10px] font-medium transition-colors ${
          isVaultMode
            ? "border-amber-500/50 bg-amber-500/10 text-amber-600 dark:text-amber-400"
            : "border-input bg-muted text-muted-foreground hover:text-foreground"
        } ${readOnly || (!isVaultMode && !vaultAvailable) ? "opacity-60 cursor-default" : "hover:bg-muted/80"}`}
        data-testid={`${testId}-toggle`}
      >
        {isVaultMode ? (
          <KeyRound className="h-3 w-3" />
        ) : (
          <Lock className="h-3 w-3" />
        )}
      </button>

      {isVaultMode ? (
        /* ─── Vault dropdown ─── */
        <div className="relative flex flex-1 items-stretch">
          <div className="relative flex-1">
            <select
              value={currentVaultKey}
              onChange={(e) => handleVaultSelect(e.target.value)}
              disabled={readOnly || !vaultAvailable}
              className={`h-7 w-full appearance-none rounded-none border bg-background pe-7 ps-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60 ${
                isVaultRef(value)
                  ? "border-amber-500/50"
                  : "border-input"
              }`}
              data-testid={`${testId}-vault-select`}
            >
              <option value="">
                {secretsLoading
                  ? t("secretPicker.loading", "Loading keys…")
                  : vaultAvailable
                    ? t("secretPicker.selectKey", "Select vault key…")
                    : t("secretPicker.vaultUnavailable", "Vault unavailable")}
              </option>
              {/* Include current key if not in fetched secrets */}
              {currentVaultKey && !secretKeys.includes(currentVaultKey) && (
                <option key={currentVaultKey} value={currentVaultKey}>
                  {currentVaultKey}
                </option>
              )}
              {secretKeys.map((key) => (
                <option key={key} value={key}>
                  {key}
                </option>
              ))}
            </select>
            <ChevronDown className="pointer-events-none absolute inset-e-1.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-muted-foreground" />
            {!vaultAvailable && (
              <span
                className="absolute inset-e-6 top-1/2 -translate-y-1/2"
                title={t("secretPicker.vaultDown", "Vault is not configured — set up a secret provider in the EDDI backend")}
              >
                <AlertTriangle className="h-3 w-3 text-amber-500" />
              </span>
            )}
          </div>
          <button
            type="button"
            onClick={() => setShowCreateDialog(true)}
            disabled={readOnly || !vaultAvailable}
            title={t("secrets.create", "Add Secret")}
            className="flex h-7 items-center justify-center rounded-e-md border border-s-0 border-input bg-muted px-1.5 text-muted-foreground transition-colors hover:bg-muted/80 hover:text-foreground disabled:opacity-60"
          >
            <Plus className="h-3 w-3" />
          </button>
        </div>
      ) : (
        /* ─── Direct value input ─── */
        <div className="relative flex flex-1 items-stretch">
          <div className="relative flex-1">
            <input
              type={showPassword ? "text" : "password"}
              value={value}
              onChange={(e) => handleDirectChange(e.target.value)}
              readOnly={readOnly}
              placeholder={placeholder ?? t("secretPicker.placeholder", "API key or ${vault:key-name}")}
              dir="ltr"
              className={`h-7 w-full border border-input bg-background pe-7 ps-2 font-mono text-xs text-foreground placeholder:text-muted-foreground/60 focus:outline-none focus:ring-1 focus:ring-ring ${vaultAvailable && !readOnly ? "rounded-none" : "rounded-e-md"}`}
              data-testid={`${testId}-input`}
            />
            <button
              type="button"
              onClick={() => setShowPassword((p) => !p)}
              disabled={readOnly}
              className="absolute inset-e-1.5 top-1/2 -translate-y-1/2 rounded p-0.5 text-muted-foreground hover:text-foreground transition-colors"
              tabIndex={-1}
              aria-label={showPassword ? "Hide" : "Show"}
            >
              {showPassword ? (
                <EyeOff className="h-3 w-3" />
              ) : (
                <Eye className="h-3 w-3" />
              )}
            </button>
          </div>
          {/* Ad-hoc create secret button — available when vault is up */}
          {vaultAvailable && !readOnly && (
            <button
              type="button"
              onClick={() => setShowCreateDialog(true)}
              title={t("secrets.create", "Add Secret")}
              className="flex h-7 items-center justify-center rounded-e-md border border-s-0 border-input bg-muted px-1.5 text-muted-foreground transition-colors hover:bg-muted/80 hover:text-foreground"
              data-testid={`${testId}-create`}
            >
              <Plus className="h-3 w-3" />
            </button>
          )}
        </div>
      )}
      
      {showCreateDialog && (
        <CreateSecretModal
          isOpen={showCreateDialog}
          onClose={() => setShowCreateDialog(false)}
          tenantId={tenantId}
          onSuccess={(keyName) => {
            setIsVaultMode(true); // Switch to vault mode to show selected key
            onChange(toVaultRef(keyName));
          }}
        />
      )}
    </div>
  );
}
