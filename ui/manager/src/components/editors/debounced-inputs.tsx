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
 * - **The latest `onCommit` is the one called — for the same target.** It is
 *   read through a ref, so a flush after a re-render (the version a save just
 *   created) does not call a stale closure. But when `commitKey` changes — the
 *   input now edits a different agent, reused in place by an in-app navigation
 *   — the pending edit is flushed through the callback it was typed under
 *   BEFORE the new one is adopted, so an edit made for agent A is never saved
 *   into agent B.
 */

function useDebouncedCommit<T>(onCommit: (v: T) => void, delay: number, commitKey?: unknown) {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pending = useRef<{ value: T } | null>(null);
  const onCommitRef = useRef(onCommit);
  const commitKeyRef = useRef(commitKey);

  const flush = useCallback(() => {
    if (timer.current) {
      clearTimeout(timer.current);
      timer.current = null;
    }
    const p = pending.current;
    pending.current = null;
    if (p) onCommitRef.current(p.value);
  }, []);

  useEffect(() => {
    if (!Object.is(commitKeyRef.current, commitKey)) {
      // Still the previous target's callback: settle its edit first.
      flush();
      commitKeyRef.current = commitKey;
    }
    onCommitRef.current = onCommit;
  }, [onCommit, commitKey, flush]);

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
  commitKey,
  onBlur,
  ...rest
}: BaseProps & {
  value: string;
  onCommit: (v: string) => void;
  delay?: number;
  /** Identity of what is being edited (e.g. the agent id) — see useDebouncedCommit. */
  commitKey?: unknown;
}) {
  const [local, setLocal] = useState(value);
  const { schedule, flush } = useDebouncedCommit(onCommit, delay, commitKey);

  // Sync from parent if the external value changes (version bump etc.) — or
  // the target does: two agents can hold the same value, and then only the key
  // says the draft on screen belongs to the previous one. Runs after the hook's
  // key-change flush, so the old draft is saved to its own agent first.
  useEffect(() => setLocal(value), [value, commitKey]);

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
 * the input declares is respected by clamping — the fallback included, so a
 * cleared `min={1}` field never commits a default `0`.
 */
function parseNumberField(raw: string, fallback: number, min: number | undefined): number {
  const trimmed = raw.trim();
  const parsed = trimmed === "" ? Number.NaN : Number(trimmed);
  const value = Number.isFinite(parsed) ? parsed : fallback;
  if (min !== undefined && Number.isFinite(min) && value < min) return min;
  return value;
}

/** Number input that buffers locally and debounces the mutation */
export function DebouncedNumberInput({
  value,
  onCommit,
  delay = 600,
  fallback = 0,
  commitKey,
  onBlur,
  ...rest
}: BaseProps & {
  value: number;
  onCommit: (v: number) => void;
  delay?: number;
  fallback?: number;
  /** Identity of what is being edited (e.g. the agent id) — see useDebouncedCommit. */
  commitKey?: unknown;
}) {
  const [local, setLocal] = useState(String(value));
  const min = rest.min === undefined ? undefined : Number(rest.min);
  // Show what was committed. A clamped or defaulted value (typed 0 under
  // min=1, a cleared field) used to stay on screen as typed while the saved
  // value differed — and when the parent's value did not change, nothing
  // corrected it.
  const commitAndShow = useCallback(
    (v: number) => {
      onCommit(v);
      setLocal((shown) => (shown.trim() !== "" && Number(shown) === v ? shown : String(v)));
    },
    [onCommit],
  );
  const { schedule, flush } = useDebouncedCommit<number>(commitAndShow, delay, commitKey);

  // As DebouncedInput: a new target resets the draft even when the value matches.
  useEffect(() => setLocal(String(value)), [value, commitKey]);

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
