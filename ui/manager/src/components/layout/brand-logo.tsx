import { useState } from "react";
import { useBrand } from "@/hooks/use-brand";
import { cn } from "@/lib/utils";
import { brandAssetUrl } from "@/lib/brand";
// The bundled EDDI wordmark, inlined as a data URI. Only a fallback now: EDDI
// serves the current logo at /branding/logo, but the design-system bundle has no
// server, and a broken image there is worse than the EDDI one
// (.design-sync/NOTES.md — keep this file importable).
import logoEddi from "@/assets/logo_eddi.png";

/**
 * Tracks which URL failed to load. Keyed to the URL, not stored on the DOM node,
 * so a later brand revision (a new URL) gets its own chance to load and, failing
 * that, its own fallback.
 */
function useFailedSrc(src: string) {
  const [failed, setFailed] = useState<string | null>(null);
  return [failed === src, () => setFailed(src)] as const;
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

/** The product name set as a wordmark, for a brand with no logo of its own. */
function TextWordmark({ name, className, testId }: { name: string; className?: string; testId?: string }) {
  return (
    <span
      role="img"
      aria-label={name}
      className={cn(
        "inline-flex items-center truncate text-lg font-bold leading-none tracking-tight text-sidebar-foreground",
        className,
      )}
      data-testid={testId}
      data-wordmark="text"
    >
      {name}
    </span>
  );
}

interface WordmarkImageProps {
  src: string;
  name: string;
  branded: boolean;
  className?: string;
  /** Treatment for the bundled EDDI wordmark shown when an unbranded image fails. */
  fallbackClassName?: string;
  testId?: string;
}

/**
 * One wordmark `<img>`. When it fails to load: unbranded, the bundled EDDI
 * wordmark (it is the product's own); branded, the product name as text —
 * never EDDI's logo under another brand's name.
 */
function WordmarkImage({ src, name, branded, className, fallbackClassName, testId }: WordmarkImageProps) {
  const [failed, onError] = useFailedSrc(src);
  if (failed && branded) return <TextWordmark name={name} className={className} testId={testId} />;
  return (
    <img
      src={failed ? logoEddi : src}
      alt={name}
      onError={failed ? undefined : onError}
      className={cn(className, failed && fallbackClassName)}
      data-testid={testId}
    />
  );
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
 *
 * Branded without a logo of its own (`brand.assets`), `/branding/logo` would
 * answer the bundled EDDI wordmark, so the product name is set as text instead —
 * per mode: a brand that uploaded only a dark logo shows text in light mode and
 * its logo in dark mode. Image URLs carry the brand revision (`brandAssetUrl`),
 * so a logo replaced on the settings page shows at once.
 */
export function BrandWordmark({ className, invertInLight = false, testId }: BrandWordmarkProps) {
  const brand = useBrand();
  const { branded, productName, assets } = brand;

  if (!branded) {
    return (
      <WordmarkImage
        src={brandAssetUrl("logo", brand)}
        name={productName}
        branded={false}
        className={cn(className, invertInLight && "invert dark:invert-0")}
        testId={testId}
      />
    );
  }

  const lightImage = assets.logo;
  // The dark slot borrows the light logo on the server when it has none.
  const darkImage = assets.logoDark || assets.logo;
  if (!lightImage && !darkImage) {
    return <TextWordmark name={productName} className={className} testId={testId} />;
  }

  // Two elements rather than <picture>: the theme is the app's `.dark` class,
  // which a media query in <source> cannot see.
  return (
    <>
      {lightImage ? (
        <WordmarkImage
          src={brandAssetUrl("logo", brand)}
          name={productName}
          branded
          className={cn(className, "dark:hidden")}
          testId={testId}
        />
      ) : (
        <TextWordmark name={productName} className={cn(className, "dark:hidden")} testId={testId} />
      )}
      {darkImage ? (
        <WordmarkImage
          src={brandAssetUrl("logo-dark", brand)}
          name={productName}
          branded
          className={cn(className, "hidden dark:block")}
          testId={testId ? `${testId}-dark` : undefined}
        />
      ) : (
        <TextWordmark
          name={productName}
          className={cn(className, "hidden dark:inline-flex")}
          testId={testId ? `${testId}-dark` : undefined}
        />
      )}
    </>
  );
}

interface BrandIconProps {
  className?: string;
  /** Empty for a decorative icon next to the product name. */
  alt?: string;
  testId?: string;
}

/**
 * The product's square icon, `/branding/icon` — EDDI's own when unbranded.
 *
 * Branded without an icon of its own, the server would answer EDDI's, so the
 * first letter of the short name is drawn on the primary colour instead.
 */
export function BrandIcon({ className, alt, testId }: BrandIconProps) {
  const brand = useBrand();
  const src = brandAssetUrl("icon", brand);
  const [failed, onError] = useFailedSrc(src);
  const decorative = alt === "";
  const label = alt ?? brand.productName;

  // Branded without an icon of its own, or whose icon failed to load: the first
  // letter on the primary colour. A branded deployment never shows EDDI's mark.
  if (brand.branded && (!brand.assets.icon || failed)) {
    return (
      <span
        role={decorative ? undefined : "img"}
        aria-label={decorative ? undefined : label}
        aria-hidden={decorative ? true : undefined}
        className={cn(
          "inline-flex shrink-0 items-center justify-center bg-primary font-bold text-primary-foreground",
          className,
        )}
        data-testid={testId}
        data-icon="letter"
      >
        {Array.from(brand.shortName)[0]?.toUpperCase()}
      </span>
    );
  }

  return (
    <img
      src={failed ? logoEddi : src}
      alt={decorative ? "" : label}
      aria-hidden={decorative ? true : undefined}
      // Unbranded, the one bundled image is the EDDI wordmark — a white glyph,
      // so it is inverted on the light theme exactly as BrandWordmark does.
      onError={failed ? undefined : onError}
      className={cn(className, failed && "object-contain invert dark:invert-0")}
      data-testid={testId}
    />
  );
}
