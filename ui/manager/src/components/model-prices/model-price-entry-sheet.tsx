import { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useTranslation } from "react-i18next";
import { X } from "lucide-react";
import type { LongContextTier, ModelPriceEntry } from "@/lib/api/model-prices";
import { entryProblems } from "@/lib/model-prices";

interface Props {
  /** The entry being edited, or a new one (possibly prefilled). */
  entry: ModelPriceEntry;
  /** The other entries, for the duplicate check. */
  others: readonly ModelPriceEntry[];
  knownProviders: readonly string[] | null;
  readOnly?: boolean;
  onApply: (entry: ModelPriceEntry) => void;
  onClose: () => void;
}

type PriceField = "inputPricePer1M" | "outputPricePer1M" | "cacheReadPricePer1M" | "cacheWritePricePer1M";

function toNum(v: string): number | null {
  if (v.trim() === "") return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : Number.NaN;
}

/**
 * Inspector side sheet for one catalog entry (no modal: the table stays
 * visible). Edits a local copy; "Apply" hands it back to the page, which
 * saves the whole catalog.
 */
export function ModelPriceEntrySheet({ entry, others, knownProviders, readOnly, onApply, onClose }: Props) {
  const { t } = useTranslation();
  const [draft, setDraft] = useState<ModelPriceEntry>(entry);
  const closeRef = useRef<HTMLButtonElement>(null);
  const baseId = useId();
  const id = (name: string) => `${baseId}-${name}`;
  const problems = entryProblems(draft, others);

  useEffect(() => {
    closeRef.current?.focus();
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  const set = (patch: Partial<ModelPriceEntry>) => setDraft((d) => ({ ...d, ...patch }));
  const setTier = (patch: Partial<LongContextTier>) => setDraft((d) => ({ ...d, longContext: { ...(d.longContext ?? {}), ...patch } }));

  const input = "h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring";
  const priceInput = (field: PriceField, label: string) => (
    <div>
      <label htmlFor={id(field)} className="mb-0.5 block text-[10px] text-muted-foreground">
        {label}
      </label>
      <input
        id={id(field)}
        type="number"
        min={0}
        step="0.001"
        value={draft[field] ?? ""}
        readOnly={readOnly}
        onChange={(e) => set({ [field]: toNum(e.target.value) })}
        className={input}
        data-testid={`mp-sheet-${field}`}
      />
    </div>
  );

  const body = (
    <>
      <div
        role="dialog"
        aria-label={t("modelPrices.sheetTitle", "Model price")}
        className="fixed bottom-0 end-0 top-0 z-50 flex w-[440px] max-w-full flex-col border-s border-border bg-card shadow-2xl"
        data-testid="mp-sheet"
      >
        <div className="flex shrink-0 items-center justify-between border-b border-border px-4 py-3">
          <h3 className="text-sm font-semibold text-foreground">{t("modelPrices.sheetTitle", "Model price")}</h3>
          <button
            ref={closeRef}
            type="button"
            onClick={onClose}
            aria-label={t("common.close", "Close")}
            className="rounded-md p-1 text-muted-foreground hover:bg-secondary hover:text-foreground"
            data-testid="mp-sheet-close"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-4">
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label htmlFor={id("provider")} className="mb-0.5 block text-[10px] text-muted-foreground">
                {t("modelPrices.provider", "Provider")}
              </label>
              <input
                id={id("provider")}
                list={id("providers")}
                value={draft.provider}
                readOnly={readOnly}
                onChange={(e) => set({ provider: e.target.value })}
                className={input}
                data-testid="mp-sheet-provider"
              />
              <datalist id={id("providers")}>
                {["*", ...(knownProviders ?? [])].map((p) => (
                  <option key={p} value={p} />
                ))}
              </datalist>
            </div>
            <div>
              <label htmlFor={id("model")} className="mb-0.5 block text-[10px] text-muted-foreground">
                {t("modelPrices.model", "Model")}
              </label>
              <input
                id={id("model")}
                value={draft.model}
                readOnly={readOnly}
                onChange={(e) => set({ model: e.target.value })}
                placeholder="gpt-4o*"
                className={input}
                data-testid="mp-sheet-model"
              />
            </div>
          </div>
          <p className="text-[10px] leading-relaxed text-muted-foreground">
            {t(
              "modelPrices.modelHint",
              "Exact name, or * at the start and/or end. The longest match wins; the model the provider reported is tried before the one requested.",
            )}
          </p>
          <div className="grid grid-cols-2 gap-2">
            {priceInput("inputPricePer1M", t("modelPrices.inputPrice", "Input $ / 1M"))}
            {priceInput("outputPricePer1M", t("modelPrices.outputPrice", "Output $ / 1M"))}
            {priceInput("cacheReadPricePer1M", t("modelPrices.cacheReadPrice", "Cache read $ / 1M"))}
            {priceInput("cacheWritePricePer1M", t("modelPrices.cacheWritePrice", "Cache write $ / 1M"))}
          </div>
          <p className="text-[10px] leading-relaxed text-muted-foreground">
            {t("modelPrices.cacheHint", "A blank cache price charges cached tokens at the input price.")}
          </p>

          <fieldset className="space-y-2 rounded-lg border border-border p-3" data-testid="mp-sheet-long-context">
            <legend className="px-1 text-[10px] font-medium text-muted-foreground">
              {t("modelPrices.longContext", "Long-context tier")}
            </legend>
            <div>
              <label htmlFor={id("threshold")} className="mb-0.5 block text-[10px] text-muted-foreground">
                {t("modelPrices.threshold", "Applies to prompts over (tokens)")}
              </label>
              <input
                id={id("threshold")}
                type="number"
                min={1000}
                value={draft.longContext?.thresholdTokens ?? ""}
                readOnly={readOnly}
                onChange={(e) => {
                  const v = toNum(e.target.value);
                  if (v === null && !draft.longContext?.inputPricePer1M && !draft.longContext?.outputPricePer1M) {
                    set({ longContext: null });
                  } else {
                    setTier({ thresholdTokens: v });
                  }
                }}
                className={input}
                data-testid="mp-sheet-threshold"
              />
            </div>
            {draft.longContext && (
              <div className="grid grid-cols-2 gap-2">
                {(
                  [
                    ["inputPricePer1M", t("modelPrices.inputPrice", "Input $ / 1M")],
                    ["outputPricePer1M", t("modelPrices.outputPrice", "Output $ / 1M")],
                    ["cacheReadPricePer1M", t("modelPrices.cacheReadPrice", "Cache read $ / 1M")],
                    ["cacheWritePricePer1M", t("modelPrices.cacheWritePrice", "Cache write $ / 1M")],
                  ] as const
                ).map(([f, label]) => (
                  <div key={f}>
                    <label htmlFor={id(`lc-${f}`)} className="mb-0.5 block text-[10px] text-muted-foreground">
                      {label}
                    </label>
                    <input
                      id={id(`lc-${f}`)}
                      type="number"
                      min={0}
                      step="0.001"
                      value={draft.longContext?.[f] ?? ""}
                      readOnly={readOnly}
                      onChange={(e) => setTier({ [f]: toNum(e.target.value) })}
                      className={input}
                      data-testid={`mp-sheet-lc-${f}`}
                    />
                  </div>
                ))}
              </div>
            )}
          </fieldset>

          <div className="grid grid-cols-2 gap-2">
            <div>
              <label htmlFor={id("asOf")} className="mb-0.5 block text-[10px] text-muted-foreground">
                {t("modelPrices.asOf", "As of")}
              </label>
              <input
                id={id("asOf")}
                type="date"
                value={draft.asOf ?? ""}
                readOnly={readOnly}
                onChange={(e) => set({ asOf: e.target.value || null })}
                className={input}
                data-testid="mp-sheet-asof"
              />
            </div>
            <div>
              <label htmlFor={id("source")} className="mb-0.5 block text-[10px] text-muted-foreground">
                {t("modelPrices.source", "Source link")}
              </label>
              <input
                id={id("source")}
                value={draft.source ?? ""}
                readOnly={readOnly}
                onChange={(e) => set({ source: e.target.value || null })}
                placeholder="https://"
                className={input}
                data-testid="mp-sheet-source"
              />
            </div>
          </div>
          <div>
            <label htmlFor={id("note")} className="mb-0.5 block text-[10px] text-muted-foreground">
              {t("modelPrices.note", "Note")}
            </label>
            <input
              id={id("note")}
              value={draft.note ?? ""}
              maxLength={512}
              readOnly={readOnly}
              onChange={(e) => set({ note: e.target.value || null })}
              className={input}
              data-testid="mp-sheet-note"
            />
          </div>

          {problems.length > 0 && (
            <ul className="space-y-1 rounded-md border border-destructive/30 bg-destructive/5 p-2 text-[11px] text-destructive" data-testid="mp-sheet-problems">
              {problems.map((p) => (
                <li key={p.key}>{t(p.key, p.fallback)}</li>
              ))}
            </ul>
          )}
        </div>
        <div className="flex shrink-0 justify-end gap-2 border-t border-border px-4 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md px-3 py-1.5 text-xs text-muted-foreground hover:bg-secondary"
            data-testid="mp-sheet-cancel"
          >
            {t("common.cancel", "Cancel")}
          </button>
          {!readOnly && (
            <button
              type="button"
              disabled={problems.length > 0}
              onClick={() => onApply({ ...draft, provider: draft.provider.trim(), model: draft.model.trim() })}
              className="rounded-md bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground disabled:opacity-50"
              data-testid="mp-sheet-apply"
            >
              {t("modelPrices.apply", "Apply")}
            </button>
          )}
        </div>
      </div>
    </>
  );
  return createPortal(body, document.body);
}
