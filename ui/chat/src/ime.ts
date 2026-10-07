/* ──────────────────────────────────────────────
   IME guard
   While an input method editor is composing (Japanese, Chinese, Korean, and
   many mobile keyboards), Enter confirms the candidate — it is not "send".
   Browsers flag that keydown with `isComposing`; Safari fires the final
   keydown just AFTER compositionend, with the legacy keyCode 229 instead.
   ────────────────────────────────────────────── */

export function isImeComposing(e: {
  nativeEvent?: { isComposing?: boolean; keyCode?: number };
  keyCode?: number;
}): boolean {
  return (
    e.nativeEvent?.isComposing === true ||
    e.nativeEvent?.keyCode === 229 ||
    e.keyCode === 229
  );
}
