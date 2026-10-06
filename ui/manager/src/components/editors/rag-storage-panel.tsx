import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { AlertTriangle, HardDrive, MoveRight } from "lucide-react";
import { AlertDialog } from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import {
  NAMESPACE_ID,
  RESERVED_PREFIX,
  idLayoutLocation,
  locationParameter,
  locationProblem,
  usesIdNamespace,
} from "@/lib/rag-storage";

interface StorageConfig {
  name?: string;
  storeType?: string;
  storeParameters?: Record<string, string>;
  storeNamespace?: string;
}

/**
 * Where this knowledge base's vectors live (EDDI 6.6+), and what the server will
 * refuse about an explicit location.
 *
 * Up to 6.5 a store was addressed by the knowledge base's NAME, so two with the
 * same name shared one store. 6.6 addresses new ones by id (`storeNamespace:
 * "id"`) and keeps the old ones where they are until someone migrates them —
 * which moves them to an EMPTY store. This panel says which layout applies and
 * offers that migration with its consequences spelled out first.
 */
export function RagStoragePanel<T extends StorageConfig>({
  data,
  onChange,
  readOnly,
  resourceId,
  version,
}: {
  data: T;
  onChange: (next: T) => void;
  readOnly?: boolean;
  resourceId?: string;
  /** The loaded version; a new one (a save, a version switch) re-reads the saved location. */
  version?: number;
}) {
  const { t } = useTranslation();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const param = locationParameter(data.storeType);
  const explicit = (param && data.storeParameters?.[param]?.trim()) || null;

  // The location as it was loaded. The server re-judges an explicit location
  // only when it CHANGES (KnowledgeBaseStorageGuard.prepareUpdate), so a 6.5
  // knowledge base saved with, say, `eddi_kb_docs` keeps saving as long as the
  // location is left alone — telling its owner "the server refuses" it was wrong.
  const [saved, setSaved] = useState(() => ({ type: data.storeType, value: explicit }));
  useEffect(() => {
    setSaved({ type: data.storeType, value: explicit });
    // Re-baseline only when another document version is loaded, not per keystroke.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resourceId, version]);

  if (!param) return null;

  const problem = locationProblem(data.storeType, explicit);
  const unchanged = explicit != null && saved.value === explicit && saved.type === data.storeType;
  const idLayout = usesIdNamespace(data.storeNamespace);
  const ownDefault = idLayoutLocation(data.storeType, resourceId);

  return (
    <div className="space-y-2" data-testid="rag-storage-panel">
      {problem && explicit && (
        <div
          className={
            unchanged && problem !== "reference"
              ? "flex items-start gap-2 rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-xs"
              : "flex items-start gap-2 rounded-lg border border-destructive/40 bg-destructive/10 px-3 py-2 text-xs"
          }
          role={unchanged && problem !== "reference" ? "status" : "alert"}
          data-testid={`rag-location-${problem}`}
          data-saved={unchanged || undefined}
        >
          <AlertTriangle
            className={`mt-0.5 h-3.5 w-3.5 shrink-0 ${unchanged && problem !== "reference" ? "text-amber-600 dark:text-amber-400" : "text-destructive"}`}
            aria-hidden="true"
          />
          <div className="space-y-0.5">
            <p
              className={`font-semibold ${unchanged && problem !== "reference" ? "text-amber-700 dark:text-amber-400" : "text-destructive"}`}
            >
              {!unchanged
                ? t("ragEditor.location.refused", {
                    defaultValue: "The server refuses {{param}} = {{value}}",
                    param,
                    value: explicit,
                  })
                : problem === "reference"
                  ? t("ragEditor.location.savedReference", {
                      defaultValue: "The saved {{param}} = {{value}} no longer works",
                      param,
                      value: explicit,
                    })
                  : t("ragEditor.location.refusedIfChanged", {
                      defaultValue:
                        "{{param}} = {{value}} keeps working as saved, but the server would refuse it if it were changed",
                      param,
                      value: explicit,
                    })}
            </p>
            <p className="text-foreground">
              {problem === "reference"
                ? t(
                    "ragEditor.location.reference",
                    "A store location must be written out, not a ${…} reference. The location decides whose documents an agent reads, and a reference is resolved only when the store is built — after every check — from variables any editor can change, so it could point at another knowledge base's store or at SQL. A knowledge base already saved with a reference stops ingesting and retrieving until it is saved once with the literal name.",
                  )
                : problem === "pgIdentifier"
                  ? t(
                      "ragEditor.location.pgIdentifier",
                      "A pgvector table must be a plain name, optionally schema-qualified (letters, digits, _ and $). The store puts it into SQL as it is, so quotes, spaces and punctuation are refused.",
                    )
                  : t("ragEditor.location.reserved", {
                      defaultValue:
                        "Names starting with {{prefix}} (in any part, any case) are reserved: EDDI derives every knowledge base's own location from that prefix, so this name could address another knowledge base's store. Choose another name, or leave it blank to get a location of this knowledge base's own.",
                      prefix: RESERVED_PREFIX,
                    })}
            </p>
          </div>
        </div>
      )}

      <div className="rounded-lg border border-border bg-secondary/20 px-3 py-2 text-xs" data-testid="rag-storage-layout" data-layout={idLayout ? "id" : "name"}>
        <p className="flex items-center gap-1.5 font-semibold text-foreground">
          <HardDrive className="h-3.5 w-3.5 text-primary" aria-hidden="true" />
          {idLayout
            ? t("ragEditor.layout.idTitle", "Own store (addressed by id)")
            : t("ragEditor.layout.nameTitle", "Name-based store (6.5 layout)")}
        </p>
        <p className="mt-1 text-muted-foreground">
          {idLayout
            ? t(
                "ragEditor.layout.idBody",
                "This knowledge base has a store of its own: every chunk is tagged with its id and retrieval sees only its own chunks, even in a shared table.",
              )
            : t(
                "ragEditor.layout.nameBody",
                "Created before EDDI 6.6: its store is found by the knowledge base's name, so any other knowledge base with the same name shares it. It keeps working as it is.",
              )}
        </p>
        <p className="mt-1 text-muted-foreground" data-testid="rag-storage-location">
          {explicit
            ? t("ragEditor.layout.explicit", { defaultValue: "Location: {{value}} (set above)", value: explicit })
            : idLayout
              ? ownDefault
                ? t("ragEditor.layout.default", { defaultValue: "Location: {{value}} (default)", value: ownDefault })
                : t("ragEditor.layout.defaultUnsaved", "Location: eddi_kbid_<id> (default, assigned when saved)")
              : t("ragEditor.layout.legacyDefault", {
                  defaultValue: "Location: derived from the name “{{name}}” (default)",
                  name: data.name ?? "",
                })}
        </p>
        {!idLayout && !readOnly && (
          <div className="mt-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setConfirmOpen(true)}
              data-testid="rag-migrate-own-store"
            >
              <MoveRight className="h-3.5 w-3.5" aria-hidden="true" />
              {t("ragEditor.layout.migrate", "Move to its own store…")}
            </Button>
            <p className="mt-1 text-[10px] text-muted-foreground">
              {t(
                "ragEditor.layout.renameNote",
                "Renaming this knowledge base moves it the same way. Needs EDDI 6.6 or later.",
              )}
            </p>
          </div>
        )}
      </div>

      <AlertDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title={t("ragEditor.layout.migrateTitle", "Move this knowledge base to its own store?")}
        description={
          explicit
            ? t(
                "ragEditor.layout.migrateBodyExplicit",
                "It keeps its table, but only chunks tagged with its id are retrieved from now on — none of the chunks already there carry that tag, so it starts EMPTY. Its sources re-ingest by themselves on their next run; documents added through /ingest must be ingested again. Nothing is deleted. The change takes effect when you save, and cannot be switched back.",
              )
            : t(
                "ragEditor.layout.migrateBody",
                "It moves to a new, EMPTY store of its own. Its sources re-ingest by themselves on their next run; documents added through /ingest must be ingested again. The old store is left in place, untouched. The change takes effect when you save, and cannot be switched back.",
              )
        }
        confirmLabel={t("ragEditor.layout.migrateConfirm", "Move when I save")}
        variant="warning"
        onConfirm={() => {
          onChange({ ...data, storeNamespace: NAMESPACE_ID });
          setConfirmOpen(false);
        }}
      />
    </div>
  );
}
