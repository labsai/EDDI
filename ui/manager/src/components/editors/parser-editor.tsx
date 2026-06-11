/**
 * Parser editor — inline form for `ai.labs.parser` extension config.
 *
 * Used in two modes:
 * 1. Inline within workflow detail (dialog — editing workflow step's config/extensions)
 * 2. Standalone via resource detail page (editing a parserstore resource)
 *
 * Both share the same data shape: { config, extensions }.
 */
import { useState, useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import {
  BUILTIN_DICTIONARIES,
  REGULAR_DICT_TYPE,
  CORRECTION_TYPES,
  NORMALIZER_TYPES,
  type ParserData,
  type ParserConfig,
  type ParserExtensionItem,
} from "./parser-editor-types";
import {
  BookOpen,
  Check,
  ChevronDown,
  Plus,
  Settings,
  Sparkles,
  Trash2,
  Type,
  X,
} from "lucide-react";
import { cn } from "@/lib/utils";

export interface ParserEditorProps {
  data: ParserData;
  onChange: (data: ParserData) => void;
  readOnly?: boolean;
}

// ─── Section Component ──────────────────────────────────────────────────────

function Section({
  label,
  icon: Icon,
  accent,
  defaultOpen = true,
  badge,
  children,
  testId,
}: {
  label: string;
  icon: React.ElementType;
  accent: string;
  defaultOpen?: boolean;
  badge?: string | number;
  children: React.ReactNode;
  testId?: string;
}) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="rounded-lg border border-border bg-card/50 overflow-hidden" data-testid={testId}>
      <button
        type="button"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        className="flex w-full items-center gap-2.5 px-4 py-2.5 text-start transition-colors hover:bg-muted/30"
      >
        <Icon className={cn("h-4 w-4 shrink-0", accent)} />
        <span className="flex-1 text-xs font-semibold uppercase tracking-wider text-foreground/80">
          {label}
        </span>
        {badge !== undefined && (
          <span className="rounded-full bg-primary/10 px-2 py-0.5 text-[10px] font-medium text-primary">
            {badge}
          </span>
        )}
        <ChevronDown
          className={cn(
            "h-3.5 w-3.5 text-muted-foreground transition-transform",
            open && "rotate-180",
          )}
        />
      </button>
      {open && <div className="border-t border-border px-4 py-3">{children}</div>}
    </div>
  );
}

// ─── Toggle Row ──────────────────────────────────────────────────────────────

function ToggleRow({
  label,
  checked,
  onChange,
  readOnly,
  testId,
  children,
}: {
  label: string;
  checked: boolean;
  onChange: (v: boolean) => void;
  readOnly?: boolean;
  testId?: string;
  children?: React.ReactNode;
}) {
  return (
    <label
      className={cn(
        "flex items-center gap-3 rounded-lg px-3 py-2 transition-colors",
        !readOnly && "cursor-pointer hover:bg-muted/30",
        checked && "bg-primary/5",
      )}
      data-testid={testId}
    >
      <div
        className={cn(
          "flex h-5 w-5 shrink-0 items-center justify-center rounded-md border-2 transition-all",
          checked
            ? "border-primary bg-primary text-primary-foreground"
            : "border-muted-foreground/30",
        )}
      >
        {checked && <Check className="h-3 w-3" />}
      </div>
      <input
        type="checkbox"
        className="sr-only"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        disabled={readOnly}
      />
      <span className="flex-1 text-sm text-foreground">{label}</span>
      {children}
    </label>
  );
}

// ─── Main Editor Component ──────────────────────────────────────────────────

export function ParserEditor({ data, onChange, readOnly }: ParserEditorProps) {
  const { t } = useTranslation();
  const [showDictPicker, setShowDictPicker] = useState(false);
  const [newDictUri, setNewDictUri] = useState("");

  const config = useMemo(() => data.config ?? {}, [data.config]);
  const extensions = useMemo(() => data.extensions ?? {}, [data.extensions]);
  const dictionaries = useMemo(() => extensions.dictionaries ?? [], [extensions.dictionaries]);
  const corrections = useMemo(() => extensions.corrections ?? [], [extensions.corrections]);
  const normalizers = useMemo(() => extensions.normalizer ?? [], [extensions.normalizer]);

  // ── Config helpers ──

  const updateConfig = useCallback(
    (key: keyof ParserConfig, value: boolean) => {
      onChange({ ...data, config: { ...config, [key]: value } });
    },
    [data, config, onChange],
  );

  // ── Dictionary helpers ──

  const isBuiltinEnabled = useCallback(
    (type: string) => dictionaries.some((d) => d.type === type),
    [dictionaries],
  );

  const toggleBuiltinDict = useCallback(
    (type: string, enabled: boolean) => {
      let newDicts: ParserExtensionItem[];
      if (enabled) {
        newDicts = [...dictionaries, { type }];
      } else {
        newDicts = dictionaries.filter((d) => d.type !== type);
      }
      onChange({
        ...data,
        extensions: { ...extensions, dictionaries: newDicts },
      });
    },
    [data, extensions, dictionaries, onChange],
  );

  const regularDicts = dictionaries.filter((d) => d.type === REGULAR_DICT_TYPE);

  const addRegularDict = useCallback(
    (uri: string) => {
      if (!uri.trim()) return;
      const newEntry: ParserExtensionItem = {
        type: REGULAR_DICT_TYPE,
        config: { uri },
      };
      onChange({
        ...data,
        extensions: {
          ...extensions,
          dictionaries: [...dictionaries, newEntry],
        },
      });
    },
    [data, extensions, dictionaries, onChange],
  );

  const removeRegularDict = useCallback(
    (index: number) => {
      // Index is relative to regularDicts, need to find the actual index in dictionaries
      let count = 0;
      const actualIndex = dictionaries.findIndex((d) => {
        if (d.type === REGULAR_DICT_TYPE) {
          if (count === index) return true;
          count++;
        }
        return false;
      });
      if (actualIndex >= 0) {
        const newDicts = dictionaries.filter((_, i) => i !== actualIndex);
        onChange({
          ...data,
          extensions: { ...extensions, dictionaries: newDicts },
        });
      }
    },
    [data, extensions, dictionaries, onChange],
  );

  // ── Correction helpers ──

  const isCorrectionEnabled = useCallback(
    (type: string) => corrections.some((c) => c.type === type),
    [corrections],
  );

  const toggleCorrection = useCallback(
    (type: string, enabled: boolean) => {
      let newCorr: ParserExtensionItem[];
      if (enabled) {
        const info = CORRECTION_TYPES.find((c) => c.type === type);
        const defaultConfig = info?.hasDistance ? { distance: "2" } : undefined;
        newCorr = [...corrections, { type, config: defaultConfig }];
      } else {
        newCorr = corrections.filter((c) => c.type !== type);
      }
      onChange({
        ...data,
        extensions: { ...extensions, corrections: newCorr },
      });
    },
    [data, extensions, corrections, onChange],
  );

  const updateCorrectionConfig = useCallback(
    (type: string, key: string, value: string) => {
      const newCorr = corrections.map((c) =>
        c.type === type ? { ...c, config: { ...c.config, [key]: value } } : c,
      );
      onChange({
        ...data,
        extensions: { ...extensions, corrections: newCorr },
      });
    },
    [data, extensions, corrections, onChange],
  );

  // ── Normalizer helpers ──

  const isNormalizerEnabled = useCallback(
    (type: string) => normalizers.some((n) => n.type === type),
    [normalizers],
  );

  const toggleNormalizer = useCallback(
    (type: string, enabled: boolean) => {
      let newNorm: ParserExtensionItem[];
      if (enabled) {
        newNorm = [...normalizers, { type }];
      } else {
        newNorm = normalizers.filter((n) => n.type !== type);
      }
      onChange({
        ...data,
        extensions: { ...extensions, normalizer: newNorm },
      });
    },
    [data, extensions, normalizers, onChange],
  );

  const updateNormalizerConfig = useCallback(
    (type: string, key: string, value: string) => {
      const newNorm = normalizers.map((n) =>
        n.type === type ? { ...n, config: { ...n.config, [key]: value } } : n,
      );
      onChange({
        ...data,
        extensions: { ...extensions, normalizer: newNorm },
      });
    },
    [data, extensions, normalizers, onChange],
  );

  return (
    <div className="space-y-4" data-testid="parser-editor">
      {/* ══════ Config ══════ */}
      <Section
        label={t("parserEditor.config", "Configuration")}
        icon={Settings}
        accent="text-sky-500"
        testId="parser-config-section"
      >
        <div className="space-y-1">
          <ToggleRow
            label={t("parserEditor.appendExpressions", "Append Expressions")}
            checked={config.appendExpressions ?? true}
            onChange={(v) => updateConfig("appendExpressions", v)}
            readOnly={readOnly}
            testId="toggle-appendExpressions"
          />
          <ToggleRow
            label={t("parserEditor.includeUnused", "Include Unused Expressions")}
            checked={config.includeUnused ?? true}
            onChange={(v) => updateConfig("includeUnused", v)}
            readOnly={readOnly}
            testId="toggle-includeUnused"
          />
          <ToggleRow
            label={t("parserEditor.includeUnknown", "Include Unknown Expressions")}
            checked={config.includeUnknown ?? true}
            onChange={(v) => updateConfig("includeUnknown", v)}
            readOnly={readOnly}
            testId="toggle-includeUnknown"
          />
        </div>
      </Section>

      {/* ══════ Dictionaries ══════ */}
      <Section
        label={t("parserEditor.dictionaries", "Dictionaries")}
        icon={BookOpen}
        accent="text-amber-500"
        badge={dictionaries.length}
        testId="parser-dictionaries-section"
      >
        <div className="space-y-3">
          {/* Built-in dictionaries */}
          <div>
            <span className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
              {t("parserEditor.builtInDicts", "Built-in Dictionaries")}
            </span>
            <div className="mt-1 space-y-0.5">
              {BUILTIN_DICTIONARIES.map((bd) => (
                <ToggleRow
                  key={bd.type}
                  label={t(bd.labelKey, bd.label)}
                  checked={isBuiltinEnabled(bd.type)}
                  onChange={(v) => toggleBuiltinDict(bd.type, v)}
                  readOnly={readOnly}
                  testId={`dict-${bd.type.split(".").pop()}`}
                />
              ))}
            </div>
          </div>

          {/* Regular dictionaries */}
          <div>
            <div className="flex items-center justify-between">
              <span className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                {t("parserEditor.regularDicts", "Regular Dictionaries")}
              </span>
              {!readOnly && (
                <button
                  type="button"
                  onClick={() => setShowDictPicker(true)}
                  className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-primary hover:bg-primary/10 transition-colors"
                  data-testid="add-regular-dict-btn"
                >
                  <Plus className="h-3 w-3" />
                  {t("parserEditor.addDict", "Add")}
                </button>
              )}
            </div>

            {regularDicts.length === 0 && (
              <p className="mt-1 text-xs text-muted-foreground" data-testid="no-regular-dicts">
                {t("parserEditor.noRegularDicts", "No regular dictionaries configured")}
              </p>
            )}

            {regularDicts.map((rd, idx) => {
              const uri = (rd.config?.uri as string) ?? "";
              // Extract ID from eddi:// URI for display
              const displayId = extractIdFromUri(uri);
              return (
                <div
                  key={`regular-${idx}`}
                  className="mt-1 flex items-center gap-2 rounded-lg border border-border bg-card px-3 py-2"
                  data-testid={`regular-dict-${idx}`}
                >
                  <BookOpen className="h-3.5 w-3.5 shrink-0 text-amber-500" />
                  <div className="min-w-0 flex-1">
                    <p className="text-xs font-medium text-foreground truncate">
                      {t("parserEditor.regularDictLabel", "Regular Dictionary")}
                    </p>
                    <p className="text-[10px] text-muted-foreground truncate" title={uri}>
                      {displayId || uri}
                    </p>
                  </div>
                  {!readOnly && (
                    <button
                      type="button"
                      onClick={() => removeRegularDict(idx)}
                      className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors"
                      aria-label={t("parserEditor.removeDict", "Remove dictionary")}
                      data-testid={`remove-regular-dict-${idx}`}
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  )}
                </div>
              );
            })}
          </div>

          {/* Dictionary URI picker (inline) */}
          {showDictPicker && (
            <div className="rounded-lg border border-primary/30 bg-primary/5 p-3 space-y-2" data-testid="dict-uri-picker">
              <label className="text-xs font-medium text-foreground">
                {t("parserEditor.dictUri", "Dictionary Resource URI")}
              </label>
              <input
                type="text"
                value={newDictUri}
                onChange={(e) => setNewDictUri(e.target.value)}
                placeholder="eddi://ai.labs.dictionary/dictionarystore/dictionaries/<id>?version=<v>"
                className="h-8 w-full rounded-md border border-input bg-background px-3 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                data-testid="dict-uri-input"
                autoFocus
              />
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => {
                    addRegularDict(newDictUri);
                    setNewDictUri("");
                    setShowDictPicker(false);
                  }}
                  disabled={!newDictUri.trim()}
                  className="inline-flex items-center gap-1 rounded-md bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
                  data-testid="confirm-add-dict"
                >
                  <Plus className="h-3 w-3" />
                  {t("parserEditor.addDict", "Add")}
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setShowDictPicker(false);
                    setNewDictUri("");
                  }}
                  className="inline-flex items-center gap-1 rounded-md border border-input px-3 py-1.5 text-xs font-medium text-foreground hover:bg-secondary transition-colors"
                  data-testid="cancel-add-dict"
                >
                  <X className="h-3 w-3" />
                  {t("common.cancel", "Cancel")}
                </button>
              </div>
            </div>
          )}
        </div>
      </Section>

      {/* ══════ Corrections ══════ */}
      <Section
        label={t("parserEditor.corrections", "Corrections")}
        icon={Sparkles}
        accent="text-violet-500"
        badge={corrections.length}
        testId="parser-corrections-section"
      >
        <div className="space-y-0.5">
          {CORRECTION_TYPES.map((ct) => {
            const enabled = isCorrectionEnabled(ct.type);
            const entry = corrections.find((c) => c.type === ct.type);
            return (
              <ToggleRow
                key={ct.type}
                label={t(ct.labelKey, ct.label)}
                checked={enabled}
                onChange={(v) => toggleCorrection(ct.type, v)}
                readOnly={readOnly}
                testId={`corr-${ct.type.split(".").pop()}`}
              >
                {enabled && ct.hasDistance && (
                  <div className="flex items-center gap-1.5">
                    <label className="text-[10px] text-muted-foreground whitespace-nowrap">
                      {t("parserEditor.distance", "Distance")}:
                    </label>
                    <input
                      type="number"
                      min={1}
                      max={10}
                      value={(entry?.config?.distance as string) ?? "2"}
                      onChange={(e) =>
                        updateCorrectionConfig(ct.type, "distance", e.target.value)
                      }
                      readOnly={readOnly}
                      className="h-6 w-14 rounded border border-input bg-background px-2 text-xs text-foreground text-center focus:outline-none focus:ring-1 focus:ring-ring"
                      data-testid="levenshtein-distance"
                    />
                  </div>
                )}
              </ToggleRow>
            );
          })}
        </div>
      </Section>

      {/* ══════ Normalizers ══════ */}
      <Section
        label={t("parserEditor.normalizers", "Normalizers")}
        icon={Type}
        accent="text-cyan-500"
        badge={normalizers.length}
        testId="parser-normalizers-section"
      >
        <div className="space-y-0.5">
          {NORMALIZER_TYPES.map((nt) => {
            const enabled = isNormalizerEnabled(nt.type);
            const entry = normalizers.find((n) => n.type === nt.type);
            return (
              <div key={nt.type}>
                <ToggleRow
                  label={t(nt.labelKey, nt.label)}
                  checked={enabled}
                  onChange={(v) => toggleNormalizer(nt.type, v)}
                  readOnly={readOnly}
                  testId={`norm-${nt.type.split(".").pop()}`}
                />
                {enabled && nt.hasConfig && (
                  <div className="ms-10 mt-1 mb-2 flex items-center gap-3 rounded-lg bg-muted/30 px-3 py-2">
                    <ToggleRow
                      label={t("parserEditor.removePunctuation", "Remove Punctuation")}
                      checked={(entry?.config?.removePunctuation as string) === "true"}
                      onChange={(v) =>
                        updateNormalizerConfig(nt.type, "removePunctuation", String(v))
                      }
                      readOnly={readOnly}
                      testId="norm-removePunctuation"
                    />
                    <div className="flex items-center gap-1.5">
                      <label className="text-[10px] text-muted-foreground whitespace-nowrap">
                        {t("parserEditor.regexPattern", "Pattern")}:
                      </label>
                      <input
                        type="text"
                        value={(entry?.config?.punctuationRegexPattern as string) ?? ""}
                        onChange={(e) =>
                          updateNormalizerConfig(
                            nt.type,
                            "punctuationRegexPattern",
                            e.target.value,
                          )
                        }
                        readOnly={readOnly}
                        placeholder="[.!?]"
                        className="h-6 w-24 rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
                        data-testid="norm-punctuationRegexPattern"
                      />
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </Section>
    </div>
  );
}

// ─── Utilities ───────────────────────────────────────────────────────────────

/** Extract a human-readable ID from an eddi:// URI */
function extractIdFromUri(uri: string): string {
  try {
    const normalised = uri.startsWith("eddi://")
      ? uri.replace("eddi://", "http://")
      : uri;
    const url = new URL(normalised, "http://dummy");
    const segments = url.pathname.split("/").filter(Boolean);
    const id = segments.length >= 3 ? segments[2] : undefined;
    const version = url.searchParams.get("version");
    if (id) return `${id}${version ? ` v${version}` : ""}`;
  } catch {
    // ignore
  }
  return uri;
}
