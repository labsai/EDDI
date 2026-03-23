import { useState, useCallback } from "react";
import { useTranslation } from "react-i18next";
import {
  ChevronDown,
  ChevronRight,
  Plus,
  Trash2,
  X,
  MessageSquareText,
} from "lucide-react";

// ─── Types matching OutputConfigurationSet backend model ─────────────────────

export interface OutputItem {
  type: string;
  text?: string;
  delay?: number;
  url?: string;
  [key: string]: unknown;
}

export interface OutputEntry {
  valueAlternatives: OutputItem[];
}

export interface QuickReply {
  value: string;
  expressions: string;
  isDefault?: boolean;
}

export interface OutputConfiguration {
  action: string;
  timesOccurred: number;
  outputs: OutputEntry[];
  quickReplies: QuickReply[];
}

export interface OutputConfig {
  lang?: string;
  outputSet: OutputConfiguration[];
}

// ─── Constants ───────────────────────────────────────────────────────────────

const OUTPUT_TYPES = [
  "text", "image", "quickReply", "agentFace", "inputField",
  "applicationLink", "button", "other",
] as const;

// ─── Sub-components ──────────────────────────────────────────────────────────

function OutputItemEditor({
  item, onChange, onRemove, readOnly,
}: {
  item: OutputItem; onChange: (i: OutputItem) => void;
  onRemove: () => void; readOnly?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <div className="flex items-start gap-1.5" data-testid="output-item-row">
      <select value={item.type} onChange={(e) => onChange({ ...item, type: e.target.value })}
        disabled={readOnly}
        className="h-7 rounded border border-input bg-background px-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60">
        {OUTPUT_TYPES.map((ot) => (<option key={ot} value={ot}>{ot}</option>))}
      </select>
      {item.type === "text" ? (
        <input type="text" value={item.text ?? ""} onChange={(e) => onChange({ ...item, text: e.target.value })}
          readOnly={readOnly} placeholder={t("outputEditor.textPlaceholder", "Output text...")}
          className="h-7 flex-1 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
      ) : item.type === "image" || item.type === "applicationLink" ? (
        <input type="text" value={(item.url as string) ?? ""} onChange={(e) => onChange({ ...item, url: e.target.value })}
          readOnly={readOnly} placeholder={t("outputEditor.urlPlaceholder", "URL...")}
          className="h-7 flex-1 rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
      ) : (
        <input type="text" readOnly value={JSON.stringify(Object.fromEntries(Object.entries(item).filter(([k]) => k !== "type")))}
          className="h-7 flex-1 rounded border border-input bg-muted px-2 font-mono text-xs text-foreground" />
      )}
      {item.type === "text" && (
        <input type="number" value={item.delay ?? 0} onChange={(e) => onChange({ ...item, delay: parseInt(e.target.value, 10) || 0 })}
          readOnly={readOnly} title={t("outputEditor.delayMs", "Delay (ms)")}
          className="h-7 w-16 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
      )}
      {!readOnly && (
        <button type="button" onClick={onRemove} className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors">
          <X className="h-3 w-3" />
        </button>
      )}
    </div>
  );
}

function QuickReplyRow({
  qr, onChange, onRemove, readOnly,
}: {
  qr: QuickReply; onChange: (q: QuickReply) => void;
  onRemove: () => void; readOnly?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <div className="flex items-center gap-1.5" data-testid="quickreply-row">
      <input type="text" value={qr.value} onChange={(e) => onChange({ ...qr, value: e.target.value })}
        readOnly={readOnly} placeholder={t("outputEditor.qrValue", "Button text")}
        className="h-7 w-36 rounded border border-input bg-background px-2 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
      <input type="text" value={qr.expressions} onChange={(e) => onChange({ ...qr, expressions: e.target.value })}
        readOnly={readOnly} placeholder={t("outputEditor.qrExpressions", "Expressions")}
        className="h-7 flex-1 rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
      <label className="inline-flex items-center gap-1 text-xs text-foreground">
        <input type="checkbox" checked={qr.isDefault ?? false} onChange={(e) => onChange({ ...qr, isDefault: e.target.checked })}
          disabled={readOnly} className="h-3 w-3 accent-primary" />
        {t("outputEditor.default", "Default")}
      </label>
      {!readOnly && (
        <button type="button" onClick={onRemove} className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors">
          <X className="h-3 w-3" />
        </button>
      )}
    </div>
  );
}

function OutputConfigEditor({
  config, onChange, onRemove, readOnly,
}: {
  config: OutputConfiguration; onChange: (c: OutputConfiguration) => void;
  onRemove: () => void; readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(true);

  return (
    <div className="rounded-xl border border-border bg-card shadow-sm" data-testid="output-config-editor">
      <div className="flex items-center gap-2 p-3">
        <button type="button" onClick={() => setExpanded(!expanded)}
          className="rounded p-0.5 text-muted-foreground hover:text-foreground transition-colors">
          {expanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
        </button>
        <span className="inline-flex rounded bg-primary/10 px-1.5 py-0.5 text-[10px] font-bold tracking-wider text-primary">
          {config.action || "—"}
        </span>
        <input type="text" value={config.action} onChange={(e) => onChange({ ...config, action: e.target.value })}
          readOnly={readOnly} placeholder={t("outputEditor.actionName", "Action name")}
          className="h-8 flex-1 rounded-md border border-input bg-background px-3 text-sm font-medium text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          data-testid="output-action-input" />
        <div className="flex items-center gap-1">
          <label className="text-xs text-muted-foreground whitespace-nowrap">{t("outputEditor.timesOccurred", "×")}</label>
          <input type="number" value={config.timesOccurred}
            onChange={(e) => onChange({ ...config, timesOccurred: parseInt(e.target.value, 10) || 0 })}
            readOnly={readOnly} className="h-8 w-14 rounded-md border border-input bg-background px-2 text-xs text-center text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
        </div>
        {!readOnly && (
          <button type="button" onClick={onRemove}
            className="rounded p-1.5 text-muted-foreground hover:text-destructive transition-colors"
            aria-label={t("outputEditor.removeOutput", "Remove")}>
            <Trash2 className="h-4 w-4" />
          </button>
        )}
      </div>

      {expanded && (
        <div className="space-y-4 border-t px-4 py-3">
          {/* Outputs */}
          <div>
            <h5 className="mb-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              {t("outputEditor.outputs", "Outputs")}
            </h5>
            <div className="space-y-3">
              {config.outputs.map((output, oi) => (
                <div key={oi} className="rounded-lg border border-dashed border-muted-foreground/30 p-2 space-y-1.5">
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
                      {t("outputEditor.alternativeGroup", "Alternative Group")} {oi + 1}
                    </span>
                    {!readOnly && (
                      <button type="button" onClick={() => onChange({ ...config, outputs: config.outputs.filter((_, j) => j !== oi) })}
                        className="rounded p-0.5 text-muted-foreground hover:text-destructive transition-colors">
                        <Trash2 className="h-3 w-3" />
                      </button>
                    )}
                  </div>
                  {output.valueAlternatives.map((item, ii) => (
                    <OutputItemEditor key={ii} item={item}
                      onChange={(updated) => { const outputs = [...config.outputs]; const alts = [...output.valueAlternatives]; alts[ii] = updated; outputs[oi] = { ...output, valueAlternatives: alts }; onChange({ ...config, outputs }); }}
                      onRemove={() => { const outputs = [...config.outputs]; outputs[oi] = { ...output, valueAlternatives: output.valueAlternatives.filter((_, j) => j !== ii) }; onChange({ ...config, outputs }); }}
                      readOnly={readOnly} />
                  ))}
                  {!readOnly && (
                    <button type="button" onClick={() => { const outputs = [...config.outputs]; outputs[oi] = { ...output, valueAlternatives: [...output.valueAlternatives, { type: "text", text: "" }] }; onChange({ ...config, outputs }); }}
                      className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors">
                      <Plus className="h-3 w-3" />{t("outputEditor.addAlternative", "Add Alternative")}
                    </button>
                  )}
                </div>
              ))}
              {!readOnly && (
                <button type="button" onClick={() => onChange({ ...config, outputs: [...config.outputs, { valueAlternatives: [{ type: "text", text: "" }] }] })}
                  className="inline-flex items-center gap-1.5 rounded-md border border-dashed border-muted-foreground/40 px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:border-primary hover:text-primary">
                  <Plus className="h-3.5 w-3.5" />{t("outputEditor.addOutputGroup", "Add Output Group")}
                </button>
              )}
            </div>
          </div>

          {/* Quick Replies */}
          <div data-testid="quickreplies-section">
            <h5 className="mb-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              {t("outputEditor.quickReplies", "Quick Replies")}
            </h5>
            <div className="space-y-1.5">
              {(config.quickReplies ?? []).length === 0 && (
                <p className="text-xs italic text-muted-foreground">{t("outputEditor.noQuickReplies", "No quick replies")}</p>
              )}
              {(config.quickReplies ?? []).map((qr, qi) => (
                <QuickReplyRow key={qi} qr={qr}
                  onChange={(updated) => { const qrs = [...(config.quickReplies ?? [])]; qrs[qi] = updated; onChange({ ...config, quickReplies: qrs }); }}
                  onRemove={() => onChange({ ...config, quickReplies: (config.quickReplies ?? []).filter((_, j) => j !== qi) })}
                  readOnly={readOnly} />
              ))}
              {!readOnly && (
                <button type="button" onClick={() => onChange({ ...config, quickReplies: [...(config.quickReplies ?? []), { value: "", expressions: "" }] })}
                  className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors">
                  <Plus className="h-3 w-3" />{t("outputEditor.addQuickReply", "Add Quick Reply")}
                </button>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Main Component ──────────────────────────────────────────────────────────

export interface OutputEditorProps {
  data: OutputConfig;
  onChange: (data: OutputConfig) => void;
  readOnly?: boolean;
}

export function OutputEditor({ data, onChange, readOnly }: OutputEditorProps) {
  const { t } = useTranslation();

  const addOutput = useCallback(() => {
    const newEntry: OutputConfiguration = {
      action: "", timesOccurred: 0,
      outputs: [{ valueAlternatives: [{ type: "text", text: "" }] }],
      quickReplies: [],
    };
    onChange({ ...data, outputSet: [...(data.outputSet ?? []), newEntry] });
  }, [data, onChange]);

  return (
    <div className="space-y-6" data-testid="output-editor">
      <div className="flex items-center gap-2">
        <label className="text-sm font-medium text-foreground">{t("outputEditor.language", "Language")}</label>
        <input type="text" value={data.lang ?? ""} onChange={(e) => onChange({ ...data, lang: e.target.value })}
          readOnly={readOnly} placeholder={t("outputEditor.langPlaceholder", "e.g. en, de")}
          className="h-8 w-24 rounded-md border border-input bg-background px-3 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
      </div>

      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
            <MessageSquareText className="h-4 w-4 text-primary" />
            {t("outputEditor.outputSets", "Output Sets")}
          </h3>
          {!readOnly && (
            <button type="button" onClick={addOutput}
              className="inline-flex items-center gap-1.5 rounded-lg border border-dashed border-muted-foreground/40 px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:border-primary hover:text-primary"
              data-testid="add-output-btn">
              <Plus className="h-3.5 w-3.5" />{t("outputEditor.addOutputSet", "Add Output Set")}
            </button>
          )}
        </div>

        {(data.outputSet ?? []).length === 0 && (
          <div className="rounded-lg border border-dashed py-8 text-center text-sm text-muted-foreground">
            {t("outputEditor.noOutputSets", "No output sets configured")}
          </div>
        )}

        {(data.outputSet ?? []).map((entry, ei) => (
          <OutputConfigEditor key={ei} config={entry}
            onChange={(updated) => { const set = [...(data.outputSet ?? [])]; set[ei] = updated; onChange({ ...data, outputSet: set }); }}
            onRemove={() => onChange({ ...data, outputSet: (data.outputSet ?? []).filter((_, j) => j !== ei) })}
            readOnly={readOnly} />
        ))}
      </div>
    </div>
  );
}
