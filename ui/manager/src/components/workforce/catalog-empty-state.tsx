import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { LibraryBig } from "lucide-react";
import { Button } from "@/components/ui/button";

/**
 * What an empty catalog says. Listing is opt-in, so "nothing listed" is the
 * normal state of a fresh deployment: someone who can list things (`eddi-editor`
 * or `eddi-admin`) is told how; an end user gets a neutral sentence, because
 * there is nothing they can do about it and the mechanics are not theirs.
 */
export function CatalogEmptyState({ canAuthor }: { canAuthor: boolean }) {
  const { t } = useTranslation();
  return (
    <div
      data-testid={canAuthor ? "catalog-empty-author" : "catalog-empty-user"}
      className="flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-border p-8 text-center"
    >
      <LibraryBig className="h-10 w-10 text-muted-foreground/60" aria-hidden />
      <p className="text-sm font-medium text-foreground">
        {t("Workforce.catalog.emptyTitle", "Nothing to show here yet")}
      </p>
      {canAuthor ? (
        <>
          <p className="max-w-md text-sm text-muted-foreground">
            {t(
              "Workforce.catalog.emptyAuthorHint",
              "End users only see agents and teams that are listed. Open an agent or a group in the Manager and switch on \"Listed in the catalog\" — or, with workspaces on, make it published or internal. People still need access to use it.",
            )}
          </p>
          <Button asChild variant="outline" size="sm">
            <Link to="/manage/agents" data-testid="catalog-empty-manage-agents">
              {t("Workforce.catalog.openAgents", "Open agents in the Manager")}
            </Link>
          </Button>
        </>
      ) : (
        <p className="max-w-md text-sm text-muted-foreground">
          {t(
            "Workforce.catalog.emptyUserHint",
            "No agents or teams have been made available to you yet. Check back later.",
          )}
        </p>
      )}
    </div>
  );
}
