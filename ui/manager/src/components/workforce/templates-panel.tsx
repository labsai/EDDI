import { useTranslation } from "react-i18next";
import { Bookmark, Users, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { cn } from "@/lib/utils";
import { useTemplates, type DiscussionTemplate } from "@/hooks/use-templates";
import { styleLabel } from "@/lib/discussion-styles";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

interface TemplatesPanelProps {
  onUseTemplate: (template: DiscussionTemplate) => void;
}

export function TemplatesPanel({ onUseTemplate }: TemplatesPanelProps) {
  const { t } = useTranslation();
  const { templates, deleteTemplate } = useTemplates();


  return (
    <section className="space-y-3">
      {/* Header */}
      <div className="flex items-center gap-2">
        <Bookmark className="h-4 w-4 text-muted-foreground" />
        <h3 className="text-sm font-semibold">
          {t("Workforce.templates", "Templates")}
        </h3>
        {templates.length > 0 && (
          <Badge variant="secondary" className="text-xs">
            {templates.length}
          </Badge>
        )}
      </div>

      {/* Empty state */}
      {templates.length === 0 && (
        <p className="text-sm text-muted-foreground">
          {t(
            "Workforce.templatesEmpty",
            "Save a task force as a template to quickly recreate it later."
          )}
        </p>
      )}

      {/* Template cards */}
      {templates.length > 0 && (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-3 pb-2">
          {templates.map((template) => (
            <div
              key={template.id}
              className={cn(
                "group/card relative min-w-[220px] rounded-xl border bg-card p-4",
                "hover:border-primary/30 transition-all"
              )}
            >
              {/* Delete button — visible on hover / focus */}
              <Button
                variant="ghost"
                size="icon"
                className={cn(
                  "absolute top-1 end-1 h-7 w-7 opacity-0",
                  "group-hover/card:opacity-100 focus-visible:opacity-100 transition-opacity",
                  "text-muted-foreground hover:text-destructive"
                )}
                onClick={(e) => {
                  e.stopPropagation();
                  if (window.confirm(t("Workforce.templates.deleteConfirm", "Delete this template?"))) {
                    deleteTemplate(template.id);
                    toast.success(t("Workforce.templates.deleted", "Template deleted"));
                  }
                }}
              >
                <Trash2 className="h-3.5 w-3.5" />
              </Button>

              {/* Name */}
              <p className="font-medium truncate pe-6">{template.name}</p>

              {/* Style badge */}
              <Badge variant="outline" className="mt-2 text-xs">
                {styleLabel(template.style, t)}
              </Badge>

              {/* Member count */}
              <div className="mt-2 flex items-center gap-1.5 text-xs text-muted-foreground">
                <Users className="h-3.5 w-3.5" />
                <span>
                  {t("Workforce.memberCount", "{{count}} members", {
                    count: template.members.length,
                  })}
                </span>
              </div>

              {/* Use button */}
              <Button
                size="sm"
                className="mt-3 w-full"
                onClick={() => onUseTemplate(template)}
              >
                {t("Workforce.useTemplate", "Use")}
              </Button>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
