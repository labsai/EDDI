import { useTranslation } from "react-i18next";
import { ExternalLink } from "lucide-react";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";
import { useBrand } from "@/hooks/use-brand";
import { BrandIcon } from "./brand-logo";

/** The license EDDI ships under (LICENSE at the repository root). */
const LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0";

interface AboutDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** The server's version, or `null` when it is not known (demo mode, an error). */
  version: string | null;
}

/**
 * Which product this is and which version runs — opened from the sidebar footer.
 *
 * Under a brand it names the brand. "Powered by EDDI" and the Apache 2.0 notice
 * show while the brand's `poweredBy` is on, which is the default (white-label
 * plan, R3); they are the one place a branded Manager names EDDI on purpose.
 */
export function AboutDialog({ open, onOpenChange, version }: AboutDialogProps) {
  const { t } = useTranslation();
  const brand = useBrand();

  return (
    <AccessibleDialog
      open={open}
      onClose={() => onOpenChange(false)}
      title={t("about.title", "About {{productName}}")}
      testId="about-dialog"
      maxWidth="max-w-sm"
    >
      <div className="flex flex-col items-center gap-3 p-6 text-center">
        <BrandIcon className="h-12 w-12 rounded-xl" alt="" />
        <div>
          <p className="text-lg font-semibold text-foreground" data-testid="about-product-name">
            {brand.productName}
          </p>
          {brand.tagline && (
            <p className="text-sm text-muted-foreground" data-testid="about-tagline">
              {brand.tagline}
            </p>
          )}
        </div>
        <p className="text-sm text-muted-foreground" data-testid="about-version">
          {version ? (
            <>
              {t("about.versionLabel", "Version")} <span className="font-mono">{version}</span>
            </>
          ) : (
            t("about.versionUnknown", "Version unknown")
          )}
        </p>
        {brand.poweredBy && (
          <div className="mt-2 space-y-1 border-t border-border pt-4 text-xs text-muted-foreground" data-testid="about-powered-by">
            <p className="font-medium text-foreground">{t("about.poweredBy", "Powered by EDDI")}</p>
            <p>{t("about.license", "EDDI is open-source software, licensed under the Apache License 2.0.")}</p>
            <a
              href={LICENSE_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-primary underline underline-offset-2 hover:no-underline"
              data-testid="about-license-link"
            >
              {t("about.licenseLink", "Read the license")}
              <ExternalLink className="h-3 w-3" aria-hidden="true" />
              <span className="sr-only">({t("common.opensNewTab", "opens in new tab")})</span>
            </a>
          </div>
        )}
      </div>
    </AccessibleDialog>
  );
}
