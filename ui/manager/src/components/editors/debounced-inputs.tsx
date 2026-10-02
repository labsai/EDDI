import { useCallback, useEffect, useRef, useState, type InputHTMLAttributes } from "react";

/**
 * Inputs that buffer keystrokes locally and commit once typing pauses, so a
 * section editor sends one PUT per edit instead of one per keystroke.
 *
 * Two rules both inputs keep, each learnt from a lost edit:
 *
 * - **A pending edit is never dropped.** The commit is debounced, and the
 *   sections these sit in collapse (unmounting the input) — the old cleanup
 *   cleared the timer, so an edit typed within the debounce window before a
 *   collapse, a tab switch or a navigation silently vanished. Blur and unmount
 *   now FLUSH the pending value instead of discarding it.
 * - **The latest `onCommit` is the one called.** It is read through a ref, so a
 *   flush after a re-render does not call a stale closure.
 */

function useDebouncedCommit<T>(onCommit: (v: T) => void, delay: number) {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pending = useRef<{ value: T } | null>(null);
  const onCommitRef = useRef(onCommit);
  useEffect(() => {
    onCommitRef.current = onCommit;
  }, [onCommit]);

  const flush = useCallback(() => {
    if (timer.current) {
      clearTimeout(timer.current);
      timer.current = null;
    }
    const p = pending.current;
    pending.current = null;
    if (p) onCommitRef.current(p.value);
  }, []);

  const schedule = useCallback(
    (value: T) => {
      pending.current = { value };
      if (timer.current) clearTimeout(timer.current);
      timer.current = setTimeout(flush, delay);
    },
    [delay, flush],
  );

  // Unmount (a collapsed section, a closed tab): flush, do not discard.
  useEffect(() => flush, [flush]);

  return { schedule, flush };
}

type BaseProps = Omit<InputHTMLAttributes<HTMLInputElement>, "onChange" | "value">;

/** Text input that buffers locally and debounces the mutation to avoid per-keystroke PUTs */
export function DebouncedInput({
  value,
  onCommit,
  delay = 600,
  onBlur,
  ...rest
}: BaseProps & {
  value: string;
  onCommit: (v: string) => void;
  delay?: number;
}) {
  const [local, setLocal] = useState(value);
  const { schedule, flush } = useDebouncedCommit(onCommit, delay);

  // Sync from parent if the external value changes (version bump etc.)
  useEffect(() => setLocal(value), [value]);

  return (
    <input
      {...rest}
      value={local}
      onChange={(e) => {
        setLocal(e.target.value);
        schedule(e.target.value);
      }}
      onBlur={(e) => {
        flush();
        onBlur?.(e);
      }}
    />
  );
}

/**
 * Read a number field's text.
 *
 * `parseFloat(raw) || fallback` — the previous rule — turned a typed `0` into
 * the fallback, because 0 is falsy: `pruneStaleAfterDays: 0` (the documented
 * "never prune") saved as 90 and `maxWritesPerTurn: 0` as 10. Only a value that
 * is not a finite number at all (empty, `-`, `abc`) falls back now; a `min`
 * the input declares is respected by clamping.
 */
function parseNumberField(raw: string, fallback: number, min: number | undefined): number {
  const trimmed = raw.trim();
  const parsed = trimmed === "" ? Number.NaN : Number(trimmed);
  if (!Number.isFinite(parsed)) return fallback;
  if (min !== undefined && Number.isFinite(min) && parsed < min) return min;
  return parsed;
}

/** Number input that buffers locally and debounces the mutation */
export function DebouncedNumberInput({
  value,
  onCommit,
  delay = 600,
  fallback = 0,
  onBlur,
  ...rest
}: BaseProps & {
  value: number;
  onCommit: (v: number) => void;
  delay?: number;
  fallback?: number;
}) {
  const [local, setLocal] = useState(String(value));
  const min = rest.min === undefined ? undefined : Number(rest.min);
  const { schedule, flush } = useDebouncedCommit<number>(onCommit, delay);

  useEffect(() => setLocal(String(value)), [value]);

  return (
    <input
      {...rest}
      type="number"
      value={local}
      onChange={(e) => {
        setLocal(e.target.value);
        schedule(parseNumberField(e.target.value, fallback, min));
      }}
      onBlur={(e) => {
        flush();
        onBlur?.(e);
      }}
    />
  );
}
