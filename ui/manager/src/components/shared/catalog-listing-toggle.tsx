import { useId } from "react";
import { useTranslation } from "react-i18next";
import { LibraryBig } from "lucide-react";
import { cn } from "@/lib/utils";

interface CatalogListingToggleProps {
  /** The stored `listed` flag; absent reads as off, the backend's default. */
  listed: boolean | undefined;
  onChange: (listed: boolean) => void;
  kind: "agent" | "group";
  disabled?: boolean;
  className?: string;
}

/**
 * The `listed` flag of an agent or group: whether it is offered in the end-user
 * catalog (`GET /catalog/agents`, `/catalog/groups`) that Workforce shows an
 * `eddi-user`. Opt-in, so a deployment lists nothing until someone says so.
 * Listing decides what is offered, never who may use it — that stays with the
 * resource's sharing, which the description says so nobody mistakes this for a
 * permission.
 */
export function CatalogListingToggle({ listed, onChange, kind, disabled, className }: CatalogListingToggleProps) {
  const { t } = useTranslation();
  const labelId = useId();
  const descriptionId = useId();
  const on = listed === true;

  return (
    <div
      className={cn("flex items-start justify-between gap-4 rounded-xl border bg-card p-4 shadow-sm", className)}
      data-testid={`${kind}-listed-section`}
    >
      <div className="flex items-start gap-3">
        <LibraryBig className="mt-0.5 h-5 w-5 shrink-0 text-primary" aria-hidden />
        <div>
          <p id={labelId} className="text-sm font-medium text-foreground">
            {t("catalogListing.label", "Listed in the catalog")}
          </p>
          <p id={descriptionId} className="text-xs text-muted-foreground">
            {kind === "agent"
              ? t(
                  "catalogListing.agentDescription",
                  "Offer this agent to end users in Workforce. Only people who may use it see it, and only once it is deployed. With workspaces on, published and internal agents are listed anyway.",
                )
              : t(
                  "catalogListing.groupDescription",
                  "Offer this team to end users in Workforce. Only people who may use it see it. With workspaces on, published and internal teams are listed anyway.",
                )}
          </p>
        </div>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={on}
        aria-labelledby={labelId}
        aria-describedby={descriptionId}
        disabled={disabled}
        onClick={() => onChange(!on)}
        data-testid={`${kind}-listed-toggle`}
        className={cn(
          "relative mt-0.5 inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50",
          on ? "bg-primary" : "bg-muted",
        )}
      >
        <span
          aria-hidden
          className={cn(
            "pointer-events-none inline-block h-5 w-5 rounded-full bg-background shadow transition-transform",
            on ? "translate-x-5 rtl:-translate-x-5" : "translate-x-0",
          )}
        />
      </button>
    </div>
  );
}
