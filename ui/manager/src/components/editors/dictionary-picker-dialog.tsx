import { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { BookOpen, Check, Loader2, Plus, Search } from "lucide-react";
import { toast } from "sonner";
import { getErrorMessage } from "@/lib/api-client";
import { cn } from "@/lib/utils";
import { AccessibleDialog } from "@/components/ui/accessible-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/shared/empty-state";
import { ErrorState } from "@/components/shared/error-state";
import { useDebounce } from "@/hooks/use-debounce";
import { useCreateResource, useResourceDescriptors } from "@/hooks/use-resources";
import { updateDescriptor } from "@/lib/api/descriptors";
import { buildDictionaryUri, dictionaryIdFromUri, parseDictionaryUri } from "@/lib/dictionary-uri";
import type { AgentDescriptor } from "@/lib/api/agents";

export interface DictionaryPickerDialogProps {
  open: boolean;
  onClose: () => void;
  /** Called with the `eddi://` URI to link. The dialog closes itself after. */
  onSelect: (uri: string) => void;
  /** Already-linked URIs — those dictionaries are shown as added, not addable twice. */
  existingUris?: readonly string[];
}

type Mode = "browse" | "create" | "manual";

/**
 * Pick — or create — the dictionary resource a parser links to.
 *
 * Replaces the field that asked an operator to type
 * `eddi://ai.labs.dictionary/dictionarystore/dictionaries/<id>?version=<v>`
 * from memory. Nothing in the UI ever showed those ids, so the only way to
 * fill it in was to open the dictionary list in another tab and copy one out —
 * and a typo produced a parser that saved cleanly and silently resolved no
 * dictionary at runtime.
 *
 * Same two-way shape as `AddExtensionDialog`'s config step (pick an existing
 * resource, or create an empty one and link it immediately), because that is
 * the flow this screen's users already know from the pipeline builder. The
 * URI field survives as `"manual"` — pinning a specific version, or pointing
 * at a resource this list cannot reach, is still legitimate.
 */
export function DictionaryPickerDialog({
  open,
  onClose,
  onSelect,
  existingUris = [],
}: DictionaryPickerDialogProps) {
  const { t } = useTranslation();
  const [mode, setMode] = useState<Mode>("browse");
  const [search, setSearch] = useState("");
  const debouncedSearch = useDebounce(search, 300);
  const [manualUri, setManualUri] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  /** Covers the POST *and* the rename that follows it — `mutation.isPending`
   *  alone re-enables Create the moment the POST resolves, and a second click
   *  in that window creates a second dictionary. */
  const [creating, setCreating] = useState(false);

  // Held back until the dialog is actually open: this component stays mounted
  // for the whole life of the editor, and listing every dictionary on a page
  // load where nobody touches Add is a request for nothing.
  const descriptors = useResourceDescriptors("dictionary", 100, 0, debouncedSearch, open);
  const createMutation = useCreateResource("dictionary");

  // Every open starts clean: a half-typed name or an abandoned search from the
  // previous open is never what the operator meant this time.
  useEffect(() => {
    if (!open) return;
    setMode("browse");
    setSearch("");
    setManualUri("");
    setName(t("dictionaryPicker.defaultName", "New Dictionary"));
    setDescription("");
  }, [open, t]);

  /** Ids already linked — matched WITHOUT the version, since linking the same
   *  dictionary twice at two versions is a configuration mistake, not a use case. */
  const linkedIds = useMemo(
    () => new Set(existingUris.map((uri) => dictionaryIdFromUri(uri)).filter(Boolean)),
    [existingUris],
  );

  const items = useMemo(() => {
    return (descriptors.data ?? []).map((d: AgentDescriptor) => {
      const { id, version } = parseDictionaryUri(d.resource);
      return {
        id,
        version: version ?? 1,
        name: d.name || id,
        description: d.description,
        resource: d.resource,
      };
    });
  }, [descriptors.data]);

  /** Nothing to list — either this instance has no dictionaries at all, or the
   *  search matched none. The two say different things, so they render differently. */
  const showEmptyState = !descriptors.isLoading && !descriptors.isError && items.length === 0;
  /** The "no dictionaries yet" state carries its own Create action. */
  const createOfferedAbove = showEmptyState && !debouncedSearch;

  const handlePick = useCallback(
    (uri: string) => {
      onSelect(uri);
      onClose();
    },
    [onSelect, onClose],
  );

  const handleCreate = useCallback(async () => {
    setCreating(true);
    try {
      // Created WITHOUT name/description, then named in a second step, rather
      // than letting `useCreateResource` do both: it awaits the descriptor
      // PATCH inside the mutation, so a failure there rejects a call whose
      // POST already succeeded. The dictionary would exist, unnamed and
      // unlinked, and the obvious retry would create a second one. A rename
      // that fails is worth a warning, not an orphan.
      const result = await createMutation.mutateAsync({ body: {} });
      // The Location header is the only place the new id exists — the POST
      // response carries no body.
      const { id, version } = parseDictionaryUri(result.location ?? "");
      if (!id) {
        throw new Error(
          t("dictionaryPicker.createFailed", "The dictionary was created but could not be linked."),
        );
      }

      let named = true;
      if (name.trim() || description.trim()) {
        try {
          await updateDescriptor(id, version ?? 1, {
            name: name.trim() || undefined,
            description: description.trim() || undefined,
          });
        } catch {
          named = false;
        }
      }

      if (named) {
        toast.success(t("dictionaryPicker.created", "Dictionary created and linked"));
      } else {
        toast.warning(
          t(
            "dictionaryPicker.createdUnnamed",
            "Dictionary created and linked, but its name could not be saved — rename it in the dictionary editor.",
          ),
        );
      }
      handlePick(buildDictionaryUri(id, version ?? 1));
    } catch (err) {
      toast.error(getErrorMessage(err));
    } finally {
      setCreating(false);
    }
  }, [createMutation, name, description, handlePick, t]);

  return (
    <AccessibleDialog
      open={open}
      onClose={onClose}
      title={
        mode === "create"
          ? t("dictionaryPicker.createTitle", "New dictionary")
          : t("dictionaryPicker.title", "Add regular dictionary")
      }
      maxWidth="max-w-lg"
      testId="dictionary-picker-dialog"
    >
      {mode === "create" ? (
        <div className="space-y-4 p-5">
          <p className="text-sm text-muted-foreground">
            {t(
              "dictionaryPicker.createHint",
              "It is created empty and linked to this parser right away — add words, phrases and patterns in the dictionary editor.",
            )}
          </p>
          <div>
            <label htmlFor="dict-picker-name" className="mb-1.5 block text-sm font-medium text-foreground">
              {t("common.name", "Name")}
            </label>
            <Input
              id="dict-picker-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              // The dialog only traps focus once, when it opens; switching
              // mode has to place the caret in the field it just revealed.
              autoFocus
              data-testid="dict-create-name"
            />
          </div>
          <div>
            <label htmlFor="dict-picker-description" className="mb-1.5 block text-sm font-medium text-foreground">
              {t("common.description", "Description")}
            </label>
            <textarea
              id="dict-picker-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={2}
              placeholder={t(
                "dictionaryPicker.descriptionPlaceholder",
                "What this dictionary teaches the parser…",
              )}
              className="w-full resize-none rounded-lg border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              data-testid="dict-create-description"
            />
          </div>
          <div className="flex justify-end gap-2">
            <Button variant="ghost" size="sm" onClick={() => setMode("browse")} data-testid="dict-create-back">
              {t("common.cancel", "Cancel")}
            </Button>
            <Button
              size="sm"
              onClick={handleCreate}
              disabled={creating}
              data-testid="dict-create-submit"
            >
              {creating ? <Loader2 className="animate-spin" /> : <Plus />}
              {t("common.create", "Create")}
            </Button>
          </div>
        </div>
      ) : (
        <div className="space-y-3 p-5">
          <div className="relative">
            <Search
              className="absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground"
              aria-hidden="true"
            />
            <Input
              className="ps-9"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t("dictionaryPicker.searchPlaceholder", "Search dictionaries…")}
              aria-label={t("dictionaryPicker.searchPlaceholder", "Search dictionaries…")}
              data-testid="dict-search"
            />
          </div>

          {descriptors.isLoading && (
            <div className="space-y-2">
              <Skeleton className="h-14 w-full" />
              <Skeleton className="h-14 w-full" />
              <Skeleton className="h-14 w-full" />
            </div>
          )}

          {descriptors.isError && (
            <ErrorState
              message={t("common.loadError", "Failed to load data")}
              onRetry={() => void descriptors.refetch()}
            />
          )}

          {/* Outside the scroll box on purpose: `EmptyState` is taller than the
              list is allowed to grow, so nesting it made the "no dictionaries
              yet" message itself scroll. */}
          {showEmptyState &&
            (debouncedSearch ? (
              <p className="py-8 text-center text-sm text-muted-foreground" data-testid="dict-no-results">
                {t("common.noResults", "No results found")}
              </p>
            ) : (
              <EmptyState
                icon={BookOpen}
                title={t("dictionaryPicker.empty", "No dictionaries yet")}
                description={t(
                  "dictionaryPicker.emptyDescription",
                  "A dictionary teaches the parser your own words, phrases and patterns.",
                )}
                actionLabel={t("dictionaryPicker.create", "Create dictionary")}
                onAction={() => setMode("create")}
              />
            ))}

          {/* Viewport-relative as well as fixed: 18rem of list is right on a
              laptop and taller than the whole window on a landscape phone. */}
          <div className="max-h-[min(18rem,40dvh)] space-y-1 overflow-y-auto" data-testid="dict-list">
            {items.map((item) => {
              const alreadyLinked = linkedIds.has(item.id);
              return (
                <button
                  key={item.resource}
                  type="button"
                  disabled={alreadyLinked}
                  onClick={() => handlePick(item.resource)}
                  className="flex w-full items-center gap-3 rounded-lg border border-border bg-card px-3 py-2.5 text-start transition-colors hover:border-primary/40 hover:bg-secondary disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:border-border disabled:hover:bg-card"
                  data-testid={`dict-option-${item.id}`}
                >
                  <BookOpen className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium text-foreground">{item.name}</span>
                    {item.description && (
                      <span className="block truncate text-xs text-muted-foreground">{item.description}</span>
                    )}
                    {/* Rendered, not a `title`: two dictionaries can carry the
                        same name, and the id is the only thing that tells them
                        apart — a tooltip says that to a mouse and to nobody
                        else. */}
                    <span className="block truncate font-mono text-[10px] text-muted-foreground">
                      {item.id} · v{item.version}
                    </span>
                  </span>
                  {alreadyLinked ? (
                    <span className="inline-flex shrink-0 items-center gap-1 text-xs text-muted-foreground">
                      <Check className="h-3.5 w-3.5" aria-hidden="true" />
                      {t("dictionaryPicker.added", "Added")}
                    </span>
                  ) : (
                    <Plus className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
                  )}
                </button>
              );
            })}
          </div>

          {mode === "manual" ? (
            <div className="space-y-2 rounded-lg border border-border bg-secondary/40 p-3" data-testid="dict-uri-picker">
              <label htmlFor="dict-manual-uri" className="block text-xs font-medium text-foreground">
                {t("parserEditor.dictUri", "Dictionary Resource URI")}
              </label>
              <Input
                id="dict-manual-uri"
                value={manualUri}
                onChange={(e) => setManualUri(e.target.value)}
                placeholder={t(
                  "parserEditor.dictUriPlaceholder",
                  "eddi://ai.labs.dictionary/dictionarystore/dictionaries/<id>?version=<v>",
                )}
                className="h-8 font-mono text-xs"
                autoFocus
                data-testid="dict-uri-input"
              />
              <div className="flex justify-end gap-2">
                <Button variant="ghost" size="sm" onClick={() => setMode("browse")} data-testid="cancel-add-dict">
                  {t("common.cancel", "Cancel")}
                </Button>
                <Button
                  size="sm"
                  disabled={!manualUri.trim()}
                  onClick={() => handlePick(manualUri.trim())}
                  data-testid="confirm-add-dict"
                >
                  <Plus />
                  {t("parserEditor.addDict", "Add")}
                </Button>
              </div>
            </div>
          ) : (
            <div
              className={cn(
                "flex items-center gap-2 border-t border-border pt-3",
                createOfferedAbove ? "justify-end" : "justify-between",
              )}
            >
              {/* Hidden when the empty state already offers Create as its own
                  action — two identical buttons on one screen is a choice the
                  reader has to make and cannot. */}
              {!createOfferedAbove && (
                <Button variant="outline" size="sm" onClick={() => setMode("create")} data-testid="dict-create-open">
                  <Plus />
                  {t("dictionaryPicker.create", "Create dictionary")}
                </Button>
              )}
              <Button variant="link" size="sm" onClick={() => setMode("manual")} data-testid="dict-manual-open">
                {t("dictionaryPicker.manual", "Link by URI instead")}
              </Button>
            </div>
          )}
        </div>
      )}
    </AccessibleDialog>
  );
}
