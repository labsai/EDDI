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

/**
 * The whole text must be a number — `parseInt("1.5")` is 1 and
 * `parseInt("1e3")` is 1, which stored something other than what the field
 * showed. For an integer field a fraction is not a value at all.
 */
function parse(raw: string, integer: boolean): number | undefined {
  if (raw.trim() === "") return undefined;
  const parsed = Number(raw);
  if (!Number.isFinite(parsed)) return undefined;
  if (integer && !Number.isInteger(parsed)) return undefined;
  return parsed;
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

  const invalid = draft.trim() !== "" && parse(draft, integer) === undefined;

  return (
    <input
      {...rest}
      type="number"
      step={rest.step ?? (integer ? 1 : undefined)}
      aria-invalid={invalid || undefined}
      value={draft}
      onChange={(e) => {
        const raw = e.target.value;
        setDraft(raw);
        const next = parse(raw, integer) ?? emptyValue;
        reported.current = next;
        onChange(next);
      }}
      onBlur={(e) => {
        // Tidy up only once the user has left the field, so the field shows
        // exactly what was stored: an unparseable leftover ("-", "1.5" in an
        // integer field) becomes the empty value, and "1e3" becomes "1000".
        const parsed = parse(draft, integer);
        setDraft(format(parsed ?? emptyValue));
        onBlur?.(e);
      }}
    />
  );
}
