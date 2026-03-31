import { useState, useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { KeyRound, Eye, EyeOff, ChevronDown, AlertTriangle, Lock } from "lucide-react";
import { useSecrets, useVaultHealth } from "@/hooks/use-secrets";

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
        // Switching to vault mode — clear value to let user pick
        onChange("");
      } else {
        // Switching to direct mode — clear vault ref
        onChange("");
      }
      return !prev;
    });
  }, [readOnly, onChange]);

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
        disabled={readOnly}
        title={
          isVaultMode
            ? t("secretPicker.switchToDirect", "Switch to direct value")
            : t("secretPicker.switchToVault", "Pick from vault")
        }
        className={`flex h-7 items-center gap-1 rounded-s-md border border-e-0 px-1.5 text-[10px] font-medium transition-colors ${
          isVaultMode
            ? "border-amber-500/50 bg-amber-500/10 text-amber-600 dark:text-amber-400"
            : "border-input bg-muted text-muted-foreground hover:text-foreground"
        } ${readOnly ? "opacity-60 cursor-default" : "hover:bg-muted/80"}`}
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
        <div className="relative flex-1">
          <select
            value={currentVaultKey}
            onChange={(e) => handleVaultSelect(e.target.value)}
            disabled={readOnly || !vaultAvailable}
            className={`h-7 w-full appearance-none rounded-e-md border bg-background pe-7 ps-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60 ${
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
      ) : (
        /* ─── Direct value input ─── */
        <div className="relative flex-1">
          <input
            type={showPassword ? "text" : "password"}
            value={value}
            onChange={(e) => handleDirectChange(e.target.value)}
            readOnly={readOnly}
            placeholder={placeholder ?? t("secretPicker.placeholder", "API key or ${vault:key-name}")}
            dir="ltr"
            className="h-7 w-full rounded-e-md border border-input bg-background pe-7 ps-2 font-mono text-xs text-foreground placeholder:text-muted-foreground/60 focus:outline-none focus:ring-1 focus:ring-ring"
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
      )}
    </div>
  );
}
