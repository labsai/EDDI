import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router-dom";
import {
  FileCode,
  GitBranch,
  Globe,
  MessageSquareText,
  BookOpen,
  BookOpenCheck,
  Brain,
  Settings,
  Plug,
  ChevronRight,
} from "lucide-react";
import { RESOURCE_TYPES } from "@/lib/api/resources";
import { useResourceDescriptors } from "@/hooks/use-resources";
import { cn } from "@/lib/utils";
import type { LucideIcon } from "lucide-react";
import { useOnboarding } from "@/hooks/use-onboarding";

const ICON_MAP: Record<string, LucideIcon> = {
  GitBranch,
  Globe,
  MessageSquareText,
  BookOpen,
  BookOpenCheck,
  Brain,
  Settings,
  Plug,
};

function ResourceTypeCard({
  slug,
  iconName,
  labelKey,
}: {
  slug: string;
  iconName: string;
  labelKey: string;
}) {
  const { t } = useTranslation();
  const { data: items } = useResourceDescriptors(slug, 1000, 0, "");
  const Icon = ICON_MAP[iconName] ?? FileCode;
  const count = items?.length ?? 0;

  return (
    <Link
      to={`/manage/resources/${slug}`}
      className={cn(
        "group flex flex-col rounded-xl border bg-card p-6 shadow-sm transition-all duration-200",
        "hover:shadow-lg hover:border-primary/30 hover:-translate-y-0.5"
      )}
      data-testid={`resource-type-${slug}`}
    >
      <div className="flex items-start justify-between">
        <div className="rounded-xl bg-primary/10 p-3">
          <Icon className="h-6 w-6 text-primary" />
        </div>
        <ChevronRight className="h-5 w-5 text-muted-foreground/40 transition-transform group-hover:translate-x-0.5 group-hover:text-primary" />
      </div>

      <div className="mt-4">
        <h3 className="text-lg font-semibold text-foreground">
          {t(`${labelKey}.name`)}
        </h3>
        <p className="mt-1 text-sm text-muted-foreground line-clamp-2">
          {t(`${labelKey}.description`)}
        </p>
      </div>

      <div className="mt-auto pt-4 border-t border-border">
        <span className="text-sm font-medium text-primary">
          {t("resources.itemCount", {
            count,
            defaultValue: `${count} item(s)`,
          })}
        </span>
      </div>
    </Link>
  );
}

export function ResourcesPage() {
  const { t } = useTranslation();

  // Auto-trigger resources onboarding chapter
  const maybeAutoStart = useOnboarding((s) => s.maybeAutoStart);
  useEffect(() => {
    const timer = setTimeout(() => maybeAutoStart("resources"), 500);
    return () => clearTimeout(timer);
  }, [maybeAutoStart]);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
          <FileCode className="h-8 w-8 text-primary" />
          {t("pages.resources.title")}
        </h1>
        <p className="mt-1 text-muted-foreground">
          {t("pages.resources.subtitle")}
        </p>
      </div>

      {/* Resource type grid */}
      <div
        className={cn(
          "grid gap-4",
          "grid-cols-1 sm:grid-cols-2 lg:grid-cols-3"
        )}
        data-testid="resource-types-grid"
      >
        {RESOURCE_TYPES.map((rt) => (
          <ResourceTypeCard
            key={rt.slug}
            slug={rt.slug}
            iconName={rt.icon}
            labelKey={rt.labelKey}
          />
        ))}
      </div>
    </div>
  );
}
