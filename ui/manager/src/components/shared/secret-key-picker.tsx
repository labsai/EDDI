import { useState, useCallback, useMemo, useRef, useEffect } from "react";
import { useTranslation } from "react-i18next";
import {
  KeyRound,
  Eye,
  EyeOff,
  AlertTriangle,
  Plus,
  X,
  Loader2,
  Search,
  ChevronDown,
} from "lucide-react";
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

/** Canonical prefix (v6+) */
const VAULT_PREFIX = "vault:";
const VAULT_EXPR_PREFIX = "${vault:";

/** Legacy prefix — still accepted for backward compat when reading */
const LEGACY_VAULT_PREFIX = "eddivault:";
const LEGACY_VAULT_EXPR_PREFIX = "${eddivault:";

/** Check if a value is a vault reference (canonical or legacy prefix) */
function isVaultRef(value: string): boolean {
  return (
    value.startsWith(VAULT_PREFIX) ||
    value.startsWith(VAULT_EXPR_PREFIX) ||
    value.startsWith(LEGACY_VAULT_PREFIX) ||
    value.startsWith(LEGACY_VAULT_EXPR_PREFIX)
  );
}

/** Extract the key name from a vault reference (handles both canonical and legacy) */
function extractVaultKey(value: string): string {
  if (value.startsWith(VAULT_EXPR_PREFIX)) {
    return value.slice(
      VAULT_EXPR_PREFIX.length,
      value.endsWith("}") ? -1 : undefined,
    );
  }
  if (value.startsWith(LEGACY_VAULT_EXPR_PREFIX)) {
    return value.slice(
      LEGACY_VAULT_EXPR_PREFIX.length,
      value.endsWith("}") ? -1 : undefined,
    );
  }
  if (value.startsWith(VAULT_PREFIX)) {
    return value.slice(VAULT_PREFIX.length);
  }
  if (value.startsWith(LEGACY_VAULT_PREFIX)) {
    return value.slice(LEGACY_VAULT_PREFIX.length);
  }
  return value;
}

/** Create a vault reference string in the canonical `${vault:...}` format */
function toVaultRef(keyName: string): string {
  return `\${vault:${keyName}}`;
}

// ─── CreateSecretModal ───────────────────────────────────────────────────────

function CreateSecretModal({
  onClose,
  tenantId,
  onSuccess,
}: {
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

// ─── VaultPopup ──────────────────────────────────────────────────────────────

interface VaultPopupProps {
  secrets: { keyName: string; description: string | null }[];
  secretsLoading: boolean;
  vaultAvailable: boolean;
  filter: string;
  onFilterChange: (v: string) => void;
  highlightedIndex: number;
  onSelect: (keyName: string) => void;
  onCreate: () => void;
  onClose: () => void;
  vaultError?: string;
}

function VaultPopup({
  secrets,
  secretsLoading,
  vaultAvailable,
  filter,
  onFilterChange,
  highlightedIndex,
  onSelect,
  onCreate,
  onClose,
  vaultError,
}: VaultPopupProps) {
  const { t } = useTranslation();
  const filterRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  // Auto-focus the filter input on open
  useEffect(() => {
    // Small delay so the popup renders before focusing
    const timer = setTimeout(() => filterRef.current?.focus(), 50);
    return () => clearTimeout(timer);
  }, []);

  // Scroll highlighted item into view
  useEffect(() => {
    if (highlightedIndex < 0 || !listRef.current) return;
    const items = listRef.current.querySelectorAll("[data-vault-item]");
    items[highlightedIndex]?.scrollIntoView({ block: "nearest" });
  }, [highlightedIndex]);

  const filtered = useMemo(() => {
    if (!filter.trim()) return secrets;
    const q = filter.toLowerCase();
    return secrets.filter(
      (s) =>
        s.keyName.toLowerCase().includes(q) ||
        (s.description ?? "").toLowerCase().includes(q),
    );
  }, [secrets, filter]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Escape") {
      e.preventDefault();
      onClose();
    } else if (e.key === "ArrowDown") {
      e.preventDefault();
      // Handled by parent — we need to bubble
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (highlightedIndex >= 0 && highlightedIndex < filtered.length) {
        onSelect(filtered[highlightedIndex]!.keyName);
      }
    }
  };

  return (
    <div
      className="absolute inset-x-0 top-full z-50 mt-1 overflow-hidden rounded-lg border border-border bg-popover shadow-xl animate-in fade-in-0 zoom-in-95 slide-in-from-top-2 duration-150"
      onKeyDown={handleKeyDown}
      data-testid="vault-popup"
    >
      {/* Search filter */}
      <div className="flex items-center gap-2 border-b border-border px-3 py-2">
        <Search className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
        <input
          ref={filterRef}
          type="text"
          value={filter}
          onChange={(e) => onFilterChange(e.target.value)}
          placeholder={t("secretPicker.filterPlaceholder", "Search vault keys…")}
          className="h-6 flex-1 border-none bg-transparent text-xs text-foreground placeholder:text-muted-foreground/60 focus:outline-none"
          autoComplete="off"
          data-testid="vault-popup-filter"
        />
      </div>

      {/* Key list */}
      <div ref={listRef} className="max-h-48 overflow-y-auto">
        {!vaultAvailable ? (
          <div className="flex items-center gap-2 px-3 py-3 text-xs text-muted-foreground">
            <AlertTriangle className="h-3.5 w-3.5 shrink-0 text-amber-500" />
            <span>
              {vaultError ||
                t(
                  "secretPicker.vaultDown",
                  "Vault is not configured — set up a secret provider in the EDDI backend",
                )}
            </span>
          </div>
        ) : secretsLoading ? (
          <div className="flex items-center justify-center gap-2 py-4 text-xs text-muted-foreground">
            <Loader2 className="h-3.5 w-3.5 animate-spin" />
            {t("secretPicker.loading", "Loading keys…")}
          </div>
        ) : filtered.length === 0 ? (
          <div className="px-3 py-3 text-center text-xs text-muted-foreground">
            {filter.trim()
              ? t("secretPicker.noMatch", 'No keys matching "{{filter}}"', {
                  filter,
                })
              : t("secretPicker.emptyVault", "No secrets stored yet")}
          </div>
        ) : (
          filtered.map((secret, idx) => (
            <button
              key={secret.keyName}
              type="button"
              data-vault-item
              onClick={() => onSelect(secret.keyName)}
              className={`flex w-full items-start gap-2 px-3 py-2 text-start text-xs transition-colors ${
                idx === highlightedIndex
                  ? "bg-primary/10 text-foreground"
                  : "text-foreground hover:bg-secondary/50"
              }`}
              data-testid={`vault-key-${secret.keyName}`}
            >
              <KeyRound className="mt-0.5 h-3 w-3 shrink-0 text-amber-500" />
              <div className="min-w-0 flex-1">
                <span className="block truncate font-mono font-medium">
                  {secret.keyName}
                </span>
                {secret.description && (
                  <span className="block truncate text-[10px] text-muted-foreground">
                    {secret.description}
                  </span>
                )}
              </div>
            </button>
          ))
        )}
      </div>

      {/* Create button */}
      {vaultAvailable && (
        <div className="border-t border-border">
          <button
            type="button"
            onClick={onCreate}
            className="flex w-full items-center gap-2 px-3 py-2 text-xs text-muted-foreground transition-colors hover:bg-secondary/50 hover:text-foreground"
            data-testid="vault-popup-create"
          >
            <Plus className="h-3 w-3" />
            {t("secretPicker.createNew", "Create new secret")}
          </button>
        </div>
      )}
    </div>
  );
}

// ─── SecretKeyPicker (main component) ────────────────────────────────────────

/**
 * Unified combobox for API keys & vault secrets.
 *
 * - **Direct value**: password input with show/hide toggle
 * - **Vault reference**: amber chip showing the resolved key name
 * - **Vault picker popup**: searchable list of existing vault keys with descriptions,
 *   keyboard navigation, and inline secret creation
 *
 * When a vault key is selected → value becomes `${vault:<keyName>}`.
 * When a value starts with `vault:` or `${vault:` (or legacy `eddivault:`) → auto-shows vault chip.
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

  // UI state
  const [showPassword, setShowPassword] = useState(false);
  const [popupOpen, setPopupOpen] = useState(false);
  const [filter, setFilter] = useState("");
  const [highlightedIndex, setHighlightedIndex] = useState(-1);
  const [showCreateDialog, setShowCreateDialog] = useState(false);

  // Refs
  const containerRef = useRef<HTMLDivElement>(null);

  // Vault data
  const { data: secrets, isLoading: secretsLoading } = useSecrets(tenantId);
  const { data: vaultHealth } = useVaultHealth();

  const vaultAvailable = vaultHealth?.available !== false;
  const vaultError = vaultHealth?.reason || vaultHealth?.error;

  const secretList = useMemo(
    () =>
      (secrets ?? [])
        .map((s) => ({ keyName: s.keyName, description: s.description }))
        .sort((a, b) => a.keyName.localeCompare(b.keyName)),
    [secrets],
  );

  const secretKeyNames = useMemo(
    () => new Set(secretList.map((s) => s.keyName)),
    [secretList],
  );

  // Derived state
  const hasVaultRef = isVaultRef(value);
  const currentVaultKey = hasVaultRef ? extractVaultKey(value) : "";
  const currentKeyExists = secretKeyNames.has(currentVaultKey);
  const currentDescription = hasVaultRef
    ? secretList.find((s) => s.keyName === currentVaultKey)?.description ?? null
    : null;

  // Filtered list for keyboard nav count
  const filteredForNav = useMemo(() => {
    if (!filter.trim()) return secretList;
    const q = filter.toLowerCase();
    return secretList.filter(
      (s) =>
        s.keyName.toLowerCase().includes(q) ||
        (s.description ?? "").toLowerCase().includes(q),
    );
  }, [secretList, filter]);

  // ─── Handlers ──────────────────────────────────────────────────────────────

  const openPopup = useCallback(() => {
    if (readOnly) return;
    setFilter("");
    setHighlightedIndex(-1);
    setPopupOpen(true);
  }, [readOnly]);

  const closePopup = useCallback(() => {
    setPopupOpen(false);
    setFilter("");
    setHighlightedIndex(-1);
  }, []);

  const handleSelectKey = useCallback(
    (keyName: string) => {
      onChange(toVaultRef(keyName));
      closePopup();
    },
    [onChange, closePopup],
  );

  const handleClearVault = useCallback(() => {
    if (readOnly) return;
    onChange("");
  }, [readOnly, onChange]);

  const handleDirectChange = useCallback(
    (newValue: string) => {
      onChange(newValue);
      // Auto-detect full vault reference paste
      if (isVaultRef(newValue) && newValue.endsWith("}")) {
        closePopup();
      }
    },
    [onChange, closePopup],
  );

  const handleInputKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (!popupOpen) {
        // Open popup on ArrowDown when closed
        if (e.key === "ArrowDown" && vaultAvailable) {
          e.preventDefault();
          openPopup();
        }
        return;
      }

      if (e.key === "Escape") {
        e.preventDefault();
        closePopup();
      } else if (e.key === "ArrowDown") {
        e.preventDefault();
        setHighlightedIndex((prev) =>
          prev < filteredForNav.length - 1 ? prev + 1 : 0,
        );
      } else if (e.key === "ArrowUp") {
        e.preventDefault();
        setHighlightedIndex((prev) =>
          prev > 0 ? prev - 1 : filteredForNav.length - 1,
        );
      } else if (e.key === "Enter") {
        e.preventDefault();
        if (
          highlightedIndex >= 0 &&
          highlightedIndex < filteredForNav.length
        ) {
          handleSelectKey(filteredForNav[highlightedIndex]!.keyName);
        }
      }
    },
    [
      popupOpen,
      vaultAvailable,
      openPopup,
      closePopup,
      filteredForNav,
      highlightedIndex,
      handleSelectKey,
    ],
  );

  // Click outside to close
  useEffect(() => {
    if (!popupOpen) return;
    const handleMouseDown = (e: MouseEvent) => {
      if (
        containerRef.current &&
        !containerRef.current.contains(e.target as Node)
      ) {
        closePopup();
      }
    };
    document.addEventListener("mousedown", handleMouseDown);
    return () => document.removeEventListener("mousedown", handleMouseDown);
  }, [popupOpen, closePopup]);

  // ─── Render ────────────────────────────────────────────────────────────────

  // State B: Vault reference selected — show chip
  if (hasVaultRef) {
    return (
      <div ref={containerRef} className="relative" data-testid={testId}>
        <div
          className={`flex h-7 items-center gap-1.5 rounded-md border px-2 ${
            readOnly
              ? "border-amber-500/30 bg-amber-500/5"
              : "border-amber-500/50 bg-amber-500/10"
          }`}
          title={
            currentDescription
              ? `${currentVaultKey} — ${currentDescription}`
              : currentVaultKey
          }
        >
          <KeyRound className="h-3 w-3 shrink-0 text-amber-600 dark:text-amber-400" />
          <span className="flex-1 truncate font-mono text-xs font-medium text-amber-700 dark:text-amber-300">
            {currentVaultKey}
          </span>
          {/* Warning if key not found in vault */}
          {!secretsLoading && !currentKeyExists && currentVaultKey && (
            <span
              title={t(
                "secretPicker.keyNotFound",
                "This key was not found in the vault",
              )}
            >
              <AlertTriangle className="h-3 w-3 shrink-0 text-amber-500" />
            </span>
          )}
          {/* Clear button */}
          {!readOnly && (
            <button
              type="button"
              onClick={handleClearVault}
              className="rounded p-0.5 text-amber-600/70 transition-colors hover:text-amber-700 dark:text-amber-400/70 dark:hover:text-amber-300"
              aria-label={t("secretPicker.clearVault", "Clear vault reference")}
              data-testid={`${testId}-clear`}
            >
              <X className="h-3 w-3" />
            </button>
          )}
        </div>
      </div>
    );
  }

  // State A: Empty or direct value — show input with vault opener
  return (
    <div ref={containerRef} className="relative" data-testid={testId}>
      <div className="flex items-stretch">
        {/* Password input */}
        <div className="relative flex-1">
          <input
            type={showPassword ? "text" : "password"}
            value={value}
            onChange={(e) => handleDirectChange(e.target.value)}
            onKeyDown={handleInputKeyDown}
            readOnly={readOnly}
            placeholder={
              placeholder ??
              t("secretPicker.placeholder", "API key or ${vault:key-name}")
            }
            dir="ltr"
            className={`h-7 w-full border border-input bg-background pe-14 ps-2 font-mono text-xs text-foreground placeholder:text-muted-foreground/60 focus:outline-none focus:ring-1 focus:ring-ring ${
              vaultAvailable && !readOnly
                ? "rounded-s-md rounded-e-none"
                : "rounded-md"
            }`}
            data-testid={`${testId}-input`}
          />
          {/* Eye toggle */}
          <button
            type="button"
            onClick={() => setShowPassword((p) => !p)}
            disabled={readOnly}
            className="absolute inset-e-1.5 top-1/2 -translate-y-1/2 rounded p-0.5 text-muted-foreground transition-colors hover:text-foreground"
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

        {/* Vault opener button */}
        {vaultAvailable && !readOnly && (
          <button
            type="button"
            onClick={() => (popupOpen ? closePopup() : openPopup())}
            title={t("secretPicker.pickFromVault", "Pick from vault")}
            className={`flex h-7 items-center gap-0.5 border border-s-0 border-input px-1.5 text-xs transition-colors ${
              popupOpen
                ? "rounded-e-md bg-primary/10 text-primary"
                : "rounded-e-md bg-muted text-muted-foreground hover:bg-muted/80 hover:text-foreground"
            }`}
            data-testid={`${testId}-vault-btn`}
          >
            <KeyRound className="h-3 w-3" />
            <ChevronDown
              className={`h-2.5 w-2.5 transition-transform ${popupOpen ? "rotate-180" : ""}`}
            />
          </button>
        )}
      </div>

      {/* Vault suggestion popup */}
      {popupOpen && (
        <VaultPopup
          secrets={secretList}
          secretsLoading={secretsLoading}
          vaultAvailable={vaultAvailable}
          filter={filter}
          onFilterChange={(v) => {
            setFilter(v);
            setHighlightedIndex(-1);
          }}
          highlightedIndex={highlightedIndex}
          onSelect={handleSelectKey}
          onCreate={() => {
            closePopup();
            setShowCreateDialog(true);
          }}
          onClose={closePopup}
          vaultError={vaultError}
        />
      )}

      {/* Create secret modal */}
      {showCreateDialog && (
        <CreateSecretModal
          onClose={() => setShowCreateDialog(false)}
          tenantId={tenantId}
          onSuccess={(keyName) => {
            onChange(toVaultRef(keyName));
          }}
        />
      )}
    </div>
  );
}
