import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Wand2 } from "lucide-react";
import { NumberInput } from "@/components/editors/number-input";
import { ChipInput } from "@/components/shared/chip-input";
import { Button } from "@/components/ui/button";
import type { CatalogParam } from "@/lib/llm-parameter-catalog";
import { cn } from "@/lib/utils";
import {
  jsonProblem,
  numberProblem,
  parseStringList,
  serializeStringList,
} from "./model-settings-utils";

const FIELD =
  "h-8 w-full rounded-md border border-input bg-background px-2 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-60 aria-[invalid=true]:border-destructive";

export interface ParameterControlProps {
  param: CatalogParam;
  /** The stored value; undefined when the key is absent. */
  value: string | undefined;
  /** `undefined` removes the key, so the provider default applies. */
  onChange: (value: string | undefined) => void;
  /** Inherited value shown as the placeholder (a cascade step inherits the task's). */
  inherited?: string;
  disabled?: boolean;
  readOnly?: boolean;
  /** Enum values the configured model rejects; offered, but marked and disabled. */
  rejectedOptions?: ReadonlySet<string>;
  id: string;
  /** The visible label's id, for controls a `<label htmlFor>` cannot name (the radio group). */
  labelId?: string;
  describedBy?: string;
  testId: string;
}

/**
 * The typed control for one catalog parameter. Values stay strings, exactly as
 * the task's `parameters` map stores them; clearing a control removes the key.
 */
export function ParameterControl(props: ParameterControlProps) {
  switch (props.param.type) {
    case "double":
    case "int":
      return <NumericControl {...props} />;
    case "boolean":
      return <BooleanControl {...props} />;
    case "enum":
      return <EnumControl {...props} />;
    case "stringList":
      return <StringListControl {...props} />;
    case "json":
      return <JsonControl {...props} />;
    default:
      return <StringControl {...props} />;
  }
}

function useDefaultPlaceholder(param: CatalogParam, inherited?: string): string {
  const { t } = useTranslation();
  if (inherited !== undefined && inherited.trim() !== "") return inherited;
  return param.default ?? t("llmEditor.modelSettings.providerDefault", "Provider default");
}

function NumberProblemText({ param, value, id }: { param: CatalogParam; value: string; id: string }) {
  const { t } = useTranslation();
  const problem = numberProblem(value, param);
  if (!problem) return null;
  const text =
    problem === "notNumber"
      ? t("llmEditor.modelSettings.notNumber", "Enter a number.")
      : problem === "notInteger"
        ? t("llmEditor.modelSettings.notInteger", "Enter a whole number.")
        : problem === "belowMin"
          ? t("llmEditor.modelSettings.belowMin", "Must be at least {{min}}.", { min: param.min })
          : t("llmEditor.modelSettings.aboveMax", "Must be at most {{max}}.", { max: param.max });
  return (
    <p id={id} className="text-[11px] text-destructive dark:text-red-400" role="alert">
      {text}
    </p>
  );
}

function NumericControl({ param, value, onChange, inherited, disabled, readOnly, id, describedBy, testId }: ParameterControlProps) {
  const placeholder = useDefaultPlaceholder(param, inherited);
  const raw = value ?? "";
  const integer = param.type === "int";
  const parsed = raw.trim() === "" ? undefined : Number(raw.trim());
  const errorId = `${id}-error`;
  const problem = numberProblem(raw, param);
  const describe = [describedBy, problem ? errorId : undefined].filter(Boolean).join(" ") || undefined;

  // A stored value that is not a number at all ("hot") cannot live in a number
  // field, which would show it as empty; keep it visible as text so it can be fixed.
  const unparseable = parsed !== undefined && !Number.isFinite(parsed);
  const step = integer ? 1 : param.max !== undefined && param.min !== undefined && param.max - param.min <= 2 ? 0.05 : "any";

  return (
    <div className="space-y-1">
      {unparseable ? (
        <input
          id={id}
          type="text"
          value={raw}
          onChange={(e) => onChange(e.target.value === "" ? undefined : e.target.value)}
          readOnly={readOnly}
          disabled={disabled}
          aria-invalid
          aria-describedby={describe}
          className={FIELD}
          data-testid={testId}
        />
      ) : (
        <NumberInput
          id={id}
          value={parsed}
          integer={integer}
          min={param.min}
          max={param.max}
          step={step}
          onChange={(n) => onChange(n === undefined ? undefined : String(n))}
          readOnly={readOnly}
          disabled={disabled}
          placeholder={placeholder}
          aria-describedby={describe}
          aria-invalid={problem ? true : undefined}
          className={FIELD}
          data-testid={testId}
        />
      )}
      <NumberProblemText param={param} value={raw} id={errorId} />
    </div>
  );
}

function StringControl({ param, value, onChange, inherited, disabled, readOnly, id, describedBy, testId }: ParameterControlProps) {
  const placeholder = useDefaultPlaceholder(param, inherited);
  return (
    <input
      id={id}
      type="text"
      value={value ?? ""}
      onChange={(e) => onChange(e.target.value === "" ? undefined : e.target.value)}
      readOnly={readOnly}
      disabled={disabled}
      placeholder={placeholder}
      aria-describedby={describedBy}
      className={cn(FIELD, "font-mono")}
      dir="ltr"
      data-testid={testId}
    />
  );
}

/**
 * Three states, because "unset" is a real choice: it leaves the provider's own
 * default in place, which is not always the same as either explicit value.
 */
function BooleanControl({ param, value, onChange, inherited, disabled, readOnly, id, labelId, describedBy, testId }: ParameterControlProps) {
  const { t } = useTranslation();
  const normalised = value?.trim().toLowerCase();
  const current = normalised === "true" ? "true" : normalised === "false" ? "false" : "";
  const invalid = value !== undefined && value.trim() !== "" && current === "";
  const inheritedBool = inherited?.trim().toLowerCase();
  const defaultHint =
    inheritedBool === "true" || inheritedBool === "false"
      ? inheritedBool
      : param.default;
  const options: { value: "" | "true" | "false"; label: string }[] = [
    {
      value: "",
      label:
        defaultHint === "true"
          ? t("llmEditor.modelSettings.defaultOn", "Default (on)")
          : defaultHint === "false"
            ? t("llmEditor.modelSettings.defaultOff", "Default (off)")
            : t("llmEditor.modelSettings.providerDefault", "Provider default"),
    },
    { value: "true", label: t("llmEditor.modelSettings.on", "On") },
    { value: "false", label: t("llmEditor.modelSettings.off", "Off") },
  ];
  const locked = disabled || readOnly;
  return (
    <div className="space-y-1">
      <div
        id={id}
        role="radiogroup"
        aria-labelledby={labelId}
        aria-describedby={describedBy}
        aria-invalid={invalid || undefined}
        className={cn(
          "inline-flex rounded-md border border-input bg-background p-0.5",
          locked && "opacity-60",
        )}
        data-testid={testId}
      >
        {options.map((option) => (
          <label key={option.value || "default"} className="relative">
            <input
              type="radio"
              name={id}
              value={option.value}
              checked={current === option.value && !invalid}
              disabled={locked}
              onChange={() => onChange(option.value === "" ? undefined : option.value)}
              className="peer sr-only"
              data-testid={`${testId}-${option.value || "default"}`}
            />
            <span
              className={cn(
                "inline-flex h-7 cursor-pointer items-center rounded px-2.5 text-[11px] font-medium text-muted-foreground transition-colors",
                "peer-checked:bg-primary/15 peer-checked:text-foreground",
                "peer-focus-visible:ring-2 peer-focus-visible:ring-ring",
                "peer-disabled:cursor-not-allowed",
                !locked && "hover:text-foreground",
              )}
            >
              {option.label}
            </span>
          </label>
        ))}
      </div>
      {invalid && (
        <p className="text-[11px] text-destructive dark:text-red-400" role="alert">
          {t("llmEditor.modelSettings.notBoolean", "“{{value}}” is neither true nor false; pick one.", { value })}
        </p>
      )}
    </div>
  );
}

function EnumControl({ param, value, onChange, inherited, disabled, readOnly, rejectedOptions, id, describedBy, testId }: ParameterControlProps) {
  const { t } = useTranslation();
  const options = param.enum ?? [];
  const trimmed = value?.trim() ?? "";
  // The backend matches enum values case-insensitively; show the canonical spelling.
  const canonical = options.find((o) => o.toLowerCase() === trimmed.toLowerCase());
  const unknown = trimmed !== "" && canonical === undefined;
  const fallback =
    inherited !== undefined && inherited.trim() !== ""
      ? t("llmEditor.modelSettings.inherited", "Inherited: {{value}}", { value: inherited })
      : param.default !== undefined
        ? t("llmEditor.modelSettings.defaultValueOption", "Default ({{value}})", { value: param.default })
        : t("llmEditor.modelSettings.providerDefault", "Provider default");
  return (
    <div className="space-y-1">
      <select
        id={id}
        value={unknown ? trimmed : (canonical ?? "")}
        onChange={(e) => onChange(e.target.value === "" ? undefined : e.target.value)}
        disabled={disabled || readOnly}
        aria-describedby={describedBy}
        aria-invalid={unknown || undefined}
        className={cn(FIELD, "font-mono")}
        data-testid={testId}
      >
        <option value="">{fallback}</option>
        {unknown && <option value={trimmed}>{trimmed}</option>}
        {options.map((option) => {
          const rejected = rejectedOptions?.has(option) ?? false;
          return (
            <option key={option} value={option} disabled={rejected && option !== canonical}>
              {rejected
                ? t("llmEditor.modelSettings.optionRejected", "{{value}} (rejected by this model)", { value: option })
                : option}
            </option>
          );
        })}
      </select>
      {unknown && (
        <p className="text-[11px] text-destructive dark:text-red-400" role="alert">
          {t("llmEditor.modelSettings.unknownOption", "“{{value}}” is not one of the accepted values.", { value: trimmed })}
        </p>
      )}
    </div>
  );
}

function StringListControl({ param, value, onChange, inherited, disabled, readOnly, id, testId }: ParameterControlProps) {
  const { t } = useTranslation();
  const [pending, setPending] = useState("");
  const { items, invalid } = parseStringList(value);
  if (invalid) {
    return (
      <div className="space-y-1">
        <input
          id={id}
          type="text"
          value={value ?? ""}
          onChange={(e) => onChange(e.target.value === "" ? undefined : e.target.value)}
          readOnly={readOnly}
          disabled={disabled}
          aria-invalid
          className={cn(FIELD, "font-mono")}
          data-testid={`${testId}-raw`}
        />
        <p className="text-[11px] text-destructive dark:text-red-400" role="alert">
          {t("llmEditor.modelSettings.listInvalid", "Not a JSON array of strings or a comma-separated list.")}
        </p>
      </div>
    );
  }
  const inheritedItems = parseStringList(inherited).items;
  return (
    <div className={cn(disabled && "pointer-events-none opacity-60")}>
      <ChipInput
        values={items}
        onChange={(next) => onChange(serializeStringList(next))}
        pending={pending}
        onPendingChange={setPending}
        readOnly={readOnly || disabled}
        inputLabel={t("llmEditor.modelSettings.addValueTo", "Add a value to {{label}}", { label: param.label })}
        placeholder={
          inheritedItems.length > 0
            ? t("llmEditor.modelSettings.inherited", "Inherited: {{value}}", { value: inheritedItems.join(", ") })
            : t("llmEditor.modelSettings.addValue", "Type a value and press Enter")
        }
        testId={testId}
        ltr
      />
    </div>
  );
}

function JsonControl({ param, value, onChange, inherited, disabled, readOnly, id, describedBy, testId }: ParameterControlProps) {
  const { t } = useTranslation();
  const raw = value ?? "";
  const problem = jsonProblem(raw);
  const errorId = `${id}-error`;
  const placeholder = inherited?.trim()
    ? inherited
    : param.key === "plugins"
      ? '[{"id":"web"}]'
      : "{}";
  return (
    <div className="space-y-1">
      <textarea
        id={id}
        value={raw}
        onChange={(e) => onChange(e.target.value === "" ? undefined : e.target.value)}
        readOnly={readOnly}
        disabled={disabled}
        rows={Math.min(8, Math.max(2, raw.split("\n").length))}
        spellCheck={false}
        placeholder={placeholder}
        aria-invalid={problem ? true : undefined}
        aria-describedby={[describedBy, problem ? errorId : undefined].filter(Boolean).join(" ") || undefined}
        className={cn(FIELD, "h-auto py-1.5 font-mono leading-relaxed")}
        dir="ltr"
        data-testid={testId}
      />
      <div className="flex items-center gap-2">
        {problem && (
          <p id={errorId} className="flex-1 text-[11px] text-destructive dark:text-red-400" role="alert" data-testid={`${testId}-error`}>
            {problem === "syntax"
              ? t("llmEditor.modelSettings.jsonSyntax", "Not valid JSON yet.")
              : t("llmEditor.modelSettings.jsonShape", "Must be a JSON object or array.")}
          </p>
        )}
        {!problem && raw.trim() !== "" && !readOnly && !disabled && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="ms-auto h-6 px-2 text-[11px]"
            onClick={() => onChange(JSON.stringify(JSON.parse(raw), null, 2))}
            data-testid={`${testId}-format`}
          >
            <Wand2 aria-hidden="true" />
            {t("llmEditor.modelSettings.formatJson", "Format")}
          </Button>
        )}
      </div>
    </div>
  );
}
