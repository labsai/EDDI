/**
 * A theme colour for Monaco, read from a CSS custom property on `:root`.
 *
 * Monaco's theme rules take a bare six-digit hex, so anything else the property
 * might hold (unset, a `var()`, `oklch()`) keeps `fallback`, the colour the
 * editor always had.
 */
export function cssHexColor(property: string, fallback: string): string {
  const value =
    typeof document === "undefined"
      ? ""
      : getComputedStyle(document.documentElement).getPropertyValue(property).trim();
  const hex = /^#?([0-9a-f]{6})$/i.exec(value);
  return hex ? hex[1]!.toLowerCase() : fallback;
}
