import { useState, useMemo, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import {
  Brain,
  Search,
  Trash2,
  AlertTriangle,
  ChevronDown,
  ChevronRight,
  RefreshCw,
  AlertCircle,
  Hash,
  FileText,
  Clock,
  Bot,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { useDebounce } from "@/hooks/use-debounce";
import {
  useUserMemories,
  useDeleteMemory,
  useDeleteAllMemories,
} from "@/hooks/use-user-memory";
import type { UserMemoryEntry } from "@/lib/api/user-memory";

const CATEGORIES = ["all", "preference", "fact", "context"] as const;

const categoryColors: Record<string, string> = {
  preference: "bg-purple-500/10 text-purple-600 dark:text-purple-400",
  fact: "bg-sky-500/10 text-sky-600 dark:text-sky-400",
  context: "bg-amber-500/10 text-amber-600 dark:text-amber-400",
};

const visibilityColors: Record<string, string> = {
  self: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  global: "bg-violet-500/10 text-violet-600 dark:text-violet-400",
  group: "bg-orange-500/10 text-orange-600 dark:text-orange-400",
};

export function UserMemoryPage({ embedded }: { embedded?: boolean } = {}) {
  const { t } = useTranslation();
  const [userId, setUserId] = useState("");
  const [search, setSearch] = useState("");
  const [activeCategory, setActiveCategory] = useState<string>("all");
  const [showDeleteAll, setShowDeleteAll] = useState(false);

  const debouncedUserId = useDebounce(userId.trim(), 500);
  const { data: memories, isLoading, isError, refetch } = useUserMemories(debouncedUserId);
  const deleteMemory = useDeleteMemory();
  const deleteAll = useDeleteAllMemories();

  // Filter and search
  const filtered = useMemo(() => {
    if (!memories) return [];
    let result = memories;
    if (activeCategory !== "all") {
      result = result.filter((m) => m.category === activeCategory);
    }
    if (search.trim()) {
      const q = search.trim().toLowerCase();
      result = result.filter(
        (m) =>
          m.key.toLowerCase().includes(q) ||
          String(m.value).toLowerCase().includes(q),
      );
    }
    return result;
  }, [memories, activeCategory, search]);

  // Category counts — single-pass reduce
  const counts = useMemo(() => {
    if (!memories) return { all: 0, preference: 0, fact: 0, context: 0 };
    return memories.reduce(
      (acc, m) => {
        acc.all++;
        if (m.category === "preference") acc.preference++;
        else if (m.category === "fact") acc.fact++;
        else if (m.category === "context") acc.context++;
        return acc;
      },
      { all: 0, preference: 0, fact: 0, context: 0 },
    );
  }, [memories]);

  const conflictCount = useMemo(
    () => (memories ?? []).filter((m) => m.conflicted).length,
    [memories],
  );

  const handleDeleteEntry = useCallback(
    (entryId: string) => {
      deleteMemory.mutate(entryId, {
        onSuccess: () => toast.success(t("memories.entryDeleted", "Memory entry deleted")),
        onError: (err) => toast.error(err.message),
      });
    },
    [deleteMemory, t],
  );

  const handleDeleteAll = useCallback(() => {
    deleteAll.mutate(debouncedUserId, {
      onSuccess: () => {
        toast.success(t("memories.allDeleted", "All memories deleted for user"));
        setShowDeleteAll(false);
      },
      onError: (err) => toast.error(err.message),
    });
  }, [deleteAll, debouncedUserId, t]);

  return (
    <div className="space-y-6" data-testid="user-memory-page">
      {/* Header — hidden when embedded in tabbed UserDataPage */}
      {!embedded && (
      <div>
        <h1 className="flex items-center gap-3 text-2xl font-bold text-foreground">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-teal-500/10">
            <Brain className="h-5 w-5 text-teal-500" />
          </div>
          {t("memories.title", "User Memory")}
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {t("memories.subtitle", "Browse, search, and manage persistent user memories stored by agents")}
        </p>
      </div>
      )}

      {/* Search controls */}
      <div className="flex flex-col gap-3 sm:flex-row">
        <div className="flex-1">
          <input
            type="text"
            value={userId}
            onChange={(e) => setUserId(e.target.value)}
            placeholder={t("memories.userIdPlaceholder", "Enter User ID...")}
            className="h-10 w-full rounded-lg border border-input bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid="memory-user-id"
          />
        </div>
        <div className="relative flex-1">
          <Search className="absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={t("memories.searchPlaceholder", "Search memories...")}
            className="h-10 w-full rounded-lg border border-input bg-background ps-9 pe-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            data-testid="memory-search"
          />
        </div>
      </div>

      {/* Stats row */}
      {debouncedUserId && memories && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4" data-testid="memory-stats">
          <StatCard label={t("memories.totalEntries", "Total Entries")} value={counts.all} icon={Hash} />
          <StatCard label={t("memories.preferences", "Preferences")} value={counts.preference} icon={Brain} color="text-purple-500" />
          <StatCard label={t("memories.facts", "Facts")} value={counts.fact} icon={FileText} color="text-sky-500" />
          <StatCard label={t("memories.conflicts", "Conflicted")} value={conflictCount} icon={AlertTriangle} color={conflictCount > 0 ? "text-amber-500" : "text-muted-foreground"} />
        </div>
      )}

      {/* Category tabs */}
      {debouncedUserId && memories && (
        <div className="flex flex-wrap gap-1.5 border-b border-border pb-2">
          {CATEGORIES.map((cat) => (
            <button
              key={cat}
              onClick={() => setActiveCategory(cat)}
              className={cn(
                "inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-medium transition-colors",
                activeCategory === cat
                  ? "bg-primary text-primary-foreground"
                  : "bg-secondary/50 text-muted-foreground hover:bg-secondary hover:text-foreground",
              )}
              data-testid={`category-${cat}`}
            >
              {t(`memories.cat_${cat}`, cat.charAt(0).toUpperCase() + cat.slice(1))}
              <span className="rounded-full bg-background/20 px-1.5 text-[10px]">
                {counts[cat as keyof typeof counts]}
              </span>
            </button>
          ))}

          <span className="flex-1" />

          {/* Delete All */}
          <Button
            variant="destructive"
            size="sm"
            onClick={() => setShowDeleteAll(true)}
            disabled={!memories.length || deleteAll.isPending}
            className="gap-1.5 text-xs"
            data-testid="delete-all-memories"
          >
            <Trash2 className="h-3.5 w-3.5" />
            {t("memories.deleteAll", "Delete All")}
          </Button>
        </div>
      )}

      {/* Empty state */}
      {!debouncedUserId && (
        <div className="flex flex-col items-center justify-center rounded-xl border border-dashed py-20 text-muted-foreground">
          <Brain className="h-12 w-12 opacity-30" />
          <p className="mt-4 text-sm">{t("memories.enterUserId", "Enter a User ID to browse their memories")}</p>
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
          <button onClick={() => refetch()} className="mt-3 text-xs text-primary hover:underline">
            {t("common.retry")}
          </button>
        </div>
      )}

      {/* Memory list */}
      {debouncedUserId && !isLoading && !isError && memories && (
        <div className="space-y-2" data-testid="memory-list">
          {filtered.length === 0 ? (
            <div className="rounded-xl border border-dashed py-12 text-center text-sm text-muted-foreground">
              {t("memories.noResults", "No memory entries found")}
            </div>
          ) : (
            filtered.map((entry) => (
              <MemoryRow
                key={entry.id ?? entry.key}
                entry={entry}
                onDelete={handleDeleteEntry}
                isDeleting={deleteMemory.isPending}
              />
            ))
          )}
        </div>
      )}

      {/* Delete All Confirm */}
      <AlertDialog
        open={showDeleteAll}
        onOpenChange={setShowDeleteAll}
        title={t("memories.deleteAllTitle", "Delete All Memories")}
        description={t("memories.deleteAllDesc", "This will permanently delete ALL memory entries for this user. This action cannot be undone.")}
        confirmLabel={t("memories.deleteAllConfirm", "Delete All")}
        variant="destructive"
        onConfirm={handleDeleteAll}
        isPending={deleteAll.isPending}
      />
    </div>
  );
}

// ─── Sub-components ───

function StatCard({
  label,
  value,
  icon: Icon,
  color,
}: {
  label: string;
  value: number;
  icon: React.ElementType;
  color?: string;
}) {
  return (
    <div className="rounded-xl border border-border bg-card p-4">
      <div className="flex items-center gap-2">
        <Icon className={cn("h-4 w-4", color ?? "text-primary")} />
        <span className="text-xs text-muted-foreground">{label}</span>
      </div>
      <p className="mt-1 text-2xl font-bold text-foreground">{value}</p>
    </div>
  );
}

function MemoryRow({
  entry,
  onDelete,
  isDeleting,
}: {
  entry: UserMemoryEntry;
  onDelete: (id: string) => void;
  isDeleting: boolean;
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(false);

  const valuePreview = useMemo(() => {
    if (entry.value === null || entry.value === undefined) return "—";
    if (typeof entry.value === "object") return JSON.stringify(entry.value).slice(0, 100);
    return String(entry.value).slice(0, 100);
  }, [entry.value]);

  return (
    <div
      className={cn(
        "rounded-xl border bg-card transition-colors",
        entry.conflicted && "border-amber-500/30 bg-amber-500/5",
      )}
      data-testid={`memory-entry-${entry.id ?? entry.key}`}
    >
      <div
        className="flex items-center gap-3 px-4 py-3 cursor-pointer"
        onClick={() => setExpanded(!expanded)}
      >
        <button type="button" className="shrink-0 text-muted-foreground">
          {expanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
        </button>

        {/* Key */}
        <span className="min-w-0 flex-1 truncate text-sm font-medium text-foreground">{entry.key}</span>

        {/* Badges */}
        <span className={cn("shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold", categoryColors[entry.category] ?? "bg-muted text-muted-foreground")}>
          {entry.category}
        </span>
        <span className={cn("shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold", visibilityColors[entry.visibility] ?? "bg-muted text-muted-foreground")}>
          {entry.visibility}
        </span>
        {entry.conflicted && (
          <span title={t("memories.conflicted", "Conflicted")}>
            <AlertTriangle className="h-3.5 w-3.5 shrink-0 text-amber-500" />
          </span>
        )}

        {/* Value preview */}
        <span className="hidden text-xs text-muted-foreground sm:block max-w-48 truncate">{valuePreview}</span>

        {/* Delete */}
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            if (entry.id) onDelete(entry.id);
          }}
          disabled={isDeleting || !entry.id}
          className="shrink-0 rounded p-1.5 text-muted-foreground hover:text-destructive transition-colors disabled:opacity-50"
          title={t("common.delete")}
          data-testid={`delete-memory-${entry.id}`}
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </div>

      {/* Expanded detail */}
      {expanded && (
        <div className="border-t border-border px-4 py-3 space-y-2">
          <div>
            <label className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
              {t("memories.value", "Value")}
            </label>
            <pre className="mt-0.5 rounded-lg bg-muted/50 p-3 text-xs text-foreground overflow-x-auto max-h-48 whitespace-pre-wrap break-all">
              {typeof entry.value === "object"
                ? JSON.stringify(entry.value, null, 2)
                : String(entry.value ?? "—")}
            </pre>
          </div>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 text-xs">
            <div>
              <span className="text-[10px] text-muted-foreground flex items-center gap-1"><Bot className="h-3 w-3" /> {t("memories.sourceAgent", "Source Agent")}</span>
              <p className="font-mono text-foreground truncate">{entry.sourceAgentId ?? "—"}</p>
            </div>
            <div>
              <span className="text-[10px] text-muted-foreground flex items-center gap-1"><Hash className="h-3 w-3" /> {t("memories.accessCount", "Access Count")}</span>
              <p className="text-foreground">{entry.accessCount ?? 0}</p>
            </div>
            <div>
              <span className="text-[10px] text-muted-foreground flex items-center gap-1"><Clock className="h-3 w-3" /> {t("memories.created", "Created")}</span>
              <p className="text-foreground">{entry.createdAt ? new Date(entry.createdAt).toLocaleString() : "—"}</p>
            </div>
            <div>
              <span className="text-[10px] text-muted-foreground flex items-center gap-1"><Clock className="h-3 w-3" /> {t("memories.updated", "Updated")}</span>
              <p className="text-foreground">{entry.updatedAt ? new Date(entry.updatedAt).toLocaleString() : "—"}</p>
            </div>
          </div>
          {entry.sourceConversationId && (
            <div className="text-xs">
              <span className="text-[10px] text-muted-foreground">{t("memories.sourceConversation", "Source Conversation")}</span>
              <p className="font-mono text-foreground">{entry.sourceConversationId}</p>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
