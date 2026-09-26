import { useEffect, useState, type InputHTMLAttributes } from "react";
import { cn } from "@/lib/utils";

export interface RenamableKeyInputProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, "value" | "onChange"> {
  /** The key as it is stored. */
  value: string;
  /** Called once the edit is finished with a key that passed `isAvailable`. */
  onRename: (next: string) => void;
  /**
   * Whether `next` may be used. Typically "not already a key of this map".
   * An empty name is always refused.
   */
  isAvailable: (next: string) => boolean;
}

/**
 * The name half of a key/value row.
 *
 * Renaming on every keystroke re-keys the map while the user types, so an
 * intermediate spelling that collides with another key would either be
 * refused mid-word or overwrite that key's value. The edit is kept locally and
 * committed on blur or Enter; a name that is empty or already taken is marked
 * invalid and reverted instead of being written.
 */
export function RenamableKeyInput({
  value,
  onRename,
  isAvailable,
  className,
  readOnly,
  ...rest
}: RenamableKeyInputProps) {
  const [draft, setDraft] = useState(value);

  useEffect(() => setDraft(value), [value]);

  // Compared untrimmed: a stored key with surrounding whitespace must not be
  // rewritten just because the field was focused and left.
  const edited = draft !== value;
  const trimmed = draft.trim();
  const invalid =
    edited && (trimmed === "" || (trimmed !== value && !isAvailable(trimmed)));

  const commit = () => {
    if (!edited) return;
    if (invalid || trimmed === value) {
      setDraft(value);
      return;
    }
    onRename(trimmed);
  };

  return (
    <input
      {...rest}
      type="text"
      value={draft}
      readOnly={readOnly}
      aria-invalid={invalid || undefined}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === "Enter") {
          // The Enter that confirms an IME composition is not a commit. Some
          // browsers fire compositionend before that keydown, so isComposing
          // is already false there; keyCode 229 still marks it.
          if (e.nativeEvent.isComposing || e.nativeEvent.keyCode === 229) return;
          e.preventDefault();
          commit();
        } else if (e.key === "Escape") {
          setDraft(value);
        }
      }}
      className={cn(className, invalid && "border-destructive focus:ring-destructive")}
    />
  );
}
