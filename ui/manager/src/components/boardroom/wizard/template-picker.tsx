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
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500",
              isSelected
                ? "scale-[1.02] border-indigo-500 bg-indigo-50 ring-2 ring-indigo-500/30 dark:bg-indigo-500/10"
                : "border-slate-200 hover:border-indigo-300 dark:border-slate-700 dark:hover:border-indigo-600",
            )}
          >
            {/* Selected checkmark */}
            {isSelected && (
              <div className="absolute end-3 top-3 flex h-5 w-5 items-center justify-center rounded-full bg-indigo-500 text-white">
                <Check className="h-3 w-3" />
              </div>
            )}

            <span className="text-3xl">{tpl.icon}</span>
            <h3 className="mt-2 text-base font-semibold text-foreground">
              {tpl.name}
            </h3>
            <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
              {tpl.description}
            </p>
            <p className="mt-2 text-xs text-slate-400 dark:text-slate-500">
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
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500",
          selected === "custom"
            ? "scale-[1.02] border-indigo-500 bg-indigo-50 ring-2 ring-indigo-500/30 dark:bg-indigo-500/10"
            : "border-dashed border-slate-300 hover:border-indigo-300 dark:border-slate-600 dark:hover:border-indigo-600",
        )}
      >
        {selected === "custom" && (
          <div className="absolute end-3 top-3 flex h-5 w-5 items-center justify-center rounded-full bg-indigo-500 text-white">
            <Check className="h-3 w-3" />
          </div>
        )}

        <span className="text-3xl">✨</span>
        <h3 className="mt-2 text-base font-semibold text-foreground">
          {t("boardroom.wizard.customTemplate", "Custom")}
        </h3>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
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
