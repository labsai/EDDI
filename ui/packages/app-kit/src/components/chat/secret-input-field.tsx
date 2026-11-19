import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import { Eye, EyeOff, Lock, Send } from "lucide-react";
import { inputFieldType, isSecretInputField } from "@eddi/conversation";
import { cn } from "../../lib/utils";
import { isImeComposing } from "../../lib/ime";

/**
 * The input a backend `inputField` output item asks for — the composer's
 * "field" mode (`resolveComposerMode` in `@eddi/conversation`, T2.5).
 *
 * Shared by the chat panel and the test-chat drawer. It used to live inside the
 * panel only, so the drawer — which reads the same store — never rendered it:
 * an agent that asked for an API key there got it typed into the ordinary
 * textarea, shown in clear and sent without the `secretInput` flag, so the
 * backend neither masked it in the transcript nor redacted it from the audit
 * ledger.
 *
 * Only a `password` field is a secret: masked, sent as typed (whitespace is
 * valid in a password) and flagged `secretInput`. A `text` or `email` field is
 * an ordinary field of that type and an ordinary turn — it used to be masked
 * and vaulted as though it were a credential, as the widget's once was.
 *
 * `onSend` gets the value and whether it is a secret. Returning `false` says it
 * was not sent, and the value stays in the field. `onCancel` leaves the field
 * for the ordinary composer: without it the request traps the user until they
 * send something.
 */
export function SecretInputField({
  label,
  placeholder,
  defaultValue = "",
  subType = "password",
  onSend,
  onCancel,
  disabled = false,
  compact = false,
}: {
  label?: string;
  placeholder?: string;
  defaultValue?: string;
  subType?: string;
  onSend: (value: string, isSecret: boolean) => boolean | void;
  onCancel?: () => void;
  disabled?: boolean;
  /** Drawer sizing: tighter padding and a smaller send button. */
  compact?: boolean;
}) {
  const { t } = useTranslation();
  const [value, setValue] = useState(defaultValue);
  const [visible, setVisible] = useState(false);
  const inputId = useId();
  const isSecret = isSecretInputField(subType);
  const placeholderText =
    placeholder || (isSecret ? t("chat.secretPlaceholder", "Enter secret value...") : "");

  // A secret goes as typed; an ordinary field is trimmed like any message.
  const outgoing = isSecret ? value : value.trim();
  const canSend = outgoing.length > 0 && !disabled;

  const handleSubmit = () => {
    if (!canSend) return;
    if (onSend(outgoing, isSecret) === false) return;
    setValue("");
  };

  const inputType = isSecret && visible ? "text" : inputFieldType(subType);

  return (
    <div className={cn("border-t border-border bg-background", compact ? "p-3 shrink-0" : "p-4")}>
      {label && (
        <label
          htmlFor={inputId}
          className="mb-2 flex items-center gap-1.5 text-sm font-medium text-primary"
          data-testid="secret-input-label"
        >
          {isSecret && <Lock className="h-3.5 w-3.5" aria-hidden="true" />}
          {label}
        </label>
      )}
      <div className="flex items-end gap-2">
        <div className="relative flex-1">
          <input
            id={inputId}
            // The visible label names the field when the agent sent one; the
            // placeholder is only a hint, so without a label it is repeated as
            // the accessible name rather than leaving the field unnamed.
            aria-label={label ? undefined : placeholderText || t("conversation.input.label")}
            type={inputType}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey && !isImeComposing(e)) {
                e.preventDefault();
                handleSubmit();
              }
            }}
            placeholder={placeholderText}
            disabled={disabled}
            autoFocus
            autoComplete={isSecret ? "off" : undefined}
            className={cn(
              "w-full rounded-xl border border-primary/60 bg-card text-sm",
              isSecret && "pe-10",
              compact ? "px-3 py-2.5" : "px-4 py-3",
              "placeholder:text-muted-foreground",
              "focus:outline-none focus:ring-2 focus:ring-primary/30",
              "disabled:cursor-not-allowed disabled:opacity-50"
            )}
            data-testid="secret-input-field"
          />
          {isSecret && (
            <button
              type="button"
              onClick={() => setVisible(!visible)}
              className="absolute inset-e-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
              title={visible ? t("chat.hide", "Hide") : t("chat.show", "Show")}
              aria-label={visible ? t("chat.hide", "Hide") : t("chat.show", "Show")}
              aria-pressed={visible}
              data-testid="secret-input-eye"
            >
              {visible ? <Eye className="h-4 w-4" /> : <EyeOff className="h-4 w-4" />}
            </button>
          )}
        </div>
        <button
          type="button"
          onClick={handleSubmit}
          disabled={!canSend}
          className={cn(
            "flex shrink-0 items-center justify-center rounded-xl transition-colors",
            compact ? "h-10 w-10" : "h-11 w-11",
            canSend
              ? "bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer"
              : "bg-muted text-muted-foreground cursor-not-allowed"
          )}
          aria-label={t("chat.send")}
          data-testid="secret-input-send"
        >
          <Send className={compact ? "h-4 w-4" : "h-5 w-5"} />
        </button>
      </div>
      {onCancel && (
        <button
          type="button"
          onClick={onCancel}
          className="mt-2 text-xs text-muted-foreground underline-offset-2 hover:text-foreground hover:underline"
          data-testid="secret-input-cancel"
        >
          {t("conversation.input.typeInstead")}
        </button>
      )}
    </div>
  );
}
