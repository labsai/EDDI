import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

/** Shared look for the test-suite editor's text controls. */
export const INPUT_CLASS =
  "h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60";

export function LabelledField({
  label,
  hint,
  className,
  children,
}: {
  label: ReactNode;
  hint?: ReactNode;
  className?: string;
  children: (id: string) => ReactNode;
}) {
  const id = useId();
  return (
    <div className={cn("space-y-1", className)}>
      <label htmlFor={id} className="block text-xs font-medium text-muted-foreground">
        {label}
      </label>
      {children(id)}
      {hint && <p className="text-[11px] text-muted-foreground">{hint}</p>}
    </div>
  );
}

/**
 * A JSON value edited as text. The text is kept locally so a half-typed
 * document does not bounce back; the parsed value is committed only while the
 * text parses (and, with `objectOnly`, is an object). An empty field commits
 * `undefined` — "not set".
 */
export function JsonField({
  id,
  value,
  onChange,
  readOnly,
  objectOnly = false,
  rows = 2,
  placeholder,
  testId,
}: {
  id?: string;
  value: unknown;
  onChange: (value: unknown) => void;
  readOnly?: boolean;
  objectOnly?: boolean;
  rows?: number;
  placeholder?: string;
  testId?: string;
}) {
  const { t } = useTranslation();
  const format = (v: unknown) => (v === undefined ? "" : JSON.stringify(v));
  const [text, setText] = useState(() => format(value));
  const [invalid, setInvalid] = useState(false);
  const lastCommitted = useRef(format(value));

  // Adopt an outside change (JSON tab edit, version switch) — but never
  // overwrite the user's own text with its re-serialised twin.
  useEffect(() => {
    const incoming = format(value);
    if (incoming !== lastCommitted.current) {
      lastCommitted.current = incoming;
      setText(incoming);
      setInvalid(false);
    }
  }, [value]);

  const handle = (raw: string) => {
    setText(raw);
    if (raw.trim() === "") {
      setInvalid(false);
      lastCommitted.current = "";
      onChange(undefined);
      return;
    }
    try {
      const parsed: unknown = JSON.parse(raw);
      if (objectOnly && (parsed === null || typeof parsed !== "object" || Array.isArray(parsed))) {
        setInvalid(true);
        return;
      }
      setInvalid(false);
      lastCommitted.current = format(parsed);
      onChange(parsed);
    } catch {
      setInvalid(true);
    }
  };

  return (
    <div>
      <textarea
        id={id}
        value={text}
        rows={rows}
        readOnly={readOnly}
        spellCheck={false}
        placeholder={placeholder}
        onChange={(e) => handle(e.target.value)}
        aria-invalid={invalid || undefined}
        data-testid={testId}
        className={cn(
          "w-full rounded-md border bg-background px-2 py-1.5 font-mono text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring",
          invalid ? "border-destructive" : "border-input"
        )}
      />
      {invalid && (
        <p className="mt-0.5 text-[11px] text-destructive" role="alert">
          {objectOnly
            ? t("testSuiteEditor.invalidJsonObject", "Not a valid JSON object — the last valid value is kept.")
            : t("testSuiteEditor.invalidJson", "Not valid JSON — the last valid value is kept.")}
        </p>
      )}
    </div>
  );
}

/** A list of strings typed as comma-separated text. Empty means "not set". */
export function StringListField({
  id,
  value,
  onChange,
  readOnly,
  placeholder,
  testId,
}: {
  id?: string;
  value: string[] | undefined;
  onChange: (value: string[] | undefined) => void;
  readOnly?: boolean;
  placeholder?: string;
  testId?: string;
}) {
  const join = (v: string[] | undefined) => (v ?? []).join(", ");
  const [text, setText] = useState(() => join(value));
  const lastCommitted = useRef(join(value));

  useEffect(() => {
    const incoming = join(value);
    if (incoming !== lastCommitted.current) {
      lastCommitted.current = incoming;
      setText(incoming);
    }
  }, [value]);

  return (
    <input
      id={id}
      type="text"
      value={text}
      readOnly={readOnly}
      placeholder={placeholder}
      data-testid={testId}
      className={INPUT_CLASS}
      onChange={(e) => {
        setText(e.target.value);
        const items = e.target.value
          .split(",")
          .map((s) => s.trim())
          .filter(Boolean);
        lastCommitted.current = join(items);
        onChange(items.length > 0 ? items : undefined);
      }}
    />
  );
}

/** A number that may be unset; keeps the typed text while it is not a number. */
export function OptionalNumberField({
  id,
  value,
  onChange,
  readOnly,
  integer,
  min,
  max,
  step,
  placeholder,
  testId,
}: {
  id?: string;
  value: number | undefined;
  onChange: (value: number | undefined) => void;
  readOnly?: boolean;
  integer?: boolean;
  min?: number;
  max?: number;
  step?: number;
  placeholder?: string;
  testId?: string;
}) {
  const [text, setText] = useState(value === undefined ? "" : String(value));
  const [outOfRange, setOutOfRange] = useState(false);
  const lastCommitted = useRef(value);

  useEffect(() => {
    if (value !== lastCommitted.current) {
      lastCommitted.current = value;
      setText(value === undefined ? "" : String(value));
      setOutOfRange(false);
    }
  }, [value]);

  return (
    <input
      id={id}
      type="number"
      inputMode={integer ? "numeric" : "decimal"}
      value={text}
      min={min}
      max={max}
      step={step ?? (integer ? 1 : "any")}
      readOnly={readOnly}
      placeholder={placeholder}
      data-testid={testId}
      aria-invalid={outOfRange || undefined}
      title={outOfRange ? `${min ?? ""}–${max ?? ""}` : undefined}
      className={cn(INPUT_CLASS, outOfRange && "border-destructive")}
      onChange={(e) => {
        setText(e.target.value);
        if (e.target.value.trim() === "") {
          setOutOfRange(false);
          lastCommitted.current = undefined;
          onChange(undefined);
          return;
        }
        const n = Number(e.target.value);
        if (!Number.isFinite(n) || (integer && !Number.isInteger(n))) return;
        if ((min !== undefined && n < min) || (max !== undefined && n > max)) {
          setOutOfRange(true);
          return;
        }
        setOutOfRange(false);
        lastCommitted.current = n;
        onChange(n);
      }}
    />
  );
}
