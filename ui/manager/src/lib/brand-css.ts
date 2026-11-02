/**
 * The brand's colour tokens, as EDDI's `/branding/brand.css` renders them
 * (`BrandDelivery.stylesheet`), for the two places the Manager needs them
 * without a server: the settings page's live preview, and the MSW mock that
 * answers `brand.css` in mock mode.
 *
 * The formulas are not restated by hand. {@link BRAND_SCALE} is a verbatim copy
 * of `BrandDelivery.BRAND_SCALE`, and `BrandDeliveryTest` fails when the two
 * differ; `brand-css.test.ts` pins the stylesheet shape to the one the Java test
 * expects.
 */

/**
 * The `brand-50…950` scale, derived in the browser from `--color-primary`.
 * Kept on one line on purpose: `BrandDeliveryTest` looks for it verbatim.
 */
// prettier-ignore
export const BRAND_SCALE = "--color-brand-50:color-mix(in oklch,var(--color-primary) 6%,white);--color-brand-100:color-mix(in oklch,var(--color-primary) 16%,white);--color-brand-200:color-mix(in oklch,var(--color-primary) 33%,white);--color-brand-300:color-mix(in oklch,var(--color-primary) 52%,white);--color-brand-400:color-mix(in oklch,var(--color-primary) 75%,white);--color-brand-500:var(--color-primary);--color-brand-600:color-mix(in oklch,var(--color-primary) 85%,black);--color-brand-700:color-mix(in oklch,var(--color-primary) 70%,black);--color-brand-800:color-mix(in oklch,var(--color-primary) 60%,black);--color-brand-900:color-mix(in oklch,var(--color-primary) 54%,black);--color-brand-950:color-mix(in oklch,var(--color-primary) 36%,black);";

/** The steps of the scale, lightest first. */
export const BRAND_SCALE_STEPS = [50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 950] as const;

/** The five colours of a brand; `null` is "unset". */
export interface BrandColorValues {
  primary: string | null;
  primaryForeground: string | null;
  darkPrimary: string | null;
  darkPrimaryForeground: string | null;
  chatAccent: string | null;
}

export const EMPTY_COLORS: Readonly<BrandColorValues> = Object.freeze({
  primary: null,
  primaryForeground: null,
  darkPrimary: null,
  darkPrimaryForeground: null,
  chatAccent: null,
});

/**
 * `BrandingService.resolveColors`: the dark colours follow the light ones and
 * the chat accent follows the primary.
 */
export function resolveBrandColors(colors: BrandColorValues): BrandColorValues {
  return {
    primary: colors.primary,
    primaryForeground: colors.primaryForeground,
    darkPrimary: colors.darkPrimary ?? colors.primary,
    darkPrimaryForeground: colors.darkPrimaryForeground ?? colors.primaryForeground,
    chatAccent: colors.chatAccent ?? colors.primary,
  };
}

/** The scale as `[property, value]` pairs, parsed from {@link BRAND_SCALE}. */
export function brandScaleDeclarations(): [string, string][] {
  return BRAND_SCALE.split(";")
    .filter(Boolean)
    .map((declaration) => {
      const colon = declaration.indexOf(":");
      return [declaration.slice(0, colon), declaration.slice(colon + 1)];
    });
}

/**
 * The Manager's tokens for one mode, as `BrandDelivery.managerTokens` writes
 * them: the primary also drives the ring and the active nav entry, and with a
 * primary the whole scale is re-declared so it derives from this mode's primary.
 * Unset values are left out, so the bundle's own token applies.
 */
export function managerTokenDeclarations(primary: string | null, foreground: string | null): [string, string][] {
  const out: [string, string][] = [];
  if (primary) {
    out.push(["--color-primary", primary], ["--color-ring", primary], ["--color-sidebar-accent", primary]);
  }
  if (foreground) {
    out.push(["--color-primary-foreground", foreground], ["--color-sidebar-accent-foreground", foreground]);
  }
  if (primary) out.push(...brandScaleDeclarations());
  return out;
}

/**
 * Inline custom properties that make a container preview a brand: set them on
 * an element and every `bg-primary`, `brand-N`, ring and sidebar-accent utility
 * inside it renders in the brand's colours, while the page around it keeps the
 * brand in effect.
 */
export function brandPreviewStyle(primary: string | null, foreground: string | null): Record<string, string> {
  return Object.fromEntries(managerTokenDeclarations(primary, foreground));
}

/**
 * `BrandDelivery.stylesheet`, byte for byte: what EDDI answers for
 * `/branding/brand.css`. `colors` must already be resolved
 * ({@link resolveBrandColors}) and validated; mock mode only renders values the
 * mock validated with the same grammar as the server.
 */
export function renderBrandStylesheet(colors: BrandColorValues, revision: number): string {
  const { primary, primaryForeground, darkPrimary, darkPrimaryForeground, chatAccent } = colors;
  if (!primary && !primaryForeground && !chatAccent) return "";
  const decl = (pairs: [string, string][]) => pairs.map(([k, v]) => `${k}:${v};`).join("");
  let css = `/* brand revision ${revision} */\n:root{`;
  css += decl(managerTokenDeclarations(primary, primaryForeground));
  if (primary) css += `--brand-loader-accent:${darkPrimary ?? primary};`;
  if (chatAccent) css += `--chat-accent:${chatAccent};`;
  css += "}\n";
  if (darkPrimary || darkPrimaryForeground) {
    css += `.dark{${decl(managerTokenDeclarations(darkPrimary, darkPrimaryForeground))}}\n`;
  }
  return css;
}
