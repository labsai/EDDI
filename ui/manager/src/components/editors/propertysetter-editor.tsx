import { useState, useCallback, useEffect } from "react";
import { useTranslation } from "react-i18next";
import {
  ChevronDown,
  ChevronRight,
  Plus,
  Trash2,
  X,
  Settings,
  Maximize2,
} from "lucide-react";
import Editor from "@monaco-editor/react";

// ─── Types matching PropertySetterConfiguration backend model ────────────────

export interface PropertyInstruction {
  name?: string;
  valueString?: string;
  valueInt?: number;
  valueFloat?: number;
  valueBoolean?: boolean;
  scope?: "step" | "conversation" | "longTerm";
  fromObjectPath?: string;
  toObjectPath?: string;
  override?: boolean;
  convertToObject?: boolean;
}

export interface SetOnActions {
  actions: string[];
  setProperties: PropertyInstruction[];
}

export interface PropertySetterConfig {
  setOnActions: SetOnActions[];
}

// ─── Constants ───────────────────────────────────────────────────────────────

const SCOPES = ["step", "conversation", "longTerm"] as const;

// ─── Sub-components ──────────────────────────────────────────────────────────

function ActionTags({
  actions, onChange, readOnly,
}: {
  actions: string[]; onChange: (a: string[]) => void; readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [input, setInput] = useState("");
  const addAction = () => {
    const trimmed = input.trim();
    if (trimmed && !actions.includes(trimmed)) { onChange([...actions, trimmed]); setInput(""); }
  };

  return (
    <div className="space-y-1.5">
      <div className="flex flex-wrap gap-1.5">
        {actions.map((a, i) => (
          <span key={i} className="inline-flex items-center gap-1 rounded-md bg-primary/10 px-2 py-0.5 text-xs font-medium text-primary">
            {a}
            {!readOnly && (
              <button type="button" onClick={() => onChange(actions.filter((_, j) => j !== i))}
                className="rounded p-0.5 hover:bg-primary/20 transition-colors" aria-label={`Remove ${a}`}>
                <X className="h-3 w-3" />
              </button>
            )}
          </span>
        ))}
        {actions.length === 0 && (
          <span className="text-xs text-muted-foreground italic">{t("propertySetterEditor.noActions", "No actions")}</span>
        )}
      </div>
      {!readOnly && (
        <div className="flex gap-1.5">
          <input type="text" value={input} onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); addAction(); } }}
            placeholder={t("propertySetterEditor.actionPlaceholder", "e.g. greet, fallback")}
            className="h-8 flex-1 rounded-md border border-input bg-background px-2 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
          <button type="button" onClick={addAction}
            className="inline-flex h-8 items-center gap-1 rounded-md border border-input px-2 text-xs font-medium text-foreground transition-colors hover:bg-secondary">
            <Plus className="h-3 w-3" />
          </button>
        </div>
      )}
    </div>
  );
}

function PropertyRow({
  prop, onChange, onRemove, readOnly,
}: {
  prop: PropertyInstruction; onChange: (p: PropertyInstruction) => void;
  onRemove: () => void; readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [showEditor, setShowEditor] = useState(false);
  const value = prop.valueString ?? "";

  return (
    <>
      <div className="flex flex-wrap items-center gap-1.5 rounded-lg border border-border bg-card p-2" data-testid="property-row">
        <input type="text" value={prop.name ?? ""} onChange={(e) => onChange({ ...prop, name: e.target.value })}
          readOnly={readOnly} placeholder={t("propertySetterEditor.propName", "Property name")}
          className="h-7 w-40 rounded border border-input bg-background px-2 text-xs font-medium text-foreground focus:outline-none focus:ring-1 focus:ring-ring" />
        <span className="text-xs text-muted-foreground">=</span>
        <div className="flex flex-1 min-w-[120px] items-center gap-1">
          <input type="text" value={value} onChange={(e) => onChange({ ...prop, valueString: e.target.value })}
            readOnly={readOnly} placeholder={t("propertySetterEditor.propValue", "Value / expression")}
            title={value}
            className="h-7 flex-1 rounded border border-input bg-background px-2 text-xs text-foreground font-mono focus:outline-none focus:ring-1 focus:ring-ring" />
          {!readOnly && (
            <button type="button" onClick={() => setShowEditor(true)}
              className="shrink-0 rounded p-1 text-muted-foreground hover:text-primary hover:bg-primary/10 transition-colors"
              title={t("propertySetterEditor.openEditor", "Edit in editor")}>
              <Maximize2 className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
        <select value={prop.scope ?? "conversation"} onChange={(e) => onChange({ ...prop, scope: e.target.value as PropertyInstruction["scope"] })}
          disabled={readOnly}
          className="h-7 rounded border border-input bg-background px-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:opacity-60"
          data-testid="scope-select">
          {SCOPES.map((s) => (<option key={s} value={s}>{s}</option>))}
        </select>
        <input type="text" value={prop.fromObjectPath ?? ""} onChange={(e) => onChange({ ...prop, fromObjectPath: e.target.value })}
          readOnly={readOnly} placeholder={t("propertySetterEditor.fromPath", "From path")}
          className="h-7 w-28 rounded border border-input bg-background px-2 font-mono text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
          title={t("propertySetterEditor.fromObjectPath", "fromObjectPath")} />
        <label className="inline-flex items-center gap-1 text-xs text-foreground cursor-pointer">
          <input type="checkbox" checked={prop.override ?? true}
            onChange={(e) => onChange({ ...prop, override: e.target.checked })}
            disabled={readOnly} className="h-3 w-3 accent-primary" />
          {t("propertySetterEditor.override", "Override")}
        </label>
        {!readOnly && (
          <button type="button" onClick={onRemove}
            className="rounded p-1 text-muted-foreground hover:text-destructive transition-colors">
            <Trash2 className="h-3 w-3" />
          </button>
        )}
      </div>

      {/* Monaco editor modal */}
      {showEditor && (
        <ValueEditorModal
          name={prop.name ?? "property"}
          value={value}
          onChange={(v) => onChange({ ...prop, valueString: v })}
          onClose={() => setShowEditor(false)}
          readOnly={readOnly}
        />
      )}
    </>
  );
}

/** Modal with Monaco editor for editing long property values */
function ValueEditorModal({
  name, value, onChange, onClose, readOnly,
}: {
  name: string; value: string; onChange: (v: string) => void;
  onClose: () => void; readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [localValue, setLocalValue] = useState(value);

  // Esc key closes the modal
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  return (
    <>
      <div className="fixed inset-0 z-50 bg-black/50 backdrop-blur-sm" onClick={onClose} />
      <div className="fixed inset-0 z-50 flex items-center justify-center p-8">
        <div className="flex w-full max-w-3xl flex-col rounded-xl border bg-card shadow-2xl" style={{ height: "70vh" }}>
          <div className="flex items-center justify-between border-b border-border px-5 py-3">
            <h3 className="text-sm font-semibold text-foreground">
              {t("propertySetterEditor.editValue", "Edit Value")} — <code className="text-primary">{name}</code>
            </h3>
            <button onClick={onClose} className="rounded-md p-1 text-muted-foreground hover:bg-secondary transition-colors">
              <X className="h-4 w-4" />
            </button>
          </div>
          <div className="flex-1 min-h-0">
            <MonacoValueEditor value={localValue} onChange={setLocalValue} readOnly={readOnly} />
          </div>
          <div className="flex justify-end gap-2 border-t border-border px-5 py-3">
            <button onClick={onClose}
              className="rounded-lg px-4 py-2 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors">
              {t("common.cancel", "Cancel")}
            </button>
            <button
              onClick={() => { onChange(localValue); onClose(); }}
              disabled={readOnly}
              className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50">
              {t("common.apply", "Apply")}
            </button>
          </div>
        </div>
      </div>
    </>
  );
}

/** Monaco editor for the value editor modal */
function MonacoValueEditor({
  value, onChange, readOnly,
}: {
  value: string; onChange: (v: string) => void; readOnly?: boolean;
}) {
  return (
    <Editor
      height="100%"
      language="plaintext"
      theme="vs-dark"
      value={value}
      onChange={(v: string | undefined) => onChange(v ?? "")}
      options={{
        readOnly,
        minimap: { enabled: false },
        lineNumbers: "off",
        wordWrap: "on",
        fontSize: 13,
        padding: { top: 12 },
        scrollBeyondLastLine: false,
        renderWhitespace: "boundary",
      }}
    />
  );
}

function SetterEditor({
  setter, onChange, onRemove, readOnly,
}: {
  setter: SetOnActions; onChange: (s: SetOnActions) => void;
  onRemove: () => void; readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [expanded, setExpanded] = useState(true);

  return (
    <div className="rounded-xl border border-border bg-card shadow-sm" data-testid="setter-editor">
      <div className="flex items-center gap-2 p-3">
        <button type="button" onClick={() => setExpanded(!expanded)}
          className="rounded p-0.5 text-muted-foreground hover:text-foreground transition-colors">
          {expanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
        </button>
        <span className="text-sm font-medium text-foreground">
          {setter.actions.length > 0 ? setter.actions.join(", ") : t("propertySetterEditor.untitled", "(no actions)")}
        </span>
        <span className="flex-1" />
        <span className="rounded bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground">
          {setter.setProperties.length} {t("propertySetterEditor.propsCount", "props")}
        </span>
        {!readOnly && (
          <button type="button" onClick={onRemove}
            className="rounded p-1.5 text-muted-foreground hover:text-destructive transition-colors"
            aria-label={t("propertySetterEditor.removeSetter", "Remove")}>
            <Trash2 className="h-4 w-4" />
          </button>
        )}
      </div>

      {expanded && (
        <div className="space-y-3 border-t px-4 py-3">
          <div>
            <h5 className="mb-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              {t("propertySetterEditor.triggerActions", "Trigger Actions")}
            </h5>
            <ActionTags actions={setter.actions} onChange={(a) => onChange({ ...setter, actions: a })} readOnly={readOnly} />
          </div>
          <div>
            <h5 className="mb-1.5 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              {t("propertySetterEditor.properties", "Properties")}
            </h5>
            <div className="space-y-1.5">
              {setter.setProperties.length === 0 && (
                <p className="text-xs italic text-muted-foreground">{t("propertySetterEditor.noProperties", "No properties")}</p>
              )}
              {setter.setProperties.map((prop, pi) => (
                <PropertyRow key={pi} prop={prop}
                  onChange={(updated) => { const props = [...setter.setProperties]; props[pi] = updated; onChange({ ...setter, setProperties: props }); }}
                  onRemove={() => onChange({ ...setter, setProperties: setter.setProperties.filter((_, j) => j !== pi) })}
                  readOnly={readOnly} />
              ))}
              {!readOnly && (
                <button type="button"
                  onClick={() => onChange({ ...setter, setProperties: [...setter.setProperties, { name: "", valueString: "", scope: "conversation", override: true }] })}
                  className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs text-muted-foreground hover:text-foreground transition-colors">
                  <Plus className="h-3 w-3" />{t("propertySetterEditor.addProperty", "Add Property")}
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

export interface PropertySetterEditorProps {
  data: PropertySetterConfig;
  onChange: (data: PropertySetterConfig) => void;
  readOnly?: boolean;
}

export function PropertySetterEditor({ data, onChange, readOnly }: PropertySetterEditorProps) {
  const { t } = useTranslation();

  const addSetter = useCallback(() => {
    const newSetter: SetOnActions = { actions: [], setProperties: [{ name: "", valueString: "", scope: "conversation", override: true }] };
    onChange({ ...data, setOnActions: [...(data.setOnActions ?? []), newSetter] });
  }, [data, onChange]);

  return (
    <div className="space-y-6" data-testid="propertysetter-editor">
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
            <Settings className="h-4 w-4 text-primary" />
            {t("propertySetterEditor.setters", "Property Setters")}
          </h3>
          {!readOnly && (
            <button type="button" onClick={addSetter}
              className="inline-flex items-center gap-1.5 rounded-lg border border-dashed border-muted-foreground/40 px-3 py-1.5 text-xs font-medium text-muted-foreground transition-colors hover:border-primary hover:text-primary"
              data-testid="add-setter-btn">
              <Plus className="h-3.5 w-3.5" />{t("propertySetterEditor.addSetter", "Add Setter")}
            </button>
          )}
        </div>

        {(data.setOnActions ?? []).length === 0 && (
          <div className="rounded-lg border border-dashed py-8 text-center text-sm text-muted-foreground">
            {t("propertySetterEditor.noSetters", "No property setters configured")}
          </div>
        )}

        {(data.setOnActions ?? []).map((setter, si) => (
          <SetterEditor key={si} setter={setter}
            onChange={(updated) => { const s = [...(data.setOnActions ?? [])]; s[si] = updated; onChange({ ...data, setOnActions: s }); }}
            onRemove={() => onChange({ ...data, setOnActions: (data.setOnActions ?? []).filter((_, j) => j !== si) })}
            readOnly={readOnly} />
        ))}
      </div>
    </div>
  );
}
