import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { AlertTriangle, Copy, Globe, Plug, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import {
  AuthTypeBadge,
  BindingBadge,
} from "@/components/connections/connection-badges";
import type { EnrichedConnectionDescriptor } from "@/lib/api/connections";

interface ConnectionCardProps {
  connection: EnrichedConnectionDescriptor;
  onDelete: (id: string, version: number) => void;
  onDuplicate: (id: string, version: number) => void;
}

export function ConnectionCard({
  connection,
  onDelete,
  onDuplicate,
}: ConnectionCardProps) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const to = `/manage/connections/${connection.id}?version=${connection.version}`;

  return (
    <div
      data-testid={`connection-card-${connection.id}`}
      className="group relative flex cursor-pointer flex-col gap-3 rounded-xl border border-border/50 bg-card p-5 transition-all hover:border-primary/30 hover:shadow-lg hover:shadow-primary/5"
      onClick={() => navigate(to)}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => {
        // Only the card itself. Enter and Space bubble from the Duplicate and
        // Delete buttons nested inside it, so an unguarded handler navigated
        // away instead of deleting — and `preventDefault` suppressed the button
        // activation on the way past.
        if (e.target !== e.currentTarget) return;
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          navigate(to);
        }
      }}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 items-center gap-3">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
            <Plug className="h-5 w-5" aria-hidden="true" />
          </div>
          <div className="min-w-0">
            <h3 className="truncate font-semibold leading-tight text-foreground">
              {connection.connectionName ||
                connection.name ||
                t("connections.unnamed", "Unnamed connection")}
            </h3>
            {connection.description && (
              <p className="mt-0.5 truncate text-xs text-muted-foreground">
                {connection.description}
              </p>
            )}
          </div>
        </div>
        <div className="flex items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
          <Button
            variant="ghost"
            size="icon"
            className="h-7 w-7"
            onClick={(e) => {
              e.stopPropagation();
              onDuplicate(connection.id, connection.version);
            }}
            title={t("common.duplicate", "Duplicate")}
            aria-label={t("common.duplicate", "Duplicate")}
          >
            <Copy className="h-3.5 w-3.5" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            className="h-7 w-7 text-destructive hover:text-destructive"
            onClick={(e) => {
              e.stopPropagation();
              onDelete(connection.id, connection.version);
            }}
            title={t("common.delete", "Delete")}
            aria-label={t("common.delete", "Delete")}
          >
            <Trash2 className="h-3.5 w-3.5" />
          </Button>
        </div>
      </div>

      {/* A config that would not load still gets a row: a connection an admin
          cannot see is one they cannot delete either, and an empty card is a
          better answer than a missing one. */}
      {connection.unreadable ? (
        <p className="flex items-center gap-1.5 text-xs text-amber-600 dark:text-amber-400">
          <AlertTriangle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          {t("connections.unreadable", "This connection could not be read.")}
        </p>
      ) : (
        <div className="flex flex-wrap items-center gap-1.5">
          <AuthTypeBadge authType={connection.authType} />
          <BindingBadge binding={connection.binding} />
          {connection.origins[0] && (
            <Badge variant="secondary" className="gap-1 font-mono text-[10px]">
              <Globe className="h-3 w-3" aria-hidden="true" />
              <span dir="ltr">{connection.origins[0]}</span>
              {connection.origins.length > 1 && (
                <span>
                  {t("connections.moreOrigins", {
                    more: connection.origins.length - 1,
                    defaultValue: "+{{more}}",
                  })}
                </span>
              )}
            </Badge>
          )}
        </div>
      )}

      <div className="flex items-center justify-between border-t border-border/30 pt-1 text-xs text-muted-foreground">
        <span>
          {t("common.versionShort", "v{{version}}", { version: connection.version })}
        </span>
        {/* The viewer's chosen language, not the browser's: the two differ
            whenever somebody picks a locale in the top bar. */}
        <span>
          {new Date(connection.lastModifiedOn).toLocaleDateString(i18n.language)}
        </span>
      </div>
    </div>
  );
}
