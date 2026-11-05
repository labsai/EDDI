/**
 * The settings page's client-side copy of `BrandValidator`: the same caps, the
 * same refused characters, the same colour grammar and the same WCAG contrast
 * maths. It only decides what to flag before a save — the server validates every
 * write again, and its `violations` are always shown as they arrive.
 */

export const PRODUCT_NAME_MAX = 40;
export const SHORT_NAME_MAX = 12;
export const TAGLINE_MAX = 120;
const COLOR_MAX = 64;

/** WCAG AA for normal text. */
export const MIN_CONTRAST = 4.5;

const HEX = /^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/;
const NUM = String.raw`-?(?:\d{1,4}(?:\.\d{1,6})?|\.\d{1,6})(?:%|deg)?`;
const SEP = String.raw`(?:\s*,\s*|\s+)`;
const FUNCTION = new RegExp(
  String.raw`^(?:rgba?|hsla?|oklch)\(\s*${NUM}${SEP}${NUM}${SEP}${NUM}(?:\s*[,/]\s*${NUM})?\s*\)$`,
  "i",
);
const RGB_ARGS = new RegExp(String.raw`^rgba?\(\s*(${NUM})${SEP}(${NUM})${SEP}(${NUM})`, "i");

/** Why a value is refused, as a key the page translates. */
export type BrandTextProblem = "blank" | "tooLong" | "angleBrackets" | "control";

/** `BrandValidator.checkText`. `null` when the value is fine (or unset). */
export function checkBrandText(value: string | null, max: number): BrandTextProblem | null {
  if (value === null) return null;
  if (value.trim() === "") return "blank";
  if (Array.from(value).length > max) return "tooLong";
  for (const ch of value) {
    if (ch === "<" || ch === ">") return "angleBrackets";
    const code = ch.codePointAt(0)!;
    const control = code < 0x20 || (code >= 0x7f && code <= 0x9f) || code === 0x2028 || code === 0x2029;
    const bidi = (code >= 0x202a && code <= 0x202e) || (code >= 0x2066 && code <= 0x2069) || code === 0x200e || code === 0x200f;
    if (control || bidi) return "control";
  }
  return null;
}

/** `BrandValidator.isSafeColor`. */
export function isSafeBrandColor(value: string | null | undefined): boolean {
  if (!value || value.length > COLOR_MAX) return false;
  return HEX.test(value) || FUNCTION.test(value);
}

/** sRGB channels (0..1) of a hex or `rgb()` colour, else `null`. */
export function parseSrgb(color: string | null | undefined): [number, number, number] | null {
  if (!color || !isSafeBrandColor(color)) return null;
  if (color.startsWith("#")) {
    let hex = color.slice(1);
    if (hex.length === 3) hex = hex.split("").map((c) => c + c).join("");
    return [0, 2, 4].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255) as [number, number, number];
  }
  const match = RGB_ARGS.exec(color);
  if (!match) return null;
  const out: number[] = [];
  for (const arg of match.slice(1, 4)) {
    if (arg.endsWith("deg")) return null;
    const value = arg.endsWith("%") ? parseFloat(arg) / 100 : parseFloat(arg) / 255;
    out.push(Math.min(1, Math.max(0, value)));
  }
  return out as [number, number, number];
}

function linear(channel: number): number {
  return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
}

function luminance([r, g, b]: [number, number, number]): number {
  return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b);
}

/**
 * WCAG 2 contrast ratio of two colours, or `null` when either is not a hex or
 * `rgb()` value (the server does not guess `hsl()`/`oklch()` either).
 */
export function contrastRatio(foreground: string | null, background: string | null): number | null {
  const fg = parseSrgb(foreground);
  const bg = parseSrgb(background);
  if (!fg || !bg) return null;
  const a = luminance(fg);
  const b = luminance(bg);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

/** `#rrggbb` for a native colour input, or `null` when the value is not hex. */
export function toColorInputValue(value: string | null): string | null {
  if (!value || !HEX.test(value)) return null;
  const hex = value.slice(1);
  return `#${hex.length === 3 ? hex.split("").map((c) => c + c).join("") : hex}`.toLowerCase();
}
