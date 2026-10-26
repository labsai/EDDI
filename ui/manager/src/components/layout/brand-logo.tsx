import type { SyntheticEvent } from "react";
import { useBrand } from "@/hooks/use-brand";
import { cn } from "@/lib/utils";
// The bundled EDDI wordmark, inlined as a data URI. Only a fallback now: EDDI
// serves the current logo at /branding/logo, but the design-system bundle has no
// server, and a broken image there is worse than the EDDI one
// (.design-sync/NOTES.md — keep this file importable).
import logoEddi from "@/assets/logo_eddi.png";

/** Swap a failed /branding/* image for the bundled wordmark, once. */
function fallBackToBundled(event: SyntheticEvent<HTMLImageElement>) {
  const img = event.currentTarget;
  if (img.dataset.fallback) return;
  img.dataset.fallback = "true";
  img.src = logoEddi;
}

interface BrandWordmarkProps {
  className?: string;
  /**
   * Turn the unbranded EDDI wordmark dark on a light background. It is a pure
   * white glyph, which vanishes on the light-mode sidebar; `invert` makes it
   * near-black there while dark mode keeps it white.
   */
  invertInLight?: boolean;
  testId?: string;
}

/**
 * The product's wordmark: `/branding/logo` in light mode, `/branding/logo-dark`
 * in dark mode (the server answers the light logo there when no dark one is
 * uploaded).
 *
 * Unbranded, both slots serve the one white EDDI wordmark, so it stays a single
 * image with the old `invert dark:invert-0` treatment and looks exactly as it
 * did. Branded, an uploaded logo is shown as uploaded: inverting a colour logo
 * would turn it into its negative, which is why the trick is not used there.
 */
export function BrandWordmark({ className, invertInLight = false, testId }: BrandWordmarkProps) {
  const { branded, productName } = useBrand();

  if (!branded) {
    return (
      <img
        src="/branding/logo"
        alt={productName}
        onError={fallBackToBundled}
        className={cn(className, invertInLight && "invert dark:invert-0")}
        data-testid={testId}
      />
    );
  }

  // Two images rather than <picture>: the theme is the app's `.dark` class, which
  // a media query in <source> cannot see.
  return (
    <>
      <img
        src="/branding/logo"
        alt={productName}
        onError={fallBackToBundled}
        className={cn(className, "dark:hidden")}
        data-testid={testId}
      />
      <img
        src="/branding/logo-dark"
        alt={productName}
        onError={fallBackToBundled}
        className={cn(className, "hidden dark:block")}
        data-testid={testId ? `${testId}-dark` : undefined}
      />
    </>
  );
}

interface BrandIconProps {
  className?: string;
  /** Empty for a decorative icon next to the product name. */
  alt?: string;
  testId?: string;
}

/** The product's square icon, `/branding/icon` — EDDI's own when none is uploaded. */
export function BrandIcon({ className, alt, testId }: BrandIconProps) {
  const { productName } = useBrand();
  const decorative = alt === "";
  return (
    <img
      src="/branding/icon"
      alt={alt ?? productName}
      aria-hidden={decorative ? true : undefined}
      className={className}
      data-testid={testId}
    />
  );
}
