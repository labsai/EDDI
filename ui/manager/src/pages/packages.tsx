import { useTranslation } from "react-i18next";
import { Package } from "lucide-react";

export function PackagesPage() {
  const { t } = useTranslation();

  return (
    <div className="space-y-6">
      <div>
        <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
          <Package className="h-8 w-8 text-primary" />
          {t("pages.packages.title")}
        </h1>
        <p className="mt-1 text-muted-foreground">
          {t("pages.packages.subtitle")}
        </p>
      </div>
      <div className="flex flex-col items-center justify-center rounded-xl border-2 border-dashed border-border py-16">
        <Package className="h-12 w-12 text-muted-foreground/50" />
        <p className="mt-4 text-lg font-medium text-muted-foreground">
          {t("common.loading")}
        </p>
      </div>
    </div>
  );
}
