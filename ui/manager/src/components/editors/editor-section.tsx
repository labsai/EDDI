import { useId, useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";

/**
 * Shared collapsible section wrapper used across all form editors.
 *
 * Two visual variants:
 * - `"inline"` (default): lightweight header, no card border — used inside editor forms
 * - `"card"`: full card wrapper with border + padding — used in agent detail sections
 */

export interface EditorSectionProps {
  label: string;
  defaultOpen?: boolean;
  icon?: React.ComponentType<{ className?: string }>;
  accent?: string;
  variant?: "inline" | "card";
  children: React.ReactNode;
}

export function EditorSection({
  label,
  defaultOpen = true,
  icon: Icon,
  accent,
  variant = "inline",
  children,
}: EditorSectionProps) {
  const [open, setOpen] = useState(defaultOpen);
  const panelId = useId();

  if (variant === "card") {
    // The heading wraps the button (not the other way round): a heading inside a
    // button is invalid and flattens to plain button text for assistive tech.
    return (
      <section className="rounded-xl border bg-card shadow-sm">
        <h2 className="text-lg font-semibold text-foreground">
          <button
            type="button"
            onClick={() => setOpen(!open)}
            aria-expanded={open}
            aria-controls={panelId}
            className="flex w-full items-center gap-2 border-b border-border p-5 text-start"
          >
            {open ? (
              <ChevronDown className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
            ) : (
              <ChevronRight className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
            )}
            {Icon && <Icon className={`h-5 w-5 ${accent ?? "text-primary"}`} aria-hidden="true" />}
            {label}
          </button>
        </h2>
        {open && (
          <div id={panelId} className="p-5 space-y-4">
            {children}
          </div>
        )}
      </section>
    );
  }

  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        aria-controls={panelId}
        className="mb-1.5 flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground hover:text-foreground transition-colors"
      >
        {open ? (
          <ChevronDown className="h-3 w-3" />
        ) : (
          <ChevronRight className="h-3 w-3" />
        )}
        {Icon && <Icon className={`h-3.5 w-3.5 ${accent ?? ""}`} />}
        {label}
      </button>
      {open && (
        <div id={panelId} className="space-y-2">
          {children}
        </div>
      )}
    </div>
  );
}
