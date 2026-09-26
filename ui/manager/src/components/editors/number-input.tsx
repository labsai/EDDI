import { useEffect, useRef, useState, type InputHTMLAttributes } from "react";

export interface NumberInputProps
  extends Omit<InputHTMLAttributes<HTMLInputElement>, "value" | "onChange" | "type"> {
  /** The stored value. `undefined` renders an empty field. */
  value: number | undefined;
  /** Called with the parsed number, or with `emptyValue` while the field is empty. */
  onChange: (value: number | undefined) => void;
  /**
   * What an empty (or unparseable) field stores. Leave it `undefined` for an
   * optional field whose absence means "use the server default".
   */
  emptyValue?: number;
  /** Parse with `parseInt` instead of `parseFloat`. */
  integer?: boolean;
}

function parse(raw: string, integer: boolean): number | undefined {
  if (raw.trim() === "") return undefined;
  const parsed = integer ? parseInt(raw, 10) : parseFloat(raw);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function format(value: number | undefined): string {
  return value === undefined || value === null ? "" : String(value);
}

/**
 * A number field that keeps what the user is typing.
 *
 * The editors used to render `value={x ?? -1}` with
 * `onChange={parseInt(e.target.value) || -1}`. Clearing the field stored the
 * fallback, the fallback was rendered straight back into the field, and the
 * next keystroke was appended to it — typing "8" into a cleared "-1" gave
 * "-18". Here the text is local state; only a change of `value` that did not
 * come from this field (a reset, a version switch) replaces it.
 */
export function NumberInput({
  value,
  onChange,
  emptyValue,
  integer = false,
  onBlur,
  ...rest
}: NumberInputProps) {
  const [draft, setDraft] = useState(() => format(value));
  // The last value this field reported. A `value` prop equal to it is our own
  // echo and must not overwrite the text the user is in the middle of typing.
  const reported = useRef<number | undefined>(value);

  useEffect(() => {
    if (!Object.is(value, reported.current)) {
      reported.current = value;
      setDraft(format(value));
    }
  }, [value]);

  return (
    <input
      {...rest}
      type="number"
      value={draft}
      onChange={(e) => {
        const raw = e.target.value;
        setDraft(raw);
        const next = parse(raw, integer) ?? emptyValue;
        reported.current = next;
        onChange(next);
      }}
      onBlur={(e) => {
        // Tidy up only once the user has left the field: an unparseable
        // leftover ("-", "1e") shows what was actually stored.
        if (parse(draft, integer) === undefined) setDraft(format(emptyValue));
        onBlur?.(e);
      }}
    />
  );
}
