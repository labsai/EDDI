/* ──────────────────────────────────────────────
   SecretInput — the input field an agent requested
   Rendered when the backend sends an InputFieldOutputItem. Only a
   "password" field is masked and sent as secret; "text" and "email"
   are ordinary fields of that type.
   ────────────────────────────────────────────── */

import { useState, useCallback, useId, type KeyboardEvent } from "react";
import { Eye, EyeOff, Lock, SendHorizontal } from "lucide-react";
import { useChatDispatch } from "@/store/chat-store";
import { isImeComposing } from "@/ime";
import { t } from "@/i18n";

interface SecretInputProps {
  label?: string;
  placeholder?: string;
  defaultValue?: string;
  subType?: string;
  onSend: (message: string, isSecret: boolean) => void;
  /**
   * Leave the requested field for the ordinary composer. Without it an agent
   * that asks for an input field traps the user: the composer is replaced for
   * as long as the request stands, and only sending a value ends it.
   */
  onCancel?: () => void;
  disabled?: boolean;
}

export function SecretInput({
  label,
  placeholder,
  defaultValue = "",
  subType = "password",
  onSend,
  onCancel,
  disabled = false,
}: SecretInputProps) {
  const dispatch = useChatDispatch();
  const [value, setValue] = useState(defaultValue);
  const [visible, setVisible] = useState(false);
  const inputId = useId();

  // Only a password field is a secret. Every subType used to be masked and
  // sent with `secretInput`, so an e-mail address was hidden from the user
  // typing it and vaulted by the backend as though it were a credential.
  const isSecret = (subType || "password") === "password";

  const handleSubmit = useCallback(() => {
    // Don't trim — leading/trailing whitespace is valid in passwords and tokens
    if (!value || disabled) return;
    onSend(value, isSecret);
    setValue("");
    dispatch({ type: "CLEAR_INPUT_FIELD" });
  }, [value, disabled, isSecret, onSend, dispatch]);

  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLInputElement>) => {
      // Enter that confirms an IME candidate must not submit the password.
      if (e.key === "Enter" && !e.shiftKey && !isImeComposing(e)) {
        e.preventDefault();
        handleSubmit();
      }
    },
    [handleSubmit],
  );

  const handleCancel = useCallback(() => {
    if (onCancel) onCancel();
    else dispatch({ type: "CLEAR_INPUT_FIELD" });
  }, [onCancel, dispatch]);

  const inputType = isSecret
    ? visible
      ? "text"
      : "password"
    : subType === "email"
      ? "email"
      : "text";

  return (
    <div className="secret-input" data-testid="secret-input">
      {label && (
        <label
          htmlFor={inputId}
          className="secret-input__label"
          data-testid="secret-input-label"
        >
          {isSecret && (
            <>
              <Lock className="secret-input__label-icon" size="1em" />{" "}
            </>
          )}
          {label}
        </label>
      )}
      <div className="secret-input__row">
        <div className="secret-input__field-wrapper">
          <input
            id={inputId}
            type={inputType}
            className="secret-input__field"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={placeholder || (isSecret ? t("input.secretPlaceholder") : "")}
            disabled={disabled}
            autoFocus
            autoComplete={isSecret ? "off" : undefined}
            data-testid="secret-input-field"
          />
          {isSecret && (
            <button
              type="button"
              className="secret-input__eye-toggle"
              onClick={() => setVisible((v) => !v)}
              aria-label={visible ? t("input.secretHide") : t("input.secretShow")}
              title={visible ? t("input.hide") : t("input.show")}
              data-testid="secret-input-eye"
            >
              {/* Shows the action, like the aria-label: an open eye reveals. */}
              {visible ? <EyeOff size="1em" /> : <Eye size="1em" />}
            </button>
          )}
        </div>
        <button
          type="button"
          className={`chat-input__send ${value && !disabled ? "chat-input__send--active" : "chat-input__send--disabled"}`}
          onClick={handleSubmit}
          disabled={!value || disabled}
          aria-label={isSecret ? t("input.sendSecret") : t("input.send")}
          data-testid="secret-input-send"
        >
          <SendHorizontal size="1em" />
        </button>
      </div>
      <button
        type="button"
        className="secret-input__cancel"
        onClick={handleCancel}
        data-testid="secret-input-cancel"
      >
        {t("input.typeInstead")}
      </button>
    </div>
  );
}
