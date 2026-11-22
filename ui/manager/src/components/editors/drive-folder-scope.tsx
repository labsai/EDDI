import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useInfiniteQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, FolderMinus, FolderOpen, FolderPlus, Loader2, X } from "lucide-react";
import { Button } from "@eddi/ui";
import { Input } from "@eddi/app-kit/components/ui/input";
import { AccessibleDialog } from "@eddi/app-kit/components/ui/accessible-dialog";
import {
  DRIVE_ROOT,
  listDriveFolders,
  type DriveFolder,
  type DriveFolderPage,
  type GoogleDriveSource,
} from "@/lib/api/ingestion-sources";

type ScopeKind = "include" | "exclude";

export interface DriveFolderScopeProps {
  drive: GoogleDriveSource | undefined;
  onChange: (patch: Partial<GoogleDriveSource>) => void;
  kbId?: string;
  sourceId?: string;
  version: number;
  readOnly?: boolean;
  /** Browsing lists the saved source's drive, so unsaved edits would mislead. */
  hasUnsavedChanges?: boolean;
  testId: string;
}

/** How a folder entry reads in a chip: its label, its path, or its id. */
function describeFolder(folder: DriveFolder): string {
  return folder.label?.trim() || folder.path?.trim() || folder.id || "";
}

/** Whether two entries name the same folder, by id or by path. */
function sameFolder(a: DriveFolder, b: DriveFolder): boolean {
  if (a.id && b.id) return a.id === b.id;
  if (a.path && b.path) return normalizePath(a.path) === normalizePath(b.path);
  return false;
}

/** A path as the server compares it: no leading or trailing slash. */
function normalizePath(path: string): string {
  return path.trim().replace(/^\/+|\/+$/g, "");
}

/**
 * The folders a Google Drive source reads and the subtrees it leaves out.
 *
 * Folders are added by path, or picked in the drive's own tree once the source
 * is saved. A picked folder is stored by id with its path as the label, so a
 * later rename in Drive does not move the scope; a typed path is resolved to an
 * id by the first run and kept in the source's state for the same reason.
 * Exclusion wins over inclusion at any depth.
 */
export function DriveFolderScope({
  drive,
  onChange,
  kbId,
  sourceId,
  version,
  readOnly,
  hasUnsavedChanges,
  testId,
}: DriveFolderScopeProps) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState("");
  const [browsing, setBrowsing] = useState(false);

  const legacyIds = drive?.folderIds ?? [];
  const folders = drive?.folders ?? [];
  const excluded = drive?.excludeFolders ?? [];
  const canBrowse = Boolean(kbId && sourceId) && !hasUnsavedChanges;

  const add = (kind: ScopeKind, folder: DriveFolder) => {
    if (kind === "include") {
      if (folders.some((f) => sameFolder(f, folder)) || (folder.id && legacyIds.includes(folder.id))) return;
      onChange({ folders: [...folders, folder] });
    } else {
      if (excluded.some((f) => sameFolder(f, folder))) return;
      // Excluding a folder that is also read replaces the include: both at once
      // would make the server drop it, and if it was the only folder, refuse the run.
      const keptFolders = folders.filter((f) => !sameFolder(f, folder));
      const keptIds = folder.id ? legacyIds.filter((id) => id !== folder.id) : legacyIds;
      const patch: Partial<GoogleDriveSource> = { excludeFolders: [...excluded, folder] };
      if (keptFolders.length !== folders.length) patch.folders = keptFolders;
      if (keptIds.length !== legacyIds.length) patch.folderIds = keptIds;
      onChange(patch);
    }
  };

  const addDraft = (kind: ScopeKind) => {
    const path = normalizePath(draft);
    if (!path) return;
    add(kind, { path });
    setDraft("");
  };

  const kindOf = (id: string): ScopeKind | null => {
    if (excluded.some((f) => f.id === id)) return "exclude";
    if (legacyIds.includes(id) || folders.some((f) => f.id === id)) return "include";
    return null;
  };

  const empty = legacyIds.length === 0 && folders.length === 0;

  return (
    <div className="space-y-2" data-testid={`${testId}-drive-scope`}>
      <div className="space-y-1">
        <span className="text-xs font-medium text-foreground/80">
          {t("ragEditor.sources.driveScope.folders", "Folders to read")}
        </span>
        <div className="flex flex-wrap gap-1.5" data-testid={`${testId}-drive-scope-included`}>
          {empty && (
            <span className="text-[11px] text-muted-foreground">
              {t("ragEditor.sources.driveScope.wholeDrive", "The whole drive")}
            </span>
          )}
          {legacyIds.map((id) => (
            <FolderChip
              key={`id-${id}`}
              text={id}
              kind="include"
              readOnly={readOnly}
              onRemove={() => onChange({ folderIds: legacyIds.filter((other) => other !== id) })}
              removeLabel={t("ragEditor.sources.driveScope.remove", "Remove {{folder}}", { folder: id })}
              testId={`${testId}-drive-scope-chip`}
            />
          ))}
          {folders.map((folder, index) => (
            <FolderChip
              key={`f-${index}-${folder.id ?? folder.path}`}
              text={describeFolder(folder)}
              title={folder.id && folder.path === undefined ? folder.id : undefined}
              kind="include"
              readOnly={readOnly}
              onRemove={() => onChange({ folders: folders.filter((_, i) => i !== index) })}
              removeLabel={t("ragEditor.sources.driveScope.remove", "Remove {{folder}}", {
                folder: describeFolder(folder),
              })}
              testId={`${testId}-drive-scope-chip`}
            />
          ))}
        </div>
      </div>
      <div className="space-y-1">
        <span className="text-xs font-medium text-foreground/80">
          {t("ragEditor.sources.driveScope.excluded", "Folders to leave out")}
        </span>
        <div className="flex flex-wrap gap-1.5" data-testid={`${testId}-drive-scope-excluded`}>
          {excluded.length === 0 && (
            <span className="text-[11px] text-muted-foreground">
              {t("ragEditor.sources.driveScope.noneExcluded", "None")}
            </span>
          )}
          {excluded.map((folder, index) => (
            <FolderChip
              key={`x-${index}-${folder.id ?? folder.path}`}
              text={describeFolder(folder)}
              kind="exclude"
              readOnly={readOnly}
              onRemove={() => onChange({ excludeFolders: excluded.filter((_, i) => i !== index) })}
              removeLabel={t("ragEditor.sources.driveScope.remove", "Remove {{folder}}", {
                folder: describeFolder(folder),
              })}
              testId={`${testId}-drive-scope-chip`}
            />
          ))}
        </div>
      </div>
      {!readOnly && (
        <div className="flex flex-wrap items-center gap-2">
          <Input
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                addDraft("include");
              }
            }}
            placeholder="Sales/Offers"
            aria-label={t("ragEditor.sources.driveScope.pathLabel", "Folder path")}
            className="max-w-xs"
            data-testid={`${testId}-drive-scope-path`}
          />
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => addDraft("include")}
            disabled={!normalizePath(draft)}
            data-testid={`${testId}-drive-scope-add-include`}
          >
            <FolderPlus className="mr-1 h-3.5 w-3.5" />
            {t("ragEditor.sources.driveScope.include", "Include")}
          </Button>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => addDraft("exclude")}
            disabled={!normalizePath(draft)}
            data-testid={`${testId}-drive-scope-add-exclude`}
          >
            <FolderMinus className="mr-1 h-3.5 w-3.5" />
            {t("ragEditor.sources.driveScope.exclude", "Exclude")}
          </Button>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => setBrowsing(true)}
            disabled={!canBrowse}
            data-testid={`${testId}-drive-scope-browse`}
          >
            <FolderOpen className="mr-1 h-3.5 w-3.5" />
            {t("ragEditor.sources.driveScope.browse", "Browse the drive")}
          </Button>
        </div>
      )}
      <span className="block text-[11px] text-muted-foreground">
        {canBrowse || readOnly
          ? t(
              "ragEditor.sources.driveScope.hint",
              "A path is read from the drive's root, one folder name per level. Each folder is read with its subfolders; a left-out folder's whole subtree is skipped, even inside a folder to read. Renaming a folder in Drive later does not change what is read.",
            )
          : t(
              "ragEditor.sources.driveScope.saveToBrowse",
              "Save the source to browse its drive: the folder list is read with the saved connection.",
            )}
      </span>
      {browsing && kbId && sourceId && (
        <AccessibleDialog
          open={browsing}
          onClose={() => setBrowsing(false)}
          title={t("ragEditor.sources.driveScope.pickerTitle", "Choose folders")}
          maxWidth="max-w-2xl"
          testId={`${testId}-drive-picker`}
        >
          <div className="max-h-[60vh] overflow-y-auto p-5">
            <FolderLevel
              kbId={kbId}
              sourceId={sourceId}
              version={version}
              parentId={DRIVE_ROOT}
              parentPath=""
              depth={0}
              kindOf={kindOf}
              onPick={(kind, folder) => add(kind, folder)}
              testId={`${testId}-drive-picker`}
            />
          </div>
        </AccessibleDialog>
      )}
    </div>
  );
}

function FolderChip({
  text,
  title,
  kind,
  readOnly,
  onRemove,
  removeLabel,
  testId,
}: {
  text: string;
  title?: string;
  kind: ScopeKind;
  readOnly?: boolean;
  onRemove: () => void;
  removeLabel: string;
  testId: string;
}) {
  return (
    <span
      className={
        kind === "exclude"
          ? "inline-flex items-center gap-1 rounded-full border border-warn-500/40 bg-warn-500/10 px-2 py-0.5 text-[11px]"
          : "inline-flex items-center gap-1 rounded-full border border-border bg-muted px-2 py-0.5 text-[11px]"
      }
      title={title}
      data-testid={testId}
      data-kind={kind}
    >
      {text}
      {!readOnly && (
        <button
          type="button"
          onClick={onRemove}
          aria-label={removeLabel}
          className="rounded-full p-0.5 hover:bg-foreground/10"
        >
          <X className="h-3 w-3" />
        </button>
      )}
    </span>
  );
}

/** One level of the drive, loaded when it is first opened, a page at a time. */
function FolderLevel({
  kbId,
  sourceId,
  version,
  parentId,
  parentPath,
  depth,
  kindOf,
  onPick,
  testId,
}: {
  kbId: string;
  sourceId: string;
  version: number;
  parentId: string;
  parentPath: string;
  depth: number;
  kindOf: (id: string) => ScopeKind | null;
  onPick: (kind: ScopeKind, folder: DriveFolder) => void;
  testId: string;
}) {
  const { t } = useTranslation();
  const query = useInfiniteQuery<DriveFolderPage>({
    queryKey: ["ingestion", "drive-folders", kbId, sourceId, version, parentId],
    queryFn: ({ pageParam }) =>
      listDriveFolders(kbId, sourceId, version, parentId, pageParam as string | null | undefined),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextPageToken ?? undefined,
    staleTime: 60_000,
    retry: false,
  });

  if (query.isPending) {
    return (
      <p className="flex items-center gap-1.5 py-1 text-[11px] text-muted-foreground" style={{ paddingLeft: depth * 16 }}>
        <Loader2 className="h-3 w-3 animate-spin" />
        {t("ragEditor.sources.driveScope.loading", "Loading folders…")}
      </p>
    );
  }
  if (query.isError) {
    return (
      <p
        className="py-1 text-[11px] text-destructive"
        role="alert"
        style={{ paddingLeft: depth * 16 }}
        data-testid={`${testId}-error`}
      >
        {t("ragEditor.sources.driveScope.loadFailed", "Could not list the folders: {{error}}", {
          error: query.error instanceof Error ? query.error.message : String(query.error),
        })}
      </p>
    );
  }
  const folders = query.data.pages.flatMap((page) => page.folders);
  return (
    <ul className="space-y-0.5" data-testid={depth === 0 ? `${testId}-root` : undefined}>
      {folders.length === 0 && (
        <li className="py-1 text-[11px] text-muted-foreground" style={{ paddingLeft: depth * 16 }}>
          {t("ragEditor.sources.driveScope.noSubfolders", "No subfolders")}
        </li>
      )}
      {folders.map((folder) => (
        <FolderRow
          key={folder.id}
          kbId={kbId}
          sourceId={sourceId}
          version={version}
          folder={folder}
          path={parentPath ? `${parentPath}/${folder.name}` : folder.name}
          depth={depth}
          kindOf={kindOf}
          onPick={onPick}
          testId={testId}
        />
      ))}
      {query.hasNextPage && (
        <li style={{ paddingLeft: depth * 16 }}>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => void query.fetchNextPage()}
            disabled={query.isFetchingNextPage}
            data-testid={`${testId}-more`}
          >
            {t("ragEditor.sources.driveScope.more", "Show more folders")}
          </Button>
        </li>
      )}
    </ul>
  );
}

function FolderRow({
  kbId,
  sourceId,
  version,
  folder,
  path,
  depth,
  kindOf,
  onPick,
  testId,
}: {
  kbId: string;
  sourceId: string;
  version: number;
  folder: { id: string; name: string };
  path: string;
  depth: number;
  kindOf: (id: string) => ScopeKind | null;
  onPick: (kind: ScopeKind, folder: DriveFolder) => void;
  testId: string;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const kind = kindOf(folder.id);
  const Chevron = open ? ChevronDown : ChevronRight;
  return (
    <li>
      <div className="flex items-center gap-1.5 py-0.5" style={{ paddingLeft: depth * 16 }}>
        <button
          type="button"
          onClick={() => setOpen((value) => !value)}
          aria-expanded={open}
          aria-label={t("ragEditor.sources.driveScope.expand", "Show the subfolders of {{folder}}", {
            folder: folder.name,
          })}
          className="rounded p-0.5 hover:bg-foreground/10"
          data-testid={`${testId}-expand-${folder.id}`}
        >
          <Chevron className="h-3.5 w-3.5" />
        </button>
        <span className="flex-1 truncate text-xs">{folder.name}</span>
        {kind && (
          <span className="text-[11px] text-muted-foreground" data-testid={`${testId}-state-${folder.id}`}>
            {kind === "include"
              ? t("ragEditor.sources.driveScope.included", "Read")
              : t("ragEditor.sources.driveScope.isExcluded", "Left out")}
          </span>
        )}
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => onPick("include", { id: folder.id, label: path })}
          disabled={kind !== null}
          data-testid={`${testId}-include-${folder.id}`}
        >
          {t("ragEditor.sources.driveScope.include", "Include")}
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => onPick("exclude", { id: folder.id, label: path })}
          disabled={kind === "exclude"}
          data-testid={`${testId}-exclude-${folder.id}`}
        >
          {t("ragEditor.sources.driveScope.exclude", "Exclude")}
        </Button>
      </div>
      {open && (
        <FolderLevel
          kbId={kbId}
          sourceId={sourceId}
          version={version}
          parentId={folder.id}
          parentPath={path}
          depth={depth + 1}
          kindOf={kindOf}
          onPick={onPick}
          testId={testId}
        />
      )}
    </li>
  );
}
