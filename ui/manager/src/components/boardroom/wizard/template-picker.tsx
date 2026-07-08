import { useTranslation } from "react-i18next";
import { Check } from "lucide-react";
import { cn } from "@/lib/utils";
import { getGroupTemplates } from "@/lib/group-templates";

interface TemplatePickerProps {
  selected: string | null; // template key or "custom"
  onSelect: (key: string) => void;
}

function TemplatePicker({ selected, onSelect }: TemplatePickerProps) {
  const { t } = useTranslation();
  const templates = getGroupTemplates(t);

  return (
    <div role="group" aria-label={t("boardroom.wizard.templates", "Discussion templates")} className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {templates.map((tpl) => {
        const isSelected = selected === tpl.key;
        return (
          <button
            key={tpl.key}
            type="button"
            aria-pressed={isSelected}
            onClick={() => onSelect(tpl.key)}
            className={cn(
              "relative rounded-xl border p-5 text-start transition-all duration-150",
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              isSelected
                ? "scale-[1.02] border-primary bg-primary/10 ring-2 ring-ring/30"
                : "border-border hover:border-primary/50",
            )}
          >
            {/* Selected checkmark */}
            {isSelected && (
              <div className="absolute end-3 top-3 flex h-5 w-5 items-center justify-center rounded-full bg-primary text-primary-foreground">
                <Check className="h-3 w-3" />
              </div>
            )}

            <span className="text-3xl">{tpl.icon}</span>
            <h3 className="mt-2 text-base font-semibold text-foreground">
              {tpl.name}
            </h3>
            <p className="mt-1 text-sm text-muted-foreground">
              {tpl.description}
            </p>
            <p className="mt-2 text-xs text-muted-foreground">
              {t("boardroom.wizard.advisorCount", "{{count}} advisors", {
                count: tpl.roles.length,
              })}
            </p>
          </button>
        );
      })}

      {/* Custom card */}
      <button
        type="button"
        aria-pressed={selected === "custom"}
        onClick={() => onSelect("custom")}
        className={cn(
          "relative rounded-xl border p-5 text-start transition-all duration-150",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
          selected === "custom"
            ? "scale-[1.02] border-primary bg-primary/10 ring-2 ring-ring/30"
            : "border-dashed border-border hover:border-primary/50",
        )}
      >
        {selected === "custom" && (
          <div className="absolute end-3 top-3 flex h-5 w-5 items-center justify-center rounded-full bg-primary text-primary-foreground">
            <Check className="h-3 w-3" />
          </div>
        )}

        <span className="text-3xl">✨</span>
        <h3 className="mt-2 text-base font-semibold text-foreground">
          {t("boardroom.wizard.customTemplate", "Custom")}
        </h3>
        <p className="mt-1 text-sm text-muted-foreground">
          {t(
            "boardroom.wizard.customTemplateDesc",
            "Start from scratch with your own configuration",
          )}
        </p>
      </button>
    </div>
  );
}

export { TemplatePicker };
export type { TemplatePickerProps };
