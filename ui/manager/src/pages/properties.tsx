import { useState, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import {
  Database,
  Search,
  Trash2,
  RefreshCw,
  AlertCircle,
  Hash,
  Type,
  ToggleRight,
  List,
  Braces,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { useDebounce } from "@/hooks/use-debounce";
import { useUserProperties, useDeleteProperties } from "@/hooks/use-properties";
import type { Property } from "@/lib/api/properties";

function getValueDisplay(prop: Property): { type: string; value: string; icon: React.ElementType } {
  if (prop.valueString != null) return { type: "string", value: String(prop.valueString), icon: Type };
  if (prop.valueInt != null) return { type: "number", value: String(prop.valueInt), icon: Hash };
  if (prop.valueFloat != null) return { type: "number", value: String(prop.valueFloat), icon: Hash };
  if (prop.valueBoolean != null) return { type: "boolean", value: String(prop.valueBoolean), icon: ToggleRight };
  if (prop.valueList != null) return { type: "array", value: JSON.stringify(prop.valueList), icon: List };
  if (prop.valueObject != null) return { type: "object", value: JSON.stringify(prop.valueObject), icon: Braces };
  return { type: "null", value: "—", icon: Type };
}

const typeColors: Record<string, string> = {
  string: "text-emerald-600 bg-emerald-500/10 dark:text-emerald-400",
  number: "text-sky-600 bg-sky-500/10 dark:text-sky-400",
  boolean: "text-violet-600 bg-violet-500/10 dark:text-violet-400",
  array: "text-amber-600 bg-amber-500/10 dark:text-amber-400",
  object: "text-rose-600 bg-rose-500/10 dark:text-rose-400",
  null: "text-muted-foreground bg-muted",
};

export function PropertiesPage({ embedded }: { embedded?: boolean } = {}) {
  const { t } = useTranslation();
  const [userId, setUserId] = useState("");
  const [search, setSearch] = useState("");
  const [showDeleteAll, setShowDeleteAll] = useState(false);

  const debouncedUserId = useDebounce(userId.trim(), 500);
  const { data: properties, isLoading, isError, refetch } = useUserProperties(debouncedUserId);
  const deleteMutation = useDeleteProperties();

  const hasProperties = properties && Object.keys(properties).length > 0;

  // Flatten and filter
  const entries = useMemo(() => {
    if (!properties) return [];
    const items = Object.entries(properties).map(([key, prop]) => {
      const display = getValueDisplay(prop);
      return { key, prop, ...display };
    });
    if (search.trim()) {
      const q = search.trim().toLowerCase();
      return items.filter(
        (e) => e.key.toLowerCase().includes(q) || e.value.toLowerCase().includes(q),
      );
    }
    return items;
  }, [properties, search]);

  return (
    <div className="space-y-6" data-testid="properties-page">
      {/* Header — hidden when embedded in tabbed UserDataPage */}
      {!embedded && (
      <div>
        <h1 className="flex items-center gap-3 text-2xl font-bold text-foreground">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-indigo-500/10">
            <Database className="h-5 w-5 text-indigo-500" />
          </div>
          {t("properties.title", "User Properties")}
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {t("properties.subtitle", "Browse and manage longTerm slot-filling properties stored per user")}
        </p>
      </div>
      )}

      {/* Controls */}
      <div className="flex flex-col gap-3 sm:flex-row">
        <div className="flex-1">
          <input
            type="text"
            value={userId}
            onChange={(e) => setUserId(e.target.value)}
            placeholder={t("properties.userIdPlaceholder", "Enter User ID...")}
            className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid="properties-user-id"
          />
        </div>
        <div className="relative flex-1">
          <Search className="absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t("properties.searchPlaceholder", "Filter properties...")}
            className="h-10 w-full rounded-lg border border-input bg-background ps-9 pe-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid="properties-search"
          />
        </div>
        {hasProperties && (
          <Button
            variant="destructive"
            size="sm"
            onClick={() => setShowDeleteAll(true)}
            disabled={deleteMutation.isPending}
            className="gap-1.5 self-end"
            data-testid="delete-all-props"
          >
            <Trash2 className="h-3.5 w-3.5" />
            {t("properties.deleteAll", "Delete All")}
          </Button>
        )}
      </div>

      {/* Empty state */}
      {!debouncedUserId && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed py-20 text-muted-foreground">
          <Database className="h-12 w-12 opacity-30" />
          <p className="mt-4 text-sm">{t("properties.enterUserId", "Enter a User ID to view their properties")}</p>
        </div>
      )}

      {/* Loading */}
      {debouncedUserId && isLoading && (
        <div className="flex items-center justify-center py-16">
          <RefreshCw className="h-8 w-8 animate-spin text-primary" />
        </div>
      )}

      {/* Error */}
      {debouncedUserId && isError && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-destructive/30 bg-destructive/5 py-12">
          <AlertCircle className="h-10 w-10 text-destructive" />
          <p className="mt-3 text-sm text-destructive">{t("common.error")}</p>
          <button onClick={() => refetch()} className="mt-3 text-xs text-primary hover:underline">{t("common.retry")}</button>
        </div>
      )}

      {/* Table */}
      {debouncedUserId && !isLoading && !isError && properties && (
        <div className="rounded-xl border border-border bg-card overflow-hidden" data-testid="properties-table">
          {entries.length === 0 ? (
            <div className="py-12 text-center text-sm text-muted-foreground">
              {t("properties.noResults", "No properties found")}
            </div>
          ) : (
            <div className="divide-y divide-border">
              {/* Header */}
              <div className="hidden sm:grid sm:grid-cols-12 gap-3 px-4 py-2 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground bg-muted/30">
                <span className="col-span-4">{t("properties.key", "Key")}</span>
                <span className="col-span-1">{t("properties.type", "Type")}</span>
                <span className="col-span-7">{t("properties.value", "Value")}</span>
              </div>
              {entries.map((entry) => (
                <div
                  key={entry.key}
                  className="grid grid-cols-1 gap-1 px-4 py-3 sm:grid-cols-12 sm:gap-3 sm:items-center hover:bg-secondary/30 transition-colors"
                  data-testid={`property-${entry.key}`}
                >
                  <span className="col-span-4 text-sm font-medium text-foreground font-mono truncate">{entry.key}</span>
                  <span className={cn("col-span-1 inline-flex w-fit rounded-full px-2 py-0.5 text-[10px] font-semibold", typeColors[entry.type])}>
                    {entry.type}
                  </span>
                  <span className="col-span-7 text-xs text-muted-foreground font-mono truncate" title={entry.value}>
                    {entry.value.length > 200 ? entry.value.slice(0, 200) + "…" : entry.value}
                  </span>
                </div>
              ))}
            </div>
          )}
          <div className="border-t border-border px-4 py-2 text-xs text-muted-foreground bg-muted/20">
            {t("properties.showingCount", "{{count}} properties", { count: entries.length })}
          </div>
        </div>
      )}

      <AlertDialog
        open={showDeleteAll}
        onOpenChange={setShowDeleteAll}
        title={t("properties.deleteAllTitle", "Delete All Properties")}
        description={t("properties.deleteAllDesc", "This will permanently delete ALL properties for this user.")}
        confirmLabel={t("common.delete")}
        variant="destructive"
        onConfirm={() => {
          deleteMutation.mutate(debouncedUserId, {
            onSuccess: () => {
              toast.success(t("properties.deleted", "Properties deleted"));
              setShowDeleteAll(false);
            },
            onError: (err) => toast.error(err.message),
          });
        }}
        isPending={deleteMutation.isPending}
      />
    </div>
  );
}
