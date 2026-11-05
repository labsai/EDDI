/**
 * Whether a keydown belongs to an IME composition (Chinese, Japanese, Korean
 * and other input methods) rather than to the page.
 *
 * The Enter that confirms a composition commits the converted text; a composer
 * that sends on Enter must ignore it, or the message goes out half-typed.
 * `isComposing` covers the standard case. `keyCode` 229 covers Safari, which
 * fires that confirming keydown after `compositionend`, when `isComposing` is
 * already false.
 */
export function isImeComposing(event: {
  nativeEvent?: { isComposing?: boolean };
  keyCode?: number;
}): boolean {
  return event.nativeEvent?.isComposing === true || event.keyCode === 229;
}
