/* ──────────────────────────────────────────────
   SecretInput — Password input field for secret values
   Rendered when the backend sends an InputFieldOutputItem
   with subType: "password".
   ────────────────────────────────────────────── */

import { useState, useCallback, type KeyboardEvent } from "react";
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

  const handleSubmit = useCallback(() => {
    const trimmed = value.trim();
    if (!trimmed || disabled) return;
    onSend(trimmed, true);
    setValue("");
    dispatch({ type: "CLEAR_INPUT_FIELD" });
  }, [value, disabled, onSend, dispatch]);

  const handleKeyDown = useCallback(
    (e: KeyboardEvent<HTMLInputElement>) => {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        handleSubmit();
      }
    },
    [handleSubmit],
  );

  // Determine input type: use subType (e.g. "password", "email") or "text"
  const inputType = visible ? "text" : (subType || "password");

  return (
    <div className="secret-input" data-testid="secret-input">
      {label && (
        <label className="secret-input__label" data-testid="secret-input-label">
          🔒 {label}
        </label>
      )}
      <div className="secret-input__row">
        <div className="secret-input__field-wrapper">
          <input
            type={inputType}
            className="secret-input__field"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={placeholder || "Enter secret value..."}
            disabled={disabled}
            autoFocus
            autoComplete="off"
            data-testid="secret-input-field"
          />
          <button
            type="button"
            className="secret-input__eye-toggle"
            onClick={() => setVisible((v) => !v)}
            title={visible ? "Hide" : "Show"}
            data-testid="secret-input-eye"
          >
            {visible ? "👁" : "👁‍🗨"}
          </button>
        </div>
        <button
          type="button"
          className={`chat-input__send ${value.trim() && !disabled ? "chat-input__send--active" : "chat-input__send--disabled"}`}
          onClick={handleSubmit}
          disabled={!value.trim() || disabled}
          data-testid="secret-input-send"
        >
          ▶
        </button>
      </div>
    </div>
  );
}
