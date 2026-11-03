import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { AlertTriangle, Coins, Download, Pencil, Plus, RefreshCw, Save, Trash2, Upload, BookOpen } from "lucide-react";
import { ErrorState } from "@/components/shared/error-state";
import { ModelPriceEntrySheet } from "@/components/model-prices/model-price-entry-sheet";
import { useModelPrices, useReferencePrices, useSaveModelPrices, useUnpricedSightings } from "@/hooks/use-model-prices";
import { useUnsavedChangesGuard } from "@/hooks/use-unsaved-changes-guard";
import { getErrorMessage, isApiError } from "@/lib/api-client";
import {
  UNPRICED_POLICIES,
  type ModelPriceCatalog,
  type ModelPriceEntry,
  type UnpricedPolicy,
} from "@/lib/api/model-prices";
import { entryProblems, isStale, mergeReference, parseCatalogImport, type ReferenceMerge } from "@/lib/model-prices";

const EMPTY_ENTRY: ModelPriceEntry = { provider: "", model: "" };

function money(v: number | null | undefined): string {
  return v == null ? "—" : `$${v}`;
}

/**
 * Administration → Model Prices: the deployment price catalog every model call
 * is priced against (docs/model-pricing.md). Edits stay local until saved; a
 * save sends the whole catalog with the revision it was based on, so a
 * concurrent edit answers 409 instead of being overwritten.
 */
export function ModelPricesPage() {
  const { t } = useTranslation();
  const { data: view, isLoading, isError, error, refetch } = useModelPrices();
  const { data: sightings } = useUnpricedSightings();
  const save = useSaveModelPrices();

  const [form, setForm] = useState<ModelPriceCatalog | null>(null);
  const [dirty, setDirty] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [editing, setEditing] = useState<{ index: number | null; entry: ModelPriceEntry } | null>(null);
  const [wantReference, setWantReference] = useState(false);
  const [preview, setPreview] = useState<ReferenceMerge | null>(null);
  const reference = useReferencePrices(wantReference);
  const fileRef = useRef<HTMLInputElement>(null);

  useUnsavedChangesGuard(dirty);

  useEffect(() => {
    // an older view (the refetch after a save has not landed yet) must not overwrite a newer form
    if (view && !dirty && (!form || view.catalog.revision >= form.revision)) setForm(view.catalog);
  }, [view, dirty, form]);

  useEffect(() => {
    if (wantReference && reference.data && form) {
      setPreview(mergeReference(form.entries, reference.data.entries));
      setWantReference(false);
    }
  }, [wantReference, reference.data, form]);

  const floor = view?.floorPolicy ?? "ALLOW";
  const staleAfter = view?.staleAfterDays ?? 120;
  const entries = useMemo(() => form?.entries ?? [], [form]);
  const staleCount = entries.filter((e) => isStale(e.asOf, staleAfter)).length;
  const invalid = entries.some((e) => entryProblems(e, entries).length > 0);

  const update = useCallback((next: Partial<ModelPriceCatalog>) => {
    setForm((prev) => (prev ? { ...prev, ...next } : prev));
    setDirty(true);
  }, []);

  const handleSave = useCallback(() => {
    if (!form || invalid) return;
    save.mutate(form, {
      onSuccess: (stored) => {
        setForm(stored);
        setDirty(false);
        setConflict(false);
        toast.success(t("modelPrices.saved", "Prices saved"));
      },
      onError: (err) => {
        if (isApiError(err) && err.status === 409) {
          setConflict(true);
        } else {
          toast.error(getErrorMessage(err));
        }
      },
    });
  }, [form, invalid, save, t]);

  const reload = useCallback(() => {
    setDirty(false);
    setConflict(false);
    void refetch();
  }, [refetch]);

  const applyEntry = (entry: ModelPriceEntry) => {
    if (!form || !editing) return;
    const next = [...form.entries];
    if (editing.index === null) next.push(entry);
    else next[editing.index] = entry;
    update({ entries: next });
    setEditing(null);
  };

  const removeEntry = (index: number) => {
    if (!form) return;
    update({ entries: form.entries.filter((_, i) => i !== index) });
  };

  const exportJson = () => {
    if (!form) return;
    const blob = new Blob([JSON.stringify({ unpricedPolicy: form.unpricedPolicy, entries: form.entries }, null, 2)], {
      type: "application/json",
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = "eddi-model-prices.json";
    a.click();
    URL.revokeObjectURL(url);
  };

  const importJson = async (file: File | undefined) => {
    if (!file || !form) return;
    try {
      const parsed = parseCatalogImport(await file.text());
      update({ entries: parsed.entries, unpricedPolicy: parsed.unpricedPolicy });
      toast.success(t("modelPrices.imported", "Imported — review and save"));
    } catch {
      toast.error(t("modelPrices.importFailed", "That file is not a price catalog"));
    }
  };

  if (isLoading || (!form && !isError)) {
    return (
      <div className="space-y-4" data-testid="model-prices-loading">
        <div className="h-10 w-64 animate-pulse rounded-md bg-muted" />
        <div className="h-48 animate-pulse rounded-xl bg-muted" />
      </div>
    );
  }
  if (isError || !form || !view) {
    return <ErrorState error={error} onRetry={() => void refetch()} />;
  }

  const floorIndex = UNPRICED_POLICIES.indexOf(floor);

  return (
    <div className="space-y-6" data-testid="model-prices-page">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-primary/10">
            <Coins className="h-5 w-5 text-primary" />
          </div>
          <div>
            <h1 className="text-2xl font-bold tracking-tight text-foreground">{t("modelPrices.title", "Model Prices")}</h1>
            <p className="text-sm text-muted-foreground">
              {t(
                "modelPrices.description",
                "The prices every model call is charged at when its config names none — and what happens to a call nobody priced.",
              )}
            </p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {dirty && (
            <span className="text-xs font-medium text-warn-500" data-testid="model-prices-dirty">
              {t("editor.dirty", "Unsaved changes")}
            </span>
          )}
          <button
            type="button"
            onClick={() => setWantReference(true)}
            className="inline-flex items-center gap-1.5 rounded-lg border border-border px-3 py-2 text-sm hover:bg-secondary"
            data-testid="model-prices-load-reference"
          >
            <BookOpen className="h-4 w-4" />
            {view.referenceAsOf
              ? t("modelPrices.loadReferenceAsOf", "Load reference prices (as of {{date}})", { date: view.referenceAsOf })
              : t("modelPrices.loadReference", "Load reference prices")}
          </button>
          <button
            type="button"
            onClick={exportJson}
            className="inline-flex items-center gap-1.5 rounded-lg border border-border px-3 py-2 text-sm hover:bg-secondary"
            data-testid="model-prices-export"
          >
            <Download className="h-4 w-4" />
            {t("modelPrices.export", "Export")}
          </button>
          <button
            type="button"
            onClick={() => fileRef.current?.click()}
            className="inline-flex items-center gap-1.5 rounded-lg border border-border px-3 py-2 text-sm hover:bg-secondary"
            data-testid="model-prices-import"
          >
            <Upload className="h-4 w-4" />
            {t("modelPrices.import", "Import")}
          </button>
          <input
            ref={fileRef}
            type="file"
            accept="application/json,.json"
            className="hidden"
            onChange={(e) => void importJson(e.target.files?.[0])}
            data-testid="model-prices-import-file"
          />
          <button
            type="button"
            onClick={handleSave}
            disabled={!dirty || invalid || save.isPending}
            className="inline-flex items-center gap-1.5 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-50"
            data-testid="model-prices-save"
          >
            <Save className="h-4 w-4" />
            {t("common.save", "Save")}
          </button>
        </div>
      </div>

      {conflict && (
        <div
          role="alert"
          className="flex items-center justify-between gap-3 rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive"
          data-testid="model-prices-conflict"
        >
          <span>{t("modelPrices.conflict", "Someone else saved the prices since you loaded them. Reload, then apply your change again.")}</span>
          <button type="button" onClick={reload} className="inline-flex items-center gap-1 font-medium" data-testid="model-prices-reload">
            <RefreshCw className="h-4 w-4" />
            {t("modelPrices.reload", "Reload")}
          </button>
        </div>
      )}

      {/* Policy */}
      <section className="rounded-xl border border-border bg-card p-4" data-testid="model-prices-policy">
        <h2 className="text-sm font-semibold text-foreground">{t("modelPrices.policyTitle", "Unpriced calls")}</h2>
        <p className="mt-1 text-xs text-muted-foreground">
          {t(
            "modelPrices.policyDesc",
            "What happens to a model call that neither its config nor this catalog prices. ALLOW runs it at $0 and counts it; REFUSE_WHEN_BUDGETED refuses it while a dollar ceiling applies; REFUSE refuses it always.",
          )}
        </p>
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <select
            value={form.unpricedPolicy}
            onChange={(e) => update({ unpricedPolicy: e.target.value as UnpricedPolicy })}
            className="h-9 rounded-md border border-input bg-background px-2 text-sm"
            aria-label={t("modelPrices.policyTitle", "Unpriced calls")}
            data-testid="model-prices-policy-select"
          >
            {UNPRICED_POLICIES.map((p, i) => (
              <option key={p} value={p} disabled={i < floorIndex}>
                {p}
              </option>
            ))}
          </select>
          <span className="text-xs text-muted-foreground" data-testid="model-prices-effective">
            {t("modelPrices.effective", "In force: {{policy}}", { policy: view.effectivePolicy })}
          </span>
          {floor !== "ALLOW" && (
            <span className="text-xs text-muted-foreground" data-testid="model-prices-floor">
              {t("modelPrices.floor", "The operator set a floor of {{policy}}; the catalog cannot go below it.", { policy: floor })}
            </span>
          )}
        </div>
      </section>

      {/* Reference preview */}
      {preview && (
        <section className="rounded-xl border border-primary/30 bg-primary/5 p-4" data-testid="model-prices-reference-preview">
          <h2 className="text-sm font-semibold text-foreground">{t("modelPrices.referenceTitle", "Reference prices")}</h2>
          <p className="mt-1 text-xs text-muted-foreground">
            {t("modelPrices.referenceSummary", "{{added}} new, {{changed}} changed, {{unchanged}} already up to date.", {
              added: preview.added.length,
              changed: preview.changed.length,
              unchanged: preview.unchanged,
            })}
          </p>
          <ul className="mt-2 max-h-40 overflow-y-auto text-xs" data-testid="model-prices-reference-diff">
            {[...preview.added.map((e) => ["+", e] as const), ...preview.changed.map((e) => ["~", e] as const)].map(([sign, e]) => (
              <li key={`${sign}${e.provider}/${e.model}`} className="font-mono">
                {sign} {e.provider}/{e.model} {money(e.inputPricePer1M)} / {money(e.outputPricePer1M)}
              </li>
            ))}
          </ul>
          <div className="mt-3 flex gap-2">
            <button
              type="button"
              onClick={() => {
                update({ entries: preview.entries });
                setPreview(null);
              }}
              disabled={preview.added.length + preview.changed.length === 0}
              className="rounded-md bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground disabled:opacity-50"
              data-testid="model-prices-reference-apply"
            >
              {t("modelPrices.referenceApply", "Add to the catalog")}
            </button>
            <button
              type="button"
              onClick={() => setPreview(null)}
              className="rounded-md px-3 py-1.5 text-xs text-muted-foreground hover:bg-secondary"
              data-testid="model-prices-reference-cancel"
            >
              {t("common.cancel", "Cancel")}
            </button>
          </div>
        </section>
      )}

      {/* Entries */}
      <section className="rounded-xl border border-border bg-card" data-testid="model-prices-entries">
        <div className="flex items-center justify-between border-b border-border px-4 py-3">
          <div className="flex items-center gap-2">
            <h2 className="text-sm font-semibold text-foreground">
              {t("modelPrices.entriesTitle", "Prices ({{n}})", { n: entries.length })}
            </h2>
            {staleCount > 0 && (
              <span className="rounded-full bg-warn-500/10 px-2 py-0.5 text-[10px] font-medium text-warn-500" data-testid="model-prices-stale-badge">
                {t("modelPrices.staleBadge", "{{n}} older than {{days}} days", { n: staleCount, days: staleAfter })}
              </span>
            )}
          </div>
          <button
            type="button"
            onClick={() => setEditing({ index: null, entry: EMPTY_ENTRY })}
            className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-primary hover:bg-primary/10"
            data-testid="model-prices-add"
          >
            <Plus className="h-3.5 w-3.5" />
            {t("modelPrices.add", "Add price")}
          </button>
        </div>
        {entries.length === 0 ? (
          <p className="px-4 py-6 text-center text-sm text-muted-foreground" data-testid="model-prices-empty">
            {t(
              "modelPrices.empty",
              "No catalog prices. Calls are priced from their configs only — load the reference prices or add your own.",
            )}
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead className="text-muted-foreground">
                <tr className="border-b border-border">
                  <th className="px-3 py-2 text-start font-medium">{t("modelPrices.provider", "Provider")}</th>
                  <th className="px-3 py-2 text-start font-medium">{t("modelPrices.model", "Model")}</th>
                  <th className="px-3 py-2 text-end font-medium">{t("modelPrices.inputPrice", "Input $ / 1M")}</th>
                  <th className="px-3 py-2 text-end font-medium">{t("modelPrices.outputPrice", "Output $ / 1M")}</th>
                  <th className="px-3 py-2 text-end font-medium">{t("modelPrices.cacheReadPrice", "Cache read $ / 1M")}</th>
                  <th className="px-3 py-2 text-start font-medium">{t("modelPrices.asOf", "As of")}</th>
                  <th className="px-3 py-2" />
                </tr>
              </thead>
              <tbody>
                {entries.map((e, i) => {
                  const problems = entryProblems(e, entries);
                  return (
                    <tr key={`${e.provider}/${e.model}/${i}`} className="border-b border-border/60" data-testid="model-prices-row">
                      <td className="px-3 py-2 font-mono">{e.provider}</td>
                      <td className="px-3 py-2 font-mono">
                        {e.model}
                        {e.longContext && (
                          <span className="ms-1 rounded bg-secondary px-1 text-[10px] text-muted-foreground">
                            {t("modelPrices.longContextBadge", "> {{tokens}} tok", { tokens: e.longContext.thresholdTokens ?? "?" })}
                          </span>
                        )}
                        {problems[0] && (
                          <AlertTriangle
                            className="ms-1 inline h-3.5 w-3.5 text-destructive"
                            aria-label={t(problems[0].key, problems[0].fallback)}
                            data-testid="model-prices-row-problem"
                          />
                        )}
                      </td>
                      <td className="px-3 py-2 text-end">{money(e.inputPricePer1M)}</td>
                      <td className="px-3 py-2 text-end">{money(e.outputPricePer1M)}</td>
                      <td className="px-3 py-2 text-end">{money(e.cacheReadPricePer1M)}</td>
                      <td className="px-3 py-2">
                        {e.asOf ?? "—"}
                        {isStale(e.asOf, staleAfter) && (
                          <span className="ms-1 text-[10px] text-warn-500" data-testid="model-prices-row-stale">
                            {t("modelPrices.stale", "stale")}
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-2 text-end">
                        <button
                          type="button"
                          onClick={() => setEditing({ index: i, entry: e })}
                          aria-label={t("common.edit", "Edit")}
                          className="rounded p-1 text-muted-foreground hover:bg-secondary"
                          data-testid="model-prices-edit"
                        >
                          <Pencil className="h-3.5 w-3.5" />
                        </button>
                        <button
                          type="button"
                          onClick={() => removeEntry(i)}
                          aria-label={t("common.delete", "Delete")}
                          className="rounded p-1 text-muted-foreground hover:bg-secondary"
                          data-testid="model-prices-delete"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* Unpriced sightings */}
      <section className="rounded-xl border border-border bg-card p-4" data-testid="model-prices-unpriced">
        <h2 className="text-sm font-semibold text-foreground">{t("modelPrices.unpricedTitle", "Unpriced models seen (this node)")}</h2>
        <p className="mt-1 text-xs text-muted-foreground">
          {t(
            "modelPrices.unpricedDesc",
            "Model calls that had no price, on the node serving this page since it started. They cost $0 against every budget.",
          )}
        </p>
        {!sightings || sightings.length === 0 ? (
          <p className="mt-3 text-xs text-muted-foreground" data-testid="model-prices-unpriced-empty">
            {t("modelPrices.unpricedNone", "None seen.")}
          </p>
        ) : (
          <ul className="mt-3 divide-y divide-border/60 text-xs">
            {sightings.map((s) => (
              <li key={`${s.provider}/${s.model}/${s.site}`} className="flex items-center justify-between gap-2 py-2" data-testid="model-prices-sighting">
                <span className="font-mono">
                  {s.provider}/{s.model}
                </span>
                <span className="text-muted-foreground">
                  {t("modelPrices.sightingDetail", "{{n}} calls · {{site}}", { n: s.count, site: s.site })}
                </span>
                <button
                  type="button"
                  onClick={() => setEditing({ index: null, entry: { provider: s.provider, model: s.model } })}
                  className="rounded-md px-2 py-1 font-medium text-primary hover:bg-primary/10"
                  data-testid="model-prices-sighting-add"
                >
                  {t("modelPrices.add", "Add price")}
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      {editing && (
        <ModelPriceEntrySheet
          entry={editing.entry}
          others={editing.index === null ? entries : entries.filter((_, i) => i !== editing.index)}
          knownProviders={view.knownProviders}
          onApply={applyEntry}
          onClose={() => setEditing(null)}
        />
      )}
    </div>
  );
}
