import { Plus, Trash2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import {
  CONVERSATION_STATES,
  type Expectation,
  type ExpectedConversationState,
  type PropertyExpectation,
  type ToolCallExpectation,
} from "@/lib/api/agent-tests";
import { cleanExpectation as clean, propertyKind, type PropertyKind } from "@/lib/agent-test-utils";
import { INPUT_CLASS, JsonField, LabelledField, OptionalNumberField, StringListField } from "./fields";

function buildProperty(kind: PropertyKind, value: unknown): PropertyExpectation {
  switch (kind) {
    case "absent":
      return { absent: true };
    case "exists":
      return { exists: true };
    case "matches":
      return { matches: typeof value === "string" ? value : "" };
    default:
      return { equals: value ?? "" };
  }
}

export function ExpectationEditor({
  value,
  onChange,
  readOnly,
}: {
  value: Expectation | undefined;
  onChange: (value: Expectation | undefined) => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const e = value ?? {};
  const set = (patch: Partial<Expectation>) => onChange(clean({ ...e, ...patch }));

  const properties = Object.entries(e.properties ?? {});
  const setProperties = (next: Array<[string, PropertyExpectation]>) =>
    set({ properties: next.length > 0 ? Object.fromEntries(next) : undefined });

  const toolCalled = e.toolCalled ?? [];
  const setToolCalled = (next: ToolCallExpectation[]) => set({ toolCalled: next.length > 0 ? next : undefined });

  return (
    <div className="space-y-4" data-testid="expectation-editor">
      <div className="grid gap-3 sm:grid-cols-2">
        <LabelledField label={t("testSuiteEditor.actionsContain", "Actions fired (all of)")} hint={t("testSuiteEditor.listHint", "Comma-separated")}>
          {(id) => (
            <StringListField
              id={id}
              value={e.actionsContain}
              onChange={(v) => set({ actionsContain: v })}
              readOnly={readOnly}
              placeholder="greet, ask_date"
              testId="expect-actions-contain"
            />
          )}
        </LabelledField>
        <LabelledField label={t("testSuiteEditor.actionsExact", "Actions fired (exactly, in order)")}>
          {(id) => (
            <StringListField
              id={id}
              value={e.actionsExact}
              onChange={(v) => set({ actionsExact: v })}
              readOnly={readOnly}
              testId="expect-actions-exact"
            />
          )}
        </LabelledField>
        <LabelledField label={t("testSuiteEditor.outputContains", "Output contains")}>
          {(id) => (
            <input
              id={id}
              type="text"
              className={INPUT_CLASS}
              value={e.outputContains ?? ""}
              readOnly={readOnly}
              onChange={(ev) => set({ outputContains: ev.target.value })}
            />
          )}
        </LabelledField>
        <LabelledField
          label={t("testSuiteEditor.outputMatches", "Output matches (regex)")}
          hint={t("testSuiteEditor.regexHint", "Searched, not a full match. At most 500 characters.")}
        >
          {(id) => (
            <input
              id={id}
              type="text"
              className={`${INPUT_CLASS} font-mono`}
              value={e.outputMatches ?? ""}
              readOnly={readOnly}
              maxLength={500}
              placeholder="(?i)3 rooms"
              onChange={(ev) => set({ outputMatches: ev.target.value })}
            />
          )}
        </LabelledField>
      </div>

      {/* Properties */}
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <h4 className="text-xs font-semibold text-foreground">
            {t("testSuiteEditor.properties", "Conversation properties")}
          </h4>
          {!readOnly && (
            <button
              type="button"
              onClick={() => setProperties([...properties, [`property_${properties.length + 1}`, { equals: "" }]])}
              className="inline-flex items-center gap-1 text-xs text-primary hover:underline"
              data-testid="expect-add-property"
            >
              <Plus className="h-3 w-3" aria-hidden="true" />
              {t("testSuiteEditor.addProperty", "Add property")}
            </button>
          )}
        </div>
        {properties.map(([name, p], i) => {
          const kind = propertyKind(p);
          const replace = (entry: [string, PropertyExpectation]) =>
            setProperties(properties.map((x, j) => (j === i ? entry : x)));
          return (
            <div key={i} className="flex flex-wrap items-start gap-2" data-testid="expect-property-row">
              <input
                type="text"
                aria-label={t("testSuiteEditor.propertyName", "Property name")}
                className={`${INPUT_CLASS} w-36 sm:w-44`}
                value={name}
                readOnly={readOnly}
                onChange={(ev) => replace([ev.target.value, p])}
              />
              <select
                aria-label={t("testSuiteEditor.propertyCheck", "Check")}
                className={`${INPUT_CLASS} w-36`}
                value={kind}
                disabled={readOnly}
                onChange={(ev) => replace([name, buildProperty(ev.target.value as PropertyKind, p.equals ?? p.matches)])}
              >
                <option value="equals">{t("testSuiteEditor.kindEquals", "equals")}</option>
                <option value="matches">{t("testSuiteEditor.kindMatches", "matches (regex)")}</option>
                <option value="exists">{t("testSuiteEditor.kindExists", "exists")}</option>
                <option value="absent">{t("testSuiteEditor.kindAbsent", "is absent")}</option>
              </select>
              {kind === "equals" && (
                <div className="min-w-40 flex-1">
                  <JsonField
                    value={p.equals}
                    readOnly={readOnly}
                    rows={1}
                    placeholder='"Vienna", 3, {"a":1}'
                    onChange={(v) => replace([name, { equals: v ?? "" }])}
                  />
                </div>
              )}
              {kind === "matches" && (
                <input
                  type="text"
                  aria-label={t("testSuiteEditor.regex", "Regex")}
                  className={`${INPUT_CLASS} min-w-40 flex-1 font-mono`}
                  value={p.matches ?? ""}
                  readOnly={readOnly}
                  maxLength={500}
                  onChange={(ev) => replace([name, { matches: ev.target.value }])}
                />
              )}
              {!readOnly && (
                <button
                  type="button"
                  onClick={() => setProperties(properties.filter((_, j) => j !== i))}
                  aria-label={t("testSuiteEditor.removeProperty", "Remove property")}
                  className="rounded p-1.5 text-muted-foreground hover:bg-secondary hover:text-destructive"
                >
                  <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
                </button>
              )}
            </div>
          );
        })}
      </div>

      {/* Tools */}
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <h4 className="text-xs font-semibold text-foreground">{t("testSuiteEditor.toolCalled", "Tools called")}</h4>
          {!readOnly && (
            <button
              type="button"
              onClick={() => setToolCalled([...toolCalled, { tool: "" }])}
              className="inline-flex items-center gap-1 text-xs text-primary hover:underline"
              data-testid="expect-add-tool"
            >
              <Plus className="h-3 w-3" aria-hidden="true" />
              {t("testSuiteEditor.addToolCall", "Add tool call")}
            </button>
          )}
        </div>
        {toolCalled.map((c, i) => {
          const replace = (next: ToolCallExpectation) => setToolCalled(toolCalled.map((x, j) => (j === i ? next : x)));
          return (
            <div key={i} className="flex flex-wrap items-start gap-2" data-testid="expect-tool-row">
              <input
                type="text"
                aria-label={t("testSuiteEditor.toolName", "Tool name")}
                className={`${INPUT_CLASS} w-44`}
                value={c.tool ?? ""}
                readOnly={readOnly}
                onChange={(ev) => replace({ ...c, tool: ev.target.value })}
              />
              <div className="min-w-40 flex-1">
                <JsonField
                  value={c.argsSubset}
                  readOnly={readOnly}
                  objectOnly
                  rows={1}
                  placeholder='{"city":"Vienna"}'
                  onChange={(v) => replace({ ...c, argsSubset: v as Record<string, unknown> | undefined })}
                />
              </div>
              {!readOnly && (
                <button
                  type="button"
                  onClick={() => setToolCalled(toolCalled.filter((_, j) => j !== i))}
                  aria-label={t("testSuiteEditor.removeToolCall", "Remove tool call")}
                  className="rounded p-1.5 text-muted-foreground hover:bg-secondary hover:text-destructive"
                >
                  <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
                </button>
              )}
            </div>
          );
        })}
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <LabelledField label={t("testSuiteEditor.toolNotCalled", "Tools not called")}>
          {(id) => (
            <StringListField
              id={id}
              value={e.toolNotCalled}
              onChange={(v) => set({ toolNotCalled: v })}
              readOnly={readOnly}
              placeholder="createBooking"
            />
          )}
        </LabelledField>
        <LabelledField label={t("testSuiteEditor.maxToolCalls", "Max tool calls")}>
          {(id) => (
            <OptionalNumberField id={id} integer min={0} value={e.maxToolCalls} onChange={(v) => set({ maxToolCalls: v })} readOnly={readOnly} />
          )}
        </LabelledField>
        <LabelledField label={t("testSuiteEditor.maxCostUsd", "Max cost (USD)")}>
          {(id) => (
            <OptionalNumberField id={id} min={0} value={e.maxCostUsd} onChange={(v) => set({ maxCostUsd: v })} readOnly={readOnly} />
          )}
        </LabelledField>
        <LabelledField label={t("testSuiteEditor.conversationState", "Conversation state after")}>
          {(id) => (
            <select
              id={id}
              className={INPUT_CLASS}
              value={e.conversationState ?? ""}
              disabled={readOnly}
              onChange={(ev) =>
                set({ conversationState: (ev.target.value || undefined) as ExpectedConversationState | undefined })
              }
            >
              <option value="">{t("testSuiteEditor.anyState", "Any")}</option>
              {CONVERSATION_STATES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          )}
        </LabelledField>
      </div>
    </div>
  );
}
