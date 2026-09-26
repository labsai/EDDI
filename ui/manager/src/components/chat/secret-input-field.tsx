import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Eye, EyeOff, Lock, Send } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * The input a backend `inputField` output item asks for (e.g. a password).
 *
 * Shared by the chat panel and the test-chat drawer. It used to live inside the
 * panel only, so the drawer — which reads the same store — never rendered it:
 * an agent that asked for an API key there got it typed into the ordinary
 * textarea, shown in clear and sent without the `secretInput` flag, so the
 * backend neither masked it in the transcript nor redacted it from the audit
 * ledger.
 *
 * `onSend` is expected to send the value as a secret turn.
 */
export function SecretInputField({
  label,
  placeholder,
  defaultValue = "",
  subType = "password",
  onSend,
  disabled = false,
  compact = false,
}: {
  label?: string;
  placeholder?: string;
  defaultValue?: string;
  subType?: string;
  onSend: (value: string) => void;
  disabled?: boolean;
  /** Drawer sizing: tighter padding and a smaller send button. */
  compact?: boolean;
}) {
  const { t } = useTranslation();
  const [value, setValue] = useState(defaultValue);
  const [visible, setVisible] = useState(false);

  const handleSubmit = () => {
    const trimmed = value.trim();
    if (!trimmed || disabled) return;
    onSend(trimmed);
    setValue("");
  };

  const inputType = visible ? "text" : (subType || "password");

  return (
    <div className={cn("border-t border-border bg-background", compact ? "p-3 shrink-0" : "p-4")}>
      {label && (
        <div className="mb-2 flex items-center gap-1.5 text-sm font-medium text-primary" data-testid="secret-input-label">
          <Lock className="h-3.5 w-3.5" />
          {label}
        </div>
      )}
      <div className="flex items-end gap-2">
        <div className="relative flex-1">
          <input
            type={inputType}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                handleSubmit();
              }
            }}
            placeholder={placeholder || t("chat.secretPlaceholder", "Enter secret value...")}
            disabled={disabled}
            autoFocus
            autoComplete="off"
            className={cn(
              "w-full rounded-xl border border-primary/60 bg-card pe-10 text-sm",
              compact ? "px-3 py-2.5" : "px-4 py-3",
              "placeholder:text-muted-foreground",
              "focus:outline-none focus:ring-2 focus:ring-primary/30",
              "disabled:cursor-not-allowed disabled:opacity-50"
            )}
            data-testid="secret-input-field"
          />
          <button
            type="button"
            onClick={() => setVisible(!visible)}
            className="absolute inset-e-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
            title={visible ? t("chat.hide", "Hide") : t("chat.show", "Show")}
            data-testid="secret-input-eye"
          >
            {visible ? <Eye className="h-4 w-4" /> : <EyeOff className="h-4 w-4" />}
          </button>
        </div>
        <button
          onClick={handleSubmit}
          disabled={!value.trim() || disabled}
          className={cn(
            "flex shrink-0 items-center justify-center rounded-xl transition-colors",
            compact ? "h-10 w-10" : "h-11 w-11",
            value.trim() && !disabled
              ? "bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer"
              : "bg-muted text-muted-foreground cursor-not-allowed"
          )}
          aria-label={t("chat.send")}
          data-testid="secret-input-send"
        >
          <Send className={compact ? "h-4 w-4" : "h-5 w-5"} />
        </button>
      </div>
    </div>
  );
}
