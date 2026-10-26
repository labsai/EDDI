import { useTranslation } from "react-i18next";
import { Bot, LayoutDashboard, Workflow } from "lucide-react";
import { cn } from "@/lib/utils";
import { brandPreviewStyle, type BrandColorValues } from "@/lib/brand-css";
import type { BrandAssetFlags } from "@/lib/brand";

/**
 * Static class names for the swatch strip: Tailwind only generates utilities it
 * finds written out in the sources.
 */
const SWATCHES = [
  ["50", "bg-brand-50"],
  ["100", "bg-brand-100"],
  ["200", "bg-brand-200"],
  ["300", "bg-brand-300"],
  ["400", "bg-brand-400"],
  ["500", "bg-brand-500"],
  ["600", "bg-brand-600"],
  ["700", "bg-brand-700"],
  ["800", "bg-brand-800"],
  ["900", "bg-brand-900"],
  ["950", "bg-brand-950"],
] as const;

export interface BrandPreviewProps {
  /** The colours as they will resolve: dark follows light, accent follows primary. */
  colors: BrandColorValues;
  productName: string;
  /** False while the name is still the EDDI default and nothing is uploaded. */
  branded: boolean;
  /** Which slots have an image of their own. */
  assets: BrandAssetFlags;
  /** Cache buster for the uploaded images. */
  revision: number;
}

function imageUrl(path: string, revision: number) {
  return revision > 0 ? `/branding/${path}?r=${revision}` : `/branding/${path}`;
}

/**
 * One mode of the live preview: a mini sidebar with the wordmark and an active
 * entry, a primary button, the `brand-50…950` scale and a chat bubble.
 *
 * The panel restates its mode's surfaces and the EDDI defaults
 * (`.brand-preview-light` / `-dark` in index.css), and the brand's colours go on
 * top as inline custom properties built by the same formulas `/branding/brand.css`
 * uses — so what it shows is what a save will look like, whatever brand is in
 * effect on the page around it.
 */
function PreviewPanel({ mode, colors, productName, branded, assets, revision }: BrandPreviewProps & { mode: "light" | "dark" }) {
  const { t } = useTranslation();
  const dark = mode === "dark";
  const primary = dark ? colors.darkPrimary : colors.primary;
  const foreground = dark ? colors.darkPrimaryForeground : colors.primaryForeground;
  const style = { ...brandPreviewStyle(primary, foreground) };
  const accent = colors.chatAccent;

  const ownLogo = dark ? assets.logoDark || assets.logo : assets.logo;
  const logoPath = dark ? "logo-dark" : "logo";

  return (
    <div
      className={cn(
        "overflow-hidden rounded-xl border border-border",
        dark ? "brand-preview-dark" : "brand-preview-light",
      )}
      style={style}
      data-testid={`brand-preview-${mode}`}
    >
      <div className="flex min-h-[13rem]">
        {/* Mini sidebar */}
        <div className="flex w-36 shrink-0 flex-col border-e border-sidebar-border bg-sidebar p-2">
          <div className="flex h-9 items-center justify-center border-b border-sidebar-border pb-2">
            {ownLogo || !branded ? (
              <img
                src={imageUrl(logoPath, revision)}
                alt={productName}
                // The unbranded EDDI wordmark is white; the light sidebar inverts it.
                className={cn("h-5 w-auto max-w-full", !branded && !dark && "invert")}
                data-testid={`brand-preview-${mode}-logo`}
              />
            ) : (
              <span
                className="truncate text-sm font-bold text-sidebar-foreground"
                data-testid={`brand-preview-${mode}-wordmark`}
              >
                {productName}
              </span>
            )}
          </div>
          <div className="mt-2 space-y-1 text-xs">
            <div className="flex items-center gap-1.5 rounded-md bg-sidebar-accent px-2 py-1.5 font-medium text-sidebar-accent-foreground">
              <LayoutDashboard className="h-3.5 w-3.5" aria-hidden="true" />
              {t("nav.dashboard", "Dashboard")}
            </div>
            <div className="flex items-center gap-1.5 px-2 py-1.5 text-sidebar-foreground">
              <Bot className="h-3.5 w-3.5" aria-hidden="true" />
              {t("nav.agents", "Agents")}
            </div>
            <div className="flex items-center gap-1.5 px-2 py-1.5 text-sidebar-foreground">
              <Workflow className="h-3.5 w-3.5" aria-hidden="true" />
              {t("nav.packages", "Workflows")}
            </div>
          </div>
        </div>

        {/* Content */}
        <div className="flex min-w-0 flex-1 flex-col gap-3 p-3">
          <div className="flex flex-wrap items-center gap-2">
            <span
              className="inline-flex items-center rounded-lg bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground"
              data-testid={`brand-preview-${mode}-button`}
            >
              {t("branding.preview.button", "Primary action")}
            </span>
            <span className="inline-flex items-center rounded-full border border-brand-300 bg-brand-100 px-2 py-0.5 text-xs font-medium text-brand-800">
              {t("branding.preview.badge", "Highlight")}
            </span>
          </div>

          <div className="flex overflow-hidden rounded-md border border-border" aria-hidden="true">
            {SWATCHES.map(([step, cls]) => (
              <div key={step} className={cn("h-6 flex-1", cls)} title={`brand-${step}`} />
            ))}
          </div>

          <div className="mt-auto space-y-1.5 rounded-lg border border-border bg-card p-2 text-xs">
            <div className="max-w-[85%] rounded-lg bg-muted px-2 py-1.5 text-foreground">
              {t("branding.preview.agentMessage", "Hi! How can I help you today?")}
            </div>
            <div
              className="ms-auto max-w-[85%] rounded-lg px-2 py-1.5 text-primary-foreground"
              // The Chat UI's accent; the Manager has no token for it. It follows
              // the LIGHT primary in both modes, so its text is the light pair's.
              style={
                accent
                  ? { backgroundColor: accent, color: colors.primaryForeground ?? undefined }
                  : { backgroundColor: "var(--color-primary)" }
              }
              data-testid={`brand-preview-${mode}-bubble`}
            >
              {t("branding.preview.userMessage", "Show me my agents.")}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

/** The live preview of a brand: light and dark side by side. */
export function BrandPreview(props: BrandPreviewProps) {
  const { t } = useTranslation();
  return (
    <div className="grid gap-3 @3xl/main:grid-cols-2" data-testid="brand-preview">
      <div className="space-y-1.5">
        <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          {t("branding.preview.light", "Light")}
        </p>
        <PreviewPanel mode="light" {...props} />
      </div>
      <div className="space-y-1.5">
        <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
          {t("branding.preview.dark", "Dark")}
        </p>
        <PreviewPanel mode="dark" {...props} />
      </div>
    </div>
  );
}
