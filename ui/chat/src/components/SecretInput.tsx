/* ──────────────────────────────────────────────
   SecretInput — the input field an agent requested
   Rendered when the backend sends an InputFieldOutputItem. Only a
   "password" field is masked and sent as secret; "text" and "email"
   are ordinary fields of that type.
   ────────────────────────────────────────────── */

import { useState, useCallback, useId, type KeyboardEvent } from "react";
import { useChatDispatch } from "@/store/chat-store";

interface SecretInputProps {
  label?: string;
  placeholder?: string;
  defaultValue?: string;
  subType?: string;
  onSend: (message: string, isSecret: boolean) => void;
  disabled?: boolean;
}

export function SecretInput({
  label,
  placeholder,
  defaultValue = "",
  subType = "password",
  onSend,
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
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        handleSubmit();
      }
    },
    [handleSubmit],
  );

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
          {isSecret ? "🔒 " : ""}
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
            placeholder={placeholder || (isSecret ? "Enter secret value..." : "")}
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
              aria-label={visible ? "Hide secret" : "Show secret"}
              title={visible ? "Hide" : "Show"}
              data-testid="secret-input-eye"
            >
              {visible ? "👁" : "👁‍🗨"}
            </button>
          )}
        </div>
        <button
          type="button"
          className={`chat-input__send ${value && !disabled ? "chat-input__send--active" : "chat-input__send--disabled"}`}
          onClick={handleSubmit}
          disabled={!value || disabled}
          aria-label={isSecret ? "Send secret" : "Send"}
          data-testid="secret-input-send"
        >
          ▶
        </button>
      </div>
    </div>
  );
}
