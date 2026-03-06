import { useTranslation } from "react-i18next";
import { Bot } from "lucide-react";

export function BotsPage() {
  const { t } = useTranslation();

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
            <Bot className="h-8 w-8 text-primary" />
            {t("pages.bots.title")}
          </h1>
          <p className="mt-1 text-muted-foreground">
            {t("pages.bots.subtitle")}
          </p>
        </div>
        <button className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground transition-colors hover:bg-primary/90">
          {t("common.create")}
        </button>
      </div>

      {/* Empty state */}
      <div className="flex flex-col items-center justify-center rounded-xl border-2 border-dashed border-border py-16">
        <Bot className="h-12 w-12 text-muted-foreground/50" />
        <p className="mt-4 text-lg font-medium text-muted-foreground">
          {t("common.loading")}
        </p>
      </div>
    </div>
  );
}
