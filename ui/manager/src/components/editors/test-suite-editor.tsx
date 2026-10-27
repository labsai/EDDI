import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ChevronDown, ChevronRight, FlaskConical, MessageSquare, Plus, Trash2, Wrench } from "lucide-react";
import { EditorSection } from "./editor-section";
import { ExpectationEditor } from "@/components/agent-tests/expectation-editor";
import { countExpectations } from "@/lib/agent-test-utils";
import { INPUT_CLASS, JsonField, LabelledField, OptionalNumberField } from "@/components/agent-tests/fields";
import type {
  AgentTestSuite,
  SuiteCase,
  SuiteTurn,
  ToolMock,
  ToolMode,
  UnmockedToolsPolicy,
} from "@/lib/api/agent-tests";

export type { AgentTestSuite };

export interface TestSuiteEditorProps {
  data: AgentTestSuite;
  onChange: (data: AgentTestSuite) => void;
  readOnly?: boolean;
}

function replaceAt<T>(list: T[], index: number, next: T): T[] {
  return list.map((x, i) => (i === index ? next : x));
}

function dropEmpty<T extends object>(obj: T): T | undefined {
  const entries = Object.entries(obj).filter(([, v]) => v !== undefined && v !== "");
  return entries.length > 0 ? (Object.fromEntries(entries) as T) : undefined;
}

// ─── Turn ───────────────────────────────────────────────────────────────────

function TurnEditor({
  turn,
  index,
  onChange,
  onRemove,
  readOnly,
}: {
  turn: SuiteTurn;
  index: number;
  onChange: (turn: SuiteTurn) => void;
  onRemove: () => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const count = countExpectations(turn.expect);

  return (
    <div className="rounded-lg border border-border bg-background/50 p-3" data-testid="suite-turn">
      <div className="flex items-start gap-2">
        <span className="mt-1.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-primary/10 text-[10px] font-semibold text-primary">
          {index + 1}
        </span>
        <div className="min-w-0 flex-1 space-y-2">
          <textarea
            aria-label={t("testSuiteEditor.turnInput", "User says")}
            value={turn.input ?? ""}
            readOnly={readOnly}
            rows={1}
            maxLength={20000}
            placeholder={t("testSuiteEditor.turnInputPlaceholder", "What the user types in this turn")}
            onChange={(e) => onChange({ ...turn, input: e.target.value })}
            className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
            data-testid="turn-input"
          />
          <button
            type="button"
            onClick={() => setOpen(!open)}
            aria-expanded={open}
            className="inline-flex items-center gap-1 text-xs font-medium text-muted-foreground hover:text-foreground"
            data-testid="turn-expect-toggle"
          >
            {open ? <ChevronDown className="h-3 w-3" aria-hidden="true" /> : <ChevronRight className="h-3 w-3" aria-hidden="true" />}
            {t("testSuiteEditor.expectations", "Expectations")}
            <span className="rounded-full bg-secondary px-1.5 text-[10px]">
              {count === 0 ? t("testSuiteEditor.noneSet", "none") : count}
            </span>
          </button>
          {open && (
            <div className="space-y-3 border-s-2 border-border ps-3">
              <ExpectationEditor value={turn.expect} onChange={(expect) => onChange({ ...turn, expect })} readOnly={readOnly} />
              <LabelledField
                label={t("testSuiteEditor.turnContext", "Extra context for this turn (JSON object)")}
                hint={t("testSuiteEditor.turnContextHint", "Applied on top of the case context")}
              >
                {(id) => (
                  <JsonField
                    id={id}
                    objectOnly
                    value={turn.context}
                    readOnly={readOnly}
                    onChange={(v) => onChange({ ...turn, context: v as Record<string, unknown> | undefined })}
                  />
                )}
              </LabelledField>
            </div>
          )}
        </div>
        {!readOnly && (
          <button
            type="button"
            onClick={onRemove}
            aria-label={t("testSuiteEditor.removeTurn", "Remove turn")}
            className="rounded p-1.5 text-muted-foreground hover:bg-secondary hover:text-destructive"
          >
            <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
          </button>
        )}
      </div>
    </div>
  );
}

// ─── Case ───────────────────────────────────────────────────────────────────

function CaseEditor({
  value,
  onChange,
  onRemove,
  readOnly,
  defaultOpen,
}: {
  value: SuiteCase;
  onChange: (c: SuiteCase) => void;
  onRemove: () => void;
  readOnly?: boolean;
  defaultOpen: boolean;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(defaultOpen);
  const turns = value.turns ?? [];

  return (
    <div className="rounded-xl border border-border bg-card" data-testid="suite-case">
      <div className="flex items-center gap-2 p-3">
        <button
          type="button"
          onClick={() => setOpen(!open)}
          aria-expanded={open}
          aria-label={t("testSuiteEditor.toggleCase", "Show or hide case")}
          className="rounded p-1 text-muted-foreground hover:bg-secondary"
        >
          {open ? <ChevronDown className="h-4 w-4" aria-hidden="true" /> : <ChevronRight className="h-4 w-4" aria-hidden="true" />}
        </button>
        <MessageSquare className="h-4 w-4 text-primary" aria-hidden="true" />
        <input
          type="text"
          aria-label={t("testSuiteEditor.caseName", "Case name")}
          value={value.name ?? ""}
          readOnly={readOnly}
          placeholder={t("testSuiteEditor.caseNamePlaceholder", "e.g. greeting then availability")}
          onChange={(e) => onChange({ ...value, name: e.target.value })}
          className={`${INPUT_CLASS} flex-1 font-medium`}
          data-testid="case-name"
        />
        <span className="shrink-0 text-xs text-muted-foreground">
          {t("testSuiteEditor.turnCount", { n: turns.length, defaultValue: "Turns: {{n}}" })}
        </span>
        {!readOnly && (
          <button
            type="button"
            onClick={onRemove}
            aria-label={t("testSuiteEditor.removeCase", "Remove case")}
            className="rounded p-1.5 text-muted-foreground hover:bg-secondary hover:text-destructive"
          >
            <Trash2 className="h-4 w-4" aria-hidden="true" />
          </button>
        )}
      </div>
      {open && (
        <div className="space-y-3 border-t border-border p-3">
          <div className="grid gap-3 sm:grid-cols-2">
            <LabelledField
              label={t("testSuiteEditor.caseRepetitions", "Repetitions")}
              hint={t("testSuiteEditor.caseRepetitionsHint", "1–20. Blank uses the suite default.")}
            >
              {(id) => (
                <OptionalNumberField
                  id={id}
                  integer
                  min={1}
                  max={20}
                  value={value.repetitions}
                  readOnly={readOnly}
                  onChange={(v) => onChange({ ...value, repetitions: v })}
                  testId="case-repetitions"
                />
              )}
            </LabelledField>
            <LabelledField
              label={t("testSuiteEditor.casePassRate", "Pass rate")}
              hint={t("testSuiteEditor.casePassRateHint", "Share of repetitions that must pass, 0–1")}
            >
              {(id) => (
                <OptionalNumberField
                  id={id}
                  min={0}
                  max={1}
                  step={0.05}
                  value={value.passRate}
                  readOnly={readOnly}
                  onChange={(v) => onChange({ ...value, passRate: v })}
                  testId="case-pass-rate"
                />
              )}
            </LabelledField>
          </div>
          <LabelledField
            label={t("testSuiteEditor.caseContext", "Context (JSON object)")}
            hint={t("testSuiteEditor.caseContextHint", "Sent with every turn of this case")}
          >
            {(id) => (
              <JsonField
                id={id}
                objectOnly
                value={value.context}
                readOnly={readOnly}
                placeholder='{"language":"en"}'
                onChange={(v) => onChange({ ...value, context: v as Record<string, unknown> | undefined })}
              />
            )}
          </LabelledField>
          <div className="space-y-2">
            {turns.map((turn, i) => (
              <TurnEditor
                key={i}
                turn={turn}
                index={i}
                readOnly={readOnly}
                onChange={(next) => onChange({ ...value, turns: replaceAt(turns, i, next) })}
                onRemove={() => onChange({ ...value, turns: turns.filter((_, j) => j !== i) })}
              />
            ))}
            {!readOnly && (
              <button
                type="button"
                onClick={() => onChange({ ...value, turns: [...turns, { input: "" }] })}
                className="inline-flex items-center gap-1 text-xs font-medium text-primary hover:underline"
                data-testid="add-turn"
              >
                <Plus className="h-3 w-3" aria-hidden="true" />
                {t("testSuiteEditor.addTurn", "Add turn")}
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Tool mock ──────────────────────────────────────────────────────────────

function MockEditor({
  mock,
  onChange,
  onRemove,
  readOnly,
}: {
  mock: ToolMock;
  onChange: (m: ToolMock) => void;
  onRemove: () => void;
  readOnly?: boolean;
}) {
  const { t } = useTranslation();
  return (
    <div className="rounded-lg border border-border bg-background/50 p-3" data-testid="suite-mock">
      <div className="flex flex-wrap items-start gap-2">
        <LabelledField label={t("testSuiteEditor.mockTool", "Tool")} className="w-44">
          {(id) => (
            <input
              id={id}
              type="text"
              className={INPUT_CLASS}
              value={mock.tool ?? ""}
              readOnly={readOnly}
              placeholder="getAvailability"
              onChange={(e) => onChange({ ...mock, tool: e.target.value })}
              data-testid="mock-tool"
            />
          )}
        </LabelledField>
        <LabelledField label={t("testSuiteEditor.mockHttpCode", "HTTP code")} className="w-24">
          {(id) => (
            <OptionalNumberField
              id={id}
              integer
              min={100}
              max={599}
              value={mock.response?.httpCode}
              readOnly={readOnly}
              placeholder="200"
              onChange={(v) => onChange({ ...mock, response: dropEmpty({ ...mock.response, httpCode: v }) ?? {} })}
            />
          )}
        </LabelledField>
        <LabelledField
          label={t("testSuiteEditor.mockArgs", "Only when arguments contain")}
          className="min-w-40 flex-1"
        >
          {(id) => (
            <JsonField
              id={id}
              objectOnly
              rows={1}
              value={mock.argsSubset}
              readOnly={readOnly}
              placeholder='{"city":"Vienna"}'
              onChange={(v) => onChange({ ...mock, argsSubset: v as Record<string, unknown> | undefined })}
            />
          )}
        </LabelledField>
        {!readOnly && (
          <button
            type="button"
            onClick={onRemove}
            aria-label={t("testSuiteEditor.removeMock", "Remove mock")}
            className="mt-5 rounded p-1.5 text-muted-foreground hover:bg-secondary hover:text-destructive"
          >
            <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
          </button>
        )}
      </div>
      <LabelledField
        label={t("testSuiteEditor.mockBody", "Response body (JSON)")}
        hint={t("testSuiteEditor.mockBodyHint", 'A text body needs quotes, for example "forbidden".')}
        className="mt-2"
      >
        {(id) => (
          <JsonField
            id={id}
            rows={2}
            value={mock.response?.body}
            readOnly={readOnly}
            placeholder='{"rooms":3}'
            onChange={(v) => onChange({ ...mock, response: dropEmpty({ ...mock.response, body: v }) ?? {} })}
          />
        )}
      </LabelledField>
    </div>
  );
}

// ─── Main ───────────────────────────────────────────────────────────────────

export function TestSuiteEditor({ data, onChange, readOnly }: TestSuiteEditorProps) {
  const { t } = useTranslation();
  const cases = data.cases ?? [];
  const mocks = data.toolMocks ?? [];
  const defaults = data.defaults ?? {};
  const setDefaults = (patch: Partial<NonNullable<AgentTestSuite["defaults"]>>) =>
    onChange({ ...data, defaults: dropEmpty({ ...defaults, ...patch }) });

  return (
    <div className="space-y-6" data-testid="test-suite-editor">
      <EditorSection label={t("testSuiteEditor.identity", "Suite")} icon={FlaskConical} accent="text-lime-600">
        <div className="grid gap-3 sm:grid-cols-2">
          <LabelledField label={t("testSuiteEditor.name", "Name")}>
            {(id) => (
              <input
                id={id}
                type="text"
                className={INPUT_CLASS}
                value={data.name ?? ""}
                readOnly={readOnly}
                onChange={(e) => onChange({ ...data, name: e.target.value })}
                data-testid="suite-name"
              />
            )}
          </LabelledField>
          <LabelledField
            label={t("testSuiteEditor.maxCostPerRun", "Cost ceiling per run (USD)")}
            hint={t("testSuiteEditor.maxCostPerRunHint", "The run stops and fails when its total cost reaches this. Default 1.0, at most 100.")}
          >
            {(id) => (
              <OptionalNumberField
                id={id}
                min={0}
                max={100}
                value={data.maxCostPerRun}
                readOnly={readOnly}
                placeholder="1.0"
                onChange={(v) => onChange({ ...data, maxCostPerRun: v })}
                testId="suite-max-cost"
              />
            )}
          </LabelledField>
          <LabelledField label={t("testSuiteEditor.description", "Description")} className="sm:col-span-2">
            {(id) => (
              <textarea
                id={id}
                rows={2}
                value={data.description ?? ""}
                readOnly={readOnly}
                onChange={(e) => onChange({ ...data, description: e.target.value })}
                className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-xs text-foreground focus:outline-none focus:ring-1 focus:ring-ring"
              />
            )}
          </LabelledField>
        </div>
      </EditorSection>

      <EditorSection label={t("testSuiteEditor.defaults", "Defaults for every case")} defaultOpen={false} icon={Wrench} accent="text-sky-500">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <LabelledField
            label={t("testSuiteEditor.toolMode", "Tool mode")}
            hint={t("testSuiteEditor.toolModeHint", "mock: calls get their mock and never run. live: every call runs for real.")}
          >
            {(id) => (
              <select
                id={id}
                className={INPUT_CLASS}
                value={defaults.toolMode ?? ""}
                disabled={readOnly}
                onChange={(e) => setDefaults({ toolMode: (e.target.value || undefined) as ToolMode | undefined })}
                data-testid="default-tool-mode"
              >
                <option value="">{t("testSuiteEditor.defaultMock", "mock (default)")}</option>
                <option value="mock">mock</option>
                <option value="live">live</option>
              </select>
            )}
          </LabelledField>
          <LabelledField
            label={t("testSuiteEditor.unmockedTools", "Tool without a mock")}
            hint={t("testSuiteEditor.unmockedToolsHint", "fail: not executed and the case fails. live: runs for real.")}
          >
            {(id) => (
              <select
                id={id}
                className={INPUT_CLASS}
                value={defaults.unmockedTools ?? ""}
                disabled={readOnly}
                onChange={(e) => setDefaults({ unmockedTools: (e.target.value || undefined) as UnmockedToolsPolicy | undefined })}
                data-testid="default-unmocked-tools"
              >
                <option value="">{t("testSuiteEditor.defaultFail", "fail (default)")}</option>
                <option value="fail">fail</option>
                <option value="live">live</option>
              </select>
            )}
          </LabelledField>
          <LabelledField label={t("testSuiteEditor.turnTimeout", "Turn timeout (seconds)")} hint={t("testSuiteEditor.turnTimeoutHint", "1–900, default 120")}>
            {(id) => (
              <OptionalNumberField id={id} integer min={1} max={900} value={defaults.turnTimeoutSeconds} readOnly={readOnly} onChange={(v) => setDefaults({ turnTimeoutSeconds: v })} />
            )}
          </LabelledField>
          <LabelledField label={t("testSuiteEditor.defaultRepetitions", "Repetitions")} hint={t("testSuiteEditor.defaultRepetitionsHint", "1–20, default 1")}>
            {(id) => (
              <OptionalNumberField id={id} integer min={1} max={20} value={defaults.repetitions} readOnly={readOnly} onChange={(v) => setDefaults({ repetitions: v })} />
            )}
          </LabelledField>
          <LabelledField label={t("testSuiteEditor.defaultPassRate", "Pass rate")} hint={t("testSuiteEditor.defaultPassRateHint", "0–1, default 1.0. 5 repetitions at 0.8 means at least 4 must pass.")}>
            {(id) => (
              <OptionalNumberField id={id} min={0} max={1} step={0.05} value={defaults.passRate} readOnly={readOnly} onChange={(v) => setDefaults({ passRate: v })} />
            )}
          </LabelledField>
        </div>
      </EditorSection>

      <EditorSection label={t("testSuiteEditor.toolMocks", "Tool mocks")} defaultOpen={mocks.length > 0} icon={Wrench} accent="text-ochre-500">
        <p className="mb-3 text-xs text-muted-foreground">
          {t(
            "testSuiteEditor.toolMocksHelp",
            "A test never reaches a real endpoint unless the suite says so. A call that has a mock gets its response; a call without one is refused and fails the case."
          )}
        </p>
        <div className="space-y-2">
          {mocks.map((m, i) => (
            <MockEditor
              key={i}
              mock={m}
              readOnly={readOnly}
              onChange={(next) => onChange({ ...data, toolMocks: replaceAt(mocks, i, next) })}
              onRemove={() => {
                const next = mocks.filter((_, j) => j !== i);
                onChange({ ...data, toolMocks: next.length > 0 ? next : undefined });
              }}
            />
          ))}
          {!readOnly && (
            <button
              type="button"
              onClick={() => onChange({ ...data, toolMocks: [...mocks, { tool: "", response: { httpCode: 200 } }] })}
              className="inline-flex items-center gap-1 text-xs font-medium text-primary hover:underline"
              data-testid="add-mock"
            >
              <Plus className="h-3 w-3" aria-hidden="true" />
              {t("testSuiteEditor.addMock", "Add tool mock")}
            </button>
          )}
        </div>
      </EditorSection>

      <EditorSection label={t("testSuiteEditor.cases", "Conversations")} icon={MessageSquare} accent="text-primary">
        <div className="space-y-3">
          {cases.length === 0 && (
            <p className="text-xs italic text-muted-foreground">
              {t("testSuiteEditor.noCases", "No conversations yet. A suite needs at least one case to run.")}
            </p>
          )}
          {cases.map((c, i) => (
            <CaseEditor
              key={i}
              value={c}
              readOnly={readOnly}
              defaultOpen={cases.length === 1}
              onChange={(next) => onChange({ ...data, cases: replaceAt(cases, i, next) })}
              onRemove={() => onChange({ ...data, cases: cases.filter((_, j) => j !== i) })}
            />
          ))}
          {!readOnly && (
            <button
              type="button"
              onClick={() => onChange({ ...data, cases: [...cases, { name: "", turns: [{ input: "" }] }] })}
              className="inline-flex items-center gap-1 rounded-md border border-dashed border-input px-3 py-1.5 text-xs font-medium text-primary hover:bg-secondary"
              data-testid="add-case"
            >
              <Plus className="h-3 w-3" aria-hidden="true" />
              {t("testSuiteEditor.addCase", "Add conversation")}
            </button>
          )}
        </div>
      </EditorSection>
    </div>
  );
}
