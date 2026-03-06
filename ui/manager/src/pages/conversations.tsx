import { useTranslation } from "react-i18next";
import { MessageSquare } from "lucide-react";

export function ConversationsPage() {
  const { t } = useTranslation();

  return (
    <div className="space-y-6">
      <div>
        <h1 className="flex items-center gap-2 text-3xl font-bold text-foreground">
          <MessageSquare className="h-8 w-8 text-primary" />
          {t("pages.conversations.title")}
        </h1>
        <p className="mt-1 text-muted-foreground">
          {t("pages.conversations.subtitle")}
        </p>
      </div>
      <div className="flex flex-col items-center justify-center rounded-xl border-2 border-dashed border-border py-16">
        <MessageSquare className="h-12 w-12 text-muted-foreground/50" />
        <p className="mt-4 text-lg font-medium text-muted-foreground">
          {t("common.loading")}
        </p>
      </div>
    </div>
  );
}
